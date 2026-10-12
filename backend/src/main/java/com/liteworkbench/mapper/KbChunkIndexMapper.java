package com.liteworkbench.mapper;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.domain.vo.KbChunkVO;
import com.manticoresearch.client.api.IndexApi;
import com.manticoresearch.client.api.SearchApi;
import com.manticoresearch.client.api.UtilsApi;
import com.manticoresearch.client.model.BoolFilter;
import com.manticoresearch.client.model.DeleteDocumentRequest;
import com.manticoresearch.client.model.DeleteResponse;
import com.manticoresearch.client.model.HitsHits;
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

/** 知识库分块 Manticore 表访问：用户资产不设保留期，业务主键为 chunk_id 列 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbChunkIndexMapper {

    private static final String TABLE = "lw_kbchunk";

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS lw_kbchunk (
                chunk_id string,
                user_id  string,
                kb_id    string,
                doc_id   string,
                doc_name text indexed stored,
                seq      int,
                content  text indexed stored,
                ts       bigint
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
            utilsApi.sql(StrUtil.format("SELECT chunk_id FROM {} WHERE user_id = '__probe__' LIMIT 1", TABLE), false);
            log.info("[kbchunk.table] 表就绪 {}", TABLE);
            return true;
        } catch (Exception e) {
            log.warn("[kbchunk.table] 建表失败 {} {}", TABLE, e.getMessage());
            return false;
        }
    }

    /** 批量写入分块文档，不设保留期 */
    public void addBatch(List<KbChunkVO> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        try {
            StringBuilder ndjson = new StringBuilder(chunks.size() * 256);
            for (KbChunkVO chunk : chunks) {
                ndjson.append(JSONUtil.toJsonStr(Map.of("insert",
                                Map.of("table", TABLE, "doc", docOf(chunk)))))
                        .append('\n');
            }
            indexApi.bulk(ndjson.toString());
        } catch (Exception e) {
            log.warn("[kbchunk.insert] 批量写入失败 count={} {}", chunks.size(), e.getMessage());
        }
    }

    /** 分块检索：user_id 必带、kb_id 可选，关键字同时匹配正文与文档名；有关键字按相关度，无关键字按文档内序号 */
    public List<KbChunkVO> search(String userId, String kbId, String keyword, long offset, long size) {
        try {
            SearchRequest request = new SearchRequest()
                    .table(TABLE)
                    .query(chunkQuery(userId, kbId, keyword))
                    .limit((int) Math.max(1, Math.min(size, 500)))
                    .offset((int) Math.max(0, offset));
            if (StrUtil.isBlank(keyword)) {
                request.sort(List.of(Map.of("seq", "asc")));
            }
            return parseChunks(searchApi.search(request));
        } catch (Exception e) {
            log.warn("[kbchunk.search] 检索失败 {}", e.getMessage());
            return List.of();
        }
    }

    /** 检索总数，分页计数用 */
    public long count(String userId, String kbId, String keyword) {
        try {
            SearchResponse response = searchApi.search(new SearchRequest()
                    .table(TABLE)
                    .query(chunkQuery(userId, kbId, keyword))
                    .limit(1));
            return totalOf(response);
        } catch (Exception e) {
            log.warn("[kbchunk.count] 计数失败 {}", e.getMessage());
            return 0;
        }
    }

    /** 删除文档的全部分块，返回删除数 */
    public long deleteByDoc(String docId) {
        return deleteByField("doc_id", docId);
    }

    /** 删除知识库的全部分块，返回删除数 */
    public long deleteByKb(String kbId) {
        return deleteByField("kb_id", kbId);
    }

    /** 按属性等值删除，返回删除数 */
    private long deleteByField(String field, String value) {
        try {
            DeleteResponse response = indexApi.delete(new DeleteDocumentRequest()
                    .table(TABLE)
                    .query(new QueryFilter().equalsDef(Map.of(field, value))));
            return Objects.requireNonNullElse(response.getDeleted(), 0);
        } catch (Exception e) {
            log.warn("[kbchunk.delete] 分块清理失败 {}={} {}", field, value, e.getMessage());
            return 0;
        }
    }

    /** 分块过滤查询：user_id 必带，kb_id 可选，关键字全文走 正文 | 文档名 */
    private static SearchQuery chunkQuery(String userId, String kbId, String keyword) {
        BoolFilter bool = new BoolFilter()
                .addMustItem(new QueryFilter().equalsDef(Map.of("user_id", userId)));
        if (StrUtil.isNotBlank(kbId)) {
            bool.addMustItem(new QueryFilter().equalsDef(Map.of("kb_id", kbId)));
        }
        String contentExpr = ManticoreSupport.fieldExpression("content", keyword);
        String docNameExpr = ManticoreSupport.fieldExpression("doc_name", keyword);
        if (Objects.nonNull(contentExpr) && Objects.nonNull(docNameExpr)) {
            bool.addMustItem(new QueryFilter().queryString(contentExpr + " | " + docNameExpr));
        } else if (Objects.nonNull(contentExpr)) {
            bool.addMustItem(new QueryFilter().queryString(contentExpr));
        }
        return new SearchQuery().bool(bool);
    }

    /** 分块文档转 Manticore 文档字段 */
    private static Map<String, Object> docOf(KbChunkVO chunk) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("chunk_id", StrUtil.nullToEmpty(chunk.getChunkId()));
        doc.put("user_id", StrUtil.nullToEmpty(chunk.getUserId()));
        doc.put("kb_id", StrUtil.nullToEmpty(chunk.getKbId()));
        doc.put("doc_id", StrUtil.nullToEmpty(chunk.getDocId()));
        doc.put("doc_name", StrUtil.nullToEmpty(chunk.getDocName()));
        doc.put("seq", Objects.requireNonNullElse(chunk.getSeq(), 0));
        doc.put("content", StrUtil.nullToEmpty(chunk.getContent()));
        doc.put("ts", Objects.requireNonNullElse(chunk.getCreatedAt(), 0L));
        return doc;
    }

    /** 命中列表转分块 VO */
    private static List<KbChunkVO> parseChunks(SearchResponse response) {
        if (Objects.isNull(response) || Objects.isNull(response.getHits())) {
            return List.of();
        }
        List<HitsHits> hits = Objects.requireNonNullElse(response.getHits().getHits(), List.of());
        List<KbChunkVO> result = new ArrayList<>(hits.size());
        for (HitsHits hit : hits) {
            Map<String, String> doc = ManticoreSupport.sourceMap(hit.getSource());
            KbChunkVO vo = new KbChunkVO();
            vo.setChunkId(ManticoreSupport.text(doc, "chunk_id"));
            vo.setUserId(ManticoreSupport.text(doc, "user_id"));
            vo.setKbId(ManticoreSupport.text(doc, "kb_id"));
            vo.setDocId(ManticoreSupport.text(doc, "doc_id"));
            vo.setDocName(ManticoreSupport.text(doc, "doc_name"));
            vo.setSeq(ManticoreSupport.intValue(doc, "seq"));
            vo.setContent(ManticoreSupport.text(doc, "content"));
            vo.setCreatedAt(ManticoreSupport.longValue(doc, "ts"));
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
