package com.liteworkbench.mapper;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.vo.DocVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 创作文档 Redisearch 索引访问，索引是 PG 的检索副本，落后时以 PG 为准
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocIndexMapper {

    private static final String INDEX = "idx:lw:doc";
    private static final String KEY_PREFIX = "lw:doc:";
    /** FT.SEARCH 查询语法特殊字符 */
    private static final String SPECIAL_CHARS = ",.<>{}[]\"':;!@#$%^&*()-+=~| ";

    private final StringRedisTemplate redisTemplate;

    /** 幂等创建索引，已存在则跳过；Redis 不可用时返回 false，不阻断启动 */
    public boolean ensureIndex() {
        try {
            return Boolean.TRUE.equals(redisTemplate.execute((RedisConnection connection) -> {
                try {
                    if (Objects.nonNull(connection.execute("FT.INFO", args(INDEX)))) {
                        log.info("[doc.index] 索引已存在 {}", INDEX);
                        return true;
                    }
                } catch (Exception ignored) {
                    // 索引不存在，走创建
                }
                try {
                    connection.execute("FT.CREATE", args(INDEX,
                            "ON", "HASH",
                            "PREFIX", "1", KEY_PREFIX,
                            "SCHEMA",
                            "doc_id", "TAG",
                            "user_id", "TAG",
                            "category", "TAG",
                            "doc_title", "TEXT",
                            "content", "TEXT",
                            "word_count", "NUMERIC",
                            "updated_at", "NUMERIC", "SORTABLE"));
                    log.info("[doc.index] 索引创建成功 {}", INDEX);
                    return true;
                } catch (Exception e) {
                    log.warn("[doc.index] 索引创建失败 {} {}", INDEX, e.getMessage());
                    return false;
                }
            }));
        } catch (Exception e) {
            log.warn("[doc.index] Redis 不可用，索引跳过 {} {}", INDEX, e.getMessage());
            return false;
        }
    }

    /** 覆盖写单条文档索引 */
    public void upsert(String docId, String userId, String docTitle, String category,
                       String contentText, int wordCount, long updatedAt) {
        Map<String, String> doc = new LinkedHashMap<>();
        doc.put("doc_id", StrUtil.nullToEmpty(docId));
        doc.put("user_id", StrUtil.nullToEmpty(userId));
        doc.put("category", StrUtil.nullToEmpty(category));
        doc.put("doc_title", StrUtil.nullToEmpty(docTitle));
        doc.put("content", StrUtil.nullToEmpty(contentText));
        doc.put("word_count", String.valueOf(wordCount));
        doc.put("updated_at", String.valueOf(updatedAt));
        try {
            redisTemplate.opsForHash().putAll(KEY_PREFIX + docId, doc);
        } catch (Exception e) {
            log.warn("[doc.index] 写入失败 docId={} {}", docId, e.getMessage());
        }
    }

    /** 删除文档索引 */
    public void delete(String docId) {
        try {
            redisTemplate.delete(KEY_PREFIX + docId);
        } catch (Exception e) {
            log.warn("[doc.index] 删除失败 docId={} {}", docId, e.getMessage());
        }
    }

    /** 全文检索：@user_id 必带，标题与正文任一命中，按更新时间倒序 */
    public List<DocVO> search(String userId, String category, String keyword, long offset, long size) {
        StringBuilder query = new StringBuilder();
        query.append(" @user_id:{").append(escapeTag(userId)).append('}');
        if (StrUtil.isNotBlank(category)) {
            query.append(" @category:{").append(escapeTag(category)).append('}');
        }
        if (StrUtil.isNotBlank(keyword)) {
            query.append(" (@doc_title:(").append(escape(keyword)).append(')')
                    .append(" | @content:(").append(escape(keyword)).append("))");
        }
        try {
            Object reply = redisTemplate.execute((RedisConnection connection) ->
                    connection.execute("FT.SEARCH", args(INDEX, query.toString(),
                            "SORTBY", "updated_at", "DESC", "LIMIT",
                            String.valueOf(Math.max(offset, 0)), String.valueOf(Math.max(size, 1)))));
            return parseReply(reply);
        } catch (Exception e) {
            log.warn("[doc.search] 检索失败 {}", e.getMessage());
            return List.of();
        }
    }

    /** 检索总数，分页计数用 */
    public long count(String userId, String category, String keyword) {
        StringBuilder query = new StringBuilder();
        query.append(" @user_id:{").append(escapeTag(userId)).append('}');
        if (StrUtil.isNotBlank(category)) {
            query.append(" @category:{").append(escapeTag(category)).append('}');
        }
        if (StrUtil.isNotBlank(keyword)) {
            query.append(" (@doc_title:(").append(escape(keyword)).append(')')
                    .append(" | @content:(").append(escape(keyword)).append("))");
        }
        try {
            Object reply = redisTemplate.execute((RedisConnection connection) ->
                    connection.execute("FT.SEARCH", args(INDEX, query.toString(), "NOCONTENT")));
            if (reply instanceof List<?> rows && !rows.isEmpty()
                    && rows.get(0) instanceof Number number) {
                return number.longValue();
            }
            return 0L;
        } catch (Exception e) {
            log.warn("[doc.count] 计数失败 {}", e.getMessage());
            return 0L;
        }
    }

    /** 解析 FT.SEARCH 回复：[total, key, [field, value, ...], ...] */
    private List<DocVO> parseReply(Object reply) {
        if (!(reply instanceof List<?> rows) || rows.size() < 3) {
            return List.of();
        }
        List<DocVO> result = new ArrayList<>();
        for (int i = 1; i + 1 < rows.size(); i += 2) {
            if (!(rows.get(i + 1) instanceof List<?> fields)) {
                continue;
            }
            Map<String, String> doc = new LinkedHashMap<>();
            for (int f = 0; f + 1 < fields.size(); f += 2) {
                doc.put(text(fields.get(f)), text(fields.get(f + 1)));
            }
            DocVO vo = new DocVO();
            vo.setDocId(doc.get("doc_id"));
            vo.setUserId(doc.get("user_id"));
            vo.setDocTitle(doc.get("doc_title"));
            vo.setCategory(doc.get("category"));
            vo.setWordCount(NumberUtil.parseInt(doc.get("word_count"), 0));
            vo.setUpdatedAt(NumberUtil.parseLong(doc.get("updated_at"), 0L));
            vo.setSummary(StrUtil.sub(doc.get("content"), 0, 120));
            result.add(vo);
        }
        return result;
    }

    /** 查询串转义：RediSearch 语法特殊字符全部加反斜杠 */
    private String escape(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length() + 8);
        for (char ch : StrUtil.blankToDefault(raw, "").toCharArray()) {
            if (SPECIAL_CHARS.indexOf(ch) >= 0) {
                escaped.append('\\');
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }

    /** TAG 值转义：TAG 集合语法内特殊字符加反斜杠 */
    private String escapeTag(String raw) {
        return StrUtil.blankToDefault(raw, "").replace("-", "\\-").replace(".", "\\.");
    }

    /** 回复字段统一转文本，字节数组按 UTF-8 解码 */
    private String text(Object value) {
        if (value instanceof byte[] raw) {
            return StrUtil.str(raw, StandardCharsets.UTF_8);
        }
        return Objects.isNull(value) ? StrUtil.EMPTY : value.toString();
    }

    /** Redisearch 原始命令只收字节数组参数，字符串参数统一转码 */
    private byte[][] args(String... values) {
        return Arrays.stream(values)
                .map(value -> StrUtil.bytes(value, StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
    }
}
