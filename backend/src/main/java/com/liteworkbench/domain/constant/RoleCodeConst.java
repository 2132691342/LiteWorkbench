package com.liteworkbench.domain.constant;

import java.util.Set;

/** 内置角色码，与 docs/DATA-MODEL.md 对齐 */
public interface RoleCodeConst {

    /** 超级管理员：全部菜单与权限点，内置不可删 */
    String SUPER_ADMIN = "super_admin";
    /** 平台管理员：系统管理与工作台功能，不持角色管理权限 */
    String ADMIN = "admin";
    /** 普通用户：工作台功能 */
    String USER = "user";

    /** 角色管理权限前缀：仅超级管理员持有，平台管理员按特权划分不授 */
    String ROLE_MANAGEMENT_PERM_PREFIX = "system:role:";

    /** 内置角色集合，不允许删除与改码 */
    Set<String> BUILTIN_CODES = Set.of(SUPER_ADMIN, ADMIN, USER);

    /** 是否为内置角色码 */
    static boolean isBuiltin(String roleCode) {
        return BUILTIN_CODES.contains(roleCode);
    }
}
