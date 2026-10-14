# 01 · Agent 内核

## 定位

把"模型怎么想、工具怎么用、循环什么时候停"实现到底：一个不碰数据库、不碰 HTTP 层的纯逻辑内核，模型协议差异在 `agent/model` 内被拉平，事件归一在同一处完成，前端只认一种事件流。

边界：向下依赖 `agent/model`（模型出站）与 `agent/tool`（工具实现），只读平台级配置（`AgentProperties` / `RuntimeProperties`）与 `domain` 常量；向上只暴露 `AgentRuntime` 一个门面，不引用 `api`，不直接访问 `mapper`。

不回答：内核实现约定与工具契约（见 AGENTS.md §19）；内置 Python 运行时约定（见 AGENTS.md §20）；不变量表（见 AGENTS.md §18）；线程池清单（见 AGENTS.md §17）；依赖使用边界（见 AGENTS.md §15.1）；`wb_event` / `wb_run` 字段（见 docs/DATA-MODEL.md §5.2、§5.3）；运行状态机、落库路径与 SSE 帧形态（见 specs/02 与 docs/API-CONTRACT.md §8.1）；系统提示词装配（平台提示 + 会话补充 + 技能展开 + 长期记忆，见 specs/02）。

## 设计

### 组件

| 组件 | 职责 |
|---|---|
| `AgentRuntime` | 门面接口：发起一次运行，事件按产生顺序回调，返回可取消句柄 |
| `AgentKernelRuntime` | 实现：装配 `AgentLoop`、工具集与模型客户端；分配 `seq`；在后台线程跑一次运行并在启动期干跑注册校验工具 Schema |
| `AgentLoop` | 推理循环本体：思考 → 工具调用 → 观察 → 收敛；全部协作者构造期注入 |
| `RunHandle` | 运行句柄：`cancel()` 协作式取消（幂等、可跨线程）、`isCancelled()` 查询、`completion()` 拿终态 |
| `AgentMemory` | 上下文组装与确定性压缩（不调模型）：协议配对修复、Token 估算、整轮裁切 |
| `AgentEventAdapter` | 唯一归一入口：内核 `AgentEvent` → 工作台 `RunEventDTO`，单帧增量超 4096 字符截断 |
| `agent/tool/ToolRegistry` | 工具注册表：注册期编译参数 Schema，下发前按启停过滤，调用期按声明的并发属性批量执行 |
| `agent/tool/WorkbenchTools` | 内置工具集，共 9 个（见下表） |
| `agent/tool/PlanNormalizer` | 把 `task_plan` 的行式清单解析成 `{index, content, status}`，并把归一结果渲染回人读清单 |
| `agent/spi/*` | 端口：`ToolGate`（启停）、`MemoryPort`、`SubRunPort`、`KbSearchPort`、`ProviderStore`，实现全在 `service` 侧 |
| `runtime/LocalPythonRuntime` | Python 解释器探测与脚本执行，超时按进程树终止 |

Agent **无状态**：上下文不存在 Agent 里，每次运行由工作台从消息索引取最近 `history.limit` 条历史，经 `AgentMemory` 压缩后显式传入；`ModelClient` 按 `provider + model` 缓存复用；`AgentLoop` 每次运行新建，天然无跨会话状态。`seq` 由 `AgentKernelRuntime` 用 `AtomicLong` 从 `RunConst.EVENT_SEQ_START`(=1) 起分配，适配器只做类型映射，不判业务。

构建参数（前缀 `liteworkbench.agent`）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `loop.max-iters` | 20 | 单轮最大迭代，超出发 `EXCEED_MAX_ITERS` |
| `loop.repeat-guard` | 3 | 同一工具同一参数累计超过该值即拦截，逼模型换路 |
| `loop.tool-timeout-seconds` | 60 | 循环内单次工具执行超时 |
| `model.default-name` | `qwen-plus` | 模型名四级兜底的最后一档 |
| `model.temperature` | 0.3 | 采样温度 |
| `model.context-window` | 32000 | 上下文窗口，超过即压缩 |
| `model.reserve-tokens` | 4096 | 预留给输出的 token |
| `model.stream-timeout-seconds` | 180 | 流式读超时 |
| `model.sync-timeout-seconds` | 120 | 同步补全读超时（自研客户端路径） |
| `model.connect-timeout-seconds` | 30 | 建连超时 |
| `history.limit` | 40 | 历史消息条数上限 |
| `skill.enabled` | true | 是否在系统提示里装配技能，见 specs/03 |

### 事件模型

事件流是运行的唯一事实来源：一次 Run 由有序事件构成（`seq` 从 1 严格递增，内存计数器分配），落库后可回放，前端只需一个 reducer。内核事件是封闭集合（`agent/event/AgentEventType`），映射表：

| 内核事件 | 工作台类型 | blockType |
|---|---|---|
| `AGENT_START` | `run.start` | — |
| `THINKING_DELTA` | `thinking.delta` | `thinking` |
| `TEXT_DELTA` | `text.delta` | `text` |
| `TOOL_CALL_START` / `TOOL_CALL_END` | `tool.call`（`phase=start/end`） | `tool_call` |
| `TOOL_RESULT` | `tool.result` | `tool_result` |
| `MODEL_CALL_START` / `MODEL_CALL_END` | `model.call`（end 带用量与耗时） | — |
| `PLAN_UPDATED` | `plan.updated` | `plan` |
| `AGENT_RESULT` | `run.result` | `text` |
| `EXCEED_MAX_ITERS` | `run.exceed` | — |
| `AGENT_END` | `run.end` | — |
| `AGENT_ERROR` | `run.error` | — |

`AgentEventType` 为封闭枚举，归一的 `switch` 穷举全部类型，新增事件类型必须同步映射表，由编译器保证不漏；唯一边界是 `type` 为 null 时归为 `custom` 并在 payload 带上原值，不丢事件。`run.snapshot` 不是内核事件，由 `RunService` 在推送侧合成（见 specs/02）。`AGENT_RESULT` 与正文增量不重复计数：`run.result` 的 `text` 是权威全文，`text.delta` 只是过程帧。

### 工具模型

工具用 `@AgentTool` + `@AgentToolParam` 声明，`ToolRegistry.register(Object)` 扫描注册。注册期**即编译 JSON Schema**：参数缺注解、返回类型不是 `String`、工具名重复都在启动期抛错（`AgentKernelRuntime` 在 `@PostConstruct` 干跑一次注册做校验）；启用状态从 `wb_tool` 读取（`ToolGate` 端口）。工具方法统一返回 `String`：失败时返回以 `ERROR:` 开头的文本，`AgentLoop` 据此把事件标 `isError`，模型看到的是可读错误而不是异常中断。

| 工具 | Label | 类别 | 风险 | 默认启用 |
|---|---|---|---|---|
| `python_exec` | Python 执行 | runtime | `confirm` | 是 |
| `file_read` | 读取文件 | file | `safe` | 是 |
| `file_write` | 写入文件 | file | `confirm` | 是 |
| `http_get` | HTTP 请求 | network | `safe` | 是 |
| `kb_search` | 知识库检索 | knowledge | `safe` | 是 |
| `task_plan` | 任务规划 | agent | `safe` | 是 |
| `memory_search` | 记忆检索 | agent | `safe` | 是 |
| `memory_write` | 记忆写入 | agent | `confirm` | 是 |
| `task_delegate` | 子任务委派 | agent | `confirm` | 否 |

风险等级（`ToolRiskEnum`）：`safe` 直接执行；`confirm` 有副作用，Agent 内由平台级启停控制、页面手工调用（`/tool/invoke`）必须显式 `confirmed: true`；`danger` 与 `confirm` 同等对待，当前内置工具无此等级。

两个声明式属性决定内核行为：`parallelSafe`（同批是否可并发，默认 true；写类工具 `python_exec` / `file_write` / `memory_write` / `task_delegate` 为 false）、`emitsPlan`（输出是结构化计划，内核额外产出一条 `plan.updated`，当前只有 `task_plan`）。

参数绑定与执行全部在 `ToolRegistry` 收口：参数按声明名取用，缺失时基本类型给零值、包装类型给 null；未知工具、参数非法 JSON、超时、池满（`ToolPool` 拒绝）、工具抛异常一律转 `ERROR:` 文本，单个工具失败不会吞掉整批或整次运行。`invokeBatch` 的并发规则：批内**全部** `parallelSafe` 才并发提交，任一为 false 则整批串行；结果严格按下标返回，与模型声明的顺序一一对应。

`http_get` 是唯一接触外网的 `safe` 工具，因此在执行前强制目标校验：只放行 `http` / `https`，对解析出的**全部** IP 逐个判定，命中回环 / 任意本地 / 链路本地 / 私有网段 / 组播即拒答，域名解析失败同样拒答。返回文本固定 8000 字符截断。校验失败与网络失败都转 `ERROR:` 文本（错误码 `5006`），让模型换一个公开地址而不是反复重试同一个内网地址。

### 规划器

`task_plan` 收一段**行式清单**（每行一步，行首 `[x]` / `[~]` / `[ ]` 表示状态），由 `PlanNormalizer` 归一成 `{index, content, status}` 数组（`status` 取 `pending` / `doing` / `done`）：

- 选行式而非 JSON 入参：模型对行式清单的产出稳定性明显更高，且用户可直接读懂；解析容错（去自编号 `1.` / `1)` / `1、`、空白行、`[X]` 与 `[-]` 变体）集中在一处。
- 全量覆盖而非增量：每次调用给出完整计划，界面整体替换。增量合并需要模型维护稳定的步骤 ID，实际很难做对。
- 边界：步数上限 `PlanConst.MAX_STEPS`(20)，单步字符上限 `MAX_STEP_CHARS`(200)，超限截断；解析结果为空返回 `ERROR:` 文本。
- 计划不落独立表：以 `plan.updated` 事件入 `wb_event`（payload 里是归一后的 `steps` 数组），回放与实时共用同一渲染路径，不引入第二份状态。

### 长期记忆

`memory_write` / `memory_search` 经 `MemoryPort` 落到 `wb_memory`，规则全在 `MemoryService`（阈值取 `MemoryConst`）：

- 同 `user_id` + `memory_key` 只留一行有效记录（唯一索引兜底，并发写同 key 退化为覆盖更新），避免模型重复记忆堆成噪音。
- 写入前过容量闸门：超过 `MAX_PER_USER`(200) 时循环淘汰权重最低行；`MEMORY_LIMIT_REACHED` 只在淘汰循环仍无法降到上限以内时出现，正常写入不会失败。
- 维度白名单 `fact` / `preference` / `constraint` / `profile`，越界回落 `fact`；权重归一 0–9；key 上限 128 字符、value 上限 2000 字符，超长截断。
- 注入：`RunService` 装配系统提示时取权重最高的 `PROMPT_LIMIT`(5) 条拼成一段，追加到系统提示**末尾**并受 `PROMPT_MAX_CHARS`(2000) 约束——越靠近本轮输入越容易被遵守，也不挤占上下文。
- 检索命中即累加 `hit_count`，为后续按实际使用调整注入顺序留出依据；工具返回文本受 `TOOL_OUTPUT_MAX`(4000) 约束。

### 子运行

`task_delegate` 经 `SubRunPort` 交给 `SubRunService`：落一条 `relation='child'` 且带 `parent_run_id` 的 `wb_run`（供应方优先继承父会话，模型名继承父运行），用同一个内核同步执行、等产出，最后按 `seq` 排序一次性落库事件并结算。

- 子运行不推中间帧：父运行时间线上只呈现"委派"这一件事，细节进 Run 树与事件回放。
- 子任务描述必须自带全部上下文——子运行看不到当前对话，这是能力边界不是缺陷；子运行系统提示不带父会话的技能，避免越权。
- 工具默认停用：子运行真实消耗 token，须管理员显式开启。
- 等待上限取调用值与 `PlanConst.DELEGATE_TIMEOUT_SECONDS * 2`(360s) 的较小者；超时即取消并落 `failed`，返回结构化 `ERROR:` 文本；可能已有半成品事件，由父运行判断是否可用。父运行不属于当前用户、或找不到可用供应方时直接拒答。
- **并发配额**：同时在跑的子运行上限为 CPU 核数（下限 2），配额在派发子运行**之前**获取，拿不到即返回结构化 `ERROR:` 让模型自行完成该步骤。父运行线程会同步等子运行，不设配额会让运行编排池整体被拖住。

### Python 运行时

内置 Python 是工作台唯一预置的脚本运行时，只负责"找到一个可用的解释器 + 安全地执行一段脚本"，不解释脚本内容，不做包管理。探测顺序：

```
1 配置项指定的绝对路径 liteworkbench.runtime.python.path（非空才参与）
2 数据目录下的内置解释器 {liteworkbench.runtime.data-dir}/runtime/python/bin/{python3 | python.exe}
3 系统 PATH 中的 python3 / python
```

逐个候选调用 `--version` 取第一个能输出版本的解释器，探测结果（版本、来源、状态）缓存整个进程生命周期；首次探测用双检锁串行化，页面可经 `resetProbe()` 触发重新探测。状态：`READY` / `NOT_FOUND` / `BAD_VERSION`（低于 3.8）/ `DISABLED`（`runtime.python.enabled=false` 时直接判不可用，不探测）。

执行契约：

- 脚本一律落临时文件后把文件名交给解释器，禁止拼 `-c "..."`；工作目录必须落在数据目录内，越界即 `PYTHON_UNSAFE_PATH`；脚本文本含 `../` 或 `..\` 同样拒答。
- 标准输出与错误重定向到临时文件而不是管道：管道缓冲写满会阻塞子进程，`waitFor` 之后再读必然死锁。
- 超时归一为「调用值与 `runtime.python.timeout-seconds` 取较小者，再封顶 300 秒」，超时按进程树终止（先子孙后自身，留 3 秒收尾窗口）。
- **超时同样回报已落盘的输出**：`stdout` 原样返回，`stderr` 前面加上一行超时说明。只回"执行超时"会让模型无从判断脚本是否已经跑出一半结果。
- 子进程环境把解释器所在目录置 `PATH` 首位并强制 `PYTHONDONTWRITEBYTECODE=1` / `PYTHONIOENCODING=utf-8`。

## 契约

内核不对外暴露 HTTP 接口；SSE 帧形态（单通道 `run.event`、`id` 为 `seq`）见 docs/API-CONTRACT.md §8.1。

```java
public interface AgentRuntime {

    /** 发起一次运行：立即返回，事件在后台线程按序回调，取消经句柄传播到模型连接与工具执行 */
    RunHandle start(RunContext context, Consumer<RunEventDTO> sink);
}

public record RunContext(
    String userId, String sessionId, String runId, String providerCode, String modelName,
    String systemPrompt, List<ModelTypes.Msg> history, String prompt) {}

public record RunEventDTO(
    String runId, String sessionId, long seq,
    String eventType, String blockType,
    String textDelta, Map<String, Object> payload,
    long occurredAt) {}
```

`systemPrompt` 已含技能装配与长期记忆；`RunHandle.cancel()` 幂等，可在任意线程调用；`completion()` 正常收敛 `complete`、异常收敛 `completeExceptionally`，`RunService` 据此结算终态。

```java
public interface PythonRuntime {

    /** 探测解释器，结果缓存，重探调用 resetProbe */
    RuntimeStatus detect();

    /** 清探测缓存，下次 detect 重新探测 */
    void resetProbe();

    /** 执行脚本，返回标准输出与错误输出；超时以 timeout=true 表达 */
    ExecResult execute(ExecRequest request);
}

public record ExecRequest(String code, long timeoutSeconds, Path workDir) {}
public record ExecResult(int exitCode, String stdout, String stderr, boolean timeout) {}
```

进程内推送节拍常量（`RunConst`）：`SSE_PUSH_TICK_MS`=16ms、`EVENT_PERSIST_INTERVAL_MS`=200ms、`SSE_SNAPSHOT_THROTTLE_MS`=200ms、`SSE_HEARTBEAT_MS`=15000ms、`EVENT_SEQ_START`=1；`SSE_TIMEOUT_MS`=30 分钟。

## 关键流程

### 一次运行

```
start(context, sink)
  ├─ 1 ModelFactory.client(provider, model)：命中 LRU 缓存则复用，否则解密密钥并构建
  ├─ 2 装配工具集：registry.register(new WorkbenchTools(...))（全量注册，启停在下发时过滤）
  ├─ 3 AgentMemory.assemble(history, systemPrompt, prompt, contextWindow, reserveTokens)
  │      ├─ 协议修复 clean()：丢空助手消息与孤儿 tool 结果
  │      ├─ 估算 token：CJK 一字一 token，其余 3.5 字符一 token，每条消息 +4、每个工具调用 +8
  │      └─ 超预算 compact()：整轮丢弃最老历史，保留系统提示与末尾完整轮次，插入压缩提示
  ├─ 4 emit AGENT_START → 构建 AgentLoop → executor.submit 后台执行（MDC 注入 userId/sessionId/runId）
  └─ 5 每产出一个事件：分配 seq → sink.accept（终态帧由 service 层补发）

循环体（AgentLoop#run）
for (iter = 1; iter <= maxIters; iter++)
  ├─ 取消检查：命中即返回最后一段助手正文，收敛 aborted
  ├─ emit MODEL_CALL_START → tools.specs(toolGate::isEnabled) 过滤启停后下发
  │    → client.stream(...) 逐块回调
  │      ├─ 思考块 → THINKING_DELTA
  │      └─ 正文块 → TEXT_DELTA
  ├─ 累积完整回复（含 tool_calls），emit MODEL_CALL_END（带 usage 与耗时）
  ├─ 取消再检查：命中即返回本轮正文
  ├─ 无工具调用且正文非空 → emit AGENT_RESULT → 收敛 completed
  ├─ 无工具调用但正文为空 → 继续下一轮，由迭代上限兜底
  └─ 有工具调用 → executeToolBatch：
        ├─ 按模型声明顺序一次性发出全部 TOOL_CALL_START
        ├─ 重复调用护栏：同一（工具名+参数）整轮累计超过 repeat-guard 的调用不发往注册表，
        │    直接产出 ERROR: 文本并提示换参数
        ├─ 未拦下的调用交 registry.invokeBatch：全批 parallelSafe 才并发，否则串行；
        │    结果严格按下标回填（并发跑完再按序发出事件）
        ├─ 取消抵达而未回填的调用占位为 ERROR: 文本，保证 assistant 声明的 tool_calls 与结果一一配对
        └─ 按下标 emit TOOL_CALL_END → TOOL_RESULT（失败置 isError）；计划类工具再补 PLAN_UPDATED；
             结果按调用 id 作为 tool 角色消息回填进消息列表
```

终止条件：无工具调用且产出非空正文即结束；只有思考没有正文不结束；达到迭代上限先发 `EXCEED_MAX_ITERS`，再补发一条带本轮最后一段助手正文的 `AGENT_RESULT` 并收敛（不补造工具错误结果）——只靠增量帧累积会因单帧 4KB 截断丢掉长回答的尾部。取消的兜底正文取本轮已产出的最后一段正文：取消发生在模型出站之前时返回空串，绝不把历史里的助手正文当成本次答案。

### 事件推送与落库：双节拍

推送与落库是两件事，用两个独立节拍，互不拖累（节拍器定义在 `RunService`，此处只描述内核侧约定）：

```
内核线程：事件 → sink.accept → offer() 入待推送缓冲（只做一次 add，不做任何 IO）
                ↓
推送节拍 16ms（RunConst.SSE_PUSH_TICK_MS）
  ├─ 取走待推送缓冲，同一批内连续同类型的 text.delta / thinking.delta 合为一帧
  │    （textDelta 拼接、seq 取批内最后一条、payload.merged 记合并条数）
  ├─ 被取走的事件原样登记进待落库缓冲
  ├─ 类型切换与其余事件原样推送，保证时间线边界不丢
  └─ seq 有新推进且距上次 ≥200ms（SSE_SNAPSHOT_THROTTLE_MS）时补一条 run.snapshot
                ↓
落库节拍 200ms（RunConst.EVENT_PERSIST_INTERVAL_MS）
  └─ 取走待落库缓冲 → wb_event 批量 insert（失败只记 error，不中断输出）
```

- **为什么拆两个节拍**：推送要 16ms 才跟手，落库按这个频率就是每秒几十次写库；两者绑在一起只能二选一。
- **两个节拍器都是自注销的**：`active` 为空时任务自行停表，下一个运行到来时重新注册，空闲不占调度线程。
- **收尾冲刷**：终态前把两个缓冲各走一次，保证攒批内事件既可回放也已在客户端可见。
- `run.snapshot` 是**水位帧**：携带 `watermark`（seq 高水位）、`textLength`、`toolCalls`，客户端比对自身收到的最大 seq 即可判定丢帧，再从 `/run/events/{runId}?afterSeq=` 补齐。只在 seq 有新推进时下发，空闲等待模型时不产帧；收尾路径不补发快照，避免终态帧前多一帧无效水位。

累积快照不随每个 delta 下发，避免 O(n²) 的文本拷贝；SSE 连接每 15 秒（`RunConst.SSE_HEARTBEAT_MS`）发一行注释心跳 `: ping` 保活，心跳不带 `event` 与 `data`，不污染事件流，心跳发送失败即取消该运行。

### 工具执行与输出裁剪

```
模型产出 tool_calls（可能多个）
  → 按模型声明顺序发全部 TOOL_CALL_START
  → 重复护栏（工具名 + 参数原文）与已取消的调用直接产出 ERROR: 文本
  → 其余批量执行：全批 parallelSafe 才并发（工具池），否则逐个串行；结果按下标保序
  → 参数按声明名绑定：缺失时基本类型给零值、包装类型给 null，由工具实现兜底
  → 工具实现内再查一次 ToolGate（禁用即返回 ERROR: 文本）
  → 执行带超时，超时 / 异常 / 池满统一转 ERROR: 文本
  → 输出裁剪：控制字符清理 + ANSI 剥离 + 长度裁剪
  → 逐个发 TOOL_CALL_END、TOOL_RESULT，再作为 tool 角色消息按调用 id 回填，回到模型
```

输出裁剪在 `OutputSanitizer` 一处完成：剥 ANSI 与除 `\t`/`\n`/`\r` 外的控制字符，超 8000 字符（`MAX_CHARS`）**保尾**并在开头标注「已丢弃前 N 字符，仅保留结尾 8000 字符」。保尾不保头是因为 Python 的 traceback 末行才是异常原因、结论在最后一行。启停时机：`specs(toolGate::isEnabled)` 在每次模型调用前求值，页面开关即时生效；工具实现内部再校验一次，覆盖页面手工调用路径。

### Python 执行

```
execute(request)
  ├─ 1 探测解释器，未就绪抛 PYTHON_NOT_READY
  ├─ 2 工作目录 normalize 后必须落在数据目录内（越界抛 PYTHON_UNSAFE_PATH）；
  │    未传工作目录时落到 {data-dir}/python
  ├─ 3 超时归一：调用值与配置默认值 `runtime.python.timeout-seconds`(120) 取较小者，封顶 300 秒
  ├─ 4 脚本内容出现 `../` 或 `..\` 直接拒绝（PYTHON_UNSAFE_PATH）
  ├─ 5 脚本落盘到临时文件 lwb-python-{id}.py
  ├─ 6 ProcessBuilder 启动，工作目录设为 workDir，
  │    注入 PYTHONIOENCODING=utf-8 与 PYTHONDONTWRITEBYTECODE=1，
  │    并把解释器目录置于子进程 PATH 首位；stdout / stderr 重定向到临时文件
  ├─ 7 超时后按进程树终止（ProcessHandle.descendants() 全部 destroyForcibly）
  ├─ 8 读回双通道输出并清洗（超长保尾截断 8000 字符）
  └─ 9 finally 删除脚本与两个输出临时文件
```

**重定向到文件而不是管道**：管道缓冲写满会阻塞子进程，而 `waitFor` 之后才读流会造成死锁；重定向到临时文件后 `waitFor` 与读取互不干扰。执行工作目录由工具层传入按用户隔离的 `{data-dir}/workspace/{userId}`，与文件工具共享同一工作区。

### 工具侧路径约束

`file_read` / `file_write` 共用同一解析：拒绝空路径、绝对路径与任何含 `..` 的片段，`normalize()` 后必须仍落在用户工作区内，否则抛 `PYTHON_UNSAFE_PATH`；`file_write` 自动创建父目录并覆盖同名文件。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 内核全部自研、协作者构造注入 | 不联网不起 Spring 即可单测整条循环 | 装配代码由自己写 |
| 每次运行新建 `AgentLoop` | 天然无跨会话状态，工具启停即时生效 | 每轮少量构建开销 |
| `ModelClient` 按 provider+model 缓存 | 省去连接与策略的重复构建 | 供应方配置变更需主动失效缓存（见 specs/02） |
| 上下文由消息索引显式组装 | 只有一份事实来源，回放与重放一致 | 每轮多读一次历史消息 |
| 工具整批并发 + 结果按下标保序回填 | 只读工具可以并行提速 | 并发执行后需要缓冲重排 |
| 行为开关声明在注解上（`parallelSafe` / `emitsPlan`） | 新增工具不改内核 | 注解即契约，改语义要动工具声明 |
| 协作式取消 | 不产生半写状态，无 `Thread.stop` 风险 | 取消延迟取决于最近的行级检查点 |
| 终态事件由 service 层合成 | 内核只负责干活，运行状态只有一处写 | 内核事件集合与工作台事件集合不完全同构 |
| 内核事件封闭集合 | 归一表穷举即可，编译期可查漏 | 新增能力必须显式加事件类型 |
| 攒批写入 | 写放大降低一个量级 | 断电可能丢最后一批，靠终态兜底 |
| 推送与落库拆两个节拍 | 跟手程度与写放大各自最优，不必二选一 | 两条缓冲，收尾要各冲刷一次 |
| 水位快照而非全量快照 | 单帧恒定大小，长回答不产生 O(n) 带宽 | 丢帧要回源拉一次补齐 |
| 失败以文本返回而非抛异常 | 模型能自我纠错，不中断运行 | 需要靠 `ERROR:` 前缀识别失败 |
| 输出保尾不保头 | 异常原因不被截断 | 看不到开头输出 |
| 注册即编译 Schema | 工具定义错误在启动期暴露 | 反射元数据需在注册期一次算清 |
| 启停在下发时求值 + 实现内二次校验 | 开关即时生效，两条调用路径都不漏 | 每次模型调用多一轮开关查询 |
| 优先用系统 Python | 零镜像体积负担 | 依赖镜像 / 宿主机已装 Python |
| 脚本落盘再执行 | 支持多行与复杂语法 | 需要清理临时文件 |
| 按进程树终止 | 子进程不会被遗留 | 依赖容器内 POSIX 进程语义 |
| 计划覆盖式下发 | 归一与渲染各一处，界面整体替换 | 单步增删必须由模型重述整份计划 |
| 记忆同 key 覆盖 + 权重淘汰 | 写入永不失败，噪音不堆积 | 老记忆可能被低权重新记忆挤掉 |

## 约束

- 禁止在 `agent` 包引用 `api` 层任何类型；禁止在 `AgentLoop` 内直接 `new` 协作者；`agent` 包不得出现 `mapper`。
- 每个模型调用必须带超时：建连 30s / 流式读 180s / 同步读 120s（自研客户端）；经原生同步服务（`ModelFactory.aiConfig`）的调用读超时取 `stream-timeout-seconds`。建连或读超时抛 `MODEL_CALL_TIMEOUT`，其余出站失败抛 `MODEL_CALL_FAILED`；流式路径超时由 service 层把运行落为 `failed`。
- 工具输出进入上下文前必须经过控制字符清理与长度裁剪；每个工具必须带超时；工具方法必须返回 `String`；工具失败必须以 `ERROR:` 前缀文本表达，不得抛异常穿透。
- 写文件类工具必须把路径限定在当前用户工作区内（拒绝绝对路径与任何 `..` 片段）；禁止在工具内读取环境变量中的密钥。
- 新增工具必须同步写入 `wb_tool` 种子；工具实现内必须校验 `ToolGate`。
- Python 单次执行超时上限 300 秒；临时脚本与输出文件必须 `finally` 删除；不安装依赖包。
- 事件 `payload` 必须脱敏；`run.end` 与 `run.error` 必有且仅有其一；SSE 推送失败（客户端断开）即取消运行。
