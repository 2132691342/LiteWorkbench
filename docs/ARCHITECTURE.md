# ARCHITECTURE

分层设计、模块职责、关键流程。

---

## 1. 定位

后端是一个阻塞式 Spring MVC 应用，前端是单页应用。两者通过 REST + SSE 通信，无网关、无服务发现、无多实例。

整体职责划分：**Agent 负责执行，工作台负责观测与控制**。Agent 每次执行产生一条 Run，Run 由有序事件流构成，事件流是唯一事实来源。

认证与数据归属：登录态由 Sa-Token 管理（存 Redis），用户身份进入请求上下文后，用户拥有的数据访问显式携带 `user_id`。

## 2. 分层

```
┌─────────────────────────────────────────────────────┐
│ api        XxxController：校验入参 → 调 service → R  │
│            Sa-Token 注解在此层做登录与权限校验        │
├─────────────────────────────────────────────────────┤
│ service    XxxService：编排、事务、日志、编排 Agent   │
│            用户身份取 LoginContext，归属见 4.3        │
├─────────────────────────────────────────────────────┤
│ mapper     XxxMapper：Postgres / Redisearch /        │
│                       Redis / MinIO                  │
├─────────────────────────────────────────────────────┤
│ domain     entity / dto / vo / constant                  │
└─────────────────────────────────────────────────────┘
  侧翼：config  anno  aspect  mq  agent  runtime
        observability  common```

| 层 | 职责 | 禁止 |
|---|---|---|
| `api` | 参数校验、鉴权注解、响应封装、SSE 通道建立 | 业务逻辑、直接访问 mapper |
| `service` | 流程编排、事务边界、领域规则、日志打点 | 引用 HTTP 类型、写 SQL |
| `mapper` | 数据访问，四种数据源各一类 | 业务判断 |
| `domain` | 实体与常量，零依赖 | 依赖任何上层 |
| `anno` / `aspect` | 幂等、审计等横切逻辑 | 持有业务状态 |

## 3. 模块职责

| 包 | 模块 | 职责 |
|---|---|---|
| `service` | `AuthService` | 登录、登出、当前用户信息组装 |
| `service` | `UserService` / `RoleService` / `MenuService` | RBAC 主数据维护与授权关系 |
| `service` | `RunService` | 会话 → 运行 → 事件流主链路编排：SSE、攒批落库、中断、回放 |
| `service` | `SessionService` / `ProviderService` / `SkillService` / `FileService` / `ToolService` / `SettingService` / `MonitorService` | 会话、供应方、技能、文件、工具、设置与监控的业务编排 |
| `agent` | `AgentScopeAgentRuntime` | 每次运行装配 ReActAgent（复用缓存的 Model 实例），注入工具与技能 |
| `agent` | `AgentEventAdapter` | 把 AgentScope `AgentEvent` 归一化为工作台 `RunEventDTO` |
| `agent` | `ModelFactory` | 按供应方构建并缓存模型实例，解密密钥，三级模型名兜底 |
| `agent` | `WorkbenchTools` | 内置工具集：`python_exec`、`file_read`、`file_write`、`http_get` |
| `runtime` | `LocalPythonRuntime` | Python 解释器探测、脚本落盘、进程执行、超时与进程树终止 |
| `mapper` | `LogSearchMapper` | Redisearch 索引创建、写入、检索 |
| `observability` | `RedisearchLogAppender` | Logback 扩展，异步把日志送达 Redisearch |
| `service` | `MonitorService` | 各中间件健康探测与仪表盘汇总统计 |
| `mq` | `LogProducer` / `LogConsumer` | 日志投递削峰，失败降级为本地文件 |
| `aspect` | `IdempotentAspect` | `@Idempotent` 写接口防重提交 |
| `common` | `LoginContext` / `StpInterfaceImpl` | 请求线程的用户身份载体，权限点解析 |

## 4. 认证与数据归属

### 4.1 登录链路

```
POST /api/v1/auth/login  { username, password }
  ├─ 1 按 username 查用户，校验存在且未停用          UserMapper
  ├─ 2 校验密码（SHA-256 + 盐）与账号状态            AuthService
  ├─ 3 StpUtil.login(userId)，会话写入 Redis         Sa-Token
  ├─ 4 组装 LoginUser（userId + roleCodes + perms）缓存进 TokenSession
  └─ 5 返回 token 与用户信息（含菜单权限标识）
```

`LoginUser` 结构：

| 字段 | 说明 |
|---|---|
| `userId` / `username` / `nickname` | 用户身份 |
| `roleCodes` | 角色码集合 |
| `perms` | 权限标识集合，形如 `system:user:create` |

### 4.2 请求鉴权

```
请求进入
  ├─ TraceFilter：注入 traceId
  ├─ SaToken 拦截器：
  │    ├─ 未登录 → 401 UNAUTHORIZED
  │    ├─ @SaCheckPermission 不通过 → 403 FORBIDDEN
  │    └─ 通过 → 把 LoginUser 放入 LoginContext
  ├─ Controller → Service（userId 取 LoginContext）
  └─ finally 清理 LoginContext 与 MDC
```

### 4.3 数据归属

- 用户拥有的业务表全部带 `user_id`，Mapper 条件显式写入，不做全局拦截器自动拼接。
- 单资源访问按 `(主键, user_id)` 查询，查不到直接 404，不泄露他人资源是否存在。
- 平台级表（`sys_menu`、`sys_user_role`、`sys_role_menu`、`wb_tool`、`wb_provider`）由管理角色的权限点控制读写。

## 5. 关键流程

### 5.1 一次对话的完整链路

```
POST /api/v1/chat/stream
  │
  ├─ 1 校验会话归属（user_id），获取会话级运行锁   SessionService
  ├─ 2 创建 Run（queued）+ 写入用户消息 wb_message(user)      RunService
  ├─ 3 建立 SseEmitter                      RunController
  ├─ 4 构建 ReActAgent（Model 实例按 provider+model 缓存复用）   AgentScopeAgentRuntime
  ├─ 5 组装历史消息（wb_message 最近 20 条）→ 订阅 agent.streamEvents
  │     ├─ 归一化事件 → seq 递增
  │     ├─ 批量攒批写入 wb_event
  │     └─ 同步推送 SSE
  ├─ 6 结束：更新 Run 状态与用量 + 写入助手消息 wb_message(assistant)
  └─ 7 异常/中断：标记 aborted 并推送终态，finally 释放会话锁
```

### 5.2 事件归一

AgentScope 的 `AgentEvent` 是开放结构，不做第二套记账，只在出口做一次归一：

| AgentScope 事件 | 工作台事件类型 | blockType |
|---|---|---|
| `AgentStartEvent` | `run.start` | — |
| `TextBlockDeltaEvent` | `text.delta` | `text` |
| `ThinkingBlockDeltaEvent` | `thinking.delta` | `thinking` |
| `ToolCallStartEvent` | `tool.call`（start 阶段） | `tool_call` |
| `ToolCallEndEvent` | `tool.call`（end 阶段） | `tool_call` |
| `ToolResultEndEvent` | `tool.result` | `tool_result` |
| `ModelCallStartEvent` / `ModelCallEndEvent` | `model.call` | — |
| `AgentResultEvent` | `run.result` | `text` |
| `ExceedMaxItersEvent` | `run.exceed` | — |
| `AgentEndEvent` | `run.end` | — |
| 其余未列出事件 | `custom` | — |

`tool.call` 以载荷中的 `phase: start / end` 区分起止；归一在 `AgentEventAdapter` 一处完成，前端只消费工作台事件，不感知 SDK 版本变化。

三条硬规则：

| 规则 | 说明 |
|---|---|
| 推送合帧 | 文本增量逐条推会把连接线程打满，批内连续**同类型**增量（`text.delta` / `thinking.delta`）合并为一帧后推送（`textDelta` 拼接、`seq` 取批内最后一条）；类型切换不合并；落库仍按 100 条 / 200ms 保持逐条 |
| 单帧截断 | 单条增量超 4KB 截断，完整正文以 `run.result` 为准 |
| 中断优先 | `run.result`（completed）与中断标记同时到达时，**中断裁决优先**，终态记 `aborted` |
| 用量单一入账 | Token 用量只从 `run.result` 一处累加，`model.call` 与 `run.end` 不重复计数 |

### 5.3 中断

```
POST /api/v1/run/abort/{runId}
  → 校验运行归属（user_id）与状态
  → Redis 写 lw:run:{runId}:abort = 1（跨实例与孤儿运行的中断凭据）
  → 有活动实例：释放订阅（Reactor dispose → doOnCancel）→ 终态记 aborted
  → 无活动实例（进程重启后的孤儿运行）：直接结算为 aborted
  → 推送 run.end
```

中断靠释放订阅 + 标记位双路径，不做线程中断，避免半写状态。

### 5.4 幂等

```
POST 写接口（带 @Idempotent）
  ├─ 1 取 LoginContext 的 userId 与请求体摘要
  ├─ 2 SETNX lw:idem:{prefix}:{userId}:{digest}，TTL 默认 10s
  ├─ 3 命中 → 响应头 X-Lw-Idem-Key 回传占位键 + 抛 REPEAT_SUBMIT（2501）
  └─ 4 未命中 → 放行，正常处理
```

### 5.5 日志汇聚

```
业务代码 log.info
  → Logback AsyncAppender
      ├─ RollingFileAppender（按天 + 按级别）
      └─ RedisearchLogAppender → MQ → LogConsumer → Redisearch
```

MQ 不可用时降级为跳过索引，不阻塞主流程。

## 6. 并发与性能

| 点 | 做法 |
|---|---|
| Agent 实例 | Model 按 `provider + model` 缓存复用；ReActAgent 每次运行构建（构建成本低，天然无跨会话状态） |
| 登录态 | Sa-Token 会话与权限缓存进 Redis，权限查询一次后缓存 10 分钟 |
| 事件写入 | 攒批（100 条或 200ms）批量 insert，降低写放大 |
| SSE | 独立线程池，超时 30min，心跳 15s |
| 工具执行 | 独立线程池，Python 执行带进程级超时 |
| 数据库 | HikariCP，池大小 10，连接超时 30s |
| 日志 | 异步 Appender，丢弃阈值以下日志不回压业务线程 |

## 7. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 阻塞式 MVC + SseEmitter | MyBatis / JDBC / 进程执行天然适配，无需响应式改造 | 单实例并发受线程池上限约束 |
| 事件流为唯一事实来源 | 可回放、可审计、前端一个 reducer | 事件量大，需要批量写与裁剪 |
| Agent 单例 + RuntimeContext 隔离 | 省内存与构建开销 | 上下文必须落库，不能只靠进程内存 |
| 行级用户隔离（不建 MyBatis 自动拦截） | SQL 可读、可控，避免隐式条件 | 每个查询都要记得带 `user_id` |
| Sa-Token 而非自研 JWT | 注解鉴权、会话管理、踢人下线开箱即用 | 登录态强依赖 Redis |
| 归一一次事件 | 前端与 SDK 解耦 | 新增 SDK 事件需同步适配表 |
| 日志双写（文件 + Redisearch） | 既有原文又有检索 | 写入路径变长，靠 MQ 削峰 |

## 8. 第三方集成事实

这几条是踩过坑后的结论，改动依赖前先读。

| 依赖 | 事实 | 本项目的做法 |
|---|---|---|
| AgentScope | groupId 是 `io.agentscope` | 坐标统一按此写 |
| AgentScope | 官方 Spring Boot starter 编译基线为 Boot 4.x | **不引 starter**，`config` 包自建 `@Bean` 装配 `Model` 与 `ReActAgent` |
| AgentScope | `agentscope-harness` 带入 kubernetes-client 等重型传递依赖 | **不引**，只用 `agentscope-core` + 三个模型扩展 |
| AgentScope | `Memory` / `UserAgent` / 旧 `agent.Event` 已标注 `forRemoval` | 禁用；事件一律用 `io.agentscope.core.event.*` |
| AgentScope | 流式入口是 `ReActAgent.streamEvents(...)` → `Flux<AgentEvent>` | 订阅侧直接消费，不做二次包装 |
| Hutool | `IdUtil` 无 ULID，`fastUUID` 不是有序 ID | ULID 只走 `IdGenerator`（`ulid-creator`） |
| Hutool | `CollectionUtil` 是 `CollUtil` 别名 | 统一用 `CollUtil` |
| Sa-Token | 登录态存 Redis | Redis 不可用时登录链路不可用，不为此做本地降级 |

## 9. 约束

- 单实例部署，不做分布式锁与选主。
- `agent` 包不得反向依赖 `api`。
- `runtime` 包不得依赖任何业务包。
- 所有外部调用必须带超时。
- 任何用户资源查询不得缺少 `user_id` 条件（平台级表除外）。
