package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/** 工作流执行入参：画布 ID + start 节点初始变量 */
@Data
public class FlowRunREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工作流 ID */
    @NotBlank(message = "工作流 ID 不能为空")
    private String flowId;
    /** 初始变量，覆盖 start 节点的默认输入 */
    private Map<String, String> inputs;
}
