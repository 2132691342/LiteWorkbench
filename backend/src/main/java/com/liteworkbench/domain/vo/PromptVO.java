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

    /** 提示词 ID */
    private String promptId;
    /** 归属用户，内置为 builtin */
    private String userId;
    /** 标题 */
    private String promptTitle;
    /** 分类 */
    private String category;
    /** 模板正文 */
    private String content;
    /** 变量定义 */
    private List<PromptVariable> variables;
    /** 是否内置 */
    private Boolean builtin;
    /** 使用次数 */
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
        /** 展示名 */
        private String label;
        /** 默认值 */
        private String defaultValue;
    }
}
