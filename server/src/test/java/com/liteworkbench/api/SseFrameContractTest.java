package com.liteworkbench.api;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** SSE 帧契约：id 为 seq、事件名固定 run.event、data 为 JSON 原文、心跳只发注释行 */
class SseFrameContractTest {

    @Test
    void frameShouldCarrySeqAsIdAndJsonPayload() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseRunSink sink = new SseRunSink(emitter);

        sink.send(12, "text.delta", "{\"seq\":12,\"eventType\":\"text.delta\"}");

        String frame = captureFrame(emitter);
        assertTrue(frame.contains("id:12"), frame);
        assertTrue(frame.contains("event:run.event"), frame);
        assertTrue(frame.contains("{\"seq\":12"), frame);
    }

    @Test
    void heartbeatShouldBeCommentOnly() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseRunSink sink = new SseRunSink(emitter);

        sink.ping();

        String frame = captureFrame(emitter);
        assertTrue(frame.contains("ping"), frame);
        assertFalse(frame.contains("data:"), frame);
        assertFalse(frame.contains("event:"), frame);
    }

    /** 捕获 emitter 收到的帧并拼成文本；帧实现类为包可见，反射读取需放开访问 */
    private String captureFrame(SseEmitter emitter) throws Exception {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        Method build = captor.getValue().getClass().getMethod("build");
        build.setAccessible(true);
        Object built = build.invoke(captor.getValue());
        List<String> parts = new ArrayList<>();
        for (Object item : (Iterable<?>) built) {
            Method getData = item.getClass().getMethod("getData");
            getData.setAccessible(true);
            parts.add(String.valueOf(getData.invoke(item)));
        }
        return String.join("\n", parts);
    }
}
