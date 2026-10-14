package com.liteworkbench.service;

/** 辅助写作流式输出通道：service 只依赖本接口，SSE 适配在 api 层 */
public interface DocAssistSink {

    /** 推送一帧，eventType 为 assist.start / assist.delta / assist.end / assist.error */
    void send(String eventType, String payloadJson);

    void complete();

    /** 客户端是否已断开：断连后模型出站必须立即关闭连接，不能只等下一次推送失败 */
    boolean isClientGone();
}
