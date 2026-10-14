package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 技能实体，映射 wb_skill */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbSkillDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 技能 ID，SKIL_ 前缀 */
    private String skillId;
    /** 归属用户 */
    private String userId;
    /** 技能标识，用户内唯一 */
    private String skillCode;
    private String skillName;
    /** 摘要，常驻系统提示词 */
    private String summary;
    /** 正文，命中触发词后展开 */
    private String content;
    /** 触发词，逗号分隔 */
    private String triggerWords;
    private Boolean enabled;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String remark;
}
