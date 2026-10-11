package com.liteworkbench.domain.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 文件对象实体，映射 wb_file */
@Data
public class WbFileDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文件 ID，FILE_ 前缀 */
    private String fileId;
    /** 归属用户 */
    private String userId;
    /** 关联会话，可空 */
    private String sessionId;
    /** 原始文件名 */
    private String fileName;
    /** MinIO 对象键，含用户前缀 */
    private String objectKey;
    /** 桶名 */
    private String bucketName;
    /** 字节数 */
    private Long fileSize;
    /** MIME 类型 */
    private String contentType;
    /** 分类 upload / artifact / log */
    private String category;
    private String createdBy;
    private LocalDateTime createdAt;
    /** 逻辑删除 */
    private Boolean deleted;
}
