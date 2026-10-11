package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 安全参数：供应方密钥加密因子 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.security")
public class SecurityProperties {

    /** 加密因子，生产必须用环境变量覆盖 */
    private String secret = "liteworkbench-default-secret";
}
