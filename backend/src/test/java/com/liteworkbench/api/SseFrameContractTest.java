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

/** SSE 帧契约：业务帧 id 为 seq、事件名固定 run.event，心跳只发注释行不触发事件 */
class SseFrameContractTest {

    @Test
    void 业务帧应带seq与固定事件名而心跳应为纯注释行() throws Exception {
        SseEmitter businessEmitter = mock(SseEmitter.class);
        new SseRunSink(businessEmitter).send(12, "text.delta", "{\"seq\":12,\"eventType\":\"text.delta\"}");

        String business = captureFrame(businessEmitter);
        assertTrue(business.contains("id:12"), business);
        assertTrue(business.contains("event:run.event"), business);
        assertTrue(business.contains("{\"seq\":12"), business);

        SseEmitter heartbeatEmitter = mock(SseEmitter.class);
        new SseRunSink(heartbeatEmitter).ping();

        String heartbeat = captureFrame(heartbeatEmitter);
        assertTrue(heartbeat.contains("ping"), heartbeat);
        assertFalse(heartbeat.contains("data:"), heartbeat);
        assertFalse(heartbeat.contains("event:"), heartbeat);
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
