package com.liteworkbench.agent;

import com.liteworkbench.agent.model.ModelTypes.Msg;
import com.liteworkbench.agent.model.SamplingParams;

import java.util.List;

/** 一次运行的全部装配信息，由服务层组装后交给 AgentRuntime */
public record RunContext(
        String userId,
        String sessionId,
        String runId,
        String providerCode,
        String modelName,
        String systemPrompt,
        List<Msg> history,
        String prompt,
        /** 模型采样参数，null 时内核回落全局配置 */
        SamplingParams sampling) {
}
