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
    /** 记录器名 */
    private String logger;
    /** 线程名 */
    private String thread;
    /** 链路标识 */
    private String traceId;
    /** 正文 */
    private String message;
    /** 异常堆栈 */
    private String exception;
}
