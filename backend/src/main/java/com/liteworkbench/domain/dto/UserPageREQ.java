package com.liteworkbench.domain.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/** 用户分页查询请求 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserPageREQ extends PageREQ {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户状态过滤 */
    private String state;
    /** 角色 ID 过滤：命中该角色的用户 */
    private String roleId;
    /** 仅看待首次改密的账号 */
    private Boolean pwdResetRequired;
}
