package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.vo.LogPageVO;
import com.liteworkbench.domain.vo.LogStatsVO;
import com.liteworkbench.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 日志检索接口：Manticore 全文 + 等值过滤，视角由服务端按角色判定 */
@RestController
@RequestMapping("/api/v1/log")
@RequiredArgsConstructor
public class LogController {

    private final MonitorService monitorService;

    /** 日志检索，按时间倒序分页；响应带 scope 视角标识 */
    @SaCheckPermission(PermConst.MONITOR_LOG_LIST)
    @GetMapping("/search")
    public R<LogPageVO> search(@RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String field,
                               @RequestParam(required = false) String level,
                               @RequestParam(required = false) String logger,
                               @RequestParam(required = false) String traceId,
                               @RequestParam(required = false) String userId,
                               @RequestParam(required = false) String runId,
                               @RequestParam(required = false) String sessionId,
                               @RequestParam(defaultValue = "0") long from,
                               @RequestParam(defaultValue = "0") long to,
                               @RequestParam(defaultValue = "1") long page,
                               @RequestParam(defaultValue = "20") long size) {
        return R.ok(monitorService.searchLogs(keyword, field, level, logger, traceId,
                userId, runId, sessionId, from, to, page, size));
    }

    /** 日志分布概览：级别计数 + 时间直方图，过滤参数同检索，未传时间范围时默认近 24 小时 */
    @SaCheckPermission(PermConst.MONITOR_LOG_LIST)
    @GetMapping("/stats")
    public R<LogStatsVO> stats(@RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String field,
                               @RequestParam(required = false) String level,
                               @RequestParam(required = false) String logger,
                               @RequestParam(required = false) String traceId,
                               @RequestParam(required = false) String userId,
                               @RequestParam(required = false) String runId,
                               @RequestParam(required = false) String sessionId,
                               @RequestParam(defaultValue = "0") long from,
                               @RequestParam(defaultValue = "0") long to) {
        return R.ok(monitorService.logStats(keyword, field, level, logger, traceId,
                userId, runId, sessionId, from, to));
    }

    /** 导出 CSV：与检索同口径，上限 5000 行，浏览器直接下载 */
    @SaCheckPermission(PermConst.MONITOR_LOG_LIST)
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String field,
                       @RequestParam(required = false) String level,
                       @RequestParam(required = false) String logger,
                       @RequestParam(required = false) String traceId,
                       @RequestParam(required = false) String userId,
                       @RequestParam(required = false) String runId,
                       @RequestParam(required = false) String sessionId,
                       @RequestParam(defaultValue = "0") long from,
                       @RequestParam(defaultValue = "0") long to,
                       jakarta.servlet.http.HttpServletResponse response) throws IOException {
        String csv = monitorService.exportLogs(keyword, field, level, logger, traceId,
                userId, runId, sessionId, from, to);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=lw-log.csv");
        // BOM：Excel 直接打开 UTF-8 CSV 不乱码
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        response.getOutputStream().write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
