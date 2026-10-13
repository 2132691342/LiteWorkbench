package com.liteworkbench.observability;

/** 日志投递出口，Logback Appender 经此把日志送往下游 */
public interface LogPublisher {

    /** 投递一条结构化日志，实现方必须自行容错不得抛出 */
    void publish(LogMessage message);

    /** 结构化日志消息：链路标识四件套用于检索隔离与串联，允许为空串（匿名请求与系统线程） */
    record LogMessage(String logId, long ts, String level, String logger, String thread,
                      String traceId, String userId, String sessionId, String runId,
                      String message, String exception) {
    }
}
