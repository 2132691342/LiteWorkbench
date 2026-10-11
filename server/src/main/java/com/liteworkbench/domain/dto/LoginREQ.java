package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 登录请求 */
@Data
public class LoginREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 登录名 */
    @NotBlank(message = "登录名不能为空")
    private String username;
    /** 密码明文，仅用于当次校验 */
    @NotBlank(message = "密码不能为空")
    private String password;
}
