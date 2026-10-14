package com.liteworkbench.service;

import com.liteworkbench.agent.AgentEventAdapter;
import com.liteworkbench.agent.event.AgentEvent;
import com.liteworkbench.agent.event.AgentEventType;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件长链路：内核事件 → 归一 → 批内合帧，一条用例串起完整链路。
 * 断言只覆盖靠读代码看不出、改一处会静默破坏的边界：合帧保序、seq 语义、账目字段来源、超长裁剪与失败标记。
 */
class RunEventPipelineTest {

    private final AgentEventAdapter adapter = new AgentEventAdapter();

    @Test
    @DisplayName("一次完整运行：思考正文分组合帧、工具起止与用量入账、顺序与 seq 尾值不变")
    void fullRunPipelineKeepsOrderAndAccounts() {
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

        // 9 条合成 7 帧：思考与正文各并为 1 帧，类型切换处断开，顺序与 seq 尾值不变
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
        // merged 记录被合并的原始条数：客户端据此把「帧序号跳变」与「真丢帧」区分开
        assertEquals(2L, frames.get(1).payload().get("merged"));
        assertEquals(2L, frames.get(2).payload().get("merged"));
        assertNull(frames.get(0).payload().get("merged"));

        // 工具起止与结果各占一帧：调用帧带参数，结果帧带输出且不混入正文
        assertEquals("start", frames.get(3).payload().get("phase"));
        assertEquals("{\"code\":\"1\"}", frames.get(3).payload().get("arguments"));
        assertEquals("42\n", frames.get(4).payload().get("output"));
        assertEquals(Boolean.FALSE, frames.get(4).payload().get("isError"));
        assertNull(frames.get(4).textDelta());

        // 模型用量是入账的唯一来源，缺失必须补 0 而不是 null，否则结算会写成 NULL
        assertEquals(120L, frames.get(5).payload().get("inputTokens"));
        assertEquals(45L, frames.get(5).payload().get("outputTokens"));
        assertEquals(800L, frames.get(5).payload().get("durationMs"));
    }

    @Test
    @DisplayName("边界路径：超长增量在归一阶段保尾裁剪，失败工具与异常事件标记完整")
    void boundaryPathsAreMarked() {
        RunEventDTO oversized = adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA)
                .textDelta("x".repeat(5000)).build(), 2);
        assertEquals(4096, oversized.textDelta().length());
        assertEquals(oversized.textDelta(), oversized.payload().get("delta"),
                "两个字段分别是流式渲染与回放的读取源，必须同源");

        RunEventDTO failedTool = adapt(AgentEvent.builder().type(AgentEventType.TOOL_RESULT)
                .toolCallId("call-2").toolName("python_exec").output("boom").error(Boolean.TRUE).build(), 3);
        assertEquals(Boolean.TRUE, failedTool.payload().get("isError"));
        assertEquals("boom", failedTool.payload().get("output"));

        RunEventDTO error = adapt(AgentEvent.builder().type(AgentEventType.AGENT_ERROR)
                .message("上游 429").build(), 4);
        assertEquals(EventTypeEnum.RUN_ERROR.getCode(), error.eventType());
        assertEquals("上游 429", error.payload().get("message"));
        assertTrue(error.textDelta() == null, "错误事件不得携带正文增量");
    }

    private RunEventDTO adapt(AgentEvent event, long seq) {
        return adapter.adapt(event, "RUN_1", "SESS_1", seq);
    }
}
