# API-CONTRACT

接口契约唯一事实源：统一响应信封、认证方式、每个资源的全部接口（方法 / 路径 / 入参 / 出参 / 权限点 / 幂等 / 限流）、SSE 帧结构与字段语义、分页与列表响应形状。

---

## 1. 定位

本文回答：前端能调到哪些接口，每个接口怎么传参、返回什么、需要什么权限、是否幂等。

本文不回答：

- 通用 API 约定（只允许 GET / POST、路径参数放末尾、分页参数命名、动作式路径）——见 `AGENTS.md §4`。
- 业务实现原理（运行状态机、事件合帧时机、检索排序与索引结构、异步解析流程）——见 `specs/01` ~ `specs/06`。
- 完整错误码清单——见 [ERROR-CODES.md](ERROR-CODES.md)。

所有路径统一前缀 `/api/v1`。控制器位于 `backend/src/main/java/com/liteworkbench/api/`，入参一律 `domain.dto.*REQ`，出参一律 `domain.vo.*` 或 `common.R` / `common.PageResult`。

## 2. 统一信封

成功响应：

```json
{
  "code": "0",
  "message": "ok",
  "data": {},
  "requestId": "01HQ...",
  "timestamp": 1710000000000
}
```

| 字段 | 说明 |
|---|---|
| `code` | 业务码，`"0"` 成功；`String` 类型，与 HTTP 状态码的 `int` 区分 |
| `message` | 面向人的提示，失败时可直接展示 |
| `data` | 业务数据；分页接口为 `PageResult`（见 §18） |
| `requestId` | 链路标识，取自日志 `traceId`（MDC） |
| `timestamp` | 响应毫秒时间戳 |

失败响应同样落在信封内，不返回 Spring 默认错误体：

```json
{ "code": "3001", "message": "会话不存在", "data": null, "requestId": "01M4..", "timestamp": 1710000000000 }
```

`业务码 → HTTP 状态码` 由 `common.ErrorCodeEnum` 固定绑定，`GlobalExceptionHandler` 按该绑定输出：

| HTTP | 触发场景 | 代表 code |
|---|---|---|
| 200 | 成功 | `0` |
| 400 | 参数校验失败、畸形 JSON、原密码错、密码强度不足、凭据解密失败、供应方未配置、文档格式/正文超限、变量缺失 | `1001` `2005` `2008` `2009` `4001` `8604` `8703` `8902` `7004` |
| 401 | 未登录、令牌过期、用户名或密码错误 | `2001` `2002` `2004` |
| 403 | 无权限点、强制改密期访问业务接口、登录失败锁定、账号停用 | `2003` `2006` `2007` `2203` `2404` |
| 404 | 资源不存在（含归属校验失败与未命中的 `/api/**`） | `1002` `2201` `2301` `2401` `3001` `3101` `5001` `6001` `8601` `8603` `8701` `8704` `8901` |
| 405 | 方法与接口声明不符（PUT / PATCH / DELETE） | `1005` |
| 409 | 状态冲突、重复提交、唯一性冲突、需确认的工具、文档未就绪 | `1003` `2501` `2202` `2204` `2302` `2303` `2304` `2402` `2403` `3002` `3102` `3103` `5002` `5005` `8602` `8606` `8702` `8903` |
| 413 | 上传超过大小上限 | `6003` |
| 415 | 请求体类型不符 | `1006` |
| 429 | 限流触发 | `1004` |
| 500 | 内部错误、文件上传/删除失败、脚本执行失败、文档解析失败 | `1099` `6002` `6004` `7003` `8605` |
| 502 | 模型调用失败、模型输出不可解析 | `4002` `4004` |
| 503 | Python 运行时未就绪、Postgres / Redis / MinIO / MQ 不可用 | `7001` `7002` `9001` ~ `9004` |
| 504 | 模型调用超时、工具执行超时 | `4003` `5003` |

### 2.1 路由兜底

`config/WebConfig` 注册两个 `SimpleUrlHandlerMapping`，顺序是唯一保障（两者模式重叠）：

| 顺序 | 模式 | 行为 |
|---|---|---|
| 1 | `/api/**` | 未命中任何业务接口时回 `404` + `1002` 信封（`application/json`），并打 `[api.notfound]` 日志 |
| 2 | `/{path:[^\.]*}`、`/**/{path:[^\.]*}` | `forward:/index.html`，前端深链刷新不被吞 |

带点的请求（静态资源）与 `/api/**` 都不会落到深链兜底：前者模式不匹配，后者已被顺序 1 接管。业务接口抛出的 `NoResourceFoundException` / `NoHandlerFoundException` 也统一转 `1002`。

## 3. 认证

登录态由 Sa-Token 管理：令牌放 `Authorization` 头，值为令牌本身（不加 `Bearer` 前缀），会话存 Redis，`timeout = 7200` 秒（2 小时），多端登录并发（`is-concurrent = true`）、不共享令牌，令牌风格为 ULID。

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 / 限流 |
|---|---|---|---|---|
| GET | `/auth/public-key` | — → `PublicKeyVO` | 免登录 | 限流 `auth:key` 60 次 / 60 秒，按 IP |
| POST | `/auth/login` | `LoginREQ` → `LoginRESP` | 免登录 | 限流 `auth:login` 10 次 / 60 秒，按 IP |
| POST | `/auth/logout` | — → `R<Void>` | 登录 | `@Idempotent("logout", ttl = 3)` |
| GET | `/auth/current` | — → `LoginRESP` | 登录 | — |
| POST | `/auth/password` | `PasswordREQ` → `R<Void>` | 登录 | `@Idempotent("auth:password")`（ttl 10） |

**密码字段一律为 RSA 密文**（`RSA/ECB/PKCS1Padding`，Base64，与浏览器端 JSEncrypt 对齐）。覆盖四个入口：

| 入口 | 字段 |
|---|---|
| 登录 | `password` + `keyId` |
| 自助改密 | `oldPassword` / `newPassword` + `keyId` |
| 管理员建用户 | `password` + `passwordKeyId` |
| 管理员重置密码 | `newPassword` + `keyId` |

密文字段不做长度校验（RSA-2048 密文 Base64 后 344 字符）；口令强度在服务端解密后的明文上判定（≥8 位且含字母与数字，否则 `PASSWORD_WEAK` `2008`）。解密失败即 `CREDENTIAL_INVALID` `2002`（自助改密与管理员设密为 `PAYLOAD_DECRYPT_FAILED` `2009`）。

公钥响应 `data`（`PublicKeyVO`）：`keyId` / `algorithm` / `publicKey`（SPKI 的 Base64）/ `expiresAt`（毫秒时间戳）。

登录与当前用户响应 `data`（`LoginRESP`）：

```json
{
  "token": "01M4...",
  "user": {
    "userId": "USER_..", "username": "admin", "nickname": "管理员", "email": "",
    "avatarUrl": "", "state": "normal", "roleIds": ["ROLE_.."],
    "lastLoginAt": 1710000000000, "createdAt": 1700000000000
  },
  "roleCodes": ["super_admin"],
  "perms": ["system:user:list", "system:user:create"],
  "pwdResetRequired": false,
  "menus": [
    {
      "menuId": "MENU_..", "parentId": "MENU_..", "menuName": "menu.dashboard",
      "menuType": "menu", "routePath": "/dashboard", "component": "Dashboard",
      "icon": "dashboard", "permCode": null, "sortNo": 10, "visible": true, "children": []
    }
  ]
}
```

- `user.state` 取值 `normal` / `disabled` / `locked`。
- `menus` 是 `dir` / `menu` 两级树（`button` 类型的权限点不进树），`menuName` 为 i18n key；`token` 仅登录接口返回，`/auth/current` 为 null。
- `pwdResetRequired = true` 时前端必须跳强制改密页；后端只放行 `/auth/current`、`/auth/password`、`/auth/logout`、`/auth/public-key`，其余业务接口返回 `PASSWORD_RESET_REQUIRED` `2006`。
- 同一用户名连续 5 次凭据错误锁定 600 秒，返回 `ACCOUNT_LOCKED` `2007`。

## 4. 用户

权限点前缀 `system:user:`。

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 |
|---|---|---|---|---|
| GET | `/user/page` | `UserPageREQ`（分页字段 + `state` + `roleId` + `pwdResetRequired`） → `PageResult<UserVO>` | `system:user:list` | — |
| GET | `/user/detail/{userId}` | — → `UserVO` | `system:user:list` | — |
| POST | `/user/create` | `UserREQ` → `R<String>`（新用户 ID） | `system:user:create` | `user:create` |
| POST | `/user/update` | `UserREQ` → `R<Void>` | `system:user:update` | `user:update` |
| POST | `/user/toggle/{userId}` | — → `R<Void>` | `system:user:update` | `user:toggle` |
| POST | `/user/reset-password` | `ResetPasswordREQ` → `R<Void>` | `system:user:reset` | `user:reset` |
| POST | `/user/delete/{userId}` | — → `R<Void>` | `system:user:delete` | `user:delete` |
| POST | `/user/batch-delete` | `BatchREQ`（`ids`，≤100） → `BatchResultVO` | `system:user:delete` | `user:batch-delete` |
| POST | `/user/batch-toggle` | `BatchREQ` → `BatchResultVO` | `system:user:update` | `user:batch-toggle` |

- `UserREQ`：`userId`（修改时必填）、`username`（仅新建，`^[a-zA-Z][a-zA-Z0-9_]{2,31}$`）、`password` + `passwordKeyId`（仅新建，密文）、`nickname`（≤64）、`email`、`roleIds`、`remark`。
- 不能修改自己的角色、不能停用或删除自己（`SELF_OPERATION_FORBIDDEN` `2204`）。
- 停用 / 删除目标用户会失效其在线会话；重置密码后该用户首登仍要求改密。
- 批量接口返回 `BatchResultVO`：`successCount` + `failures[{id, reason}]`。**逐条守门而非整批事务回滚**：不允许的（删自己、停自己）进 `failures`，其余照常执行；批量启停的目标状态由首条当前状态推导并整批统一，不会出现半启用半停用。

## 5. 角色

权限点前缀 `system:role:`，仅 `super_admin` 持有。

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 |
|---|---|---|---|---|
| GET | `/role/page` | `PageREQ` → `PageResult<RoleVO>` | `system:role:list` | — |
| GET | `/role/list` | — → `List<RoleVO>` | `system:role:list` | — |
| GET | `/role/detail/{roleId}` | — → `RoleVO` | `system:role:list` | — |
| GET | `/role/menu-ids/{roleId}` | — → `List<String>` | `system:role:list` | — |
| POST | `/role/create` | `RoleREQ` → `R<String>` | `system:role:create` | `role:create` |
| POST | `/role/update` | `RoleREQ` → `R<Void>` | `system:role:update` | `role:update` |
| POST | `/role/grant` | `RoleGrantREQ`（`roleId` + `menuIds`，全量覆盖） → `R<Void>` | `system:role:grant` | `role:grant` |
| POST | `/role/delete/{roleId}` | — → `R<Void>` | `system:role:delete` | `role:delete` |

内置角色码 `super_admin` / `admin` / `user` 不可删除、不可改码（`BUILTIN_ROLE_IMMUTABLE` `2304`）；已分配用户的角色不可删除（`ROLE_IN_USE` `2303`）。赋权守门：`/user/create`、`/user/update` 分配角色与 `/role/grant` 授菜单时，被授出的权限点必须是操作人已持有权限的子集，越权即 `FORBIDDEN` `2003`。

## 6. 菜单

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 |
|---|---|---|---|---|
| GET | `/menu/tree` | — → `List<MenuVO>` | `system:menu:list` | — |
| GET | `/menu/user-tree` | — → `List<MenuVO>` | 登录即可用 | — |
| GET | `/menu/detail/{menuId}` | — → `MenuVO` | `system:menu:list` | — |
| POST | `/menu/create` | `MenuREQ` → `R<String>` | `system:menu:create` | `menu:create` |
| POST | `/menu/update` | `MenuREQ` → `R<Void>` | `system:menu:update` | `menu:update` |
| POST | `/menu/delete/{menuId}` | — → `R<Void>` | `system:menu:delete` | `menu:delete` |

`MenuVO`：`menuId` / `parentId`（根为 `0`）/ `menuName`（i18n key）/ `menuType`（`dir` / `menu` / `button`）/ `routePath` / `component` / `icon` / `permCode` / `sortNo` / `visible` / `children`。存在子菜单时删除返回 `MENU_HAS_CHILDREN` `2402`，`permCode` 冲突返回 `MENU_PERM_CONFLICT` `2403`。

## 7. 会话

会话按 `user_id` 归属，登录即可用，不额外挂权限点。

| 方法 | 路径 | 入参 → 出参 | 幂等 |
|---|---|---|---|
| GET | `/session/page` | `SessionPageREQ`（`page` `size` `keyword`） → `PageResult<SessionVO>` | — |
| GET | `/session/detail/{sessionId}` | — → `SessionVO` | — |
| POST | `/session/create` | `SessionREQ` → `R<String>` | `session:create` |
| POST | `/session/update` | `SessionREQ` → `R<Void>` | `session:update` |
| POST | `/session/delete/{sessionId}` | — → `R<Void>` | `session:delete` |
| GET | `/session/messages/{sessionId}` | — → `List<MessageVO>` | — |

`SessionREQ` 承载标题 / 模型 / 供应方 / 工作区 / 系统提示词（标题上限 128，系统提示词上限 8000）。`(sessionId, user_id)` 查不到即 `SESSION_NOT_FOUND` `3001`。`workspaceId` 显式传入才改绑定（留空沿用当前），新建时空值解析默认工作区（无工作区则懒建）。`SessionVO` 回带 `workspaceId`，输入框据即切换工作区。

## 8. 对话与运行

### 8.1 流式对话（SSE）

```
POST /api/v1/chat/stream
Content-Type: application/json
Authorization: <token>

{ "sessionId": "SESS_...", "content": "帮我分析这份数据 /data_brief @会议纪要.txt" }
```

入参 `ChatStreamREQ`（`sessionId` 必填，`content` 必填且 ≤32000）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `skillCodes` | `string[]`，≤5 | 输入框 `/` 点名的技能码，命中即强制展开正文，不看触发词 |
| `refs` | `RunRefREQ[]`，≤8 | 输入框 `@` 引用的上下文，`{type: kb / file / doc, id, label}`；`kb` 按当前问题预取分块，`file` 注入文本正文（非文本给提示），`doc` 注入文档正文 |

`content` 里保留 `/code` 与 `@名称` 标记（模型可见），但以 `skillCodes` / `refs` 为准；`run.ref` 日志给出注入条数与字符数。类型非法抛 `PARAM_INVALID`。接口带 `@RateLimit(run:chat, 20 次 / 60 秒, 按用户)` 与 `@Idempotent("chat", ttl = 3)`。

响应 `Content-Type: text/event-stream`，连接超时 30 分钟。帧格式（`api/SseRunSink` 实现 `service/RunSink`）：

```
event: run.event
id: 42
data: {"runId":"RUN_..","sessionId":"SESS_..","seq":42,"eventType":"text.delta","blockType":"text","textDelta":"你","payload":{"delta":"你","merged":3},"occurredAt":1710000000000}

: ping
```

| 帧要素 | 契约 |
|---|---|
| 事件名 | 固定 `run.event`，单一通道，具体类型看 `data.eventType` |
| `id` | 事件序号 `seq`；客户端重连携带 `Last-Event-ID` 可从断点续拉 |
| `data` | `RunEventDTO` 全量 JSON，字段固定为 `runId` / `sessionId` / `seq` / `eventType` / `blockType` / `textDelta` / `payload` / `occurredAt` |
| `retry` | 不发送，重连间隔由浏览器默认策略决定 |
| 心跳 | 注释行 `: ping`，每 15000 ms 一次（`RunConst.SSE_HEARTBEAT_MS`） |

`seq` 从 1 起（`RunConst.EVENT_SEQ_START`）单调递增；终态帧先落库再推送，因此回放与实时看到同一序列。

事件类型全集（`EventTypeEnum`，`payload` 为 `data.payload` 的字段）：

| eventType | blockType | payload | 时机 |
|---|---|---|---|
| `run.start` | — | `phase="start"` | 运行创建 |
| `thinking.delta` | `thinking` | `delta`、合帧后带 `merged` | 思考增量 |
| `text.delta` | `text` | `delta`、合帧后带 `merged` | 正文增量 |
| `tool.call` | `tool_call` | `phase`（`start` / `end`）、`toolName`、`toolCallId`、`arguments` | 工具调用起止 |
| `tool.result` | `tool_result` | `toolName`、`toolCallId`、`output`、`isError` | 工具调用结束 |
| `model.call` | — | `phase`；`end` 帧带 `inputTokens`、`outputTokens`、`durationMs` | 模型调用起止 |
| `plan.updated` | `plan` | `steps` | 计划更新 |
| `run.result` | `text` | `text`（完整正文，单帧） | 运行产出正文 |
| `run.exceed` | — | `maxIters` | 达到迭代上限 |
| `run.snapshot` | — | `watermark`、`textLength`、`toolCalls` | 水位快照，节流下发 |
| `run.end` | — | `runId`、`status`、`stopReason`、`usage{inputTokens,outputTokens}` | 终态，落库后推送 |
| `run.error` | — | `code`、`message` | 异常终态 |
| `custom` | — | `eventType` | 未归类事件的兜底 |

规则：

- `run.end` 与 `run.error` 必有且仅有其一（`settled` 标志保证一次收尾），收到后连接关闭。
- 合帧只作用于连续同类型的增量（`text.delta` / `thinking.delta`）：`textDelta` 拼接、`seq` 取末条、`payload.merged` 记合并条数；类型切换即断批，保证 text 与 thinking 的时间线不乱。
- 单帧增量超 4096 字符截断，完整正文以 `run.result` 为准。
- 水位快照节流 200 ms（`RunConst.SSE_SNAPSHOT_THROTTLE_MS`），只在 `seq` 有新推进时下发；客户端比对 `watermark` 与自身最大 `seq` 判定丢帧并回放补齐。
- 推送与落库双节拍：推送 16 ms、落库 200 ms，互不阻塞。

### 8.2 运行

| 方法 | 路径 | 入参 → 出参 | 幂等 |
|---|---|---|---|
| GET | `/run/page` | `SessionPageREQ`（`sessionId` `state` `from` `to` + 分页） → `PageResult<RunVO>` | — |
| GET | `/run/detail/{runId}` | — → `RunVO` | — |
| GET | `/run/events/{runId}` | `afterSeq`（默认 0） → `List<RunEventDTO>`，单页上限 500 | — |
| POST | `/run/abort/{runId}` | — → `R<Void>` | `run:abort` |

- 运行接口登录即可用；详情、回放、中断一律先按 `(runId, user_id)` 查，查不到即 `RUN_NOT_FOUND` `3101`。
- 中断不可中断的运行返回 `RUN_NOT_ABORTABLE` `3102`。
- **并发**：同一会话同时只允许一个运行，`lw:lock:run:{sessionId}` 的 TTL 为 `SSE_TIMEOUT_MS / 1000 + 60` 秒，锁占用时返回 `SESSION_BUSY` `3002`。
- **子运行**：`task_delegate` 产生的子运行同属一个会话，不单独开 SSE，交互与树关系见 `specs/02`。

### 8.3 可观测性

| 方法 | 路径 | 入参 → 出参 | 权限 |
|---|---|---|---|
| GET | `/trace/spans/{runId}` | — → `List<TraceSpanVO>` | 登录即可用，按 `(runId, user_id)` 校验归属 |
| GET | `/trace/tree/{sessionId}` | — → `List<TraceRunVO>` | 登录即可用，按归属用户过滤 |
| GET | `/trace/usage` | `TraceQueryREQ`（`from` `to` 毫秒时间戳，`days` 缺省回看 30 天） → `UsageStatsVO` | 登录即可用 |

`TraceSpanVO`（一次运行内的一个时间区间，供瀑布图按偏移与宽度绘制）：

| 字段 | 语义 |
|---|---|
| `spanId` | 模型段为 `model-{seq}`，工具段为 `toolCallId`，计划段为 `plan-{seq}` |
| `kind` | `model` / `tool` / `plan` |
| `name` | 展示名：模型段固定「模型推理」，工具段为工具名，计划段固定「计划更新」 |
| `iteration` | 模型段的推理轮次，从 1 递增 |
| `offsetMs` | 相对本次运行最早事件的偏移毫秒 |
| `durationMs` | 持续毫秒；模型段优先取 `model.call` end 帧的 `durationMs` |
| `status` | `ok` / `error` / `pending`（未收到收尾事件即 `pending`，让「卡在哪」可见） |
| `inputTokens` / `outputTokens` | 模型段 token，取自 end 帧 |
| `outputChars` | 工具段输出字符数 |
| `arguments` | 工具段入参摘要，上限 160 字符 |

`TraceRunVO`（Run 树节点，`children` 为派生出的子运行）：`runId` / `parentRunId` / `relation`（`original` / `child`）/ `prompt`（输入摘要，≤160 字符）/ `modelName` / `status` / `stopReason` / `iterCount` / `inputTokens` / `outputTokens` / `durationMs` / `startedAt` / `eventCount`（事件条数）/ `children`。父节点不在结果集内的节点作为根返回，从子运行进入也能拿到整棵树。

`UsageStatsVO`：`totals`（`runCount` / `inputTokens` / `outputTokens` / `avgDurationMs`）、`daily[]`（`day` `runCount` `inputTokens` `outputTokens` `successRate`，时间正序）、`byModel[]`（`modelName` `runCount` `inputTokens` `outputTokens`，token 降序）、`byState[]`（`state` `runCount`），全部聚合自 `wb_run` 账目字段。**视角**：持有 `monitor:log:global` 的角色看全平台，其余用户只看自己。

## 9. 工具

工具为平台级定义（`wb_tool`），启停全局生效。

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 |
|---|---|---|---|---|
| GET | `/tool/list` | — → `List<ToolVO>` | `wb:tool:list` | — |
| POST | `/tool/toggle/{toolId}` | query `enabled`（必填） → `R<Void>` | `wb:tool:toggle` | `tool:toggle` |
| POST | `/tool/invoke/{toolName}` | `InvokeToolREQ` → `R<String>`（工具原始输出） | `wb:tool:invoke` | `tool:invoke` |

`InvokeToolREQ`：`args`（工具参数，必填）、`confirmed`（默认 false）、`timeoutSeconds`（可空，走默认 120 秒）。

- 页面手工执行时：`safe` 工具直接执行；`confirm` / `danger` 工具必须显式携带 `confirmed = true`，否则返回 `TOOL_CONFIRM_REQUIRED` `5005`。工具被停用返回 `TOOL_DISABLED` `5002`，超出执行时限返回 `TOOL_EXEC_TIMEOUT` `5003`，工具名不存在返回 `TOOL_NOT_FOUND` `5001`。
- Agent 自主调用不走确认闸门，统一由平台级启停开关控制。

## 10. 技能

技能按 `user_id` 归属，权限点前缀 `wb:skill:`。

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 |
|---|---|---|---|---|
| GET | `/skill/page` | `PageREQ` → `PageResult<SkillVO>` | `wb:skill:list` | — |
| POST | `/skill/create` | `SkillREQ` → `R<String>` | `wb:skill:create` | `skill:create` |
| POST | `/skill/update` | `SkillREQ` → `R<Void>`（全量更新） | `wb:skill:update` | `skill:update` |
| POST | `/skill/delete/{skillId}` | — → `R<Void>` | `wb:skill:delete` | `skill:delete` |
| POST | `/skill/batch-delete` | `BatchREQ` → `BatchResultVO` | `wb:skill:delete` | `skill:batch-delete` |
| POST | `/skill/restore/{skillId}` | — → `R<Void>`（仅内置技能，恢复为种子版本并保留启用状态） | `wb:skill:update` | `skill:restore` |

## 11. 文件

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 |
|---|---|---|---|---|
| GET | `/file/page` | `page` `size` `sessionId` `category` `keyword` → `PageResult<FileVO>` | 登录即可用 | — |
| POST | `/file/upload` | multipart `file` + `sessionId`（可空） → `FileVO` | 登录即可用 | `file:upload` |
| GET | `/file/url/{fileId}` | — → `R<String>`（预签名下载地址） | 登录即可用 | — |
| POST | `/file/delete/{fileId}` | — → `R<Void>` | `wb:file:delete` | `file:delete` |
| POST | `/file/batch-delete` | `BatchREQ` → `BatchResultVO` | `wb:file:delete` | `file:batch-delete` |

- 单文件 ≤ 100MB（`spring.servlet.multipart.max-file-size` 与 `FileService.MAX_SIZE` 同值），超限返回 `FILE_TOO_LARGE` `6003`；上传失败返回 `FILE_UPLOAD_FAILED` `6002`。
- 预签名地址有效期 15 分钟。
- `FileVO`：`fileId` / `sessionId` / `fileName` / `fileSize` / `contentType` / `category` / `createdBy` / `createdAt`。

## 12. 知识库

知识库为用户私有资源（`user_id` 归属），原件进 MinIO，分块进 Manticore。权限点前缀 `wb:kb:`。

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 |
|---|---|---|---|---|
| GET | `/kb/page` | `PageREQ` → `PageResult<KbVO>` | `wb:kb:list` | — |
| GET | `/kb/detail/{kbId}` | — → `KbVO` | `wb:kb:list` | — |
| POST | `/kb/create` | `KbREQ` → `R<String>` | `wb:kb:create` | `kb:create` |
| POST | `/kb/update` | `KbREQ` → `R<Void>` | `wb:kb:update` | `kb:update` |
| POST | `/kb/delete/{kbId}` | — → `R<Void>`（级联清文档与分块索引） | `wb:kb:delete` | `kb:delete` |
| GET | `/kb/doc/page` | `kbId`（必填） `page` `size` `keyword` `state` → `PageResult<KbDocVO>` | `wb:kb:list` | — |
| POST | `/kb/doc/upload/{kbId}` | multipart `file` → `KbDocVO` | `wb:kb:upload` | `kb:upload` |
| POST | `/kb/doc/delete/{docId}` | — → `R<Void>` | `wb:kb:delete` | `kb:doc:delete` |
| POST | `/kb/doc/reparse/{docId}` | — → `R<Void>` | `wb:kb:upload` | `kb:reparse` |
| GET | `/kb/search` | `keyword`（必填） `kbId`（可空） `page` `size`（默认 10） → `PageResult<KbChunkVO>` | `wb:kb:search` | — |

约定：

- 支持格式：`.txt` `.md` `.csv` `.json` `.html` `.xml` `.log`（UTF-8 直读）、`.docx`、`.pdf`，其余返回 `KB_DOC_TYPE_UNSUPPORTED` `8604`；单文件 ≤ 20MB，单库文档数上限 200。
- 上传立即返回 `KbDocVO(state = pending)`，解析经 MQ 异步执行，前端轮询文档状态到 `ready` / `failed`；解析未完成即检索文档内容返回 `KB_DOC_NOT_READY` `8606`，失败可 `reparse` 重放。
- `KbChunkVO`：`chunkId` / `userId` / `kbId` / `docId` / `docName` / `seq`（块序号，从 1 递增）/ `content`（原文）/ `score`（相关度）/ `createdAt`。高亮由前端按 `keyword` 分段渲染，后端只回原文。

## 13. 模型供应方

供应方为平台级定义（`wb_provider`），密钥脱敏后返回。权限点前缀 `wb:provider:`。

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 |
|---|---|---|---|---|
| GET | `/provider/list` | — → `List<ProviderVO>` | `wb:provider:list` | — |
| GET | `/provider/presets` | — → `List<ProviderPresetVO>` | `wb:provider:list` | — |
| POST | `/provider/create` | `ProviderREQ` → `R<String>` | `wb:provider:create` | `provider:create` |
| POST | `/provider/update` | `ProviderREQ` → `R<Void>` | `wb:provider:update` | `provider:update` |
| POST | `/provider/delete/{providerId}` | — → `R<Void>` | `wb:provider:delete` | `provider:delete` |
| POST | `/provider/test/{providerId}` | — → `ProviderVO`（连通性测试） | `wb:provider:update` | `provider:test` |

- `ProviderVO`：`providerId` / `providerCode` / `providerName` / `baseUrl` / `apiKeyMasked`（如 `sk-***abcd`）/ `hasApiKey` / `defaultModel` / 模型参数（`contextWindow` / `maxOutputTokens` / `temperature` / `topP` / `capVision` / `capTool` / `capReasoning`）/ `enabled` / `updatedAt`。密钥永不回显明文。
- 模型参数：`contextWindow`（上下文窗口 token 数，NULL 回落全局）、`maxOutputTokens`（最大输出，NULL 不限）、`temperature`（0~2，NULL 回落全局）、`topP`（0~1，NULL 不下发）、`capVision` / `capTool` / `capReasoning`（能力位，`capTool` 默认 true）。参数随运行下发模型请求（`temperature` / `top_p` / `max_tokens`），`capTool=false` 的模型不注入工具描述。存量行与留空列行为不变。
- `ProviderPresetVO`：`providerCode` / `baseUrl` / `defaultModel` / `protocol`（固定 `openai`）/ `requiresKey`（固定 `true`）。预设码为 `openai` / `deepseek` / `doubao` / `grok`，`baseUrl` 与 `defaultModel` 留空则取预设默认值。
- 供应方配置变更后清 `lw:cache:provider:{code}` 缓存，即时生效。启动不预置任何供应方（避免内置一个跑不通的端点误导用户），无可用供应方时调用返回 `PROVIDER_NOT_CONFIGURED` `4001`，前端设置页给出新建引导。
- `create` 与 `update` 对 `providerCode` 做预设白名单校验：仅接受 `openai` / `deepseek` / `doubao` / `grok`，其余编码（含本地 ollama 端点）返回 `PARAM_INVALID`。存量非预设行仅可读可删，页面展示「协议不受支持」标签。

## 14. 日志、监控与工作区 / 存储

| 方法 | 路径 | 入参 → 出参 | 权限 |
|---|---|---|---|
| GET | `/log/search` | 见下表 → `LogPageVO` | `monitor:log:list` |
| GET | `/log/stats` | 同检索（无 `page` `size`） → `LogStatsVO` | `monitor:log:list` |
| GET | `/log/export` | 同检索（无 `page` `size`） → CSV 流（带 BOM，上限 5000 行） | `monitor:log:list` |
| GET | `/workspace/list` | — → `List<WorkspaceVO>`（含 `objectCount` / `totalSize`） | 登录即可 |
| POST | `/workspace/create` | `WorkspaceREQ` → `R<String>` | 登录即可，`@Idempotent("workspace:create")` |
| POST | `/workspace/update` | `WorkspaceREQ` → `R<Void>` | 登录即可，`@Idempotent("workspace:update")` |
| POST | `/workspace/default/{workspaceId}` | — → `R<Void>` | 登录即可，`@Idempotent("workspace:default")` |
| POST | `/workspace/delete/{workspaceId}` | — → `R<Void>` | 登录即可，`@Idempotent("workspace:delete")` |
| GET | `/storage/buckets` | — → `List<BucketAdminVO>` | `monitor:storage:list` |
| POST | `/storage/bucket/create` | `{bucketName}` → `R<Void>` | `monitor:storage:create`，`@Idempotent("storage:create")` |
| POST | `/storage/bucket/delete/{bucketName}` | — → `R<Void>` | `monitor:storage:delete`，`@Idempotent("storage:delete")` |
| GET | `/monitor/health` | — → `HealthRESP` | `monitor:health:list` |
| GET | `/monitor/overview` | — → `OverviewRESP` | `monitor:health:list` |
| GET | `/runtime/python` | — → `PythonStatusVO` | `monitor:health:list` |
| POST | `/runtime/python/reprobe` | — → `PythonStatusVO` | `monitor:python:reprobe`，`@Idempotent("runtime:reprobe", ttl = 5)` |
| GET | `/monitor/observe` | — → `ObserveRESP` | `monitor:health:list` |
| POST | `/monitor/observe/reset` | — → `R<String>` | `monitor:observe:reset`，`@Idempotent("monitor:observe:reset", ttl = 5)` |

**视角隔离**：持有 `monitor:log:global` 的角色（超级管理员、平台管理员）检索全局日志，并可用 `userId` / `runId` 反查；其余用户由服务端强制追加 `user_id = 自己`，传了 `userId` 也无效。响应首部 `scope` 回传 `"global"` / `"self"`，前端据此展示视角标识。

`log/search` 参数语义：

| 参数 | 说明 |
|---|---|
| `keyword` | 关键字，匹配列由 `field` 决定 |
| `field` | 匹配列白名单：`message`（默认，ICU 中文分词全文）/ `exception`（全文）/ `logger` / `traceId`；传白名单外的值直接 `PARAM_INVALID` |
| `level` | 级别等值：`INFO` / `WARN` / `ERROR` |
| `logger` | Logger 名等值（如 `com.liteworkbench.service.RunService`） |
| `traceId` | 链路标识等值，串一次请求的全部日志 |
| `userId` | 用户 ID 等值，仅全局视角生效 |
| `runId` | 运行 ID 等值，串一次运行的全部日志 |
| `sessionId` | 会话 ID 等值，串一个会话的全部日志 |
| `from` / `to` | 毫秒时间戳，各自缺省：`from` 缺省为近 7 天，`to` 缺省为当前 |
| `page` / `size` | 按 `ts` 倒序分页，单页上限 500 |

- `LogPageVO`：`list` / `total` / `page` / `size` / `scope`；`LogDocVO` 字段为 `logId` / `ts` / `level` / `logger` / `thread` / `traceId` / `userId` / `sessionId` / `runId` / `message` / `exception`。
- `LogStatsVO`：`scope`、`levels[{level,count}]`、`histogram[{hour,count}]`（桶起点毫秒时间戳升序）、`bucketMs`（3600000 或 86400000）。`log/stats` 时间范围缺省为近 24 小时；区间超过 7 天自动改按天分桶，小时分桶上限 168 个、天分桶上限 90 个。
- `HealthRESP`：`status`（`UP` / `DEGRADED` / `DOWN`，核心组件 Postgres / Redis 任一 DOWN 即整体 DOWN，其余组件 DOWN 为 DEGRADED）、`components[{name,status,detail,costMs}]`，组件名固定 `postgres` / `redis` / `manticore` / `minio` / `rabbitmq` / `python`。
- `OverviewRESP`：`sessionCount` / `runCount` / `successRunCount` / `todayRunCount` / `totalTokens` / `runTrend` / `tokenTrend`（`[{date,yyyyMMdd},{value}]`，近 7 天补齐 0）/ `toolDistribution`（工具名 → 调用次数）。全局口径，Redis 缓存 60 秒。
- `ObserveRESP`：`windowStartMs` / `windowMs`（统计窗口，自启动或上次重置起算）/ `sql{count,slowCount,avgMs,maxMs,top[]}` / `http{count,errorCount,avgMs,maxMs,top[]}`；`top[]` 元素为 `{key,count,avgMs,maxMs,lastTs,sample}`，SQL 的 `key` 是语句模板、`sample` 是 mapper 方法，HTTP 的 `key` 是 `方法 + 路由模式`（路径里的 ID 片段归一为 `{id}`）、`sample` 是查询串。进程内窗口值，不落库不缓存。
- `PythonStatusVO`：`status`（`READY` / `NOT_FOUND` / `BAD_VERSION`）/ `version` / `source` / `ok`。

## 15. 创作文档

文档为用户私有资源，权限点前缀 `wb:doc:`。

| 方法 | 路径 | 入参 → 出参 | 权限 | 幂等 |
|---|---|---|---|---|
| GET | `/doc/page` | `PageREQ` + `category` `state` → `PageResult<DocVO>` | `wb:doc:list` | — |
| GET | `/doc/detail/{docId}` | — → `DocVO`（含正文） | `wb:doc:list` | — |
| POST | `/doc/create` | `DocREQ` → `R<String>` | `wb:doc:create` | `doc:create` |
| POST | `/doc/update` | `DocREQ` → `R<Void>`（保存正文） | `wb:doc:update` | `doc:update` |
| POST | `/doc/delete/{docId}` | — → `R<Void>` | `wb:doc:delete` | `doc:delete` |
| GET | `/doc/search` | `PageREQ` + `category` → `PageResult<DocVO>` | `wb:doc:search` | — |
| GET | `/doc/version/page` | `docId`（必填） `page` `size` → `PageResult<DocVersionVO>` | `wb:doc:list` | — |
| GET | `/doc/version/detail/{versionId}` | — → `DocVersionVO` | `wb:doc:list` | — |
| POST | `/doc/version/restore/{versionId}` | — → `R<Void>` | `wb:doc:update` | `doc:restore` |
| POST | `/doc/assist/stream` | `DocAssistREQ` → `SseEmitter` | `wb:doc:update` | — |

约定：

- 正文上限 200000 字符，超出抛 `DOC_CONTENT_TOO_LONG` `8703`；同名文档冲突 `DOC_TITLE_CONFLICT` `8702`；版本不存在 `DOC_VERSION_NOT_FOUND` `8704`。
- AI 辅助动作（`DocAssistREQ.action`）：`continue` / `polish` / `expand` / `summary` / `outline` / `translate`，其余值 `PARAM_INVALID`。`selection` 为空则取 `docId` 指向的整篇正文，两者都空即参数错误；`instruction` ≤2000 字（翻译动作里即目标语言）；`kbId` 非空时先检索知识库拼入提示词。
- `doc/assist/stream` 响应 `Content-Type: text/event-stream`，连接超时 30 分钟，**不建运行**（不写 `wb_run` / `wb_event`），客户端断开即取消模型调用。

辅助写作帧格式（`api/SseDocAssistSink`，事件名固定 `assist.event`，`data` 内 `type` 与载荷同层）：

| data.type | 载荷 | 时机 |
|---|---|---|
| `assist.start` | `{ type, action }` | 请求受理 |
| `assist.delta` | `{ type, text }` | 增量文本，按 120 ms 合帧推送 |
| `assist.end` | `{ type, action, text, durationMs }` | 终态，`text` 为全文，必达 |
| `assist.error` | `{ type, code, message }` | 异常，必达 |

不发送心跳与 `retry`；`assist.end` 与 `assist.error` 必有且仅有其一。

## 16. 长期记忆

记忆为用户私有资产，按归属用户隔离，登录即可用，不额外挂权限点。

| 方法 | 路径 | 入参 → 出参 | 幂等 |
|---|---|---|---|
| GET | `/memory/page` | `MemoryREQ`（`page` `size` `keyword` `category`） → `PageResult<MemoryVO>` | — |
| POST | `/memory/create` | `MemoryREQ` → `R<String>` | `@Idempotent("memory", ttl = 5)` |
| POST | `/memory/update` | `MemoryREQ` → `R<Void>` | `@Idempotent("memory", ttl = 5)` |
| POST | `/memory/delete/{memoryId}` | — → `R<Void>` | `@Idempotent("memory", ttl = 5)` |

- 分页按权重与更新时间倒序；`MemoryVO` 为 `memoryId` / `memoryKey` / `memoryValue` / `category` / `weight` / `hitCount` / `updatedAt` / `createdAt`。
- 字段边界：`memoryKey` ≤128 字符，`memoryValue` ≤2000 字符，`category` ∈ `fact` / `preference` / `constraint` / `profile`（非法或缺失回落 `fact`），`weight` 0~9（缺失为 0）。键或内容为空抛 `MEMORY_EMPTY` `8902`。
- 同 key 幂等：`create` 对已存在的键覆盖旧值，不新增行。
- `update` 只改内容与维度，键不可改（改键等于换一条记忆）；`delete` 为逻辑删除；`(memoryId, user_id)` 查不到抛 `MEMORY_NOT_FOUND` `8901`。
- **容量与淘汰**：单用户上限 200 条。写入前先腾位——按权重最低、最久未更新淘汰，最多尝试 5 次；仍无法腾出空间才抛 `MEMORY_LIMIT_REACHED` `8903`，因此正常写入不会因超限失败。

## 17. 运行派生关系

`wb_run.relation` 取 `original` / `child`；`child` 由 `task_delegate` 的派生运行使用，`retry` / `fork` / `recovery` 仍为预留（见 `specs/02`）。

## 18. 分页与列表响应

分页参数命名与位置见 `AGENTS.md §4`。请求侧归一化规则（`common.PageREQ`）：

| 参数 | 类型 | 缺省 | 边界 |
|---|---|---|---|
| `page` | int | 1 | < 1 按 1 处理 |
| `size` | int | 20 | < 1 按 20 处理，上限 200 |
| `keyword` | string | — | 模糊检索关键字，按各接口声明的列生效 |

响应侧 `data` 统一为 `PageResult<T>`：`{ "list": [], "total": 0, "page": 1, "size": 20 }`。部分接口（`/log/search`）在上述四字段之外附加视角标识 `scope`。

## 19. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 只用 GET / POST | 兼容所有代理与老旧网关，前端拦截器只需处理两种方法 | 语义化弱于 REST 全方法 |
| 统一信封 + HTTP 状态码双轨 | 前端可按 `code` 分支，网关与监控可按状态码统计 | 两处语义需保持绑定一致 |
| SSE 而非 WebSocket | 单向流天然适配，浏览器自动重连，穿透代理好 | 无法实现客户端主动推消息；连接数受浏览器同域上限约束 |
| 动作式路径（`/delete/{id}`） | 与「只用 GET/POST」自洽，权限点可直接映射 URL | 资源语义弱化，靠本文对齐 |
