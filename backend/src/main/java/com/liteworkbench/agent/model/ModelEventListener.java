package com.liteworkbench.agent.model;

/** 模型流式回调：协议差异在实现类内被拉平，内核只认这四类增量 */
public interface ModelEventListener {

    /** 正文增量 */
    default void onText(String delta) {
    }

    /** 思考增量，模型不返回思考时不会触发 */
    default void onThinking(String delta) {
    }

    /** 一次完整的工具调用（参数已拼装完毕） */
    default void onToolCall(ModelTypes.ToolCall call) {
    }

    /** 用量，部分端点不返回则不会触发 */
    default void onUsage(ModelTypes.Usage usage) {
    }
}
