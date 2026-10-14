package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 供应方预设视图：新建表单下拉的候选与默认值来源，名称由前端按 code 走 i18n */
@Data
public class ProviderPresetVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String providerCode;
    private String baseUrl;
    /** 默认模型，Ollama 为空 */
    private String defaultModel;
    /** 协议通道：openai（兼容族）/ ollama */
    private String protocol;
    private Boolean requiresKey;
}
