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
    /** 密码盐 */
    private String passwordSalt;
    /** 显示名 */
    private String nickname;
    /** 邮箱 */
    private String email;
    /** 头像地址 */
    private String avatarUrl;
    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;
    /** 状态：normal / disabled / locked */
    private String state;
    /** 创建人 */
    private String createdBy;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新人 */
    private String updatedBy;
    /** 更新时间 */
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    /** 备注 */
    private String remark;
}
