package com.liteworkbench.agent.loop;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.event.AgentEvent;
import com.liteworkbench.agent.event.AgentEventType;
import com.liteworkbench.agent.model.ModelClient;
import com.liteworkbench.agent.model.ModelEventListener;
import com.liteworkbench.agent.model.ModelTypes;
import com.liteworkbench.agent.model.ModelTypes.Msg;
import com.liteworkbench.agent.model.ModelTypes.Request;
import com.liteworkbench.agent.model.ModelTypes.StreamResult;
import com.liteworkbench.agent.model.ModelTypes.ToolCall;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** ReAct 推理循环：零框架依赖，模型、工具、事件出口全部构造注入，可离线单测 */
@Slf4j
public class AgentLoop {

    /** 事件出口：由 Runtime 转成带 seq 的工作台事件 */
    @FunctionalInterface
    public interface EventSink {
        void emit(AgentEvent event);
    }

    /** 工具失败前缀，与工具实现约定一致 */
    private static final String ERROR_PREFIX = "ERROR:";

    private final ModelClient client;
    private final ToolRegistry tools;
    private final ToolGate toolGate;
    private final int maxIters;
    private final int repeatGuard;
    private final long toolTimeoutSeconds;
    private final int modelTimeoutSeconds;
    private final Double temperature;
    private final BooleanSupplier cancelled;

    /** 同一工具同一参数在整个运行内的调用计数 */
    private final Map<String, Integer> callCounter = new HashMap<>();

    public AgentLoop(ModelClient client, ToolRegistry tools, ToolGate toolGate, int maxIters, int repeatGuard,
                     long toolTimeoutSeconds, int modelTimeoutSeconds, Double temperature,
                     BooleanSupplier cancelled) {
        this.client = client;
        this.tools = tools;
        this.toolGate = toolGate;
        this.maxIters = Math.max(maxIters, 1);
        this.repeatGuard = Math.max(repeatGuard, 1);
        this.toolTimeoutSeconds = toolTimeoutSeconds;
        this.modelTimeoutSeconds = modelTimeoutSeconds;
        this.temperature = temperature;
        this.cancelled = cancelled;
    }

    /**
     * 执行一次完整推理：思考 → 工具调用 → 观察，直到产出正文、达到迭代上限或被取消。
     * 返回最终正文，可能为空（被取消且尚未产出内容时）。
     */
    public String run(List<Msg> messages, EventSink sink) {
        List<Msg> context = new ArrayList<>(messages);
        for (int iter = 1; iter <= maxIters; iter++) {
            if (cancelled.getAsBoolean()) {
                return lastAssistantText(context);
            }
            long started = System.currentTimeMillis();
            sink.emit(AgentEvent.builder().type(AgentEventType.MODEL_CALL_START).build());
            StreamResult result = callModel(context, sink);
            sink.emit(AgentEvent.builder()
                    .type(AgentEventType.MODEL_CALL_END)
                    .inputTokens(result.getUsage().getInputTokens())
                    .outputTokens(result.getUsage().getOutputTokens())
                    .durationMs(System.currentTimeMillis() - started)
                    .build());
            if (cancelled.getAsBoolean()) {
                return result.getText();
            }
            List<ToolCall> calls = result.getToolCalls();
            context.add(Msg.assistant(result.getText(), calls));
            if (CollUtil.isEmpty(calls)) {
                if (StrUtil.isNotBlank(result.getText())) {
                    sink.emit(AgentEvent.builder().type(AgentEventType.AGENT_RESULT)
                            .message(result.getText()).build());
                }
                return result.getText();
            }
            for (ToolCall call : calls) {
                if (cancelled.getAsBoolean()) {
                    return result.getText();
                }
                executeTool(context, call, sink);
            }
        }
        sink.emit(AgentEvent.builder().type(AgentEventType.EXCEED_MAX_ITERS).maxIters(maxIters).build());
        log.warn("[agent.loop] 达到迭代上限 {}，收敛", maxIters);
        return lastAssistantText(context);
    }

    /** 调用模型：工具描述按平台启停过滤后下发 */
    private StreamResult callModel(List<Msg> context, EventSink sink) {
        Request request = new Request();
        request.setMessages(context);
        request.setTools(tools.specs(toolGate::isEnabled));
        request.setTemperature(temperature);
        request.setTimeoutSeconds(modelTimeoutSeconds);
        return client.stream(request, new ModelEventListener() {

            @Override
            public void onText(String delta) {
                sink.emit(AgentEvent.builder().type(AgentEventType.TEXT_DELTA).textDelta(delta).build());
            }

            @Override
            public void onThinking(String delta) {
                sink.emit(AgentEvent.builder().type(AgentEventType.THINKING_DELTA).textDelta(delta).build());
            }
        }, cancelled);
    }

    /** 执行一次工具：重复调用护栏 → 注册表调用 → 结果按调用下标回填 */
    private void executeTool(List<Msg> context, ToolCall call, EventSink sink) {
        sink.emit(AgentEvent.builder().type(AgentEventType.TOOL_CALL_START)
                .toolCallId(call.getId()).toolName(call.getName()).arguments(call.getArguments()).build());
        String key = call.getName() + '|' + StrUtil.nullToEmpty(call.getArguments());
        int count = callCounter.merge(key, 1, Integer::sum);
        String output;
        if (count > repeatGuard) {
            output = StrUtil.format("ERROR: 同一调用已重复 {} 次仍未推进，请更换参数或改用其他方式",
                    count - 1);
        } else {
            output = tools.invoke(call.getName(), call.getArguments(), toolTimeoutSeconds);
        }
        boolean failed = Objects.nonNull(output) && output.startsWith(ERROR_PREFIX);
        sink.emit(AgentEvent.builder().type(AgentEventType.TOOL_CALL_END)
                .toolCallId(call.getId()).toolName(call.getName()).build());
        sink.emit(AgentEvent.builder().type(AgentEventType.TOOL_RESULT)
                .toolCallId(call.getId()).toolName(call.getName()).output(output).error(failed).build());
        context.add(Msg.tool(call.getId(), call.getName(), output));
    }

    /** 取最后一条有正文的助手消息，用于取消或超限时兜底产出 */
    private String lastAssistantText(List<Msg> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg message = context.get(i);
            if (ModelTypes.Role.ASSISTANT.equals(message.getRole()) && StrUtil.isNotBlank(message.getContent())) {
                return message.getContent();
            }
        }
        return StrUtil.EMPTY;
    }
}
