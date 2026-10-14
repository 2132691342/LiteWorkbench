package com.liteworkbench.domain.dto;

import com.liteworkbench.domain.constant.SessionConst;
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
    @Size(max = SessionConst.TITLE_MAX_LENGTH, message = "标题最长 128 字")
    private String title;
    private String providerCode;
    private String modelName;
    @Size(max = SessionConst.SYSTEM_PROMPT_MAX_LENGTH, message = "系统提示词最长 8000 字")
    private String systemPrompt;
}
