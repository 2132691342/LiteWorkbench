package com.liteworkbench.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 流式对话入参 */
@Data
public class ChatStreamREQ implements Serializable {

    /** 单轮显式点名技能数上限 */
    private static final int MAX_SKILLS = 5;
    /** 单轮引用数上限 */
    private static final int MAX_REFS = 8;

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标会话 ID */
    @NotBlank(message = "会话不能为空")
    private String sessionId;
    /** 用户输入 */
    @NotBlank(message = "内容不能为空")
    @Size(max = 32000, message = "单次输入不能超过 32000 字")
    private String content;
    /** 输入框里点名的技能码（/ 命令），命中即强制展开，不再看触发词 */
    @Size(max = MAX_SKILLS, message = "单轮最多点名 5 个技能")
    private List<String> skillCodes;
    /** 输入框里 @ 引用的上下文对象 */
    @Size(max = MAX_REFS, message = "单轮最多引用 8 个对象")
    private List<@Valid RunRefREQ> refs;
}
