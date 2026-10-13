package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/** 供应方预设：端点与默认模型与 hutool-ai 各 XxxConfig 常量对齐，前后端共用一份元数据 */
@Getter
@AllArgsConstructor
public enum ProviderPresetEnum {

    /** OpenAI */
    OPENAI("openai", "https://api.openai.com/v1", "gpt-4o"),
    /** DeepSeek */
    DEEPSEEK("deepseek", "https://api.deepseek.com", "deepseek-chat"),
    /** 豆包（火山方舟 v3） */
    DOUBAO("doubao", "https://ark.cn-beijing.volces.com/api/v3", "doubao-1.5-lite-32k-250115"),
    /** Grok（xAI） */
    GROK("grok", "https://api.x.ai/v1", "grok-2-1212"),
    /** Ollama 本地，模型由部署方填写 */
    OLLAMA("ollama", "http://localhost:11434", "");

    /** 供应方码，全局唯一 */
    private final String code;
    /** 默认端点 */
    private final String baseUrl;
    /** 默认模型，Ollama 留空由部署方填写 */
    private final String defaultModel;

    /** 按码查找预设（忽略大小写），未知返回 null */
    public static ProviderPresetEnum ofCode(String code) {
        if (Objects.isNull(code)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(preset -> preset.code.equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }

    /** 是否 Ollama 协议通道（其余预设均走 OpenAI 兼容通道） */
    public static boolean isOllama(String code) {
        return OLLAMA.code.equalsIgnoreCase(Objects.toString(code, "").trim());
    }
}
