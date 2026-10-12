package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 登录加密公钥：keyId 随请求回传，过期即判凭据无效 */
@Data
public class PublicKeyVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 公钥标识 */
    private String keyId;
    /** 加密算法，前端据此选择实现 */
    private String algorithm;
    /** SPKI 结构的 Base64 公钥 */
    private String publicKey;
    /** 过期毫秒时间戳 */
    private Long expiresAt;
}
