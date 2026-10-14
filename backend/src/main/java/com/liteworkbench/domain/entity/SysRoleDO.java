package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 角色实体 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class SysRoleDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 角色 ID，ROLE_ 前缀 */
    private String roleId;
    /** 角色码，全局唯一 */
    private String roleCode;
    private String roleName;
    private Integer sortNo;
    /** 状态：normal / disabled */
    private String state;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String remark;
}
