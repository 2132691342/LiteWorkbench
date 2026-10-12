package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 管理员重置用户密码请求 */
@Data
public class ResetPasswordREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标用户 ID */
    @NotBlank(message = "用户 ID 不能为空")
    private String userId;
    /** 新密码密文：强度在解密后的明文上校验，故此处不做长度约束 */
    @NotBlank(message = "新密码不能为空")
    private String newPassword;
    /** 加密所用公钥标识 */
    @NotBlank(message = "缺少公钥标识")
    private String keyId;
}
