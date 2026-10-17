# ERROR-CODES

错误码唯一事实源：分段、每个码的标识与 HTTP 状态、抛出方式、全局异常处理器映射、前端消费方式。

---

## 1. 定位

本文回答错误码是什么与怎么用：每个码对应确定的用户可见提示与 HTTP 状态码，业务代码只抛 `BizException`，不自行拼装响应。

不回答：错误码枚举的实现（见 `common/ErrorCodeEnum`），异常抛出与控制器注解的方法约定（见 `AGENTS.md §11`），响应信封字段（见 `API-CONTRACT.md §2`）。

## 2. 分段

| 段位 | 领域 |
|---|---|
| 0 | 成功（保留码，不属于任何分段） |
| 1000 | 通用 |
| 2000 | 认证与登录态 |
| 2200 | 用户 |
| 2300 | 角色 |
| 2400 | 菜单与权限 |
| 2500 | 幂等 |
| 3000 | 会话与运行 |
| 4000 | 模型 |
| 5000 | 工具 |
| 6000 | 文件 |
| 7000 | 运行时 |
| 8000 | 技能 |
| 8600 | 知识库 |
| 8700 | 创作文档 |
| 8800 | 保留未用（段号不回收） |
| 8900 | 长期记忆 |
| 9000 | 系统与中间件 |
| 9100 | 工作区与存储桶 |

## 3. 通用 1000（0 为成功保留码）

信封 `R<T>` 中 `code = "0"` 表示成功；1000 段自 1001 起为通用失败码。

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 0 | `SUCCESS` | 200 | 成功 |
| 1001 | `PARAM_INVALID` | 400 | 参数不合法 |
| 1002 | `RESOURCE_NOT_FOUND` | 404 | 资源不存在 |
| 1003 | `STATE_CONFLICT` | 409 | 当前状态不允许该操作 |
| 1004 | `RATE_LIMITED` | 429 | 操作过于频繁，请稍后再试 |
| 1005 | `METHOD_NOT_ALLOWED` | 405 | 请求方法不支持 |
| 1006 | `UNSUPPORTED_MEDIA_TYPE` | 415 | 不支持的请求类型 |
| 1099 | `INTERNAL_ERROR` | 500 | 服务内部错误 |

## 4. 认证 2000

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 2001 | `UNAUTHORIZED` | 401 | 请先登录 |
| 2002 | `CREDENTIAL_INVALID` | 401 | 用户名或密码错误 |
| 2003 | `FORBIDDEN` | 403 | 没有操作权限 |
| 2004 | `TOKEN_EXPIRED` | 401 | 登录已过期 |
| 2005 | `OLD_PASSWORD_INVALID` | 400 | 原密码不正确 |
| 2006 | `PASSWORD_RESET_REQUIRED` | 403 | 请先修改初始密码 |
| 2007 | `ACCOUNT_LOCKED` | 403 | 登录失败次数过多，请稍后再试 |
| 2008 | `PASSWORD_WEAK` | 400 | 密码强度不足，至少 8 位且同时包含字母与数字 |
| 2009 | `PAYLOAD_DECRYPT_FAILED` | 400 | 凭据解析失败，请刷新页面后重试 |

## 5. 用户 2200

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 2201 | `USER_NOT_FOUND` | 404 | 用户不存在 |
| 2202 | `USERNAME_CONFLICT` | 409 | 登录名已存在 |
| 2203 | `ACCOUNT_DISABLED` | 403 | 账号已停用 |
| 2204 | `SELF_OPERATION_FORBIDDEN` | 409 | 不能对自己执行该操作 |

## 6. 角色 2300

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 2301 | `ROLE_NOT_FOUND` | 404 | 角色不存在 |
| 2302 | `ROLE_CODE_CONFLICT` | 409 | 角色码已存在 |
| 2303 | `ROLE_IN_USE` | 409 | 角色已分配给用户，不能删除 |
| 2304 | `BUILTIN_ROLE_IMMUTABLE` | 409 | 内置角色不允许该操作 |

## 7. 菜单与权限 2400

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 2401 | `MENU_NOT_FOUND` | 404 | 菜单不存在 |
| 2402 | `MENU_HAS_CHILDREN` | 409 | 存在子菜单，不能删除 |
| 2403 | `MENU_PERM_CONFLICT` | 409 | 权限标识已存在 |
| 2404 | `PERMISSION_DENIED` | 403 | 无此权限点 |

## 8. 幂等 2500

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 2501 | `REPEAT_SUBMIT` | 409 | 请求正在处理中，请勿重复提交 |

## 9. 会话与运行 3000

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 3001 | `SESSION_NOT_FOUND` | 404 | 会话不存在 |
| 3002 | `SESSION_BUSY` | 409 | 该会话有运行正在进行 |
| 3101 | `RUN_NOT_FOUND` | 404 | 运行不存在 |
| 3102 | `RUN_NOT_ABORTABLE` | 409 | 该运行不可中断 |
| 3103 | `RUN_ALREADY_FINISHED` | 409 | 运行已结束 |

## 10. 模型 4000

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 4001 | `PROVIDER_NOT_CONFIGURED` | 400 | 模型供应方未配置 |
| 4002 | `MODEL_CALL_FAILED` | 502 | 模型调用失败 |
| 4003 | `MODEL_CALL_TIMEOUT` | 504 | 模型调用超时 |
| 4004 | `MODEL_OUTPUT_INVALID` | 502 | 模型返回内容无法解析 |

## 11. 工具 5000

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 5001 | `TOOL_NOT_FOUND` | 404 | 工具不存在 |
| 5002 | `TOOL_DISABLED` | 409 | 工具已禁用 |
| 5003 | `TOOL_EXEC_TIMEOUT` | 504 | 工具执行超时 |
| 5005 | `TOOL_CONFIRM_REQUIRED` | 409 | 该工具有副作用，需确认后执行 |
| 5006 | `TOOL_TARGET_BLOCKED` | 403 | 工具目标地址不被允许 |

## 12. 文件 6000

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 6001 | `FILE_NOT_FOUND` | 404 | 文件不存在 |
| 6002 | `FILE_UPLOAD_FAILED` | 500 | 文件上传失败 |
| 6003 | `FILE_TOO_LARGE` | 413 | 文件超过大小上限 |
| 6004 | `FILE_DELETE_FAILED` | 500 | 文件删除失败 |

## 13. 运行时 7000

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 7001 | `PYTHON_NOT_READY` | 503 | Python 运行时未就绪 |
| 7002 | `PYTHON_BUNDLE_MISSING` | 503 | 内置运行时包缺失 |
| 7003 | `PYTHON_EXEC_FAILED` | 500 | 脚本执行失败 |
| 7004 | `PYTHON_UNSAFE_PATH` | 400 | 脚本包含不安全的路径引用 |

## 14. 技能 8000

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 8001 | `SKILL_NOT_FOUND` | 404 | 技能不存在 |
| 8002 | `SKILL_CODE_CONFLICT` | 409 | 技能标识已存在 |

## 15. 知识库 8600

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 8601 | `KB_NOT_FOUND` | 404 | 知识库不存在 |
| 8602 | `KB_NAME_CONFLICT` | 409 | 知识库名已存在 |
| 8603 | `KB_DOC_NOT_FOUND` | 404 | 知识库文档不存在 |
| 8604 | `KB_DOC_TYPE_UNSUPPORTED` | 400 | 不支持的文档格式 |
| 8605 | `KB_DOC_PARSE_FAILED` | 500 | 文档解析失败 |
| 8606 | `KB_DOC_NOT_READY` | 409 | 文档尚未解析完成 |

## 16. 创作文档 8700

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 8701 | `DOC_NOT_FOUND` | 404 | 文档不存在 |
| 8702 | `DOC_TITLE_CONFLICT` | 409 | 同名文档已存在 |
| 8703 | `DOC_CONTENT_TOO_LONG` | 400 | 正文超过 200000 字上限 |
| 8704 | `DOC_VERSION_NOT_FOUND` | 404 | 文档版本不存在 |

## 17. 长期记忆 8900

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 8901 | `MEMORY_NOT_FOUND` | 404 | 记忆不存在 |
| 8902 | `MEMORY_EMPTY` | 400 | 记忆键与内容不能为空 |
| 8903 | `MEMORY_LIMIT_REACHED` | 409 | 记忆条数已达上限 |

## 18. 系统 9000

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 9001 | `DB_UNAVAILABLE` | 503 | 数据库不可用 |
| 9002 | `REDIS_UNAVAILABLE` | 503 | 缓存不可用 |
| 9003 | `STORAGE_UNAVAILABLE` | 503 | 对象存储不可用 |
| 9004 | `MQ_UNAVAILABLE` | 503 | 消息队列不可用 |

## 19. 工作区与存储桶 9100

| 码 | 标识 | HTTP | 提示 |
|---|---|---|---|
| 9101 | `WORKSPACE_NOT_FOUND` | 404 | 工作区不存在 |
| 9102 | `WORKSPACE_NAME_CONFLICT` | 409 | 工作区名称已存在 |
| 9103 | `WORKSPACE_LIMIT_REACHED` | 409 | 工作区数量已达上限（单用户 20） |
| 9104 | `WORKSPACE_DEFAULT_REQUIRED` | 409 | 每个用户至少保留一个默认工作区 |
| 9105 | `BUCKET_CREATE_FAILED` | 500 | 存储桶创建失败 |
| 9106 | `BUCKET_NOT_EMPTY` | 409 | 存储桶非空，不能删除 |
| 9107 | `BUCKET_OP_FAILED` | 500 | 存储桶操作失败 |

## 20. 抛出与消费

抛出方式（统一抛 `common.BizException`，由全局处理器转换）见 `AGENTS.md §11`。

`code` 一律为 `String`（如 `"0"`、`"3001"`），与 HTTP 状态码的 int 区分；网络交互字段全部使用包装类型。

全局处理器 `GlobalExceptionHandler` 统一转换：

| 触发 | 处理 |
|---|---|
| `BizException` | 按其错误码输出，记 warn |
| `NotLoginException`（Sa-Token） | 令牌超时转 `TOKEN_EXPIRED`，其余转 `UNAUTHORIZED` |
| `NotPermissionException` / `NotRoleException`（Sa-Token） | 转 `FORBIDDEN` |
| `MethodArgumentNotValidException` / `ConstraintViolationException` / `MissingServletRequestParameterException` / `HttpMessageNotReadableException` | 转 `PARAM_INVALID`（400），取首条字段错误作为 message |
| `HttpRequestMethodNotSupportedException` | 转 `METHOD_NOT_ALLOWED`（405） |
| `HttpMediaTypeNotSupportedException` | 转 `UNSUPPORTED_MEDIA_TYPE`（415） |
| `NoResourceFoundException` / `NoHandlerFoundException` | 转 `RESOURCE_NOT_FOUND`（404） |
| 其他异常 | 转 `INTERNAL_ERROR`（500），记 error 含堆栈，不把堆栈返回前端 |

前端消费（`frontend/src/api/index.ts`）：

- 响应拦截器只在信封 `code !== '0'` 时提示，文案取后端 `message`，不按 `code` 映射本地文案。
- HTTP `401` 清本地令牌并跳 `/login`。
- HTTP `403` 提示 `body.message`（缺省用 `locales` 的 `api.noPermission`），停留当前页。
- 其余状态取 `body.message`，缺省用 `locales` 的 `api.networkError`；无按状态码自动重试。
- 首登强制改密不由拦截器处理：登录响应携带 `pwdResetRequired`，路由守卫据此把用户锁在 `/force-password`，其余业务接口由后端回 `2006` 阻止越权访问。

## 21. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 分段编码 | 看码知领域，便于归类统计 | 段位需预留，避免后期挤占 |
| 错误码同时带 HTTP 状态 | 网关与前端各取所需 | 两处需同步维护 |
| code 用 String | 与 HTTP int 状态码天然区分，序列化不丢精度 | 前端比较须用字符串 |
| 内部错误不回传堆栈 | 不泄露实现细节 | 排障需查日志 |
| 登录失败统一提示 | 不暴露用户是否存在 | 排查需依赖日志 |

---

错误码定义实现：`backend/src/main/java/com/liteworkbench/common/ErrorCodeEnum.java`
