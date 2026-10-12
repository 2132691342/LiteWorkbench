# 06 · 模型供应方

## 定位

把"用哪个模型、走哪个端点、带什么密钥"从代码里抽出来，使模型配置可在页面修改，改完立即生效。

边界：只负责构建模型客户端、三协议出站、连通性测试与密钥加解密，不做计费统计等业务。

## 设计

供应方配置存 `wb_provider`，为**平台级定义**（`UNIQUE(provider_code)`），由管理角色维护；密钥加密存储，读取时解密，对外一律脱敏。协议覆盖三条通用路线，不绑定单一厂商：

| 供应方 | code | 实现类 | 流式形态 | 密钥 |
|---|---|---|---|---|
| OpenAI 兼容 | `openai` | `OpenAiCompatClient` | SSE，`data:` 行 + `[DONE]` 终止 | 必填 |
| Anthropic | `anthropic` | `AnthropicClient` | SSE，具名事件 `content_block_delta` | 必填 |
| Ollama | `ollama` | `OllamaClient` | NDJSON，一行一个 JSON | 不需要 |

OpenAI 兼容端点覆盖 DeepSeek、GLM、Kimi、vLLM、OneAPI 等，通过 `baseUrl` 区分；本地部署优先走 `ollama`。协议判定按 `provider_code` 关键字：命中 `anthropic` / `claude` 走 Anthropic，命中 `ollama` 走 Ollama，其余一律按 OpenAI 兼容处理。

三条出站共用 `HttpModelSupport`：JDK `HttpURLConnection` 建连 → 逐行读取 → 剥协议前缀（`data:` / 具名事件 / NDJSON）→ 解析增量 → 回调内核监听器。**取消以关闭连接实现**，所以流式路径是自研的：需要有 `tools` 下发能力与可中断的句柄。

同步文本路径（辅助写作、供应方连通性测试）与流式路径不同：它复用 hutool-ai 的原生服务。`ProviderAIConfig` 实现 hutool-ai 的 `AIConfig` 契约，把 `apiUrl` / `apiKey` / `model` / 连接与读超时 / 动态参数集中在一处；供应方码命中 hutool-ai 原生实现时先走 `AIUtil.chat(...)`，返回空文本或抛异常则回退自研客户端，因此正常路径吃依赖原生实现，异常路径不影响可用性。

`ModelClient` 统一契约：

```java
public interface ModelClient {

    /** 协议码：openai / anthropic / ollama */
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
| `baseUrl` | `wb_provider.base_url` |
| `temperature` | `liteworkbench.agent.model.temperature` |
| 超时 | 建连 `model.connect-timeout-seconds`，流式读 `model.stream-timeout-seconds`，同步读 `model.sync-timeout-seconds` |

**默认供应方**（会话未指定时）按优先级取：启用且 `baseUrl` 与密钥齐备的云端供应方（openai / anthropic）→ 启用的 `ollama` → 任一启用项；一个都没有则抛 `PROVIDER_NOT_CONFIGURED` 并提示去设置页配置。

**密钥存储**：AES/GCM（随机 IV 前置后 Base64），解密兼容旧的 AES/ECB 格式；密钥由 `liteworkbench.security.secret` 摘要取前 16 字节得到。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/provider/list` | 列表，登录即可用，密钥脱敏为 `sk-***abcd` |
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
  ├─ 4 解密 apiKey（ollama 跳过），openai / anthropic 无密钥即抛错
  └─ 5 按协议构建客户端并落缓存
```

```
client.stream(request, listener, cancelled)
  ├─ 1 按协议拼请求体：messages / tools / temperature / stream
  ├─ 2 HttpURLConnection 建连，设连接与读超时
  ├─ 3 逐行读：剥 data: 前缀 / 识别 [DONE] / 处理具名事件与 NDJSON
  ├─ 4 解析增量 → listener.onText / onThinking，工具调用按 index 累积
  └─ 5 流结束、超时或取消 → 返回 StreamResult（含 usage 与 finishReason）
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 三协议自研而不引厂商 SDK | 依赖面只有 hutool 与 JDK HTTP，无协议包升级风险 | 各家字段差异要自己维护 |
| 协议差异收敛在 `model` 包 | 内核与前端只见统一事件，换协议零改动 | 新增协议必须补齐监听器与工具调用解析 |
| 密钥加密入库 | 页面可改，无需重启 | 需保管加密密钥（`liteworkbench.security.secret`） |
| 供应方配置 Redis 缓存 5 分钟 | 每轮对话不查库 | 页面变更需主动失效（已在 update/delete 内处理） |
| 客户端本地 LRU 缓存 | 省去每次运行的构建与解密开销 | 容量 64，超出按最近最少使用淘汰 |
| 同步路径优先 hutool-ai | 依赖原生实现，代码量少 | 需要回退通道保证异常路径可用 |
| 供应方平台级 | 管理一处，全员可用 | 不能按用户隔离密钥 |
| 连通测试发真实请求 | 结果可信 | 消耗少量 token |

## 约束

- 密钥不得写入日志；对外脱敏保留前 3 后 4 位（如 `sk-***abcd`），原文长度不足 8 位时只显示前 3 位加 `***`。
- `openai` / `anthropic` 未配置密钥时构建即抛 `PROVIDER_NOT_CONFIGURED`，不做静默兜底。
- 供应方配置变更（新建 / 更新 / 删除）必须主动清缓存，保证下一次请求即生效。
- 工具调用（`tools`）必须在三协议下都能下发。
