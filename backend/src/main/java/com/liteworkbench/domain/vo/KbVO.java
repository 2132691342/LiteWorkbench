package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 知识库视图对象 */
@Data
public class KbVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 知识库 ID */
    private String kbId;
    /** 归属用户 */
    private String userId;
    /** 知识库名 */
    private String kbName;
    /** 描述 */
    private String description;
    /** 文档数 */
    private Integer docCount;
    /** 分块数 */
    private Integer chunkCount;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
}
