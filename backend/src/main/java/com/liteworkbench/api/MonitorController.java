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

    /** 组件探活汇总 */
    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/health")
    public R<HealthRESP> health() {
        return R.ok(monitorService.health());
    }

    /** 仪表盘汇总指标 */
    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/overview")
    public R<OverviewRESP> overview() {
        return R.ok(monitorService.overview());
    }

    /** SQL 与 HTTP 调用观测窗口 */
    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/observe")
    public R<ObserveRESP> observe() {
        return R.ok(monitorService.observe());
    }

    /** 清空观测窗口重新统计 */
    @Idempotent(prefix = "monitor:observe:reset", ttl = 5)
    @SaCheckPermission(PermConst.MONITOR_OBSERVE_RESET)
    @PostMapping("/observe/reset")
    public R<String> resetObserve() {
        monitorService.resetObserve();
        return R.ok("ok");
    }
}
