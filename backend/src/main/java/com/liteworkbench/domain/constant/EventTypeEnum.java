package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 归一化后的运行事件类型 */
@Getter
@AllArgsConstructor
public enum EventTypeEnum {

    RUN_START("run.start", "运行开始"),
    THINKING_DELTA("thinking.delta", "思考增量"),
    TEXT_DELTA("text.delta", "正文增量"),
    TOOL_CALL("tool.call", "工具调用"),
    TOOL_RESULT("tool.result", "工具结果"),
    MODEL_CALL("model.call", "模型调用"),
    RUN_RESULT("run.result", "运行结果"),
    RUN_EXCEED("run.exceed", "超出迭代"),
    RUN_END("run.end", "运行结束"),
    RUN_ERROR("run.error", "运行错误"),
    FLOW_NODE("flow.node", "工作流节点"),
    CUSTOM("custom", "未归类事件"),
    ;

    /** 事件类型码 */
    private final String code;
    /** 事件描述 */
    private final String description;
}
