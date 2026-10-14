# ARCHITECTURE

**定位**：本文回答四件事——系统形态与中间件分工、分层与依赖方向、跨模块主链路全景、对外依赖边界与并发策略。它不回答单模块的设计细节与选型取舍，那些在 `specs/01..07`；也不复述工程强制规则（分层职责与禁止、包与命名、API / ID / 日志 / 测试约定），唯一权威是 `AGENTS.md`，本文只标注引用位置。

---

## 1. 系统形态

单体应用：阻塞式 Spring MVC 后端 + Vue3 单页前端，前端构建产物由后端一起发布，两者只通过 REST 与 SSE 通信。无网关、无服务发现、无多实例。

| 特征 | 取值 | 代价 |
|---|---|---|
| 部署单元 | 一个应用进程 + 一组中间件容器 | 单实例，不做分布式锁与选主（编排见 [DEPLOYMENT.md](DEPLOYMENT.md)） |
| 运行状态 | 进行中的运行注册表（runId → 活动实例）在进程内存 | 进程重启后的运行成为孤儿，只能在中断接口上直接落终态 |
| 扩缩容 | 上限由单机线程池与数据库连接池决定 | 水平扩容前必须先外置会话运行锁与运行注册表 |

职责划分：**Agent 负责执行，工作台负责观测与控制**。每次执行产生一条 Run，Run 由有序事件流构成，事件流是唯一事实来源。

```
Agent 执行 ──► 内核事件 ──► 归一为 RunEventDTO（seq 递增）─┬─► wb_event 落库（回放/审计）
                                                          ├─► SSE 推送（实时渲染）
                                                          └─► 用量与状态汇总（终态一次落库）
```

事件类型与内核循环见 [specs/01-agent-core.md](../specs/01-agent-core.md)，运行状态机与回放见 [specs/02-conversation.md](../specs/02-conversation.md)。

## 2. 中间件分工

| 中间件 | 存什么 | 读写路径与边界 |
|---|---|---|
| PostgreSQL | 关系与主数据：用户 / 角色 / 菜单、会话、运行、事件、技能、文件、知识库与文档元数据、创作文档与版本、提示词、长期记忆、工具、供应方 | MyBatis，单表注解优先、复杂 SQL 落 XML（AGENTS.md §2.3）；跑 pgvector 镜像，但当前 schema 无向量列，语义检索未启用 |
| Manticore | 一切要检索的文本：`lw_msg`（会话消息）、`lw_kbchunk`（知识库分块）、`lw_doc`（创作文档）、`lw_log`（业务日志） | 建表 DDL 走 `/cli`，写入与检索走 HTTP JSON API；无关系约束、无事务 |
| MinIO | 原件：用户上传文件、知识库文档 | 按 `user/{userId}/...` 前缀存放，按用户隔离，删除走级联 |
| Redis | 派生数据：登录态（Sa-Token）、权限缓存、菜单树缓存、会话运行锁、幂等键、限流计数 | 全部可失效，丢失只影响性能与在线状态，不影响数据正确性 |
| RabbitMQ | 异步管道：日志索引 `lw.log.index.queue`、知识库文档解析 `lw.kb.parse.queue` | 只做削峰，不做可靠投递，失败即降级（见 §4.4） |

四张 Manticore 表统一 `charset_table='non_cont,cjk'` + `morphology='icu_chinese'`，文本列一律 `text indexed stored`。

## 3. 分层与依赖方向

```
api ──► service ──► mapper ──► domain
侧翼（互不依赖业务实现）：config / mq / agent / runtime / observability / anno / aspect / common
```

| 包 | 一句话职责 |
|---|---|
| `api` | HTTP 边界：参数校验、权限注解、`R<T>` 信封、SSE 通道建立与生命周期 |
| `service` | 用例编排、事务边界、日志打点；向下只调 mapper，向上不暴露 HTTP 类型 |
| `mapper` | 四种数据源访问：PG（MyBatis）、Manticore、Redis、MinIO |
| `domain` | 零依赖词汇表：`entity` / `dto` / `vo` / `constant` |
| `config` | 配置绑定与基础设施装配：Sa-Token、线程池、Web 兜底、Manticore、MinIO、MQ 拓扑、启动自检 |
| `agent` | 自研内核：推理循环、工具、模型协议适配、上下文压缩、事件归一；只读平台级配置，端口实现在 service |
| `runtime` | 内置 Python 运行时的探测与执行，无业务依赖 |
| `observability` | Logback 扩展、日志下游投递与保留清理 |
| `mq` | `producer` / `consumer`，削峰管道 |
| `anno` / `aspect` | 幂等、限流等横切能力的声明与实现 |
| `common` | 跨层基础件：`R` / `PageResult` / `IdGenerator` / `BizException` / `ErrorCodeEnum` / `LoginContext` / `LoginUser` / `TraceFilter` / `TimeUtil` / `TimestamptzTypeHandler` / `PasswordUtil` / `SecretCodec` / `OutputSanitizer` / `GlobalExceptionHandler` |

方向性是硬约束：`agent` 不得反向依赖 `api`，`runtime` 不依赖业务包，横切包只依赖 `common` 与 `domain/constant`。完整依赖图与各层禁止事项见 AGENTS.md §1。

## 4. 跨模块主链路全景

### 4.1 登录一次

```
GET  /api/v1/auth/public-key            AuthService + SecretCodec：进程内存 RSA 私钥，下发 SPKI Base64 与 keyId
POST /api/v1/auth/login
  ├─ 1 Redis 失败计数未达上限（同用户名连续 5 次锁 10 分钟）        AuthService
  ├─ 2 SecretCodec.decrypt(keyId, 密文)，失败即 CREDENTIAL_INVALID
  ├─ 3 按 username 查用户 + PasswordUtil.matches（PBKDF2-HMAC-SHA256，兼容旧 SHA-256 记录）
  ├─ 4 LoginUserAssembler.assemble：角色码取 sys_role，权限标识取菜单授权    service 层
  ├─ 5 StpUtil.login + 会话快照写 Redis（键 AuthConst.SESSION_LOGIN_USER）   Sa-Token
  └─ 6 返回 token / 用户 / 角色码 / 权限标识 / 菜单树（MenuService.userTree）
```

登录与改密的密码字段一律 RSA 密文，算法标识 `RSA/PKCS1Padding`；密钥对 6 小时或 1000 次调用轮换，旧钥保留一轮兜住在途请求，轮换判定在公钥下发与解密两处都触发。明文密码不出浏览器、不进日志、不进 Redis。改密入口与首登强制改密的放行集合见 [specs/06-security.md](../specs/06-security.md)。

每个 `/api/**` 请求进入的横切顺序：

```
TraceFilter（最高优先级）           traceId 入 MDC 并回写响应头
  └─ SaInterceptor(checkLogin)      未登录 401，@SaCheckPermission 不通过 403
       └─ LoginContextInterceptor    登录快照入 LoginContext、userId 入 MDC；
                                     停用账号直接拒绝；未改密账号只放行公钥 / 当前用户 / 改密 / 登出
            └─ Controller ──► Service（LoginContext.userId()）
  afterCompletion / 异常路径         清 LoginContext 与 MDC，防线程复用串号
```

### 4.2 一次对话运行

```
POST /api/v1/chat/stream（SSE，produces text/event-stream）
  ├─ 1 会话归属校验 + Redis 会话运行锁（setIfAbsent，TTL = SSE 超时 + 60s）      SessionService / RunService
  ├─ 2 建 Run（queued → running）+ 用户消息写 Manticore lw_msg；
  │     系统提示 = 平台提示 + 会话补充 + 技能展开 + 长期记忆（记忆放末尾），历史取最近 40 条并经 AgentMemory 压缩
  ├─ 3 活动实例登记进进程内运行注册表，内核任务提交到 lwRunExecutor               AgentKernelRuntime
  ├─ 4 内核循环（模型流式出站；工具执行走 lwToolExecutor）
  │     ├─ 内核事件 → AgentEventAdapter 归一 + seq 递增
  │     ├─ 推送节拍 16ms 取批、同类型增量合帧 → RunSink → SSE
  │     └─ 落库节拍 200ms 批量 insert wb_event
  ├─ 5 终态：Run 状态与用量一次落库 + 助手消息写 lw_msg；异常或中断记 aborted
  └─ 6 释放会话锁；SSE 完成 / 超时 / 出错统一经 onChannelClosed 收敛为 aborted
```

状态机、事件类型映射、合帧规则、工具输出裁剪、协作式中断的安全点见 [specs/01-agent-core.md](../specs/01-agent-core.md) 与 [specs/02-conversation.md](../specs/02-conversation.md)。

### 4.3 知识库入库与检索

```
POST /api/v1/kb/doc/upload/{kbId}（multipart）
  ├─ 1 归属与文件校验 → 原件写 MinIO user/{userId}/kbdoc/... → 落库 wb_kb_doc(state=pending)
  └─ 2 发 MQ lw.kb.parse.queue（上传请求零等待）
        └─ KbDocParseConsumer（跑在 RabbitMQ 监听容器，并发由 listener.simple 显式给出）
              ├─ 3 MinIO 拉原件 → DocTextExtractor 提纯文本（txt/md、docx、pdf）
              ├─ 4 KbTextChunker 分块：目标 600 字，相邻重叠 120 字
              └─ 5 分块批量写 Manticore lw_kbchunk → wb_kb_doc(ready) 与计数；失败置 failed 可重放

检索两条入口都落在 lw_kbchunk：
  页面   GET /api/v1/kb/search            user_id（+ 可选 kb_id）+ 全文匹配，按相关度返回分块
  Agent  kb_search 工具                   经 agent/spi 端口进 KbDocService，回带文档名与序号的片段
```

原件在 MinIO、索引可重建，删除文档即清分块索引；检索方式与分块参数见 [specs/04-knowledge-doc.md](../specs/04-knowledge-doc.md)。

### 4.4 日志从产生到可检索

```
业务 log.info（链路标识由框架注入，业务不手拼）
  └─ Logback：CONSOLE + 三个文件 Appender（均由 AsyncAppender 包裹）
       └─ LW_MANTICORE Appender → LogPublisher(LogProducer) → MQ lw.log.index.queue
            └─ LogConsumer → Manticore lw_log

GET /api/v1/log/search ｜ /api/v1/log/stats
  └─ 视角判定（持 monitor:log:global 走全局，否则服务端强制 user_id = 当前用户）
       └─ 等值过滤（level / logger / trace_id / user_id）+ ts 范围 + message 全文匹配
```

MDC 注入点三处：`TraceFilter` 注入 `traceId`，`LoginContextInterceptor` 注入 `userId`，`RunService` 在运行编排期注入 `sessionId` / `runId`；异步 Appender 从 Logback 事件快照读这四个键（异步线程的 ThreadLocal MDC 为空），匿名请求与系统线程允许为空。降级：MQ 投递失败即只落文件，不重试、不阻塞业务线程；Manticore 检索异常降级为空结果并告警，原文仍在滚动文件里。文件名、级别切分与检索字段分工见 [specs/05-observability.md](../specs/05-observability.md)，级别与打点规则见 AGENTS.md §9。

## 5. 关键机制

### 5.1 时间：库内与出网

| 环节 | 事实 |
|---|---|
| 库列 | 所有时间列统一 `timestamptz`，Java 侧统一 `LocalDateTime` |
| 全局映射 | `mybatis.type-handlers-package=com.liteworkbench.common` 装载 `common/TimestamptzTypeHandler`：PG JDBC 对 `timestamptz` 不支持默认的 `getObject(LocalDateTime.class)`，收发两侧一律改走 `Timestamp` 通道。不逐字段标注，全局生效 |
| 出网 | 对外 JSON 输出毫秒时间戳（long），唯一出口 `common/TimeUtil.toMillis(LocalDateTime)`，空值返回 `null` 而非 0 |
| 序列化 | Jackson `default-property-inclusion: non_null`、`time-zone: GMT+8` |

字段语义本身（审计字段、时间列命名）见 AGENTS.md §7。

### 5.2 Web 层兜底（`config/WebConfig`）

| order | 映射 | 命中后行为 |
|---|---|---|
| 1 | `/api/**` | 业务接口未命中时回 `R.fail(RESOURCE_NOT_FOUND)` 的 JSON 并 warn 记录 URI，不返回 HTML |
| 2 | `/{path:[^\.]*}`、`/**/{path:[^\.]*}` | `forward:/index.html`，前端 history 路由刷新时回落单页应用 |

- 顺序是唯一保障：`order=1` 的 `/api/**` 与 `order=2` 的 `/` 通配重叠，若不指定顺序，接口 404 会被回成 `index.html`。
- 模式带 `[^\.]*` 的用意：带点路径（js / css / 图片等静态资源）不落进兜底，交默认资源处理。
- 两个兜底只解决"路径未命中"；已命中接口的资源不存在仍由 `GlobalExceptionHandler` 统一转 `R` 信封。
- 同文件另配 CORS，仅放行 `http://localhost:[*]` 与 `http://127.0.0.1:[*]` 供前端 dev server 调试，生产同源部署不依赖它。

契约测试见 AGENTS.md §14。

### 5.3 SSE 输出抽象（`service/RunSink` + `api/SseRunSink`）

| 角色 | 类型 | 内容 |
|---|---|---|
| 端口 | `service/RunSink` | `send(seq, eventType, payloadJson)` / `ping()` / `complete()`，只认字符串与序号 |
| 实现 | `api/SseRunSink` | `SseEmitter` 适配：事件名 `run.event`、`id=seq`、`data` 为 JSON；`ping` 为注释帧；写失败（IOException / IllegalStateException）转运行时异常，由 service 判为连接断开 |
| 同理 | `service/DocAssistSink` + `api/SseDocAssistSink` | 辅助写作的单通道（`assist.start` / `assist.delta` / `assist.end` / `assist.error`） |

拆这一步的原因：HTTP 类型只出现在 `api` 层，`RunService` 与后台内核线程拿到的是接口，运行事件的输出通道因此可替换（回放、测试、其它传输），也让 service 不依赖 web 栈。

通道生命周期归 api 层：`SseEmitter` 超时 30 分钟、心跳 15 秒，`onCompletion` / `onTimeout` / `onError` 一律回调 `RunService.onChannelClosed(runId)`，由它把运行收敛为 `aborted` 并释放会话锁。

### 5.4 登录快照装配（`service/LoginUserAssembler`）

- 装配发生在 `service` 层：`AuthService` 登录成功、以及 `/auth/current` 取当前用户时调用，产出 `common/LoginUser`（`userId` / `username` / `nickname` / `avatarUrl` / `roleCodes` / `perms` / `state` / `pwdResetRequired`）。
- 角色码与权限标识实时回源（权限列表加 10 分钟 Redis 缓存）；授权变更后由 `refreshSession(userId)` 回写在线会话快照，用户不在线则跳过，下次登录自然取到新值。
- 放在 service 的原因：装配要读 `SysUserMapper` / `SysRoleMapper` 并调 `MenuService`，属编排职责；api 层只拿结果，`agent` 与 `runtime` 不感知登录模型。

## 6. 线程与执行模型

### 6.1 线程资源为什么集中声明

单个容器承载全部异步能力，线程资源由 `config/ExecutorConfig` 一处声明、业务只做构造注入。集中声明的理由是容量可核算：池分散在业务类里时，进程总并发无从统计，拒绝策略与停机收尾也无法统一，容器停机就换不出一致的收尾行为。

按用途分池的依据是各池任务的可丢性与时延要求彼此相反：主链路不可丢（池满时让提交方降速）、工具执行排队无意义（快速失败让内核收敛）、观测投递不得反压业务（满则丢弃但必须留计数）、解析入库宁可变慢也不丢用户已上传的文档、周期任务只跑短活。混池会让任一用途的积压把其余用途一并拖死。

线程模型上本文件只锁定两条架构边界：

- **阻塞 IO 不得占用运行编排池**：模型流式出站、工具与 Python 执行、PG / Manticore / MinIO / RabbitMQ 访问各有归属，运行编排池只放"一次运行一个任务"的编排线程。
- **收尾必须三段式**：停收新任务 → 等待在途完成 → 超时强制中断，否则容器停机留下悬挂的 SSE 连接与子进程。核数取 `max(2, availableProcessors())`，小容器核数不稳定时留下限。

池清单、规模策略与拒绝策略见 AGENTS.md §17。

### 6.2 运行节拍器（`RunService`）

两个节拍器全进程各一个，与运行数量无关，无活动运行时自动注销：

| 节拍 | 常量 | 周期 | 做什么 |
|---|---|---|---|
| 推送 | `RunConst.SSE_PUSH_TICK_MS` | 16ms | 取各运行的新事件、同类型增量合帧、写 `RunSink` |
| 落库 | `RunConst.EVENT_PERSIST_INTERVAL_MS` | 200ms | 批量 insert `wb_event`，与推送解耦，DB 写入不拖慢首字延迟 |
| 心跳 | `RunConst.SSE_HEARTBEAT_MS` | 15s | 注释帧维持长连接 |
| 水位快照 | `RunConst.SSE_SNAPSHOT_THROTTLE_MS` | 200ms 节流 | 客户端据此判定丢帧并补齐 |

合帧、截断与用量入账规则见 [specs/01-agent-core.md](../specs/01-agent-core.md)。

### 6.3 缓存与写路径

| 点 | 做法 |
|---|---|
| 模型客户端 | `ModelFactory` 按 `provider + model` 缓存复用 `ModelClient`（持端点和超时配置） |
| 登录态与权限 | 会话与权限标识缓存进 Redis，权限缓存 `liteworkbench.auth.perm-cache-seconds` 默认 600 |
| 菜单树 | 用户可见菜单树缓存 Redis 600s，菜单定义或授权变更即清，路由加载不打穿库 |
| 仪表盘 | 汇总指标全局缓存 60s（`MonitorService`），避免高频重算 |
| 事件写入 | 200ms 攒批 insert（见 §6.2） |
| 消息 / 知识块 / 创作文档 | 单条或批量直写 Manticore 文档，不占事务、无倒排维护成本 |
| 知识库解析 | MQ 异步消费，上传接口零等待；失败不重试挤压，置 `failed` 可手工重放 |
| 数据库 | HikariCP `maximum-pool-size=10`、`connection-timeout=30s` |
| 外部调用超时 | 模型流式 180s、同步调用 120s、连接 30s；工具 60s；Python 120s；Manticore `/cli` 10s —— 全部显式设置 |

## 7. 对外依赖边界

| 依赖 | 取用 | 自研补齐 |
|---|---|---|
| hutool-ai | 供应方配置契约（`AIConfig`）、各厂商默认端点与默认模型、同步文本补全 | 流式出站（`agent/model/*Client`）、请求体构造、响应解析、工具调用下发与增量累积、`usage` 与 `finish_reason` 解析。协议集合固定为 OpenAI 兼容 + Ollama 本地通道 |
| Hutool | 字符串 / 集合 / 日期 / JSON / 加解密 / HTTP 工具类 | 业务 ID 生成（`common/IdGenerator` 出 ULID，前缀 + ULID 字典序近似时间序） |
| Sa-Token | 登录态存 Redis、`@SaCheckPermission` 注解鉴权、踢人下线 | 权限点来源与缓存（`config/StpInterfaceImpl`）、登录快照装配、菜单种子对账（`config/DataInitializer`） |
| MyBatis | SQL 映射与事务 | 归属隔离条件的写入、注解 / XML 的取舍、`jsonb` 列的显式 `CAST`、`timestamptz` 全局 TypeHandler |
| Manticore | 全文检索（ICU 中文分词）与分组聚合 | 四张表的 DDL、文档结构、写入与检索路径（见 [specs/05-observability.md](../specs/05-observability.md)） |
| RabbitMQ | 异步削峰 | 消费失败降级：日志回退到只落文件、知识库解析置失败态 |
| MinIO | 对象存储 | 按用户前缀隔离、预签名下载、级联删除 |
| PostgreSQL | 关系存储与事务 | 行级归属隔离、`jsonb` 显式转换、幂等 DDL 演进 |

关键技术约束：

- Manticore 四张表建表必须显式带 `charset_table='non_cont,cjk'` 与 `morphology='icu_chinese'`，文本列 `text indexed stored`；DDL 走 `/cli`（该端点对失败语句同样回显 `Query OK`，故建表后另用属性过滤 `SELECT` 复验，不靠回执判断），写入与检索走 HTTP JSON API。
- 应用与中间件同容器网络、服务名直连；Manticore 的 HTTP 端口不发布宿主机。
- 登录态强依赖 Redis：Redis 不可用时登录链路直接报错，不静默降级。中间件整体不可用时是否放行启动由 `liteworkbench.degradation.allow-start`（默认 `true`）决定，放行仅记 warn 并在健康面板标 `DEGRADED`。

## 8. 架构级取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 分层单向依赖 | 每层可独立替换（换全文检索客户端只动 mapper），业务逻辑不散落在入口 | 禁止跨层取巧，简单查询也要过一层 |
| 模块化单体 | 一次部署、一个事务边界，跨模块调用是进程内方法调用，不需要分布式一致性与契约序列化 | 模块边界靠依赖方向与评审维持，没有编译期隔离；不能按模块独立扩缩容 |
| 单容器单实例 | 无网关与注册中心，运行注册表、节拍器、模型客户端缓存都可放进程内存，运维面最小 | 并发受单机线程池与连接池上限约束；水平扩容前必须先外置运行锁与运行注册表 |
| 阻塞式 MVC + `SseEmitter` | MyBatis、JDBC、进程执行天然适配，不需要响应式改造 | 长连接占用容器线程，SSE 并发上限即线程上限 |
| 事件流为唯一事实来源 | 可回放、可审计，前端只需一个 reducer | 事件量大，依赖攒批写入与裁剪（见 §6.2） |
| 读写路径按中间件分工 | 关系与事务交 PG、检索交 Manticore、原件交 MinIO、派生数据交 Redis、削峰交 MQ，各取所长 | 跨库无事务，一致性只有"PG 为准 + 索引可重建"，双写处需显式幂等 |
| 时间出网为毫秒 long | 前端零时区处理，载荷小 | 每次转换都要过 `TimeUtil`，排查时看到的是数字 |
| 运行事件出站经 `RunSink` 抽象 | service 不依赖 HTTP 类型，通道可替换 | 多一层适配与一个断连回调约定 |
| PG 用 pgvector 镜像 | 语义检索后续加列即可启用，不再引入第二个中间件 | 当前是占位，镜像体积与依赖面已经付出 |
