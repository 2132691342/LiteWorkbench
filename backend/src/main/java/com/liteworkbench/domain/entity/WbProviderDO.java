package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 模型供应方实体，平台级映射 wb_provider */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbProviderDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 供应方 ID，PROV_ 前缀 */
    private String providerId;
    /** 供应方编码，预设 openai / deepseek / doubao / grok，全局唯一 */
    private String providerCode;
    private String providerName;
    /** 端点，OpenAI 兼容方必填 */
    private String baseUrl;
    /** AES 加密的 API Key */
    private String apiKeyEnc;
    /** 默认模型名，可空 */
    private String defaultModel;
    /** 上下文窗口 token 数，NULL 回落全局配置 */
    private Integer contextWindow;
    /** 最大输出 token 数，NULL 不限制 */
    private Integer maxOutputTokens;
    /** 采样温度 0~2，NULL 回落全局配置 */
    private Double temperature;
    /** top-P 0~1，NULL 不下发 */
    private Double topP;
    /** 是否支持图片输入 */
    private Boolean capVision;
    /** 是否支持工具调用 */
    private Boolean capTool;
    /** 是否支持推理（思维链）输出 */
    private Boolean capReasoning;
    private Boolean enabled;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String remark;
}
