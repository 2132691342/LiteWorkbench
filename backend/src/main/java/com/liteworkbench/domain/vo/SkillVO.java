package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 技能视图对象 */
@Data
public class SkillVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String skillId;
    private String skillCode;
    private String skillName;
    private String summary;
    private String content;
    private String triggerWords;
    private Boolean enabled;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
