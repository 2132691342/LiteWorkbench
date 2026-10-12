package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 修改密码请求：字段均为 RSA 密文 */
@Data
public class PasswordREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 原密码密文 */
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;
    /** 新密码密文 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "新密码长度须在 8-64 位之间")
    private String newPassword;
    /** 加密所用公钥标识 */
    @NotBlank(message = "缺少公钥标识")
    private String keyId;
}
