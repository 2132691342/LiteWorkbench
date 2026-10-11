package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 流式对话入参 */
@Data
public class ChatStreamREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标会话 ID */
    @NotBlank(message = "会话不能为空")
    private String sessionId;
    /** 用户输入 */
    @NotBlank(message = "内容不能为空")
    @Size(max = 32000, message = "单次输入不能超过 32000 字")
    private String content;
}
