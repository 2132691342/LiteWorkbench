# 01 · Agent 运行时

## 定位

把 AgentScope 的 `ReActAgent` 装配成工作台可用的执行单元，并管好它的生命周期。本模块只做装配与事件出口，不做业务判断。

边界：向下调用 AgentScope SDK，向上只暴露 `AgentRuntime` 一个门面。不依赖 `api` 层。

## 设计

Agent 是**无状态**的：同一个 `provider + model` 组合只构建一个实例并缓存，会话隔离靠 `RuntimeContext.builder().userId(...).sessionId(...)`。

| 组件 | 职责 |
|---|---|
| `AgentRuntime` | 门面：按会话构建/取用 Agent，发起流式调用 |
| `ModelFactory` | 按供应方构建 OpenAI / Anthropic / Ollama 模型实例 |
| `ToolkitFactory` | 按启用的工具定义装配 `Toolkit` |
| `AgentProperties` | 模型、迭代、技能等参数绑定 |

构建参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `agent.loop.max-iters` | 20 | 单轮最大迭代，超出发 `run.exceed` |
| `agent.model.default-name` | `qwen-plus` | 兜底模型名；会话/供应方指定了 `model_name` / `default_model` 时优先用后者 |
| `agent.model.temperature` | 0.3 | 采样温度 |
| `agent.model.stream` | true | 流式开关 |
| `agent.skill.enabled` | true | 是否挂载技能仓库 |

## 契约

```java
public interface AgentRuntime {
    /** 按会话发起一次流式运行，返回归一化事件流 */
    Flux<RunEvent> stream(RunContext context);
    /** 释放指定组合的缓存实例 */
    void evict(String providerCode, String modelName);
}
```

`RunContext` 携带 `userId`、`sessionId`、`runId`、`providerCode`、`modelName`、`systemPrompt`（已含技能装配）、`history` 与 `prompt`。

## 关键流程

```
stream(context)
  ├─ 1 取/建 Agent（按 provider+model 缓存）
  ├─ 2 组装 RuntimeContext（userId + sessionId）
  ├─ 3 agent.streamEvents(new UserMessage(prompt))
  ├─ 4 AgentEventAdapter 归一化 + 分配 seq
  ├─ 5 检测 Redis 中断标记，命中则取消订阅
  └─ 6 终态发 run.end
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| Agent 单例缓存 | 省构建开销与内存 | 工具/供应方变更需主动 `evict` |
| 缓存键为 provider+model | 实例数不随用户数增长 | 供应方密钥全局一份，由平台级配置保证 |
| RuntimeContext 做会话隔离 | 无需每会话一个 Agent | 上下文必须落库 |
| 不启用已废弃的 Hook，用事件流 | 避免未来移除时返工 | 可用扩展点比旧文档少 |
| SDK 返回 Reactor 流，订阅侧转 SseEmitter | SDK 原生 API 零包装损耗 | 需处理背压与取消传播 |

## 集成事实

改动依赖或升级 SDK 版本前先核对这几条。

| 事实 | 说明 |
|---|---|
| groupId 是 `io.agentscope` | 坐标不写成 `agentscope` |
| 流式入口 `ReActAgent.streamEvents(...)` | 返回 `Flux<AgentEvent>`，事件类在 `io.agentscope.core.event.*` |
| 工具注册 `@Tool` + `@ToolParam` → `Toolkit.registerTool(Object)` | 工具方法返回 `String` 即可 |
| 协议转换扩展点是 `Formatter` | OpenAI / Anthropic / Ollama 三协议在此归一，业务侧不感知 |
| 官方 Spring Boot starter 基于 Boot 4.x | 本项目 Boot 3.5.12，**不引**，`config` 包自建 Bean |
| `agentscope-harness` 带重型传递依赖 | **不引**，只用 `agentscope-core` 与三个模型扩展 |
| `Memory` / `UserAgent` / 旧 `agent.Event` 已 `forRemoval` | 禁用，上下文走 `AgentState` |

## 约束

- 禁止在本模块引用 `api` 层任何类型。
- 禁止使用 `@Deprecated(forRemoval)` 的 `Hook`、`Memory`、`UserAgent` 与旧 `agent.Event`。
- 每个模型调用必须带超时，超时归为 `MODEL_CALL_TIMEOUT`。
