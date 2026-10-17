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

/** 运行事件长链路：内核事件 → 归一 → 批内合帧 → 边界裁剪 */
class RunEventPipelineTest {

    private final AgentEventAdapter adapter = new AgentEventAdapter();

    @Test
    @DisplayName("一次完整运行：同类增量合帧、类型切换保序、帧序与 seq 尾值不变")
    void oneRunMergesAndKeepsOrder() {
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

        // 9 条事件按块类型合成 7 帧；帧的 seq 取该批次最后一条，前端据此判丢帧
        assertEquals(List.of(EventTypeEnum.RUN_START.getCode(), EventTypeEnum.THINKING_DELTA.getCode(),
                        EventTypeEnum.TEXT_DELTA.getCode(), EventTypeEnum.TOOL_CALL.getCode(),
                        EventTypeEnum.TOOL_RESULT.getCode(), EventTypeEnum.MODEL_CALL.getCode(),
                        EventTypeEnum.RUN_END.getCode()),
                frames.stream().map(RunEventDTO::eventType).toList());
        assertEquals(List.of(1L, 3L, 5L, 6L, 7L, 8L, 9L), frames.stream().map(RunEventDTO::seq).toList());

        // 合帧后正文与合并条数必须落在同一帧上：流式渲染与回放读的是同一份数据
        assertEquals(List.of("先想清楚|thinking|2", "答案|text|2"),
                List.of(digest(frames.get(1)), digest(frames.get(2))));
    }

    @Test
    @DisplayName("工具起止分工与用量入账：入参只在起帧、输出不进正文、用量缺失补 0")
    void toolPairingAndUsageSettlement() {
        List<RunEventDTO> frames = RunService.mergeFrames(List.of(
                adapt(AgentEvent.builder().type(AgentEventType.TOOL_CALL_START)
                        .toolCallId("call-1").toolName("python_exec").arguments("{\"code\":\"1\"}").build(), 1),
                adapt(AgentEvent.builder().type(AgentEventType.TOOL_RESULT)
                        .toolCallId("call-1").toolName("python_exec").output("42\n").build(), 2),
                adapt(AgentEvent.builder().type(AgentEventType.MODEL_CALL_END)
                        .inputTokens(120L).outputTokens(45L).durationMs(800L).build(), 3)));

        assertEquals("start|{\"code\":\"1\"}", frames.get(0).payload().get("phase")
                + "|" + frames.get(0).payload().get("arguments"));
        assertEquals("42\n|false", frames.get(1).payload().get("output")
                + "|" + frames.get(1).payload().get("isError"));
        assertNull(frames.get(1).textDelta(), "工具输出不得混进正文增量");

        // 用量以模型回报为唯一来源，缺失补 0 而不是 null，否则结算会把列写成 NULL
        assertEquals(List.of(120L, 45L, 800L),
                List.of(frames.get(2).payload().get("inputTokens"),
                        frames.get(2).payload().get("outputTokens"),
                        frames.get(2).payload().get("durationMs")));

        RunEventDTO failed = adapt(AgentEvent.builder().type(AgentEventType.TOOL_RESULT)
                .toolCallId("call-2").toolName("python_exec").output("boom").error(Boolean.TRUE).build(), 3);
        assertEquals("true|boom", failed.payload().get("isError") + "|" + failed.payload().get("output"));
    }

    @Test
    @DisplayName("边界：超长增量保尾裁剪且两处同源，异常事件不带正文")
    void oversizedAndErrorBoundaries() {
        RunEventDTO oversized = adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA)
                .textDelta("x".repeat(5000)).build(), 2);
        assertEquals(4096, oversized.textDelta().length());
        assertEquals(oversized.textDelta(), oversized.payload().get("delta"));

        RunEventDTO error = adapt(AgentEvent.builder().type(AgentEventType.AGENT_ERROR)
                .message("上游 429").build(), 3);
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
