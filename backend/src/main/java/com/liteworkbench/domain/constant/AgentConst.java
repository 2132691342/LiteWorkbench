package com.liteworkbench.domain.constant;

/** Agent 装配静态常量：同步调用边界、变量占位符 */
public interface AgentConst {

    /** 同步模型调用超时（秒），创作台 AI 辅助与连通性测试共用 */
    int SYNC_CALL_TIMEOUT_SECONDS = 120;

    /** 变量占位符左边界 */
    String VAR_PREFIX = "{{";

    /** 变量占位符右边界 */
    String VAR_SUFFIX = "}}";
}
