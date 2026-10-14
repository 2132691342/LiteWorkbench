package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 知识库实体，映射 wb_kb */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbKbDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 知识库 ID，KB_ 前缀 */
    private String kbId;
    /** 归属用户 */
    private String userId;
    /** 知识库名，用户内唯一 */
    private String kbName;
    private String description;
    /** 文档数，冗余计数 */
    private Integer docCount;
    /** 分块数，冗余计数 */
    private Integer chunkCount;
    /** 业务状态 normal / disabled */
    private String state;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String remark;
}
