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
}
