# 06 · 模型供应方

## 定位

把"用哪个模型、走哪个端点、带什么密钥"从代码里抽出来，使模型配置可在页面修改，改完立即生效。

边界：只负责构建模型实例与连通性测试，不做计费统计等业务。

## 设计

供应方配置存 `wb_provider`，为**平台级定义**（`UNIQUE(provider_code)`），由管理角色维护；密钥加密存储，读取时解密，对外一律脱敏。协议覆盖三条通用路线，不绑定单一厂商：

| 供应方 | code | 实现类 | 密钥 |
|---|---|---|---|
| OpenAI 兼容 | `openai` | `OpenAIChatModel` | 必填 |
| Anthropic | `anthropic` | `AnthropicChatModel` | 必填 |
| Ollama | `ollama` | `OllamaChatModel` | 不需要 |

OpenAI 兼容端点覆盖 DeepSeek、GLM、Kimi、vLLM、OneAPI 等，通过 `baseUrl` 区分；本地部署优先走 `ollama`。

构建参数：

| 参数 | 来源 |
|---|---|
| `apiKey` | `wb_provider.api_key_enc` 解密后（`ollama` 不传） |
| `modelName` | 会话指定 > `wb_provider.default_model` > `agent.model.default-name`（三级兜底） |
| `stream` | 固定 true |
| `temperature` | `agent.model.temperature`（`openai` / `anthropic` 生效） |
| `baseUrl` | `wb_provider.base_url` |

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/provider/list` | 列表，登录即可用，密钥脱敏为 `sk-***` |
| POST | `/api/v1/provider/create` | 新建，权限 `wb:provider:create` |
| POST | `/api/v1/provider/update` | 更新，权限 `wb:provider:update` |
| POST | `/api/v1/provider/delete/{providerId}` | 删除，权限 `wb:provider:delete` |
| POST | `/api/v1/provider/test/{providerId}` | 连通性测试，权限 `wb:provider:update` |

测试方式：发一条极短 prompt，取首帧即返回，记录耗时。

## 关键流程

```
ModelFactory.build(providerCode, modelName)
  ├─ 1 读 wb_provider（按 code + 5 分钟缓存）
  ├─ 2 校验 enabled，否则抛 PROVIDER_NOT_CONFIGURED
  ├─ 3 解密 apiKey（ollama 跳过）
  ├─ 4 按 code 选实现类并构建
  └─ 5 缓存实例，键为 provider + model
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 密钥加密入库 | 页面可改，无需重启 | 需保管加密密钥 |
| 供应方配置带缓存 | 减少建实例开销 | 缓存 5 分钟；页面变更时主动失效，改完即时生效 |
| 三条通用协议（OpenAI / Anthropic / Ollama） | 不绑定单一厂商，本地模型可跑 | 各家细节差异需靠 baseUrl 与提示规避 |
| 供应方平台级 | 管理一处，全员可用 | 不能按用户隔离密钥 |
| 连通测试发真实请求 | 结果可信 | 消耗少量 token |
| Ollama 不下发采样参数 | 规避扩展库选项差异 | 该通道温度固定为模型默认 |

## 约束

- 密钥不得写入日志；对外脱敏展示为保留前 3 后 4 位（如 `sk-***abcd`），密钥原文长度不足 8 位时仅显示前 3 位加 `***`。
- `openai` / `anthropic` 未配置密钥时构建即抛 `PROVIDER_NOT_CONFIGURED`，不做静默兜底。
- 未配置供应方时，所有对话请求直接抛 `PROVIDER_NOT_CONFIGURED`。
- 模型调用超时统一 180 秒。
