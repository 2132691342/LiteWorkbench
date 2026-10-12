# 01 · Agent 运行时

## 定位

把"模型怎么想、工具怎么用、循环什么时候停"实现到底，并把它做成一个可离线单测的纯逻辑内核。本模块不做业务判断，不碰数据库。

边界：向下只依赖 `agent/model`（模型出站）与 `agent/tool`（工具实现），向上只暴露 `AgentRuntime` 一个门面。不依赖 `api` 层，不直接访问 `mapper`。

## 设计

Agent **无状态**：上下文不存在 Agent 里，每次运行由工作台从 Manticore 消息表 `lw_msg` 取最近 `agent.history.limit` 条历史，经 `AgentMemory` 压缩后显式传入。`ModelClient`（持有 HTTP 客户端与连接策略）按 `provider + model` 缓存复用；`AgentLoop` 每次运行新建。

| 组件 | 职责 |
|---|---|
| `AgentRuntime` | 门面接口：发起一次运行，把内核事件推给订阅者，返回可取消句柄 |
| `AgentKernelRuntime` | 实现：装配 `AgentLoop`、工具集、技能提示词与模型客户端 |
| `AgentLoop` | 推理循环本体：思考 → 工具调用 → 观察 → 收敛；零框架依赖，构造期注入全部协作者 |
| `RunHandle` | 运行句柄：`cancel()` 协作式取消，`await()` 等待终态 |
| `AgentMemory` | 上下文组装与确定性压缩（不调模型）：协议配对修复、Token 估算、切点选择 |
| `AgentKernelProperties` | 迭代上限、上下文窗口、预留 token、超时、历史条数 |
| `agent/model/*Client` | 三协议出站：OpenAI 兼容 / Anthropic / Ollama |
| `agent/tool/ToolRegistry` | 工具注册表：注册即编译参数 Schema，调用前校验参数 |
| `agent/tool/WorkbenchTools` | 内置工具集：`python_exec`、`file_read`、`file_write`、`http_get`、`kb_search` |
| `agent/spi/ToolGate` | 工具启停端口：由 `service` 侧实现，禁用工具不下发给模型 |
| `agent/event/*` | 内核事件类型与 `AgentEvent` 载体 |

构建参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `agent.loop.max-iters` | 20 | 单轮最大迭代，超出发 `EXCEED_MAX_ITERS` |
| `agent.loop.repeat-guard` | 3 | 同一工具同一参数在整个运行内累计 N 次即判失败，逼模型换路 |
| `agent.model.default-name` | `qwen-plus` | 三级模型名兜底的最后一档 |
| `agent.model.temperature` | 0.3 | 采样温度 |
| `agent.model.context-window` | 32000 | 上下文窗口，超过即压缩 |
| `agent.model.reserve-tokens` | 4096 | 预留给输出的 token |
| `agent.model.stream-timeout-seconds` | 180 | 流式调用超时 |
| `agent.history.limit` | 40 | 历史消息条数上限 |

## 契约

```java
public interface AgentRuntime {

    /** 发起一次运行，事件经订阅者回调，返回可取消句柄 */
    RunHandle start(RunContext context, Consumer<RunEventDTO> sink);
}
```

`RunContext` 携带 `userId`、`sessionId`、`runId`、`providerCode`、`modelName`、`systemPrompt`（已含技能装配）`history` 与 `prompt`。`RunHandle` 的 `cancel()` 是幂等的，可在任意线程调用。

## 关键流程

```
start(context, sink)
  ├─ 1 ModelFactory.client(provider, model)：命中缓存则复用，否则解密密钥并构建
  ├─ 2 ToolRegistry.enabled(...)：从 wb_tool 读启停，只注册启用的工具
  ├─ 3 AgentMemory.assemble(history, prompt)：协议修复 → 估算 token → 超窗口则在 user 边界裁切
  ├─ 4 new AgentLoop(协作者…) → 后台线程执行
  └─ 5 每产出一个事件：分配 seq → sink.accept → 运行结束发 AGENT_END
```

循环体（`AgentLoop#run`）：

```
for (iter = 1; iter <= maxIters; iter++)
  ├─ 取消检查：命中即按 aborted 收敛
  ├─ emit MODEL_CALL_START → client.stream(...) 逐块回调
  │     ├─ 思考块 → THINKING_DELTA
  │     └─ 正文块 → TEXT_DELTA
  ├─ 累积完整回复（含 tool_calls），emit MODEL_CALL_END（带 usage 与耗时）
  ├─ 有工具调用：
  │     ├─ emit TOOL_CALL_START
  │     ├─ 重复调用护栏：同工具同参数累计超限即以 IsError 结果逼模型换路
  │     ├─ registry.invoke(...) 带超时执行，输出裁剪后 emit TOOL_RESULT
  │     └─ 结果按调用下标严格回填进消息列表（顺序不能乱）
  └─ 无工具调用且正文非空 → emit AGENT_RESULT → 收敛 completed
```

**终止条件**：无工具调用且产出非空正文即结束；只有思考没有正文不结束；达到迭代上限发 `EXCEED_MAX_ITERS` 并把未完成的工具补错误结果后收敛 `max_iters`。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 内核零框架依赖、全部协作者构造注入 | 不联网不起 Spring 即可单测整条循环 | 装配代码由自己写 |
| 每次运行新建 `AgentLoop` | 天然无跨会话状态，工具启停即时生效 | 每轮少量构建开销 |
| `ModelClient` 按 provider+model 缓存 | 省去连接与策略的重复构建 | 供应方配置变更需主动失效缓存 |
| 上下文由消息索引显式组装 | 只有一份事实来源，回放与重放一致 | 每轮多读一次历史消息 |
| 工具结果按调用下标严格回填 | 模型看到的顺序与它声明的顺序一致 | 并发执行后需要缓冲重排 |
| 协作式取消 | 不产生半写状态，无 Thread.stop 风险 | 取消延迟取决于最近的检查点 |

## 约束

- 禁止在本模块引用 `api` 层任何类型。
- 禁止在 `AgentLoop` 内直接 `new` 任何协作者；模型、工具、审批、事件出口全部由构造函数传入。
- 每个模型调用必须带超时：流式 180 秒（`agent.model.stream-timeout-seconds`），同步 120 秒。同步路径超时抛 `MODEL_CALL_TIMEOUT`；流式路径超时以 `AGENT_ERROR` 收敛，运行落 failed。
- 工具输出进入上下文前必须经过控制字符清理与长度裁剪（保留结尾，见 spec 03）。
