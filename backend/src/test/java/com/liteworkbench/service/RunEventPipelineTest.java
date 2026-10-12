package com.liteworkbench.service;

import com.liteworkbench.agent.AgentEventAdapter;
import com.liteworkbench.agent.event.AgentEvent;
import com.liteworkbench.agent.event.AgentEventType;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事件长链路：内核事件归一 → 批内合帧，覆盖增量合并边界、类型切换保序、工具与用量入账、超长裁剪 */
class RunEventPipelineTest {

    private final AgentEventAdapter adapter = new AgentEventAdapter();

    @Test
    void 一次完整运行的事件链路应保序合帧并携带全部账目字段() {
        List<RunEventDTO> batch = List.of(
                adapt(AgentEvent.builder().type(AgentEventType.AGENT_START).build(), 1),
                adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA).textDelta("先想").build(), 2),
                adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA).textDelta("清楚").build(), 3),
                adapt(AgentEvent.builder().type(AgentEventType.TEXT_DELTA).textDelta("答").build(), 4),
                adapt(AgentEvent.builder().type(AgentEventType.TEXT_DELTA).textDelta("案").build(), 5),
                adapt(AgentEvent.builder().type(AgentEventType.TOOL_CALL_START)
                        .toolCallId("call-1").toolName("python_exec").arguments("{\"code\":\"1\"}").build(), 6),
                adapt(AgentEvent.builder().type(AgentEventType.TOOL_RESULT)
                        .toolCallId("call-1").toolName("python_exec").output("42\n").build(), 7),
                adapt(AgentEvent.builder().type(AgentEventType.MODEL_CALL_END)
                        .inputTokens(120L).outputTokens(45L).durationMs(800L).build(), 8),
                adapt(AgentEvent.builder().type(AgentEventType.AGENT_END).build(), 9));

        List<RunEventDTO> frames = RunService.mergeFrames(batch);

        // 9 条事件合成 7 帧：思考与正文各并为 1 帧，其余原样，顺序与 seq 尾值不变
        assertEquals(7, frames.size());
        assertEquals(List.of(EventTypeEnum.RUN_START.getCode(), EventTypeEnum.THINKING_DELTA.getCode(),
                        EventTypeEnum.TEXT_DELTA.getCode(), EventTypeEnum.TOOL_CALL.getCode(),
                        EventTypeEnum.TOOL_RESULT.getCode(), EventTypeEnum.MODEL_CALL.getCode(),
                        EventTypeEnum.RUN_END.getCode()),
                frames.stream().map(RunEventDTO::eventType).toList());
        assertEquals(List.of(1L, 3L, 5L, 6L, 7L, 8L, 9L), frames.stream().map(RunEventDTO::seq).toList());
        assertEquals("先想清楚", frames.get(1).textDelta());
        assertEquals("thinking", frames.get(1).blockType());
        assertEquals("答案", frames.get(2).textDelta());
        assertEquals("text", frames.get(2).blockType());

        // 工具起止与结果各占一帧：调用帧带参数，结果帧带输出且不混入正文
        assertEquals("start", frames.get(3).payload().get("phase"));
        assertEquals("python_exec", frames.get(3).payload().get("toolName"));
        assertEquals("{\"code\":\"1\"}", frames.get(3).payload().get("arguments"));
        assertEquals("42\n", frames.get(4).payload().get("output"));
        assertEquals(Boolean.FALSE, frames.get(4).payload().get("isError"));
        assertNull(frames.get(4).textDelta());

        // 模型用量是入账的唯一来源，缺失必须补 0 而不是 null
        assertEquals(120L, frames.get(5).payload().get("inputTokens"));
        assertEquals(45L, frames.get(5).payload().get("outputTokens"));
        assertEquals(800L, frames.get(5).payload().get("durationMs"));
    }

    @Test
    void 超长增量应在归一阶段裁剪且失败工具与异常路径标记完整() {
        RunEventDTO oversized = adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA)
                .textDelta("x".repeat(5000)).build(), 2);
        assertEquals(EventTypeEnum.THINKING_DELTA.getCode(), oversized.eventType());
        assertEquals(4096, oversized.textDelta().length());
        assertEquals(4096, String.valueOf(oversized.payload().get("delta")).length());

        RunEventDTO failedTool = adapt(AgentEvent.builder().type(AgentEventType.TOOL_RESULT)
                .toolCallId("call-2").toolName("python_exec").output("boom").error(Boolean.TRUE).build(), 3);
        assertEquals(Boolean.TRUE, failedTool.payload().get("isError"));
        assertEquals("boom", failedTool.payload().get("output"));

        RunEventDTO error = adapt(AgentEvent.builder().type(AgentEventType.AGENT_ERROR)
                .message("上游 429").build(), 4);
        assertEquals(EventTypeEnum.RUN_ERROR.getCode(), error.eventType());
        assertEquals("上游 429", error.payload().get("message"));
        assertTrue(error.payload().containsKey("message"));
    }

    private RunEventDTO adapt(AgentEvent event, long seq) {
        return adapter.adapt(event, "RUN_1", "SESS_1", seq);
    }
}
