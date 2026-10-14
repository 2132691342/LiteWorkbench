package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 菜单与权限点实体，平台级表 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class SysMenuDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 菜单 ID，MENU_ 前缀 */
    private String menuId;
    /** 父菜单 ID，根为 0 */
    private String parentId;
    /** 菜单名（i18n key） */
    private String menuName;
    /** 类型：dir / menu / button */
    private String menuType;
    /** 前端路由路径 */
    private String routePath;
    /** 前端组件路径 */
    private String component;
    private String icon;
    /** 权限标识 system:user:create */
    private String permCode;
    private Integer sortNo;
    private Boolean visible;
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
