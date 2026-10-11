package com.liteworkbench.service;

import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.service.FlowService.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 画布结构校验与拓扑执行序：合法链、有环、孤立节点、非法节点与悬空连线 */
class FlowGraphTest {

    private final FlowService service = new FlowService(null);

    @Test
    void 合法链按拓扑序排出来() {
        String graph = """
                {"nodes":[{"id":"start","type":"start","label":"开始","config":{}},
                {"id":"a","type":"llm","label":"生成","config":{}},
                {"id":"end","type":"end","label":"结束","config":{}}],
                "edges":[{"source":"start","target":"a"},{"source":"a","target":"end"}]}""";
        service.validate(graph);
        FlowService.FlowGraph parsed = service.parse(graph);
        List<FlowNode> sorted = service.topoSort(parsed.nodes(), parsed.outgoing());
        assertEquals(List.of("start", "a", "end"), sorted.stream().map(FlowNode::id).toList());
    }

    @Test
    void 有环或缺结束节点即拒绝() {
        String cyclic = """
                {"nodes":[{"id":"start","type":"start","label":"开始","config":{}},
                {"id":"a","type":"llm","label":"A","config":{}},
                {"id":"b","type":"llm","label":"B","config":{}},
                {"id":"end","type":"end","label":"结束","config":{}}],
                "edges":[{"source":"start","target":"a"},{"source":"a","target":"b"},
                {"source":"b","target":"a"},{"source":"b","target":"end"}]}""";
        assertEquals(ErrorCodeEnum.FLOW_GRAPH_INVALID, code(() -> service.validate(cyclic)));

        String noEnd = """
                {"nodes":[{"id":"start","type":"start","label":"开始","config":{}},
                {"id":"a","type":"llm","label":"A","config":{}}],
                "edges":[{"source":"start","target":"a"}]}""";
        assertEquals(ErrorCodeEnum.FLOW_GRAPH_INVALID, code(() -> service.validate(noEnd)));
    }

    @Test
    void 孤立节点即拒绝() {
        String graph = """
                {"nodes":[{"id":"start","type":"start","label":"开始","config":{}},
                {"id":"a","type":"llm","label":"A","config":{}},
                {"id":"end","type":"end","label":"结束","config":{}}],
                "edges":[{"source":"start","target":"end"}]}""";
        assertEquals(ErrorCodeEnum.FLOW_GRAPH_INVALID, code(() -> service.validate(graph)));
    }

    @Test
    void 未知类型重复ID与悬空连线即拒绝() {
        String unknown = """
                {"nodes":[{"id":"start","type":"start","label":"开始","config":{}},
                {"id":"a","type":"unknown","label":"A","config":{}}],"edges":[]}""";
        assertEquals(ErrorCodeEnum.FLOW_GRAPH_INVALID, code(() -> service.parse(unknown)));

        String duplicate = """
                {"nodes":[{"id":"a","type":"llm","label":"A","config":{}},
                {"id":"a","type":"llm","label":"B","config":{}}],"edges":[]}""";
        assertEquals(ErrorCodeEnum.FLOW_GRAPH_INVALID, code(() -> service.parse(duplicate)));

        FlowService.FlowGraph graph = service.parse("""
                {"nodes":[{"id":"start","type":"start","label":"开始","config":{}}],"edges":[]}""");
        Map<String, List<String>> broken = Map.of("start", List.of("ghost"));
        assertEquals(ErrorCodeEnum.FLOW_GRAPH_INVALID, code(() -> service.topoSort(graph.nodes(), broken)));
    }

    /** 取业务异常的错误码，未抛异常即判定失败 */
    private ErrorCodeEnum code(Runnable runnable) {
        try {
            runnable.run();
        } catch (BizException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("期望抛出 BizException");
    }
}
