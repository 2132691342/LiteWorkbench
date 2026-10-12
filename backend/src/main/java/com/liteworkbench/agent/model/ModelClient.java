package com.liteworkbench.agent.model;

import java.util.function.BooleanSupplier;

/** 模型出站客户端：三协议各自实现，内核只依赖本接口 */
public interface ModelClient {

    /** 协议码：openai / anthropic / ollama */
    String protocol();

    /** 模型名，用于日志与事件载荷 */
    String modelName();

    /**
     * 流式对话：逐块回调增量，阻塞至流结束、超时或取消。
     * 取消以关闭连接实现，因此实现必须保证 cancelled 为真时立即返回。
     */
    ModelTypes.StreamResult stream(ModelTypes.Request request, ModelEventListener listener,
                                   BooleanSupplier cancelled);
}
