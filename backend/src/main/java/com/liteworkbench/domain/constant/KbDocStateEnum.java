package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 知识库文档解析状态 */
@Getter
@AllArgsConstructor
public enum KbDocStateEnum {

    /** 已上传待解析 */
    PENDING("pending"),
    /** 解析中 */
    PROCESSING("processing"),
    /** 解析完成可检索 */
    READY("ready"),
    /** 解析失败，可重放 */
    FAILED("failed"),
    ;

    /** 状态码 */
    private final String code;
}
