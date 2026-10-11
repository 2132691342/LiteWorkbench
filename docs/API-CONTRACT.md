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

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/login` | 登录，入参 `username` `password` |
| POST | `/api/v1/auth/logout` | 登出 |
| GET | `/api/v1/auth/current` | 当前用户：资料 + 角色码 + 权限标识集合 + 菜单树 |
| POST | `/api/v1/auth/password` | 修改自己的密码 |

登录响应 `data`：

```json
{
  "token": "xxxx",
  "user": { "userId": "USER_..", "username": "admin", "nickname": "管理员", "avatarUrl": "" },
  "roleCodes": ["super_admin"],
  "perms": ["system:user:list", "system:user:create"],
  "menus": [ { "menuId": "MENU_..", "menuName": "system.menu.dashboard", "routePath": "/dashboard" } ]
}
```

## 4. 用户

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/user/page` | 分页，支持 `keyword` `state` |
| GET | `/api/v1/user/detail/{userId}` | 详情（含角色 ID 列表） |
| POST | `/api/v1/user/create` | 新建 |
| POST | `/api/v1/user/update` | 修改资料与角色 |
| POST | `/api/v1/user/toggle/{userId}` | 启用 / 停用 |
| POST | `/api/v1/user/reset-password` | 重置密码 |
| POST | `/api/v1/user/delete/{userId}` | 逻辑删除 |

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
| `error` | `{ code, message }` | 异常，必达 |
| `custom` | `{ payload }` | 未归类事件的兜底 |

公共字段：`seq`、`runId`、`sessionId`、`occurredAt`。

**规则**：`run.end` 与 `error` 必有且仅有其一；客户端收到后关闭连接。心跳为注释行 `: ping`，15 秒一次。单帧增量（`text.delta` / `thinking.delta`）超过 4KB 会被截断，完整正文以 `run.result` 为准；推送侧会对批内连续增量合帧（`textDelta` 拼接、`seq` 取最后一条）。

### 8.2 运行

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/run/detail/{runId}` | 运行详情 |
| GET | `/api/v1/run/events/{runId}` | 事件列表，按 `seq` 升序，支持 `afterSeq` 增量拉取，单次上限 500 条 |
| GET | `/api/v1/run/page` | 按 `sessionId` 查运行列表 |
| POST | `/api/v1/run/abort/{runId}` | 中断运行 |

## 9. 工具

工具为平台级定义（`wb_tool`），启停全局生效。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tool/list` | 工具列表，权限 `wb:tool:list` |
| POST | `/api/v1/tool/toggle/{toolId}` | 启停，权限 `wb:tool:toggle` |
| POST | `/api/v1/tool/invoke/{toolName}` | 页面手工执行，用于调试，权限 `wb:tool:invoke` |

`/tool/invoke/{toolName}` 请求体 `{ "args": { ... }, "confirmed": false }`：`safe` 工具直接执行；`confirm` / `danger` 工具必须显式携带 `"confirmed": true`，否则返回 409 `TOOL_CONFIRM_REQUIRED`（页面由确认勾选承接）。Agent 自主调用不走该闸门，统一由平台级启停开关控制。接口加 `@Idempotent`，超时与参数校验同 Agent 内执行一致。

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

## 12. 模型供应方

供应方为平台级定义（`wb_provider`），管理动作需供应方权限点，列表登录即可用（密钥脱敏）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/provider/list` | 列表，登录即可用，密钥字段脱敏 |
| POST | `/api/v1/provider/create` | 新建，权限 `wb:provider:create` |
| POST | `/api/v1/provider/update` | 更新，权限 `wb:provider:update` |
| POST | `/api/v1/provider/delete/{providerId}` | 删除，权限 `wb:provider:delete` |
| POST | `/api/v1/provider/test/{providerId}` | 连通性测试，权限 `wb:provider:update` |

供应方配置变更后清除 `lw:cache:provider:{code}` 缓存，即时生效。

## 13. 日志与监控

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/log/search` | Redisearch 检索，参数 `keyword` `level` `from` `to` `page` `size`，权限 `monitor:log:list` |
| GET | `/api/v1/monitor/health` | 各组件健康状态，权限 `monitor:health:list` |
| GET | `/api/v1/monitor/overview` | 仪表盘汇总指标，权限 `monitor:health:list` |
| GET | `/api/v1/runtime/python` | Python 运行时探测结果，权限 `monitor:health:list` |
| POST | `/api/v1/runtime/python/reprobe` | 重新探测 Python 运行时，权限 `monitor:python:reprobe` |

`monitor/overview` 返回：`sessionCount` / `runCount` / `successRunCount` / `todayRunCount` / `totalTokens` / `runTrend` / `tokenTrend` / `toolDistribution`。

## 14. 预留结构

异步任务（`wb_task`）与运行派生关系（`wb_run.relation` / `parent_run_id`）为预留结构，本期不暴露接口。

## 15. 分页请求

GET 分页统一携带：

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `page` | int | 1 | 页码，从 1 开始 |
| `size` | int | 20 | 每页条数，上限 200 |
| `keyword` | string | — | 模糊检索关键字 |

## 16. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 只用 GET / POST | 兼容所有代理与老旧网关，前端拦截器只需处理两种方法 | 语义化弱于 REST 全方法 |
| 路径参数放末尾 | 路由规则简单统一，便于网关与日志统计 | 与部分 REST 教程习惯相反 |
| 统一信封 + HTTP 状态码双轨 | 前端可按 code 分支，网关可按状态码统计 | 两处语义需保持一致 |
| SSE 而非 WebSocket | 单向流天然适配，自动重连，穿透代理好 | 无法实现客户端主动推消息 |
| 动作式路径（`/delete/{id}`） | 与"只用 GET/POST"自洽，权限点可直接映射 URL | 资源语义弱化，靠文档对齐 |
