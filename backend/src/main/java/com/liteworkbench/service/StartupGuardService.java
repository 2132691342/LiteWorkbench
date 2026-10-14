package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.config.DegradationProperties;
import com.liteworkbench.config.SecurityProperties;
import com.liteworkbench.domain.vo.HealthRESP;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.stream.Collectors;

/** 启动自检：生产拒绝默认密钥；核心中间件不可用时按降级开关决定放行（warn）或终止启动（fail fast） */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupGuardService implements ApplicationRunner {

    /** 容器运行使用的生产标识，由镜像以 SPRING_PROFILES_ACTIVE 注入 */
    private static final String PROD_PROFILE = "prod";

    private final MonitorService monitorService;
    private final DegradationProperties degradationProperties;
    private final SecurityProperties securityProperties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        assertSecret();
        HealthRESP health = monitorService.health();
        if ("UP".equals(health.getStatus())) {
            log.info("[startup.guard] 全组件健康");
            return;
        }
        String detail = health.getComponents().stream()
                .filter(item -> !"UP".equals(item.getStatus()))
                .map(item -> StrUtil.format("{}:{}({})", item.getName(), item.getStatus(),
                        Objects.toString(item.getDetail(), "unknown")))
                .collect(Collectors.joining(", "));
        if ("DOWN".equals(health.getStatus()) && !degradationProperties.isAllowStart()) {
            log.error("[startup.guard] 核心组件不可用且未允许降级启动，终止: {}", detail);
            throw new IllegalStateException("core components unavailable: " + detail);
        }
        log.warn("[startup.guard] 组件降级启动: {}", detail);
    }

    /** 生产环境必须注入真实加密因子：用内置因子加密的供应方密钥等于明文落库 */
    private void assertSecret() {
        boolean prod = environment.acceptsProfiles(Profiles.of(PROD_PROFILE));
        if (!securityProperties.usingDevSecret()) {
            return;
        }
        if (prod) {
            log.error("[startup.guard] prod 环境未注入 LITEWORKBENCH_SECURITY_SECRET，终止启动");
            throw new IllegalStateException("LITEWORKBENCH_SECURITY_SECRET is required under prod profile");
        }
        log.warn("[startup.guard] 正在使用内置开发加密因子，仅限本地调试，生产必须注入 LITEWORKBENCH_SECURITY_SECRET");
    }
}
