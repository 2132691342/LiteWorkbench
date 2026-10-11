package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 创作文档业务状态，只影响列表筛选，不限制编辑 */
@Getter
@AllArgsConstructor
public enum DocStateEnum {

    DRAFT("draft", "草稿"),
    PUBLISHED("published", "已发布"),
    ARCHIVED("archived", "已归档"),
    ;

    /** 状态码 */
    private final String code;
    /** 状态描述 */
    private final String description;
}
