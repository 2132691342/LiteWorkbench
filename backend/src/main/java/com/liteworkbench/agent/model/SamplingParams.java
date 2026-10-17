package com.liteworkbench.agent.model;

import java.util.Objects;

/** 模型采样参数：由供应方配置解析而来，随 RunContext 下传内核；字段为 null 时回落全局配置 */
public record SamplingParams(
        Double temperature,
        Double topP,
        Integer maxOutputTokens,
        Integer contextWindow,
        boolean capVision,
        boolean capTool,
        boolean capReasoning) {

    /** 全局默认参数：未配置供应方或字段留空时使用 */
    public static SamplingParams global(Double temperature, int contextWindow) {
        return new SamplingParams(temperature, null, null, contextWindow, false, true, false);
    }

    /** 历史压缩预算：供应方未配置上下文窗口时回落全局值 */
    public int windowOr(int fallback) {
        return Objects.nonNull(contextWindow) && contextWindow > 0 ? contextWindow : fallback;
    }
}
