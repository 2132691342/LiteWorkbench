package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 角色视图对象 */
@Data
public class RoleVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 角色 ID */
    private String roleId;
    /** 角色码 */
    private String roleCode;
    /** 角色名 */
    private String roleName;
    /** 排序号 */
    private Integer sortNo;
    /** 状态 */
    private String state;
    /** 是否内置角色，内置角色不可改码不可删 */
    private Boolean builtin;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
}
