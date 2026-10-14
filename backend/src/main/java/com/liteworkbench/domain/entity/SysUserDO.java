package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 用户实体 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class SysUserDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID，USER_ 前缀 */
    private String userId;
    /** 登录名，全局唯一 */
    private String username;
    /** 密码摘要：SHA-256(密码 + 盐) */
    private String passwordHash;
    private String passwordSalt;
    private String nickname;
    private String email;
    private String avatarUrl;
    private LocalDateTime lastLoginAt;
    /** 是否必须先改密 */
    private Boolean pwdResetRequired;
    /** 状态：normal / disabled / locked */
    private String state;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String remark;
}
