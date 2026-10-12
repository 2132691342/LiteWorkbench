package com.liteworkbench.agent;

import com.liteworkbench.agent.model.ModelTypes.Msg;

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
        String prompt) {
}
