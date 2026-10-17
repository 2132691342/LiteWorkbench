package com.liteworkbench.agent.loop;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.event.AgentEvent;
import com.liteworkbench.agent.event.AgentEventType;
import com.liteworkbench.agent.model.ModelClient;
import com.liteworkbench.agent.model.ModelEventListener;
import com.liteworkbench.agent.model.ModelTypes.Msg;
import com.liteworkbench.agent.model.ModelTypes.Request;
import com.liteworkbench.agent.model.ModelTypes.StreamResult;
import com.liteworkbench.agent.model.ModelTypes.ToolCall;
import com.liteworkbench.agent.model.SamplingParams;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.agent.tool.ToolCallTask;
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
    /** 采样参数：由供应方配置经 RunContext 下传，字段 null 时内核已回落全局值 */
    private final SamplingParams sampling;
    private final BooleanSupplier cancelled;

    /** 同一工具同一参数在整个运行内的调用计数 */
    private final Map<String, Integer> callCounter = new HashMap<>();

    public AgentLoop(ModelClient client, ToolRegistry tools, ToolGate toolGate, int maxIters, int repeatGuard,
                     long toolTimeoutSeconds, int modelTimeoutSeconds, SamplingParams sampling,
                     BooleanSupplier cancelled) {
        this.client = client;
        this.tools = tools;
        this.toolGate = toolGate;
        this.maxIters = Math.max(maxIters, 1);
        this.repeatGuard = Math.max(repeatGuard, 1);
        this.toolTimeoutSeconds = toolTimeoutSeconds;
        this.modelTimeoutSeconds = modelTimeoutSeconds;
        this.sampling = sampling;
        this.cancelled = cancelled;
    }

    /** 执行一次完整推理，直到产出正文、达到迭代上限或被取消；返回最终正文，可能为空 */
    public String run(List<Msg> messages, EventSink sink) {
        List<Msg> context = new ArrayList<>(messages);
        String lastText = StrUtil.EMPTY;
        for (int iter = 1; iter <= maxIters; iter++) {
            if (cancelled.getAsBoolean()) {
                // 取消发生在模型出站之前：本轮无产出，不能把历史里的助手正文当成本次答案
                return StrUtil.EMPTY;
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
            if (StrUtil.isNotBlank(result.getText())) {
                lastText = result.getText();
            }
            if (cancelled.getAsBoolean()) {
                return lastText;
            }
            List<ToolCall> calls = result.getToolCalls();
            context.add(Msg.assistant(result.getText(), calls));
            if (CollUtil.isEmpty(calls)) {
                if (StrUtil.isNotBlank(result.getText())) {
                    sink.emit(AgentEvent.builder().type(AgentEventType.AGENT_RESULT)
                            .message(result.getText()).build());
                    return result.getText();
                }
                // 只有思考没有正文不收敛：继续下一轮，由迭代上限兜底
                log.info("[agent.loop] iter={} 无正文产出，继续下一轮", iter);
            }
            executeToolBatch(context, calls, sink);
        }
        sink.emit(AgentEvent.builder().type(AgentEventType.EXCEED_MAX_ITERS).maxIters(maxIters).build());
        // 超限收敛也必须发终态正文：只靠增量帧累积会因单帧截断丢失长回答的尾部
        if (StrUtil.isNotBlank(lastText)) {
            sink.emit(AgentEvent.builder().type(AgentEventType.AGENT_RESULT).message(lastText).build());
        }
        log.warn("[agent.loop] 达到迭代上限 {}，收敛", maxIters);
        return lastText;
    }

    /** 调用模型：工具描述按平台启停过滤后下发，采样参数按供应方配置 */
    private StreamResult callModel(List<Msg> context, EventSink sink) {
        Request request = new Request();
        request.setMessages(context);
        // 模型不支持工具调用时不注入 tools：部分端点收到 tools 会直接 400
        if (sampling.capTool()) {
            request.setTools(tools.specs(toolGate::isEnabled));
        }
        request.setTemperature(sampling.temperature());
        request.setTopP(sampling.topP());
        if (Objects.nonNull(sampling.maxOutputTokens()) && sampling.maxOutputTokens() > 0) {
            request.setMaxTokens(sampling.maxOutputTokens());
        }
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

    /** 执行一批工具调用：起止事件与结果严格按下标保序，内部按工具自声明决定是否并发 */
    private void executeToolBatch(List<Msg> context, List<ToolCall> calls, EventSink sink) {
        if (CollUtil.isEmpty(calls)) {
            return;
        }
        calls.forEach(call -> sink.emit(AgentEvent.builder().type(AgentEventType.TOOL_CALL_START)
                .toolCallId(call.getId()).toolName(call.getName()).arguments(call.getArguments()).build()));

        List<String> outputs = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            outputs.add(repeatGuard(call));
        }
        // 未被重复护栏拦下的调用交给注册表：可并发的整体并发，结果按下标回填
        List<ToolCallTask> tasks = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            if (Objects.isNull(outputs.get(i))) {
                tasks.add(new ToolCallTask(calls.get(i).getName(), calls.get(i).getArguments()));
                slots.add(i);
            }
        }
        long start = System.currentTimeMillis();
        List<String> executed = tools.invokeBatch(tasks, toolTimeoutSeconds);
        for (int i = 0; i < slots.size(); i++) {
            outputs.set(slots.get(i), executed.get(i));
        }
        log.info("[agent.tools] batch={} parallel={} cost={}ms", calls.size(), tasks.size() > 1,
                System.currentTimeMillis() - start);

        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (cancelled.getAsBoolean() && Objects.isNull(outputs.get(i))) {
                // 取消抵达时未回填的调用必须占位，否则 assistant 声明的 tool_calls 缺少配对结果会被上游判 400
                outputs.set(i, "ERROR: 运行已中断，工具未执行");
            }
            String output = outputs.get(i);
            boolean failed = Objects.nonNull(output) && output.startsWith(ERROR_PREFIX);
            sink.emit(AgentEvent.builder().type(AgentEventType.TOOL_CALL_END)
                    .toolCallId(call.getId()).toolName(call.getName()).build());
            sink.emit(AgentEvent.builder().type(AgentEventType.TOOL_RESULT)
                    .toolCallId(call.getId()).toolName(call.getName()).output(output).error(failed).build());
            if (!failed && tools.isPlanTool(call.getName())) {
                // 计划类工具额外产出一条结构化计划事件，界面不必去解析工具输出文本
                sink.emit(AgentEvent.builder().type(AgentEventType.PLAN_UPDATED).planJson(output).build());
            }
            context.add(Msg.tool(call.getId(), call.getName(), output));
        }
    }

    /** 重复调用护栏：超限返回结构化文本，未超限返回 null 交由注册表执行 */
    private String repeatGuard(ToolCall call) {
        String key = call.getName() + '|' + StrUtil.nullToEmpty(call.getArguments());
        int count = callCounter.merge(key, 1, Integer::sum);
        if (count <= repeatGuard) {
            return null;
        }
        log.warn("[agent.loop] tool={} 同一调用累计 {} 次，判定为打转", call.getName(), count);
        return StrUtil.format("ERROR: 同一调用已重复 {} 次仍未推进，请更换参数或改用其他方式", count - 1);
    }
}
