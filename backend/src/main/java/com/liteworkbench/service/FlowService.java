package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.constant.FlowConst;
import com.liteworkbench.domain.constant.FlowNodeTypeEnum;
import com.liteworkbench.domain.constant.FlowStateEnum;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.dto.FlowREQ;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.entity.WbFlowDO;
import com.liteworkbench.domain.vo.FlowVO;
import com.liteworkbench.mapper.WbFlowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 工作流画布：CRUD、结构校验与拓扑排序；执行由 FlowEngine 负责 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowService {

    private final WbFlowMapper flowMapper;

    /** 分页查询，仅返回归属用户的画布 */
    public PageResult<FlowVO> page(PageREQ req) {
        String userId = LoginContext.userId();
        long total = flowMapper.countPage(req, userId);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<FlowVO> list = flowMapper.selectPage(req, userId, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 详情，含画布结构 */
    public FlowVO detail(String flowId) {
        String userId = LoginContext.userId();
        return toVO(getOwned(flowId, userId));
    }

    /** 新建画布：结构为空时给一个最小可执行的 start → end 骨架 */
    public String create(FlowREQ req) {
        String userId = LoginContext.userId();
        requireUniqueName(userId, req.getFlowName(), null);
        String graphJson = StrUtil.isNotBlank(req.getGraphJson()) ? req.getGraphJson() : skeletonGraph();
        validate(graphJson);
        WbFlowDO flow = new WbFlowDO()
                .setFlowId(IdGenerator.generate(IdPrefixConst.FLOW))
                .setUserId(userId)
                .setFlowName(req.getFlowName())
                .setDescription(req.getDescription())
                .setGraphJson(graphJson)
                .setNodeCount(nodeCount(graphJson))
                .setState(resolveState(req.getState()))
                .setCreatedBy(userId);
        flowMapper.insert(flow);
        log.info("[flow.create] userId={} flowId={} name={}", userId, flow.getFlowId(), flow.getFlowName());
        return flow.getFlowId();
    }

    /** 保存画布：结构不合法即拒绝，不存半成品图 */
    public void update(FlowREQ req) {
        String userId = LoginContext.userId();
        if (StrUtil.isBlank(req.getFlowId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "flowId 不能为空");
        }
        getOwned(req.getFlowId(), userId);
        requireUniqueName(userId, req.getFlowName(), req.getFlowId());
        String graphJson = StrUtil.isNotBlank(req.getGraphJson()) ? req.getGraphJson() : skeletonGraph();
        validate(graphJson);
        WbFlowDO flow = new WbFlowDO()
                .setFlowId(req.getFlowId())
                .setUserId(userId)
                .setFlowName(req.getFlowName())
                .setDescription(req.getDescription())
                .setGraphJson(graphJson)
                .setNodeCount(nodeCount(graphJson))
                .setState(resolveState(req.getState()))
                .setUpdatedBy(userId);
        flowMapper.update(flow);
        log.info("[flow.update] userId={} flowId={} nodes={}", userId, flow.getFlowId(), flow.getNodeCount());
    }

    /** 删除画布 */
    public void delete(String flowId) {
        String userId = LoginContext.userId();
        getOwned(flowId, userId);
        flowMapper.deleteById(userId, flowId, userId);
        log.info("[flow.delete] userId={} flowId={}", userId, flowId);
    }

    /** 归属校验：按 (ID, user) 查，查不到即 404 */
    public WbFlowDO getOwned(String flowId, String userId) {
        WbFlowDO flow = flowMapper.selectByIdAndUser(userId, flowId);
        if (Objects.isNull(flow)) {
            throw new BizException(ErrorCodeEnum.FLOW_NOT_FOUND, flowId);
        }
        return flow;
    }

    /** 记录最近一次运行 */
    public void markLastRun(String flowId, String userId, String runId) {
        flowMapper.updateLastRun(flowId, userId, runId);
    }

    /** 结构校验：唯一 start、至少一个 end、无环、无孤立节点、节点数不超上限 */
    public void validate(String graphJson) {
        List<FlowNode> nodes = parseNodes(graphJson);
        if (nodes.isEmpty()) {
            throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "画布没有任何节点");
        }
        if (nodes.size() > FlowConst.MAX_NODE_COUNT) {
            throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "节点数超过上限 " + FlowConst.MAX_NODE_COUNT);
        }
        long startCount = nodes.stream().filter(node -> FlowNodeTypeEnum.START.getCode().equals(node.type())).count();
        long endCount = nodes.stream().filter(node -> FlowNodeTypeEnum.END.getCode().equals(node.type())).count();
        if (startCount != 1) {
            throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "必须有且仅有一个开始节点");
        }
        if (endCount == 0) {
            throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "至少需要一个结束节点");
        }
        Map<String, List<String>> outgoing = parseEdges(graphJson, nodeIds(nodes));
        topoSort(nodes, outgoing);
    }

    /** 拓扑排序：有环抛 FLOW_GRAPH_INVALID，孤立节点同抛 */
    public List<FlowNode> topoSort(List<FlowNode> nodes, Map<String, List<String>> outgoing) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, FlowNode> nodeMap = new LinkedHashMap<>();
        nodes.forEach(node -> {
            indegree.put(node.id(), 0);
            nodeMap.put(node.id(), node);
        });
        for (List<String> targets : outgoing.values()) {
            for (String target : targets) {
                indegree.merge(target, 1, Integer::sum);
            }
        }
        String startId = nodes.stream()
                .filter(node -> FlowNodeTypeEnum.START.getCode().equals(node.type()))
                .map(FlowNode::id)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "缺少开始节点"));

        List<FlowNode> sorted = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (!visited.add(current)) {
                continue;
            }
            sorted.add(nodeMap.get(current));
            for (String next : outgoing.getOrDefault(current, List.of())) {
                int left = indegree.merge(next, -1, Integer::sum);
                if (left == 0) {
                    queue.add(next);
                }
            }
        }
        if (sorted.size() != nodes.size()) {
            throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "画布存在环或孤立节点");
        }
        return sorted;
    }

    /** 解析画布节点 */
    public List<FlowNode> parseNodes(String graphJson) {
        JSONArray array = nodesArray(graphJson);
        List<FlowNode> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (Objects.isNull(item)) {
                continue;
            }
            String id = StrUtil.trimToEmpty(item.getStr("id"));
            String type = StrUtil.trimToEmpty(item.getStr("type"));
            if (StrUtil.isBlank(id) || StrUtil.isBlank(type)) {
                throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "节点缺少 id 或 type");
            }
            if (!ids.add(id)) {
                throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "节点 id 重复：" + id);
            }
            if (Objects.isNull(FlowNodeTypeEnum.ofCode(type))) {
                throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "不支持的节点类型：" + type);
            }
            nodes.add(new FlowNode(id, type, StrUtil.nullToEmpty(item.getStr("label")),
                    Objects.requireNonNullElse(item.getJSONObject("config"), new JSONObject())));
        }
        return nodes;
    }

    /** 解析连线为邻接表，边两端必须指向已存在的节点 */
    public Map<String, List<String>> parseEdges(String graphJson, Set<String> nodeIds) {
        JSONArray array = edgesArray(graphJson);
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (Objects.isNull(item)) {
                continue;
            }
            String source = StrUtil.trimToEmpty(item.getStr("source"));
            String target = StrUtil.trimToEmpty(item.getStr("target"));
            if (StrUtil.isBlank(source) || StrUtil.isBlank(target)) {
                throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "连线缺少 source 或 target");
            }
            if (!nodeIds.contains(source) || !nodeIds.contains(target)) {
                throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "连线指向了不存在的节点");
            }
            outgoing.computeIfAbsent(source, key -> new ArrayList<>()).add(target);
        }
        return outgoing;
    }

    /** 画布结构整体解析：节点 + 邻接表 */
    public FlowGraph parse(String graphJson) {
        List<FlowNode> nodes = parseNodes(graphJson);
        Map<String, List<String>> outgoing = parseEdges(graphJson, nodeIds(nodes));
        return new FlowGraph(nodes, outgoing);
    }

    private Set<String> nodeIds(List<FlowNode> nodes) {
        return nodes.stream().map(FlowNode::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private int nodeCount(String graphJson) {
        return parseNodes(graphJson).size();
    }

    private JSONArray nodesArray(String graphJson) {
        JSONObject graph = requireObject(graphJson);
        JSONArray nodes = graph.getJSONArray("nodes");
        return Objects.isNull(nodes) ? new JSONArray() : nodes;
    }

    private JSONArray edgesArray(String graphJson) {
        JSONObject graph = requireObject(graphJson);
        JSONArray edges = graph.getJSONArray("edges");
        return Objects.isNull(edges) ? new JSONArray() : edges;
    }

    private JSONObject requireObject(String graphJson) {
        if (StrUtil.isBlank(graphJson) || !JSONUtil.isJsonObj(graphJson)) {
            throw new BizException(ErrorCodeEnum.FLOW_GRAPH_INVALID, "画布结构不是合法 JSON 对象");
        }
        return JSONUtil.parseObj(graphJson);
    }

    /** 最小可执行骨架：start → end */
    private String skeletonGraph() {
        return """
                {"nodes":[{"id":"start","type":"start","label":"开始","config":{"inputs":{}}},
                {"id":"end","type":"end","label":"结束","config":{"template":"{{output}}"}}],
                "edges":[{"source":"start","target":"end"}]}""";
    }

    private void requireUniqueName(String userId, String flowName, String excludeId) {
        if (flowMapper.countByName(userId, flowName, excludeId) > 0) {
            throw new BizException(ErrorCodeEnum.FLOW_NAME_CONFLICT, flowName);
        }
    }

    private String resolveState(String state) {
        if (StrUtil.isBlank(state)) {
            return FlowStateEnum.DRAFT.getCode();
        }
        for (FlowStateEnum item : FlowStateEnum.values()) {
            if (item.getCode().equals(state)) {
                return item.getCode();
            }
        }
        throw new BizException(ErrorCodeEnum.PARAM_INVALID, "不支持的工作流状态");
    }

    private FlowVO toVO(WbFlowDO flow) {
        FlowVO vo = new FlowVO();
        vo.setFlowId(flow.getFlowId());
        vo.setUserId(flow.getUserId());
        vo.setFlowName(flow.getFlowName());
        vo.setDescription(flow.getDescription());
        vo.setGraphJson(flow.getGraphJson());
        vo.setNodeCount(flow.getNodeCount());
        vo.setLastRunId(flow.getLastRunId());
        vo.setState(flow.getState());
        vo.setCreatedAt(TimeUtil.toMillis(flow.getCreatedAt()));
        vo.setUpdatedAt(TimeUtil.toMillis(flow.getUpdatedAt()));
        return vo;
    }

    /** 画布节点 */
    public record FlowNode(String id, String type, String label, JSONObject config) {
    }

    /** 画布结构：节点列表 + 邻接表 */
    public record FlowGraph(List<FlowNode> nodes, Map<String, List<String>> outgoing) {
    }
}
