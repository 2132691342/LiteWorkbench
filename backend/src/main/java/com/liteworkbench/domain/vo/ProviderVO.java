package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 模型供应方视图对象，密钥脱敏 */
@Data
public class ProviderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String providerId;
    private String providerCode;
    private String providerName;
    private String baseUrl;
    /** 脱敏密钥，如 sk-***abcd */
    private String apiKeyMasked;
    private Boolean hasApiKey;
    private String defaultModel;
    /** 上下文窗口 token 数 */
    private Integer contextWindow;
    /** 最大输出 token 数 */
    private Integer maxOutputTokens;
    /** 采样温度 */
    private Double temperature;
    /** top-P */
    private Double topP;
    /** 支持图片输入 */
    private Boolean capVision;
    /** 支持工具调用 */
    private Boolean capTool;
    /** 支持推理输出 */
    private Boolean capReasoning;
    private Boolean enabled;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
