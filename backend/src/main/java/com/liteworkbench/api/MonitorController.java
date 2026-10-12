package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.vo.HealthRESP;
import com.liteworkbench.domain.vo.LogDocVO;
import com.liteworkbench.domain.vo.LogStatsVO;
import com.liteworkbench.domain.vo.OverviewRESP;
import com.liteworkbench.domain.vo.PythonStatusVO;
import com.liteworkbench.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 监控、日志检索与运行时接口 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;

    /** 逐组件健康状态 */
    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/monitor/health")
    public R<HealthRESP> health() {
        return R.ok(monitorService.health());
    }

    /** 仪表盘汇总 */
    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/monitor/overview")
    public R<OverviewRESP> overview() {
        return R.ok(monitorService.overview());
    }

    /** Python 运行时探测结果 */
    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/runtime/python")
    public R<PythonStatusVO> pythonStatus() {
        return R.ok(monitorService.pythonStatus());
    }

    /** 重新探测 Python 运行时 */
    @Idempotent(prefix = "runtime:reprobe", ttl = 5)
    @SaCheckPermission(PermConst.MONITOR_PYTHON_REPROBE)
    @PostMapping("/runtime/python/reprobe")
    public R<PythonStatusVO> reprobe() {
        monitorService.reprobePython();
        return R.ok(monitorService.pythonStatus());
    }

    /** 日志检索，Manticore 全文 + 等值过滤，按时间倒序分页 */
    @SaCheckPermission(PermConst.MONITOR_LOG_LIST)
    @GetMapping("/log/search")
    public R<PageResult<LogDocVO>> logSearch(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String field,
                                             @RequestParam(required = false) String level,
                                             @RequestParam(required = false) String logger,
                                             @RequestParam(required = false) String traceId,
                                             @RequestParam(defaultValue = "0") long from,
                                             @RequestParam(defaultValue = "0") long to,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(monitorService.searchLogs(keyword, field, level, logger, traceId, from, to, page, size));
    }

    /** 日志分布概览：级别计数 + 小时直方图，未传时间范围时默认近 24 小时 */
    @SaCheckPermission(PermConst.MONITOR_LOG_LIST)
    @GetMapping("/log/stats")
    public R<LogStatsVO> logStats(@RequestParam(defaultValue = "0") long from,
                                  @RequestParam(defaultValue = "0") long to) {
        return R.ok(monitorService.logStats(from, to));
    }
}
