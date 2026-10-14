package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 创作文档实体，映射 wb_doc；正文以 PG 为准，纯文本副本同步到 Manticore */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbDocDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文档 ID，DOC_ 前缀 */
    private String docId;
    /** 归属用户 */
    private String userId;
    private String docTitle;
    /** 正文格式 markdown / rich / code */
    private String docType;
    private String category;
    /** 标签，逗号分隔 */
    private String tags;
    private String summary;
    /** 正文原文 */
    private String content;
    /** 去标签纯文本，供索引与字数统计 */
    private String contentText;
    private Integer wordCount;
    /** 当前版本号，每次保存 +1 */
    private Integer versionNo;
    /** 引用的知识库，AI 辅助写作时作为检索范围 */
    private String sourceKbId;
    /** 业务状态 draft / published / archived */
    private String state;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String remark;
}
