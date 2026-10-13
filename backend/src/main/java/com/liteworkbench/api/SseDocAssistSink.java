package com.liteworkbench.api;

import com.liteworkbench.service.DocAssistSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/** 把 SseEmitter 适配成辅助写作的输出通道，HTTP 类型只出现在 api 层 */
@Slf4j
public record SseDocAssistSink(SseEmitter emitter) implements DocAssistSink {

    @Override
    public void send(String eventType, String payloadJson) {
        emit(SseEmitter.event()
                .name("assist.event")
                .data(payloadJson, MediaType.APPLICATION_JSON));
    }

    @Override
    public void complete() {
        emitter.complete();
    }

    /** 受检 IOException 转为运行时异常，交由 service 侧判定为连接断开并取消模型调用 */
    private void emit(SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("sse send failed", e);
        }
    }
}
