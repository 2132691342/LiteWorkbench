# ARCHITECTURE

分层设计、模块职责、关键流程。

---

## 1. 定位

后端是一个阻塞式 Spring MVC 应用，前端是单页应用。两者通过 REST + SSE 通信，无网关、无服务发现、无多实例。

整体职责划分：**Agent 负责执行，工作台负责观测与控制**。Agent 每次执行产生一条 Run，Run 由有序事件流构成，事件流是唯一事实来源。

存储分工：PG 存关系与主数据，Manticore 存一切要检索的文本（消息、知识块、日志、创作文档，ICU 中文分词 + 关键词全文），MinIO 存原件（上传文件、知识库文档），Redis 存缓存、登录态与运行锁，MQ 做削峰管道（日志索引、知识库文档解析）。PostgreSQL 采用 pgvector 镜像，向量列与语义检索为预留扩展位，当前检索一律走 Manticore 关键词全文。

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
│ mapper     XxxMapper：Postgres / Manticore /         │
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
| `service` | `SessionService` / `ProviderService` / `SkillService` / `FileService` / `ToolService` | 会话、供应方、技能、文件与工具的业务编排 |
| `service` | `KbService` | 知识库 CRUD、归属校验、级联清理（文档原件 + 分块索引） |
| `service` | `DocService` | 创作文档 CRUD、版本快照、全文索引同步、版本回滚 |
| `service` | `DocAssistService` | AI 辅助写作：动作模板 + 选区 + 可选知识库片段 → 同步调用模型返回文本 |
| `service` | `PromptService` | 提示词模板 CRUD、内置只读保护、变量渲染 |
| `service` | `FlowService` | 工作流画布 CRUD、结构校验、拓扑排序 |
| `service` | `FlowEngine` | 按拓扑序串行执行节点，逐节点落事件；`llm` / `python` / `knowledge` / `tool` 四类执行器 |
| `service` | `KbDocService` | 文档上传（MinIO + MQ 派发）、状态查询、分块检索、文档删除 |
| `service` | `DocTextExtractor` | 从 MinIO 拉原件提取纯文本：txt/md 直接读，docx 解包取 XML，pdf 走 pdfbox |
| `agent` | `AgentLoop` | 自研推理循环：思考 → 工具调用 → 观察 → 收敛，含迭代上限、取消、重复调用护栏 |
| `agent` | `AgentKernelRuntime` | `AgentRuntime` 实现：装配 `AgentLoop` 与工具集，把内核事件写入订阅者 |
| `agent` | `AgentEventAdapter` | 把内核 `AgentEvent` 归一化为工作台 `RunEventDTO` 并分配 `seq` |
| `agent` | `ModelFactory` | 按供应方构建并缓存 `ModelClient`，解密密钥，三级模型名兜底 |
| `agent` | `model/*Client` | 三协议出站：`OpenAiCompatClient` / `AnthropicClient` / `OllamaClient`，共用 SSE 解析与取消 |
| `agent` | `ModelCaller` | 模型同步调用，供 AI 辅助写作与工作流 `llm` 节点复用，不建 Run |
| `agent` | `WorkbenchTools` | 内置工具集：`python_exec`、`file_read`、`file_write`、`http_get`、`kb_search` |
| `agent` | `AgentMemory` | 上下文组装与确定性压缩（不调模型）：配对修复、Token 估算、切点选择 |
| `runtime` | `LocalPythonRuntime` | Python 解释器探测、脚本落盘、进程执行、超时与进程树终止 |
| `mapper` | `LogSearchMapper` / `MessageIndexMapper` / `KbChunkIndexMapper` / `DocIndexMapper` | Manticore 表创建、写入、检索：日志 / 会话消息 / 知识库分块 / 创作文档 |
| `observability` | `ManticoreLogAppender` | Logback 扩展，异步把日志送达 Manticore |
| `service` | `MonitorService` | 各中间件健康探测与仪表盘汇总统计（60s 缓存） |
| `mq` | `LogProducer` / `LogConsumer` | 日志投递削峰，失败降级为本地文件 |
| `mq` | `KbDocParseProducer` / `KbDocParseConsumer` | 知识库文档解析任务派发与消费：提文本 → 分块 → 写索引 |
| `aspect` | `IdempotentAspect` | `@Idempotent` 写接口防重提交 |
| `common` | `LoginContext` / `StpInterfaceImpl` | 请求线程的用户身份载体，权限点解析 |

## 4. 认证与数据归属

### 4.1 登录链路

```
GET  /api/v1/auth/public-key → { keyId, publicKey(SPKI 的 Base64), algorithm }
POST /api/v1/auth/login  { username, password(密文), keyId }
  ├─ 1 按 keyId 取内存私钥解密，失败即 CREDENTIAL_INVALID  AuthService
  ├─ 2 按 username 查用户，校验存在且未停用                  UserMapper
  ├─ 3 失败计数（Redis 连续 5 次锁 10 分钟）                  AuthService
  ├─ 4 校验密码（SHA-256 + 盐）与账号状态                     AuthService
  ├─ 5 StpUtil.login(userId)，会话写入 Redis                  Sa-Token
  ├─ 6 组装 LoginUser（userId + roleCodes + perms）缓存进 TokenSession
  └─ 7 返回 token、用户信息、pwdResetRequired 与菜单权限标识
```

登录与改密的密码字段一律 RSA/PKCS#1 v1.5 密文（浏览器端 JSEncrypt，避免明文 HTTP 下 `crypto.subtle` 不可用）；密钥对进程内存持有，每 6 小时或每 1000 次加密轮换一次，轮换后旧钥保留一轮兜住在途请求。明文密码不出浏览器、不进日志、不进 Redis。

`pwdResetRequired = true` 的账号（种子账号）在改密前只放行 `/auth/current` 与 `/auth/password`，其余接口返回 `PASSWORD_RESET_REQUIRED`。

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

### 4.4 菜单种子对账

菜单是平台级定义，升级时**只增不改**会留下两类脏数据：历史随机 ID 的重复菜单、新旧混排的 i18n key。因此 `DataInitializer` 每次启动做一次对账：

```
initMenus()
  ├─ 1 逐条种子：按固定 menu_id 存在即 upsert（回写 name/route/component/perm/icon/sort/visible）
  └─ 2 残留清理：把"route_path 落在种子集合内、或 perm_code 落在种子集合内，但 menu_id 不在种子内"的行软删
```

种子 ID 采用 `MENU_ + 26 位零填充序号`，人不直接维护；菜单中文名一律以 i18n key 形式存储（见 AGENTS.md §2.5），改文案不动库。

## 5. 关键流程

### 5.1 一次对话的完整链路

```
POST /api/v1/chat/stream
  │
  ├─ 1 校验会话归属（user_id），获取会话级运行锁   SessionService
  ├─ 2 创建 Run（queued）+ 用户消息写入 Manticore lw_msg     RunService
  ├─ 3 建立 SseEmitter                      RunController
  ├─ 4 构建 AgentLoop（ModelClient 按 provider+model 缓存复用）  AgentKernelRuntime
  ├─ 5 组装历史消息（消息索引最近 20 条，经 AgentMemory 压缩）→ 启动内核
  │     ├─ 归一化事件 → seq 递增
  │     ├─ 批量攒批写入 wb_event
  │     └─ 同步推送 SSE
  ├─ 6 结束：更新 Run 状态与用量 + 助手消息写入消息索引
  └─ 7 异常/中断：标记 aborted 并推送终态，finally 释放会话锁
```

### 5.2 事件归一

内核事件是收敛过的封闭集合（`agent/event/AgentEventType`），不做第二套记账，只在出口做一次归一：

| 内核事件 | 工作台事件类型 | blockType |
|---|---|---|
| `AGENT_START` | `run.start` | — |
| `TEXT_DELTA` | `text.delta` | `text` |
| `THINKING_DELTA` | `thinking.delta` | `thinking` |
| `TOOL_CALL_START` / `TOOL_CALL_END` | `tool.call`（`phase=start/end`） | `tool_call` |
| `TOOL_RESULT` | `tool.result` | `tool_result` |
| `MODEL_CALL_START` / `MODEL_CALL_END` | `model.call`（带用量与耗时） | — |
| `AGENT_RESULT` | `run.result` | `text` |
| `EXCEED_MAX_ITERS` | `run.exceed` | — |
| `AGENT_END` | `run.end` | — |
| `AGENT_ERROR` | `run.error` | — |

三协议出站的差异（OpenAI 的 `data:` SSE、Anthropic 的命名事件、Ollama 的 NDJSON）在 `model` 包内被拉平为同一组内核事件，`AgentEventAdapter` 只认内核事件，因此前端与模型协议彻底解耦。

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
  → 本进程有活动运行：置取消标志 + 关闭当前响应的 InputStream → 内核在安全点收敛 → 终态记 aborted
  → 本进程无活动运行（进程重启后的孤儿运行）：直接结算为 aborted
  → 推送 run.end
```

中断是**协作式取消**，不做线程中断：`RunHandle.cancel()` 置原子标志并关闭流式连接，内核每轮循环开头与每个工具执行前检查标志，在"消息已落库、事件已发完"的安全点退出，因此不会留下半截状态。运行注册表在进程内存（单实例部署），重启后的孤儿运行在 abort 时直接落终态。

### 5.4 限流

`@RateLimit` 注解 + 切面，Redis 固定窗口计数（`lw:rl:{prefix}:{主体}:{窗口号}`）：登录按 IP 每分钟 10 次防爆破，流式对话按用户每分钟 20 次控模型成本，超限抛 `RATE_LIMITED`（429）。

### 5.5 幂等

```
POST 写接口（带 @Idempotent）
  ├─ 1 取 LoginContext 的 userId 与请求体摘要
  ├─ 2 SETNX lw:idem:{prefix}:{userId}:{digest}，TTL 默认 10s
  ├─ 3 命中 → 响应头 X-Lw-Idem-Key 回传占位键 + 抛 REPEAT_SUBMIT（2501）
  └─ 4 未命中 → 放行，正常处理
```

### 5.6 日志汇聚

```
业务代码 log.info
  → Logback AsyncAppender
      ├─ RollingFileAppender（按天 + 按级别）
      └─ ManticoreLogAppender → MQ → LogConsumer → Manticore
```

MQ 不可用时降级为跳过索引，不阻塞主流程。

### 5.7 知识库链路

```
POST /api/v1/kb/doc/upload/{kbId}（multipart）
  ├─ 1 校验知识库归属与文件（大小 / 扩展名）        KbDocService
  ├─ 2 原件写 MinIO user/{userId}/kbdoc/...，失败即回滚
  ├─ 3 落库 wb_kb_doc(state=pending)
  └─ 4 发 MQ lw.kb.parse（削峰，解析不占请求线程）
        → KbDocParseConsumer
            ├─ 5 MinIO 拉原件，DocTextExtractor 提取纯文本
            ├─ 6 按段落贪心合并分块（≈600 字，相邻重叠 120 字）
            ├─ 7 分块批量写 Manticore lw_kbchunk
            └─ 8 更新 wb_kb_doc(ready) 与 wb_kb 计数；失败置 failed + 原因
```

检索与消费两条路：

| 路 | 入口 | 行为 |
|---|---|---|
| 页面检索 | `GET /api/v1/kb/search` | `@user_id` + 可选 `@kb_id` + `@content:(关键字)`，按相关度返回分块，前端做关键字高亮 |
| Agent 工具 | `kb_search`（`WorkbenchTools`） | 经 `agent/spi/KbSearchPort` 端口调 `KbDocService`，返回带文档名与序号的拼装片段，模型据此引用 |

分块与索引可随时重放：原件在 MinIO，删除文档即清分块索引；`state=failed` 的文档可重新触发解析。

### 5.8 创作链路

```
POST /api/v1/doc/update（正文 + 标题）
  ├─ 1 校验归属，正文长度与字数核算                DocService
  ├─ 2 version_no + 1，写 wb_doc_version 快照
  ├─ 3 更新 wb_doc 正文与纯文本
  └─ 4 覆盖写 Manticore lw_doc（失败只告警，PG 为准）

POST /api/v1/doc/assist（action + selection + instruction + kbId?）
  ├─ 1 按 action 选内置动作模板（续写/润色/摘要/扩写/翻译）
  ├─ 2 可选：kbId 存在时 kb_search 取 3 段片段拼进上下文  DocAssistService
  ├─ 3 ModelFactory 取模型同步调用，返回纯文本
  └─ 4 前端决定插入 / 替换选区

POST /api/v1/flow/run（flowId + inputs）
  ├─ 1 取画布结构并校验（唯一 start、有 end、无环、无孤立）   FlowService
  ├─ 2 拓扑排序得执行序列                              FlowService
  ├─ 3 逐节点执行（上下文变量沿边传递）                  FlowEngine
  │     ├─ llm        → ModelFactory 同步调用
  │     ├─ python     → LocalPythonRuntime 执行脚本
  │     ├─ knowledge  → KbDocService 检索分块
  │     └─ tool       → 已启用内置工具
  ├─ 4 每节点结果落 wb_event（event_type=flow.node）
  └─ 5 节点失败即中止，runId 与已产出输出一并返回
```

创作文档是唯一"PG 与 Manticore 双写"的资产：PG 保证编辑事务，Manticore 提供全站检索，索引落后时以 PG 为准并可由文档列表页触发重建。

## 6. 并发与性能

| 点 | 做法 |
|---|---|
| Agent 实例 | `ModelClient` 按 `provider + model` 缓存复用（持有连接与重试策略）；`AgentLoop` 每次运行新建，天然无跨会话状态 |
| 登录态 | Sa-Token 会话与权限缓存进 Redis，权限查询一次后缓存 10 分钟 |
| 菜单树 | 用户可见菜单树缓存 Redis（TTL 10min），菜单 / 授权 / 用户角色变更即清，路由加载不打穿库 |
| 仪表盘 | 汇总指标全局缓存 60s，趋势统计免高频重算 |
| 事件写入 | 攒批（100 条或 200ms）批量 insert，降低写放大 |
| 消息与知识块写入 | 单条 / 批量直写 Manticore 文档，免事务与倒排维护 |
| 知识库解析 | MQ 异步消费，上传接口零等待；解析失败不重试挤压，直接置 failed 可手工重放 |
| SSE | 独立线程池，超时 30min，心跳 15s |
| 工具执行 | 独立线程池，Python 执行带进程级超时 |
| 数据库 | HikariCP，池大小 10，连接超时 30s |
| 文档索引 | 保存即覆盖写单条 Manticore 文档，成本与文档大小无关，不做批量攒批 |
| 日志 | 异步 Appender，丢弃阈值以下日志不回压业务线程 |

## 7. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 阻塞式 MVC + SseEmitter | MyBatis / JDBC / 进程执行天然适配，无需响应式改造 | 单实例并发受线程池上限约束 |
| 事件流为唯一事实来源 | 可回放、可审计、前端一个 reducer | 事件量大，需要批量写与裁剪 |
| Agent 无状态 + 每次运行新建循环 | 省内存，天然无跨会话污染 | 上下文必须落库，不能只靠进程内存 |
| 行级用户隔离（不建 MyBatis 自动拦截） | SQL 可读、可控，避免隐式条件 | 每个查询都要记得带 `user_id` |
| Sa-Token 而非自研 JWT | 注解鉴权、会话管理、踢人下线开箱即用 | 登录态强依赖 Redis |
| 归一一次事件 | 前端与模型协议解耦，换协议不动前端 | 新增内核事件需同步适配表 |
| 自研内核而非引 Agent 框架 | 依赖面极小（只有 hutool-ai），循环与工具完全可控，可单测 | ReAct 循环、工具 Schema、上下文压缩都要自己维护 |
| 日志双写（文件 + Manticore） | 既有原文又有检索 | 写入路径变长，靠 MQ 削峰 |
| 消息存 Manticore 不存 PG | 全文检索免费获得，写路径短，与日志同一套设施 | 消息资产依赖 Manticore 持久化，容量规划要算上它 |
| 知识检索用关键词全文（Manticore + ICU 分词） | 零额外依赖、零 embedding 成本，中文按词典切分，短查询够用 | 无语义召回，同义改写查不到 |
| 画布结构存整体 jsonb | 读写一次完成，前端可直接接管渲染 | 节点级查询与统计要解析 JSON |
| 工作流串行执行不并行分支 | 语义可预测、变量传递简单、出错点明确 | 有依赖无关的分支也只能排队，靠节点超时控制总耗时 |
| AI 辅助写作不建 Run | 短结果即时可见，不占事件表 | 无法回放辅助过程 |
| pgvector 列预留不启用 | 语义检索留了升级位 | 当前是占位，不产生价值 |

## 8. 第三方集成事实

下表是各依赖不可绕开的客观事实，实现按此约束。

| 依赖 | 事实 | 本项目的做法 |
|---|---|---|
| hutool-ai | `AIService` 只返回裸字符串，无 usage / tool_calls / finish_reason 模型 | 只借用其配置与出站 HTTP；请求体构造、响应解析、工具调用全部自研 |
| hutool-ai | 流式是"逐行原样回调 + 后台线程即弃"，无 `data:` 解析、不可取消 | 自建 `ModelStreamSink`：剥 `data:` / 识别 `[DONE]` / 合 delta / 支持取消；`hutool-ai` 的流式入口不使用 |
| hutool-ai | `Message` 无 `tool_calls` / `tool_call_id` | 内核自带消息模型 `AgentMessage`，出站时按协议转 JSON |
| hutool-ai | ollama 无 `AIUtil.getOllamaService` | Ollama 走 `agent/model/OllamaClient` 直连 `/api/chat`（NDJSON） |
| Hutool | `IdUtil` 无 ULID，`fastUUID` 不是有序 ID | ULID 只走 `IdGenerator`（`ulid-creator`） |
| Hutool | `CollectionUtil` 是 `CollUtil` 别名 | 统一用 `CollUtil` |
| Sa-Token | 登录态存 Redis | Redis 不可用时登录链路不可用，不为此做本地降级 |
| RabbitMQ | `spring.rabbitmq.listener.simple.retry` 的 `multiplier` 默认 1.0，会触发 `ExponentialBackOffPolicy` 告警 | 显式配置 `initial-interval` / `max-interval` / `multiplier=2` |
| Manticore | 默认 charset_table 下文本不入索引；`text stored` 只存不索引 | 建表统一 `charset_table='non_cont,cjk' morphology='icu_chinese'`，文本列一律 `text indexed stored` |
| Manticore | 非 raw 模式下 `/sql` 把语句编译成搜索查询，`SELECT 1` / `SHOW TABLES` 会被回 501 `only SELECT queries are supported` | DDL 走 `/cli`，写入走 `/insert` `/update` `/delete` `/bulk`，检索走 `/search`；**健康探测必须带 `FROM <索引>`** |
| Manticore | 9308 不发布宿主机 | 应用容器与中间件同网络，`manticore:9308` 服务名直连 |
| Manticore SDK | 传递依赖 `commons-logging` 与 `spring-jcl` 冲突并打印告警 | Maven 排除 `commons-logging` |

## 9. 约束

- 单实例部署，不做分布式锁与选主。
- `agent` 包不得反向依赖 `api`。
- `runtime` 包不得依赖任何业务包。
- 所有外部调用必须带超时。
- 任何用户资源查询不得缺少 `user_id` 条件（平台级表除外）。
