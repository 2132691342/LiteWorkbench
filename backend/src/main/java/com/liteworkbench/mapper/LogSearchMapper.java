package com.liteworkbench.mapper;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.dto.LogSearchDTO;
import com.liteworkbench.domain.vo.LogDocVO;
import com.liteworkbench.observability.LogPublisher;
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
    public LogSearchDTO search(String keyword, String level,
                               long from, long to, long page, long size) {
        long offset = Math.max(page - 1, 0) * Math.max(size, 1);
        try {
            SearchResponse response = searchApi.search(new SearchRequest()
                    .table(TABLE)
                    .query(logQuery(keyword, level, from, to))
                    .sort(List.of(Map.of("ts", "desc")))
                    .limit((int) Math.max(1, Math.min(size, 500)))
                    .offset((int) Math.max(0, offset)));
            return new LogSearchDTO(parseLogs(response), totalOf(response));
        } catch (Exception e) {
            log.warn("[log.search] 检索失败，返回空结果 {}", e.getMessage());
            return new LogSearchDTO(List.of(), 0L);
        }
    }

    /** 日志过滤查询：level 等值可选，ts 范围可选，关键字全文走 message */
    private static SearchQuery logQuery(String keyword, String level, long from, long to) {
        BoolFilter bool = new BoolFilter();
        if (StrUtil.isNotBlank(level)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("level", level.toUpperCase())));
        }
        if (from > 0 && to > 0) {
            bool.addMustItem(new QueryFilter().range(Map.of("ts", Map.of("gte", from, "lte", to))));
        }
        String messageExpr = ManticoreSupport.fieldExpression("message", keyword);
        if (Objects.nonNull(messageExpr)) {
            bool.addMustItem(new QueryFilter().queryString(messageExpr));
        }
        return new SearchQuery().bool(bool);
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
