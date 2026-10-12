package com.liteworkbench.agent.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 内核事件类型：封闭集合，模型协议差异在 model 包内已被拉平 */
@Getter
@AllArgsConstructor
public enum AgentEventType {

    AGENT_START("agent.start", "运行开始"),
    THINKING_DELTA("thinking.delta", "思考增量"),
    TEXT_DELTA("text.delta", "正文增量"),
    TOOL_CALL_START("tool.call.start", "工具调用开始"),
    TOOL_CALL_END("tool.call.end", "工具调用结束"),
    TOOL_RESULT("tool.result", "工具结果"),
    MODEL_CALL_START("model.call.start", "模型调用开始"),
    MODEL_CALL_END("model.call.end", "模型调用结束"),
    AGENT_RESULT("agent.result", "运行产出"),
    EXCEED_MAX_ITERS("agent.exceed", "超出迭代上限"),
    AGENT_END("agent.end", "运行结束"),
    AGENT_ERROR("agent.error", "运行错误"),
    ;

    /** 事件类型码 */
    private final String code;
    /** 事件描述 */
    private final String description;
}
