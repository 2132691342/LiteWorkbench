package com.liteworkbench.config;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 安全参数：供应方密钥加密因子 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.security")
public class SecurityProperties {

    /** 仅供本地开发使用的内置加密因子，prod 环境下出现即拒绝启动 */
    public static final String DEV_SECRET = "liteworkbench-default-secret";

    /** 加密因子，生产必须用环境变量覆盖 */
    private String secret = DEV_SECRET;

    /** 是否仍在用内置开发因子 */
    public boolean usingDevSecret() {
        return StrUtil.isBlank(secret) || StrUtil.equals(secret, DEV_SECRET);
    }
}
