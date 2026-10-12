package com.liteworkbench.mapper;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.vo.DocVO;
import com.manticoresearch.client.api.IndexApi;
import com.manticoresearch.client.api.SearchApi;
import com.manticoresearch.client.api.UtilsApi;
import com.manticoresearch.client.model.BoolFilter;
import com.manticoresearch.client.model.DeleteDocumentRequest;
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

/** 创作文档 Manticore 检索表访问：PG 为准的索引副本，保存即先删同键再插入 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocIndexMapper {

    private static final String TABLE = "lw_doc";

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS lw_doc (
                doc_id     string,
                user_id    string,
                doc_title  text indexed stored,
                category   string,
                content    text indexed stored,
                word_count int,
                updated_at bigint
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
            utilsApi.sql(StrUtil.format("SELECT doc_id FROM {} WHERE category = '__probe__' LIMIT 1", TABLE), false);
            log.info("[doc.table] 表就绪 {}", TABLE);
            return true;
        } catch (Exception e) {
            log.warn("[doc.table] 建表失败 {} {}", TABLE, e.getMessage());
            return false;
        }
    }

    /** 覆盖写单条文档索引：先删同 doc_id 文档再插入 */
    public void upsert(String docId, String userId, String docTitle, String category,
                       String contentText, int wordCount, long updatedAt) {
        try {
            indexApi.delete(new DeleteDocumentRequest()
                    .table(TABLE)
                    .query(new QueryFilter().equalsDef(Map.of("doc_id", docId))));
            indexApi.insert(new InsertDocumentRequest()
                    .table(TABLE)
                    .doc(docOf(docId, userId, docTitle, category, contentText, wordCount, updatedAt)));
        } catch (Exception e) {
            log.warn("[doc.upsert] 写入失败 docId={} {}", docId, e.getMessage());
        }
    }

    /** 删除文档索引 */
    public void delete(String docId) {
        try {
            indexApi.delete(new DeleteDocumentRequest()
                    .table(TABLE)
                    .query(new QueryFilter().equalsDef(Map.of("doc_id", docId))));
        } catch (Exception e) {
            log.warn("[doc.delete] 删除失败 docId={} {}", docId, e.getMessage());
        }
    }

    /** 全文检索：user_id 必带，分类可选，标题与正文任一命中，按更新时间倒序 */
    public List<DocVO> search(String userId, String category, String keyword, long offset, long size) {
        try {
            SearchResponse response = searchApi.search(new SearchRequest()
                    .table(TABLE)
                    .query(docQuery(userId, category, keyword))
                    .sort(List.of(Map.of("updated_at", "desc")))
                    .limit((int) Math.max(1, Math.min(size, 500)))
                    .offset((int) Math.max(0, offset)));
            return parseDocs(response);
        } catch (Exception e) {
            log.warn("[doc.search] 检索失败 {}", e.getMessage());
            return List.of();
        }
    }

    /** 检索总数，分页计数用 */
    public long count(String userId, String category, String keyword) {
        try {
            SearchResponse response = searchApi.search(new SearchRequest()
                    .table(TABLE)
                    .query(docQuery(userId, category, keyword))
                    .limit(1));
            return totalOf(response);
        } catch (Exception e) {
            log.warn("[doc.count] 计数失败 {}", e.getMessage());
            return 0;
        }
    }

    /** 文档过滤查询：user_id 必带，category 可选，关键字全文走 标题 | 正文 */
    private static SearchQuery docQuery(String userId, String category, String keyword) {
        BoolFilter bool = new BoolFilter()
                .addMustItem(new QueryFilter().equalsDef(Map.of("user_id", userId)));
        if (StrUtil.isNotBlank(category)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("category", category)));
        }
        String titleExpr = ManticoreSupport.fieldExpression("doc_title", keyword);
        String contentExpr = ManticoreSupport.fieldExpression("content", keyword);
        if (Objects.nonNull(titleExpr) && Objects.nonNull(contentExpr)) {
            bool.addMustItem(new QueryFilter().queryString(titleExpr + " | " + contentExpr));
        } else if (Objects.nonNull(titleExpr)) {
            bool.addMustItem(new QueryFilter().queryString(titleExpr));
        }
        return new SearchQuery().bool(bool);
    }

    /** 文档字段装配 */
    private static Map<String, Object> docOf(String docId, String userId, String docTitle, String category,
                                             String contentText, int wordCount, long updatedAt) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("doc_id", StrUtil.nullToEmpty(docId));
        doc.put("user_id", StrUtil.nullToEmpty(userId));
        doc.put("doc_title", StrUtil.nullToEmpty(docTitle));
        doc.put("category", StrUtil.nullToEmpty(category));
        doc.put("content", StrUtil.nullToEmpty(contentText));
        doc.put("word_count", wordCount);
        doc.put("updated_at", updatedAt);
        return doc;
    }

    /** 命中列表转文档 VO */
    private static List<DocVO> parseDocs(SearchResponse response) {
        if (Objects.isNull(response) || Objects.isNull(response.getHits())) {
            return List.of();
        }
        List<HitsHits> hits = Objects.requireNonNullElse(response.getHits().getHits(), List.of());
        List<DocVO> result = new ArrayList<>(hits.size());
        for (HitsHits hit : hits) {
            Map<String, String> doc = ManticoreSupport.sourceMap(hit.getSource());
            DocVO vo = new DocVO();
            vo.setDocId(ManticoreSupport.text(doc, "doc_id"));
            vo.setUserId(ManticoreSupport.text(doc, "user_id"));
            vo.setDocTitle(ManticoreSupport.text(doc, "doc_title"));
            vo.setCategory(ManticoreSupport.text(doc, "category"));
            vo.setWordCount(ManticoreSupport.intValue(doc, "word_count"));
            vo.setUpdatedAt(ManticoreSupport.longValue(doc, "updated_at"));
            vo.setSummary(StrUtil.sub(ManticoreSupport.text(doc, "content"), 0, 120));
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
