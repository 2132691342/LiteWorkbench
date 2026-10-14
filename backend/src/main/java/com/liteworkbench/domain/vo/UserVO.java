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

    private String userId;
    private String username;
    private String nickname;
    private String email;
    private String avatarUrl;
    /** 状态：normal / disabled / locked */
    private String state;
    private List<String> roleIds;
    /** 最近登录时间，毫秒时间戳 */
    private Long lastLoginAt;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
}
