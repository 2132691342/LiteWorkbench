package com.liteworkbench.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.agent.event.AgentEvent;
import com.liteworkbench.agent.event.AgentEventType;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 内核事件 → 工作台事件的唯一适配点：分配 seq 之外不做任何判断 */
@Slf4j
@Component
public class AgentEventAdapter {

    /** 单帧增量上限，完整正文以 run.result 为准 */
    private static final int TEXT_DELTA_MAX = 4096;
    /** 无法识别内核类型时的兜底载荷键 */
    private static final String KEY_EVENT_TYPE = "eventType";

    /** 内核对到工作台载荷的映射结果 */
    private record Mapped(String blockType, String textDelta, Map<String, Object> payload) {
    }

    /** 把内核事件归一为工作台事件；switch 表达式穷举全部内核类型，新增类型由编译器强制补分支 */
    public RunEventDTO adapt(AgentEvent event, String runId, String sessionId, long seq) {
        if (Objects.isNull(event.getType())) {
            Map<String, Object> payload = new HashMap<>();
            payload.put(KEY_EVENT_TYPE, "unknown");
            return new RunEventDTO(runId, sessionId, seq, EventTypeEnum.CUSTOM.getCode(), null, null, payload,
                    System.currentTimeMillis());
        }
        Mapped mapped = switch (event.getType()) {
            case AGENT_START -> mapped(null, null, "phase", "start");
            case TEXT_DELTA -> deltaMapped("text", event.getTextDelta());
            case THINKING_DELTA -> deltaMapped("thinking", event.getTextDelta());
            case TOOL_CALL_START -> toolMapped("tool_call", "start", event);
            case TOOL_CALL_END -> toolMapped("tool_call", "end", event);
            case TOOL_RESULT -> toolMapped("tool_result", null, event,
                    "output", StrUtil.nullToEmpty(event.getOutput()),
                    "isError", Boolean.TRUE.equals(event.getError()));
            case MODEL_CALL_START -> mapped(null, null, "phase", "start");
            case MODEL_CALL_END -> mapped(null, null,
                    "phase", "end",
                    "inputTokens", Objects.isNull(event.getInputTokens()) ? 0L : event.getInputTokens(),
                    "outputTokens", Objects.isNull(event.getOutputTokens()) ? 0L : event.getOutputTokens(),
                    "durationMs", Objects.isNull(event.getDurationMs()) ? 0L : event.getDurationMs());
            case PLAN_UPDATED -> mapped("plan", null, "steps",
                    JSONUtil.toList(JSONUtil.parseArray(event.getPlanJson()), Map.class));
            case AGENT_RESULT -> mapped("text", null, "text", StrUtil.nullToEmpty(event.getMessage()));
            case EXCEED_MAX_ITERS -> mapped(null, null, "maxIters",
                    Objects.isNull(event.getMaxIters()) ? 0 : event.getMaxIters());
            case AGENT_END -> mapped(null, null, "phase", "end");
            case AGENT_ERROR -> mapped(null, null, "message", StrUtil.nullToEmpty(event.getMessage()));
        };
        EventTypeEnum type = toEventType(event.getType());
        return new RunEventDTO(runId, sessionId, seq, type.getCode(), mapped.blockType(), mapped.textDelta(),
                mapped.payload(), System.currentTimeMillis());
    }

    /** 内核类型映射到工作台事件类型 */
    private EventTypeEnum toEventType(AgentEventType kernelType) {
        return switch (kernelType) {
            case AGENT_START -> EventTypeEnum.RUN_START;
            case TEXT_DELTA -> EventTypeEnum.TEXT_DELTA;
            case THINKING_DELTA -> EventTypeEnum.THINKING_DELTA;
            case TOOL_CALL_START, TOOL_CALL_END -> EventTypeEnum.TOOL_CALL;
            case TOOL_RESULT -> EventTypeEnum.TOOL_RESULT;
            case MODEL_CALL_START, MODEL_CALL_END -> EventTypeEnum.MODEL_CALL;
            case PLAN_UPDATED -> EventTypeEnum.PLAN_UPDATED;
            case AGENT_RESULT -> EventTypeEnum.RUN_RESULT;
            case EXCEED_MAX_ITERS -> EventTypeEnum.RUN_EXCEED;
            case AGENT_END -> EventTypeEnum.RUN_END;
            case AGENT_ERROR -> EventTypeEnum.RUN_ERROR;
        };
    }

    /** 纯载荷映射，键值成对传入 */
    private Mapped mapped(String blockType, String textDelta, Object... pairs) {
        return new Mapped(blockType, textDelta, pairMap(pairs));
    }

    /** 增量帧：单帧超 4KB 截断，完整正文以 run.result 为准 */
    private Mapped deltaMapped(String blockType, String delta) {
        String truncated = Objects.isNull(delta) || delta.length() <= TEXT_DELTA_MAX
                ? delta : delta.substring(0, TEXT_DELTA_MAX);
        return mapped(blockType, truncated, "delta", truncated);
    }

    /** 工具类事件：工具名 / 调用 ID / 参数三件套只在非空时下发 */
    private Mapped toolMapped(String blockType, String phase, AgentEvent event, Object... extraPairs) {
        Map<String, Object> payload = new HashMap<>();
        if (StrUtil.isNotBlank(phase)) {
            payload.put("phase", phase);
        }
        if (StrUtil.isNotBlank(event.getToolName())) {
            payload.put("toolName", event.getToolName());
        }
        if (StrUtil.isNotBlank(event.getToolCallId())) {
            payload.put("toolCallId", event.getToolCallId());
        }
        if (StrUtil.isNotBlank(event.getArguments())) {
            payload.put("arguments", event.getArguments());
        }
        payload.putAll(pairMap(extraPairs));
        return new Mapped(blockType, null, payload);
    }

    /** 键值成对展开为可变 Map，保证下游可继续修改 */
    private Map<String, Object> pairMap(Object... pairs) {
        Map<String, Object> payload = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            payload.put(Objects.toString(pairs[i]), pairs[i + 1]);
        }
        return payload;
    }
}
