package com.liteworkbench.service;

import com.liteworkbench.agent.AgentEventAdapter;
import com.liteworkbench.agent.event.AgentEvent;
import com.liteworkbench.agent.event.AgentEventType;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 运行事件长链路：内核事件 → 归一 → 批内合帧 → 推送帧。
 * 一条完整运行串起全部环节，失败时按断言块定位到合帧、工具分工、用量结算或边界裁剪。
 */
class RunEventPipelineTest {

    private final AgentEventAdapter adapter = new AgentEventAdapter();

    @Test
    @DisplayName("一次完整运行：合帧保序、工具起止分工、用量入账、超长保尾、异常不带正文")
    void oneRunThroughWholePipeline() {
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
                adapt(AgentEvent.builder().type(AgentEventType.THINKING_DELTA)
                        .textDelta("x".repeat(5000)).build(), 9),
                adapt(AgentEvent.builder().type(AgentEventType.MODEL_CALL_END).build(), 10),
                adapt(AgentEvent.builder().type(AgentEventType.AGENT_ERROR).message("上游 429").build(), 11),
                adapt(AgentEvent.builder().type(AgentEventType.AGENT_END).build(), 12));

        List<RunEventDTO> frames = RunService.mergeFrames(batch);

        // 环节一 合帧与保序：12 条事件合成 10 帧；text 与 thinking 分属两个区域不得互相吞并
        assertEquals(List.of(EventTypeEnum.RUN_START.getCode(), EventTypeEnum.THINKING_DELTA.getCode(),
                        EventTypeEnum.TEXT_DELTA.getCode(), EventTypeEnum.TOOL_CALL.getCode(),
                        EventTypeEnum.TOOL_RESULT.getCode(), EventTypeEnum.MODEL_CALL.getCode(),
                        EventTypeEnum.THINKING_DELTA.getCode(), EventTypeEnum.MODEL_CALL.getCode(),
                        EventTypeEnum.RUN_ERROR.getCode(), EventTypeEnum.RUN_END.getCode()),
                frames.stream().map(RunEventDTO::eventType).toList());
        // 帧的 seq 取该批次最后一条，前端据此判丢帧并补齐
        assertEquals(List.of(1L, 3L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L),
                frames.stream().map(RunEventDTO::seq).toList());
        // 合帧后的正文、块类型与合并条数必须落在同一帧上：流式渲染与回放读的是同一份数据
        assertEquals(List.of("先想清楚|thinking|2", "答案|text|2"),
                List.of(digest(frames.get(1)), digest(frames.get(2))));

        // 环节二 工具起止分工：入参只在起帧，输出只在结果帧，且工具输出不得混进正文增量
        assertEquals("start|{\"code\":\"1\"}", payload(frames, 3).get("phase") + "|" + payload(frames, 3).get("arguments"));
        assertEquals("42\n|false", payload(frames, 4).get("output") + "|" + payload(frames, 4).get("isError"));
        assertNull(frames.get(4).textDelta(), "工具输出不得混进正文增量");

        // 环节三 用量入账：以模型回报为唯一来源，缺失补 0 而不是 null，否则结算会把列写成 NULL
        assertEquals(List.of(120L, 45L, 800L),
                List.of(payload(frames, 5).get("inputTokens"), payload(frames, 5).get("outputTokens"),
                        payload(frames, 5).get("durationMs")));
        assertEquals(List.of(0L, 0L, 0L),
                List.of(payload(frames, 7).get("inputTokens"), payload(frames, 7).get("outputTokens"),
                        payload(frames, 7).get("durationMs")));

        // 环节四 边界：超长增量保头裁剪且正文与载荷同源；异常事件不带正文增量
        assertEquals(4096, frames.get(6).textDelta().length());
        assertEquals(frames.get(6).textDelta(), payload(frames, 6).get("delta"));
        assertEquals("上游 429", payload(frames, 8).get("message"));
        assertNull(frames.get(8).textDelta(), "错误事件不得携带正文增量");
    }

    /** 一帧的正文 / 块类型 / 合并条数，压成一行便于整链对照 */
    private String digest(RunEventDTO frame) {
        return frame.textDelta() + "|" + frame.blockType() + "|" + frame.payload().get("merged");
    }

    private Map<String, Object> payload(List<RunEventDTO> frames, int index) {
        return frames.get(index).payload();
    }

    private RunEventDTO adapt(AgentEvent event, long seq) {
        return adapter.adapt(event, "RUN_1", "SESS_1", seq);
    }
}
