package com.liteworkbench.agent;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.event.AgentEvent;
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

    /** 把内核事件归一为工作台事件，未识别的类型归为 custom 不丢弃 */
    public RunEventDTO adapt(AgentEvent event, String runId, String sessionId, long seq) {
        Map<String, Object> payload = new HashMap<>();
        String blockType = null;
        String textDelta = null;

        switch (event.getType()) {
            case AGENT_START -> payload.put("phase", "start");
            case TEXT_DELTA -> {
                blockType = "text";
                textDelta = truncate(event.getTextDelta());
                payload.put("delta", textDelta);
            }
            case THINKING_DELTA -> {
                blockType = "thinking";
                textDelta = truncate(event.getTextDelta());
                payload.put("delta", textDelta);
            }
            case TOOL_CALL_START -> {
                blockType = "tool_call";
                payload.put("phase", "start");
                putTool(payload, event);
            }
            case TOOL_CALL_END -> {
                blockType = "tool_call";
                payload.put("phase", "end");
                putTool(payload, event);
            }
            case TOOL_RESULT -> {
                blockType = "tool_result";
                putTool(payload, event);
                payload.put("output", StrUtil.nullToEmpty(event.getOutput()));
                payload.put("isError", Boolean.TRUE.equals(event.getError()));
            }
            case MODEL_CALL_START -> payload.put("phase", "start");
            case MODEL_CALL_END -> {
                payload.put("phase", "end");
                payload.put("inputTokens", Objects.isNull(event.getInputTokens()) ? 0L : event.getInputTokens());
                payload.put("outputTokens", Objects.isNull(event.getOutputTokens()) ? 0L : event.getOutputTokens());
                payload.put("durationMs", Objects.isNull(event.getDurationMs()) ? 0L : event.getDurationMs());
            }
            case AGENT_RESULT -> {
                blockType = "text";
                payload.put("text", StrUtil.nullToEmpty(event.getMessage()));
            }
            case EXCEED_MAX_ITERS -> payload.put("maxIters",
                    Objects.isNull(event.getMaxIters()) ? 0 : event.getMaxIters());
            case AGENT_END -> payload.put("phase", "end");
            case AGENT_ERROR -> payload.put("message", StrUtil.nullToEmpty(event.getMessage()));
            default -> payload.put("eventType", Objects.toString(event.getType(), "unknown"));
        }

        EventTypeEnum type = toEventType(event);
        return new RunEventDTO(runId, sessionId, seq, type.getCode(), blockType, textDelta, payload,
                System.currentTimeMillis());
    }

    /** 内核类型映射到工作台事件类型 */
    private EventTypeEnum toEventType(AgentEvent event) {
        if (Objects.isNull(event.getType())) {
            return EventTypeEnum.CUSTOM;
        }
        return switch (event.getType()) {
            case AGENT_START -> EventTypeEnum.RUN_START;
            case TEXT_DELTA -> EventTypeEnum.TEXT_DELTA;
            case THINKING_DELTA -> EventTypeEnum.THINKING_DELTA;
            case TOOL_CALL_START, TOOL_CALL_END -> EventTypeEnum.TOOL_CALL;
            case TOOL_RESULT -> EventTypeEnum.TOOL_RESULT;
            case MODEL_CALL_START, MODEL_CALL_END -> EventTypeEnum.MODEL_CALL;
            case AGENT_RESULT -> EventTypeEnum.RUN_RESULT;
            case EXCEED_MAX_ITERS -> EventTypeEnum.RUN_EXCEED;
            case AGENT_END -> EventTypeEnum.RUN_END;
            case AGENT_ERROR -> EventTypeEnum.RUN_ERROR;
        };
    }

    private void putTool(Map<String, Object> payload, AgentEvent event) {
        if (StrUtil.isNotBlank(event.getToolName())) {
            payload.put("toolName", event.getToolName());
        }
        if (StrUtil.isNotBlank(event.getToolCallId())) {
            payload.put("toolCallId", event.getToolCallId());
        }
        if (StrUtil.isNotBlank(event.getArguments())) {
            payload.put("arguments", event.getArguments());
        }
    }

    /** 单帧增量超 4KB 截断，完整正文以 run.result 为准 */
    private String truncate(String delta) {
        if (Objects.isNull(delta) || delta.length() <= TEXT_DELTA_MAX) {
            return delta;
        }
        return delta.substring(0, TEXT_DELTA_MAX);
    }
}
