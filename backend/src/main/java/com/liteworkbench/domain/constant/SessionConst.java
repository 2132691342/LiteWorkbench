package com.liteworkbench.domain.constant;

/** 会话相关静态常量 */
public interface SessionConst {

    /** 标题最大长度 */
    int TITLE_MAX_LENGTH = 128;
    /** 新建会话默认标题 */
    String DEFAULT_TITLE = "新会话";
    /** 会话运行锁过期时间，秒；不得小于 SSE 连接存活上限，否则长任务期间同会话可并发起新运行 */
    long LOCK_TTL_SECONDS = RunConst.SSE_TIMEOUT_MS / 1000L + 60L;
    /** 系统提示词最大长度 */
    int SYSTEM_PROMPT_MAX_LENGTH = 8000;
}
