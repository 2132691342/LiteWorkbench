package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 菜单新建与修改请求 */
@Data
public class MenuREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 菜单 ID，修改时必填 */
    private String menuId;
    /** 父菜单 ID，根为 0 */
    private String parentId;
    /** 菜单名（i18n key） */
    @NotBlank(message = "菜单名不能为空")
    private String menuName;
    /** 类型：dir / menu / button */
    @Pattern(regexp = "^(dir|menu|button)$", message = "菜单类型只能是 dir / menu / button")
    private String menuType;
    /** 前端路由路径 */
    private String routePath;
    /** 前端组件路径 */
    private String component;
    private String icon;
    /** 权限标识，形如 system:user:create */
    private String permCode;
    private Integer sortNo;
    private Boolean visible;
    private String remark;
}
