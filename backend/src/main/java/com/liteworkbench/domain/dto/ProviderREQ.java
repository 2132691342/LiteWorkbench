package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 模型供应方新建与更新入参 */
@Data
public class ProviderREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 供应方 ID，更新时必填 */
    private String providerId;
    @NotBlank(message = "供应方编码不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,23}$", message = "供应方编码须为小写字母开头的字母数字下划线")
    private String providerCode;
    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称最长 64 字")
    private String providerName;
    /** 端点，OpenAI 兼容方必填 */
    private String baseUrl;
    /** API Key，脱敏值表示不修改 */
    private String apiKey;
    private String defaultModel;
    /** 上下文窗口 token 数，空表示回落全局配置 */
    private Integer contextWindow;
    /** 最大输出 token 数，空表示不限制 */
    private Integer maxOutputTokens;
    /** 采样温度 0~2 */
    private Double temperature;
    /** top-P 0~1 */
    private Double topP;
    /** 支持图片输入 */
    private Boolean capVision;
    /** 支持工具调用 */
    private Boolean capTool;
    /** 支持推理输出 */
    private Boolean capReasoning;
    private Boolean enabled;
}
