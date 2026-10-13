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
import java.util.regex.Pattern;

/** 平台日志 Manticore 表访问：写入即检索，保留期由应用定时清理 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogSearchMapper {

    private static final String TABLE = "lw_log";

    /** 用户 ID 过滤白名单：SQL 拼接前必须过该校验（全局视角的管理员入参不可信） */
    private static final Pattern USER_ID_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,64}");

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS lw_log (
                log_id     string,
                ts         bigint,
                level      string,
                logger     string,
                thread     string,
                trace_id   string,
                user_id    string,
                session_id string,
                run_id     string,
                message    text indexed stored,
                exception  text indexed stored
            ) charset_table='non_cont,cjk' morphology='icu_chinese'
            """;

    /** 存量表升级：链路标识列与异常列逐条补齐，列已存在时该语句失败可忽略，最终由复验 SELECT 兜底 */
    private static final List<String> UPGRADE_DDL = List.of(
            "ALTER TABLE lw_log ADD COLUMN user_id string",
            "ALTER TABLE lw_log ADD COLUMN session_id string",
            "ALTER TABLE lw_log ADD COLUMN run_id string",
            "ALTER TABLE lw_log ADD COLUMN exception text indexed stored");

    private final IndexApi indexApi;
    private final SearchApi searchApi;
    private final UtilsApi utilsApi;
    private final ManticoreCliExecutor cliExecutor;

    /** 表就绪标记：未就绪时写入路径先自保建表，杜绝 /insert 隐式建出缺列结构 */
    private volatile boolean tableReady;

    /** 幂等建表：/cli 执行后必须用属性过滤 SELECT 校验（/cli 对失败语句也回显成功，且 /insert 会建出错结构表） */
    public synchronized boolean ensureTable() {
        if (tableReady) {
            return true;
        }
        try {
            cliExecutor.execute(DDL);
            for (String sql : UPGRADE_DDL) {
                try {
                    cliExecutor.execute(sql);
                } catch (Exception ignored) {
                    // 列已存在时语句失败，属预期
                }
            }
            // 复验覆盖全文列：若 /insert 曾把表建错结构，全文列缺失会在此暴露
            utilsApi.sql(StrUtil.format(
                    "SELECT log_id, user_id, session_id, run_id, message, exception FROM {} WHERE level = '__probe__' LIMIT 1",
                    TABLE), false);
            tableReady = true;
            log.info("[log.table] 表就绪 {}", TABLE);
            return true;
        } catch (Exception e) {
            log.warn("[log.table] 建表失败 {} {}", TABLE, e.getMessage());
            return false;
        }
    }

    /** 写入日志文档，失败仅告警不抛异常（调用方在 MQ 消费线程） */
    public void add(LogPublisher.LogMessage message) {
        if (!tableReady && !ensureTable()) {
            return;
        }
        try {
            indexApi.insert(new InsertDocumentRequest()
                    .table(TABLE)
                    .doc(docOf(message)));
        } catch (Exception e) {
            // 结构被历史数据污染时补一次对账再重试，仍失败仅告警
            if (ensureTable()) {
                try {
                    indexApi.insert(new InsertDocumentRequest()
                            .table(TABLE)
                            .doc(docOf(message)));
                    return;
                } catch (Exception retry) {
                    log.warn("[log.insert] 重试写入失败 logId={} {}", message.logId(), retry.getMessage());
                    return;
                }
            }
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

    /** 检索日志，按 ts 倒序分页，异常降级为空结果；userId / runId 已由服务端视角判定后传入 */
    public LogSearchDTO search(String keyword, String field, String level,
                               String logger, String traceId, String userId, String runId,
                               long from, long to, long page, long size) {
        // 单页上限 500，offset 用截断后的页大小计算，避免大 size 翻页漏数据
        long pageSize = Math.max(1, Math.min(size, 500));
        long offset = Math.max(page - 1, 0) * pageSize;
        try {
            SearchResponse response = searchApi.search(new SearchRequest()
                    .table(TABLE)
                    .query(logQuery(keyword, field, level, logger, traceId, userId, runId, from, to))
                    .sort(List.of(Map.of("ts", "desc")))
                    .limit((int) pageSize)
                    .offset((int) Math.max(0, offset)));
            return new LogSearchDTO(parseLogs(response), totalOf(response));
        } catch (Exception e) {
            log.warn("[log.search] 检索失败，返回空结果 {}", e.getMessage());
            return new LogSearchDTO(List.of(), 0L);
        }
    }

    /** 分布概览：级别计数 + 时间直方图，过滤条件与检索一致；dayBucket 为真时按天分桶 */
    public LogStatsVO stats(String userId, String keyword, String field, String level,
                            String logger, String traceId, String runId,
                            long from, long to, boolean dayBucket) {
        String filter = filterClause(keyword, field, level, logger, traceId, runId);
        LogStatsVO stats = new LogStatsVO();
        stats.setLevels(levelCounts(userId, filter, from, to));
        stats.setHistogram(histogram(userId, filter, from, to, dayBucket));
        stats.setBucketMs(dayBucket ? 86_400_000L : 3_600_000L);
        return stats;
    }

    /** 日志过滤查询：等值过滤（level / logger / traceId / user_id / run_id）可选，ts 范围可选，关键字按 field 改判匹配列 */
    private static SearchQuery logQuery(String keyword, String field, String level,
                                        String logger, String traceId, String userId, String runId,
                                        long from, long to) {
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
        if (StrUtil.isNotBlank(userId)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("user_id", userId)));
        }
        if (StrUtil.isNotBlank(runId)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("run_id", runId)));
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

    /** 视角过滤 SQL 片段：userId 必须通过白名单校验才允许拼接，非法入参退化为不过滤 */
    private static String userClause(String userId) {
        if (StrUtil.isBlank(userId) || !USER_ID_PATTERN.matcher(userId).matches()) {
            return "";
        }
        return " AND user_id = '" + userId + "'";
    }

    /** 级别计数：SELECT level, COUNT(*) GROUP BY level（Manticore 不支持 COUNT(1)） */
    private List<LogStatsVO.LevelCount> levelCounts(String userId, String filter, long from, long to) {
        String sql = StrUtil.format("""
                SELECT level, COUNT(*) AS cnt FROM {}
                WHERE ts >= {} AND ts <= {}{}{}
                GROUP BY level
                """, TABLE, from, to, userClause(userId), filter);
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

    /** 时间直方图：DIV 整数除分桶（`/` 在 Manticore 是浮点除，桶起点会漂移），桶起点毫秒时间戳升序；
     *  小时分桶上限 168 个（7 天），天分桶上限 90 个 */
    private List<LogStatsVO.HourCount> histogram(String userId, String filter, long from, long to,
                                                 boolean dayBucket) {
        long bucketMs = dayBucket ? 86_400_000L : 3_600_000L;
        int limit = dayBucket ? 90 : 168;
        String sql = StrUtil.format("""
                SELECT ts DIV {} AS bucket, COUNT(*) AS cnt FROM {}
                WHERE ts >= {} AND ts <= {}{}{}
                GROUP BY bucket
                ORDER BY bucket ASC
                LIMIT {}
                """, bucketMs, TABLE, from, to, userClause(userId), filter, limit);
        List<LogStatsVO.HourCount> result = new ArrayList<>();
        for (Map<String, Object> row : groupRows(sql)) {
            Object bucket = row.get("bucket");
            if (Objects.nonNull(bucket)) {
                LogStatsVO.HourCount count = new LogStatsVO.HourCount();
                count.setHour(numberValue(bucket) * bucketMs);
                count.setCount(numberValue(row.get("cnt")));
                result.add(count);
            }
        }
        return result;
    }

    /** 聚合过滤片段：与检索同一套等值与全文条件，字符串值统一转义防注入 */
    private static String filterClause(String keyword, String field, String level,
                                       String logger, String traceId, String runId) {
        StringBuilder clause = new StringBuilder();
        if (StrUtil.isNotBlank(level)) {
            clause.append(" AND level = '").append(escape(level.toUpperCase())).append("'");
        }
        if (StrUtil.isNotBlank(logger)) {
            clause.append(" AND logger = '").append(escape(logger)).append("'");
        }
        if (StrUtil.isNotBlank(traceId)) {
            clause.append(" AND trace_id = '").append(escape(traceId)).append("'");
        }
        if (StrUtil.isNotBlank(runId)) {
            clause.append(" AND run_id = '").append(escape(runId)).append("'");
        }
        if (StrUtil.isNotBlank(keyword)) {
            String matchField = StrUtil.isBlank(field) ? "message" : field;
            if ("logger".equals(matchField)) {
                clause.append(" AND logger = '").append(escape(keyword)).append("'");
            } else if ("traceId".equals(matchField)) {
                clause.append(" AND trace_id = '").append(escape(keyword)).append("'");
            } else {
                clause.append(" AND MATCH('message', '").append(escape(keyword)).append("')");
            }
        }
        return clause.toString();
    }

    /** SQL 字符串值转义：单引号加倍，聚合语句为字符串拼接，必须过该函数 */
    private static String escape(String value) {
        return StrUtil.nullToEmpty(value).replace("'", "''");
    }

    /** 执行分组查询，把命中的 _source 归一为字段表列表；异常转运行时由调用方降级 */
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
        doc.put("user_id", StrUtil.nullToEmpty(message.userId()));
        doc.put("session_id", StrUtil.nullToEmpty(message.sessionId()));
        doc.put("run_id", StrUtil.nullToEmpty(message.runId()));
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
            vo.setUserId(ManticoreSupport.text(doc, "user_id"));
            vo.setSessionId(ManticoreSupport.text(doc, "session_id"));
            vo.setRunId(ManticoreSupport.text(doc, "run_id"));
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
