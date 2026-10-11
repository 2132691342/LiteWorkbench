package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** AI 辅助写作入参：动作 + 选区 + 补充要求，可选引用知识库 */
@Data
public class DocAssistREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文档 ID，用于取上下文与引用知识库 */
    private String docId;
    /** 动作：continue / polish / expand / summary / outline / translate */
    @NotBlank(message = "辅助动作不能为空")
    private String action;
    /** 选中的正文，为空则对整篇操作 */
    private String selection;
    /** 补充要求，翻译动作里写目标语言 */
    @Size(max = 2000, message = "补充要求最长 2000 字")
    private String instruction;
    /** 引用的知识库，为空则不检索 */
    private String kbId;
}
