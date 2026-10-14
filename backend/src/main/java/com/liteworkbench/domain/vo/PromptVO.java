package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 提示词视图对象；variables 解析为结构化列表便于前端渲染表单 */
@Data
public class PromptVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String promptId;
    /** 归属用户，内置为 builtin */
    private String userId;
    private String promptTitle;
    private String category;
    private String content;
    private List<PromptVariable> variables;
    private Boolean builtin;
    private Integer useCount;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;

    /** 提示词变量定义 */
    @Data
    public static class PromptVariable implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 变量名，对应正文里的 {{name}} */
        private String name;
        private String label;
        private String defaultValue;
    }
}
