package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 角色视图对象 */
@Data
public class RoleVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String roleId;
    private String roleCode;
    private String roleName;
    private Integer sortNo;
    private String state;
    /** 是否内置角色，内置角色不可改码不可删 */
    private Boolean builtin;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
}
