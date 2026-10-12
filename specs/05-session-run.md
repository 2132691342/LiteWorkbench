# 05 · 会话与运行

## 定位

会话是长期容器，运行是短期事实。一次会话包含多次运行，一次运行产生一串事件。

边界：本模块管状态机与持久化，不碰 Agent 装配，不碰 SSE 通道建立。

## 设计

### 运行状态机

```
queued ──► running ──► success
              │
              ├─► failed
              ├─► aborted
              └─► success (stop_reason=max_iters)
```

| 状态 | 终态 | 说明 |
|---|---|---|
| `queued` | 否 | 已创建未开始 |
| `running` | 否 | 执行中 |
| `success` | 是 | 正常结束 |
| `failed` | 是 | 异常结束 |
| `aborted` | 是 | 被中断 |

停止原因：`completed` / `max_iters` / `aborted` / `error`。

### 并发控制

同一会话同一时刻只允许一个 running。用 Redis 键 `lw:lock:run:{sessionId}` 加 TTL 锁，命中则抛 `SESSION_BUSY`。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/session/page` | 分页列表 |
| GET | `/api/v1/session/detail/{sessionId}` | 详情 |
| POST | `/api/v1/session/create` | 创建 |
| POST | `/api/v1/session/update` | 改标题/模型/提示词 |
| POST | `/api/v1/session/delete/{sessionId}` | 逻辑删除 |
| GET | `/api/v1/session/messages/{sessionId}` | 消息列表 |
| GET | `/api/v1/run/detail/{runId}` | 运行详情 |
| GET | `/api/v1/run/page` | 按会话查运行列表 |
| GET | `/api/v1/run/events/{runId}` | 事件列表，支持 `afterSeq` |
| POST | `/api/v1/run/abort/{runId}` | 中断 |

流式对话入口为 `POST /api/v1/chat/stream`（见 ARCHITECTURE §5.1），不在本表重复。

## 关键流程

```
POST /chat/stream
  ├─ 1 校验会话归属（user_id）
  ├─ 2 抢 Redis 锁，失败抛 SESSION_BUSY
  ├─ 3 落 wb_run（queued）与消息索引（user）
  ├─ 4 建 SseEmitter，注册超时与完成回调
  ├─ 5 AgentRuntime.start()，把事件攒批落库 + 推送，句柄存入活动运行表
  ├─ 6 终态更新 wb_run 与消息索引（assistant），从活动运行表摘除
  └─ 7 finally 释放锁，关闭 SseEmitter
```

`POST /run/abort/{runId}` 从活动运行表取句柄调 `cancel()`；表里没有即视为孤儿运行，直接结算为 `aborted`。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 会话串行锁 | 上下文不会错乱 | 并发提问被拒，需前端排队提示 |
| 消息与事件分开存 | 回放渲染快，事件审计全 | 两份数据需终态对齐 |
| 逻辑删除 | 误删可恢复 | 查询都要带 `deleted = false` |
| Redis 锁而非数据库锁 | 无需事务持锁，性能高 | Redis 是登录与锁的强依赖，不可用时请求直接失败，不做静默降级 |

**角色与消息**：消息索引的 `role_code` 有 `user` / `assistant` / `tool` / `system` 四种取值，`tool` 预留给工具产物，当前只写 `user` 与 `assistant`。

## 约束

- 所有查询必须带 `deleted = false`，用户资源必须带 `user_id` 条件。
- 事件写入失败不得中断流式输出，记 error 日志后继续。
- 中断只允许作用于 `running` 状态；中断与正常结束同时到达时按中断裁决。
- Token 用量只在终态入账一次，`model.call` 事件不参与累加。
- 回放只重放已落库的事件，不重新执行 Agent。
- 会话与运行按 `user_id` 归属，用户只能操作自己的数据。
