package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 用户视图对象，时间对外输出毫秒时间戳 */
@Data
public class UserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private String userId;
    /** 登录名 */
    private String username;
    /** 显示名 */
    private String nickname;
    /** 邮箱 */
    private String email;
    /** 头像地址 */
    private String avatarUrl;
    /** 状态：normal / disabled / locked */
    private String state;
    /** 角色 ID 列表 */
    private List<String> roleIds;
    /** 最近登录时间，毫秒时间戳 */
    private Long lastLoginAt;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
}
