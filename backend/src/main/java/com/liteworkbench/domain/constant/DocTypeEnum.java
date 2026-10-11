package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 创作文档正文格式 */
@Getter
@AllArgsConstructor
public enum DocTypeEnum {

    MARKDOWN("markdown", "Markdown 源码"),
    RICH("rich", "富文本 HTML"),
    CODE("code", "纯文本代码"),
    ;

    /** 类型码 */
    private final String code;
    /** 类型描述 */
    private final String description;
}
