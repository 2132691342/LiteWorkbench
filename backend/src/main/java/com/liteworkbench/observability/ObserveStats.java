package com.liteworkbench.observability;

import com.liteworkbench.config.ObserveProperties;
import com.liteworkbench.domain.vo.ObserveRESP;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/** 调用观测聚合：SQL 与 HTTP 的计数、耗时与慢调用榜，窗口自应用启动或上次重置起算，进程内不落库 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObserveStats {

    /** 观测键上限：SQL 语句模板与 HTTP 路由模式本就有界，超界只可能来自未规范化的路径 */
    private static final int MAX_KEYS = 500;

    private final LongAdder sqlCount = new LongAdder();
    private final LongAdder sqlSlowCount = new LongAdder();
    private final LongAdder sqlTotalMs = new LongAdder();
    private final LongAccumulator sqlMaxMs = new LongAccumulator(Long::max, 0L);
    private final LongAdder httpCount = new LongAdder();
    private final LongAdder httpErrorCount = new LongAdder();
    private final LongAdder httpTotalMs = new LongAdder();
    private final LongAccumulator httpMaxMs = new LongAccumulator(Long::max, 0L);
    private final ConcurrentHashMap<String, CallStat> sqlStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CallStat> httpStats = new ConcurrentHashMap<>();
    private final AtomicLong windowStartMs = new AtomicLong(System.currentTimeMillis());

    private final ObserveProperties observeProperties;

    /** 记一次 SQL：耗时达到阈值即计入慢查询 */
    public void recordSql(String key, long costMs, String sample) {
        sqlCount.increment();
        sqlTotalMs.add(costMs);
        sqlMaxMs.accumulate(costMs);
        if (costMs >= observeProperties.getSql().getSlowMs()) {
            sqlSlowCount.increment();
        }
        stat(sqlStats, key).update(costMs, sample);
        guard(sqlStats);
    }

    /** 记一次 HTTP：状态码 >= 500 计入失败 */
    public void recordHttp(String key, long costMs, int status, String sample) {
        httpCount.increment();
        httpTotalMs.add(costMs);
        httpMaxMs.accumulate(costMs);
        if (status >= 500) {
            httpErrorCount.increment();
        }
        stat(httpStats, key).update(costMs, sample);
        guard(httpStats);
    }

    /** 清空窗口：计数、耗时与慢调用榜一并归零，窗口起点挪到当前 */
    public void reset() {
        sqlCount.reset();
        sqlSlowCount.reset();
        sqlTotalMs.reset();
        sqlMaxMs.reset();
        httpCount.reset();
        httpErrorCount.reset();
        httpTotalMs.reset();
        httpMaxMs.reset();
        sqlStats.clear();
        httpStats.clear();
        windowStartMs.set(System.currentTimeMillis());
    }

    /** 当前窗口快照：汇总指标与两侧慢调用榜 */
    public ObserveRESP snapshot() {
        ObserveRESP resp = new ObserveRESP();
        long start = windowStartMs.get();
        resp.setWindowStartMs(start);
        resp.setWindowMs(Math.max(System.currentTimeMillis() - start, 0L));

        ObserveRESP.SqlObserve sql = new ObserveRESP.SqlObserve();
        long sqlTotal = sqlCount.sum();
        sql.setCount(sqlTotal);
        sql.setSlowCount(sqlSlowCount.sum());
        sql.setAvgMs(sqlTotal <= 0 ? 0L : sqlTotalMs.sum() / sqlTotal);
        sql.setMaxMs(sqlMaxMs.get());
        sql.setTop(top(sqlStats, observeProperties.getSql().getTopSize()));
        resp.setSql(sql);

        ObserveRESP.HttpObserve http = new ObserveRESP.HttpObserve();
        long httpTotal = httpCount.sum();
        http.setCount(httpTotal);
        http.setErrorCount(httpErrorCount.sum());
        http.setAvgMs(httpTotal <= 0 ? 0L : httpTotalMs.sum() / httpTotal);
        http.setMaxMs(httpMaxMs.get());
        http.setTop(top(httpStats, observeProperties.getHttp().getTopSize()));
        resp.setHttp(http);
        return resp;
    }

    private CallStat stat(ConcurrentHashMap<String, CallStat> map, String key) {
        return map.computeIfAbsent(key, ignored -> new CallStat());
    }

    private List<ObserveRESP.CallStatVO> top(ConcurrentHashMap<String, CallStat> map, int size) {
        return map.entrySet().stream()
                .map(entry -> toVo(entry.getKey(), entry.getValue()))
                .sorted((left, right) -> Long.compare(right.getMaxMs(), left.getMaxMs()))
                .limit(Math.max(size, 1))
                .toList();
    }

    private ObserveRESP.CallStatVO toVo(String key, CallStat stat) {
        long count = Math.max(stat.count.sum(), 1L);
        ObserveRESP.CallStatVO vo = new ObserveRESP.CallStatVO();
        vo.setKey(key);
        vo.setCount(stat.count.sum());
        vo.setAvgMs(stat.totalMs.sum() / count);
        vo.setMaxMs(stat.maxMs.get());
        vo.setLastTs(stat.lastTs);
        vo.setSample(stat.sample);
        return vo;
    }

    /** 键数封顶：淘汰慢榜之外的一半，避免路径规范化失效时把堆吃掉 */
    private void guard(ConcurrentHashMap<String, CallStat> map) {
        if (map.size() < MAX_KEYS) {
            return;
        }
        Set<String> keep = map.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().maxMs.get(), left.getValue().maxMs.get()))
                .limit(MAX_KEYS / 2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        map.keySet().removeIf(key -> !keep.contains(key));
        log.warn("[observe.evict] 观测键达到上限 {}，已淘汰至 {} 个", MAX_KEYS, keep.size());
    }

    /** 单个观测目标的累计；并发下 maxMs 取精确最新值，其余为最终一致 */
    private static final class CallStat {

        private final LongAdder count = new LongAdder();
        private final LongAdder totalMs = new LongAdder();
        private final LongAccumulator maxMs = new LongAccumulator(Long::max, 0L);
        private volatile long lastTs;
        private volatile String sample;

        private void update(long costMs, String sampleValue) {
            count.increment();
            totalMs.add(costMs);
            maxMs.accumulate(costMs);
            lastTs = System.currentTimeMillis();
            sample = sampleValue;
        }
    }
}
