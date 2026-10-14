package com.liteworkbench.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 错误码枚举：code 为 String，分段见 docs/ERROR-CODES.md */
@Getter
@AllArgsConstructor
public enum ErrorCodeEnum {

    // 通用 1000（"0" 为成功保留码）
    SUCCESS("0", 200, "成功"),
    PARAM_INVALID("1001", 400, "参数不合法"),
    RESOURCE_NOT_FOUND("1002", 404, "资源不存在"),
    STATE_CONFLICT("1003", 409, "当前状态不允许该操作"),
    RATE_LIMITED("1004", 429, "操作过于频繁，请稍后再试"),
    METHOD_NOT_ALLOWED("1005", 405, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE("1006", 415, "不支持的请求类型"),
    INTERNAL_ERROR("1099", 500, "服务内部错误"),

    // 认证 2000
    UNAUTHORIZED("2001", 401, "请先登录"),
    CREDENTIAL_INVALID("2002", 401, "用户名或密码错误"),
    FORBIDDEN("2003", 403, "没有操作权限"),
    TOKEN_EXPIRED("2004", 401, "登录已过期"),
    OLD_PASSWORD_INVALID("2005", 400, "原密码不正确"),
    PASSWORD_RESET_REQUIRED("2006", 403, "请先修改初始密码"),
    ACCOUNT_LOCKED("2007", 403, "登录失败次数过多，请稍后再试"),
    PASSWORD_WEAK("2008", 400, "密码强度不足，至少 8 位且同时包含字母与数字"),
    PAYLOAD_DECRYPT_FAILED("2009", 400, "凭据解析失败，请刷新页面后重试"),

    // 用户 2200
    USER_NOT_FOUND("2201", 404, "用户不存在"),
    USERNAME_CONFLICT("2202", 409, "登录名已存在"),
    ACCOUNT_DISABLED("2203", 403, "账号已停用"),
    SELF_OPERATION_FORBIDDEN("2204", 409, "不能对自己执行该操作"),

    // 角色 2300
    ROLE_NOT_FOUND("2301", 404, "角色不存在"),
    ROLE_CODE_CONFLICT("2302", 409, "角色码已存在"),
    ROLE_IN_USE("2303", 409, "角色已分配给用户，不能删除"),
    BUILTIN_ROLE_IMMUTABLE("2304", 409, "内置角色不允许该操作"),

    // 菜单与权限 2400
    MENU_NOT_FOUND("2401", 404, "菜单不存在"),
    MENU_HAS_CHILDREN("2402", 409, "存在子菜单，不能删除"),
    MENU_PERM_CONFLICT("2403", 409, "权限标识已存在"),
    PERMISSION_DENIED("2404", 403, "无此权限点"),

    // 幂等 2500
    REPEAT_SUBMIT("2501", 409, "请求正在处理中，请勿重复提交"),

    // 会话与运行 3000
    SESSION_NOT_FOUND("3001", 404, "会话不存在"),
    SESSION_BUSY("3002", 409, "该会话有运行正在进行"),
    RUN_NOT_FOUND("3101", 404, "运行不存在"),
    RUN_NOT_ABORTABLE("3102", 409, "该运行不可中断"),
    RUN_ALREADY_FINISHED("3103", 409, "运行已结束"),

    // 模型 4000
    PROVIDER_NOT_CONFIGURED("4001", 400, "模型供应方未配置"),
    MODEL_CALL_FAILED("4002", 502, "模型调用失败"),
    MODEL_CALL_TIMEOUT("4003", 504, "模型调用超时"),
    MODEL_OUTPUT_INVALID("4004", 502, "模型返回内容无法解析"),

    // 工具 5000
    TOOL_NOT_FOUND("5001", 404, "工具不存在"),
    TOOL_DISABLED("5002", 409, "工具已禁用"),
    TOOL_EXEC_TIMEOUT("5003", 504, "工具执行超时"),
    TOOL_CONFIRM_REQUIRED("5005", 409, "该工具有副作用，需确认后执行"),

    // 文件 6000
    FILE_NOT_FOUND("6001", 404, "文件不存在"),
    FILE_UPLOAD_FAILED("6002", 500, "文件上传失败"),
    FILE_TOO_LARGE("6003", 413, "文件超过大小上限"),
    FILE_DELETE_FAILED("6004", 500, "文件删除失败"),

    // 运行时 7000
    PYTHON_NOT_READY("7001", 503, "Python 运行时未就绪"),
    PYTHON_BUNDLE_MISSING("7002", 503, "内置运行时包缺失"),
    PYTHON_EXEC_FAILED("7003", 500, "脚本执行失败"),
    PYTHON_UNSAFE_PATH("7004", 400, "脚本包含不安全的路径引用"),

    // 技能 8000
    SKILL_NOT_FOUND("8001", 404, "技能不存在"),
    SKILL_CODE_CONFLICT("8002", 409, "技能标识已存在"),

    // 知识库 8600
    KB_NOT_FOUND("8601", 404, "知识库不存在"),
    KB_NAME_CONFLICT("8602", 409, "知识库名已存在"),
    KB_DOC_NOT_FOUND("8603", 404, "知识库文档不存在"),
    KB_DOC_TYPE_UNSUPPORTED("8604", 400, "不支持的文档格式"),
    KB_DOC_PARSE_FAILED("8605", 500, "文档解析失败"),
    KB_DOC_NOT_READY("8606", 409, "文档尚未解析完成"),

    // 创作文档 8700
    DOC_NOT_FOUND("8701", 404, "文档不存在"),
    DOC_TITLE_CONFLICT("8702", 409, "同名文档已存在"),
    DOC_CONTENT_TOO_LONG("8703", 400, "正文超过 200000 字上限"),
    DOC_VERSION_NOT_FOUND("8704", 404, "文档版本不存在"),

    // 提示词 8800
    PROMPT_NOT_FOUND("8801", 404, "提示词不存在"),
    PROMPT_BUILTIN_READONLY("8802", 409, "内置提示词只读，请先复制"),
    PROMPT_TITLE_CONFLICT("8803", 409, "同名提示词已存在"),
    PROMPT_VARIABLE_MISSING("8804", 400, "变量未填写完整"),

    // 长期记忆 8900
    MEMORY_NOT_FOUND("8901", 404, "记忆不存在"),
    MEMORY_EMPTY("8902", 400, "记忆键与内容不能为空"),
    MEMORY_LIMIT_REACHED("8903", 409, "记忆条数已达上限"),

    // 系统 9000
    DB_UNAVAILABLE("9001", 503, "数据库不可用"),
    REDIS_UNAVAILABLE("9002", 503, "缓存不可用"),
    STORAGE_UNAVAILABLE("9003", 503, "对象存储不可用"),
    MQ_UNAVAILABLE("9004", 503, "消息队列不可用"),
    ;

    private final String code;
    private final int httpStatus;
    private final String message;
}
