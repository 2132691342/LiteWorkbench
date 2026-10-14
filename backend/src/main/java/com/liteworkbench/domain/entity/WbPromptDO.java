package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 提示词模板实体，映射 wb_prompt；内置行 user_id 为 builtin 且 builtin=true，只读 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbPromptDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 提示词 ID，PRPT_ 前缀 */
    private String promptId;
    /** 归属用户，内置模板为 builtin */
    private String userId;
    private String promptTitle;
    private String category;
    /** 模板正文，变量写作 {{name}} */
    private String content;
    /** 变量定义 JSON：[{name,label,defaultValue}] */
    private String variables;
    private Boolean builtin;
    private Integer useCount;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String remark;
}
