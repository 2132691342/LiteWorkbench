package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 工具定义实体，平台级映射 wb_tool */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbToolDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工具 ID，TOOL_ 前缀 */
    private String toolId;
    /** 调用名，全局唯一 */
    private String toolName;
    /** 展示名 */
    private String toolLabel;
    /** 类别 runtime / file / network */
    private String category;
    /** 风险等级 safe / confirm / danger */
    private String riskLevel;
    /** 是否启用，全局生效 */
    private Boolean enabled;
    /** 参数 Schema，jsonb */
    private String schemaJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
