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
    private Boolean enabled;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
