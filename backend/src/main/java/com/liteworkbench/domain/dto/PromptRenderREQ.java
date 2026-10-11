package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/** 提示词变量渲染入参 */
@Data
public class PromptRenderREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 提示词 ID */
    @NotBlank(message = "提示词 ID 不能为空")
    private String promptId;
    /** 变量值：name -> value */
    private Map<String, String> variables;
}
