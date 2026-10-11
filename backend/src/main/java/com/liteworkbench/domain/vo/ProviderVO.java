package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 模型供应方视图对象，密钥脱敏 */
@Data
public class ProviderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 供应方 ID */
    private String providerId;
    /** 供应方编码 */
    private String providerCode;
    /** 展示名 */
    private String providerName;
    /** 端点 */
    private String baseUrl;
    /** 脱敏密钥，如 sk-***abcd */
    private String apiKeyMasked;
    /** 是否已配置密钥 */
    private Boolean hasApiKey;
    /** 默认模型名 */
    private String defaultModel;
    /** 是否启用 */
    private Boolean enabled;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
