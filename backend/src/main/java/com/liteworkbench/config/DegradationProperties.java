package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 降级启动开关：中间件不可用时是否放行启动 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.degradation")
public class DegradationProperties {

    /** true 时中间件不可用仅记 warn，健康面板标 DEGRADED */
    private boolean allowStart = true;
}
