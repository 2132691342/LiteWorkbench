package com.liteworkbench.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.agent.AgentRuntime;
import com.liteworkbench.agent.ModelFactory;
import com.liteworkbench.agent.RunContext;
import com.liteworkbench.agent.RunHandle;
import com.liteworkbench.agent.model.ModelTypes.Msg;
import com.liteworkbench.agent.model.ModelTypes.Role;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.domain.constant.AppConst;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.MessageRoleEnum;
import com.liteworkbench.domain.constant.RedisKeyConst;
import com.liteworkbench.domain.constant.RunConst;
import com.liteworkbench.domain.constant.RunStatusEnum;
import com.liteworkbench.domain.constant.SessionConst;
import com.liteworkbench.domain.constant.StopReasonEnum;
import com.liteworkbench.domain.dto.ChatStreamREQ;
import com.liteworkbench.domain.dto.MessageDocDTO;
import com.liteworkbench.domain.dto.RunEventDTO;
import com.liteworkbench.domain.dto.SessionPageREQ;
import com.liteworkbench.domain.entity.WbEventDO;
import com.liteworkbench.domain.entity.WbRunDO;
import com.liteworkbench.mapper.MessageIndexMapper;
import com.liteworkbench.mapper.WbEventMapper;
import com.liteworkbench.mapper.WbRunMapper;
import com.liteworkbench.mapper.WbSessionMapper;
import com.liteworkbench.domain.vo.RunVO;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 运行主链路：SSE 流式对话、事件攒批落库、取消与回放 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {

    /** 回放单页事件上限 */
    private static final long REPLAY_PAGE_SIZE = 500L;
    /** 攒批冲刷间隔，与 RunConst 的批内时间阈值一致 */
    private static final long FLUSH_INTERVAL_MS = RunConst.EVENT_BATCH_INTERVAL_MS;

    private final AgentRuntime agentRuntime;
    private final ModelFactory modelFactory;
    private final AgentProperties agentProperties;
    private final SessionService sessionService;
    private final SkillService skillService;
    private final WbRunMapper runMapper;
    private final WbEventMapper eventMapper;
    private final MessageIndexMapper messageIndexMapper;
    private final WbSessionMapper sessionMapper;
    private final StringRedisTemplate redisTemplate;

    /** 进行中的运行：runId -> 活动实例 */
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    /** SSE 心跳调度器 */
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(ThreadUtil.newNamedThreadFactory("lw-sse-heartbeat", true));
    /** 事件攒批冲刷调度器 */
    private final ScheduledExecutorService flushExecutor =
            Executors.newSingleThreadScheduledExecutor(ThreadUtil.newNamedThreadFactory("lw-sse-flush", true));
    /** 冲刷任务只登记一次 */
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    /** 流式对话：锁会话 → 落运行与用户消息 → 启动内核（攒批落库 + 推送）→ 终态收尾 */
    public String chatStream(ChatStreamREQ req, RunSink sink) {
        String userId = LoginContext.userId();
        var session = sessionService.getOwned(req.getSessionId(), userId);

        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                RedisKeyConst.lockRun(session.getSessionId()), "1",
                Duration.ofSeconds(SessionConst.LOCK_TTL_SECONDS));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BizException(ErrorCodeEnum.SESSION_BUSY);
        }
        String runId = IdGenerator.generate(IdPrefixConst.RUN);
        // 编排期日志携带会话与运行标识，与 agent 后台线程、冲刷/心跳任务同口径
        MDC.put(AppConst.MDC_SESSION_ID, session.getSessionId());
        MDC.put(AppConst.MDC_RUN_ID, runId);
        try {
            // 会话未指定供应方时取默认可用供应方，全新环境不会因缺配置直接失败
            var provider = StrUtil.isNotBlank(session.getProviderCode())
                    ? modelFactory.loadProvider(session.getProviderCode())
                    : modelFactory.resolveDefaultProvider();
            String providerCode = provider.getProviderCode();
            String modelName = modelFactory.resolveModelName(provider, session.getModelName());

            insertRun(runId, userId, session.getSessionId(), req.getContent(), modelName);
            insertMessage(userId, session.getSessionId(), runId, MessageRoleEnum.USER, req.getContent());
            runMapper.markRunning(runId);
            sessionMapper.updateStats(session.getSessionId(), 1, 1, 0);

            String systemPrompt = skillService.assembleSystemPrompt(
                    userId, session.getSystemPrompt(), req.getContent());
            RunContext context = new RunContext(userId, session.getSessionId(), runId,
                    providerCode, modelName, systemPrompt, loadHistory(session.getSessionId(), runId),
                    req.getContent());

            ActiveRun run = new ActiveRun(runId, userId, session.getSessionId(), sink);
            active.put(runId, run);
            // 首轮对话自动命名会话：运行结束后异步触发，静默失败不影响对话
            dispatch(run, context, () -> sessionService.autoRenameIfDefault(session, req.getContent()));
            log.info("[run.start] userId={} sessionId={} runId={} model={}",
                    userId, session.getSessionId(), runId, modelName);
            return runId;
        } catch (RuntimeException e) {
            // 启动失败也要落终态并发终态帧，不留 queued 僵尸行
            ActiveRun run = active.get(runId);
            if (Objects.nonNull(run)) {
                settleError(run, e);
            } else {
                markRunFailed(runId);
                releaseLock(session.getSessionId());
            }
            throw e;
        } finally {
            MDC.remove(AppConst.MDC_SESSION_ID);
            MDC.remove(AppConst.MDC_RUN_ID);
        }
    }

    /** 运行分页，按归属用户过滤 */
    public PageResult<RunVO> page(SessionPageREQ req) {
        String userId = LoginContext.userId();
        long total = runMapper.countPage(req, userId);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<RunVO> list = runMapper.selectPage(req, userId, req.offset(), req.pageSize())
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

    /** 中断运行：协作式取消，无活动实例的孤儿运行直接落终态 */
    public void abort(String runId) {
        WbRunDO run = getOwned(runId);
        if (RunStatusEnum.isTerminal(run.getState())) {
            throw new BizException(ErrorCodeEnum.RUN_NOT_ABORTABLE, run.getState());
        }
        ActiveRun activeRun = active.get(runId);
        if (Objects.nonNull(activeRun)) {
            activeRun.cancel();
            settleAborted(activeRun);
            log.info("[run.abort] runId={} 已下发取消", runId);
            return;
        }
        // 无活动实例（如进程重启后的孤儿运行）：直接落终态
        settleTerminal(run, RunStatusEnum.ABORTED.getCode(), StopReasonEnum.ABORTED.getCode(),
                run.getIterCount(), new long[2]);
        log.info("[run.abort] runId={} 孤儿运行直接置终态", runId);
    }

    /** 启动内核：事件入批缓冲，终态由句柄完成通知收尾，postSettle 在终态后触发（自动命名） */
    private void dispatch(ActiveRun run, RunContext context, Runnable postSettle) {
        run.handle = agentRuntime.start(context, run::offer);
        run.handle.completion().whenComplete((ignored, error) -> {
            try {
                if (Objects.nonNull(error)) {
                    settleError(run, error);
                } else {
                    settleSuccess(run);
                }
            } finally {
                try {
                    postSettle.run();
                } catch (Exception e) {
                    log.warn("[run.postSettle] runId={} {}", run.runId, e.getMessage());
                }
            }
        });
        scheduleHeartbeat(run);
        // 冲刷任务全进程一个，与运行数量无关
        if (flushScheduled.compareAndSet(false, true)) {
            flushExecutor.scheduleWithFixedDelay(this::flushAll, FLUSH_INTERVAL_MS,
                    FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** 通道关闭回调：由 api 层在 SSE 完成 / 超时 / 出错时调用，按中断收敛并释放运行占用 */
    public void onChannelClosed(String runId) {
        ActiveRun run = active.get(runId);
        if (Objects.isNull(run)) {
            return;
        }
        run.cancel();
        settleAborted(run);
    }

    /** 冲刷所有活动运行的批缓冲；已终态运行由收尾路径负责最终冲刷，跳过避免空转 */
    private void flushAll() {
        for (ActiveRun run : active.values()) {
            if (run.settled.get()) {
                continue;
            }
            try {
                MDC.put(AppConst.MDC_SESSION_ID, run.sessionId);
                MDC.put(AppConst.MDC_RUN_ID, run.runId);
                flush(run);
            } catch (Exception e) {
                log.error("[run.event.flush] runId={} 冲刷失败 {}", run.runId, e.getMessage());
            } finally {
                MDC.remove(AppConst.MDC_SESSION_ID);
                MDC.remove(AppConst.MDC_RUN_ID);
            }
        }
    }

    /** 冲刷单个运行：先落库再推送，落库失败不中断输出；持运行监视器保证与收尾最终冲刷串行，不丢帧 */
    private void flush(ActiveRun run) {
        synchronized (run) {
            flushLocked(run);
        }
    }

    /** 冲刷本体：持运行监视器调用，与 offer / drain 及收尾路径互斥 */
    private void flushLocked(ActiveRun run) {
        List<RunEventDTO> batch = run.drain();
        if (batch.isEmpty()) {
            return;
        }
        try {
            eventMapper.insertBatch(batch.stream().map(event -> toEventDO(run, event)).toList());
        } catch (Exception e) {
            log.error("[run.event.persist] runId={} 落库失败 {}", run.runId, e.getMessage());
        }
        batch.forEach(run::observe);
        mergeFrames(batch).forEach(event -> push(run, event));
    }

    /** 推送合帧：批内连续同类型增量合并为一帧（textDelta 拼接、seq 取最后一条），其余事件原样 */
    static List<RunEventDTO> mergeFrames(List<RunEventDTO> batch) {
        List<RunEventDTO> frames = new ArrayList<>(batch.size());
        RunEventDTO pending = null;
        for (RunEventDTO event : batch) {
            // 只有连续同类型的增量才能合并：text 与 thinking 在时间线上是两个区域
            boolean mergeable = isDeltaFrame(event) && Objects.nonNull(pending)
                    && pending.eventType().equals(event.eventType());
            if (Objects.nonNull(pending) && !mergeable) {
                frames.add(pending);
                pending = null;
            }
            if (isDeltaFrame(event)) {
                pending = Objects.isNull(pending) ? event : appendDelta(pending, event);
            } else {
                frames.add(event);
            }
        }
        if (Objects.nonNull(pending)) {
            frames.add(pending);
        }
        return frames;
    }

    private static boolean isDeltaFrame(RunEventDTO event) {
        return EventTypeEnum.TEXT_DELTA.getCode().equals(event.eventType())
                || EventTypeEnum.THINKING_DELTA.getCode().equals(event.eventType());
    }

    private static RunEventDTO appendDelta(RunEventDTO pending, RunEventDTO next) {
        String merged = Objects.toString(pending.textDelta(), StrUtil.EMPTY)
                + Objects.toString(next.textDelta(), StrUtil.EMPTY);
        Map<String, Object> payload = new HashMap<>(next.payload());
        payload.put("delta", merged);
        return new RunEventDTO(next.runId(), next.sessionId(), next.seq(), next.eventType(),
                next.blockType(), merged, payload, next.occurredAt());
    }

    /** 推送 SSE 帧；客户端断开时取消运行 */
    private void push(ActiveRun run, RunEventDTO event) {
        if (run.settled.get()) {
            return;
        }
        try {
            run.sink.send(event.seq(), event.eventType(), JSONUtil.toJsonStr(event));
        } catch (RuntimeException e) {
            log.warn("[run.sse] runId={} 推送失败，取消运行 {}", run.runId, e.getMessage());
            run.cancel();
        }
    }

    /** 成功收尾：取消标志优先，中断与完成竞争时终态记 aborted */
    private void settleSuccess(ActiveRun run) {
        // 先冲刷推送攒批内事件再置终态： settled 后 push 不再放行，短任务会在首帧冲刷前完成
        flush(run);
        if (!run.settled.compareAndSet(false, true)) {
            return;
        }
        if (cancellationWins(run)) {
            finishAborted(run);
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
        pushTerminal(run, EventTypeEnum.RUN_END.getCode(), Map.of(
                "runId", run.runId,
                "status", RunStatusEnum.SUCCESS.getCode(),
                "stopReason", stopReason,
                "usage", Map.of("inputTokens", run.usage[0], "outputTokens", run.usage[1])));
        completeSink(run);
        cleanup(run);
        log.info("[run.end] runId={} status={} cost={}ms", run.runId, RunStatusEnum.SUCCESS.getCode(),
                System.currentTimeMillis() - run.startedAt);
    }

    /** 失败收尾：取消标志优先，取消导致的异常按中断收敛 */
    private void settleError(ActiveRun run, Throwable error) {
        // 先冲刷推送攒批内事件再置终态，理由同 settleSuccess
        flush(run);
        if (!run.settled.compareAndSet(false, true)) {
            return;
        }
        if (cancellationWins(run)) {
            finishAborted(run);
            return;
        }
        log.error("[run.failed] runId={} error={}", run.runId, error.getMessage(), error);
        WbRunDO dbRun = runMapper.selectById(run.runId);
        if (Objects.nonNull(dbRun)) {
            settleTerminal(dbRun, RunStatusEnum.FAILED.getCode(), StopReasonEnum.ERROR.getCode(),
                    run.modelCalls, run.usage);
        }
        writeAssistantMessage(run);
        pushTerminal(run, EventTypeEnum.RUN_ERROR.getCode(), Map.of(
                "code", ErrorCodeEnum.INTERNAL_ERROR.getCode(),
                "message", "运行失败，请查看日志"));
        completeSink(run);
        cleanup(run);
    }

    /** 中断收尾：取消请求或通道关闭触发 */
    private void settleAborted(ActiveRun run) {
        // 先冲刷推送攒批内事件再置终态，理由同 settleSuccess
        flush(run);
        if (!run.settled.compareAndSet(false, true)) {
            return;
        }
        finishAborted(run);
    }

    /** 中断收敛本体：取消与完成/异常竞争时的共同终态 */
    private void finishAborted(ActiveRun run) {
        log.info("[run.aborted] runId={}", run.runId);
        flush(run);
        WbRunDO dbRun = runMapper.selectById(run.runId);
        if (Objects.nonNull(dbRun)) {
            settleTerminal(dbRun, RunStatusEnum.ABORTED.getCode(), StopReasonEnum.ABORTED.getCode(),
                    run.modelCalls, run.usage);
        }
        writeAssistantMessage(run);
        pushTerminal(run, EventTypeEnum.RUN_END.getCode(), Map.of(
                "runId", run.runId,
                "status", RunStatusEnum.ABORTED.getCode(),
                "stopReason", StopReasonEnum.ABORTED.getCode(),
                "usage", Map.of("inputTokens", run.usage[0], "outputTokens", run.usage[1])));
        completeSink(run);
        cleanup(run);
    }

    /** 取消标志是否胜出：已请求取消的运行，完成通知与异常都按中断收敛 */
    private boolean cancellationWins(ActiveRun run) {
        RunHandle handle = run.handle;
        return Objects.nonNull(handle) && handle.isCancelled();
    }

    /** 启动失败且无 SSE 通道可推时，直接落失败终态，避免僵尸 queued 行 */
    private void markRunFailed(String runId) {
        WbRunDO terminal = new WbRunDO();
        terminal.setRunId(runId);
        terminal.setState(RunStatusEnum.FAILED.getCode());
        terminal.setStopReason(StopReasonEnum.ERROR.getCode());
        terminal.setDurationMs(0L);
        runMapper.updateTerminal(terminal);
    }

    /** 运行终态与用量落库 */
    private void settleTerminal(WbRunDO run, String status, String stopReason, int iterCount, long[] usage) {
        WbRunDO terminal = new WbRunDO();
        terminal.setRunId(run.getRunId());
        terminal.setState(status);
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
        String answer = run.answer.toString();
        if (StrUtil.isBlank(answer)) {
            return;
        }
        insertMessage(run.userId, run.sessionId, run.runId, MessageRoleEnum.ASSISTANT, answer);
        sessionMapper.updateStats(run.sessionId, 1, 0, run.usage[0] + run.usage[1]);
    }

    /** 终态合成帧：先落库再推送，并递增 seq，保证回放能拿到终态且帧 id 唯一 */
    private void pushTerminal(ActiveRun run, String eventType, Map<String, Object> payload) {
        RunEventDTO event = new RunEventDTO(run.runId, run.sessionId, ++run.seq, eventType, null, null,
                payload, System.currentTimeMillis());
        try {
            eventMapper.insertBatch(List.of(toEventDO(run, event)));
        } catch (Exception e) {
            log.error("[run.event.persist] runId={} 终态帧落库失败 {}", run.runId, e.getMessage());
        }
        try {
            run.sink.send(event.seq(), event.eventType(), JSONUtil.toJsonStr(event));
        } catch (RuntimeException e) {
            log.warn("[run.sse] runId={} 终态帧推送失败 {}", run.runId, e.getMessage());
        }
    }

    private void completeSink(ActiveRun run) {
        try {
            run.sink.complete();
        } catch (RuntimeException ignored) {
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
        run.setState(RunStatusEnum.QUEUED.getCode());
        runMapper.insert(run);
    }

    private void insertMessage(String userId, String sessionId, String runId, MessageRoleEnum role, String content) {
        MessageDocDTO message = new MessageDocDTO(
                IdGenerator.generate(IdPrefixConst.MESSAGE), userId, sessionId, runId,
                role.getCode(), content, content.length() / 2, System.currentTimeMillis());
        messageIndexMapper.add(message);
    }

    /** 近 N 条历史消息转内核消息（时间正序），排除本轮运行已落的消息，避免本轮输入重复注入 */
    private List<Msg> loadHistory(String sessionId, String runId) {
        List<MessageDocDTO> recent = messageIndexMapper.listBySession(sessionId,
                agentProperties.getHistory().getLimit(), runId);
        if (recent.isEmpty()) {
            return Collections.emptyList();
        }
        List<Msg> messages = new ArrayList<>(recent.size());
        recent.forEach(message -> {
            if (MessageRoleEnum.USER.getCode().equals(message.role())) {
                messages.add(Msg.user(StrUtil.nullToEmpty(message.content())));
            } else if (MessageRoleEnum.ASSISTANT.getCode().equals(message.role())) {
                messages.add(Msg.assistant(StrUtil.nullToEmpty(message.content()), new ArrayList<>()));
            }
        });
        return messages;
    }

    private void scheduleHeartbeat(ActiveRun run) {
        run.heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            MDC.put(AppConst.MDC_SESSION_ID, run.sessionId);
            MDC.put(AppConst.MDC_RUN_ID, run.runId);
            try {
                if (run.settled.get()) {
                    cancelHeartbeat(run);
                    return;
                }
                run.sink.ping();
            } catch (Exception e) {
                cancelHeartbeat(run);
                run.cancel();
            } finally {
                MDC.remove(AppConst.MDC_SESSION_ID);
                MDC.remove(AppConst.MDC_RUN_ID);
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

    /** 释放运行占用的资源：待冲刷事件、活动表、心跳与会话锁 */
    private void cleanup(ActiveRun run) {
        flush(run);
        active.remove(run.runId);
        cancelHeartbeat(run);
        releaseLock(run.sessionId);
    }

    /** 归属校验：按 (运行, 用户) 查，查不到即 404，不泄露他人资源是否存在 */
    private WbRunDO getOwned(String runId) {
        WbRunDO run = runMapper.selectByIdAndUser(runId, LoginContext.userId());
        if (Objects.isNull(run)) {
            throw new BizException(ErrorCodeEnum.RUN_NOT_FOUND, runId);
        }
        return run;
    }

    private WbEventDO toEventDO(ActiveRun run, RunEventDTO event) {
        WbEventDO entity = new WbEventDO();
        entity.setEventId(IdGenerator.generate(IdPrefixConst.EVENT));
        entity.setUserId(run.userId);
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
        vo.setStatus(run.getState());
        vo.setStopReason(run.getStopReason());
        vo.setIterCount(run.getIterCount());
        vo.setInputTokens(run.getInputTokens());
        vo.setOutputTokens(run.getOutputTokens());
        vo.setDurationMs(run.getDurationMs());
        vo.setStartedAt(TimeUtil.toMillis(run.getStartedAt()));
        vo.setEndedAt(TimeUtil.toMillis(run.getEndedAt()));
        vo.setCreatedAt(TimeUtil.toMillis(run.getCreatedAt()));
        return vo;
    }

    /** 关闭调度器 */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
        flushExecutor.shutdownNow();
    }

    /** 单次活动运行：SSE 通道 + 句柄 + 批缓冲 + 观测累积 */
    private static final class ActiveRun {

        final String runId;
        final String userId;
        final String sessionId;
        final RunSink sink;
        final AtomicBoolean settled = new AtomicBoolean(false);
        final AtomicBoolean exceeded = new AtomicBoolean(false);
        final StringBuilder answer = new StringBuilder();
        final long startedAt = System.currentTimeMillis();
        final long[] usage = new long[2];
        volatile long seq = RunConst.EVENT_SEQ_START - 1;
        volatile int modelCalls = 0;
        volatile RunHandle handle;
        volatile ScheduledFuture<?> heartbeat;

        private final List<RunEventDTO> buffer = new ArrayList<>();

        ActiveRun(String runId, String userId, String sessionId, RunSink sink) {
            this.runId = runId;
            this.userId = userId;
            this.sessionId = sessionId;
            this.sink = sink;
        }

        /** 事件入批：由冲刷调度器按批大小与时间阈值统一落库推送 */
        synchronized void offer(RunEventDTO event) {
            buffer.add(event);
        }

        /** 取出并清空批缓冲 */
        synchronized List<RunEventDTO> drain() {
            if (buffer.isEmpty()) {
                return List.of();
            }
            List<RunEventDTO> batch = new ArrayList<>(buffer);
            buffer.clear();
            return batch;
        }

        /** 请求取消，句柄存在才生效 */
        void cancel() {
            RunHandle current = handle;
            if (Objects.nonNull(current)) {
                current.cancel();
            }
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
    }
}
