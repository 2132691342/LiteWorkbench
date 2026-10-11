package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.config.DegradationProperties;
import com.liteworkbench.domain.vo.HealthRESP;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.stream.Collectors;

/** 启动自检：核心中间件不可用时按降级开关决定放行（warn）或终止启动（fail fast） */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupGuardService implements ApplicationRunner {

    private final MonitorService monitorService;
    private final DegradationProperties degradationProperties;

    @Override
    public void run(ApplicationArguments args) {
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
}
