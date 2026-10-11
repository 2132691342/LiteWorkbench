package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    /** 新密码 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "新密码长度须在 6-32 位之间")
    private String newPassword;
}
