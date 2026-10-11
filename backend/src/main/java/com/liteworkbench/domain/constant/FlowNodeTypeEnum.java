package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 工作流节点类型 */
@Getter
@AllArgsConstructor
public enum FlowNodeTypeEnum {

    START("start", "开始"),
    LLM("llm", "模型生成"),
    TOOL("tool", "工具调用"),
    PYTHON("python", "Python 脚本"),
    KNOWLEDGE("knowledge", "知识库检索"),
    END("end", "结束"),
    ;

    /** 类型码 */
    private final String code;
    /** 类型描述 */
    private final String description;

    /** 按码取类型，未命中返回 null 由调用方判定 */
    public static FlowNodeTypeEnum ofCode(String code) {
        for (FlowNodeTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
