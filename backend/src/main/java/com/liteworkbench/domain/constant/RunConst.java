package com.liteworkbench.domain.constant;

/** 运行相关静态常量 */
public interface RunConst {

    /** 事件落库节拍，毫秒；DB 写入不用跟手，按批压到 5 次/秒 */
    long EVENT_PERSIST_INTERVAL_MS = 200L;
    /** 增量推送节拍，毫秒；≈一帧的时间，决定「打字」跟手程度 */
    long SSE_PUSH_TICK_MS = 16L;
    /** 水位快照节流，毫秒；客户端据此判定丢帧并补齐 */
    long SSE_SNAPSHOT_THROTTLE_MS = 200L;
    /** 单次运行输入最大长度 */
    int PROMPT_MAX_LENGTH = 32000;
    /** SSE 连接超时，毫秒 */
    long SSE_TIMEOUT_MS = 30L * 60L * 1000L;
    /** SSE 心跳间隔，毫秒 */
    long SSE_HEARTBEAT_MS = 15000L;
    /** 事件起始序号 */
    long EVENT_SEQ_START = 1L;
    /** 子运行派生关系标识，Run 树按它区分父子 */
    String RELATION_CHILD = "child";
}
