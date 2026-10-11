package com.liteworkbench.domain.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 角色实体 */
@Data
public class SysRoleDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 角色 ID，ROLE_ 前缀 */
    private String roleId;
    /** 角色码，全局唯一 */
    private String roleCode;
    /** 角色名 */
    private String roleName;
    /** 排序号 */
    private Integer sortNo;
    /** 状态：normal / disabled */
    private String state;
    /** 创建人 */
    private String createdBy;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新人 */
    private String updatedBy;
    /** 更新时间 */
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    /** 备注 */
    private String remark;
}
