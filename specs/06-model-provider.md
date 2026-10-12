# 06 · 模型供应方

## 定位

把"用哪个模型、走哪个端点、带什么密钥"从代码里抽出来，使模型配置可在页面修改，改完立即生效。

边界：只负责构建模型客户端、三协议出站与连通性测试，不做计费统计等业务。

## 设计

供应方配置存 `wb_provider`，为**平台级定义**（`UNIQUE(provider_code)`），由管理角色维护；密钥加密存储，读取时解密，对外一律脱敏。协议覆盖三条通用路线，不绑定单一厂商：

| 供应方 | code | 实现类 | 流式形态 | 密钥 |
|---|---|---|---|---|
| OpenAI 兼容 | `openai` | `OpenAiCompatClient` | SSE，`data:` 行 + `[DONE]` 终止 | 必填 |
| Anthropic | `anthropic` | `AnthropicClient` | SSE，具名事件 `content_block_delta` | 必填 |
| Ollama | `ollama` | `OllamaClient` | NDJSON，一行一个 JSON | 不需要 |

OpenAI 兼容端点覆盖 DeepSeek、GLM、Kimi、vLLM、OneAPI 等，通过 `baseUrl` 区分；本地部署优先走 `ollama`。

三条出站共用 `HttpModelSupport`：JDK `HttpURLConnection` 建连 → 逐行读取 → 剥协议前缀（`data:` / 具名事件 / NDJSON）→ 解析增量 → 回调统一的内核事件（`TEXT_DELTA` / `THINKING_DELTA` / `TOOL_CALL_*` / usage）。**取消即关闭连接**，所以流式路径不走 hutool-ai 的回调式实现。

`hutool-ai` 的用法：`ProviderAIConfig` 直接实现其 `cn.hutool.ai.core.AIConfig` 契约，供应方配置（`apiUrl` / `apiKey` / `model` / 超时 / 动态参数）在这一层统一；同步文本路径（`ModelCaller`）在 hutool-ai 覆盖的供应方码上先调 `AIUtil.chat(...)`，失败或返回空文本再回退自研客户端，因此正常路径吃 hutool-ai 的原生服务，异常路径不影响可用性。

`ModelClient` 统一契约：

```java
public interface ModelClient {

    /** 流式对话：逐块回调内核事件，阻塞至结束或取消 */
    ModelStreamResult stream(ModelRequest request, ModelEventListener listener);

    /** 连通性测试：发一条极短 prompt，取首帧即返回 */
    long ping(ModelRequest request);
}
```

`ModelRequest` 携带消息列表、工具 Schema、`temperature`、`maxTokens`、`stream` 与超时；`ModelStreamResult` 汇总完整正文、`tool_calls` 与 usage。

构建参数：

| 参数 | 来源 |
|---|---|
| `apiKey` | `wb_provider.api_key_enc` 解密后（`ollama` 不传） |
| `modelName` | 会话指定 > `wb_provider.default_model` > `agent.model.default-name`（三级兜底） |
| `baseUrl` | `wb_provider.base_url` |
| `temperature` | `agent.model.temperature` |
| 超时 | 流式 `agent.model.stream-timeout-seconds`，同步 `agent.model.sync-timeout-seconds` |

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/provider/list` | 列表，登录即可用，密钥脱敏为 `sk-***abcd` |
| POST | `/api/v1/provider/create` | 新建，权限 `wb:provider:create` |
| POST | `/api/v1/provider/update` | 更新，权限 `wb:provider:update` |
| POST | `/api/v1/provider/delete/{providerId}` | 删除，权限 `wb:provider:delete` |
| POST | `/api/v1/provider/test/{providerId}` | 连通性测试，权限 `wb:provider:update` |

测试方式：发一条极短 prompt，取首帧即返回，记录耗时。

## 关键流程

```
ModelFactory.client(providerCode, modelName)
  ├─ 1 读 wb_provider（按 code + 5 分钟缓存）
  ├─ 2 校验 enabled，否则抛 PROVIDER_NOT_CONFIGURED
  ├─ 3 解密 apiKey（ollama 跳过）
  ├─ 4 按 code 选实现类并构建
  └─ 5 缓存实例，键为 provider + model
```

```
client.stream(request, listener)
  ├─ 1 按协议拼请求体：messages / tools / temperature / stream
  ├─ 2 建立连接（hutool HttpRequest 或 HttpURLConnection），设连接与读超时
  ├─ 3 逐行读：剥 data: 前缀 / 识别 [DONE] / 处理 NDJSON
  ├─ 4 解析增量 → listener.onText / onThinking / onToolCall / onUsage
  └─ 5 连接关闭或取消 → 返回 ModelStreamResult
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 三协议自研而不引 SDK | 依赖面只有 hutool 与 JDK HTTP，无协议包升级风险 | 各家字段差异要自己维护，需要契约测试兜底 |
| 协议差异收敛在 `model` 包 | 内核与前端只见统一事件，换协议零改动 | 新增协议必须补齐四个回调点 |
| 密钥加密入库 | 页面可改，无需重启 | 需保管加密密钥 |
| 供应方配置带缓存 | 减少建实例开销 | 缓存 5 分钟；页面变更时主动失效，改完即时生效 |
| 供应方平台级 | 管理一处，全员可用 | 不能按用户隔离密钥 |
| 连通测试发真实请求 | 结果可信 | 消耗少量 token |
| `ModelClient` 可被外部关闭连接 | 取消立即生效，不必等超时 | 需要连接与取消标志的显式管理 |

## 约束

- 密钥不得写入日志；对外脱敏展示为保留前 3 后 4 位（如 `sk-***abcd`），密钥原文长度不足 8 位时仅显示前 3 位加 `***`。
- `openai` / `anthropic` 未配置密钥时构建即抛 `PROVIDER_NOT_CONFIGURED`，不做静默兜底。
- 未配置供应方时，所有对话请求直接抛 `PROVIDER_NOT_CONFIGURED`。
- 工具调用（`tools`）必须在三协议下都能下发；协议不支持工具调用时，Agent 退化为纯对话并在日志中 WARN。
