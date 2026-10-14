package com.liteworkbench.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Run 树节点：一次运行及其派生的子运行，父子关系由 wb_run.parent_run_id 决定 */
@Data
public class TraceRunVO {

    private String runId;
    /** 父运行 ID，根运行为空 */
    private String parentRunId;
    /** 派生关系：original / child */
    private String relation;
    /** 本次输入摘要 */
    private String prompt;
    /** 实际使用模型 */
    private String modelName;
    private String status;
    private String stopReason;
    private Integer iterCount;
    private Integer inputTokens;
    private Integer outputTokens;
    /** 耗时毫秒 */
    private Long durationMs;
    /** 开始时间，毫秒时间戳 */
    private Long startedAt;
    /** 事件条数，回放深度参考 */
    private Integer eventCount;
    private List<TraceRunVO> children = new ArrayList<>();
}
