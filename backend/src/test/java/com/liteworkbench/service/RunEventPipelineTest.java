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

/** 事件长链路：内核事件 → 归一 → 批内合帧 → 结算入账，一条用例串起整条链路与关键边界 */
class RunEventPipelineTest {

    private final AgentEventAdapter adapter = new AgentEventAdapter();

    @Test
    @DisplayName("一次完整运行：分组保序合帧、工具起止与用量入账、超长裁剪与失败标记")
    void fullRunPipelineKeepsOrderAndAccounts() {
        List<RunEventDTO> frames = RunService.mergeFrames(List.of(
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
                adapt(AgentEvent.builder().type(AgentEventType.AGENT_END).build(), 9)));

        // 同类增量并为 1 帧、类型切换处断开：9 条合成 7 帧，顺序与 seq 尾值都不变
        assertEquals(List.of(EventTypeEnum.RUN_START.getCode(), EventTypeEnum.THINKING_DELTA.getCode(),
                        EventTypeEnum.TEXT_DELTA.getCode(), EventTypeEnum.TOOL_CALL.getCode(),
                        EventTypeEnum.TOOL_RESULT.getCode(), EventTypeEnum.MODEL_CALL.getCode(),
                        EventTypeEnum.RUN_END.getCode()),
                frames.stream().map(RunEventDTO::eventType).toList());
        assertEquals(List.of(1L, 3L, 5L, 6L, 7L, 8L, 9L), frames.stream().map(RunEventDTO::seq).toList());

        // 合帧结果同时供流式渲染与回放读取，正文与块类型必须落在同一帧上
        assertEquals("先想清楚", frames.get(1).textDelta());
        assertEquals("thinking", frames.get(1).blockType());
        assertEquals("答案", frames.get(2).textDelta());
        assertEquals("text", frames.get(2).blockType());
        // merged 记录被合并的原始条数：客户端据此把「帧序号跳变」与「真丢帧」区分开
        assertEquals(2L, frames.get(1).payload().get("merged"));
        assertEquals(2L, frames.get(2).payload().get("merged"));

        // 工具结果帧只带输出，不得把输出混进正文增量
        assertEquals("start", frames.get(3).payload().get("phase"));
        assertEquals("{\"code\":\"1\"}", frames.get(3).payload().get("arguments"));
        assertEquals("42\n", frames.get(4).payload().get("output"));
        assertEquals(Boolean.FALSE, frames.get(4).payload().get("isError"));
        assertNull(frames.get(4).textDelta());

        // 用量以模型回报为唯一来源：缺失补 0 而不是 null，否则结算会写成 NULL
        assertEquals(120L, frames.get(5).payload().get("inputTokens"));
        assertEquals(45L, frames.get(5).payload().get("outputTokens"));
        assertEquals(800L, frames.get(5).payload().get("durationMs"));

        // 超长增量在归一阶段保尾裁剪，且增量字段与载荷字段同源
        RunEventDTO oversized = adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA)
                .textDelta("x".repeat(5000)).build(), 2);
        assertEquals(4096, oversized.textDelta().length());
        assertEquals(oversized.textDelta(), oversized.payload().get("delta"));

        // 失败工具与异常事件都带完整标记，错误帧不得携带正文
        RunEventDTO failed = adapt(AgentEvent.builder().type(AgentEventType.TOOL_RESULT)
                .toolCallId("call-2").toolName("python_exec").output("boom").error(Boolean.TRUE).build(), 3);
        assertEquals(Boolean.TRUE, failed.payload().get("isError"));
        assertEquals("boom", failed.payload().get("output"));

        RunEventDTO error = adapt(AgentEvent.builder().type(AgentEventType.AGENT_ERROR)
                .message("上游 429").build(), 4);
        assertEquals(EventTypeEnum.RUN_ERROR.getCode(), error.eventType());
        assertEquals("上游 429", error.payload().get("message"));
        assertNull(error.textDelta(), "错误事件不得携带正文增量");
    }

    private RunEventDTO adapt(AgentEvent event, long seq) {
        return adapter.adapt(event, "RUN_1", "SESS_1", seq);
    }
}
