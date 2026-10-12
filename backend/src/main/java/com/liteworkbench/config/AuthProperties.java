package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 认证配置绑定 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.auth")
public class AuthProperties {

    /** 种子账号初始密码：留空则启动随机生成并只打印一次，禁止在配置文件写死 */
    private String adminPassword = "";
    /** 权限缓存有效期（秒） */
    private long permCacheSeconds = 600;
}
