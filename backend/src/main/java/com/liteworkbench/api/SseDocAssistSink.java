package com.liteworkbench.api;

import com.liteworkbench.service.DocAssistSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 把 SseEmitter 适配成辅助写作的输出通道，HTTP 类型只出现在 api 层 */
@Slf4j
public class SseDocAssistSink implements DocAssistSink {

    private final SseEmitter emitter;
    /** 断连标志：由 emitter 回调置位，服务侧据此立即取消模型出站 */
    private final AtomicBoolean gone = new AtomicBoolean(false);

    public SseDocAssistSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onError(e -> gone.set(true));
        emitter.onTimeout(() -> gone.set(true));
        emitter.onCompletion(() -> gone.set(true));
    }

    /** 下发一段辅助写作增量 */
    @Override
    public void send(String eventType, String payloadJson) {
        emit(SseEmitter.event()
                .name("assist.event")
                .data(payloadJson, MediaType.APPLICATION_JSON));
    }

    /** 结束本次流 */
    @Override
    public void complete() {
        emitter.complete();
    }

    /** 客户端是否已断开 */
    @Override
    public boolean isClientGone() {
        return gone.get();
    }

    /** 受检 IOException 转为运行时异常，交由 service 侧判定为连接断开并取消模型调用 */
    private void emit(SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            gone.set(true);
            throw new IllegalStateException("sse send failed", e);
        }
    }
}
