package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 技能视图对象 */
@Data
public class SkillVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 技能 ID */
    private String skillId;
    /** 技能标识 */
    private String skillCode;
    /** 技能名称 */
    private String skillName;
    /** 摘要 */
    private String summary;
    /** 正文 */
    private String content;
    /** 触发词 */
    private String triggerWords;
    /** 是否启用 */
    private Boolean enabled;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
