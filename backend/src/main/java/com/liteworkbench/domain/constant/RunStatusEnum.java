package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/** 运行状态机：queued → running → success / failed / aborted */
@Getter
@AllArgsConstructor
public enum RunStatusEnum {

    QUEUED("queued", "排队中", false),
    RUNNING("running", "执行中", false),
    SUCCESS("success", "成功", true),
    FAILED("failed", "失败", true),
    ABORTED("aborted", "已中断", true),
    ;

    /** 状态码 */
    private final String code;
    /** 状态描述 */
    private final String description;
    /** 是否终态 */
    private final boolean terminal;

    /** 按状态码查找 */
    public static RunStatusEnum ofCode(String code) {
        if (Objects.isNull(code)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> Objects.equals(item.getCode(), code))
                .findFirst()
                .orElse(null);
    }

    /** 是否终态 */
    public static boolean isTerminal(String code) {
        RunStatusEnum status = ofCode(code);
        return Objects.nonNull(status) && status.isTerminal();
    }
}
