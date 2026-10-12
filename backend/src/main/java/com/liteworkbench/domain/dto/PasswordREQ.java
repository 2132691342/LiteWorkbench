package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
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
    /** 新密码密文：长度由密文决定，强度在解密后的明文上校验，故此处不做长度约束 */
    @NotBlank(message = "新密码不能为空")
    private String newPassword;
    /** 加密所用公钥标识 */
    @NotBlank(message = "缺少公钥标识")
    private String keyId;
}
