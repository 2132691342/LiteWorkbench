package com.liteworkbench.mapper;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.vo.KbChunkVO;
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
 * 知识库分块 Redisearch 索引访问，分块是用户资产不设 TTL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbChunkIndexMapper {

    private static final String INDEX = "idx:lw:kbchunk";
    private static final String KEY_PREFIX = "lw:kbchunk:";
    /** FT.SEARCH 查询语法特殊字符 */
    private static final String SPECIAL_CHARS = ",.<>{}[]\"':;!@#$%^&*()-+=~| ";
    /** 单批扫描的文档数上限，配合循环删除 */
    private static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redisTemplate;

    /** 幂等创建索引，已存在则跳过；Redis 不可用时返回 false，不阻断启动 */
    public boolean ensureIndex() {
        try {
            return Boolean.TRUE.equals(redisTemplate.execute((RedisConnection connection) -> {
                try {
                    if (Objects.nonNull(connection.execute("FT.INFO", args(INDEX)))) {
                        log.info("[kbchunk.index] 索引已存在 {}", INDEX);
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
                            "chunk_id", "TAG",
                            "user_id", "TAG",
                            "kb_id", "TAG",
                            "doc_id", "TAG",
                            "doc_name", "TEXT",
                            "seq", "NUMERIC",
                            "content", "TEXT",
                            "ts", "NUMERIC", "SORTABLE"));
                    log.info("[kbchunk.index] 索引创建成功 {}", INDEX);
                    return true;
                } catch (Exception e) {
                    log.warn("[kbchunk.index] 索引创建失败 {} {}", INDEX, e.getMessage());
                    return false;
                }
            }));
        } catch (Exception e) {
            log.warn("[kbchunk.index] Redis 不可用，索引跳过 {} {}", INDEX, e.getMessage());
            return false;
        }
    }

    /** 批量写入分块文档，不设 TTL */
    public void addBatch(List<KbChunkVO> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        for (KbChunkVO chunk : chunks) {
            Map<String, String> doc = new LinkedHashMap<>();
            doc.put("chunk_id", StrUtil.nullToEmpty(chunk.getChunkId()));
            doc.put("user_id", StrUtil.nullToEmpty(chunk.getUserId()));
            doc.put("kb_id", StrUtil.nullToEmpty(chunk.getKbId()));
            doc.put("doc_id", StrUtil.nullToEmpty(chunk.getDocId()));
            doc.put("doc_name", StrUtil.nullToEmpty(chunk.getDocName()));
            doc.put("seq", String.valueOf(Objects.requireNonNullElse(chunk.getSeq(), 0)));
            doc.put("content", StrUtil.nullToEmpty(chunk.getContent()));
            doc.put("ts", String.valueOf(Objects.requireNonNullElse(chunk.getCreatedAt(), 0L)));
            redisTemplate.opsForHash().putAll(KEY_PREFIX + chunk.getChunkId(), doc);
        }
    }

    /** 分块检索：@user_id 必带、kb_id 可选，关键字同时匹配正文与文档名，按相关度排序 */
    public List<KbChunkVO> search(String userId, String kbId, String keyword, long offset, long size) {
        StringBuilder query = new StringBuilder();
        query.append(" @user_id:{").append(escapeTag(userId)).append('}');
        if (StrUtil.isNotBlank(kbId)) {
            query.append(" @kb_id:{").append(escapeTag(kbId)).append('}');
        }
        query.append(" (@content:(").append(escape(keyword)).append(')')
                .append(" | @doc_name:(").append(escape(keyword)).append(")))");
        try {
            Object reply = redisTemplate.execute((RedisConnection connection) ->
                    connection.execute("FT.SEARCH", args(INDEX, query.toString(),
                            "WITHSCORES", "LIMIT",
                            String.valueOf(Math.max(offset, 0)), String.valueOf(Math.max(size, 1)))));
            return parseReply(reply);
        } catch (Exception e) {
            log.warn("[kbchunk.search] 检索失败 {}", e.getMessage());
            return List.of();
        }
    }

    /** 检索总数，分页计数用 */
    public long count(String userId, String kbId, String keyword) {
        StringBuilder query = new StringBuilder();
        query.append(" @user_id:{").append(escapeTag(userId)).append('}');
        if (StrUtil.isNotBlank(kbId)) {
            query.append(" @kb_id:{").append(escapeTag(kbId)).append('}');
        }
        query.append(" (@content:(").append(escape(keyword)).append(')')
                .append(" | @doc_name:(").append(escape(keyword)).append(")))");
        try {
            Object reply = redisTemplate.execute((RedisConnection connection) ->
                    connection.execute("FT.SEARCH", args(INDEX, query.toString(), "NOCONTENT")));
            if (reply instanceof List<?> rows && !rows.isEmpty()
                    && rows.get(0) instanceof Number number) {
                return number.longValue();
            }
            return 0L;
        } catch (Exception e) {
            log.warn("[kbchunk.count] 计数失败 {}", e.getMessage());
            return 0L;
        }
    }

    /** 删除文档的全部分块，返回删除数 */
    public long deleteByDoc(String docId) {
        return deleteByQuery(StrUtil.format("@doc_id:{{{}}}", escapeTag(docId)));
    }

    /** 删除知识库的全部分块，返回删除数 */
    public long deleteByKb(String kbId) {
        return deleteByQuery(StrUtil.format("@kb_id:{{{}}}", escapeTag(kbId)));
    }

    /** 按查询串循环删文档，直到无剩余 */
    private long deleteByQuery(String query) {
        long removed = 0;
        try {
            while (true) {
                Object reply = redisTemplate.execute((RedisConnection connection) ->
                        connection.execute("FT.SEARCH", args(INDEX, query,
                                "NOCONTENT", "LIMIT", "0", String.valueOf(SCAN_BATCH))));
                List<String> keys = new ArrayList<>();
                if (reply instanceof List<?> rows && rows.size() > 1) {
                    for (int i = 1; i < rows.size(); i++) {
                        keys.add(text(rows.get(i)));
                    }
                }
                if (keys.isEmpty()) {
                    break;
                }
                redisTemplate.delete(keys);
                removed += keys.size();
                if (keys.size() < SCAN_BATCH) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[kbchunk.delete] 分块清理失败 {} {}", query, e.getMessage());
        }
        return removed;
    }

    /** 解析 FT.SEARCH WITHSCORES 回复：[total, key, score, [field, value, ...], ...] */
    private List<KbChunkVO> parseReply(Object reply) {
        if (!(reply instanceof List<?> rows) || rows.size() < 4) {
            return List.of();
        }
        List<KbChunkVO> result = new ArrayList<>();
        for (int i = 1; i + 1 < rows.size(); i += 3) {
            if (!(rows.get(i + 1) instanceof List<?> fields)) {
                continue;
            }
            Map<String, String> doc = new LinkedHashMap<>();
            for (int f = 0; f + 1 < fields.size(); f += 2) {
                doc.put(text(fields.get(f)), text(fields.get(f + 1)));
            }
            KbChunkVO vo = new KbChunkVO();
            vo.setChunkId(doc.get("chunk_id"));
            vo.setKbId(doc.get("kb_id"));
            vo.setDocId(doc.get("doc_id"));
            vo.setDocName(doc.get("doc_name"));
            vo.setSeq(NumberUtil.parseInt(doc.get("seq"), 0));
            vo.setContent(doc.get("content"));
            vo.setScore(rows.get(i) instanceof Number score ? score.doubleValue() : 0D);
            vo.setCreatedAt(NumberUtil.parseLong(doc.get("ts"), 0L));
            result.add(vo);
        }
        return result;
    }

    /** 查询串转义：RediSearch 语法特殊字符全部加反斜杠，防止语法错误与查询注入 */
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
