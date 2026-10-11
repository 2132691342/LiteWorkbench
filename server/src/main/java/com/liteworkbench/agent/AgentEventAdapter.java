package com.liteworkbench.agent;

import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** AgentScope 事件 → 工作台归一事件的唯一适配点 */
@Component
public class AgentEventAdapter {

    private static final int TEXT_DELTA_MAX = 4096;

    /** 把 SDK 事件归一为工作台事件；未识别类型归为 custom，不丢弃 */
    public RunEventDTO adapt(AgentEvent event, String runId, String sessionId,
                             long seq, ToolOutputCapture capture) {
        EventTypeEnum type = EventTypeEnum.CUSTOM;
        String blockType = null;
        String textDelta = null;
        Map<String, Object> payload = new HashMap<>();

        if (event instanceof AgentStartEvent) {
            type = EventTypeEnum.RUN_START;
        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
            type = EventTypeEnum.THINKING_DELTA;
            blockType = "thinking";
            textDelta = truncate(delta.getDelta());
        } else if (event instanceof TextBlockDeltaEvent delta) {
            type = EventTypeEnum.TEXT_DELTA;
            blockType = "text";
            textDelta = truncate(delta.getDelta());
        } else if (event instanceof ToolCallStartEvent call) {
            type = EventTypeEnum.TOOL_CALL;
            blockType = "tool_call";
            payload.put("phase", "start");
            payload.put("toolName", call.getToolCallName());
            payload.put("toolCallId", call.getToolCallId());
        } else if (event instanceof ToolCallEndEvent call) {
            type = EventTypeEnum.TOOL_CALL;
            blockType = "tool_call";
            payload.put("phase", "end");
            payload.put("toolName", call.getToolCallName());
            payload.put("toolCallId", call.getToolCallId());
        } else if (event instanceof ToolResultEndEvent result) {
            type = EventTypeEnum.TOOL_RESULT;
            blockType = "tool_result";
            payload.put("toolName", result.getToolCallName());
            payload.put("output", capture.take(result.getToolCallName()));
            payload.put("isError", result.getState() == ToolResultState.ERROR);
        } else if (event instanceof ModelCallStartEvent) {
            type = EventTypeEnum.MODEL_CALL;
            payload.put("phase", "start");
        } else if (event instanceof ModelCallEndEvent callEnd) {
            type = EventTypeEnum.MODEL_CALL;
            payload.put("phase", "end");
            if (Objects.nonNull(callEnd.getUsage())) {
                payload.put("inputTokens", callEnd.getUsage().getInputTokens());
                payload.put("outputTokens", callEnd.getUsage().getOutputTokens());
                payload.put("totalTokens", callEnd.getUsage().getTotalTokens());
            }
        } else if (event instanceof AgentResultEvent result) {
            type = EventTypeEnum.RUN_RESULT;
            blockType = "text";
            Msg text = result.getResult();
            payload.put("text", Objects.isNull(text) ? "" : Objects.toString(text.getTextContent(), ""));
        } else if (event instanceof ExceedMaxItersEvent exceed) {
            type = EventTypeEnum.RUN_EXCEED;
            payload.put("maxIters", exceed.getMaxIters());
            payload.put("currentIter", exceed.getCurrentIter());
        } else if (event instanceof AgentEndEvent) {
            type = EventTypeEnum.RUN_END;
        } else {
            payload.put("eventType", Objects.toString(event.getType(), "unknown"));
        }

        if (Objects.nonNull(textDelta)) {
            payload.put("delta", textDelta);
        }
        long occurredAt = System.currentTimeMillis();
        return new RunEventDTO(runId, sessionId, seq, type.getCode(), blockType,
                textDelta, payload, occurredAt);
    }

    /** 单帧增量超 4KB 截断，完整正文以 run.result 为准 */
    private String truncate(String delta) {
        if (Objects.isNull(delta) || delta.length() <= TEXT_DELTA_MAX) {
            return delta;
        }
        return delta.substring(0, TEXT_DELTA_MAX);
    }
}
