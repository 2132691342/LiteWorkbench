package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 菜单树节点视图对象 */
@Data
public class MenuVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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
    private String permCode;
    private Integer sortNo;
    private Boolean visible;
    private List<MenuVO> children;
}
