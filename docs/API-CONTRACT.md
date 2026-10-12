# API-CONTRACT

REST 与 SSE 接口契约。所有响应统一信封，所有写操作返回受影响资源。

---

## 1. 定位

前端唯一数据入口。

**方法约定**：只允许 `GET` 与 `POST`。GET 用于查询（无副作用），POST 用于新建、修改、删除、动作触发。

**路径约定**：路径参数一律放末尾，动作词在前，资源 ID 在后。

```
✅ GET  /api/v1/user/detail/{userId}
✅ POST /api/v1/user/delete/{userId}
❌ GET  /api/v1/user/{userId}
❌ POST /api/v1/user/{userId}/delete
```

**命名约定**：`page` 分页查询、`list` 全量列表、`tree` 树结构、`detail` 详情、`create` 新建、`update` 修改、`delete` 删除、`toggle` 启停、`grant` 授权。

## 2. 统一信封

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
| `code` | `"0"` 成功，非 `"0"` 为错误码（String 类型，与 HTTP 状态码的 int 区分），见 [ERROR-CODES.md](ERROR-CODES.md) |
| `message` | 面向人的提示，失败时可直接展示 |
| `data` | 业务数据，分页接口为 `{ "list": [], "total": 0, "page": 1, "size": 20 }` |
| `requestId` | 链路标识，与日志 `trace_id` 一致 |

HTTP 状态码：成功 200，参数错误 400，未认证 401，无权限 403，资源不存在 404，状态冲突 / 重复提交 409，请求体过大 413，限流 429，服务端错误 500，模型调用失败 502，中间件不可用 503，模型 / 工具超时 504。

## 3. 认证

登录态由 Sa-Token 管理，令牌放 `Authorization` 头，值为令牌本身（不加 `Bearer` 前缀），会话存 Redis，默认有效期 2 小时。

**密码字段一律为密文**：前端先取公钥，用 RSA/PKCS#1 v1.5 加密（JSEncrypt），密文 Base64 放入密码字段，同时回传公钥标识。覆盖四个入口：登录（`password` + `keyId`）、自助改密（`oldPassword` / `newPassword` + `keyId`）、管理员建用户（`password` + `passwordKeyId`）、管理员重置密码（`newPassword` + `keyId`）。明文密码不出浏览器。

密文字段**不做长度校验**（RSA-2048 密文 Base64 后 344 字符），口令强度在服务端解密后的明文上判定（≥8 位且含字母与数字，否则 `PASSWORD_WEAK`）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/auth/public-key` | 取登录加密公钥，免登录 |
| POST | `/api/v1/auth/login` | 登录，入参 `username` `password`(密文) `keyId` |
| POST | `/api/v1/auth/logout` | 登出 |
| GET | `/api/v1/auth/current` | 当前用户：资料 + 角色码 + 权限标识集合 + 菜单树 |
| POST | `/api/v1/auth/password` | 修改自己的密码，入参 `oldPassword` `newPassword`（均为密文）`keyId` |

公钥响应 `data`：

```json
{ "keyId": "k_01M4..", "algorithm": "RSA/PKCS1Padding", "publicKey": "MIIBIjANBgkq...", "expiresAt": 1710000000000 }
```

登录响应 `data`：

```json
{
  "token": "xxxx",
  "user": {
    "userId": "USER_..", "username": "admin", "nickname": "管理员", "avatarUrl": "",
    "email": "", "state": "normal", "roleIds": ["ROLE_.."], "lastLoginAt": 1710000000000, "createdAt": 1700000000000
  },
  "roleCodes": ["super_admin"],
  "perms": ["system:user:list", "system:user:create"],
  "pwdResetRequired": false,
  "menus": [
    {
      "menuId": "MENU_..", "parentId": "MENU_..", "menuName": "menu.dashboard", "menuType": "menu",
      "routePath": "/dashboard", "component": "Dashboard", "icon": "dashboard", "permCode": null,
      "sortNo": 10, "visible": true, "children": []
    }
  ]
}
```

`menus` 是完整菜单树（`dir` / `menu` 两级，按钮权限点不进树），前端按 `component` 映射组件并注册路由。

`pwdResetRequired = true` 时前端必须跳到强制改密页；后端同时只放行 `/auth/current` 与 `/auth/password`，其余接口返回 `PASSWORD_RESET_REQUIRED`（2006）。

失败计数：同一用户名连续 5 次凭据错误锁定 10 分钟，返回 `ACCOUNT_LOCKED`（2007）；登录接口另有按 IP 的固定窗口限流。

## 4. 用户

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/user/page` | 分页，支持 `keyword` `state` |
| GET | `/api/v1/user/detail/{userId}` | 详情（含角色 ID 列表） |
| POST | `/api/v1/user/create` | 新建，`password`(密文) `passwordKeyId` 必填；新账号首登强制改密 |
| POST | `/api/v1/user/update` | 修改资料与角色（不提交密码字段），不能改自己的角色 |
| POST | `/api/v1/user/toggle/{userId}` | 启用 / 停用，不能操作自己 |
| POST | `/api/v1/user/reset-password` | 重置密码，`newPassword`(密文) `keyId`；重置后该用户首登仍需改密 |
| POST | `/api/v1/user/delete/{userId}` | 逻辑删除，不能删除自己 |

## 5. 角色

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/role/page` | 分页 |
| GET | `/api/v1/role/list` | 全量列表（下拉用） |
| GET | `/api/v1/role/detail/{roleId}` | 详情 |
| POST | `/api/v1/role/create` | 新建 |
| POST | `/api/v1/role/update` | 修改 |
| POST | `/api/v1/role/grant` | 角色授权菜单，入参 `roleId` `menuIds` |
| GET | `/api/v1/role/menu-ids/{roleId}` | 角色已授权菜单 ID 列表 |
| POST | `/api/v1/role/delete/{roleId}` | 逻辑删除 |

内置角色（`super_admin` / `admin` / `user`）不可删除、不可改码。

## 6. 菜单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/menu/tree` | 全量菜单树（管理页用） |
| GET | `/api/v1/menu/user-tree` | 当前用户可见菜单树（路由用） |
| GET | `/api/v1/menu/detail/{menuId}` | 详情 |
| POST | `/api/v1/menu/create` | 新建 |
| POST | `/api/v1/menu/update` | 修改 |
| POST | `/api/v1/menu/delete/{menuId}` | 删除（含子级校验） |

## 7. 会话

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/session/page` | 分页，支持 `keyword` |
| GET | `/api/v1/session/detail/{sessionId}` | 详情 |
| POST | `/api/v1/session/create` | 创建 |
| POST | `/api/v1/session/update` | 改标题 / 模型 / 系统提示词 |
| POST | `/api/v1/session/delete/{sessionId}` | 逻辑删除 |
| GET | `/api/v1/session/messages/{sessionId}` | 消息列表 |

会话接口登录即可用：会话按 `user_id` 归属，用户只见自己的会话。会话与运行写接口均加 `@Idempotent`。

## 8. 对话与运行

### 8.1 流式对话（SSE）

```
POST /api/v1/chat/stream
Content-Type: application/json

{ "sessionId": "SESS_...", "content": "帮我分析这份数据" }
```

响应 `Content-Type: text/event-stream`。SSE 只用单一通道 `event: run.event`，具体事件类型在 `data.type` 中区分；`id:` 字段为事件序号 `seq`，客户端断线重连时可携带 `Last-Event-ID` 从断点续拉。事件类型全集：

| data.type | 载荷（除公共字段外） | 时机 |
|---|---|---|
| `run.start` | `{ runId, sessionId, modelName }` | 运行创建 |
| `thinking.delta` | `{ seq, delta }` | 思考增量 |
| `text.delta` | `{ seq, delta }` | 正文增量 |
| `tool.call` | `{ seq, toolName, arguments }` | 工具调用开始 |
| `tool.result` | `{ seq, toolName, output, isError }` | 工具调用结束 |
| `model.call` | `{ seq, durationMs, tokens }` | 模型调用结束 |
| `run.result` | `{ runId, text, usage }` | 运行产出的完整正文，单帧 |
| `run.exceed` | `{ runId, maxIters }` | 达到最大迭代上限 |
| `run.end` | `{ runId, status, stopReason, usage, durationMs }` | 终态，必达 |
| `run.error` | `{ code, message }` | 异常，必达 |
| `custom` | `{ payload }` | 未归类事件的兜底 |

公共字段：`seq`、`runId`、`sessionId`、`occurredAt`。

**规则**：`run.end` 与 `run.error` 必有且仅有其一，且都会先落库再推送（`seq` 单调递增），因此回放与实时看到同一序列；客户端收到后关闭连接。心跳为注释行 `: ping`，15 秒一次。单帧增量（`text.delta` / `thinking.delta`）超过 4KB 会被截断，完整正文以 `run.result` 为准；推送侧会对批内连续增量合帧（`textDelta` 拼接、`seq` 取最后一条）。

### 8.2 运行

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/run/detail/{runId}` | 运行详情，按 (runId, userId) 校验归属 |
| GET | `/api/v1/run/events/{runId}` | 事件列表，按 `seq` 升序，支持 `afterSeq` 增量拉取，单次上限 500 条 |
| GET | `/api/v1/run/page` | 运行列表，按 `sessionId` 过滤且仅返回当前用户 |
| POST | `/api/v1/run/abort/{runId}` | 中断运行，归属校验同上 |

**归属**：运行详情、事件回放、中断一律先按 `(runId, user_id)` 查询，查不到直接返回 `RESOURCE_NOT_FOUND`，不泄露他人资源是否存在。

**并发**：同一会话同时只允许一个运行，`lw:lock:run:{sessionId}` 的 TTL 不小于 SSE 连接存活上限，长任务期间不会因锁过期而并发起第二个运行。

## 9. 工具

工具为平台级定义（`wb_tool`），启停全局生效。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tool/list` | 工具列表，权限 `wb:tool:list` |
| POST | `/api/v1/tool/toggle/{toolId}` | 启停，权限 `wb:tool:toggle` |
| POST | `/api/v1/tool/invoke/{toolName}` | 页面手工执行，用于调试，权限 `wb:tool:invoke` |

`/tool/invoke/{toolName}` 请求体 `{ "args": { ... }, "confirmed": false, "timeoutSeconds": 120 }`：`safe` 工具直接执行；`confirm` / `danger` 工具必须显式携带 `"confirmed": true`，否则返回 409 `TOOL_CONFIRM_REQUIRED`（页面由确认勾选承接）。工具被停用时返回 `TOOL_DISABLED`。Agent 自主调用不走确认闸门，统一由平台级启停开关控制。接口加 `@Idempotent`，超时默认 120 秒，可用 `timeoutSeconds` 覆盖。

限流：登录接口按 IP 每分钟 10 次，`/chat/stream` 按用户每分钟 20 次（`@RateLimit` 固定窗口），超限返回 429 `RATE_LIMITED`。

## 10. 技能

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/skill/page` | 分页，权限 `wb:skill:list` |
| POST | `/api/v1/skill/create` | 新建，权限 `wb:skill:create` |
| POST | `/api/v1/skill/update` | 全量更新，权限 `wb:skill:update` |
| POST | `/api/v1/skill/delete/{skillId}` | 删除，权限 `wb:skill:delete` |

## 11. 文件

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/file/page` | 分页，支持 `sessionId` `category` |
| POST | `/api/v1/file/upload` | multipart 上传，单文件 ≤ 100MB |
| GET | `/api/v1/file/url/{fileId}` | 取预签名下载地址，有效期 15 分钟 |
| POST | `/api/v1/file/delete/{fileId}` | 删除对象与元数据，权限 `wb:file:delete` |

`file/page`、`file/upload`、`file/url` 登录即可用（按 `user_id` 归属）。

## 12. 知识库

知识库为用户私有资源（`user_id` 归属），原件进 MinIO，分块进 Manticore。权限点前缀 `wb:kb:`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/kb/page` | 知识库分页，支持 `keyword`，权限 `wb:kb:list` |
| GET | `/api/v1/kb/detail/{kbId}` | 详情，按 (kbId, user) 校验归属 |
| POST | `/api/v1/kb/create` | 新建，权限 `wb:kb:create`，`@Idempotent` |
| POST | `/api/v1/kb/update` | 改名 / 描述，权限 `wb:kb:update`，`@Idempotent` |
| POST | `/api/v1/kb/delete/{kbId}` | 删除，级联清文档与分块索引，权限 `wb:kb:delete`，`@Idempotent` |
| GET | `/api/v1/kb/doc/page` | 文档分页，参数 `kbId` `keyword` `state`，权限 `wb:kb:list` |
| POST | `/api/v1/kb/doc/upload/{kbId}` | multipart 上传，权限 `wb:kb:upload`，`@Idempotent` |
| POST | `/api/v1/kb/doc/delete/{docId}` | 删文档（MinIO 原件 + 分块索引），权限 `wb:kb:delete`，`@Idempotent` |
| POST | `/api/v1/kb/doc/reparse/{docId}` | 重解析失败文档，权限 `wb:kb:upload`，`@Idempotent` |
| GET | `/api/v1/kb/search` | 分块检索，参数 `kbId`(可选) `keyword` `page` `size`，按相关度排序，权限 `wb:kb:search` |

约定：

- 支持格式：`.txt` `.md` `.csv` `.json` `.html` `.xml` `.log`（UTF-8 文本）、`.docx`、`.pdf`；单文件 ≤ 20MB。
- 上传立即返回 `KbDocVO(state=pending)`，解析经 MQ 异步执行，前端轮询文档状态到 `ready` / `failed`；失败可 `reparse` 重放。
- `kb/search` 返回 `PageResult<KbChunkVO>`：`chunkId` `kbId` `docId` `docName` `seq` `content` `userId` `score` `createdAt`；高亮由前端按 `keyword` 分段渲染，后端只回原文，避免注入富文本。
- Agent 侧工具 `kb_search` 由平台工具启停控制（`wb_tool` 种子），对话中模型按需检索当前用户的知识库。

## 13. 模型供应方

供应方为平台级定义（`wb_provider`），管理动作需供应方权限点，列表登录即可用（密钥脱敏）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/provider/list` | 列表，登录即可用，密钥字段脱敏 |
| POST | `/api/v1/provider/create` | 新建，权限 `wb:provider:create` |
| POST | `/api/v1/provider/update` | 更新，权限 `wb:provider:update` |
| POST | `/api/v1/provider/delete/{providerId}` | 删除，权限 `wb:provider:delete` |
| POST | `/api/v1/provider/test/{providerId}` | 连通性测试，权限 `wb:provider:update` |

供应方配置变更后清除 `lw:cache:provider:{code}` 缓存，即时生效。

## 14. 日志与监控

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/log/search` | Manticore 检索，参数 `keyword` `field` `level` `logger` `traceId` `from` `to` `page` `size`，返回 `PageResult<LogDocVO>`，权限 `monitor:log:list` |
| GET | `/api/v1/log/stats` | 分布概览：级别计数 + 按小时时间直方图，参数 `from` `to`，权限 `monitor:log:list` |
| GET | `/api/v1/monitor/health` | 各组件健康状态，权限 `monitor:health:list` |
| GET | `/api/v1/monitor/overview` | 仪表盘汇总指标，权限 `monitor:health:list` |
| GET | `/api/v1/runtime/python` | Python 运行时探测结果，权限 `monitor:health:list` |
| POST | `/api/v1/runtime/python/reprobe` | 重新探测 Python 运行时，权限 `monitor:python:reprobe` |

`log/search` 参数语义：

| 参数 | 说明 |
|---|---|
| `keyword` | 关键字，全文匹配列由 `field` 决定，默认 `message` |
| `field` | 关键字匹配列：`message`（默认，ICU 中文分词全文）/ `logger`（类名，精确前缀匹配）/ `traceId`（精确匹配） |
| `level` | 级别等值：`INFO` / `WARN` / `ERROR` |
| `logger` | Logger 名等值（如 `com.liteworkbench.service.RunService`） |
| `traceId` | 链路标识等值，用于把一次请求的全部日志串成时间线 |
| `from` / `to` | 时间范围，毫秒时间戳；两者都传才生效 |
| `page` / `size` | 分页，按 `ts` 倒序 |

`log/stats` 返回 `{ levels: [{level, count}], histogram: [{hour, count}] }`：`histogram` 按小时粒度覆盖查询区间，上限 168 个桶（7 天），更早的区间按天聚合由前端决定是否展示。

`monitor/overview` 返回：`sessionCount` / `runCount` / `successRunCount` / `todayRunCount` / `totalTokens` / `runTrend` / `tokenTrend` / `toolDistribution`。

## 15. 创作文档

文档为用户私有资源，权限点前缀 `wb:doc:`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/doc/page` | 分页，参数 `keyword` `category` `state`，权限 `wb:doc:list` |
| GET | `/api/v1/doc/detail/{docId}` | 详情（含正文），按 (docId, user) 校验归属 |
| POST | `/api/v1/doc/create` | 新建，权限 `wb:doc:create`，`@Idempotent` |
| POST | `/api/v1/doc/update` | 保存正文（写版本 + 同步索引），权限 `wb:doc:update`，`@Idempotent` |
| POST | `/api/v1/doc/delete/{docId}` | 删除（删版本 + 索引文档），权限 `wb:doc:delete`，`@Idempotent` |
| GET | `/api/v1/doc/search` | 全文检索，参数 `keyword` `category` `page` `size`，走 Manticore `lw_doc`，权限 `wb:doc:search` |
| GET | `/api/v1/doc/version/page` | 版本列表，参数 `docId`，权限 `wb:doc:list` |
| GET | `/api/v1/doc/version/detail/{versionId}` | 版本快照，权限 `wb:doc:list` |
| POST | `/api/v1/doc/version/restore/{versionId}` | 回滚到指定版本，权限 `wb:doc:update`，`@Idempotent` |
| POST | `/api/v1/doc/assist` | AI 辅助写作（续写 / 润色 / 摘要 / 扩写 / 翻译），非流式返回文本 |

约定：

- `doc/update` 每次调用：`version_no + 1`、写一条 `wb_doc_version` 快照、覆盖 `lw_doc` 文档，三者在同一事务内，索引写失败只告警不回滚。
- `doc/assist` 入参：`docId`(可选) `action` `selection`(选中的正文) `instruction`(补充要求) `kbId`(可选引用知识库)；返回 `{ action, text, durationMs }`。内部经 `ModelCaller` 同步调用模型，不建 Run（不产生事件流），结果由前端决定插入或替换选区。
- 正文上限 200000 字，超出抛 `DOC_CONTENT_TOO_LONG`。

## 16. 提示词

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/prompt/page` | 分页（含内置），参数 `keyword` `category`，权限 `wb:prompt:list` |
| GET | `/api/v1/prompt/detail/{promptId}` | 详情 |
| POST | `/api/v1/prompt/create` | 新建，权限 `wb:prompt:create`，`@Idempotent` |
| POST | `/api/v1/prompt/update` | 更新（内置只读），权限 `wb:prompt:update`，`@Idempotent` |
| POST | `/api/v1/prompt/delete/{promptId}` | 删除（内置不可删），权限 `wb:prompt:delete`，`@Idempotent` |
| POST | `/api/v1/prompt/copy/{promptId}` | 复制为自有模板，权限 `wb:prompt:create`，`@Idempotent` |
| POST | `/api/v1/prompt/render` | 变量渲染：`{promptId, variables}` → 返回渲染后文本，权限 `wb:prompt:list` |

约定：

- 内置模板 `user_id = 'builtin'`、`builtin = true`，列表对所有用户可见，修改与删除拒绝。
- `variables` 为 `[{name,label,defaultValue}]`，渲染用 `{{name}}` 占位替换；缺失且无默认值抛 `PROMPT_VARIABLE_MISSING`。
- 渲染与"发送到工作台"都在前端完成后续动作，后端只负责产出文本。

## 17. 预留结构

运行派生关系（`wb_run.relation` / `parent_run_id`）为预留结构，本期不暴露接口。

## 18. 分页请求

GET 分页统一携带：

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `page` | int | 1 | 页码，从 1 开始 |
| `size` | int | 20 | 每页条数，上限 200 |
| `keyword` | string | — | 模糊检索关键字 |

## 20. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 只用 GET / POST | 兼容所有代理与老旧网关，前端拦截器只需处理两种方法 | 语义化弱于 REST 全方法 |
| 路径参数放末尾 | 路由规则简单统一，便于网关与日志统计 | 与部分 REST 教程习惯相反 |
| 统一信封 + HTTP 状态码双轨 | 前端可按 code 分支，网关可按状态码统计 | 两处语义需保持一致 |
| SSE 而非 WebSocket | 单向流天然适配，自动重连，穿透代理好 | 无法实现客户端主动推消息 |
| 动作式路径（`/delete/{id}`） | 与"只用 GET/POST"自洽，权限点可直接映射 URL | 资源语义弱化，靠文档对齐 |
| AI 辅助写作走同步接口不建 Run | 结果短、需即时插入编辑器，落事件流反而增加回收成本 | 无法流式输出，长文辅助需等待 |
