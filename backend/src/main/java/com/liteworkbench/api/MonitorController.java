package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.vo.HealthRESP;
import com.liteworkbench.domain.vo.ObserveRESP;
import com.liteworkbench.domain.vo.OverviewRESP;
import com.liteworkbench.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查、仪表盘汇总与调用观测接口 */
@RestController
@RequestMapping("/api/v1/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;

    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/health")
    public R<HealthRESP> health() {
        return R.ok(monitorService.health());
    }

    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/overview")
    public R<OverviewRESP> overview() {
        return R.ok(monitorService.overview());
    }

    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/observe")
    public R<ObserveRESP> observe() {
        return R.ok(monitorService.observe());
    }

    @Idempotent(prefix = "monitor:observe:reset", ttl = 5)
    @SaCheckPermission(PermConst.MONITOR_OBSERVE_RESET)
    @PostMapping("/observe/reset")
    public R<String> resetObserve() {
        monitorService.resetObserve();
        return R.ok("ok");
    }
}
