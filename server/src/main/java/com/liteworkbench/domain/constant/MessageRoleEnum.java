package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

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
}
