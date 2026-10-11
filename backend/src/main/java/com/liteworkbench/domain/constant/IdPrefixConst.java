package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** ID 业务前缀，与 docs/DATA-MODEL.md 保持一致 */
@Getter
@AllArgsConstructor
public enum IdPrefixConst {

    /** 用户 */
    USER("USER"),
    /** 角色 */
    ROLE("ROLE"),
    /** 菜单与权限点 */
    MENU("MENU"),
    /** 会话 */
    SESSION("SESS"),
    /** 运行 */
    RUN("RUN"),
    /** 事件 */
    EVENT("EVNT"),
    /** 消息 */
    MESSAGE("MESG"),
    /** 工具 */
    TOOL("TOOL"),
    /** 技能 */
    SKILL("SKIL"),
    /** 文件 */
    FILE("FILE"),
    /** 模型供应方 */
    PROVIDER("PROV"),
    /** 异步任务 */
    TASK("TASK"),
    /** 日志文档 */
    LOG("LOGD"),
    ;

    /** 前缀字面量，最终 ID 形如 前缀_ULID */
    private final String prefix;
}
