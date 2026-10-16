package com.liteworkbench.domain.constant;

/** 权限标识常量，格式 域:资源:动作，一经发布不得改名 */
public interface PermConst {

    // 用户
    String SYSTEM_USER_LIST = "system:user:list";
    String SYSTEM_USER_CREATE = "system:user:create";
    String SYSTEM_USER_UPDATE = "system:user:update";
    String SYSTEM_USER_DELETE = "system:user:delete";
    String SYSTEM_USER_RESET = "system:user:reset";

    // 角色
    String SYSTEM_ROLE_LIST = "system:role:list";
    String SYSTEM_ROLE_CREATE = "system:role:create";
    String SYSTEM_ROLE_UPDATE = "system:role:update";
    String SYSTEM_ROLE_DELETE = "system:role:delete";
    String SYSTEM_ROLE_GRANT = "system:role:grant";

    // 菜单
    String SYSTEM_MENU_LIST = "system:menu:list";
    String SYSTEM_MENU_CREATE = "system:menu:create";
    String SYSTEM_MENU_UPDATE = "system:menu:update";
    String SYSTEM_MENU_DELETE = "system:menu:delete";

    // 监控与日志
    String MONITOR_HEALTH_LIST = "monitor:health:list";
    String MONITOR_LOG_LIST = "monitor:log:list";
    /** 日志全局视角：命中即可检索全体用户日志并用 userId / runId 反查 */
    String MONITOR_LOG_GLOBAL = "monitor:log:global";
    String MONITOR_PYTHON_REPROBE = "monitor:python:reprobe";
    /** 调用观测窗口重置：清空 SQL 与 HTTP 的累计指标，重新开始统计 */
    String MONITOR_OBSERVE_RESET = "monitor:observe:reset";

    // 工作台 · 工具
    String WB_TOOL_LIST = "wb:tool:list";
    String WB_TOOL_TOGGLE = "wb:tool:toggle";
    String WB_TOOL_INVOKE = "wb:tool:invoke";

    // 工作台 · 技能
    String WB_SKILL_LIST = "wb:skill:list";
    String WB_SKILL_CREATE = "wb:skill:create";
    String WB_SKILL_UPDATE = "wb:skill:update";
    String WB_SKILL_DELETE = "wb:skill:delete";

    // 工作台 · 文件（其余动作登录即可用）
    String WB_FILE_DELETE = "wb:file:delete";

    // 工作台 · 知识库
    String WB_KB_LIST = "wb:kb:list";
    String WB_KB_CREATE = "wb:kb:create";
    String WB_KB_UPDATE = "wb:kb:update";
    String WB_KB_DELETE = "wb:kb:delete";
    String WB_KB_UPLOAD = "wb:kb:upload";
    String WB_KB_SEARCH = "wb:kb:search";

    // 工作台 · 创作文档
    String WB_DOC_LIST = "wb:doc:list";
    String WB_DOC_CREATE = "wb:doc:create";
    String WB_DOC_UPDATE = "wb:doc:update";
    String WB_DOC_DELETE = "wb:doc:delete";
    String WB_DOC_SEARCH = "wb:doc:search";

    // 工作台 · 模型供应方
    String WB_PROVIDER_LIST = "wb:provider:list";
    String WB_PROVIDER_CREATE = "wb:provider:create";
    String WB_PROVIDER_UPDATE = "wb:provider:update";
    String WB_PROVIDER_DELETE = "wb:provider:delete";
}
