package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 登录与当前用户响应：token + 身份 + 权限 + 菜单树 */
@Data
public class LoginRESP implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 访问令牌，current 接口不返回 */
    private String token;
    /** 用户信息 */
    private UserVO user;
    /** 角色码集合 */
    private List<String> roleCodes;
    /** 权限标识集合 */
    private List<String> perms;
    /** 是否必须先改密，前端据此强制跳转改密页 */
    private Boolean pwdResetRequired;
    /** 当前用户可见菜单树 */
    private List<MenuVO> menus;
}
