# 02 · 会话、运行与模型供应方

## 定位

会话是长期容器，运行是短期事实，模型供应方决定"用哪个模型、走哪个端点、带什么密钥"。

边界：本模块管运行状态机、消息落库、会话串行与模型客户端构建；不碰 Agent 装配与 SSE 帧格式（见 spec 01 与 `docs/API-CONTRACT.md`）。

## 设计

### 运行状态机

```
queued ──► running ──► success
              │
              ├─► failed
              ├─► aborted
              └─► success (stop_reason = max_iters)
```

| 状态 | 终态 | 说明 |
|---|---|---|
| `queued` | 否 | 已创建未开始 |
| `running` | 否 | 执行中 |
| `success` | 否 | 正常结束 |
| `failed` | 是 | 异常结束 |
| `aborted` | 是 | 被中断 |

停止原因：`completed` / `max_iters` / `aborted` / `error`。Token 用量在事件冲刷时从 `model.call`（phase=end）累加到内存，终态一次性落库，避免每轮更新行。

### 并发控制

同一会话同一时刻只允许一个 running：`POST /chat/stream` 抢 Redis 键 `lw:lock:run:{sessionId}`（带 TTL），命中即抛 `SESSION_BUSY`。中断、异常与正常结束都在 `finally` 释放锁。

### 消息与回放

消息（`user` / `assistant`）不分表存 PG，统一写 Manticore `lw_msg` 并带 `user_id` 过滤；`tool` 与 `system` 为预留角色，当前只写前两种。运行事件落 `wb_event`（PG），回放按 `(run_id, seq)` 升序读 `GET /run/events/{runId}?afterSeq=`，前端用同一 reducer 重建时间线，**绝不重新执行**。

### 模型供应方

供应方配置存 `wb_provider`，为平台级定义（`UNIQUE(provider_code)`），由管理角色维护；密钥加密存储，读取时解密，对外一律脱敏。

**协议集合与 hutool-ai 对齐：依赖对接了哪些厂商的 chat，产品就支持哪些，不自行发明协议，也不做依赖未支持的协议。** 预设五个，全部取 hutool-ai 各 `XxxConfig` 的默认端点与默认模型常量：

| 预设 | code | 默认 base_url | 默认模型 | 协议通道 | 密钥 |
|---|---|---|---|---|---|
| OpenAI | `openai` | `https://api.openai.com/v1` | `gpt-4o` | OpenAI 兼容 | 必填 |
| DeepSeek | `deepseek` | `https://api.deepseek.com` | `deepseek-chat` | OpenAI 兼容 | 必填 |
| 豆包 | `doubao` | `https://ark.cn-beijing.volces.com/api/v3` | `doubao-1.5-lite-32k-250115` | OpenAI 兼容 | 必填 |
| Grok | `grok` | `https://api.x.ai/v1` | `grok-2-1212` | OpenAI 兼容 | 必填 |
| Ollama | `ollama` | `http://localhost:11434` | 部署方填写 | Ollama | 不需要 |

协议通道由 `provider_code` 判定：命中 `ollama` 走 Ollama（`/api/chat`，NDJSON，一行一个 JSON）；**其余一律按 OpenAI 兼容处理**（`{baseUrl}/chat/completions`，`data:` SSE + `[DONE]` 终止），因此任何兼容端点（自建 vLLM、网关等）用自定义 `provider_code` + `base_url` 即可接入。预设码是"端点与模型的默认值来源"，不是协议白名单。

协议出站两个实现（`OpenAiCompatClient` / `OllamaClient`），共用 `HttpModelSupport`：JDK `HttpURLConnection` 建连 → 逐行读取 → 剥协议前缀 → 解析增量 → 回调内核监听器。**取消以关闭连接实现**；流式与工具调用自研（hutool-ai 的聊天接口只返回裸 JSON、不支持工具调用与结构化流式）。同步文本路径复用 hutool-ai 原生服务：`ProviderAIConfig` 实现其 `AIConfig` 契约，命中原生实现先走 `AIUtil.chat(...)`，返回空文本或抛异常则回退自研客户端。

**默认供应方**（会话未指定时）按优先级取：启用且 `baseUrl` 与密钥齐备的云端供应方 → 启用的 `ollama` → 任一启用项；一个都没有则抛 `PROVIDER_NOT_CONFIGURED` 并提示去设置页配置。**模型名三级兜底**：会话指定 > `wb_provider.default_model` > `liteworkbench.agent.model.default-name`。

**密钥存储**：AES/GCM（随机 IV 前置后 Base64），解密兼容 AES/ECB 记录；密钥由 `liteworkbench.security.secret` 摘要取前 16 字节得到。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/session/page` | 分页列表（支持 `state` 与时间范围） |
| GET | `/api/v1/session/detail/{sessionId}` | 详情 |
| POST | `/api/v1/session/create` | 创建 |
| POST | `/api/v1/session/update` | 改标题 / 模型 / 提示词 |
| POST | `/api/v1/session/delete/{sessionId}` | 逻辑删除 |
| GET | `/api/v1/session/messages/{sessionId}` | 消息列表 |
| GET | `/api/v1/run/detail/{runId}` | 运行详情 |
| GET | `/api/v1/run/page` | 运行列表（`sessionId` / `state` / 时间范围可选） |
| GET | `/api/v1/run/events/{runId}` | 事件列表，`afterSeq` 增量，单次上限 500 |
| POST | `/api/v1/run/abort/{runId}` | 中断 |
| POST | `/api/v1/chat/stream` | 对话入口，SSE 流式返回 |
| GET | `/api/v1/provider/list` | 供应方列表，密钥脱敏为 `sk-***abcd` |
| GET | `/api/v1/provider/presets` | 预设列表，新建表单下拉用 |
| POST | `/api/v1/provider/create` | 新建，权限 `wb:provider:create` |
| POST | `/api/v1/provider/update` | 更新，权限 `wb:provider:update`（改完主动清缓存） |
| POST | `/api/v1/provider/delete/{providerId}` | 删除，权限 `wb:provider:delete` |
| POST | `/api/v1/provider/test/{providerId}` | 连通性测试，权限 `wb:provider:update` |

连通性测试走流式通道发一条 `ping`（`maxTokens` 16、超时 15 秒），拿到任意响应即视为连通；失败按 `MODEL_CALL_FAILED` 返回原因。

```java
public interface ModelClient {

    /** 协议码：openai（兼容族）/ ollama */
    String protocol();

    /** 流式对话：逐块回调增量，阻塞至流结束、超时或取消；取消以关闭连接实现，cancelled 为真时必须立即返回 */
    ModelTypes.StreamResult stream(ModelTypes.Request request, ModelEventListener listener,
                                   BooleanSupplier cancelled);
}
```

`Request` 携带 `messages`、`tools`、`temperature`、`maxTokens`、`timeoutSeconds`；`StreamResult` 汇总 `text`、`thinking`、`toolCalls`、`usage`、`finishReason`。

## 关键流程

```
POST /chat/stream
  ├─ 1 校验会话归属（user_id）
  ├─ 2 抢 Redis 锁，失败抛 SESSION_BUSY
  ├─ 3 落 wb_run（queued）与用户消息（lw_msg）
  ├─ 4 建 SseEmitter，注册超时与完成回调
  ├─ 5 AgentRuntime.start()：事件攒批落库 + 合帧推送，句柄存入活动运行表
  ├─ 6 终态更新 wb_run 与助手消息；首轮对话标题仍为默认值时异步自动命名（静默失败）
  └─ 7 finally 释放锁，关闭 SseEmitter

POST /run/abort/{runId}
  ├─ 活动运行表有句柄：cancel()（置取消标志 + 关闭流）+ 立即结算 aborted 并推 run.end
  └─ 无（进程重启后的孤儿运行）：按库中记录直接结算 aborted
```

取消是双保险：`cancel()` 让内核在下一个检查点停止产出，服务侧立刻落终态并释放会话锁，因此内核线程的余下事件被丢弃在运行表之外；已输出的正文按现有内容写入助手消息。`run.result`（completed）与中断同时到达时，**中断裁决优先**。

```
ModelFactory.client(providerCode, modelName)
  ├─ 1 本地 LRU（容量 64，键为 provider.model）命中即复用
  ├─ 2 供应方配置：Redis lw:cache:provider:{code} 存 providerId，TTL 5 分钟，未命中回源查库
  ├─ 3 校验 enabled，否则抛 PROVIDER_NOT_CONFIGURED
  ├─ 4 解密 apiKey（ollama 跳过），云端预设无密钥即抛错
  └─ 5 按协议通道构建客户端并落缓存

client.stream(request, listener, cancelled)
  ├─ 1 按协议拼请求体：messages / tools / temperature / stream
  ├─ 2 HttpURLConnection 建连，设连接与读超时
  ├─ 3 逐行读：剥 data: 前缀 / 识别 [DONE] / 处理 NDJSON
  └─ 4 解析增量 → onText / onThinking，工具调用按 index 累积 → 返回 StreamResult
```

会话自动命名：首轮对话结束后异步触发，标题仍为默认值时把用户首条提问交给 `ModelCaller`（hutool-ai 同步通道）生成标题；失败静默保持默认标题，不阻塞对话。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 会话串行锁 | 上下文不会错乱 | 并发提问被拒，需前端排队提示 |
| 消息与事件分开存 | 回放渲染快，事件审计全 | 两份数据需终态对齐 |
| 逻辑删除 | 误删可恢复 | 查询都要带 `deleted = false` |
| Redis 锁而非数据库锁 | 无需事务持锁，性能高 | Redis 不可用时请求直接失败，不做静默降级 |
| 协议集合对齐 hutool-ai | 预设端点与模型直接复用依赖常量，前后端协议集合一处定义 | 依赖未对接的厂商不支持 |
| 兼容族一个客户端覆盖多家 | 新增兼容厂商只加预设数据，不动代码 | 厂商字段差异需在解析层兜底 |
| 密钥加密入库 | 页面可改，无需重启 | 需保管 `liteworkbench.security.secret` |
| 供应方配置 Redis 缓存 5 分钟 | 每轮对话不查库 | 变更需主动失效（已在 update/delete 内处理） |
| 客户端本地 LRU 缓存 | 省去每次运行的构建与解密开销 | 容量 64，超出按最近最少使用淘汰 |
| 同步路径优先 hutool-ai | 依赖原生实现，代码量少 | 需要回退通道保证异常路径可用 |
| 供应方平台级 | 管理一处，全员可用 | 不能按用户隔离密钥 |
| 消息存 Manticore 不存 PG | 全文检索免费获得，写路径短 | 消息资产依赖 Manticore 持久化 |

## 约束

- 所有查询必须带 `deleted = false`，用户资源必须带 `user_id` 条件；单资源按 `(主键, user_id)` 查询，查不到即 `RESOURCE_NOT_FOUND`。
- 事件写入失败不得中断流式输出，记 error 后继续；回放只读已落库事件。
- 中断只允许作用于非终态运行，否则抛 `RUN_NOT_ABORTABLE`。
- 密钥不得写入日志；对外脱敏保留前 3 后 4 位，原文长度不足 8 位时只显示前 3 位加 `***`。
- 云端预设未配置密钥时构建即抛 `PROVIDER_NOT_CONFIGURED`，不做静默兜底；供应方变更必须主动清缓存。
- 工具调用（`tools`）必须在两条协议通道下都能下发；预设端点与默认模型必须与 hutool-ai 对应常量保持同步。
