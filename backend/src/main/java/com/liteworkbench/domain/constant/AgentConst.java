package com.liteworkbench.domain.constant;

/** Agent 装配静态常量：同步调用边界、变量占位符 */
public interface AgentConst {

    /** 同步模型调用超时（秒），辅助写作与工作流 llm 节点共用 */
    int SYNC_CALL_TIMEOUT_SECONDS = 120;

    /** 变量占位符左边界 */
    String VAR_PREFIX = "{{";

    /** 变量占位符右边界 */
    String VAR_SUFFIX = "}}";
}
