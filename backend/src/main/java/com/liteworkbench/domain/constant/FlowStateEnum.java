package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 工作流画布状态 */
@Getter
@AllArgsConstructor
public enum FlowStateEnum {

    DRAFT("draft", "草稿"),
    ENABLED("enabled", "已启用"),
    DISABLED("disabled", "已停用"),
    ;

    /** 状态码 */
    private final String code;
    /** 状态描述 */
    private final String description;
}
