package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/** 消息角色 */
@Getter
@AllArgsConstructor
public enum MessageRoleEnum {

    USER("user", "用户"),
    ASSISTANT("assistant", "助手"),
    TOOL("tool", "工具"),
    SYSTEM("system", "系统"),
    ;

    /** 角色码 */
    private final String code;
    /** 角色描述 */
    private final String description;

    /** 按角色码查找 */
    public static MessageRoleEnum ofCode(String code) {
        if (Objects.isNull(code)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
