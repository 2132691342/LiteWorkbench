package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/** 运行停止原因 */
@Getter
@AllArgsConstructor
public enum StopReasonEnum {

    COMPLETED("completed", "正常完成"),
    MAX_ITERS("max_iters", "达到最大迭代"),
    ABORTED("aborted", "被用户中断"),
    ERROR("error", "执行异常"),
    ;

    /** 原因码 */
    private final String code;
    /** 原因描述 */
    private final String description;

    /** 按原因码查找 */
    public static StopReasonEnum ofCode(String code) {
        if (Objects.isNull(code)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }
}
