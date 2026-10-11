package com.liteworkbench.observability;

import cn.hutool.json.JSONUtil;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.domain.constant.IdPrefixConst;
import org.slf4j.MDC;

import java.util.Objects;

/**
 * Redisearch 日志 Appender：组装文档后交给 LogPublisher（经 MQ 异步入索引）。
 * 投递失败静默丢弃并限流告警，绝不阻塞业务线程。
 */
public class RedisearchLogAppender extends AppenderBase<ILoggingEvent> {

    /** 由 Spring 配置在启动时注入 */
    private static volatile LogPublisher publisher;
    /** 连续失败计数，用于限流告警 */
    private static volatile long failureCount;
    private static volatile long lastWarnAt;

    /** 单条消息入索引前截断长度 */
    private static final int MAX_MESSAGE_CHARS = 4000;
    /** 告警限流间隔，毫秒 */
    private static final long WARN_INTERVAL_MS = 60_000L;

    public static void setPublisher(LogPublisher target) {
        publisher = target;
    }

    public RedisearchLogAppender() {
        setName("LW_REDISEARCH");
    }

    @Override
    protected void append(ILoggingEvent event) {
        LogPublisher target = publisher;
        if (Objects.isNull(target)) {
            return;
        }
        try {
            String message = event.getFormattedMessage();
            if (message.length() > MAX_MESSAGE_CHARS) {
                message = message.substring(0, MAX_MESSAGE_CHARS) + "...";
            }
            String exception = renderException(event.getThrowableProxy());
            target.publish(new LogPublisher.LogMessage(
                    IdGenerator.generate(IdPrefixConst.LOG),
                    event.getTimeStamp(),
                    event.getLevel().toString(),
                    abbreviate(event.getLoggerName()),
                    event.getThreadName(),
                    Objects.toString(MDC.get("traceId"), ""),
                    message,
                    exception));
            failureCount = 0;
        } catch (Exception e) {
            failureCount++;
            long now = System.currentTimeMillis();
            if (now - lastWarnAt > WARN_INTERVAL_MS) {
                lastWarnAt = now;
                addWarn("日志投递失败 " + failureCount + " 次：" + e.getMessage());
            }
        }
    }

    /** 异常堆栈渲染，超长截断 */
    private String renderException(IThrowableProxy proxy) {
        if (Objects.isNull(proxy)) {
            return null;
        }
        String stack = ThrowableProxyUtil.asString(proxy);
        return stack.length() > MAX_MESSAGE_CHARS ? stack.substring(0, MAX_MESSAGE_CHARS) : stack;
    }

    private String abbreviate(String loggerName) {
        return Objects.toString(loggerName, "").length() > 128
                ? loggerName.substring(loggerName.length() - 128) : Objects.toString(loggerName, "");
    }
}
