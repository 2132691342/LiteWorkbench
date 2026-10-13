# 05 · 可观测：日志、运行记录、健康监控

## 定位

三件事各答一个问题：日志回答"系统发生了什么"，运行记录回答"Agent 是怎么跑的"，健康监控回答"哪个组件挂了"。

边界：日志只做采集、落盘、索引与检索，不改业务日志内容；运行记录只做查看与控制（列表、详情、时间线、回放、中断），不重跑、不改写历史；监控只做探测与汇总。

## 设计

### 日志采集：双写

```
业务 log.info（业务代码不手拼链路字段）
  → Logback MDC：traceId / userId / sessionId / runId
  → Logback AsyncAppender
      ├─ RollingFileAppender：按天 + 按级别（pattern 带 %X{traceId} %X{userId}）
      └─ ManticoreLogAppender：读事件快照拼文档 → MQ lw.log → LogConsumer → lw_log
```

链路标识注入点三处，职责不重叠：

| 键 | 注入者 | 时机 | 可空 |
|---|---|---|---|
| `traceId` | `TraceFilter` | 请求进入 | 匿名请求也有（新生成） |
| `userId` | Sa-Token 拦截器（鉴权通过后） | 每请求 | 登录前为空 |
| `sessionId` / `runId` | `RunService`（运行编排开始 / 结束移除） | 一次运行的全程 | 非运行日志为空 |

业务代码禁止手工拼接这四个字段；MDC 由框架写入，Appender 原样读取落 `lw_log` 过滤列。`trace_id` 与响应信封的 `requestId` 必须一致。

**文件滚动**：

| 文件 | 策略 | 保留 |
|---|---|---|
| `liteworkbench.log` | 按天切分，单文件 100MB | 30 天 |
| `liteworkbench-error.log` | 按天切分 + 级别过滤 WARN/ERROR | 30 天 |
| `liteworkbench-agent.log` | 按天切分，Agent 链路与运行编排专用 | 30 天 |

所有级别进全量文件；WARN/ERROR 另进 error 文件；`agent` / `runtime` / `RunService` 的日志另进 agent 文件，避免 Agent 高频日志冲淡全量文件。

**索引表 `lw_log`**（字段见 [DATA-MODEL §6.1](../docs/DATA-MODEL.md)）：`message` 是唯一全文列（ICU 中文分词），`level` / `logger` / `trace_id` / `user_id` / `session_id` / `run_id` / `ts` 是过滤列。保留 30 天，由应用定时任务每日 `DELETE FROM lw_log WHERE ts < 截止毫秒`（Manticore 无文档级 TTL）。

**写入裁剪**（索引体积可控）：`message` 截 4000 字符、`logger` 保留尾部 128 字符、`exception` 截 4000 字符；投递失败按 60 秒一次限流告警，不逐条刷屏。MQ 三件套名为交换机 `lw.log.exchange`、路由键 `lw.log.index`、队列 `lw.log.index.queue`；知识库解析任务为 `lw.kb.parse`。

**降级**：MQ 不可用跳过索引只落文件；Manticore 检索异常返回空结果并告警，原文仍在滚动文件可查；聚合异常返回空结构，前端显示占位骨架。

### 日志检索：视角 + 全文 + 等值 + 时间

| 维度 | 参数 | 行为 |
|---|---|---|
| 视角 | `monitor:log:global` | 持有 → 全局检索；否则服务端强制 `user_id = 当前用户` |
| 全文 | `keyword` + `field=message` | ICU 分词全文匹配，命中词前端高亮 |
| 等值 | `level` / `logger` / `traceId` / `userId` / `runId` | 属性列等值，可组合；`userId` 仅全局视角有效，`runId` 在普通用户视角下只能收窄本人结果 |
| 关键字改判 | `field=logger` | 按类名精确匹配 |
| 链路钻取 | `field=traceId` | 把一次请求的全部日志按 `ts` 升序串成时间线 |
| 时间 | `from` / `to` | 毫秒时间戳，两者都传才生效；前端另给 15m / 1h / 24h / 7d 预设 |

排序固定 `ts` 倒序，分页上限 500 条/页；`traceId` 钻取场景前端按 `ts` 升序重排。**视角判定只在服务端**：检索与聚合共用同一判定函数，普通用户传入的 `userId` 被覆盖为自己（不报错、不泄漏），响应带 `scope` 字段供前端展示视角标识。

**分布概览**（`GET /log/stats`，过滤参数与视角同检索）：级别计数驱动概览卡；时间直方图按小时粒度分桶（上限 168 桶），区间超过 7 天自动改按天分桶（响应回传 `bucketMs` 桶宽），点击某桶把时间范围收敛到该桶。聚合走 Manticore 分组查询，不拉回内存再数。检索缺省时间范围时服务端默认近 7 天，禁止无界查询。

### 运行记录

```
┌──────────────┬────────────────────────────────────────┐
│ 运行列表      │ 运行详情                                │
│ 过滤：状态 /  │  头部：状态 Tag + 模型 + 耗时 + Token    │
│  时间         │  指标条：耗时 / Token / 迭代 / 停止原因   │
│ 行：时间、状  │  分段切换：时间线 | 工具链 | 用量        │
│  态、模型、   │                                        │
│  耗时、Token  │  时间线：按 seq 升序的事件块             │
│ 分页          │  ├ 思考块（可折叠，含字数）              │
│              │  ├ 正文块（Markdown 渲染）              │
│              │  ├ 工具卡（名称 + 状态 + 参数/输出）      │
│              │  └ 模型调用行（轮次 + 用量 + 耗时）       │
│              │  顶部：回放控制条（播放/暂停/速度/进度）  │
└──────────────┴────────────────────────────────────────┘
```

事件按类型折叠为四类块，与事件流（spec 01）一一对应：

| 事件 | 渲染 |
|---|---|
| `thinking.delta` | 思考块（连续合并，展示字数） |
| `text.delta` | 正文块（Markdown 渲染，流式/回放同路径） |
| `tool.call`（start/end）+ `tool.result` | 工具卡：start 显示参数 JSON，result 显示输出与 `isError`；单帧超 4KB 截断并提示 |
| `model.call`（end） | 模型调用行：轮次、`durationMs`、`usage` |
| `run.result` / `run.exceed` | 产出块 / 达迭代上限提示 |
| `run.end` / `run.error` | 终态条：状态、`stopReason`、总用量、总耗时 |

**回放是"查看已发生的事"，绝不重新执行**：数据源 `GET /run/events/{runId}`（`afterSeq` 增量拉，单次 500 条封顶），一次拉全量后本地游标按块遍历；控件为播放 / 暂停、速度档 0.5x / 1x / 2x / 4x、进度文本（当前块/总块）；回放态顶部通栏提示条（`--lw-secondary-tint` 底）+"返回实时"。

### 健康监控

| 组件 | 探测方式 | 超时 |
|---|---|---|
| PostgreSQL | `SELECT 1` | 3s |
| Redis | 取一次连接后立即释放 | 2s |
| Manticore | `SELECT log_id FROM lw_log LIMIT 1`（必须带 `FROM <索引>`，非 raw 模式下 `SELECT 1` 会被回 501） | 3s |
| MinIO | bucket exists | 5s |
| RabbitMQ | 连接打开 | 3s |
| Python | 版本探测 | 5s |

聚合状态：`UP` 全通，`DEGRADED` 有非核心组件失败，`DOWN` 数据库或 Redis 失败。仪表盘汇总（运行数、成功率、Token、工具分布、趋势）缓存 60 秒。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/log/search` | 检索，参数 `keyword` `field` `level` `logger` `traceId` `userId` `runId` `from` `to` `page` `size`，返回带 `scope` |
| GET | `/api/v1/log/stats` | 分布概览，过滤参数同检索，返回级别计数与时间直方图（带 `scope`） |
| GET | `/api/v1/run/page` | 运行列表，参数 `sessionId` `state` `from` / `to` `page` / `size`，仅当前用户 |
| GET | `/api/v1/run/detail/{runId}` | 运行详情，按 `(runId, user_id)` 校验归属 |
| GET | `/api/v1/run/events/{runId}` | 事件列表，`afterSeq` 增量，单次上限 500 |
| POST | `/api/v1/run/abort/{runId}` | 中断非终态运行 |
| GET | `/api/v1/monitor/health` | 逐组件状态，权限 `monitor:health:list` |
| GET | `/api/v1/monitor/overview` | 仪表盘汇总，权限 `monitor:health:list` |
| GET | `/api/v1/runtime/python` | Python 运行时探测结果（来源 / 版本 / 状态），权限 `monitor:health:list` |
| POST | `/api/v1/runtime/python/reprobe` | 清探测缓存重新探测，权限 `monitor:python:reprobe` |

日志中心两个接口登录即可用（范围由 `monitor:log:global` 决定收窄与否）；运行记录全部登录即可用，归属强制校验，不泄露他人运行。

## 关键流程

```
日志事件
  ├─ Appender 从 ILoggingEvent 事件快照读 traceId / userId / sessionId / runId（缺失填空串）
  ├─ 组装文档：log_id / ts / level / logger / thread / 四个链路字段 / message / exception
  ├─ 投递 MQ（失败记 warn 后丢弃，不阻塞业务线程）
  └─ Consumer 逐条插入 lw_log

页面检索
  ├─ 进入页面：GET /log/stats（默认近 24h）→ 概览卡 + 直方图（带视角标识）
  ├─ 改过滤条件：防抖 300ms 后并行请求 stats 与 search（同一视角与过滤）
  ├─ 点结果行：展开异常栈；复制 traceId；按 traceId / userId / runId 钻取
  └─ 点直方图柱：时间范围收敛到该桶，两个请求并行刷新

打开运行记录页
  ├─ 1 GET /run/page（默认全部状态，按时间倒序，骨架屏）
  ├─ 2 点行 → GET /run/detail/{runId} 渲染头部与指标条
  ├─ 3 GET /run/events/{runId} 拉全量事件 → 折叠为块 → 时间线
  ├─ 4 非终态 → "中断"按钮可用；中断后收到/拉到终态帧
  └─ 5 点"回放" → 本地游标重放已加载块，不发起任何执行
```

工作台的运行历史可深链 `/runs?runId=...` 直接定位运行详情。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 文件 + 索引双写 | 既有原文又有检索能力 | 写入路径变长 |
| 经 MQ 削峰 | 索引写入不影响业务 | 展示有秒级延迟 |
| MDC 注入链路标识 | 业务零成本获得全链路串联 | 依赖框架注入点，异步线程需走事件快照 |
| 视角隔离在服务端 | 越权面在服务端闭环，前端拿不到他人日志 | 每条检索多一次权限判定 |
| 降级不阻塞 | 组件挂了应用还能跑 | 故障期间日志不可检索 |
| 只留 30 天 | 存储可控 | 长周期审计需外部归档 |
| 全文列只留 message | 索引体积小、分词歧义少 | logger / traceId 只能等值不能模糊 |
| 聚合走服务端分组 | 概览不随结果集膨胀 | 依赖 Manticore 聚合能力，异常需降级 |
| 运行记录独立成页 | 有空间铺时间线 / 工具链 / 用量 | 多一个路由与菜单项 |
| 回放用本地游标 | 不重新请求、不重新执行，播放零成本 | 回放粒度受已落库事件限制 |
| 事件块而非逐条事件流 | 人能读；心跳与合帧不露出 | 需要一套稳定的折叠规则 |
| 服务端只提供原始事件 | 前端渲染自由演进，不改后端 | 折叠规则改了要回归前端 |

## 约束

- 禁止打印密钥、令牌、完整请求体；正文按原文入索引，异常堆栈单独成列。
- Appender 读取链路字段必须走事件快照（`ILoggingEvent.getMDCPropertyMap()`）：Appender 挂在 AsyncAppender 下，异步线程的 ThreadLocal MDC 为空，直接 `MDC.get` 会全部落空。
- 索引创建必须幂等；新增列用 `ALTER TABLE ... ADD COLUMN` 幂等补齐后复验。
- 检索与聚合必须带时间范围或条数上限，禁止无界查询；普通用户的检索条件由服务端强制注入 `user_id`。
- 时间线严格按 `seq` 排序渲染，前端不做重排与去重；回放禁止触发任何写请求（abort 除外）。
- 运行详情、事件、中断必须按 `(runId, user_id)` 校验归属，查不到即 `RESOURCE_NOT_FOUND`。
