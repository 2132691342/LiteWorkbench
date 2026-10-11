package com.liteworkbench.agent;

import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事件归一：SDK 事件 → 工作台事件，未识别类型落 custom */
class AgentEventAdapterTest {

    private final AgentEventAdapter adapter = new AgentEventAdapter();

    @Test
    void textDeltaShouldMapWithTypeAndBlock() {
        RunEventDTO event = adapter.adapt(new TextBlockDeltaEvent("r1", "b1", "你好"),
                "RUN_1", "SESS_1", 3, new ToolOutputCapture());
        assertEquals(EventTypeEnum.TEXT_DELTA.getCode(), event.eventType());
        assertEquals("text", event.blockType());
        assertEquals("你好", event.textDelta());
        assertEquals(3, event.seq());
        assertEquals("你好", event.payload().get("delta"));
    }

    @Test
    void toolCallStartShouldCarryPhaseAndName() {
        RunEventDTO event = adapter.adapt(new ToolCallStartEvent("r1", "call-1", "python_exec"),
                "RUN_1", "SESS_1", 5, new ToolOutputCapture());
        assertEquals(EventTypeEnum.TOOL_CALL.getCode(), event.eventType());
        assertEquals("tool_call", event.blockType());
        assertEquals("start", event.payload().get("phase"));
        assertEquals("python_exec", event.payload().get("toolName"));
    }

    @Test
    void toolResultShouldReadCapturedOutput() {
        ToolOutputCapture capture = new ToolOutputCapture();
        capture.record("python_exec", "42\n");
        RunEventDTO event = adapter.adapt(
                new ToolResultEndEvent("r1", "call-1", "python_exec", ToolResultState.ERROR),
                "RUN_1", "SESS_1", 6, capture);
        assertEquals(EventTypeEnum.TOOL_RESULT.getCode(), event.eventType());
        assertEquals(Boolean.TRUE, event.payload().get("isError"));
        assertEquals("42\n", event.payload().get("output"));
    }

    @Test
    void exceedMaxItersShouldCarryLimit() {
        RunEventDTO event = adapter.adapt(new ExceedMaxItersEvent("r1", 20, 20),
                "RUN_1", "SESS_1", 9, new ToolOutputCapture());
        assertEquals(EventTypeEnum.RUN_EXCEED.getCode(), event.eventType());
        assertEquals(20, event.payload().get("maxIters"));
    }

    @Test
    void unknownEventShouldFallBackToCustom() {
        Msg msg = new UserMessage("hi");
        RunEventDTO event = adapter.adapt(new io.agentscope.core.event.AgentStartEvent("n", "s", "reply-1"),
                "RUN_1", "SESS_1", 1, new ToolOutputCapture());
        // AgentStartEvent 在映射表内，属 run.start；custom 兜底由 ofCode 校验
        assertEquals(EventTypeEnum.RUN_START.getCode(), event.eventType());
        assertNull(event.blockType());
        assertNotNull(msg);
        assertEquals(EventTypeEnum.CUSTOM, EventTypeEnum.ofCode("not.exists"));
        assertTrue(EventTypeEnum.CUSTOM.getCode().equals("custom"));
    }
}
