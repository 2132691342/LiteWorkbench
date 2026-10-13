package com.liteworkbench.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.constant.RedisKeyConst;
import com.liteworkbench.domain.dto.LogSearchDTO;
import com.liteworkbench.domain.constant.RunStatusEnum;
import com.liteworkbench.domain.vo.HealthRESP;
import com.liteworkbench.domain.vo.LogDocVO;
import com.liteworkbench.domain.vo.LogStatsVO;
import com.liteworkbench.domain.vo.OverviewRESP;
import com.liteworkbench.domain.vo.PythonStatusVO;
import com.liteworkbench.mapper.HealthMapper;
import com.liteworkbench.mapper.LogSearchMapper;
import com.liteworkbench.mapper.WbEventMapper;
import com.liteworkbench.mapper.WbRunMapper;
import com.liteworkbench.mapper.WbSessionMapper;
import com.liteworkbench.runtime.PythonRuntime;
import com.manticoresearch.client.api.UtilsApi;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 健康探测与仪表盘汇总 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 汇总指标缓存时长 */
    private static final long OVERVIEW_CACHE_SECONDS = 60L;
    /** 日志概览默认统计窗口：近 24 小时 */
    private static final long DEFAULT_STATS_WINDOW_MS = 24 * 60 * 60 * 1000L;

    private final HealthMapper healthMapper;
    private final RedisConnectionFactory redisConnectionFactory;
    private final MinioClient minioClient;
    private final ConnectionFactory rabbitConnectionFactory;
    private final PythonRuntime pythonRuntime;
    private final WbSessionMapper sessionMapper;
    private final WbRunMapper runMapper;
    private final WbEventMapper eventMapper;
    private final LogSearchMapper logSearchMapper;
    private final StringRedisTemplate redisTemplate;
    private final UtilsApi manticoreUtilsApi;

    /** 探测专用线程池，探测互不阻塞 */
    private final ExecutorService probeExecutor = ExecutorBuilder.create()
            .setCorePoolSize(5).setMaxPoolSize(5)
            .setThreadFactory(ThreadUtil.newNamedThreadFactory("lw-probe", true))
            .build();

    /**
     * Manticore 日志检索，按时间倒序分页；索引不可用时降级为空结果。
     * 视角在服务端判定：全局角色（monitor:log:global）可按入参反查，其余用户强制锁定自己。
     */
    public PageResult<LogDocVO> searchLogs(String keyword, String field, String level,
                                           String logger, String traceId, String userId, String runId,
                                           long from, long to, long page, long size) {
        String scopeUserId = resolveScopeUserId(userId);
        try {
            LogSearchDTO result = logSearchMapper.search(keyword, field, level, logger, traceId,
                    scopeUserId, runId, from, to, page, size);
            return PageResult.of(result.list(), result.total(), page, size);
        } catch (Exception e) {
            log.warn("[log.search] 检索失败，降级为空结果 {}", e.getMessage());
            return PageResult.empty(page, size);
        }
    }

    /**
     * 日志分布概览：级别计数 + 小时直方图，与检索共用视角与时间范围。
     * 未传时间范围时默认近 24 小时；聚合异常降级为空结构，页面概览区显示占位。
     */
    public LogStatsVO logStats(long from, long to) {
        long now = System.currentTimeMillis();
        long effectiveFrom = from > 0 ? from : now - DEFAULT_STATS_WINDOW_MS;
        long effectiveTo = to > 0 ? to : now;
        try {
            LogStatsVO stats = logSearchMapper.stats(resolveScopeUserId(null), effectiveFrom, effectiveTo);
            stats.setScope(globalLogScope() ? "global" : "self");
            return stats;
        } catch (Exception e) {
            log.warn("[log.stats] 聚合失败，降级为空结构 {}", e.getMessage());
            LogStatsVO stats = new LogStatsVO();
            stats.setScope(globalLogScope() ? "global" : "self");
            return stats;
        }
    }

    /** 日志视角判定：全局角色按入参反查（可空表示不过滤），其余用户强制锁定自己 */
    private String resolveScopeUserId(String requestedUserId) {
        return globalLogScope() ? StrUtil.nullToEmpty(requestedUserId) : LoginContext.userId();
    }

    /** 是否持有全局日志视角 */
    private boolean globalLogScope() {
        try {
            return StpUtil.hasPermission(PermConst.MONITOR_LOG_GLOBAL);
        } catch (Exception e) {
            return false;
        }
    }

    /** 逐组件健康探测，UP / DEGRADED / DOWN 聚合 */
    public HealthRESP health() {
        List<HealthRESP.ComponentHealth> components = new ArrayList<>();
        components.add(probe("postgres", 3, () -> {
            healthMapper.ping();
            return "connected";
        }));
        components.add(probe("redis", 2, () -> {
            Objects.requireNonNull(redisConnectionFactory.getConnection()).close();
            return "pong";
        }));
        components.add(probe("manticore", 3, () -> {
            // /sql 非 raw 模式会把语句编译成搜索查询：必须带 FROM 索引。
            // SELECT 1 与 SHOW TABLES 都会被回 501 `only SELECT queries are supported`，
            // 探测语句因此必须查真实索引，顺带证明索引还在。
            manticoreUtilsApi.sql("SELECT log_id FROM lw_log LIMIT 1", false);
            return "log index ok";
        }));
        components.add(probe("minio", 5, () -> minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket("liteworkbench").build()) ? "bucket ready" : "bucket missing"));
        components.add(probe("rabbitmq", 3, () -> {
            rabbitConnectionFactory.createConnection().close();
            return "connected";
        }));
        components.add(probe("python", 5, () -> {
            var status = pythonRuntime.detect();
            if (!status.ok()) {
                throw new IllegalStateException(status.status());
            }
            return "Python " + status.version();
        }));

        HealthRESP resp = new HealthRESP();
        resp.setComponents(components);
        boolean coreDown = components.stream()
                .anyMatch(item -> ("postgres".equals(item.getName()) || "redis".equals(item.getName()))
                        && "DOWN".equals(item.getStatus()));
        boolean anyDown = components.stream().anyMatch(item -> "DOWN".equals(item.getStatus()));
        resp.setStatus(coreDown ? "DOWN" : anyDown ? "DEGRADED" : "UP");
        return resp;
    }

    /** Python 运行时状态 */
    public PythonStatusVO pythonStatus() {
        var status = pythonRuntime.detect();
        PythonStatusVO vo = new PythonStatusVO();
        vo.setStatus(status.status());
        vo.setVersion(status.version());
        vo.setSource(status.source());
        vo.setOk(status.ok());
        return vo;
    }

    /** 重新探测 Python */
    public void reprobePython() {
        pythonRuntime.resetProbe();
        pythonRuntime.detect();
    }

    /** 仪表盘汇总指标，全局口径，Redis 缓存 60s 避免高频重算 */
    public OverviewRESP overview() {
        try {
            String cached = redisTemplate.opsForValue().get(RedisKeyConst.CACHE_OVERVIEW);
            if (StrUtil.isNotBlank(cached)) {
                return JSONUtil.toBean(cached, OverviewRESP.class);
            }
        } catch (Exception e) {
            log.warn("[overview.cache] 读取失败 {}", e.getMessage());
        }
        OverviewRESP resp = buildOverview();
        try {
            redisTemplate.opsForValue().set(RedisKeyConst.CACHE_OVERVIEW,
                    JSONUtil.toJsonStr(resp), Duration.ofSeconds(OVERVIEW_CACHE_SECONDS));
        } catch (Exception e) {
            log.warn("[overview.cache] 写入失败 {}", e.getMessage());
        }
        return resp;
    }

    /** 汇总指标实算 */
    private OverviewRESP buildOverview() {
        OverviewRESP resp = new OverviewRESP();
        resp.setSessionCount(sessionMapper.countAll());
        resp.setRunCount(runMapper.countAll());
        resp.setSuccessRunCount(runMapper.countByState(RunStatusEnum.SUCCESS.getCode()));
        resp.setTodayRunCount(runMapper.countToday());
        resp.setTotalTokens(runMapper.sumTokens());
        resp.setRunTrend(toTrend(runMapper.runTrendByDay(7)));
        resp.setTokenTrend(toTrend(runMapper.tokenTrendByDay(7)));

        Map<String, Long> distribution = new LinkedHashMap<>();
        eventMapper.toolDistribution(7).forEach(row -> {
            Object name = row.get("name");
            Object value = row.get("value");
            if (Objects.nonNull(name)) {
                distribution.put(name.toString(), value instanceof Number number ? number.longValue() : 0L);
            }
        });
        resp.setToolDistribution(distribution);
        return resp;
    }

    /** 单组件探测：超时与异常统一记为 DOWN */
    private HealthRESP.ComponentHealth probe(String name, int timeoutSeconds, ProbeAction action) {
        HealthRESP.ComponentHealth component = new HealthRESP.ComponentHealth();
        component.setName(name);
        long start = System.currentTimeMillis();
        Future<String> future = probeExecutor.submit(action::run);
        try {
            component.setDetail(future.get(timeoutSeconds, TimeUnit.SECONDS));
            component.setStatus("UP");
        } catch (Exception e) {
            future.cancel(true);
            component.setStatus("DOWN");
            component.setDetail(e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getMessage());
        }
        component.setCostMs(System.currentTimeMillis() - start);
        return component;
    }

    /** 补齐近 7 天缺失日期为 0 */
    private List<OverviewRESP.TrendPoint> toTrend(List<Map<String, Object>> rows) {
        Map<String, Long> byDay = new HashMap<>();
        rows.forEach(row -> {
            Object date = row.get("date");
            Object value = row.get("value");
            if (Objects.nonNull(date)) {
                byDay.put(date.toString(), value instanceof Number number ? number.longValue() : 0L);
            }
        });
        List<OverviewRESP.TrendPoint> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            String day = LocalDate.now().minusDays(i).format(DAY_FORMAT);
            trend.add(new OverviewRESP.TrendPoint(day, byDay.getOrDefault(day, 0L)));
        }
        return trend;
    }

    @FunctionalInterface
    private interface ProbeAction {
        String run() throws Exception;
    }
}
