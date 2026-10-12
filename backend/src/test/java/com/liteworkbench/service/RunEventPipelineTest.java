package com.liteworkbench.service;

import com.liteworkbench.agent.AgentEventAdapter;
import com.liteworkbench.agent.event.AgentEvent;
import com.liteworkbench.agent.event.AgentEventType;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 事件长链路：内核事件归一 → 批内合帧，覆盖增量合并、类型切换、工具输出与超长裁剪 */
class RunEventPipelineTest {

    private final AgentEventAdapter adapter = new AgentEventAdapter();

    @Test
    void 连续正文增量归一后合并为单帧且seq取尾() {
        List<RunEventDTO> batch = List.of(
                adapt(AgentEvent.builder().type(AgentEventType.TEXT_DELTA).textDelta("你").build(), 1),
                adapt(AgentEvent.builder().type(AgentEventType.TEXT_DELTA).textDelta("好").build(), 2),
                adapt(AgentEvent.builder().type(AgentEventType.TEXT_DELTA).textDelta("！").build(), 3));

        List<RunEventDTO> frames = RunService.mergeFrames(batch);
        assertEquals(1, frames.size());
        assertEquals("你好！", frames.get(0).textDelta());
        assertEquals(3, frames.get(0).seq());
        assertEquals("text", frames.get(0).blockType());
    }

    @Test
    void 思考与正文交替不跨类型合并且非增量事件保序() {
        List<RunEventDTO> batch = List.of(
                adapt(AgentEvent.builder().type(AgentEventType.AGENT_START).build(), 1),
                adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA).textDelta("先想").build(), 2),
                adapt(AgentEvent.builder().type(AgentEventType.TEXT_DELTA).textDelta("再答").build(), 3),
                new RunEventDTO("RUN_1", "SESS_1", 4, EventTypeEnum.RUN_END.getCode(),
                        null, null, new HashMap<>(), 1000L));

        List<RunEventDTO> frames = RunService.mergeFrames(batch);
        assertEquals(List.of(EventTypeEnum.RUN_START.getCode(), EventTypeEnum.THINKING_DELTA.getCode(),
                        EventTypeEnum.TEXT_DELTA.getCode(), EventTypeEnum.RUN_END.getCode()),
                frames.stream().map(RunEventDTO::eventType).toList());
        assertEquals("先想", frames.get(1).textDelta());
        assertEquals("再答", frames.get(2).textDelta());
    }

    @Test
    void 工具调用起止与结果各占一帧且携带输出与失败标记() {
        List<RunEventDTO> batch = List.of(
                adapt(AgentEvent.builder().type(AgentEventType.TOOL_CALL_START).toolCallId("call-1")
                        .toolName("python_exec").arguments("{\"code\":\"1\"}").build(), 5),
                adapt(AgentEvent.builder().type(AgentEventType.TOOL_RESULT).toolCallId("call-1")
                        .toolName("python_exec").output("42\n").error(Boolean.TRUE).build(), 6));

        List<RunEventDTO> frames = RunService.mergeFrames(batch);
        assertEquals(2, frames.size());
        assertEquals("start", frames.get(0).payload().get("phase"));
        assertEquals("python_exec", frames.get(0).payload().get("toolName"));
        assertEquals(Boolean.TRUE, frames.get(1).payload().get("isError"));
        assertEquals("42\n", frames.get(1).payload().get("output"));
        assertNull(frames.get(1).textDelta());
    }

    @Test
    void 超长思考增量归一即裁剪不越界() {
        RunEventDTO event = adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA)
                .textDelta("x".repeat(5000)).build(), 2);
        assertEquals(EventTypeEnum.THINKING_DELTA.getCode(), event.eventType());
        assertEquals(4096, event.textDelta().length());
        assertEquals(4096, String.valueOf(event.payload().get("delta")).length());
    }

    @Test
    void 模型调用结束帧带数字用量供入账() {
        RunEventDTO event = adapt(AgentEvent.builder().type(AgentEventType.MODEL_CALL_END)
                .inputTokens(120L).outputTokens(45L).durationMs(800L).build(), 7);
        assertEquals(EventTypeEnum.MODEL_CALL.getCode(), event.eventType());
        assertEquals(120L, event.payload().get("inputTokens"));
        assertEquals(45L, event.payload().get("outputTokens"));
    }

    private RunEventDTO adapt(AgentEvent event, long seq) {
        return adapter.adapt(event, "RUN_1", "SESS_1", seq);
    }
}
