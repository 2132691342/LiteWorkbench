package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 工作流视图对象；graphJson 原样返回给 X6 画布 */
@Data
public class FlowVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工作流 ID */
    private String flowId;
    /** 归属用户 */
    private String userId;
    /** 名称 */
    private String flowName;
    /** 描述 */
    private String description;
    /** 画布结构 JSON */
    private String graphJson;
    /** 节点数 */
    private Integer nodeCount;
    /** 最近一次运行的 Run ID */
    private String lastRunId;
    /** 状态 */
    private String state;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
