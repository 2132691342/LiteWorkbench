package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.vo.HealthRESP;
import com.liteworkbench.domain.vo.LogDocVO;
import com.liteworkbench.domain.vo.OverviewRESP;
import com.liteworkbench.domain.vo.PythonStatusVO;
import com.liteworkbench.mapper.LogSearchMapper;
import com.liteworkbench.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 监控、日志检索与运行时接口 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;
    private final LogSearchMapper logSearchMapper;

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
    @SaCheckPermission(PermConst.MONITOR_PYTHON_REPROBE)
    @PostMapping("/runtime/python/reprobe")
    public R<PythonStatusVO> reprobe() {
        monitorService.reprobePython();
        return R.ok(monitorService.pythonStatus());
    }

    /** 日志检索，Redisearch 全文，按时间倒序分页 */
    @SaCheckPermission(PermConst.MONITOR_LOG_LIST)
    @GetMapping("/log/search")
    public R<List<LogDocVO>> logSearch(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String level,
                                       @RequestParam(defaultValue = "0") long from,
                                       @RequestParam(defaultValue = "0") long to,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(logSearchMapper.search(keyword, level, from, to, page, size));
    }
}
