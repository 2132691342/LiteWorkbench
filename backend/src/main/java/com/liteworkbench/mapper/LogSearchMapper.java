package com.liteworkbench.mapper;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.dto.LogSearchDTO;
import com.liteworkbench.domain.vo.LogDocVO;
import com.liteworkbench.domain.vo.LogStatsVO;
import com.liteworkbench.observability.LogPublisher;
import com.manticoresearch.client.ApiException;
import com.manticoresearch.client.api.IndexApi;
import com.manticoresearch.client.api.SearchApi;
import com.manticoresearch.client.api.UtilsApi;
import com.manticoresearch.client.model.BoolFilter;
import com.manticoresearch.client.model.DeleteDocumentRequest;
import com.manticoresearch.client.model.DeleteResponse;
import com.manticoresearch.client.model.HitsHits;
import com.manticoresearch.client.model.InsertDocumentRequest;
import com.manticoresearch.client.model.QueryFilter;
import com.manticoresearch.client.model.SearchQuery;
import com.manticoresearch.client.model.SearchRequest;
import com.manticoresearch.client.model.SearchResponse;
import com.manticoresearch.client.model.SqlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 平台日志 Manticore 表访问：写入即检索，保留期由应用定时清理 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogSearchMapper {

    private static final String TABLE = "lw_log";

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS lw_log (
                log_id    string,
                ts        bigint,
                level     string,
                logger    string,
                thread    string,
                trace_id  string,
                message   text indexed stored,
                exception text indexed stored
            ) charset_table='non_cont,cjk' morphology='icu_chinese'
            """;

    private final IndexApi indexApi;
    private final SearchApi searchApi;
    private final UtilsApi utilsApi;
    private final ManticoreCliExecutor cliExecutor;

    /** 幂等建表：/cli 执行后必须用属性过滤 SELECT 校验（/cli 对失败语句也回显成功，且 /insert 会建出错结构表） */
    public boolean ensureTable() {
        try {
            cliExecutor.execute(DDL);
            utilsApi.sql(StrUtil.format("SELECT log_id FROM {} WHERE level = '__probe__' LIMIT 1", TABLE), false);
            log.info("[log.table] 表就绪 {}", TABLE);
            return true;
        } catch (Exception e) {
            log.warn("[log.table] 建表失败 {} {}", TABLE, e.getMessage());
            return false;
        }
    }

    /** 写入日志文档，失败仅告警不抛异常（调用方在 MQ 消费线程） */
    public void add(LogPublisher.LogMessage message) {
        try {
            indexApi.insert(new InsertDocumentRequest()
                    .table(TABLE)
                    .doc(docOf(message)));
        } catch (Exception e) {
            log.warn("[log.insert] 写入失败 logId={} {}", message.logId(), e.getMessage());
        }
    }

    /** 删除截止时间之前的日志文档，返回删除数；保留期清理用 */
    public long deleteBefore(long cutoffTs) {
        try {
            DeleteResponse response = indexApi.delete(new DeleteDocumentRequest()
                    .table(TABLE)
                    .query(new QueryFilter().range(Map.of("ts", Map.of("lt", cutoffTs)))));
            return Objects.requireNonNullElse(response.getDeleted(), 0);
        } catch (Exception e) {
            log.warn("[log.delete] 保留期清理失败 cutoff={} {}", cutoffTs, e.getMessage());
            return 0;
        }
    }

    /** 检索日志，按 ts 倒序分页；Manticore 不可用或语法异常时降级为空结果 */
    public LogSearchDTO search(String keyword, String field, String level,
                               String logger, String traceId,
                               long from, long to, long page, long size) {
        long offset = Math.max(page - 1, 0) * Math.max(size, 1);
        try {
            SearchResponse response = searchApi.search(new SearchRequest()
                    .table(TABLE)
                    .query(logQuery(keyword, field, level, logger, traceId, from, to))
                    .sort(List.of(Map.of("ts", "desc")))
                    .limit((int) Math.max(1, Math.min(size, 500)))
                    .offset((int) Math.max(0, offset)));
            return new LogSearchDTO(parseLogs(response), totalOf(response));
        } catch (Exception e) {
            log.warn("[log.search] 检索失败，返回空结果 {}", e.getMessage());
            return new LogSearchDTO(List.of(), 0L);
        }
    }

    /**
     * 分布概览：级别计数 + 按小时直方图，与检索共用时间范围过滤。
     * 聚合走 /sql 的 GROUP BY（只读 SELECT），不拉结果集回内存再数；异常由调用方降级。
     */
    public LogStatsVO stats(long from, long to) {
        LogStatsVO stats = new LogStatsVO();
        stats.setLevels(levelCounts(from, to));
        stats.setHistogram(hourHistogram(from, to));
        return stats;
    }

    /** 日志过滤查询：等值过滤（level / logger / traceId）可选，ts 范围可选，关键字按 field 改判匹配列 */
    private static SearchQuery logQuery(String keyword, String field, String level,
                                        String logger, String traceId, long from, long to) {
        BoolFilter bool = new BoolFilter();
        if (StrUtil.isNotBlank(level)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("level", level.toUpperCase())));
        }
        if (StrUtil.isNotBlank(logger)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("logger", logger)));
        }
        if (StrUtil.isNotBlank(traceId)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("trace_id", traceId)));
        }
        if (from > 0 && to > 0) {
            bool.addMustItem(new QueryFilter().range(Map.of("ts", Map.of("gte", from, "lte", to))));
        }
        // field 决定关键字匹配列：message 走全文分词，logger / traceId 走属性等值
        String matchField = StrUtil.isBlank(field) ? "message" : field;
        if ("message".equals(matchField)) {
            String messageExpr = ManticoreSupport.fieldExpression("message", keyword);
            if (Objects.nonNull(messageExpr)) {
                bool.addMustItem(new QueryFilter().queryString(messageExpr));
            }
        } else if ("logger".equals(matchField) && StrUtil.isNotBlank(keyword)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("logger", keyword)));
        } else if ("traceId".equals(matchField) && StrUtil.isNotBlank(keyword)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("trace_id", keyword)));
        }
        return new SearchQuery().bool(bool);
    }

    /** 级别计数：SELECT level, COUNT(*) GROUP BY level（Manticore 不支持 COUNT(1)） */
    private List<LogStatsVO.LevelCount> levelCounts(long from, long to) {
        String sql = StrUtil.format("""
                SELECT level, COUNT(*) AS cnt FROM {}
                WHERE ts >= {} AND ts <= {}
                GROUP BY level
                """, TABLE, from, to);
        List<LogStatsVO.LevelCount> result = new ArrayList<>();
        for (Map<String, Object> row : groupRows(sql)) {
            Object level = row.get("level");
            if (Objects.nonNull(level)) {
                LogStatsVO.LevelCount count = new LogStatsVO.LevelCount();
                count.setLevel(level.toString());
                count.setCount(numberValue(row.get("cnt")));
                result.add(count);
            }
        }
        return result;
    }

    /** 小时直方图：DIV 整数除分桶（`/` 在 Manticore 是浮点除，桶起点会漂移），桶起点毫秒时间戳升序，上限 168 桶（7 天） */
    private List<LogStatsVO.HourCount> hourHistogram(long from, long to) {
        String sql = StrUtil.format("""
                SELECT ts DIV 3600000 AS hour_bucket, COUNT(*) AS cnt FROM {}
                WHERE ts >= {} AND ts <= {}
                GROUP BY hour_bucket
                ORDER BY hour_bucket ASC
                LIMIT 168
                """, TABLE, from, to);
        List<LogStatsVO.HourCount> result = new ArrayList<>();
        for (Map<String, Object> row : groupRows(sql)) {
            Object bucket = row.get("hour_bucket");
            if (Objects.nonNull(bucket)) {
                LogStatsVO.HourCount count = new LogStatsVO.HourCount();
                count.setHour(numberValue(bucket) * 3_600_000L);
                count.setCount(numberValue(row.get("cnt")));
                result.add(count);
            }
        }
        return result;
    }

    /**
     * 执行分组查询并把分组行归一为字段表列表。
     * /sql 非 raw 模式把 SQL 编译成搜索查询，分组结果的形状是
     * {@code {"hits":{"total":n,"hits":[{"_score":1,"_source":{列:值}}]}}}，
     * 列值藏在每个命中的 _source 里；from / to 为 long，SQL 无可注入片段；异常转运行时由调用方降级。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> groupRows(String sql) {
        SqlResponse response;
        try {
            response = utilsApi.sql(sql, false);
        } catch (ApiException e) {
            throw new IllegalStateException(StrUtil.format("manticore 聚合查询失败: {}", e.getMessage()), e);
        }
        if (Objects.isNull(response)) {
            return List.of();
        }
        Object hits = null;
        if (Objects.nonNull(response.getSqlObjResponse())) {
            hits = response.getSqlObjResponse().getHits();
        } else {
            try {
                hits = response.getListObject();
            } catch (ClassCastException ignored) {
                // 形态不匹配时按空处理，由调用方降级
            }
        }
        // 外层包装 {total, hits:[...]} 先剥掉，兼容直接返回行数组的形态
        Object rows = hits;
        if (hits instanceof Map<?, ?> wrapper && wrapper.get("hits") instanceof List<?> inner) {
            rows = inner;
        }
        if (!(rows instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> row) {
                Object source = row.get("_source");
                if (source instanceof Map<?, ?> sourceMap) {
                    result.add((Map<String, Object>) sourceMap);
                } else {
                    result.add((Map<String, Object>) row);
                }
            }
        }
        return result;
    }

    /** 聚合计数列转 long，缺失按 0 */
    private static long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 日志文档转 Manticore 文档字段 */
    private static Map<String, Object> docOf(LogPublisher.LogMessage message) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("log_id", StrUtil.nullToEmpty(message.logId()));
        doc.put("ts", message.ts());
        doc.put("level", StrUtil.nullToEmpty(message.level()));
        doc.put("logger", StrUtil.nullToEmpty(message.logger()));
        doc.put("thread", StrUtil.nullToEmpty(message.thread()));
        doc.put("trace_id", StrUtil.nullToEmpty(message.traceId()));
        doc.put("message", StrUtil.nullToEmpty(message.message()));
        if (StrUtil.isNotBlank(message.exception())) {
            doc.put("exception", message.exception());
        }
        return doc;
    }

    /** 命中列表转日志视图 */
    private static List<LogDocVO> parseLogs(SearchResponse response) {
        if (Objects.isNull(response) || Objects.isNull(response.getHits())) {
            return List.of();
        }
        List<HitsHits> hits = Objects.requireNonNullElse(response.getHits().getHits(), List.of());
        List<LogDocVO> result = new ArrayList<>(hits.size());
        for (HitsHits hit : hits) {
            Map<String, String> doc = ManticoreSupport.sourceMap(hit.getSource());
            LogDocVO vo = new LogDocVO();
            vo.setLogId(ManticoreSupport.text(doc, "log_id"));
            vo.setTs(ManticoreSupport.longValue(doc, "ts"));
            vo.setLevel(ManticoreSupport.text(doc, "level"));
            vo.setLogger(ManticoreSupport.text(doc, "logger"));
            vo.setThread(ManticoreSupport.text(doc, "thread"));
            vo.setTraceId(ManticoreSupport.text(doc, "trace_id"));
            vo.setMessage(ManticoreSupport.text(doc, "message"));
            vo.setException(ManticoreSupport.text(doc, "exception"));
            result.add(vo);
        }
        return result;
    }

    /** 命中总数，total_relation 非 eq 时按上限截断 */
    private static long totalOf(SearchResponse response) {
        if (Objects.isNull(response) || Objects.isNull(response.getHits())
                || Objects.isNull(response.getHits().getTotal())) {
            return 0;
        }
        return response.getHits().getTotal();
    }
}
