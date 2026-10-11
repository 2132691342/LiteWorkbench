package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 知识库文档视图对象 */
@Data
public class KbDocVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 文档 ID */
    private String docId;
    /** 归属知识库 */
    private String kbId;
    /** 文档名 */
    private String docName;
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
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
