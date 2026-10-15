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

/** 运行事件长链路：内核事件 → 归一 → 批内合帧 → 边界裁剪，一条用例串完整条链路与全部关键边界 */
class RunEventPipelineTest {

    private final AgentEventAdapter adapter = new AgentEventAdapter();

    @Test
    @DisplayName("一次完整运行：保序合帧、工具起止与结果、用量入账、超长裁剪、失败与异常标记")
    void oneRunThroughTheWholePipeline() {
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

        // 同类增量并为 1 帧、类型切换处断开：9 条事件合成 7 帧，帧序与 seq 尾值都不变
        assertEquals(List.of(EventTypeEnum.RUN_START.getCode(), EventTypeEnum.THINKING_DELTA.getCode(),
                        EventTypeEnum.TEXT_DELTA.getCode(), EventTypeEnum.TOOL_CALL.getCode(),
                        EventTypeEnum.TOOL_RESULT.getCode(), EventTypeEnum.MODEL_CALL.getCode(),
                        EventTypeEnum.RUN_END.getCode()),
                frames.stream().map(RunEventDTO::eventType).toList());
        assertEquals(List.of(1L, 3L, 5L, 6L, 7L, 8L, 9L), frames.stream().map(RunEventDTO::seq).toList());

        // 合帧结果同时供流式渲染与回放读取：正文、块类型、被合并的原始条数必须落在同一帧上
        assertEquals(List.of("先想清楚|thinking|2", "答案|text|2"),
                List.of(digest(frames.get(1)), digest(frames.get(2))));

        // 工具起止两帧分工：起帧只带入参、结果帧只带输出，输出不得混进正文增量
        assertEquals("start|{\"code\":\"1\"}", frames.get(3).payload().get("phase")
                + "|" + frames.get(3).payload().get("arguments"));
        assertEquals("42\n|false", frames.get(4).payload().get("output")
                + "|" + frames.get(4).payload().get("isError"));
        assertNull(frames.get(4).textDelta());

        // 用量以模型回报为唯一来源，缺失补 0 而不是 null，否则结算会把列写成 NULL
        assertEquals(List.of(120L, 45L, 800L),
                List.of(frames.get(5).payload().get("inputTokens"),
                        frames.get(5).payload().get("outputTokens"),
                        frames.get(5).payload().get("durationMs")));

        // 超长增量保尾裁剪，且增量字段与载荷字段必须同源，否则回放与实时渲染会看到两份正文
        RunEventDTO oversized = adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA)
                .textDelta("x".repeat(5000)).build(), 2);
        assertEquals(4096, oversized.textDelta().length());
        assertEquals(oversized.textDelta(), oversized.payload().get("delta"));

        // 失败工具带完整标记，异常事件不带正文
        RunEventDTO failed = adapt(AgentEvent.builder().type(AgentEventType.TOOL_RESULT)
                .toolCallId("call-2").toolName("python_exec").output("boom").error(Boolean.TRUE).build(), 3);
        assertEquals("true|boom", failed.payload().get("isError") + "|" + failed.payload().get("output"));

        RunEventDTO error = adapt(AgentEvent.builder().type(AgentEventType.AGENT_ERROR)
                .message("上游 429").build(), 4);
        assertEquals(EventTypeEnum.RUN_ERROR.getCode(), error.eventType());
        assertEquals("上游 429", error.payload().get("message"));
        assertNull(error.textDelta(), "错误事件不得携带正文增量");
    }

    /** 一帧的正文 / 块类型 / 合并条数，压成一行便于整链对照 */
    private String digest(RunEventDTO frame) {
        return frame.textDelta() + "|" + frame.blockType() + "|" + frame.payload().get("merged");
    }

    private RunEventDTO adapt(AgentEvent event, long seq) {
        return adapter.adapt(event, "RUN_1", "SESS_1", seq);
    }
}
