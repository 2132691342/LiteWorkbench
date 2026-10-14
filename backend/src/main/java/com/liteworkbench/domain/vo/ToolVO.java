package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 工具视图对象 */
@Data
public class ToolVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String toolId;
    private String toolName;
    private String toolLabel;
    private String category;
    private String riskLevel;
    private Boolean enabled;
}
