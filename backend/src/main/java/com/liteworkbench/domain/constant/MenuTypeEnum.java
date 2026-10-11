package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 菜单类型：目录 / 路由页 / 权限点 */
@Getter
@AllArgsConstructor
public enum MenuTypeEnum {

    DIR("dir", "目录"),
    MENU("menu", "菜单"),
    BUTTON("button", "权限点"),
    ;

    /** 类型码 */
    private final String code;
    /** 类型描述 */
    private final String description;
}
