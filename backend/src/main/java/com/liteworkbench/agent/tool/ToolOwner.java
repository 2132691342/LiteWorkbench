package com.liteworkbench.agent.tool;

/** 工具执行归属：一次运行的身份三元组；页面手工调试时 runId / sessionId 为空，依赖运行的工具自行拒绝 */
public record ToolOwner(String userId, String runId, String sessionId) {

    /** 启动期干跑校验用：不关联任何真实用户与运行 */
    public static ToolOwner startupCheck() {
        return new ToolOwner("startup-check", null, null);
    }
}
