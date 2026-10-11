package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/** 事件内容块类型 */
@Getter
@AllArgsConstructor
public enum BlockTypeEnum {

    TEXT("text", "正文"),
    THINKING("thinking", "思考"),
    TOOL_CALL("tool_call", "工具调用"),
    TOOL_RESULT("tool_result", "工具结果"),
    ;

    /** 类型码 */
    private final String code;
    /** 类型描述 */
    private final String description;

    /** 按类型码查找 */
    public static BlockTypeEnum ofCode(String code) {
        if (Objects.isNull(code)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
