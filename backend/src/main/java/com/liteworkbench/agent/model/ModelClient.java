package com.liteworkbench.agent.model;

import java.util.function.BooleanSupplier;

/** 模型出站客户端：各协议通道各自实现，内核只依赖本接口 */
public interface ModelClient {

    /** 协议码：openai（兼容族，覆盖 OpenAI / DeepSeek / Doubao / Grok 与自定义端点）/ ollama */
    String protocol();

    /** 模型名，用于日志与事件载荷 */
    String modelName();

    /** 流式对话：逐块回调增量，阻塞至流结束、超时或取消；取消以关闭连接实现，cancelled 为真时必须立即返回 */
    ModelTypes.StreamResult stream(ModelTypes.Request request, ModelEventListener listener,
                                   BooleanSupplier cancelled);
}
