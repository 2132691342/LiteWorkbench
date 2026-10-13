package com.liteworkbench.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.domain.constant.AppConst;
import com.liteworkbench.domain.constant.IdPrefixConst;
import org.slf4j.MDC;

import java.util.Objects;

/** Manticore 日志 Appender：组装文档后交 LogPublisher 异步入索引，失败不阻塞业务线程 */
public class ManticoreLogAppender extends AppenderBase<ILoggingEvent> {

    /** 由 Spring 配置在启动时注入 */
    private static volatile LogPublisher publisher;
    /** 连续失败计数，用于限流告警 */
    private static volatile long failureCount;
    private static volatile long lastWarnAt;

    /** 单条消息入索引前截断长度 */
    private static final int MAX_MESSAGE_CHARS = 4000;
    /** logger 名超长时只保留尾部 */
    private static final int MAX_LOGGER_CHARS = 128;
    /** 告警限流间隔，毫秒 */
    private static final long WARN_INTERVAL_MS = 60_000L;

    public static void setPublisher(LogPublisher target) {
        publisher = target;
    }

    public ManticoreLogAppender() {
        setName("LW_MANTICORE");
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
            String loggerName = Objects.toString(event.getLoggerName(), "");
            String exception = renderException(event.getThrowableProxy());
            target.publish(new LogPublisher.LogMessage(
                    IdGenerator.generate(IdPrefixConst.LOG),
                    event.getTimeStamp(),
                    event.getLevel().toString(),
                    loggerName.length() > MAX_LOGGER_CHARS
                            ? loggerName.substring(loggerName.length() - MAX_LOGGER_CHARS) : loggerName,
                    event.getThreadName(),
                    Objects.toString(MDC.get(AppConst.MDC_TRACE_ID), ""),
                    Objects.toString(MDC.get(AppConst.MDC_USER_ID), ""),
                    Objects.toString(MDC.get(AppConst.MDC_SESSION_ID), ""),
                    Objects.toString(MDC.get(AppConst.MDC_RUN_ID), ""),
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

}
