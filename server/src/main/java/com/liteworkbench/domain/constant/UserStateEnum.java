package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 用户状态 */
@Getter
@AllArgsConstructor
public enum UserStateEnum {

    NORMAL("normal", "正常"),
    DISABLED("disabled", "停用"),
    LOCKED("locked", "锁定"),
    ;

    /** 状态码 */
    private final String code;
    /** 状态描述 */
    private final String description;
}
