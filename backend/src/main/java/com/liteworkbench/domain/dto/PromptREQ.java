package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 提示词新建与更新入参；内置模板不可更新 */
@Data
public class PromptREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 提示词 ID，更新时必填 */
    private String promptId;
    @NotBlank(message = "提示词标题不能为空")
    @Size(max = 128, message = "标题最长 128 字")
    private String promptTitle;
    @Size(max = 64, message = "分类最长 64 字")
    private String category;
    /** 模板正文，变量写作 {{name}} */
    @NotBlank(message = "模板正文不能为空")
    @Size(max = 8000, message = "正文最长 8000 字")
    private String content;
    /** 变量定义 JSON：[{name,label,defaultValue}] */
    @Size(max = 4000, message = "变量定义过长")
    private String variables;
}
