package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 工作流新建与保存入参；graphJson 为 X6 画布结构原文 */
@Data
public class FlowREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工作流 ID，更新时必填 */
    private String flowId;
    /** 名称，用户内唯一 */
    @NotBlank(message = "工作流名称不能为空")
    @Size(max = 128, message = "名称最长 128 字")
    private String flowName;
    /** 描述 */
    @Size(max = 512, message = "描述最长 512 字")
    private String description;
    /** 画布结构 JSON：{nodes:[],edges:[]} */
    private String graphJson;
    /** 状态 draft / enabled / disabled */
    private String state;
}
