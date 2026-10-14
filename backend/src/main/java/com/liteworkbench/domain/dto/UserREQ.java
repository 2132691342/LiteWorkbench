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
    /** 初始密码密文，仅新建时使用；强度在解密后的明文上校验，故此处不做长度约束 */
    private String password;
    /** 初始密码加密所用公钥标识，与 password 成对出现 */
    private String passwordKeyId;
    @NotBlank(message = "显示名不能为空")
    @Size(max = 64, message = "显示名不能超过 64 字符")
    private String nickname;
    @Email(message = "邮箱格式不正确")
    private String email;
    private List<String> roleIds;
    private String remark;
}
