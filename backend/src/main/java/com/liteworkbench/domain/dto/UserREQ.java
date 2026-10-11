package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 用户新建与修改请求 */
@Data
public class UserREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID，修改时必填 */
    private String userId;
    /** 登录名，仅新建时使用 */
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{2,31}$", message = "登录名须为字母开头，可含数字与下划线，长度 3-32")
    private String username;
    /** 初始密码，仅新建时使用，空则取默认密码 */
    @Size(min = 6, max = 32, message = "密码长度须在 6-32 位之间")
    private String password;
    /** 显示名 */
    @NotBlank(message = "显示名不能为空")
    @Size(max = 64, message = "显示名不能超过 64 字符")
    private String nickname;
    /** 邮箱 */
    @Email(message = "邮箱格式不正确")
    private String email;
    /** 角色 ID 列表 */
    private List<String> roleIds;
    /** 备注 */
    private String remark;
}
