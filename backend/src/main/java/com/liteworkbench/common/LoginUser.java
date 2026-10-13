package com.liteworkbench.common;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 登录用户快照，存于 Sa-Token 会话，供请求线程读取 */
@Data
public class LoginUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private String userId;
    /** 登录名 */
    private String username;
    /** 显示名 */
    private String nickname;
    /** 头像地址 */
    private String avatarUrl;
    /** 角色码集合 */
    private List<String> roleCodes;
    /** 权限标识集合 */
    private List<String> perms;
    /** 账号状态：normal / disabled，停用后拦截器直接拒绝，不依赖重新登录 */
    private String state;
    /** 是否必须先改密（种子账号为 true），改密成功后置 false */
    private Boolean pwdResetRequired;
}
