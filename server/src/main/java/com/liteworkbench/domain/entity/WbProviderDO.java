package com.liteworkbench.domain.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 模型供应方实体，平台级映射 wb_provider */
@Data
public class WbProviderDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 供应方 ID，PROV_ 前缀 */
    private String providerId;
    /** 供应方编码 openai / anthropic / ollama，全局唯一 */
    private String providerCode;
    /** 展示名 */
    private String providerName;
    /** 端点，OpenAI 兼容方必填 */
    private String baseUrl;
    /** AES 加密的 API Key，ollama 可空 */
    private String apiKeyEnc;
    /** 默认模型名，可空 */
    private String defaultModel;
    /** 是否启用 */
    private Boolean enabled;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    /** 备注 */
    private String remark;
}
