package com.liteworkbench.service;

import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.dto.RunEventDTO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 推送合帧：批内连续 text/thinking 增量合并，非增量事件原样且保序 */
class RunEventFrameTest {

    @Test
    void continuousTextDeltasShouldMergeIntoSingleFrame() {
        List<RunEventDTO> batch = List.of(
                delta(1, EventTypeEnum.TEXT_DELTA, "你"),
                delta(2, EventTypeEnum.TEXT_DELTA, "好"),
                delta(3, EventTypeEnum.TEXT_DELTA, "！"));
        List<RunEventDTO> frames = RunService.mergeFrames(batch);
        assertEquals(1, frames.size());
        RunEventDTO frame = frames.get(0);
        assertEquals("你好！", frame.textDelta());
        assertEquals("你好！", frame.payload().get("delta"));
        assertEquals(3, frame.seq());
    }

    @Test
    void alternatingTypesShouldNotMergeAcross() {
        List<RunEventDTO> batch = List.of(
                delta(1, EventTypeEnum.TEXT_DELTA, "a"),
                delta(2, EventTypeEnum.THINKING_DELTA, "b"),
                delta(3, EventTypeEnum.TEXT_DELTA, "c"));
        List<RunEventDTO> frames = RunService.mergeFrames(batch);
        assertEquals(3, frames.size());
        assertEquals("a", frames.get(0).textDelta());
        assertEquals("b", frames.get(1).textDelta());
        assertEquals("c", frames.get(2).textDelta());
    }

    @Test
    void nonDeltaEventsShouldKeepOrderAroundMergedFrames() {
        List<RunEventDTO> batch = List.of(
                event(1, EventTypeEnum.RUN_START),
                delta(2, EventTypeEnum.TEXT_DELTA, "x"),
                delta(3, EventTypeEnum.TEXT_DELTA, "y"),
                event(4, EventTypeEnum.RUN_END));
        List<RunEventDTO> frames = RunService.mergeFrames(batch);
        assertEquals(3, frames.size());
        assertEquals(EventTypeEnum.RUN_START.getCode(), frames.get(0).eventType());
        assertEquals("xy", frames.get(1).textDelta());
        assertEquals(EventTypeEnum.RUN_END.getCode(), frames.get(2).eventType());
    }

    @Test
    void emptyBatchShouldYieldEmptyFrames() {
        assertTrue(RunService.mergeFrames(List.of()).isEmpty());
    }

    private RunEventDTO delta(long seq, EventTypeEnum type, String text) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("delta", text);
        return new RunEventDTO("RUN_1", "SESS_1", seq, type.getCode(),
                type == EventTypeEnum.TEXT_DELTA ? "text" : "thinking", text, payload, 1000L + seq);
    }

    private RunEventDTO event(long seq, EventTypeEnum type) {
        return new RunEventDTO("RUN_1", "SESS_1", seq, type.getCode(), null, null,
                new HashMap<>(), 1000L + seq);
    }
}
