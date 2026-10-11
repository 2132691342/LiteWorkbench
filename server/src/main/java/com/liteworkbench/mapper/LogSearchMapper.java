package com.liteworkbench.mapper;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.domain.vo.LogDocVO;
import com.liteworkbench.observability.LogPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
public class LogSearchMapper {

    private static final String INDEX = "idx:lw:log";
    private static final String KEY_PREFIX = "lw:log:";
    /** 文档保留 30 天，靠键 TTL 过期 + Redisearch GC 清理索引 */
    private static final Duration DOC_TTL = Duration.ofDays(30L);

    private final StringRedisTemplate redisTemplate;

    public LogSearchMapper(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 幂等创建索引，已存在则跳过 */
    public boolean ensureIndex() {
        return Boolean.TRUE.equals(redisTemplate.execute((RedisConnection connection) -> {
            try {
                Object info = connection.execute("FT.INFO", bytes(INDEX));
                if (Objects.nonNull(info)) {
                    log.info("[log.index] 索引已存在 {}", INDEX);
                    return true;
                }
            } catch (Exception ignored) {
                // 索引不存在，走创建
            }
            try {
                connection.execute("FT.CREATE", bytes(INDEX),
                        bytes("ON"), bytes("HASH"),
                        bytes("PREFIX"), bytes("1"), bytes(KEY_PREFIX),
                        bytes("SCHEMA"),
                        bytes("log_id"), bytes("TAG"),
                        bytes("ts"), bytes("NUMERIC"), bytes("SORTABLE"),
                        bytes("level"), bytes("TAG"),
                        bytes("logger"), bytes("TAG"),
                        bytes("thread"), bytes("TAG"),
                        bytes("trace_id"), bytes("TAG"),
                        bytes("message"), bytes("TEXT"),
                        bytes("exception"), bytes("TEXT"));
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
        doc.put("logger", Objects.toString(message.logger(), ""));
        doc.put("thread", Objects.toString(message.thread(), ""));
        doc.put("trace_id", Objects.toString(message.traceId(), ""));
        doc.put("message", Objects.toString(message.message(), ""));
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
            query.append(" @level:{").append(level.toUpperCase()).append("}");
        }
        if (from > 0 && to > 0) {
            query.append(" @ts:[").append(from).append(' ').append(to).append(']');
        }
        if (StrUtil.isNotBlank(keyword)) {
            query.append(" @message:(").append(keyword.replace("\"", "")).append(")");
        }
        long offset = (page - 1) * size;
        List<Object> args = new ArrayList<>();
        args.add(bytes(INDEX));
        args.add(bytes(query.toString()));
        args.add(bytes("SORTBY"));
        args.add(bytes("ts"));
        args.add(bytes("DESC"));
        args.add(bytes("LIMIT"));
        args.add(bytes(String.valueOf(offset)));
        args.add(bytes(String.valueOf(size)));

        Object reply = redisTemplate.execute((RedisConnection connection) ->
                connection.execute("FT.SEARCH", args.toArray(new byte[0][])));
        return parseReply(reply);
    }

    /** 解析 FT.SEARCH 回复：[total, key, [field, value, ...], ...] */
    private List<LogDocVO> parseReply(Object reply) {
        List<LogDocVO> result = new ArrayList<>();
        if (!(reply instanceof List<?> rows) || rows.size() < 1) {
            return result;
        }
        for (int i = 1; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof List<?> row) || row.size() < 2) {
                continue;
            }
            if (!(row.get(1) instanceof List<?> fields)) {
                continue;
            }
            Map<String, String> doc = new LinkedHashMap<>();
            for (int f = 0; f + 1 < fields.size(); f += 2) {
                doc.put(text(fields.get(f)), text(fields.get(f + 1)));
            }
            LogDocVO vo = new LogDocVO();
            vo.setLogId(doc.get("log_id"));
            vo.setTs(parseLong(doc.get("ts")));
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

    private String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return Objects.toString(value, "");
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
