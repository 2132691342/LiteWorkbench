package com.liteworkbench.api;

import com.liteworkbench.service.RunSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/** 把 SseEmitter 适配成 service 侧的输出通道，HTTP 类型只出现在 api 层 */
@Slf4j
public record SseRunSink(SseEmitter emitter) implements RunSink {

    @Override
    public void send(long seq, String eventType, String payloadJson) {
        emit(SseEmitter.event()
                .id(String.valueOf(seq))
                .name("run.event")
                .data(payloadJson, MediaType.APPLICATION_JSON));
    }

    @Override
    public void ping() {
        emit(SseEmitter.event().comment("ping"));
    }

    @Override
    public void complete() {
        emitter.complete();
    }

    /** 受检 IOException 转为运行时异常，交由 service 侧统一判定为连接断开 */
    private void emit(SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            log.warn("[run.sse] 推送失败 {}", e.getMessage());
            throw new IllegalStateException("sse send failed", e);
        }
    }
}
