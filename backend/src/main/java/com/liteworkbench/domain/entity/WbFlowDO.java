package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 工作流画布实体，映射 wb_flow；节点与连线整体存 graph_json，不建节点表 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbFlowDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工作流 ID，FLOW_ 前缀 */
    private String flowId;
    /** 归属用户 */
    private String userId;
    /** 名称，用户内唯一 */
    private String flowName;
    /** 描述 */
    private String description;
    /** 画布结构 JSON：{nodes:[],edges:[]} */
    private String graphJson;
    /** 节点数，冗余免解析 JSON */
    private Integer nodeCount;
    /** 最近一次运行的 Run ID */
    private String lastRunId;
    /** 状态 draft / enabled / disabled */
    private String state;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    /** 备注 */
    private String remark;
}
