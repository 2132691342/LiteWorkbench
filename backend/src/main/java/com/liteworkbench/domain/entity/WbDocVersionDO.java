package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 创作文档版本快照，映射 wb_doc_version；只读，不提供更新入口 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbDocVersionDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 版本 ID，DOCV_ 前缀 */
    private String versionId;
    /** 归属文档 */
    private String docId;
    /** 归属用户 */
    private String userId;
    /** 文档内递增的版本号 */
    private Integer versionNo;
    /** 快照标题 */
    private String docTitle;
    /** 快照正文 */
    private String content;
    /** 变更说明 */
    private String changeNote;
    private String createdBy;
    private LocalDateTime createdAt;
}
