package com.liteworkbench.service;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.agent.ModelCaller;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.domain.constant.AgentConst;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.constant.FlowConst;
import com.liteworkbench.domain.constant.FlowNodeTypeEnum;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.RunStatusEnum;
import com.liteworkbench.domain.dto.InvokeToolREQ;
import com.liteworkbench.domain.entity.WbEventDO;
import com.liteworkbench.domain.entity.WbFlowDO;
import com.liteworkbench.domain.entity.WbRunDO;
import com.liteworkbench.domain.vo.FlowRunVO;
import com.liteworkbench.mapper.WbEventMapper;
import com.liteworkbench.mapper.WbRunMapper;
import com.liteworkbench.runtime.ExecRequest;
import com.liteworkbench.runtime.ExecResult;
import com.liteworkbench.runtime.PythonRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 工作流执行：按拓扑序串行跑节点，逐节点落事件，失败即中止 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowEngine {

    /** 明细回放的事件读取上限 */
    private static final long EVENT_PAGE_SIZE = 500L;

    private final FlowService flowService;
    private final ModelCaller modelCaller;
    private final PythonRuntime pythonRuntime;
    private final KbDocService kbDocService;
    private final ToolService toolService;
    private final WbRunMapper runMapper;
    private final WbEventMapper eventMapper;

    /** 执行一次工作流，返回 Run 与逐节点结果 */
    public FlowRunVO run(WbFlowDO flow, Map<String, String> inputs) {
        String userId = LoginContext.userId();
        FlowService.FlowGraph graph = flowService.parse(flow.getGraphJson());
        List<FlowService.FlowNode> nodes = flowService.topoSort(graph.nodes(), graph.outgoing());

        String runId = IdGenerator.generate(IdPrefixConst.RUN);
        insertRun(runId, userId, flow.getFlowName());
        runMapper.markRunning(runId);
        flowService.markLastRun(flow.getFlowId(), userId, runId);

        long start = System.currentTimeMillis();
        Map<String, String> context = new LinkedHashMap<>();
        if (Objects.nonNull(inputs)) {
            inputs.forEach((key, value) -> context.put(key, StrUtil.nullToEmpty(value)));
        }
        List<FlowRunVO.FlowNodeResult> results = new ArrayList<>();
        String status = RunStatusEnum.SUCCESS.getCode();
        String errorMessage = null;
        String output = StrUtil.EMPTY;

        for (FlowService.FlowNode node : nodes) {
            long nodeStart = System.currentTimeMillis();
            FlowRunVO.FlowNodeResult result = new FlowRunVO.FlowNodeResult();
            result.setNodeId(node.id());
            result.setNodeType(node.type());
            result.setNodeLabel(node.label());
            try {
                String text = executeNode(node, userId, context);
                result.setStatus(RunStatusEnum.SUCCESS.getCode());
                result.setOutput(StrUtil.sub(text, 0, FlowConst.NODE_OUTPUT_MAX));
                if (FlowNodeTypeEnum.END.getCode().equals(node.type())) {
                    output = text;
                } else if (StrUtil.isNotBlank(node.config().getStr("outputVar"))) {
                    context.put(node.config().getStr("outputVar"), text);
                } else {
                    context.put(node.id(), text);
                }
            } catch (BizException e) {
                result.setStatus(RunStatusEnum.FAILED.getCode());
                result.setErrorMessage(e.getMessage());
                status = RunStatusEnum.FAILED.getCode();
                errorMessage = StrUtil.format("节点 {} 执行失败：{}", node.label(), e.getMessage());
                results.add(result);
                log.warn("[flow.run] runId={} nodeId={} 失败 {}", runId, node.id(), e.getMessage());
                break;
            }
            result.setDurationMs(System.currentTimeMillis() - nodeStart);
            results.add(result);
            persistNodeEvent(runId, userId, results.size(), result);
        }

        long duration = System.currentTimeMillis() - start;
        settleRun(runId, status, results.size(), duration);
        FlowRunVO vo = new FlowRunVO();
        vo.setRunId(runId);
        vo.setFlowId(flow.getFlowId());
        vo.setStatus(status);
        vo.setErrorMessage(errorMessage);
        vo.setOutput(output);
        vo.setDurationMs(duration);
        vo.setNodes(results);
        log.info("[flow.run] userId={} flowId={} runId={} status={} nodes={} cost={}ms",
                userId, flow.getFlowId(), runId, status, results.size(), duration);
        return vo;
    }

    /** 运行明细：从 wb_run 与事件流还原节点级结果 */
    public FlowRunVO detail(String runId, String userId) {
        WbRunDO run = runMapper.selectByIdAndUser(runId, userId);
        if (Objects.isNull(run)) {
            throw new BizException(ErrorCodeEnum.RUN_NOT_FOUND, runId);
        }
        List<FlowRunVO.FlowNodeResult> nodes = eventMapper.selectByRunAfterSeq(runId, 0, EVENT_PAGE_SIZE)
                .stream()
                .filter(event -> EventTypeEnum.FLOW_NODE.getCode().equals(event.getEventType()))
                .map(this::toNodeResult)
                .toList();
        FlowRunVO vo = new FlowRunVO();
        vo.setRunId(runId);
        vo.setStatus(run.getState());
        vo.setErrorMessage(RunStatusEnum.FAILED.getCode().equals(run.getState()) ? "运行失败，请查看节点明细" : null);
        vo.setOutput(nodes.isEmpty() ? StrUtil.EMPTY : StrUtil.nullToEmpty(nodes.get(nodes.size() - 1).getOutput()));
        vo.setDurationMs(run.getDurationMs());
        vo.setNodes(nodes);
        return vo;
    }

    private FlowRunVO.FlowNodeResult toNodeResult(WbEventDO event) {
        JSONObject payload = StrUtil.isBlank(event.getPayload())
                ? JSONUtil.createObj() : JSONUtil.parseObj(event.getPayload());
        FlowRunVO.FlowNodeResult result = new FlowRunVO.FlowNodeResult();
        result.setNodeId(payload.getStr("nodeId"));
        result.setNodeType(payload.getStr("nodeType"));
        result.setNodeLabel(payload.getStr("nodeLabel"));
        result.setStatus(payload.getStr("status"));
        result.setOutput(payload.getStr("output"));
        result.setDurationMs(payload.getLong("durationMs"));
        return result;
    }

    /** 单节点执行：按类型分派执行器，配置里的 {{var}} 先用上下文渲染 */
    private String executeNode(FlowService.FlowNode node, String userId, Map<String, String> context) {
        JSONObject config = node.config();
        FlowNodeTypeEnum type = FlowNodeTypeEnum.ofCode(node.type());
        if (Objects.isNull(type)) {
            throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "未知节点类型");
        }
        switch (type) {
            case START -> {
                JSONObject startInputs = config.getJSONObject("inputs");
                if (Objects.nonNull(startInputs)) {
                    startInputs.forEach((key, value) -> context.putIfAbsent(key, Objects.toString(value, "")));
                }
                return StrUtil.EMPTY;
            }
            case LLM -> {
                String prompt = render(config.getStr("prompt"), context);
                if (StrUtil.isBlank(prompt)) {
                    throw new BizException(ErrorCodeEnum.PARAM_INVALID, "模型节点缺少 prompt");
                }
                String providerCode = config.getStr("providerCode");
                String modelName = config.getStr("model");
                return StrUtil.isNotBlank(providerCode) && StrUtil.isNotBlank(modelName)
                        ? modelCaller.complete(prompt, providerCode, modelName)
                        : modelCaller.complete(prompt);
            }
            case PYTHON -> {
                String code = render(config.getStr("script"), context);
                if (StrUtil.isBlank(code)) {
                    throw new BizException(ErrorCodeEnum.PARAM_INVALID, "脚本节点缺少 script");
                }
                long timeout = NumberUtil.parseInt(String.valueOf(config.get("timeoutSeconds")),
                        FlowConst.PYTHON_TIMEOUT_SECONDS);
                timeout = Math.min(Math.max(timeout, 1), FlowConst.PYTHON_TIMEOUT_MAX_SECONDS);
                ExecResult result = pythonRuntime.execute(new ExecRequest(code, timeout, null));
                if (!result.success()) {
                    throw new BizException(ErrorCodeEnum.PYTHON_EXEC_FAILED,
                            StrUtil.nullToEmpty(result.stderr()));
                }
                return StrUtil.nullToEmpty(result.stdout());
            }
            case KNOWLEDGE -> {
                String keyword = render(config.getStr("keyword"), context);
                String kbId = render(config.getStr("kbId"), context);
                int topK = NumberUtil.parseInt(String.valueOf(config.get("topK")), 3);
                return kbDocService.search(userId, keyword, kbId, topK);
            }
            case TOOL -> {
                String toolName = config.getStr("toolName");
                if (StrUtil.isBlank(toolName)) {
                    throw new BizException(ErrorCodeEnum.TOOL_NOT_FOUND, "工具节点缺少 toolName");
                }
                InvokeToolREQ req = new InvokeToolREQ();
                req.setConfirmed(true);
                req.setArgs(renderArgs(config.getJSONObject("params"), context));
                return toolService.invoke(toolName, req);
            }
            case END -> {
                return render(config.getStr("template"), context);
            }
            default -> throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "未知节点类型");
        }
    }

    /** 变量渲染：{{name}} 替换为上下文值，未命中的占位符保持原样 */
    private String render(String raw, Map<String, String> context) {
        if (StrUtil.isBlank(raw) || context.isEmpty()) {
            return StrUtil.nullToEmpty(raw);
        }
        String text = raw;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            text = text.replace(AgentConst.VAR_PREFIX + entry.getKey() + AgentConst.VAR_SUFFIX, entry.getValue());
        }
        return text;
    }

    /** 工具参数渲染：逐值替换变量 */
    private Map<String, Object> renderArgs(JSONObject params, Map<String, String> context) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (Objects.isNull(params)) {
            return args;
        }
        params.forEach((key, value) -> args.put(key, render(Objects.toString(value, ""), context)));
        return args;
    }

    /** 节点结果落事件，输出按上限裁剪，完整结果只在返回值里 */
    private void persistNodeEvent(String runId, String userId, int seq, FlowRunVO.FlowNodeResult result) {
        JSONObject payload = JSONUtil.createObj()
                .set("nodeId", result.getNodeId())
                .set("nodeType", result.getNodeType())
                .set("nodeLabel", result.getNodeLabel())
                .set("status", result.getStatus())
                .set("durationMs", result.getDurationMs())
                .set("output", StrUtil.sub(StrUtil.nullToEmpty(result.getOutput()),
                        0, FlowConst.NODE_EVENT_OUTPUT_MAX));
        WbEventDO event = new WbEventDO()
                .setEventId(IdGenerator.generate(IdPrefixConst.EVENT))
                .setUserId(userId)
                .setRunId(runId)
                .setSeq(seq)
                .setEventType(EventTypeEnum.FLOW_NODE.getCode())
                .setPayload(payload.toString())
                .setOccurredAt(LocalDateTime.now());
        try {
            eventMapper.insertBatch(List.of(event));
        } catch (Exception e) {
            log.error("[flow.event] 落库失败 runId={} {}", runId, e.getMessage());
        }
    }

    private void insertRun(String runId, String userId, String flowName) {
        WbRunDO run = new WbRunDO();
        run.setRunId(runId);
        run.setUserId(userId);
        run.setRelation("flow");
        run.setPrompt(flowName);
        run.setState(RunStatusEnum.QUEUED.getCode());
        runMapper.insert(run);
    }

    private void settleRun(String runId, String status, int iterCount, long duration) {
        WbRunDO run = new WbRunDO();
        run.setRunId(runId);
        run.setState(status);
        run.setIterCount(iterCount);
        run.setDurationMs(duration);
        runMapper.updateTerminal(run);
    }
}
