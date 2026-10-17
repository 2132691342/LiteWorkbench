package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 文件对象视图 */
@Data
public class FileVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String fileId;
    /** 关联会话 */
    private String sessionId;
    /** 归属工作区 */
    private String workspaceId;
    private String fileName;
    private Long fileSize;
    /** MIME 类型 */
    private String contentType;
    private String category;
    /** 上传人 */
    private String createdBy;
    /** 上传时间，毫秒时间戳 */
    private Long createdAt;
}
