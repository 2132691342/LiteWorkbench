package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 工作流运行结果：Run ID + 逐节点输出 + 终态 */
@Data
public class FlowRunVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 运行 ID */
    private String runId;
    /** 工作流 ID */
    private String flowId;
    /** 运行状态 success / failed */
    private String status;
    /** 失败节点的错误信息 */
    private String errorMessage;
    /** 最终输出文本 */
    private String output;
    /** 总耗时（毫秒） */
    private Long durationMs;
    /** 节点执行明细 */
    private List<FlowNodeResult> nodes;

    /** 单节点执行结果 */
    @Data
    public static class FlowNodeResult implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 节点 ID */
        private String nodeId;
        /** 节点类型 */
        private String nodeType;
        /** 节点名称 */
        private String nodeLabel;
        /** 执行状态 success / failed */
        private String status;
        /** 输出摘要 */
        private String output;
        /** 错误信息 */
        private String errorMessage;
        /** 耗时（毫秒） */
        private Long durationMs;
    }
}
