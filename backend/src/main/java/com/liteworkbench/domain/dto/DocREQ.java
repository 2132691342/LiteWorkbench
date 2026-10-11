package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 创作文档新建与更新入参；更新时 docId 必填，正文走 content 全量覆盖 */
@Data
public class DocREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文档 ID，更新时必填 */
    private String docId;
    /** 标题 */
    @NotBlank(message = "文档标题不能为空")
    @Size(max = 255, message = "标题最长 255 字")
    private String docTitle;
    /** 正文格式 markdown / rich / code */
    private String docType;
    /** 分类 */
    @Size(max = 64, message = "分类最长 64 字")
    private String category;
    /** 标签，逗号分隔 */
    @Size(max = 512, message = "标签最长 512 字")
    private String tags;
    /** 摘要 */
    @Size(max = 512, message = "摘要最长 512 字")
    private String summary;
    /** 正文，上限 200000 字由服务层校验 */
    private String content;
    /** 引用的知识库 */
    private String sourceKbId;
    /** 业务状态 */
    private String state;
    /** 变更说明，写入版本快照 */
    @Size(max = 255, message = "变更说明最长 255 字")
    private String changeNote;
}
