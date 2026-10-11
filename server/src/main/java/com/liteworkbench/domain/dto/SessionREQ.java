package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 会话新建与更新入参 */
@Data
public class SessionREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 会话 ID，更新时必填 */
    private String sessionId;
    /** 标题 */
    @Size(max = 128, message = "标题最长 128 字")
    private String title;
    /** 供应方编码 */
    private String providerCode;
    /** 模型名 */
    private String modelName;
    /** 系统提示词 */
    @Size(max = 8000, message = "系统提示词最长 8000 字")
    private String systemPrompt;
}
