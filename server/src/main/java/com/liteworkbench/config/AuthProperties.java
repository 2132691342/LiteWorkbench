package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 认证配置绑定 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.auth")
public class AuthProperties {

    /** 新建用户的默认密码 */
    private String defaultPassword = "admin123";
    /** 权限缓存有效期（秒） */
    private long permCacheSeconds = 600;
}
