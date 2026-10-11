package com.liteworkbench.service;

/** 运行事件的输出通道：service 只依赖此接口，不感知 SSE 与任何 HTTP 类型 */
public interface RunSink {

    /** 推送一条事件，seq 用作帧 id，payloadJson 为事件序列化结果 */
    void send(long seq, String eventType, String payloadJson);

    /** 心跳帧，维持连接 */
    void ping();

    /** 正常收尾 */
    void complete();
}
