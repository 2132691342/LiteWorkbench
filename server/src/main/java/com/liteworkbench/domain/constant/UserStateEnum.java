package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

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

    /** 按状态码查找 */
    public static UserStateEnum ofCode(String code) {
        if (Objects.isNull(code)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
