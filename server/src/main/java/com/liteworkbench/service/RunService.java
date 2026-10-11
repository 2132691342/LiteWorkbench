package com.liteworkbench.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.agent.AgentRuntime;
import com.liteworkbench.agent.ModelFactory;
import com.liteworkbench.agent.RunContext;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.MessageRoleEnum;
import com.liteworkbench.domain.constant.RedisKeyConst;
import com.liteworkbench.domain.constant.RunConst;
import com.liteworkbench.domain.constant.RunStatusEnum;
import com.liteworkbench.domain.constant.SessionConst;
import com.liteworkbench.domain.constant.StopReasonEnum;
import com.liteworkbench.domain.dto.ChatStreamREQ;
import com.liteworkbench.domain.dto.RunEventDTO;
import com.liteworkbench.domain.dto.SessionPageREQ;
import com.liteworkbench.domain.entity.WbEventDO;
import com.liteworkbench.domain.entity.WbMessageDO;
import com.liteworkbench.domain.entity.WbRunDO;
import com.liteworkbench.domain.vo.RunVO;
import com.liteworkbench.mapper.WbEventMapper;
import com.liteworkbench.mapper.WbMessageMapper;
import com.liteworkbench.mapper.WbRunMapper;
import com.liteworkbench.mapper.WbSessionMapper;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 运行主链路：SSE 流式对话、事件攒批落库、中断与回放 */
@Slf4j
@Service
public class RunService {

    /** 历史消息条数上限 */
    private static final int HISTORY_LIMIT = 20;
    /** 回放单页事件上限 */
    private static final long REPLAY_PAGE_SIZE = 500L;

    private final AgentRuntime agentRuntime;
    private final ModelFactory modelFactory;
    private final SessionService sessionService;
    private final SkillService skillService;
    private final AgentProperties agentProperties;
    private final WbRunMapper runMapper;
    private final WbEventMapper eventMapper;
    private final WbMessageMapper messageMapper;
    private final WbSessionMapper sessionMapper;
    private final StringRedisTemplate redisTemplate;

    /** 进行中的运行：runId -> 活动实例 */
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    /** SSE 心跳调度器 */
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lw-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RunService(AgentRuntime agentRuntime, ModelFactory modelFactory, SessionService sessionService,
                      SkillService skillService, AgentProperties agentProperties, WbRunMapper runMapper,
                      WbEventMapper eventMapper, WbMessageMapper messageMapper, WbSessionMapper sessionMapper,
                      StringRedisTemplate redisTemplate) {
        this.agentRuntime = agentRuntime;
        this.modelFactory = modelFactory;
        this.sessionService = sessionService;
        this.skillService = skillService;
        this.agentProperties = agentProperties;
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.redisTemplate = redisTemplate;
    }

    /** 流式对话：锁会话 → 落运行与用户消息 → 订阅事件流（攒批落库 + 推送）→ 终态收尾 */
    public SseEmitter chatStream(ChatStreamREQ req) {
        String userId = LoginContext.userId();
        var session = sessionService.getOwned(req.getSessionId(), userId);

        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                RedisKeyConst.lockRun(session.getSessionId()), "1",
                Duration.ofSeconds(SessionConst.LOCK_TTL_SECONDS));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BizException(ErrorCodeEnum.SESSION_BUSY);
        }
        String runId = IdGenerator.generate(IdPrefixConst.RUN);
        try {
            String providerCode = StrUtil.blankToDefault(session.getProviderCode(), agentProperties.getProvider());
            var provider = modelFactory.loadProvider(providerCode);
            String modelName = modelFactory.resolveModelName(provider, session.getModelName());

            insertRun(runId, userId, session.getSessionId(), req.getContent(), modelName);
            insertMessage(session.getSessionId(), runId, MessageRoleEnum.USER, req.getContent());
            runMapper.markRunning(runId);
            sessionMapper.updateStats(session.getSessionId(), 1, 1, 0);

            String systemPrompt = skillService.assembleSystemPrompt(
                    userId, session.getSystemPrompt(), req.getContent());
            RunContext context = new RunContext(userId, session.getSessionId(), runId,
                    providerCode, modelName, systemPrompt, loadHistory(session.getSessionId()),
                    req.getContent());

            SseEmitter emitter = new SseEmitter(RunConst.SSE_TIMEOUT_MS);
            ActiveRun run = new ActiveRun(runId, userId, session.getSessionId(), emitter);
            active.put(runId, run);
            registerEmitterCallbacks(run);
            subscribe(run, context);
            log.info("[run.start] userId={} sessionId={} runId={} model={}",
                    userId, session.getSessionId(), runId, modelName);
            return emitter;
        } catch (RuntimeException e) {
            redisTemplate.delete(RedisKeyConst.lockRun(session.getSessionId()));
            active.remove(runId);
            throw e;
        }
    }

    /** 运行分页 */
    public PageResult<RunVO> page(SessionPageREQ req) {
        long total = runMapper.countPage(req);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<RunVO> list = runMapper.selectPage(req, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 运行详情 */
    public RunVO detail(String runId) {
        return toVO(getOwned(runId));
    }

    /** 事件回放，支持 afterSeq 增量 */
    public List<RunEventDTO> events(String runId, long afterSeq) {
        getOwned(runId);
        return eventMapper.selectByRunAfterSeq(runId, afterSeq, REPLAY_PAGE_SIZE)
                .stream().map(this::toEventDTO).toList();
    }

    /** 中断运行：写标记 → dispose 订阅 → 取消信号统一走 settleAborted */
    public void abort(String runId) {
        WbRunDO run = getOwned(runId);
        if (RunStatusEnum.isTerminal(run.getStatus())) {
            throw new BizException(ErrorCodeEnum.RUN_NOT_ABORTABLE, run.getStatus());
        }
        redisTemplate.opsForValue().set(RedisKeyConst.runAbort(runId), "1",
                Duration.ofSeconds(RunConst.ABORT_TTL_SECONDS));
        ActiveRun activeRun = active.remove(runId);
        if (Objects.nonNull(activeRun)) {
            activeRun.disposable.dispose();
            return;
        }
        // 无活动实例（如进程重启后的孤儿运行）：直接落终态
        settleTerminal(run, RunStatusEnum.ABORTED.getCode(), StopReasonEnum.ABORTED.getCode(),
                run.getIterCount(), new long[2]);
        log.info("[run.abort] runId={} 孤儿运行直接置终态", runId);
    }

    /** 订阅事件流：windowTimeout 攒批（100 条或 200ms），先落库后推送 */
    private void subscribe(ActiveRun run, RunContext context) {
        Disposable disposable = agentRuntime.stream(context)
                .windowTimeout(RunConst.EVENT_BATCH_SIZE, Duration.ofMillis(RunConst.EVENT_BATCH_INTERVAL_MS))
                .concatMap(window -> window.collectList())
                .doOnCancel(() -> settleAborted(run))
                .subscribe(
                        batch -> deliver(run, batch),
                        error -> settleError(run, error),
                        () -> settleSuccess(run));
        run.disposable = disposable;
        scheduleHeartbeat(run);
    }

    /** SSE 生命周期回调：完成 / 超时统一清理 */
    private void registerEmitterCallbacks(ActiveRun run) {
        run.emitter.onCompletion(() -> {
            if (run.settled.compareAndSet(false, true)) {
                if (Objects.nonNull(run.disposable)) {
                    run.disposable.dispose();
                }
            }
            active.remove(run.runId);
            cancelHeartbeat(run);
            releaseLock(run.sessionId);
        });
        run.emitter.onTimeout(() -> {
            log.warn("[run.timeout] runId={}", run.runId);
            run.emitter.complete();
        });
    }

    /** 单批事件：先持久化再推送，落库失败不中断输出 */
    private void deliver(ActiveRun run, List<RunEventDTO> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            eventMapper.insertBatch(batch.stream().map(this::toEventDO).toList());
        } catch (Exception e) {
            log.error("[run.event.persist] runId={} 落库失败 {}", run.runId, e.getMessage());
        }
        batch.forEach(event -> {
            run.observe(event);
            push(run, event);
        });
    }

    /** 推送 SSE 帧；客户端断开时中断运行 */
    private void push(ActiveRun run, RunEventDTO event) {
        if (run.settled.get()) {
            return;
        }
        try {
            run.emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.seq()))
                    .name("run.event")
                    .data(JSONUtil.toJsonStr(event), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.warn("[run.sse] runId={} 推送失败，中断运行 {}", run.runId, e.getMessage());
            if (Objects.nonNull(run.disposable)) {
                run.disposable.dispose();
            }
        }
    }

    /** 成功收尾：stop_reason 区分正常与达到迭代上限 */
    private void settleSuccess(ActiveRun run) {
        if (!run.settled.compareAndSet(false, true)) {
            return;
        }
        String stopReason = run.exceeded.get()
                ? StopReasonEnum.MAX_ITERS.getCode() : StopReasonEnum.COMPLETED.getCode();
        WbRunDO dbRun = runMapper.selectById(run.runId);
        if (Objects.nonNull(dbRun)) {
            settleTerminal(dbRun, RunStatusEnum.SUCCESS.getCode(), stopReason,
                    run.modelCalls, run.usage);
        }
        writeAssistantMessage(run);
        pushSynthetic(run, EventTypeEnum.RUN_END.getCode(), Map.of(
                "runId", run.runId,
                "status", RunStatusEnum.SUCCESS.getCode(),
                "stopReason", stopReason,
                "usage", run.usage));
        completeEmitter(run);
        log.info("[run.end] runId={} status={} cost={}ms", run.runId, RunStatusEnum.SUCCESS.getCode(),
                System.currentTimeMillis() - run.startedAt);
    }

    /** 失败收尾 */
    private void settleError(ActiveRun run, Throwable error) {
        if (!run.settled.compareAndSet(false, true)) {
            return;
        }
        log.error("[run.failed] runId={} error={}", run.runId, error.getMessage(), error);
        WbRunDO dbRun = runMapper.selectById(run.runId);
        if (Objects.nonNull(dbRun)) {
            settleTerminal(dbRun, RunStatusEnum.FAILED.getCode(), StopReasonEnum.ERROR.getCode(),
                    run.modelCalls, run.usage);
        }
        writeAssistantMessage(run);
        pushSynthetic(run, "error", Map.of(
                "code", ErrorCodeEnum.INTERNAL_ERROR.getCode(),
                "message", "运行失败，请查看日志"));
        completeEmitter(run);
    }

    /** 中断收尾：取消信号或 abort 触发 */
    private void settleAborted(ActiveRun run) {
        if (!run.settled.compareAndSet(false, true)) {
            return;
        }
        log.info("[run.aborted] runId={}", run.runId);
        WbRunDO dbRun = runMapper.selectById(run.runId);
        if (Objects.nonNull(dbRun)) {
            settleTerminal(dbRun, RunStatusEnum.ABORTED.getCode(), StopReasonEnum.ABORTED.getCode(),
                    run.modelCalls, run.usage);
        }
        writeAssistantMessage(run);
        pushSynthetic(run, EventTypeEnum.RUN_END.getCode(), Map.of(
                "runId", run.runId,
                "status", RunStatusEnum.ABORTED.getCode(),
                "stopReason", StopReasonEnum.ABORTED.getCode(),
                "usage", run.usage));
        completeEmitter(run);
        active.remove(run.runId);
        cancelHeartbeat(run);
        releaseLock(run.sessionId);
    }

    /** 运行终态与用量落库 */
    private void settleTerminal(WbRunDO run, String status, String stopReason, int iterCount, long[] usage) {
        WbRunDO terminal = new WbRunDO();
        terminal.setRunId(run.getRunId());
        terminal.setStatus(status);
        terminal.setStopReason(stopReason);
        terminal.setIterCount(Math.max(iterCount, 0));
        terminal.setInputTokens((int) usage[0]);
        terminal.setOutputTokens((int) usage[1]);
        long startMillis = Objects.nonNull(run.getStartedAt())
                ? LocalDateTimeUtil.toEpochMilli(run.getStartedAt()) : System.currentTimeMillis();
        terminal.setDurationMs(System.currentTimeMillis() - startMillis);
        runMapper.updateTerminal(terminal);
    }

    private void writeAssistantMessage(ActiveRun run) {
        String answer = run.answerText();
        if (StrUtil.isBlank(answer)) {
            return;
        }
        insertMessage(run.sessionId, run.runId, MessageRoleEnum.ASSISTANT, answer);
        sessionMapper.updateStats(run.sessionId, 1, 0, run.usage[0] + run.usage[1]);
    }

    private void pushSynthetic(ActiveRun run, String eventType, Map<String, Object> payload) {
        RunEventDTO event = new RunEventDTO(run.runId, run.sessionId,
                run.seqSnapshot() + 1, eventType, null, null, payload, System.currentTimeMillis());
        push(run, event);
    }

    private void completeEmitter(ActiveRun run) {
        try {
            run.emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开
        }
    }

    private void insertRun(String runId, String userId, String sessionId,
                           String prompt, String modelName) {
        WbRunDO run = new WbRunDO();
        run.setRunId(runId);
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setRelation("original");
        run.setPrompt(prompt);
        run.setModelName(modelName);
        run.setStatus(RunStatusEnum.QUEUED.getCode());
        runMapper.insert(run);
    }

    private void insertMessage(String sessionId, String runId, MessageRoleEnum role, String content) {
        WbMessageDO message = new WbMessageDO();
        message.setMessageId(IdGenerator.generate(IdPrefixConst.MESSAGE));
        message.setSessionId(sessionId);
        message.setRunId(runId);
        message.setRoleCode(role.getCode());
        message.setContent(content);
        message.setTokenCount(content.length() / 2);
        messageMapper.insert(message);
    }

    /** 近 N 条历史消息转 SDK 消息（时间正序） */
    private List<Msg> loadHistory(String sessionId) {
        List<WbMessageDO> recent = messageMapper.selectRecent(sessionId, HISTORY_LIMIT);
        if (recent.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.reverse(recent);
        List<Msg> messages = new ArrayList<>();
        recent.forEach(message -> {
            if (MessageRoleEnum.USER.getCode().equals(message.getRoleCode())) {
                messages.add(new UserMessage(StrUtil.nullToEmpty(message.getContent())));
            } else if (MessageRoleEnum.ASSISTANT.getCode().equals(message.getRoleCode())) {
                messages.add(new AssistantMessage(StrUtil.nullToEmpty(message.getContent())));
            }
        });
        return messages;
    }

    private void scheduleHeartbeat(ActiveRun run) {
        run.heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (run.settled.get()) {
                    cancelHeartbeat(run);
                    return;
                }
                run.emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                cancelHeartbeat(run);
                if (Objects.nonNull(run.disposable)) {
                    run.disposable.dispose();
                }
            }
        }, RunConst.SSE_HEARTBEAT_MS, RunConst.SSE_HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat(ActiveRun run) {
        if (Objects.nonNull(run.heartbeat)) {
            run.heartbeat.cancel(false);
        }
    }

    private void releaseLock(String sessionId) {
        try {
            redisTemplate.delete(RedisKeyConst.lockRun(sessionId));
        } catch (Exception e) {
            log.warn("[run.lock] 释放失败 sessionId={} {}", sessionId, e.getMessage());
        }
    }

    private WbRunDO getOwned(String runId) {
        WbRunDO run = runMapper.selectById(runId);
        if (Objects.isNull(run)) {
            throw new BizException(ErrorCodeEnum.RUN_NOT_FOUND, runId);
        }
        return run;
    }

    private WbEventDO toEventDO(RunEventDTO event) {
        WbEventDO entity = new WbEventDO();
        entity.setEventId(IdGenerator.generate(IdPrefixConst.EVENT));
        entity.setRunId(event.runId());
        entity.setSessionId(event.sessionId());
        entity.setSeq((int) event.seq());
        entity.setEventType(event.eventType());
        entity.setBlockType(event.blockType());
        entity.setTextDelta(event.textDelta());
        entity.setPayload(JSONUtil.toJsonStr(event.payload()));
        entity.setOccurredAt(LocalDateTimeUtil.of(event.occurredAt()));
        return entity;
    }

    @SuppressWarnings("unchecked")
    private RunEventDTO toEventDTO(WbEventDO entity) {
        return new RunEventDTO(entity.getRunId(), entity.getSessionId(),
                entity.getSeq(), entity.getEventType(), entity.getBlockType(), entity.getTextDelta(),
                StrUtil.isBlank(entity.getPayload()) ? Map.of()
                        : JSONUtil.toBean(entity.getPayload(), Map.class),
                Objects.nonNull(entity.getOccurredAt())
                        ? LocalDateTimeUtil.toEpochMilli(entity.getOccurredAt()) : 0L);
    }

    private RunVO toVO(WbRunDO run) {
        RunVO vo = new RunVO();
        vo.setRunId(run.getRunId());
        vo.setSessionId(run.getSessionId());
        vo.setPrompt(run.getPrompt());
        vo.setModelName(run.getModelName());
        vo.setStatus(run.getStatus());
        vo.setStopReason(run.getStopReason());
        vo.setIterCount(run.getIterCount());
        vo.setInputTokens(run.getInputTokens());
        vo.setOutputTokens(run.getOutputTokens());
        vo.setDurationMs(run.getDurationMs());
        vo.setStartedAt(Objects.isNull(run.getStartedAt()) ? null : LocalDateTimeUtil.toEpochMilli(run.getStartedAt()));
        vo.setEndedAt(Objects.isNull(run.getEndedAt()) ? null : LocalDateTimeUtil.toEpochMilli(run.getEndedAt()));
        vo.setCreatedAt(Objects.isNull(run.getCreatedAt()) ? null : LocalDateTimeUtil.toEpochMilli(run.getCreatedAt()));
        return vo;
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /** 单次活动运行：SSE 通道 + 订阅 + 观测累积 */
    private static final class ActiveRun {
        final String runId;
        final String userId;
        final String sessionId;
        final SseEmitter emitter;
        final AtomicBoolean settled = new AtomicBoolean(false);
        final AtomicBoolean exceeded = new AtomicBoolean(false);
        final StringBuilder answer = new StringBuilder();
        final long startedAt = System.currentTimeMillis();
        final long[] usage = new long[2];
        volatile long seq = RunConst.EVENT_SEQ_START - 1;
        volatile int modelCalls = 0;
        volatile Disposable disposable;
        volatile ScheduledFuture<?> heartbeat;

        ActiveRun(String runId, String userId, String sessionId, SseEmitter emitter) {
            this.runId = runId;
            this.userId = userId;
            this.sessionId = sessionId;
            this.emitter = emitter;
        }

        /** 从事件流提取答案文本、用量与迭代数 */
        void observe(RunEventDTO event) {
            if (event.seq() > seq) {
                seq = event.seq();
            }
            if (EventTypeEnum.TEXT_DELTA.getCode().equals(event.eventType())
                    && Objects.nonNull(event.textDelta())) {
                answer.append(event.textDelta());
            } else if (EventTypeEnum.RUN_RESULT.getCode().equals(event.eventType())) {
                Object text = event.payload().get("text");
                if (Objects.nonNull(text) && StrUtil.isNotBlank(text.toString())) {
                    answer.setLength(0);
                    answer.append(text);
                }
            } else if (EventTypeEnum.MODEL_CALL.getCode().equals(event.eventType())
                    && "end".equals(event.payload().get("phase"))) {
                modelCalls++;
                Object input = event.payload().get("inputTokens");
                Object output = event.payload().get("outputTokens");
                if (input instanceof Number in) {
                    usage[0] += in.longValue();
                }
                if (output instanceof Number out) {
                    usage[1] += out.longValue();
                }
            } else if (EventTypeEnum.RUN_EXCEED.getCode().equals(event.eventType())) {
                exceeded.set(true);
            }
        }

        String answerText() {
            return answer.toString();
        }

        long seqSnapshot() {
            return seq;
        }
    }
}
