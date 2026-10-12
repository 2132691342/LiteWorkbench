# 08 · 日志与可观测

## 定位

日志既要能翻原文，也要能检索。前者靠文件，后者靠 Manticore。健康检查则回答"哪个组件挂了"。

边界：本模块只做采集、落盘、索引、检索与探测，不改业务代码的日志内容。

## 设计

### 双写

```
业务 log.info
  → Logback AsyncAppender
      ├─ RollingFileAppender：按天 + 按级别
      └─ ManticoreLogAppender：经 MQ 异步入索引
```

MQ 不可用时跳过索引，只留文件，不阻塞业务线程。

### 文件滚动

| 文件 | 策略 | 保留 |
|---|---|---|
| `liteworkbench.log` | 按天切分，单文件 100MB | 30 天 |
| `liteworkbench-error.log` | 按天切分 + 级别过滤 WARN/ERROR | 30 天 |
| `liteworkbench-agent.log` | 按天切分，Agent 链路与运行编排专用 | 30 天 |

三个文件与检索表的关系：所有级别日志进 `liteworkbench.log`；WARN/ERROR 另进 error 文件；`agent` / `runtime` / `RunService` 的日志除进索引外，另进 agent 文件，避免 Agent 高频日志冲淡全量文件。

### Manticore 表

表 `lw_log`，字段与建表语句见 [DATA-MODEL §6.1](../docs/DATA-MODEL.md#61-lw_log平台日志)。列分两类：`message` 是唯一全文列（ICU 中文分词）；`level` / `logger` / `trace_id` / `ts` 是过滤列。

保留 30 天：Manticore 无文档级 TTL，由应用定时任务每日执行 `DELETE FROM lw_log WHERE ts < 截止毫秒`，与文件滚动同周期。

### 检索模型

日志中心不是单关键字框，而是"全文 + 等值过滤 + 时间范围"三维组合：

| 维度 | 参数 | 行为 |
|---|---|---|
| 全文 | `keyword` + `field=message` | ICU 分词全文匹配，命中词前端高亮 |
| 等值 | `level` / `logger` / `traceId` | 属性列等值，可组合 |
| 关键字改判 | `field=logger` | 按类名精确匹配（如 `com.liteworkbench.service.RunService`） |
| 链路钻取 | `field=traceId` 或 `traceId` 参数 | 把一次请求的全部日志按 `ts` 升序串成时间线 |
| 时间 | `from` / `to` | 毫秒时间戳范围，两者都传才生效；前端另给 15m / 1h / 24h / 7d 预设 |

排序固定 `ts` 倒序（新的在前），分页上限 500 条/页。`traceId` 钻取场景下前端按 `ts` 升序重排展示。

### 分布概览

`GET /log/stats` 与检索共用同一过滤条件，返回两级聚合：

- 级别计数：`[{level, count}]`，驱动概览卡（ERROR / WARN / INFO 三张计数卡）。
- 时间直方图：按小时粒度分桶 `[{hour, count}]`，上限 168 桶（7 天）；查询区间超过 7 天时按天分桶。直方图用 ECharts 柱状图，主序列 `--lw-secondary`，点击某桶可把时间范围收敛到该桶。

聚合走 Manticore 分组查询，不把结果集拉回内存再数。

### 降级

| 故障 | 行为 |
|---|---|
| MQ 不可用 | 跳过索引，只落文件；检索返回空并提示"索引通道降级" |
| Manticore 检索异常 | 返回空结果并告警，原文仍在滚动文件可查 |
| Manticore 聚合异常 | stats 返回空结构，前端概览区显示占位骨架 |

### 健康探测

| 组件 | 探测方式 | 超时 |
|---|---|---|
| PostgreSQL | `SELECT 1` | 3s |
| Redis | 取一次连接后立即释放 | 2s |
| Manticore | `SELECT log_id FROM lw_log LIMIT 1`（必须带 `FROM <索引>`；非 raw 模式下 `SELECT 1` / `SHOW TABLES` 会被回 501） | 3s |
| MinIO | bucket exists | 5s |
| RabbitMQ | 连接打开 | 3s |
| Python | 版本探测 | 5s |

聚合状态：`UP` 全通，`DEGRADED` 有非核心组件失败，`DOWN` 数据库或 Redis 失败。日志保留由定时清理任务执行，不做 Redis TTL。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/log/search` | 检索，参数 `keyword` `field` `level` `logger` `traceId` `from` `to` `page` `size` |
| GET | `/api/v1/log/stats` | 分布概览，参数 `from` `to`，返回级别计数与时间直方图 |
| GET | `/api/v1/monitor/health` | 逐组件状态 |
| GET | `/api/v1/monitor/overview` | 仪表盘汇总 |

权限：两者均需 `monitor:log:list`。

## 关键流程

```
日志事件
  ├─ 组装文档：log_id / ts / level / logger / thread / trace_id / message / exception
  ├─ 投递 MQ（失败记 warn 后丢弃）
  └─ Consumer 逐条插入 lw_log 文档

页面检索
  ├─ 进入页面：GET /log/stats（默认近 24h）→ 概览卡 + 直方图
  ├─ 改过滤条件：防抖 300ms 后并行请求 stats 与 search
  ├─ 点结果行：展开异常栈；复制 traceId；按 traceId 钻取
  └─ 点直方图柱：时间范围收敛到该桶，两个请求并行刷新
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 文件 + 索引双写 | 既有原文又有检索能力 | 写入路径变长 |
| 经 MQ 削峰 | 索引写入不影响业务 | 展示有秒级延迟 |
| 降级不阻塞 | 组件挂了应用还能跑 | 故障期间日志不可检索 |
| 只留 30 天 | 存储可控 | 长周期审计需外部归档 |
| 全文列只留 message | 索引体积小、分词歧义少 | logger / traceId 只能等值不能模糊 |
| 聚合走服务端分组 | 概览不随结果集膨胀 | 依赖 Manticore 聚合能力，异常需降级 |

## 约束

- 禁止打印密钥、令牌、完整请求体。
- 正文按原文入索引，不做二次截断；异常堆栈单独成列。
- 索引创建必须幂等（`CREATE TABLE IF NOT EXISTS`）。
- `trace_id` 与响应信封的 `requestId` 必须一致。
- 检索与聚合必须带时间范围或条数上限，禁止无界查询。
