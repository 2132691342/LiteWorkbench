# 08 · 日志与可观测

## 定位

日志既要能翻原文，也要能检索。前者靠文件，后者靠 Redisearch。健康检查则回答"哪个组件挂了"。

边界：本模块只做采集、落盘、索引与探测，不改业务代码的日志内容。

## 设计

### 双写

```
业务 log.info
  → Logback AsyncAppender
      ├─ RollingFileAppender：按天 + 按级别
      └─ RedisearchAppender：经 MQ 异步入索引
```

MQ 不可用时跳过索引，只留文件，不阻塞业务线程。

### 文件滚动

| 文件 | 策略 | 保留 |
|---|---|---|
| `liteworkbench.log` | 按天切分，单文件 100MB | 30 天 |
| `liteworkbench-error.log` | 按天切分 + 级别过滤 WARN/ERROR | 30 天 |
| `liteworkbench-agent.log` | 按天切分，Agent 链路与运行编排专用 | 30 天 |

三个文件与检索索引的关系：所有级别日志进 `liteworkbench.log`；WARN/ERROR 另进 error 文件；`agent` / `runtime` / `RunService` 的日志除进索引外，另进 agent 文件，避免 Agent 高频日志冲淡全量文件。

### Redisearch 索引

索引 `idx:lw:log`，字段见 [DATA-MODEL §6](../docs/DATA-MODEL.md#6-redisearch-索引)。检索语法：

```
@level:{ERROR|WARN} @message:(timeout OR 超时)
```

### 健康探测

| 组件 | 探测方式 | 超时 |
|---|---|---|
| PostgreSQL | `SELECT 1` | 3s |
| Redis | `PING` | 2s |
| MinIO | bucket exists | 5s |
| RabbitMQ | 连接打开 | 3s |
| Python | 版本探测 | 5s |

聚合状态：`UP` 全通，`DEGRADED` 有非核心组件失败，`DOWN` 数据库或 Redis 失败。索引文档键带 30 天 TTL，过期由 Redis 自动回收；不做定时清理任务。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/log/search` | 检索，参数 `keyword` `level` `from` `to` `page` `size` |
| GET | `/api/v1/monitor/health` | 逐组件状态 |
| GET | `/api/v1/monitor/overview` | 仪表盘汇总 |

## 关键流程

```
日志事件
  ├─ 组装文档：log_id / ts / level / logger / thread / trace_id / message / exception
  ├─ 投递 MQ（失败记 warn 后丢弃）
  └─ Consumer 逐条 HSET 并设置 30 天 TTL
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 文件 + 索引双写 | 既有原文又有检索能力 | 写入路径变长 |
| 经 MQ 削峰 | 索引写入不影响业务 | 展示有秒级延迟 |
| 降级不阻塞 | 组件挂了应用还能跑 | 故障期间日志不可检索 |
| 只留 30 天 | 存储可控 | 长周期审计需外部归档 |

## 约束

- 禁止打印密钥、令牌、完整请求体。
- 单条消息超过 4000 字符时截断后再入索引。
- 索引创建必须幂等（`FT.INFO` 探测后再 `FT.CREATE`）。
- trace_id 与响应信封的 `requestId` 必须一致。
