package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 文件对象视图 */
@Data
public class FileVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文件 ID */
    private String fileId;
    /** 关联会话 */
    private String sessionId;
    /** 文件名 */
    private String fileName;
    /** 字节数 */
    private Long fileSize;
    /** MIME 类型 */
    private String contentType;
    /** 分类 */
    private String category;
    /** 上传人 */
    private String createdBy;
    /** 上传时间，毫秒时间戳 */
    private Long createdAt;
}
