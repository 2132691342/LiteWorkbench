# 02 · 会话、运行与模型供应方

## 定位

会话是长期容器，运行是短期事实，模型供应方决定"用哪个模型、走哪个端点、带什么密钥"。三者共同回答：一次提问如何被落成可回放的数据、并发如何被挡住、模型客户端从哪来。

边界：本模块管运行状态机、消息落库、会话串行、SSE 输出通道与模型客户端构建；不碰内核装配与工具执行（见 specs/01）、事件表字段（见 docs/DATA-MODEL.md §5.2、§5.3）、接口形状（见 docs/API-CONTRACT.md §7、§8、§13）。

不回答：响应信封与路径约定（见 docs/API-CONTRACT.md）；幂等与限流切面（见 AGENTS.md §5）；不变量表（见 AGENTS.md §18）；线程池清单（见 AGENTS.md §17）；供应方预设的依赖来源（见 AGENTS.md §15.1）。

## 设计

### 运行状态机

```
queued ──► running ──► success   (stop_reason = completed)
              │      └ success   (stop_reason = max_iters，内核发 run.exceed 后正常收敛)
              ├─► failed         (stop_reason = error)
              └─► aborted        (stop_reason = aborted)
```

| 状态 | 终态 | 说明 |
|---|---|---|
| `queued` | 否 | 已创建未开始 |
| `running` | 否 | 执行中 |
| `success` | 是 | 正常结束，`stop_reason` 区分 `completed` / `max_iters` |
| `failed` | 是 | 异常结束 |
| `aborted` | 是 | 被中断 |

停止原因（`StopReasonEnum`）：`completed` / `max_iters` / `aborted` / `error`。Token 用量在推送节拍里从 `model.call`（phase=end）累加到内存（`usage[0]` / `usage[1]`），另计模型调用轮次 `modelCalls` 与工具调用数 `toolCalls`；终态一次性写 `wb_run`，避免每轮更新行。`RunStatusEnum.isTerminal()` 是中断判定的唯一依据。

### 并发控制

同一会话同一时刻只允许一个 running：`POST /chat/stream` 抢 Redis 键 `lw:lock:run:{sessionId}`（`SETNX` + TTL `SessionConst.LOCK_TTL_SECONDS`= `SSE_TIMEOUT_MS/1000 + 60` 秒，即 31 分钟——不得小于 SSE 连接存活上限，否则长任务期间同会话可并发起新运行），命中即抛 `SESSION_BUSY`。锁在终态收尾（`cleanup`）释放；启动阶段就失败且尚无活动实例时直接释放，不留悬挂锁。

### 消息与回放

消息（`user` / `assistant`）不分表存 PG，统一写 Manticore `lw_msg`（`MessageIndexMapper`），文档带 `user_id` 字段；读取按 `session_id` 过滤并排除本轮 `run_id`（`excludeRunId`），按 `(ts, message_id)` 稳定升序——归属由会话归属校验保证，故索引查询不再重复带 `user_id`。`tool` 与 `system` 是内核侧角色，不进 `lw_msg`。会话列表消息上限 `SessionConst.MESSAGE_LIST_LIMIT`(1000)。

运行事件落 `wb_event`（PG，payload 为 jsonb），回放按 `(run_id, seq)` 升序读 `GET /run/events/{runId}?afterSeq=`（单次上限 500 条），前端用同一 reducer 重建时间线，**绝不重新执行**。落库与推送共用 `RunEventCodec` 一处口径，避免两侧字段漂移。会话删除同时清 `lw_msg` 全文文档。

### 模型供应方

供应方配置存 `wb_provider`，为平台级定义（`UNIQUE(provider_code)`），由管理角色维护；密钥加密存储，读取时解密，对外一律脱敏。

**供应方预设的端点与默认模型取自 hutool-ai 的常量，协议固定为 OpenAI 兼容通道**（依赖使用边界见 AGENTS.md §15.1），预设四个：

| 预设 | code | 默认 base_url | 默认模型 | 协议通道 | 密钥 |
|---|---|---|---|---|---|
| OpenAI | `openai` | `https://api.openai.com/v1` | `gpt-4o` | OpenAI 兼容 | 必填 |
| DeepSeek | `deepseek` | `https://api.deepseek.com` | `deepseek-chat` | OpenAI 兼容 | 必填 |
| 豆包 | `doubao` | `https://ark.cn-beijing.volces.com/api/v3` | `doubao-1.5-lite-32k-250115` | OpenAI 兼容 | 必填 |
| Grok | `grok` | `https://api.x.ai/v1` | `grok-2-1212` | OpenAI 兼容 | 必填 |
协议出站唯一实现（`OpenAiCompatClient`，基于 `HttpModelSupport`）：JDK `HttpURLConnection` 建连 → 逐行读取 → 剥 `data:` 前缀（`[DONE]` 终止）→ 解析增量（正文 / 思考 / `tool_calls` 按 index 累积）→ 回调内核监听器。**取消是轮询式**：每个增量行先查取消标志，命中即停止读取并在 `finally` 断开连接。因此任何兼容端点（自建 vLLM、网关等）用自定义 `provider_code` + `base_url` 即可接入。流式出站与工具调用自研，因为需要逐行剥协议前缀、累积工具调用增量、按 `usage` 入账，并让取消立即生效。

同步文本路径（`ModelCaller`，供创作台 AI 辅助、会话自动命名与连通性测试复用）优先走依赖提供的原生同步服务（`ProviderAIConfig` 实现其配置契约），返回空文本或抛异常再回退自研客户端；自研回退路径空文本抛 `MODEL_OUTPUT_INVALID`，出站失败抛 `MODEL_CALL_FAILED`。

**默认供应方**（会话未指定时）按优先级取：启用且 `baseUrl` 与密钥齐备的供应方 → 任一启用项；一个都没有则抛 `PROVIDER_NOT_CONFIGURED` 并提示去设置页配置。**模型名四级兜底**：会话指定 > `wb_provider.default_model` > 预设默认模型（`ProviderPresetEnum`） > `liteworkbench.agent.model.default-name`。**端点兜底**：供应方 `base_url` 为空时回落预设默认值。

**密钥存储**：AES/GCM（12 字节随机 IV 前置后 Base64），解密优先 GCM、失败回退兼容旧记录；密钥由 `liteworkbench.security.secret` 经 SHA-256 摘要取前 16 字节得到。脱敏：明文保留前 3 后 4 位，原文不足 8 位只保留前 3 位加 `***`，解密失败返回 `***`。`update` 收到含 `***` 的值视为不修改密钥。

### 客户端缓存

`ModelFactory` 两级缓存：本地 LRU（容量 64，键为 `provider.model`，`accessOrder=true` 超容淘汰最久未用）存构建好的客户端；Redis `lw:cache:provider:{code}` 存 `providerId`，TTL 5 分钟，未命中回源查库再回填。`update` / `delete` / `create` 主动按 `providerCode` 前缀清本地缓存；Redis 侧没有独立失效，靠 TTL 兜底（回源查不到行时会删缓存键）。

## 契约

```java
public interface ModelClient {

    /** 协议码：openai（兼容族） */
    String protocol();

    /** 客户端实际使用的模型名 */
    String modelName();

    /** 流式对话：逐块回调增量，阻塞至流结束、超时或取消；取消以停止读取并断开连接实现，
        cancelled 为真时必须立即返回 */
    ModelTypes.StreamResult stream(ModelTypes.Request request, ModelEventListener listener,
                                   BooleanSupplier cancelled);
}
```

`Request` 携带 `messages`、`tools`、`temperature`、`maxTokens`、`timeoutSeconds`；`StreamResult` 汇总 `text`、`thinking`、`toolCalls`、`usage`、`finishReason`。读超时取 `Request.timeoutSeconds`，未传时用 `AgentProperties.model.stream-timeout-seconds`。

```java
public interface RunSink {

    /** 推送一条事件，seq 用作帧 id，payloadJson 为事件序列化结果 */
    void send(long seq, String eventType, String payloadJson);

    /** 心跳帧，维持连接 */
    void ping();

    /** 正常收尾 */
    void complete();
}
```

`service` 只依赖 `RunSink`，HTTP 类型只在 `api` 层由 `SseRunSink` 实现（`run.event` 单通道，帧 `id` 为 `seq`；心跳是 `: ping` 注释帧）。接口路径与请求 / 响应形状见 docs/API-CONTRACT.md §7、§8、§13。

## 关键流程

```
POST /chat/stream（入口带 @RateLimit(20/分钟/用户) 与 @Idempotent(ttl=3)，见 AGENTS.md §5）
  ├─ 1 校验会话归属（user_id）
  ├─ 2 抢 Redis 锁 lw:lock:run:{sessionId}，失败抛 SESSION_BUSY
  ├─ 3 解析供应方：会话指定则 loadProvider，否则 resolveDefaultProvider；再解析模型名（四级兜底）
  ├─ 4 落 wb_run(queued) 与用户消息（lw_msg），置 running，会话计数 +1
  ├─ 5 装配系统提示：平台提示 + 会话补充 + 技能展开（specs/03，含输入里点名的 skillCodes）+ @ 引用上下文（知识库预取 / 文件正文 / 文档正文）+ 长期记忆（追加在末尾）
  ├─ 6 建 RunContext（含最近 history.limit 条历史，排除本轮 runId），AgentRuntime.start()
  │     → 句柄存活动运行表；事件经 RunSink 进待推送缓冲
  │     → 推送 16ms 合帧、落库 200ms 批量（specs/01）
  ├─ 7 句柄 completion 回调收尾：成功 / 失败 / 中断三选一，写 wb_run 终态、助手消息、
  │     会话用量；终态帧先落库再推送；随后关闭 SseEmitter、清活动表、放锁
  └─ 8 收尾后执行 postSettle：首轮对话标题仍为默认值时异步自动命名（静默失败）
    启动阶段抛错时兜底：有活动实例走结算，否则直接置 failed 并放锁，不留 queued 僵尸行

POST /run/abort/{runId}
  ├─ 1 归属校验 + 终态判定：已是终态抛 RUN_NOT_ABORTABLE
  ├─ 活动运行表有句柄：cancel() 置取消标志（内核在下一个检查点停止），随后立即结算 aborted 并推 run.end
  └─ 无（进程重启后的孤儿运行）：按库中记录直接结算 aborted

通道关闭（api 层在 SSE 完成 / 超时 / 出错时回调 onChannelClosed）
  └─ 有活动实例即 cancel() + 结算 aborted
```

取消是双保险：`cancel()` 让内核在下一个检查点停止产出，服务侧立刻落终态并释放会话锁，因此内核线程的余下事件被丢弃在运行表之外；已输出的正文按现有内容写入助手消息。`settleSuccess` / `settleError` 在 CAS 抢占 `settled` 后先判 `handle.isCancelled()`，命中即改走中断收敛，因此 `run.result`（completed）与中断同时到达时，**中断裁决优先**。三条收尾路径都在置终态前先 `flush` 一次（推送 + 落库），保证攒批内事件既能回放也已在客户端可见；失败收尾推 `run.error`，成功与中断都推 `run.end`（payload 的 `status` / `stopReason` 区分）。

```
ModelFactory.client(providerCode, modelName)
  ├─ 1 本地 LRU（容量 64，键为 provider.model）命中即复用
  ├─ 2 供应方配置：Redis lw:cache:provider:{code} 存 providerId，TTL 5 分钟，未命中回源查库
  ├─ 3 校验 enabled，否则抛 PROVIDER_NOT_CONFIGURED
  ├─ 4 构建 ProviderAIConfig：apiUrl（端点兜底）、model、解密后的 apiKey、连接与读超时、temperature
  ├─ 5 无密钥即抛 PROVIDER_NOT_CONFIGURED；构建 OpenAiCompatClient 并落缓存

client.stream(request, listener, cancelled)
  ├─ 1 按协议拼请求体：messages / tools / temperature / stream（OpenAI 侧另开 include_usage）
  ├─ 2 HttpURLConnection 建连，设连接与读超时
  ├─ 3 逐行读：先查 cancelled，再剥 data: 前缀 / 识别 [DONE] / 处理 NDJSON
  └─ 4 解析增量 → onText / onThinking，工具调用按 index 累积 → 返回 StreamResult
```

会话自动命名：首轮对话结束后异步触发（跑在低频后台池 `lwEventExecutor`），标题仍为默认值时把用户首条提问截前 400 字交给 `ModelCaller.completeQuietly` 生成标题，清洗引号 / 标点 / 换行并截 24 字；失败静默保持默认标题，不阻塞对话。

连通性测试：构建该供应方的客户端，走流式通道发一条 `ping`（`maxTokens` 16、超时 15 秒），拿到任意响应即视为连通；失败按 `MODEL_CALL_FAILED` 返回原因。供应方未配置默认模型时测试用 `gpt-4o-mini` 兜底。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 会话串行锁 | 上下文不会错乱 | 并发提问被拒，需前端排队提示 |
| 消息与事件分开存 | 回放渲染快，事件审计全 | 两份数据需终态对齐 |
| 会话逻辑删除 | 误删可恢复 | 查询都要带 `deleted = false` |
| Redis 锁而非数据库锁 | 无需事务持锁，性能高 | Redis 不可用时请求直接失败，不做静默降级 |
| 锁 TTL 覆盖 SSE 超时 | 长任务不会被别的运行挤进来 | 崩溃后最久 31 分钟才自动解锁 |
| 预设端点与模型取自依赖常量 | 前后端协议集合一处定义，接入新厂商只加预设数据 | 依赖未对接的厂商不支持 |
| 兼容族一个客户端覆盖多家 | 新增兼容厂商不动代码 | 厂商字段差异需在解析层兜底 |
| 密钥加密入库 | 页面可改，无需重启 | 需保管 `liteworkbench.security.secret` |
| 供应方配置 Redis 缓存 5 分钟 | 每轮对话不查库 | 变更需主动失效（已在写路径处理） |
| 客户端本地 LRU 缓存 | 省去每次运行的构建与解密开销 | 容量 64，超出按最近最少使用淘汰 |
| 同步路径优先依赖原生服务 | 代码量少，行为与依赖一致 | 需要回退通道保证异常路径可用 |
| 供应方平台级 | 管理一处，全员可用 | 不能按用户隔离密钥 |
| 消息存 Manticore 不存 PG | 全文检索免费获得，写路径短 | 消息资产依赖 Manticore 持久化 |
| 终态一次性落库 | 每轮不更新行，写放大低 | 断电会丢当前运行的用量统计 |
| 终态帧合成而非内核产出 | 内核只负责干活，状态只有一处写 | 终态帧与内核事件流不同源，回放需按 type 归档 |

## 约束

- 所有查询必须带 `deleted = false`，用户资源必须带 `user_id` 条件；单资源按 `(主键, user_id)` 查询，查不到即 `RESOURCE_NOT_FOUND`（运行 / 会话为各自的 NOT_FOUND 码）。
- 事件写入失败不得中断流式输出，记 error 后继续；回放只读已落库事件，不重新执行。
- 中断只允许作用于非终态运行，否则抛 `RUN_NOT_ABORTABLE`。
- 密钥不得写入日志；对外脱敏保留前 3 后 4 位，原文长度不足 8 位时只显示前 3 位加 `***`。
- 云端预设未配置密钥时构建即抛 `PROVIDER_NOT_CONFIGURED`，不做静默兜底；供应方变更必须主动清本地客户端缓存。
- 工具调用（`tools`）必须在两条协议通道下都能下发。
- 终态（成功 / 失败 / 中断）都必须写 `wb_run` 终态、写助手消息（正文为空则跳过）、释放会话锁并关闭 SseEmitter；三者缺一都会留下前端"永远运行中"的状态。
