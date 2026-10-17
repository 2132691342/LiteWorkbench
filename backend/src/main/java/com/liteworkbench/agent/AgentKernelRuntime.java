package com.liteworkbench.agent;

import com.liteworkbench.agent.event.AgentEvent;
import com.liteworkbench.agent.event.AgentEventType;
import com.liteworkbench.agent.loop.AgentLoop;
import com.liteworkbench.agent.model.ModelClient;
import com.liteworkbench.agent.model.ModelTypes.Msg;
import com.liteworkbench.agent.model.SamplingParams;
import com.liteworkbench.agent.spi.KbSearchPort;
import com.liteworkbench.agent.spi.MemoryPort;
import com.liteworkbench.agent.spi.SubRunPort;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.agent.tool.ToolOwner;
import com.liteworkbench.agent.tool.ToolRegistry;
import com.liteworkbench.agent.tool.WorkbenchTools;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.config.ExecutorConfig;
import com.liteworkbench.config.RuntimeProperties;
import com.liteworkbench.domain.constant.AppConst;
import com.liteworkbench.domain.constant.RunConst;
import com.liteworkbench.domain.dto.RunEventDTO;
import com.liteworkbench.runtime.PythonRuntime;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** AgentRuntime 的内核实现：按运行装配 Loop 与工具，模型客户端按 供应方+模型 复用 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentKernelRuntime implements AgentRuntime {

    private final ModelFactory modelFactory;
    private final ToolGate toolGate;
    private final MemoryPort memoryPort;
    private final SubRunPort subRunPort;
    private final AgentProperties agentProperties;
    private final RuntimeProperties runtimeProperties;
    private final PythonRuntime pythonRuntime;
    private final KbSearchPort kbSearchPort;
    private final AgentEventAdapter adapter;

    /** 编排池：一次运行一个任务，阻塞在模型与工具上；线程由 ExecutorConfig 统一回收 */
    @Qualifier(ExecutorConfig.RUN_EXECUTOR)
    private final ExecutorService executor;
    /** 工具池：注册表每次运行都新建，共享同一池避免线程数随运行数增长 */
    @Qualifier(ExecutorConfig.TOOL_EXECUTOR)
    private final ExecutorService toolExecutor;

    /** 启动期干跑注册：工具 Schema 错误在启动时暴露，不留到首次运行 */
    @PostConstruct
    public void validateToolsAtStartup() {
        ToolRegistry registry = new ToolRegistry(toolExecutor);
        registry.register(new WorkbenchTools(toolGate, kbSearchPort, memoryPort, subRunPort,
                pythonRuntime, runtimeProperties, ToolOwner.startupCheck()));
        int count = registry.names().size();
        log.info("[agent.tools] 工具 Schema 启动校验通过 count={}", count);
    }

    @Override
    /** 在运行编排池上启动一次运行，返回可取消的句柄 */
    public RunHandle start(RunContext context, Consumer<RunEventDTO> sink) {
        RunHandle handle = new RunHandle(context.runId());
        AtomicLong seq = new AtomicLong(RunConst.EVENT_SEQ_START);
        ModelClient client = modelFactory.client(context.providerCode(), context.modelName());
        SamplingParams sampling = Objects.nonNull(context.sampling())
                ? context.sampling()
                : SamplingParams.global(agentProperties.getModel().getTemperature(),
                        agentProperties.getModel().getContextWindow());
        int window = sampling.windowOr(agentProperties.getModel().getContextWindow());
        ToolRegistry registry = new ToolRegistry(toolExecutor);
        registry.register(new WorkbenchTools(toolGate, kbSearchPort, memoryPort, subRunPort,
                pythonRuntime, runtimeProperties,
                new ToolOwner(context.userId(), context.runId(), context.sessionId())));
        List<Msg> messages = AgentMemory.assemble(context.history(), context.systemPrompt(), context.prompt(),
                window, agentProperties.getModel().getReserveTokens());
        AgentLoop loop = new AgentLoop(client, registry, toolGate,
                agentProperties.getLoop().getMaxIters(), agentProperties.getLoop().getRepeatGuard(),
                agentProperties.getLoop().getToolTimeoutSeconds(),
                agentProperties.getModel().getStreamTimeoutSeconds(),
                sampling, handle::isCancelled);
        executor.submit(() -> run(handle, context, loop, messages, registry, seq, sink));
        log.info("[agent.start] runId={} provider={} model={} history={} messages={}",
                context.runId(), context.providerCode(), context.modelName(),
                Objects.isNull(context.history()) ? 0 : context.history().size(), messages.size());
        return handle;
    }

    /** 后台执行：MDC 注入运行链路标识；事件带 seq 顺序回调，结束或异常统一收敛句柄 */
    private void run(RunHandle handle, RunContext context, AgentLoop loop, List<Msg> messages,
                     ToolRegistry registry, AtomicLong seq, Consumer<RunEventDTO> sink) {
        long started = System.currentTimeMillis();
        MDC.put(AppConst.MDC_USER_ID, context.userId());
        MDC.put(AppConst.MDC_SESSION_ID, context.sessionId());
        MDC.put(AppConst.MDC_RUN_ID, context.runId());
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
            log.info("[agent.run] runId={} 内核结束 cost={}ms", context.runId(),
                    System.currentTimeMillis() - started);
            MDC.remove(AppConst.MDC_USER_ID);
            MDC.remove(AppConst.MDC_SESSION_ID);
            MDC.remove(AppConst.MDC_RUN_ID);
        }
    }
}
