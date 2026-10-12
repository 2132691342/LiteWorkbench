package com.liteworkbench.mapper;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.dto.MessageDocDTO;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 会话消息 Manticore 表访问：用户资产不设保留期，业务主键为 message_id 列 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageIndexMapper {

    private static final String TABLE = "lw_msg";
    /** 单会话读取的批量上限 */
    private static final int SCAN_BATCH = 500;

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS lw_msg (
                message_id  string,
                user_id     string,
                session_id  string,
                run_id      string,
                role        string,
                content     text indexed stored,
                token_count int,
                ts          bigint
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
            utilsApi.sql(StrUtil.format("SELECT message_id FROM {} WHERE role = '__probe__' LIMIT 1", TABLE), false);
            log.info("[msg.table] 表就绪 {}", TABLE);
            return true;
        } catch (Exception e) {
            log.warn("[msg.table] 建表失败 {} {}", TABLE, e.getMessage());
            return false;
        }
    }

    /** 写入消息文档，不设保留期 */
    public void add(MessageDocDTO message) {
        try {
            indexApi.insert(new InsertDocumentRequest()
                    .table(TABLE)
                    .doc(docOf(message)));
        } catch (Exception e) {
            log.warn("[msg.insert] 写入失败 messageId={} {}", message.messageId(), e.getMessage());
        }
    }

    /** 会话消息，按 (ts, messageId) 稳定升序；excludeRunId 非空时排除该运行的消息 */
    public List<MessageDocDTO> listBySession(String sessionId, int limit, String excludeRunId) {
        try {
            SearchResponse response = searchApi.search(new SearchRequest()
                    .table(TABLE)
                    .query(sessionQuery(sessionId, excludeRunId))
                    .sort(List.of(Map.of("ts", "asc")))
                    .limit(Math.max(1, Math.min(limit, SCAN_BATCH))));
            return parseMessages(response).stream()
                    .sorted(Comparator.comparingLong(MessageDocDTO::ts)
                            .thenComparing(MessageDocDTO::messageId))
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            log.warn("[msg.search] 会话消息读取失败 session={} {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 删除会话全部消息文档，返回删除数 */
    public long deleteBySession(String sessionId) {
        try {
            DeleteResponse response = indexApi.delete(new DeleteDocumentRequest()
                    .table(TABLE)
                    .query(new QueryFilter().equalsDef(Map.of("session_id", sessionId))));
            return Objects.requireNonNullElse(response.getDeleted(), 0);
        } catch (Exception e) {
            log.warn("[msg.delete] 会话消息清理失败 session={} {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /** 会话过滤查询：session_id 必带，excludeRunId 非空时排除当前运行 */
    private static SearchQuery sessionQuery(String sessionId, String excludeRunId) {
        BoolFilter bool = new BoolFilter()
                .addMustItem(new QueryFilter().equalsDef(Map.of("session_id", sessionId)));
        if (StrUtil.isNotBlank(excludeRunId)) {
            bool.addMustNotItem(new QueryFilter().equalsDef(Map.of("run_id", excludeRunId)));
        }
        return new SearchQuery().bool(bool);
    }

    /** 消息文档转 Manticore 文档字段 */
    private static Map<String, Object> docOf(MessageDocDTO message) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("message_id", StrUtil.nullToEmpty(message.messageId()));
        doc.put("user_id", StrUtil.nullToEmpty(message.userId()));
        doc.put("session_id", StrUtil.nullToEmpty(message.sessionId()));
        doc.put("run_id", StrUtil.nullToEmpty(message.runId()));
        doc.put("role", StrUtil.nullToEmpty(message.role()));
        doc.put("content", StrUtil.nullToEmpty(message.content()));
        doc.put("token_count", Objects.requireNonNullElse(message.tokenCount(), 0));
        doc.put("ts", Objects.requireNonNullElse(message.ts(), 0L));
        return doc;
    }

    /** 命中列表转消息 DTO */
    private static List<MessageDocDTO> parseMessages(SearchResponse response) {
        if (Objects.isNull(response) || Objects.isNull(response.getHits())) {
            return List.of();
        }
        List<HitsHits> hits = Objects.requireNonNullElse(response.getHits().getHits(), List.of());
        List<MessageDocDTO> result = new ArrayList<>(hits.size());
        for (HitsHits hit : hits) {
            Map<String, String> doc = ManticoreSupport.sourceMap(hit.getSource());
            result.add(new MessageDocDTO(
                    ManticoreSupport.text(doc, "message_id"),
                    ManticoreSupport.text(doc, "user_id"),
                    ManticoreSupport.text(doc, "session_id"),
                    ManticoreSupport.text(doc, "run_id"),
                    ManticoreSupport.text(doc, "role"),
                    ManticoreSupport.text(doc, "content"),
                    ManticoreSupport.intValue(doc, "token_count"),
                    ManticoreSupport.longValue(doc, "ts")));
        }
        return result;
    }
}
