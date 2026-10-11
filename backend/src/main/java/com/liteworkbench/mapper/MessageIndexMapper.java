package com.liteworkbench.mapper;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.dto.MessageDocDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话消息 Redisearch 索引访问，消息是用户资产不设 TTL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageIndexMapper {

    private static final String INDEX = "idx:lw:msg";
    private static final String KEY_PREFIX = "lw:msg:";
    /** 单会话读取消息的批量上限，配合循环删除 */
    private static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redisTemplate;

    /** 幂等创建索引，已存在则跳过；Redis 不可用时返回 false，不阻断启动 */
    public boolean ensureIndex() {
        try {
            return Boolean.TRUE.equals(redisTemplate.execute((RedisConnection connection) -> {
                try {
                    if (Objects.nonNull(connection.execute("FT.INFO", args(INDEX)))) {
                        log.info("[msg.index] 索引已存在 {}", INDEX);
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
                            "message_id", "TAG",
                            "user_id", "TAG",
                            "session_id", "TAG",
                            "run_id", "TAG",
                            "role", "TAG",
                            "content", "TEXT",
                            "token_count", "NUMERIC",
                            "ts", "NUMERIC", "SORTABLE"));
                    log.info("[msg.index] 索引创建成功 {}", INDEX);
                    return true;
                } catch (Exception e) {
                    log.warn("[msg.index] 索引创建失败 {} {}", INDEX, e.getMessage());
                    return false;
                }
            }));
        } catch (Exception e) {
            log.warn("[msg.index] Redis 不可用，索引跳过 {} {}", INDEX, e.getMessage());
            return false;
        }
    }

    /** 写入消息文档，不设 TTL */
    public void add(MessageDocDTO message) {
        Map<String, String> doc = new LinkedHashMap<>();
        doc.put("message_id", message.messageId());
        doc.put("user_id", StrUtil.nullToEmpty(message.userId()));
        doc.put("session_id", StrUtil.nullToEmpty(message.sessionId()));
        doc.put("run_id", StrUtil.nullToEmpty(message.runId()));
        doc.put("role", StrUtil.nullToEmpty(message.role()));
        doc.put("content", StrUtil.nullToEmpty(message.content()));
        doc.put("token_count", String.valueOf(Objects.requireNonNullElse(message.tokenCount(), 0)));
        doc.put("ts", String.valueOf(Objects.requireNonNullElse(message.ts(), 0L)));
        redisTemplate.opsForHash().putAll(KEY_PREFIX + message.messageId(), doc);
    }

    /** 会话消息，按 (ts, messageId) 稳定升序；excludeRunId 非空时排除该运行的消息 */
    public List<MessageDocDTO> listBySession(String sessionId, int limit, String excludeRunId) {
        String query = StrUtil.format("@session_id:{{{}}}", escapeTag(sessionId));
        List<MessageDocDTO> all = new ArrayList<>();
        try {
            Object reply = redisTemplate.execute((RedisConnection connection) ->
                    connection.execute("FT.SEARCH", args(INDEX, query,
                            "SORTBY", "ts", "ASC",
                            "LIMIT", "0", String.valueOf(SCAN_BATCH))));
            all.addAll(parseReply(reply));
        } catch (Exception e) {
            log.warn("[msg.search] 会话消息读取失败 session={} {}", sessionId, e.getMessage());
            return List.of();
        }
        return all.stream()
                .filter(message -> StrUtil.isBlank(excludeRunId)
                        || !excludeRunId.equals(message.runId()))
                .sorted(Comparator.comparingLong(MessageDocDTO::ts)
                        .thenComparing(MessageDocDTO::messageId))
                .limit(limit)
                .toList();
    }

    /** 删除会话全部消息文档，返回删除数；按批扫描直到无剩余 */
    public long deleteBySession(String sessionId) {
        String query = StrUtil.format("@session_id:{{{}}}", escapeTag(sessionId));
        long removed = 0;
        try {
            while (true) {
                List<String> keys = searchKeys(query, SCAN_BATCH);
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
            log.warn("[msg.delete] 会话消息清理失败 session={} {}", sessionId, e.getMessage());
        }
        return removed;
    }

    /** 只取文档键的工具查询 */
    private List<String> searchKeys(String query, int limit) {
        Object reply = redisTemplate.execute((RedisConnection connection) ->
                connection.execute("FT.SEARCH", args(INDEX, query,
                        "NOCONTENT", "LIMIT", "0", String.valueOf(limit))));
        if (!(reply instanceof List<?> rows) || rows.size() < 2) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            keys.add(text(rows.get(i)));
        }
        return keys;
    }

    /** 解析 FT.SEARCH 回复：[total, key, [field, value, ...], ...] */
    private List<MessageDocDTO> parseReply(Object reply) {
        if (!(reply instanceof List<?> rows) || rows.isEmpty()) {
            return List.of();
        }
        List<MessageDocDTO> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof List<?> row) || row.size() < 2
                    || !(row.get(1) instanceof List<?> fields)) {
                continue;
            }
            Map<String, String> doc = new LinkedHashMap<>();
            for (int f = 0; f + 1 < fields.size(); f += 2) {
                doc.put(text(fields.get(f)), text(fields.get(f + 1)));
            }
            result.add(new MessageDocDTO(
                    doc.get("message_id"),
                    doc.get("user_id"),
                    doc.get("session_id"),
                    doc.get("run_id"),
                    doc.get("role"),
                    doc.get("content"),
                    NumberUtil.parseInt(doc.get("token_count"), 0),
                    NumberUtil.parseLong(doc.get("ts"), 0L)));
        }
        return result;
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
