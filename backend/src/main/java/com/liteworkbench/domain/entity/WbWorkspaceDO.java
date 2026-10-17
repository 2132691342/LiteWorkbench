package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 工作区实体：用户级资产容器，与 MinIO 桶一一绑定，映射 wb_workspace */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbWorkspaceDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工作区 ID，WSPC_ 前缀 */
    private String workspaceId;
    /** 归属用户 */
    private String userId;
    private String workspaceName;
    /** 绑定桶名，全局唯一，用户内按 ULID 派生 */
    private String bucketName;
    private String description;
    /** 用户默认工作区，上传与会话回落目标 */
    private Boolean isDefault;
    private String state;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String remark;
}
