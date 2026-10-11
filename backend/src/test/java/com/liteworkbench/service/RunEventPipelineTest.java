package com.liteworkbench.service;

import com.liteworkbench.agent.AgentEventAdapter;
import com.liteworkbench.agent.ToolOutputCapture;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 事件长链路：SDK 事件归一 → 批内合帧，覆盖增量合并、类型切换、工具输出与超长裁剪 */
class RunEventPipelineTest {

    private final AgentEventAdapter adapter = new AgentEventAdapter();

    @Test
    void 连续正文增量归一后合并为单帧且seq取尾() {
        List<RunEventDTO> batch = new ArrayList<>();
        batch.add(adapt(new TextBlockDeltaEvent("r1", "b1", "你"), 1));
        batch.add(adapt(new TextBlockDeltaEvent("r1", "b1", "好"), 2));
        batch.add(adapt(new TextBlockDeltaEvent("r1", "b1", "！"), 3));

        List<RunEventDTO> frames = RunService.mergeFrames(batch);
        assertEquals(1, frames.size());
        assertEquals("你好！", frames.get(0).textDelta());
        assertEquals(3, frames.get(0).seq());
        assertEquals("text", frames.get(0).blockType());
    }

    @Test
    void 思考与正文交替不跨类型合并且非增量事件保序() {
        List<RunEventDTO> batch = new ArrayList<>();
        batch.add(adapt(new AgentStartEvent("n", "s", "reply-1"), 1));
        batch.add(adapt(new ThinkingBlockDeltaEvent("r1", "b1", "先想"), 2));
        batch.add(adapt(new TextBlockDeltaEvent("r1", "b1", "再答"), 3));
        batch.add(new RunEventDTO("RUN_1", "SESS_1", 4, EventTypeEnum.RUN_END.getCode(),
                null, null, new HashMap<>(), 1000L));

        List<RunEventDTO> frames = RunService.mergeFrames(batch);
        assertEquals(List.of(EventTypeEnum.RUN_START.getCode(), EventTypeEnum.THINKING_DELTA.getCode(),
                EventTypeEnum.TEXT_DELTA.getCode(), EventTypeEnum.RUN_END.getCode()),
                frames.stream().map(RunEventDTO::eventType).toList());
        assertEquals("先想", frames.get(1).textDelta());
        assertEquals("再答", frames.get(2).textDelta());
    }

    @Test
    void 工具调用起止与结果输出各占一帧且读捕获内容() {
        ToolOutputCapture capture = new ToolOutputCapture();
        capture.record("python_exec", "42\n");
        List<RunEventDTO> batch = new ArrayList<>();
        batch.add(adapt(new ToolCallStartEvent("r1", "call-1", "python_exec"), 5));
        batch.add(adapter.adapt(new ToolResultEndEvent("r1", "call-1", "python_exec", ToolResultState.ERROR),
                "RUN_1", "SESS_1", 6, capture));

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
        RunEventDTO event = adapt(new ThinkingBlockDeltaEvent("r1", "b1", "x".repeat(5000)), 2);
        assertEquals(EventTypeEnum.THINKING_DELTA.getCode(), event.eventType());
        assertEquals(4096, event.textDelta().length());
        assertEquals(4096, String.valueOf(event.payload().get("delta")).length());
    }

    private RunEventDTO adapt(io.agentscope.core.event.AgentEvent event, long seq) {
        return adapter.adapt(event, "RUN_1", "SESS_1", seq, new ToolOutputCapture());
    }
}
