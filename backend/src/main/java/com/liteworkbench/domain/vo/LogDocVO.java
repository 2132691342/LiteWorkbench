package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 日志文档视图，对应 Manticore lw_log 命中结果 */
@Data
public class LogDocVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 日志 ID，LOGD_ 前缀 */
    private String logId;
    /** 发生时间，毫秒时间戳 */
    private Long ts;
    /** 级别 INFO / WARN / ERROR */
    private String level;
    private String logger;
    private String thread;
    /** 链路标识 */
    private String traceId;
    /** 用户 ID，匿名请求为空 */
    private String userId;
    /** 会话 ID，非运行日志为空 */
    private String sessionId;
    /** 运行 ID，非运行日志为空 */
    private String runId;
    /** 正文 */
    private String message;
    /** 异常堆栈 */
    private String exception;
}
