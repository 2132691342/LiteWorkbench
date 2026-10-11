package com.liteworkbench.agent;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.config.RuntimeProperties;
import com.liteworkbench.domain.constant.RunConst;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.domain.dto.RunEventDTO;
import com.liteworkbench.runtime.PythonRuntime;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** AgentRuntime 的 AgentScope 实现：按运行装配 ReActAgent，模型实例按 供应方+模型 缓存 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeAgentRuntime implements AgentRuntime {

    /** 模型调用超时 */
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(180L);

    private final ModelFactory modelFactory;
    private final ToolGate toolGate;
    private final AgentProperties agentProperties;
    private final RuntimeProperties runtimeProperties;
    private final PythonRuntime pythonRuntime;
    private final AgentEventAdapter adapter;

    @Override
    public Flux<RunEventDTO> stream(RunContext context) {
        ToolOutputCapture capture = new ToolOutputCapture();
        ReActAgent agent = buildAgent(context, capture);
        List<Msg> messages = assembleMessages(context);
        var seqHolder = new Object() {
            long seq = RunConst.EVENT_SEQ_START;
        };
        return agent.streamEvents(messages)
                .timeout(MODEL_TIMEOUT)
                .map(event -> adapter.adapt(event, context.runId(), context.sessionId(),
                        seqHolder.seq++, capture))
                .doFinally(signal -> log.info("[agent.stream] runId={} signal={}", context.runId(), signal));
    }

    /** 装配 Agent：模型 + 工具 + 迭代上限 + 调用超时 */
    private ReActAgent buildAgent(RunContext context, ToolOutputCapture capture) {
        Model model = modelFactory.get(context.providerCode(), context.modelName());
        return ReActAgent.builder()
                .name("lite-workbench")
                .sysPrompt(StrUtil.nullToEmpty(context.systemPrompt()))
                .model(model)
                .toolkit(buildToolkit(capture))
                .maxIters(agentProperties.getLoop().getMaxIters())
                .modelExecutionConfig(ExecutionConfig.builder().timeout(MODEL_TIMEOUT).build())
                .build();
    }

    /** 注册内置工具，启用与否由工具实现内读取 wb_tool 决定 */
    private Toolkit buildToolkit(ToolOutputCapture capture) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WorkbenchTools(toolGate, pythonRuntime, runtimeProperties, capture));
        return toolkit;
    }

    /** 历史 + 本轮输入；系统提示词已通过 builder 注入，不再重复 */
    private List<Msg> assembleMessages(RunContext context) {
        List<Msg> messages = new ArrayList<>();
        if (Objects.nonNull(context.history())) {
            messages.addAll(context.history());
        }
        messages.add(new UserMessage(context.prompt()));
        return messages;
    }
}
