package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 内置技能种子实体，映射 wb_skill_seed；平台级定义，用户技能副本由它复制而来 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbSkillSeedDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 技能标识，种子表主键 */
    private String skillCode;
    private String skillName;
    /** 摘要，常驻系统提示词 */
    private String summary;
    /** 正文，命中触发词或用户显式点名后展开 */
    private String content;
    /** 触发词，逗号分隔 */
    private String triggerWords;
    /** 展示顺序 */
    private Integer sortNo;
    private LocalDateTime updatedAt;
}
