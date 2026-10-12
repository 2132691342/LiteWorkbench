package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 知识库文档实体，映射 wb_kb_doc；分块正文存 Manticore，这里只留元数据与解析状态 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbKbDocDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文档 ID，KBDOC_ 前缀 */
    private String docId;
    /** 归属用户 */
    private String userId;
    /** 归属知识库 */
    private String kbId;
    /** 文档名 */
    private String docName;
    /** MinIO 对象键，含用户前缀 */
    private String objectKey;
    /** 桶名 */
    private String bucketName;
    /** 字节数 */
    private Long fileSize;
    /** MIME 类型 */
    private String contentType;
    /** 提取正文总字符数 */
    private Integer charCount;
    /** 分块数 */
    private Integer chunkCount;
    /** 解析状态 pending / processing / ready / failed */
    private String state;
    /** 解析失败原因 */
    private String errorMessage;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    /** 备注 */
    private String remark;
}
