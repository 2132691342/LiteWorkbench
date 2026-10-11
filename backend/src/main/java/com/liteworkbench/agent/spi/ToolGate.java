package com.liteworkbench.agent.spi;

/** 工具启停查询端口：agent 只认此接口，由 service 侧适配实现 */
public interface ToolGate {

    /** 工具是否启用，未注册的工具名视为禁用 */
    boolean isEnabled(String toolName);
}
