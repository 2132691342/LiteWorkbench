package com.liteworkbench.domain.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 菜单与权限点实体，平台级表 */
@Data
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
    /** 图标名 */
    private String icon;
    /** 权限标识 system:user:create */
    private String permCode;
    /** 排序号 */
    private Integer sortNo;
    /** 是否显示 */
    private Boolean visible;
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
