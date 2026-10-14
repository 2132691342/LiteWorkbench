# 05 · 可观测：日志、运行记录、健康监控

## 定位

三件事各答一个问题：日志回答"系统发生了什么"，运行记录回答"Agent 是怎么跑的"，健康监控回答"哪个组件挂了"。边界：日志只做采集、落盘、索引与检索，不改业务日志内容；运行记录只做查看与控制（列表、详情、时间线、瀑布、Run 树、用量、回放、中断），不重跑、不改写历史；监控只做探测与汇总，不含告警通道。

不回答：日志级别、MDC 规范与注释规范（见 AGENTS.md §9、§13）；事件类型定义与归一阵列（见 specs/01）；`lw_log` 表结构（见 docs/DATA-MODEL.md §6）；响应信封与路径约定（见 docs/API-CONTRACT.md）。

## 设计

### 日志双写

```
业务 log.info（业务代码不手拼链路字段）
  → Logback MDC：traceId / userId / sessionId / runId
  → AsyncAppender（neverBlock，队列满丢弃）
      ├─ RollingFileAppender ×3：liteworkbench.log / -error.log / -agent.log
      └─ ManticoreLogAppender（observability）
            → LogPublisher.publish(LogMessage)
            → LogProducer（LogPublisher 实现，送 MQ 削峰）
            → 交换机 lw.log.exchange / 路由键 lw.log.index
            → 队列 lw.log.index.queue
            → LogConsumer → LogSearchMapper.add → lw_log
```

`LogPublisher` 是唯一投递出口，`LogMessage` 为 record（logId / ts / level / logger / thread / 四链路字段 / message / exception）。`LogObservabilityConfig` 在启动时把 `LogProducer` 注入 Appender 的静态出口，Appender 本身不依赖 Spring。

链路标识注入点三处，职责不重叠：

| 键 | 注入者 | 时机 | 可空 |
|---|---|---|---|
| `traceId` | `TraceFilter` | 请求进入（优先复用 `X-Trace-Id` 请求头，缺失则生成） | 匿名请求也有（新生成） |
| `userId` | `LoginContextInterceptor`（鉴权通过后） | 每请求 | 登录前为空 |
| `sessionId` / `runId` | `RunService`（运行编排开始 / 心跳记账期间） | 一次运行的全程 | 非运行日志为空 |

业务代码禁止手工拼接这四个字段；MDC 由框架写入，Appender 原样读取落 `lw_log` 过滤列。`traceId` 与响应信封的 `requestId` 一致（同值经响应头回传）。

**文件滚动**（`log-dir` 由 `liteworkbench.log-dir` 配置，容器内用 `LITEWORKBENCH_LOG_DIR` 覆盖）：

| 文件 | 级别下限 | 策略 | 保留 |
|---|---|---|---|
| `liteworkbench.log` | INFO | `SizeAndTimeBasedRollingPolicy`，单文件 100MB，总量 5GB | 30 天 |
| `liteworkbench-error.log` | WARN | 同上，总量 2GB | 30 天 |
| `liteworkbench-agent.log` | INFO | 同上，总量 2GB | 30 天 |

历史文件落 `${LOG_DIR}/history/`。`agent` / `runtime` / `RunService` 三个 logger 设 `additivity=false`，额外挂 agent 文件，同时照常进全量、error 与索引；避免 Agent 高频日志冲淡全量文件。`AsyncAppender` 三路分别 2048 / 512 / 2048 队列，`neverBlock=true`，队列满即丢弃，不回压业务线程。

**索引表 `lw_log`**（字段见 [DATA-MODEL §6.1](../docs/DATA-MODEL.md)）：`message` 与 `exception` 均为全文列（`text indexed stored`），`level` / `logger` / `thread` / `trace_id` / `user_id` / `session_id` / `run_id` / `ts` 是过滤列；建表串 `charset_table='non_cont,cjk'` + `morphology='icu_chinese'` 支持中文分词。保留 30 天（`liteworkbench.manticore.log-retention-days`），由 `LogRetentionJob` 每日 03:30 按 `ts < cutoff` 删除（Manticore 无文档级 TTL）。

**写入裁剪**（索引体积可控）：`message` 截 4000 字符（超出加 `...`）、`logger` 超 128 字符只留尾部、`exception` 截 4000 字符；投递失败按 60 秒一次限流告警，不逐条刷屏。MQ 三件套名为交换机 `lw.log.exchange`、路由键 `lw.log.index`、队列 `lw.log.index.queue`；知识库解析任务为 `lw.kb.parse`（队列 `lw.kb.parse.queue`）。

**建表与拓扑对账**：`ManticoreIndexConfig` 启动时经 `LogSearchMapper.ensureTable()` 幂等建表 `/cli` 执行 DDL → `ALTER TABLE ... ADD COLUMN` 逐条补齐历史表 → 最后用属性过滤 SELECT 复验（`/cli` 对失败语句也回显成功）。写入路径在表未就绪时先自保建表，`/insert` 失败时重新对账再重试一次，仍失败仅告警。启动期 `RabbitTopologyGuard` 先于监听容器按代码声明重试一次：历史队列与代码参数不一致（典型是日志队列缺 `x-dead-letter-exchange`）时按声明重建该队列并补齐绑定，避免声明被 406 拒绝后索引链路静默断掉——只动应用自己命名空间内的队列。死信交换机 `lw.log.dlx` + 死信队列 `lw.log.dead.queue` 承接无法消费的毒消息。

**降级**：MQ 不可用跳过索引只落文件；Manticore 检索异常返回空结果并告警，原文仍在滚动文件可查；聚合异常返回空结构，前端显示占位骨架。

### 日志检索：视角 + 全文 + 等值 + 时间

| 维度 | 参数 | 行为 |
|---|---|---|
| 视角 | `monitor:log:global` | 持有 → 按入参 `userId` 反查（可空表示不过滤）；否则服务端强制 `user_id = 当前用户`，传入的 `userId` 被覆盖 |
| 全文 | `keyword` + `field=message` | ICU 分词全文匹配，命中词前端高亮 |
| 等值 | `level` / `logger` / `traceId` / `userId` / `runId` | 属性列等值，可组合；`level` 入参统一转大写 |
| 关键字改判 | `field=logger` / `field=traceId` | 按类名 / 链路标识精确匹配 |
| 时间 | `from` / `to` | 毫秒时间戳，两者都传才生效；前端另给 15m / 1h / 24h / 7d 预设 |

`field` 白名单只开放 `message` / `logger` / `traceId`，非法值抛 `PARAM_INVALID`（不静默丢弃关键字）。排序固定 `ts` 倒序，单页上限 500 条（`offset` 用截断后的页大小计算，避免大 `size` 翻页漏数据）。检索缺省时间范围时服务端默认近 7 天，禁止无界查询。**视角判定只在服务端**：检索与聚合共用同一判定，响应带 `scope` 字段（`global` / `self`）供前端展示视角标识。全局视角传入的 `userId` 必须过 `[A-Za-z0-9_]{1,64}` 白名单才拼入聚合 SQL，非法入参退化为不过滤。

**分布概览**（`GET /log/stats`，过滤参数与视角同检索）：级别计数（`GROUP BY level`）驱动概览卡；时间直方图用 `ts DIV bucketMs` 整数分桶（`/` 在 Manticore 是浮点除，桶起点会漂移），小时分桶上限 168 个（7 天）、天分桶上限 90 个，区间超过 7 天自动改按天分桶（`bucketMs` 回传桶宽）。聚合走 Manticore 分组查询，不拉回内存再数；概览默认窗口近 24 小时，点击某桶把时间范围收敛到该桶。

### 运行记录

```
┌──────────────┬────────────────────────────────────────┐
│ 运行列表      │ 运行详情                                │
│ 过滤：状态 /  │  头部：状态 Tag + 模型 + 耗时 + Token    │
│  时间         │  指标条：耗时 / Token / 迭代 / 停止原因   │
│ 行：时间、状  │  分段切换：时间线 / 工具链 / 链路 /      │
│  态、模型、   │           Run 树 / 用量                  │
│  耗时、Token  │  时间线：按 seq 升序的事件块             │
│ 分页          │  ├ 思考块（可折叠，含字数）              │
│              │  ├ 正文块（Markdown 渲染）              │
│              │  ├ 工具卡（名称 + 状态 + 参数/输出）      │
│              │  └ 模型调用行（轮次 + 用量 + 耗时）       │
│              │  顶部：回放控制条（播放/暂停/速度/进度）  │
└──────────────┴────────────────────────────────────────┘
```

事件按类型折叠为块，与事件流（spec 01）一一对应：

| 事件 | 渲染 |
|---|---|
| `thinking.delta` | 思考块（连续合并，展示字数） |
| `text.delta` | 正文块（Markdown 渲染，流式/回放同路径） |
| `tool.call`（start/end）+ `tool.result` | 工具卡：start 显示参数 JSON，result 显示输出与 `isError`；单帧超 4KB 截断并提示 |
| `model.call`（end） | 模型调用行：轮次、`durationMs`、`usage` |
| `plan.updated` | 计划清单（覆盖式快照） |
| `run.result` / `run.exceed` | 产出块 / 达迭代上限提示 |
| `run.end` / `run.error` | 终态条：状态、`stopReason`、总用量、总耗时 |

**回放是"查看已发生的事"，绝不重新执行**：数据源 `GET /run/events/{runId}`（`afterSeq` 增量拉，单次 500 条封顶），一次拉全量后本地游标按块遍历；控件为播放 / 暂停、速度档 0.5x / 1x / 2x / 4x、进度文本（当前块/总块）；回放态顶部通栏提示条（`--lw-secondary-tint` 底）+"返回实时"。

**事件输出通道**：`service` 只依赖 `RunSink`（`send` / `ping` / `complete`），不感知 SSE 与任何 HTTP 类型；`api/SseRunSink` 是 `SseEmitter` 的适配实现，把事件发成 SSE 帧（`id` = `seq`，`event` = `run.event`），`ping` 发注释帧维持连接，受检 `IOException` 转运行时异常交 service 判定连接断开。`RunService` 用 16ms 节拍推送、200ms 节拍攒批落库，两者解耦；推送时按节流补发 `run.snapshot`（带 `watermark` = 当前最大 `seq`），心跳帧按固定间隔拉活。前端读到 `watermark` 大于本地已收最大 `seq` 即按 `afterSeq` 从库里补齐丢帧（补齐失败不阻断实时流，以 `run.end` 为权威终态）。

### 链路瀑布

时间线回答"发生了什么"，瀑布图回答"时间花在哪"——两者各自独立成一档。

数据源 `GET /trace/spans/{runId}`，片段由 `wb_event` 还原而非另存一份（单次扫描上限 5000 条，偏移量相对最早事件）：

| 片段 | 来源 | 关注点 |
|---|---|---|
| 模型段 | `model.call` 起止（`payload.phase=start/end`） | 每轮推理耗时与 token，一眼看出是不是某轮特别慢 |
| 工具段 | `tool.call` 起止 + `tool.result` | 工具耗时、输出长度与失败标记 |
| 计划点 | `plan.updated` | 零宽度标记，标出计划在时间线上的改点 |

- 片段状态取 `ok` / `error` / `pending`：模型段收尾置 `ok`（耗时优先取 `payload.durationMs`），工具段由 `tool.result` 的 `isError` 决定 `ok` / `error`，计划点恒 `ok`。
- 未收到收尾事件的片段标 `pending`：模型段与工具段都有兜底出口——`tool.call` 的 end 与 `tool.result` 谁先到都收敛到同一处，运行中断也不会漏片段。
- 横轴按**偏移占比**铺满容器（不引入图表库）：片段左边界与宽度 = `offsetMs` / `totalMs`，`totalMs` 取所有片段右边界最大值；输出按 `offsetMs` 升序。
- 模型段与工具段用主紫与辅助紫两档，错误一律语义红——颜色只承担"谁在耗时"，不承担装饰。

### Run 树

`GET /trace/tree/{sessionId}` 按 `wb_run.parent_run_id` 组装，父运行下挂子运行（`task_delegate` 产生）。节点展示状态、耗时、token 与事件条数，点击切换详情。

- 从子运行进入也能拿到整棵树：根节点定义为"父为空或父不在结果集内"，因此入参是子运行时会自动回到树的根。
- 层级用缩进 + 一条连接线表示，不加折叠控件——树的深度实际只有两层，折叠是多余的交互成本。
- 事件条数走一次 `GROUP BY run_id` 聚合（`selectEventCounts`），不按运行逐条 count。

### 用量归因

`GET /trace/usage` 聚合 `wb_run` 账目字段，返回概览加三个分解维度：按天（含运行数与成功率）、按模型（token 降序）、按状态。时间窗由 `from` / `to` 或 `days` 给，缺省回看 30 天。

- 归因只看账目字段（`input_tokens` / `output_tokens` / `duration_ms`），与运行详情同源，不做第二套统计。
- **子运行一并计入**：子运行真实消耗 token，分账反而会让总额对不上；需要拆分时用 Run 树看父子。
- 视角与日志检索同一判据：普通用户只看自己，持有 `monitor:log:global` 的角色看全平台（`user_id` 条件省略），前端不做假设。

### 健康监控

| 组件 | 探测方式 | 超时 |
|---|---|---|
| PostgreSQL | `HealthMapper.ping()`（`SELECT 1`） | 3s |
| Redis | 取一次连接后立即释放 | 2s |
| Manticore | `SELECT log_id FROM lw_log LIMIT 1`（必须带 `FROM <索引>`，非 raw 模式下 `SELECT 1` / `SHOW TABLES` 会被回 501） | 3s |
| MinIO | bucket exists | 5s |
| RabbitMQ | 连接打开 | 3s |
| Python | 版本探测（`PythonRuntime.detect`） | 5s |

逐组件探测提交到入库池（`ExecutorConfig.INDEX_EXECUTOR`）执行，超时与异常统一记 `DOWN` 并带 `costMs` 与失败原因。聚合状态：`UP` 全通，`DEGRADED` 有非核心组件失败，`DOWN` 数据库或 Redis 失败。仪表盘汇总（会话数、运行数、成功率、今日运行、总 Token、运行/Token 趋势、工具分布）缓存 60 秒（Redis），避免高频重算。

## 契约

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/log/search` | `monitor:log:list` | 检索，参数 `keyword` `field` `level` `logger` `traceId` `userId` `runId` `from` `to` `page` `size`，返回带 `scope` |
| GET | `/api/v1/log/stats` | `monitor:log:list` | 分布概览，过滤参数同检索，返回级别计数与时间直方图（带 `scope`） |
| GET | `/api/v1/run/page` | 登录即可 | 运行列表，参数 `sessionId` `state` `from` / `to` `page` / `size`，仅当前用户 |
| GET | `/api/v1/run/detail/{runId}` | 登录即可 | 运行详情，按 `(runId, user_id)` 校验归属 |
| GET | `/api/v1/run/events/{runId}` | 登录即可 | 事件列表，`afterSeq` 增量，单次上限 500 |
| POST | `/api/v1/run/abort/{runId}` | 登录即可 | 中断非终态运行，按 `(runId, user_id)` 校验归属 |
| GET | `/api/v1/trace/spans/{runId}` | 登录即可 | 链路片段，按 `(runId, user_id)` 校验归属 |
| GET | `/api/v1/trace/tree/{sessionId}` | 登录即可 | 会话 Run 树，查询自带 `user_id` 条件 |
| GET | `/api/v1/trace/usage` | 登录即可 | 用量归因，视角由 `monitor:log:global` 决定 |
| GET | `/api/v1/monitor/health` | `monitor:health:list` | 逐组件状态 |
| GET | `/api/v1/monitor/overview` | `monitor:health:list` | 仪表盘汇总 |
| GET | `/api/v1/runtime/python` | `monitor:health:list` | Python 运行时探测结果（来源 / 版本 / 状态） |
| POST | `/api/v1/runtime/python/reprobe` | `monitor:python:reprobe` | 清探测缓存重新探测 |

日志两个接口需 `monitor:log:list`（内置 `user` 角色种子已授予），检索范围由 `monitor:log:global` 决定是否收窄；运行记录与 `trace` 接口登录即可用，归属强制校验，不泄露他人运行。

## 关键流程

```
日志事件
  ├─ Appender 从 ILoggingEvent 事件快照读 traceId / userId / sessionId / runId（缺失填空串）
  ├─ 组装文档：log_id / ts / level / logger / thread / 四个链路字段 / message / exception
  ├─ LogProducer 投递 MQ（失败记 warn 后丢弃，不阻塞业务线程）
  └─ LogConsumer 逐条插入 lw_log（失败仅告警，不重回队列）

页面检索
  ├─ 进入页面：GET /log/stats（默认近 24h）→ 概览卡 + 直方图（带视角标识）
  ├─ 改过滤条件：防抖后并行请求 stats 与 search（同一视角与过滤）
  ├─ 点结果行：展开异常栈；复制 traceId；按 traceId / userId / runId 钻取
  └─ 点直方图柱：时间范围收敛到该桶，两个请求并行刷新

打开运行记录页
  ├─ 1 GET /run/page（默认全部状态，按时间倒序，骨架屏）
  ├─ 2 点行 → GET /run/detail/{runId} 渲染头部与指标条
  ├─ 3 GET /run/events/{runId} 拉全量事件 → 折叠为块 → 时间线
  ├─ 4 非终态 → "中断"按钮可用；中断后收到/拉到终态帧
  └─ 5 点"回放" → 本地游标重放已加载块，不发起任何执行
```

工作台的运行历史可深链 `/runs?runId=...` 直接定位运行详情；深链可达依赖 `WebConfig` 的 SPA 兜底路由（不含点的路径回落 `index.html`，见 docs/ARCHITECTURE.md）。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 文件 + 索引双写 | 既有原文又有检索能力 | 写入路径变长 |
| 经 MQ 削峰 | 索引写入不影响业务 | 展示有秒级延迟 |
| MDC 注入链路标识 | 业务零成本获得全链路串联 | 依赖框架注入点，异步线程需走事件快照 |
| 视角隔离在服务端 | 越权面在服务端闭环，前端拿不到他人日志 | 每条检索多一次权限判定 |
| 降级不阻塞 | 组件挂了应用还能跑 | 故障期间日志不可检索 |
| 只留 30 天 | 存储可控 | 长周期审计需外部归档 |
| 检索字段白名单只开放 message / logger / traceId | 脏入参直接拒绝，避免聚合 SQL 注入 | `exception` 虽是全文列但不能直接检索 |
| 聚合走服务端分组 | 概览不随结果集膨胀 | 依赖 Manticore 聚合能力，异常需降级 |
| RunSink 抽象 + SseRunSink 适配 | service 不依赖 HTTP 类型，通道可替换 | 多一层接口与适配 |
| 运行记录独立成页 | 有空间铺时间线 / 工具链 / 用量 | 多一个路由与菜单项 |
| 回放用本地游标 | 不重新请求、不重新执行，播放零成本 | 回放粒度受已落库事件限制 |
| 事件块而非逐条事件流 | 人能读；心跳与合帧不露出 | 需要一套稳定的折叠规则 |
| 服务端只提供原始事件 | 前端渲染自由演进，不改后端 | 折叠规则改了要回归前端 |
| 瀑布由事件还原 | 不另存一份span，无一致性问题 | 每次打开重扫事件（上限 5000 条） |

## 约束

- 禁止打印密钥、令牌、完整请求体；正文按原文入索引，异常堆栈单独成列。
- Appender 读取链路字段必须走事件快照（`ILoggingEvent.getMDCPropertyMap()`）：Appender 挂在 AsyncAppender 下，异步线程的 ThreadLocal MDC 为空，直接 `MDC.get` 会全部落空。
- 索引创建必须幂等；新增列用 `ALTER TABLE ... ADD COLUMN` 幂等补齐后复验。
- 检索与聚合必须带时间范围或条数上限，禁止无界查询；普通用户的检索条件由服务端强制注入 `user_id`。
- 时间线严格按 `seq` 排序渲染，前端不做重排与去重；回放禁止触发任何写请求（abort 除外）。
- 运行详情、事件、中断、链路片段必须按 `(runId, user_id)` 校验归属，查不到即 `RESOURCE_NOT_FOUND`。
