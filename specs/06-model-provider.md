# 06 · 模型供应方

## 定位

把"用哪个模型、走哪个端点、带什么密钥"从代码里抽出来，使模型配置可在页面修改，改完立即生效。

边界：只负责构建模型客户端、协议出站、连通性测试与密钥加解密，不做计费统计等业务。

## 设计

供应方配置存 `wb_provider`，为**平台级定义**（`UNIQUE(provider_code)`），由管理角色维护；密钥加密存储，读取时解密，对外一律脱敏。

**协议集合与 hutool-ai 对齐：依赖对接了哪些厂商的 chat，产品就支持哪些，不自行发明协议，也不做依赖未支持的协议（如 Anthropic）。** 预设五个，全部取 hutool-ai 各 `XxxConfig` 的默认端点与默认模型常量：

| 预设 | code | 默认 base_url | 默认模型 | 协议通道 | 密钥 |
|---|---|---|---|---|---|
| OpenAI | `openai` | `https://api.openai.com/v1` | `gpt-4o` | OpenAI 兼容 | 必填 |
| DeepSeek | `deepseek` | `https://api.deepseek.com` | `deepseek-chat` | OpenAI 兼容 | 必填 |
| 豆包 | `doubao` | `https://ark.cn-beijing.volces.com/api/v3` | `doubao-1.5-lite-32k-250115` | OpenAI 兼容 | 必填 |
| Grok | `grok` | `https://api.x.ai/v1` | `grok-2-1212` | OpenAI 兼容 | 必填 |
| Ollama | `ollama` | `http://localhost:11434` | 部署方填写 | Ollama | 不需要 |

协议通道由 `provider_code` 判定：命中 `ollama` 走 Ollama（`/api/chat`，NDJSON，一行一个 JSON）；**其余一律按 OpenAI 兼容处理**（`{baseUrl}/chat/completions`，`data:` SSE + `[DONE]` 终止），因此任何 OpenAI 兼容端点（自建 vLLM、OneAPI 网关等）用自定义 `provider_code` + `base_url` 即可接入。预设码是"端点与模型的默认值来源"，不是协议白名单。

协议出站两个实现，共用 `HttpModelSupport`：JDK `HttpURLConnection` 建连 → 逐行读取 → 剥协议前缀（`data:` / NDJSON）→ 解析增量 → 回调内核监听器。**取消以关闭连接实现**，所以流式路径是自研的：需要有 `tools` 下发能力与可中断的句柄。hutool-ai 的聊天接口只返回裸 JSON、流式回调拿原始行、不支持工具调用，因此同步补全复用依赖、流式与工具调用自研。

同步文本路径（供应方连通性测试、轻量补全）与流式路径不同：它复用 hutool-ai 的原生服务。`ProviderAIConfig` 实现 hutool-ai 的 `AIConfig` 契约，把 `apiUrl` / `apiKey` / `model` / 连接与读超时 / 动态参数集中在一处；供应方码命中 hutool-ai 原生实现时先走 `AIUtil.chat(...)`，返回空文本或抛异常则回退自研客户端，因此正常路径吃依赖原生实现，异常路径不影响可用性。

`ModelClient` 统一契约：

```java
public interface ModelClient {

    /** 协议码：openai（兼容族）/ ollama */
    String protocol();

    /** 模型名，用于日志与事件载荷 */
    String modelName();

    /** 流式对话：逐块回调增量，阻塞至流结束、超时或取消 */
    ModelTypes.StreamResult stream(ModelTypes.Request request, ModelEventListener listener,
                                   BooleanSupplier cancelled);
}
```

`Request` 携带 `messages`、`tools`、`temperature`、`maxTokens`、`timeoutSeconds`（内核固定走流式，因此没有 `stream` 开关）；`StreamResult` 汇总 `text`、`thinking`、`toolCalls`、`usage`、`finishReason`。

构建参数：

| 参数 | 来源 |
|---|---|
| `apiKey` | `wb_provider.api_key_enc` 解密后（`ollama` 不传） |
| `modelName` | 会话指定 > `wb_provider.default_model` > `liteworkbench.agent.model.default-name`（三级兜底） |
| `baseUrl` | `wb_provider.base_url`（建时留空则回落预设默认值） |
| `temperature` | `liteworkbench.agent.model.temperature` |
| 超时 | 建连 `model.connect-timeout-seconds`，流式读 `model.stream-timeout-seconds`，同步读 `model.sync-timeout-seconds` |

**默认供应方**（会话未指定时）按优先级取：启用且 `baseUrl` 与密钥齐备的云端供应方（openai / deepseek / doubao / grok）→ 启用的 `ollama` → 任一启用项；一个都没有则抛 `PROVIDER_NOT_CONFIGURED` 并提示去设置页配置。

**密钥存储**：AES/GCM（随机 IV 前置后 Base64），解密兼容旧的 AES/ECB 格式；密钥由 `liteworkbench.security.secret` 摘要取前 16 字节得到。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/provider/list` | 列表，登录即可用，密钥脱敏为 `sk-***abcd` |
| GET | `/api/v1/provider/presets` | 预设列表（code / name / baseUrl / defaultModel），新建表单下拉用 |
| POST | `/api/v1/provider/create` | 新建，权限 `wb:provider:create` |
| POST | `/api/v1/provider/update` | 更新，权限 `wb:provider:update`（改完主动清缓存） |
| POST | `/api/v1/provider/delete/{providerId}` | 删除，权限 `wb:provider:delete` |
| POST | `/api/v1/provider/test/{providerId}` | 连通性测试，权限 `wb:provider:update` |

连通性测试走流式通道发一条 `ping`（`maxTokens` 限制为 16、超时 15 秒），拿到任意响应即视为连通；耗时只记入日志，返回体仍是供应方信息（不含耗时字段）。失败按 `MODEL_CALL_FAILED` 返回原因。

## 关键流程

```
ModelFactory.client(providerCode, modelName)
  ├─ 1 本地 LRU（容量 64，键为 provider.model）命中即复用
  ├─ 2 供应方配置：Redis lw:cache:provider:{code} 存 providerId，TTL 5 分钟，未命中回源查库
  ├─ 3 校验 enabled，否则抛 PROVIDER_NOT_CONFIGURED
  ├─ 4 解密 apiKey（ollama 跳过），其余预设无密钥即抛错
  └─ 5 按协议通道构建客户端（openai 兼容 / ollama）并落缓存
```

```
client.stream(request, listener, cancelled)
  ├─ 1 按协议拼请求体：messages / tools / temperature / stream
  ├─ 2 HttpURLConnection 建连，设连接与读超时
  ├─ 3 逐行读：剥 data: 前缀 / 识别 [DONE] / 处理 NDJSON
  ├─ 4 解析增量 → listener.onText / onThinking，工具调用按 index 累积
  └─ 5 流结束、超时或取消 → 返回 StreamResult（含 usage 与 finishReason）
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 协议集合对齐 hutool-ai，只做兼容族与 Ollama | 预设端点与模型直接复用依赖常量，前后端协议集合一处定义，零协议发明成本 | hutool-ai 未对接的厂商（Anthropic / Gemini）不支持，需要时先给依赖提 PR |
| 兼容族一个客户端覆盖多家 | 新增兼容厂商只加预设数据，不动代码 | 厂商字段差异（如思考块）需在解析层逐家兜底 |
| 协议差异收敛在 `model` 包 | 内核与前端只见统一事件，换协议零改动 | 新增协议必须补齐监听器与工具调用解析 |
| 密钥加密入库 | 页面可改，无需重启 | 需保管加密密钥（`liteworkbench.security.secret`） |
| 供应方配置 Redis 缓存 5 分钟 | 每轮对话不查库 | 页面变更需主动失效（已在 update/delete 内处理） |
| 客户端本地 LRU 缓存 | 省去每次运行的构建与解密开销 | 容量 64，超出按最近最少使用淘汰 |
| 同步路径优先 hutool-ai | 依赖原生实现，代码量少 | 需要回退通道保证异常路径可用 |
| 供应方平台级 | 管理一处，全员可用 | 不能按用户隔离密钥 |
| 连通测试发真实请求 | 结果可信 | 消耗少量 token |

## 约束

- 密钥不得写入日志；对外脱敏保留前 3 后 4 位（如 `sk-***abcd`），原文长度不足 8 位时只显示前 3 位加 `***`。
- 云端预设未配置密钥时构建即抛 `PROVIDER_NOT_CONFIGURED`，不做静默兜底。
- 供应方配置变更（新建 / 更新 / 删除）必须主动清缓存，保证下一次请求即生效。
- 工具调用（`tools`）必须在两条协议通道下都能下发。
- 预设表的端点与默认模型必须与 hutool-ai 对应 `XxxConfig` 常量保持同步，修改时两处一起改。
