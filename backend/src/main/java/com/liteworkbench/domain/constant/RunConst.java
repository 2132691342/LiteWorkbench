package com.liteworkbench.domain.constant;

/** 运行相关静态常量 */
public interface RunConst {

    /** 事件攒批时间阈值，毫秒 */
    long EVENT_BATCH_INTERVAL_MS = 200L;
    /** 单次运行输入最大长度 */
    int PROMPT_MAX_LENGTH = 32000;
    /** SSE 连接超时，毫秒 */
    long SSE_TIMEOUT_MS = 30L * 60L * 1000L;
    /** SSE 心跳间隔，毫秒 */
    long SSE_HEARTBEAT_MS = 15000L;
    /** 事件起始序号 */
    long EVENT_SEQ_START = 1L;
}
