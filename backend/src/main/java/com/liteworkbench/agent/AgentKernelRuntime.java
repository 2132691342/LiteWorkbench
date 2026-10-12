package com.liteworkbench.agent;

import cn.hutool.core.thread.ThreadUtil;
import com.liteworkbench.agent.event.AgentEvent;
import com.liteworkbench.agent.event.AgentEventType;
import com.liteworkbench.agent.loop.AgentLoop;
import com.liteworkbench.agent.model.ModelClient;
import com.liteworkbench.agent.model.ModelTypes.Msg;
import com.liteworkbench.agent.spi.KbSearchPort;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.agent.tool.ToolRegistry;
import com.liteworkbench.agent.tool.WorkbenchTools;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.config.RuntimeProperties;
import com.liteworkbench.domain.constant.RunConst;
import com.liteworkbench.domain.dto.RunEventDTO;
import com.liteworkbench.runtime.PythonRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** AgentRuntime 的内核实现：按运行装配 Loop 与工具，模型客户端按 供应方+模型 复用 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentKernelRuntime implements AgentRuntime {

    private final ModelFactory modelFactory;
    private final ToolGate toolGate;
    private final AgentProperties agentProperties;
    private final RuntimeProperties runtimeProperties;
    private final PythonRuntime pythonRuntime;
    private final KbSearchPort kbSearchPort;
    private final AgentEventAdapter adapter;

    /** 运行执行线程池：一次运行一个线程，阻塞在模型与工具上 */
    private final ExecutorService executor =
            Executors.newCachedThreadPool(ThreadUtil.newNamedThreadFactory("lw-agent", true));

    @Override
    public RunHandle start(RunContext context, Consumer<RunEventDTO> sink) {
        RunHandle handle = new RunHandle(context.runId());
        AtomicLong seq = new AtomicLong(RunConst.EVENT_SEQ_START);
        ModelClient client = modelFactory.client(context.providerCode(), context.modelName());
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WorkbenchTools(toolGate, kbSearchPort, pythonRuntime, runtimeProperties,
                context.userId()));
        List<Msg> messages = AgentMemory.assemble(context.history(), context.systemPrompt(), context.prompt(),
                agentProperties.getModel().getContextWindow(), agentProperties.getModel().getReserveTokens());
        AgentLoop loop = new AgentLoop(client, registry, toolGate,
                agentProperties.getLoop().getMaxIters(), agentProperties.getLoop().getRepeatGuard(),
                agentProperties.getLoop().getToolTimeoutSeconds(),
                agentProperties.getModel().getStreamTimeoutSeconds(),
                agentProperties.getModel().getTemperature(), handle::isCancelled);
        executor.submit(() -> run(handle, context, loop, messages, registry, seq, sink));
        log.info("[agent.start] runId={} provider={} model={} history={} messages={}",
                context.runId(), context.providerCode(), context.modelName(),
                context.history() == null ? 0 : context.history().size(), messages.size());
        return handle;
    }

    /** 后台执行：事件带 seq 顺序回调，结束或异常统一收敛句柄 */
    private void run(RunHandle handle, RunContext context, AgentLoop loop, List<Msg> messages,
                     ToolRegistry registry, AtomicLong seq, Consumer<RunEventDTO> sink) {
        long started = System.currentTimeMillis();
        try {
            sink.accept(adapter.adapt(AgentEvent.builder().type(AgentEventType.AGENT_START).build(),
                    context.runId(), context.sessionId(), seq.getAndIncrement()));
            loop.run(messages, event -> sink.accept(adapter.adapt(event, context.runId(),
                    context.sessionId(), seq.getAndIncrement())));
            handle.complete();
        } catch (Exception e) {
            log.error("[agent.run] runId={} 运行异常 {}", context.runId(), e.getMessage(), e);
            handle.fail(e);
        } finally {
            registry.shutdown();
            log.info("[agent.run] runId={} 内核结束 cost={}ms", context.runId(),
                    System.currentTimeMillis() - started);
        }
    }
}
