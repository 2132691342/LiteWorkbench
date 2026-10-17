package com.liteworkbench.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.agent.AgentRuntime;
import com.liteworkbench.agent.ModelFactory;
import com.liteworkbench.agent.RunContext;
import com.liteworkbench.agent.RunHandle;
import com.liteworkbench.agent.model.ModelTypes.Msg;
import com.liteworkbench.agent.model.ModelTypes.Role;
import com.liteworkbench.agent.model.SamplingParams;
import com.liteworkbench.agent.spi.MemoryPort;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.domain.constant.AgentConst;
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
import com.liteworkbench.domain.dto.RunRefREQ;
import com.liteworkbench.domain.dto.SessionPageREQ;
import com.liteworkbench.domain.entity.WbEventDO;
import com.liteworkbench.domain.entity.WbProviderDO;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 运行主链路：SSE 流式对话、推送与落库双节拍、取消与回放 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {

    /** 回放单页事件上限 */
    private static final long REPLAY_PAGE_SIZE = 500L;

    private final AgentRuntime agentRuntime;
    private final ModelFactory modelFactory;
    /** 长期记忆：装配系统提示时注入，只读不写 */
    private final MemoryPort memoryPort;
    private final AgentProperties agentProperties;
    private final SessionService sessionService;
    private final SkillService skillService;
    /** 输入框 @ 引用的上下文注入，只读用户自己的资源 */
    private final RunRefService runRefService;
    private final WbRunMapper runMapper;
    private final WbEventMapper eventMapper;
    private final MessageIndexMapper messageIndexMapper;
    private final WbSessionMapper sessionMapper;
    private final StringRedisTemplate redisTemplate;
    /** SSE 心跳与事件攒批冲刷的统一调度器，线程资源跟随容器生命周期回收 */
    private final ThreadPoolTaskScheduler scheduler;
    /** 启动阶段写库的短事务边界：run / 消息 / 会话统计必须同时生效 */
    private final TransactionTemplate transactionTemplate;

    /** 进行中的运行：runId -> 活动实例 */
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    /** 推送节拍器：16ms 一帧，只有存在运行时才注册 */
    private final EventLoop pushLoop = new EventLoop(active::isEmpty);
    /** 落库节拍器：200ms 一批，与推送解耦，DB 写入不拖慢首字延迟 */
    private final EventLoop persistLoop = new EventLoop(active::isEmpty);
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
        MDC.put(AppConst.MDC_SESSION_ID, session.getSessionId());
        MDC.put(AppConst.MDC_RUN_ID, runId);
        try {
            var provider = StrUtil.isNotBlank(session.getProviderCode())
                    ? modelFactory.loadProvider(session.getProviderCode())
                    : modelFactory.resolveDefaultProvider();
            String providerCode = provider.getProviderCode();
            String modelName = modelFactory.resolveModelName(provider, session.getModelName());

            // 四条写库必须同事务：否则会留下「有 run 无消息」或「消息已落而 run 仍 queued」的脏状态
            transactionTemplate.executeWithoutResult(status -> {
                insertRun(runId, userId, session.getSessionId(), req.getContent(), modelName);
                insertMessage(userId, session.getSessionId(), runId, MessageRoleEnum.USER, req.getContent());
                runMapper.markRunning(runId);
                sessionMapper.updateStats(session.getSessionId(), 1, 1, 0);
            });

            String systemPrompt = composeSystemPrompt(userId, session.getSystemPrompt(), req.getContent(),
                    req.getSkillCodes(), req.getRefs());
            RunContext context = new RunContext(userId, session.getSessionId(), runId,
                    providerCode, modelName, systemPrompt, loadHistory(session.getSessionId(), runId),
                    req.getContent(), samplingParams(provider));

            ActiveRun run = new ActiveRun(runId, userId, session.getSessionId(), sink);
            active.put(runId, run);
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

    /** 系统提示装配：平台提示 + 会话补充 + 技能展开 + 引用上下文 + 长期记忆，记忆放末尾——最近约束最容易被模型遵守 */
    private String composeSystemPrompt(String userId, String sessionPrompt, String userInput,
                                       List<String> skillCodes, List<RunRefREQ> refs) {
        String base = StrUtil.isBlank(sessionPrompt)
                ? AgentConst.PLATFORM_SYSTEM_PROMPT
                : AgentConst.PLATFORM_SYSTEM_PROMPT + "\n\n[会话补充指令] 以下是本会话的自定义要求，冲突时以其为准：\n" + sessionPrompt;
        String assembled = skillService.assembleSystemPrompt(userId, base, userInput, skillCodes);
        String refBlock = runRefService.buildBlock(userId, userInput, refs);
        String memory = memoryPort.promptBlock(userId);
        StringBuilder prompt = new StringBuilder(assembled);
        if (StrUtil.isNotBlank(refBlock)) {
            prompt.append("\n\n[本轮引用资料] 用户点名了以下上下文，回答必须优先依据这些内容：").append(refBlock);
        }
        if (StrUtil.isNotBlank(memory)) {
            prompt.append("\n\n[用户长期记忆] 以下为该用户已沉淀的偏好与事实，与本轮任务相关时必须遵守：\n").append(memory);
        }
        return prompt.toString();
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

    public RunVO detail(String runId) {
        return toVO(getOwned(runId));
    }

    /** 事件回放，支持 afterSeq 增量 */
    public List<RunEventDTO> events(String runId, long afterSeq) {
        getOwned(runId);
        return eventMapper.selectByRunAfterSeq(runId, afterSeq, REPLAY_PAGE_SIZE)
                .stream().map(RunEventCodec::toDTO).toList();
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
        // 无活动实例（如进程重启后的孤儿运行）：直接落终态，并立即释放会话锁
        // 不走 cleanup 就没人释放锁，该会话会在整个 LOCK_TTL_SECONDS 内一直回 SESSION_BUSY
        settleTerminal(run, RunStatusEnum.ABORTED.getCode(), StopReasonEnum.ABORTED.getCode(),
                run.getIterCount(), new long[2]);
        releaseLock(run.getSessionId());
        log.info("[run.abort] runId={} sessionId={} 孤儿运行直接置终态并释放会话锁",
                runId, run.getSessionId());
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
        // 两个节拍器全进程各一个，与运行数量无关；无运行时自行注销，空跑不占调度
        pushLoop.ensure(() -> pumpAll(), RunConst.SSE_PUSH_TICK_MS);
        persistLoop.ensure(() -> persistAll(), RunConst.EVENT_PERSIST_INTERVAL_MS);
    }

    /** 采样参数：供应方列留空回落全局配置，能力位取供应方值 */
    private SamplingParams samplingParams(WbProviderDO provider) {
        AgentProperties.Model model = agentProperties.getModel();
        return new SamplingParams(
                Objects.nonNull(provider.getTemperature()) ? provider.getTemperature() : model.getTemperature(),
                provider.getTopP(),
                provider.getMaxOutputTokens(),
                Objects.nonNull(provider.getContextWindow()) ? provider.getContextWindow() : model.getContextWindow(),
                Boolean.TRUE.equals(provider.getCapVision()),
                !Boolean.FALSE.equals(provider.getCapTool()),
                Boolean.TRUE.equals(provider.getCapReasoning()));
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

    /** 推送节拍：把所有活动运行的新事件合成帧发出去；无运行则注销节拍器 */
    private void pumpAll() {
        if (active.isEmpty()) {
            pushLoop.stopIfIdle();
            return;
        }
        for (ActiveRun run : active.values()) {
            if (run.settled.get()) {
                continue;
            }
            withMdc(run, () -> {
                synchronized (run) {
                    pumpLocked(run);
                }
            }, "run.sse.pump");
        }
    }

    /** 落库节拍：把所有活动运行的待落事件按批写入；无运行则注销节拍器 */
    private void persistAll() {
        if (active.isEmpty()) {
            persistLoop.stopIfIdle();
            return;
        }
        for (ActiveRun run : active.values()) {
            withMdc(run, () -> {
                synchronized (run) {
                    persistLocked(run);
                }
            }, "run.event.persist");
        }
    }

    /** 推送本体：取待推送事件 → 登记落库 → 观测累积 → 合帧下发 → 按需补水位快照 */
    private void pumpLocked(ActiveRun run) {
        pumpLocked(run, true);
    }

    /** 推送本体（可选快照）：收尾路径不补快照，避免终态帧前多一帧无效水位 */
    private void pumpLocked(ActiveRun run, boolean allowSnapshot) {
        List<RunEventDTO> batch = run.drainPending();
        if (batch.isEmpty()) {
            return;
        }
        run.enqueuePersist(batch);
        batch.forEach(run::observe);
        mergeFrames(batch).forEach(event -> push(run, event));
        if (allowSnapshot) {
            pushSnapshotIfNeeded(run);
        }
    }

    /** 落库本体：批内事件一次写库，失败不回滚推送——客户端已收到内容不能撤回 */
    private void persistLocked(ActiveRun run) {
        List<RunEventDTO> batch = run.drainPersist();
        if (batch.isEmpty()) {
            return;
        }
        try {
            eventMapper.insertBatch(batch.stream()
                    .map(event -> RunEventCodec.toDO(run.userId, event)).toList());
        } catch (Exception e) {
            log.error("[run.event.persist] runId={} 落库失败 count={} {}", run.runId, batch.size(), e.getMessage());
        }
    }

    /** 收尾冲刷：推送与落库各走一次，保证终态前攒批内的事件全部可见且可回放 */
    private void flush(ActiveRun run) {
        synchronized (run) {
            pumpLocked(run, false);
            persistLocked(run);
        }
    }

    /** 水位快照：仅在 seq 有新推进时下发，客户端比对最大 seq 判定丢帧并补齐 */
    private void pushSnapshotIfNeeded(ActiveRun run) {
        long now = System.currentTimeMillis();
        if (now - run.lastSnapshotAt < RunConst.SSE_SNAPSHOT_THROTTLE_MS || run.seq <= run.snapshotSeq) {
            return;
        }
        run.lastSnapshotAt = now;
        run.snapshotSeq = run.seq;
        Map<String, Object> payload = new HashMap<>();
        payload.put("watermark", run.seq);
        payload.put("textLength", run.answer.length());
        payload.put("toolCalls", run.toolCalls);
        RunEventDTO snapshot = new RunEventDTO(run.runId, run.sessionId, run.seq,
                EventTypeEnum.RUN_SNAPSHOT.getCode(), null, null, payload, now);
        try {
            run.sink.send(snapshot.seq(), snapshot.eventType(), JSONUtil.toJsonStr(snapshot));
        } catch (RuntimeException e) {
            log.warn("[run.sse] runId={} 水位快照推送失败 {}", run.runId, e.getMessage());
            run.cancel();
        }
    }

    /** 推送合帧：批内连续同类型增量合并为一帧（textDelta 拼接、seq 取最后一条、merged 记合并条数），其余事件原样 */
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

    /** 增量拼接：merged 记录被合并的原始条数，客户端据此对齐序号 */
    private static RunEventDTO appendDelta(RunEventDTO pending, RunEventDTO next) {
        String merged = Objects.toString(pending.textDelta(), StrUtil.EMPTY)
                + Objects.toString(next.textDelta(), StrUtil.EMPTY);
        Object mergedCount = pending.payload().get("merged");
        long count = (mergedCount instanceof Number number ? number.longValue() : 1L) + 1L;
        Map<String, Object> payload = new HashMap<>(next.payload());
        payload.put("delta", merged);
        payload.put("merged", count);
        return new RunEventDTO(next.runId(), next.sessionId(), next.seq(), next.eventType(),
                next.blockType(), merged, payload, next.occurredAt());
    }

    /** 周期性任务统一补 MDC 与异常兜底，单次失败不影响其他运行 */
    private void withMdc(ActiveRun run, Runnable action, String tag) {
        MDC.put(AppConst.MDC_SESSION_ID, run.sessionId);
        MDC.put(AppConst.MDC_RUN_ID, run.runId);
        try {
            action.run();
        } catch (Exception e) {
            log.error("[{}] runId={} 执行失败 {}", tag, run.runId, e.getMessage());
        } finally {
            MDC.remove(AppConst.MDC_SESSION_ID);
            MDC.remove(AppConst.MDC_RUN_ID);
        }
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
            eventMapper.insertBatch(List.of(RunEventCodec.toDO(run.userId, event)));
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
        run.heartbeat = scheduler.scheduleAtFixedRate(() -> {
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
        }, Duration.ofMillis(RunConst.SSE_HEARTBEAT_MS));
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

    /** 容器停机：停两个节拍器、掐断在途心跳并冲刷残留事件，线程资源由 ExecutorConfig 统一回收 */
    @PreDestroy
    public void shutdown() {
        pushLoop.stop();
        persistLoop.stop();
        active.values().forEach(run -> {
            run.heartbeat.cancel(true);
            flush(run);
        });
        active.clear();
        log.info("[run.shutdown] 运行资源已释放");
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
        volatile int toolCalls = 0;
        volatile long snapshotSeq = RunConst.EVENT_SEQ_START - 1;
        volatile long lastSnapshotAt = 0L;
        volatile RunHandle handle;
        volatile ScheduledFuture<?> heartbeat;

        /** 待推送缓冲：由 16ms 节拍取走 */
        private final List<RunEventDTO> pending = new ArrayList<>();
        /** 待落库缓冲：推送过的原始事件，由 200ms 节拍取走 */
        private final List<RunEventDTO> persistBuffer = new ArrayList<>();

        ActiveRun(String runId, String userId, String sessionId, RunSink sink) {
            this.runId = runId;
            this.userId = userId;
            this.sessionId = sessionId;
            this.sink = sink;
        }

        /** 事件入推送缓冲：内核线程只做一次 add，任何 IO 都不在此线程上发生 */
        synchronized void offer(RunEventDTO event) {
            pending.add(event);
        }

        /** 取出并清空待推送缓冲 */
        synchronized List<RunEventDTO> drainPending() {
            if (pending.isEmpty()) {
                return List.of();
            }
            List<RunEventDTO> batch = new ArrayList<>(pending);
            pending.clear();
            return batch;
        }

        /** 推送前把原始事件登记进落库缓冲 */
        synchronized void enqueuePersist(List<RunEventDTO> events) {
            persistBuffer.addAll(events);
        }

        /** 取出并清空待落库缓冲 */
        synchronized List<RunEventDTO> drainPersist() {
            if (persistBuffer.isEmpty()) {
                return List.of();
            }
            List<RunEventDTO> batch = new ArrayList<>(persistBuffer);
            persistBuffer.clear();
            return batch;
        }

        /** 请求取消，句柄存在才生效 */
        void cancel() {
            RunHandle current = handle;
            if (Objects.nonNull(current)) {
                current.cancel();
            }
        }

        /** 从事件流提取答案文本、用量、工具数与迭代数；seq 取高水位，合并帧不改变净增语义 */
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
            } else if (EventTypeEnum.TOOL_CALL.getCode().equals(event.eventType())) {
                if ("start".equals(event.payload().get("phase"))) {
                    toolCalls++;
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

    /** 自注销节拍器：首次注册时启动，运行清空后由任务自身停表，避免空转占调度线程 */
    private final class EventLoop {

        /** 空闲判据：与取消动作在同一把锁内复检，否则新运行刚注册的节拍器会被本次取消掉 */
        private final BooleanSupplier idle;
        private final Object lock = new Object();
        private ScheduledFuture<?> future;

        EventLoop(BooleanSupplier idle) {
            this.idle = idle;
        }

        /** 幂等启动：已在跑则不重复注册 */
        void ensure(Runnable task, long intervalMs) {
            synchronized (lock) {
                if (Objects.nonNull(future)) {
                    return;
                }
                future = scheduler.scheduleWithFixedDelay(task::run, Duration.ofMillis(intervalMs));
            }
        }

        /** 空闲时自注销；判空与取消必须原子，防止与新运行的注册交错后停表 */
        void stopIfIdle() {
            synchronized (lock) {
                if (idle.getAsBoolean()) {
                    cancelLocked();
                }
            }
        }

        /** 无条件停表，仅用于容器停机 */
        void stop() {
            synchronized (lock) {
                cancelLocked();
            }
        }

        private void cancelLocked() {
            ScheduledFuture<?> current = future;
            future = null;
            if (Objects.nonNull(current)) {
                current.cancel(false);
            }
        }
    }
}
