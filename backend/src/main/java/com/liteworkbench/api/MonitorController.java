package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.vo.HealthRESP;
import com.liteworkbench.domain.vo.OverviewRESP;
import com.liteworkbench.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查与仪表盘汇总接口 */
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
}
