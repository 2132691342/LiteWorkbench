package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 登录请求：密码字段为 RSA 密文，明文不出浏览器 */
@Data
public class LoginREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "登录名不能为空")
    private String username;
    /** 密码密文（RSA/PKCS#1 v1.5，Base64） */
    @NotBlank(message = "密码不能为空")
    private String password;
    /** 加密所用公钥标识，由 public-key 接口下发 */
    @NotBlank(message = "缺少公钥标识")
    private String keyId;
}
