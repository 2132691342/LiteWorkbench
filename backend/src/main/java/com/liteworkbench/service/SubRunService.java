package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.AgentRuntime;
import com.liteworkbench.agent.ModelFactory;
import com.liteworkbench.agent.RunContext;
import com.liteworkbench.agent.RunHandle;
import com.liteworkbench.agent.model.SamplingParams;
import com.liteworkbench.agent.spi.SubRunPort;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.PlanConst;
import com.liteworkbench.domain.constant.RunConst;
import com.liteworkbench.domain.constant.RunStatusEnum;
import com.liteworkbench.domain.constant.StopReasonEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import com.liteworkbench.domain.entity.WbProviderDO;
import com.liteworkbench.domain.entity.WbRunDO;
import com.liteworkbench.mapper.WbEventMapper;
import com.liteworkbench.mapper.WbRunMapper;
import com.liteworkbench.mapper.WbSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 子运行：父运行把子任务交给独立运行执行，同步收口产出，事件与父运行同属一个会话 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubRunService implements SubRunPort {

    /** 子运行系统提示：只交代任务边界，不带平台技能，避免子运行越权做父运行该做的事 */
    private static final String CHILD_PROMPT = """
            你是 LiteWorkbench 的子任务执行器，只完成交给你的那一件事。
            要求：
            1. 只输出任务要求的成果本身，不要复述任务、不要寒暄、不要解释你打算怎么做；
            2. 任务描述已包含全部上下文，不要向提问方追问；
            3. 需要计算或验证时优先调用工具得出事实，不要凭印象编造。
            """;

    /** 事件按 seq 排序，保证落库与回放保序 */
    private static final Comparator<RunEventDTO> BY_SEQ = Comparator.comparingLong(RunEventDTO::seq);

    /** 同时执行的子运行上限：父运行线程同步等子运行，不设上界会把运行编排池整体拖住 */
    private static final int MAX_CONCURRENT_SUB_RUNS = Math.max(2, Runtime.getRuntime().availableProcessors());

    /** 运行内核惰性解析：内核反向依赖本端口（task_delegate），构造期直取会形成循环依赖 */
    private final ObjectProvider<AgentRuntime> agentRuntimeProvider;
    private final ModelFactory modelFactory;
    private final AgentProperties agentProperties;
    private final WbRunMapper runMapper;
    private final WbEventMapper eventMapper;
    private final WbSessionMapper sessionMapper;

    /** 子运行配额：在派发之前获取，拿不到即刻返回结构化失败让模型换路，而不是排队阻塞 */
    private final Semaphore delegateSlots = new Semaphore(MAX_CONCURRENT_SUB_RUNS, true);

    @Override
    public String delegate(String userId, String parentRunId, String sessionId, String task, long timeoutSeconds) {
        WbRunDO parent = runMapper.selectByIdAndUser(parentRunId, userId);
        if (Objects.isNull(parent)) {
            return error("父运行不存在或不属于当前用户，子任务已拒绝执行");
        }
        String providerCode = resolveProviderCode(userId, sessionId);
        if (StrUtil.isBlank(providerCode)) {
            return error("当前没有可用的模型供应方，子任务无法执行");
        }
        if (!delegateSlots.tryAcquire()) {
            return error(StrUtil.format("子任务并发已达上限 {}，请稍后重试或由你直接完成该步骤",
                    MAX_CONCURRENT_SUB_RUNS));
        }
        try {
            return execute(userId, parentRunId, sessionId, task, timeoutSeconds, parent, providerCode);
        } finally {
            delegateSlots.release();
        }
    }

    /** 配额内执行一次子运行：建子 run → 同步等产出 → 收口终态 */
    private String execute(String userId, String parentRunId, String sessionId, String task, long timeoutSeconds,
                           WbRunDO parent, String providerCode) {
        long timeout = Math.min(Math.max(timeoutSeconds, 1L), PlanConst.DELEGATE_TIMEOUT_SECONDS * 2);
        String childRunId = IdGenerator.generate(IdPrefixConst.RUN);
        insertChildRun(childRunId, userId, sessionId, parent, task);

        // 子运行跟随父运行使用的供应方参数：未配置列时 SamplingParams.global 回落全局配置
        WbProviderDO provider = modelFactory.loadProvider(providerCode);
        RunContext context = new RunContext(userId, sessionId, childRunId, providerCode,
                parent.getModelName(), CHILD_PROMPT, Collections.emptyList(), task,
                new SamplingParams(
                        Objects.nonNull(provider.getTemperature()) ? provider.getTemperature()
                                : agentProperties.getModel().getTemperature(),
                        provider.getTopP(),
                        provider.getMaxOutputTokens(),
                        Objects.nonNull(provider.getContextWindow()) ? provider.getContextWindow()
                                : agentProperties.getModel().getContextWindow(),
                        Boolean.TRUE.equals(provider.getCapVision()),
                        !Boolean.FALSE.equals(provider.getCapTool()),
                        Boolean.TRUE.equals(provider.getCapReasoning())));
        // 子运行的中间帧不推送，只在收尾一次性落库：父运行的时间线只呈现「委派」这一件事
        List<RunEventDTO> collected = Collections.synchronizedList(new ArrayList<>());
        long started = System.currentTimeMillis();
        RunHandle handle = agentRuntimeProvider.getObject().start(context, collected::add);
        try {
            handle.completion().get(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            settleChild(childRunId, userId, RunStatusEnum.ABORTED.getCode(),
                    StopReasonEnum.ABORTED.getCode(), collected, started);
            return error("子任务被中断");
        } catch (Exception e) {
            handle.cancel();
            settleChild(childRunId, userId, RunStatusEnum.FAILED.getCode(),
                    StopReasonEnum.ERROR.getCode(), collected, started);
            log.warn("[subrun.failed] parentRunId={} childRunId={} cost={}ms {}",
                    parentRunId, childRunId, System.currentTimeMillis() - started, e.getMessage());
            return error(StrUtil.format("子任务未在 {} 秒内完成，已终止；请拆小任务或换一种做法", timeout));
        }
        String answer = settleChild(childRunId, userId, RunStatusEnum.SUCCESS.getCode(),
                StopReasonEnum.COMPLETED.getCode(), collected, started);
        log.info("[subrun.end] parentRunId={} childRunId={} events={} cost={}ms",
                parentRunId, childRunId, collected.size(), System.currentTimeMillis() - started);
        return StrUtil.isBlank(answer) ? error("子任务没有产出内容") : answer;
    }

    /** 子运行的供应方：优先继承会话配置，缺失时取默认可用供应方 */
    private String resolveProviderCode(String userId, String sessionId) {
        try {
            var session = sessionMapper.selectByIdAndUser(userId, sessionId);
            if (Objects.nonNull(session) && StrUtil.isNotBlank(session.getProviderCode())) {
                return session.getProviderCode();
            }
            return modelFactory.resolveDefaultProvider().getProviderCode();
        } catch (Exception e) {
            log.warn("[subrun.provider] sessionId={} 供应方解析失败 {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void insertChildRun(String childRunId, String userId, String sessionId, WbRunDO parent, String task) {
        WbRunDO child = new WbRunDO();
        child.setRunId(childRunId);
        child.setUserId(userId);
        child.setSessionId(sessionId);
        child.setParentRunId(parent.getRunId());
        child.setRelation(RunConst.RELATION_CHILD);
        child.setPrompt(task);
        child.setModelName(parent.getModelName());
        child.setState(RunStatusEnum.QUEUED.getCode());
        runMapper.insert(child);
        runMapper.markRunning(childRunId);
    }

    /** 收尾：事件按 seq 排序后一次性落库供 Run 树回放，再落终态，返回最终正文 */
    private String settleChild(String childRunId, String userId, String status, String stopReason,
                               List<RunEventDTO> events, long started) {
        String answer = StrUtil.EMPTY;
        if (!events.isEmpty()) {
            List<RunEventDTO> ordered = events.stream().sorted(BY_SEQ).toList();
            try {
                eventMapper.insertBatch(ordered.stream()
                        .map(event -> RunEventCodec.toDO(userId, event)).toList());
                answer = extractText(ordered);
            } catch (Exception e) {
                log.error("[subrun.persist] childRunId={} 事件落库失败 {}", childRunId, e.getMessage());
            }
        }
        WbRunDO terminal = new WbRunDO();
        terminal.setRunId(childRunId);
        terminal.setState(status);
        terminal.setStopReason(stopReason);
        terminal.setDurationMs(System.currentTimeMillis() - started);
        runMapper.updateTerminal(terminal);
        return answer;
    }

    /** 取最终正文：run.result 权威覆盖，缺失时退化为增量拼接 */
    private String extractText(List<RunEventDTO> events) {
        StringBuilder joined = new StringBuilder();
        for (RunEventDTO event : events) {
            if (EventTypeEnum.RUN_RESULT.getCode().equals(event.eventType())) {
                Object text = event.payload().get("text");
                if (Objects.nonNull(text) && StrUtil.isNotBlank(text.toString())) {
                    return text.toString();
                }
            }
            if (EventTypeEnum.TEXT_DELTA.getCode().equals(event.eventType())
                    && Objects.nonNull(event.textDelta())) {
                joined.append(event.textDelta());
            }
        }
        return joined.toString();
    }

    private String error(String message) {
        return "ERROR: " + message;
    }
}
