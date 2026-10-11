package com.liteworkbench.mapper;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.vo.LogDocVO;
import com.liteworkbench.observability.LogPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redisearch 日志索引访问：idx:lw:log，哈希文档键前缀 lw:log:。
 * 原始命令经 RedisConnection.execute 直发，索引创建幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogSearchMapper {

    private static final String INDEX = "idx:lw:log";
    private static final String KEY_PREFIX = "lw:log:";
    /** 文档保留 30 天，靠键 TTL 过期 + Redisearch GC 清理索引 */
    private static final Duration DOC_TTL = Duration.ofDays(30L);

    private final StringRedisTemplate redisTemplate;

    /** 幂等创建索引，已存在则跳过 */
    public boolean ensureIndex() {
        return Boolean.TRUE.equals(redisTemplate.execute((RedisConnection connection) -> {
            try {
                if (Objects.nonNull(connection.execute("FT.INFO", args(INDEX)))) {
                    log.info("[log.index] 索引已存在 {}", INDEX);
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
                        "log_id", "TAG",
                        "ts", "NUMERIC", "SORTABLE",
                        "level", "TAG",
                        "logger", "TAG",
                        "thread", "TAG",
                        "trace_id", "TAG",
                        "message", "TEXT",
                        "exception", "TEXT"));
                log.info("[log.index] 索引创建成功 {}", INDEX);
                return true;
            } catch (Exception e) {
                log.warn("[log.index] 索引创建失败 {} {}", INDEX, e.getMessage());
                return false;
            }
        }));
    }

    /** 写入日志文档，带 30 天 TTL */
    public void add(LogPublisher.LogMessage message) {
        Map<String, String> doc = new HashMap<>();
        doc.put("log_id", message.logId());
        doc.put("ts", String.valueOf(message.ts()));
        doc.put("level", message.level());
        doc.put("logger", StrUtil.nullToEmpty(message.logger()));
        doc.put("thread", StrUtil.nullToEmpty(message.thread()));
        doc.put("trace_id", StrUtil.nullToEmpty(message.traceId()));
        doc.put("message", StrUtil.nullToEmpty(message.message()));
        if (StrUtil.isNotBlank(message.exception())) {
            doc.put("exception", message.exception());
        }
        String key = KEY_PREFIX + message.logId();
        redisTemplate.opsForHash().putAll(key, doc);
        redisTemplate.expire(key, DOC_TTL);
    }

    /** 检索日志，按 ts 倒序分页 */
    public List<LogDocVO> search(String keyword, String level,
                                 long from, long to, long page, long size) {
        StringBuilder query = new StringBuilder();
        if (StrUtil.isNotBlank(level)) {
            query.append(" @level:{").append(level.toUpperCase()).append('}');
        }
        if (from > 0 && to > 0) {
            query.append(" @ts:[").append(from).append(' ').append(to).append(']');
        }
        if (StrUtil.isNotBlank(keyword)) {
            query.append(" @message:(").append(StrUtil.removeAll(keyword, '"')).append(')');
        }
        long offset = (page - 1) * size;
        Object reply = redisTemplate.execute((RedisConnection connection) ->
                connection.execute("FT.SEARCH", args(INDEX, query.toString(),
                        "SORTBY", "ts", "DESC", "LIMIT",
                        String.valueOf(offset), String.valueOf(size))));
        return parseReply(reply);
    }

    /** 解析 FT.SEARCH 回复：[total, key, [field, value, ...], ...] */
    private List<LogDocVO> parseReply(Object reply) {
        List<LogDocVO> result = new ArrayList<>();
        if (!(reply instanceof List<?> rows) || rows.isEmpty()) {
            return result;
        }
        for (int i = 1; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof List<?> row) || row.size() < 2
                    || !(row.get(1) instanceof List<?> fields)) {
                continue;
            }
            Map<String, String> doc = new LinkedHashMap<>();
            for (int f = 0; f + 1 < fields.size(); f += 2) {
                doc.put(text(fields.get(f)), text(fields.get(f + 1)));
            }
            LogDocVO vo = new LogDocVO();
            vo.setLogId(doc.get("log_id"));
            vo.setTs(NumberUtil.parseLong(doc.get("ts"), System.currentTimeMillis()));
            vo.setLevel(doc.get("level"));
            vo.setLogger(doc.get("logger"));
            vo.setThread(doc.get("thread"));
            vo.setTraceId(doc.get("trace_id"));
            vo.setMessage(doc.get("message"));
            vo.setException(doc.get("exception"));
            result.add(vo);
        }
        return result;
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
