package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 技能新建与更新入参 */
@Data
public class SkillREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 技能 ID，更新时必填 */
    private String skillId;
    @NotBlank(message = "技能标识不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,63}$", message = "技能标识须为小写字母开头的字母数字下划线")
    private String skillCode;
    @NotBlank(message = "技能名称不能为空")
    @Size(max = 128, message = "名称最长 128 字")
    private String skillName;
    @Size(max = 200, message = "摘要最长 200 字")
    private String summary;
    @Size(max = 8000, message = "正文最长 8000 字")
    private String content;
    /** 触发词，逗号分隔 */
    @Size(max = 512, message = "触发词最长 512 字")
    private String triggerWords;
    private Boolean enabled;
}
