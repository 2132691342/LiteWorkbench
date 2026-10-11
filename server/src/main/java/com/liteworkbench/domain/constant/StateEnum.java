package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/** 通用业务状态，写入各表 state 字段 */
@Getter
@AllArgsConstructor
public enum StateEnum {

    NORMAL("normal", "正常"),
    DISABLED("disabled", "停用"),
    ARCHIVED("archived", "已归档"),
    ;

    /** 状态码 */
    private final String code;
    /** 状态描述 */
    private final String description;

    /** 按状态码查找 */
    public static StateEnum ofCode(String code) {
        if (Objects.isNull(code)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
