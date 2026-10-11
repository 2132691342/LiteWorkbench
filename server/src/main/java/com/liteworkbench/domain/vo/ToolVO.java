package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 工具视图对象 */
@Data
public class ToolVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工具 ID */
    private String toolId;
    /** 调用名 */
    private String toolName;
    /** 展示名 */
    private String toolLabel;
    /** 类别 */
    private String category;
    /** 风险等级 */
    private String riskLevel;
    /** 是否启用 */
    private Boolean enabled;
}
