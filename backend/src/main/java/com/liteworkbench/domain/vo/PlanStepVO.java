package com.liteworkbench.domain.vo;

import lombok.Data;

/** 计划步骤：规划器写下的单条待办，序号即展示顺序 */
@Data
public class PlanStepVO {

    /** 步骤序号，从 1 开始 */
    private Integer index;
    private String content;
    /** 完成状态：pending / doing / done */
    private String status;
}
