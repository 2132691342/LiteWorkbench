# 01 · Agent 内核

## 定位

把"模型怎么想、工具怎么用、循环什么时候停"实现到底，做成一个不碰数据库、不碰 HTTP 层的纯逻辑内核；事件的归一化在同一处完成，前端不感知模型协议差异。

边界：向下依赖 `agent/model`（模型出站）与 `agent/tool`（工具实现），只读平台级配置（`AgentProperties` / `RuntimeProperties`）与 `domain` 常量；向上只暴露 `AgentRuntime` 一个门面，不引用 `api`，不直接访问 `mapper`。

## 设计

### 组件

| 组件 | 职责 |
|---|---|
| `AgentRuntime` | 门面接口：发起一次运行，事件按产生顺序回调，返回可取消句柄 |
| `AgentKernelRuntime` | 实现：装配 `AgentLoop`、工具集、技能提示词与模型客户端 |
| `AgentLoop` | 推理循环本体：思考 → 工具调用 → 观察 → 收敛；全部协作者构造期注入 |
| `RunHandle` | 运行句柄：`cancel()` 协作式取消、`isCancelled()` 查询、`completion()` 拿终态 |
| `AgentMemory` | 上下文组装与确定性压缩（不调模型）：协议配对修复、Token 估算、切点选择 |
| `AgentEventAdapter` | 唯一归一入口：内核 `AgentEvent` → 工作台 `RunEventDTO` 并分配 `seq` |
| `agent/tool/ToolRegistry` | 工具注册表：注册期编译参数 Schema，下发前按启停过滤，调用前校验参数 |
| `agent/tool/WorkbenchTools` | 内置工具集：`python_exec` / `file_read` / `file_write` / `http_get` / `kb_search` |
| `agent/spi/ToolGate` | 工具启停端口，实现在 `service` 侧，禁用工具不下发给模型 |
| `runtime/LocalPythonRuntime` | Python 解释器探测与脚本执行，进程树超时终止 |

Agent **无状态**：上下文不存在 Agent 里，每次运行由工作台从消息索引取最近 `history.limit` 条历史，经 `AgentMemory` 压缩后显式传入。`ModelClient` 按 `provider + model` 缓存复用；`AgentLoop` 每次运行新建，天然无跨会话状态。

构建参数（前缀 `liteworkbench.agent`）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `loop.max-iters` | 20 | 单轮最大迭代，超出发 `EXCEED_MAX_ITERS` |
| `loop.repeat-guard` | 3 | 同一工具同一参数累计超过该值即拦截，逼模型换路 |
| `loop.tool-timeout-seconds` | 60 | 循环内单次工具执行超时 |
| `model.default-name` | `qwen-plus` | 三级模型名兜底的最后一档 |
| `model.temperature` | 0.3 | 采样温度 |
| `model.context-window` | 32000 | 上下文窗口，超过即压缩 |
| `model.reserve-tokens` | 4096 | 预留给输出的 token |
| `model.stream-timeout-seconds` | 180 | 流式读超时 |
| `model.sync-timeout-seconds` | 120 | 同步补全读超时 |
| `model.connect-timeout-seconds` | 30 | 建连超时 |
| `history.limit` | 40 | 历史消息条数上限 |

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
| `AGENT_RESULT` | `run.result` | `text` |
| `EXCEED_MAX_ITERS` | `run.exceed` | — |
| `AGENT_END` | `run.end` | — |
| `AGENT_ERROR` | `run.error` | — |

未识别的内核类型不丢事件，归一为 `custom` 并原样带出类型字符串。`run.end` / `run.error` 不由内核产出：终态帧由 `RunService` 按运行结果合成，保证运行状态只有一处写。

### 工具模型

工具用 `@AgentTool` + `@AgentToolParam` 声明，`ToolRegistry.register(Object)` 扫描注册。注册期**即编译 JSON Schema**（参数缺注解、返回类型不是 `String`、工具名重复都在启动期抛错）；启用状态从 `wb_tool` 读取。工具方法统一返回 `String`：失败时返回以 `ERROR:` 开头的文本，`AgentLoop` 据此把事件标 `isError`，模型看到的是可读错误而不是异常中断。

| 工具 | Label | 类别 | 风险 |
|---|---|---|---|
| `python_exec` | Python 执行 | runtime | `confirm` |
| `file_read` | 读取文件 | file | `safe` |
| `file_write` | 写入文件 | file | `confirm` |
| `http_get` | HTTP 请求 | network | `safe` |
| `kb_search` | 知识库检索 | knowledge | `safe` |

风险等级（`ToolRiskEnum`）：`safe` 直接执行；`confirm` 有副作用，Agent 内由平台级启停控制、页面手工调用（`/tool/invoke`）必须显式 `confirmed: true`；`danger` 与 `confirm` 同等对待，当前内置工具无此等级。

### Python 运行时

内置 Python 是工作台唯一预置的脚本运行时，只负责"找到一个可用的解释器 + 安全地执行一段脚本"，不解释脚本内容，不做包管理。探测顺序：

```
1 配置项指定的绝对路径 liteworkbench.runtime.python.path
2 数据目录下的内置解释器 {liteworkbench.runtime.data-dir}/runtime/python/bin/{python3 | python.exe}
3 系统 PATH 中的 python3 / python
```

探测结果缓存整个进程生命周期（只做一次 `--version` 进程调用），首次探测用双检锁串行化，页面可触发重新探测。状态：`READY` / `NOT_FOUND` / `BAD_VERSION`（低于 3.8）/ `DISABLED`（配置关闭时直接判不可用，不探测）。

## 契约

```java
public interface AgentRuntime {

    /** 发起一次运行：立即返回，事件在后台线程按序回调，取消经句柄传播到模型连接与工具执行 */
    RunHandle start(RunContext context, Consumer<RunEventDTO> sink);
}

public record RunEventDTO(
    String runId, String sessionId, long seq,
    String eventType, String blockType,
    String textDelta, Map<String, Object> payload,
    long occurredAt) {}
```

`RunContext` 携带 `userId`、`sessionId`、`runId`、`providerCode`、`modelName`、`systemPrompt`（已含技能装配）、`history` 与 `prompt`；`RunHandle.cancel()` 幂等，可在任意线程调用。

```java
public interface PythonRuntime {

    /** 探测结果，带版本与来源 */
    RuntimeStatus detect();

    /** 清探测缓存，下次 detect 重新探测 */
    void resetProbe();

    /** 执行脚本，返回标准输出与错误输出 */
    ExecResult execute(ExecRequest request);
}

public record ExecRequest(String code, long timeoutSeconds, Path workDir) {}
public record ExecResult(int exitCode, String stdout, String stderr, boolean timeout) {}
```

SSE 帧（单一通道，`id` 字段为 `seq`，客户端可携 `Last-Event-ID` 断点续拉）：

```
id: 12
event: run.event
data: {"runId":"RUN_...","seq":12,"eventType":"text.delta","textDelta":"你好"}
```

空闲期每 15 秒（`RunConst.SSE_HEARTBEAT_MS`）发一行注释心跳 `: ping` 保活；心跳不带 `event` 与 `data`，因此不污染事件流，心跳发送失败即取消该运行。

## 关键流程

### 一次运行

```
start(context, sink)
  ├─ 1 ModelFactory.client(provider, model)：命中 LRU 缓存则复用，否则解密密钥并构建
  ├─ 2 装配工具集：registry.register(new WorkbenchTools(...))（全量注册，启停在下发时过滤）
  ├─ 3 AgentMemory.assemble(history, prompt)：协议修复 → 估算 token → 超窗口则在 user 边界裁切
  ├─ 4 emit AGENT_START → new AgentLoop(协作者…) → 后台线程执行
  └─ 5 每产出一个事件：分配 seq → sink.accept（终态帧由 service 层补发）

循环体（AgentLoop#run）
for (iter = 1; iter <= maxIters; iter++)
  ├─ 取消检查：命中即按 aborted 收敛
  ├─ emit MODEL_CALL_START → tools.specs(toolGate::isEnabled) 过滤启停后下发 → client.stream(...) 逐块回调
  │     ├─ 思考块 → THINKING_DELTA
  │     └─ 正文块 → TEXT_DELTA
  ├─ 累积完整回复（含 tool_calls），emit MODEL_CALL_END（带 usage 与耗时）
  ├─ 有工具调用（逐个顺序执行）：
  │     ├─ emit TOOL_CALL_START
  │     ├─ 重复调用护栏：同工具同参数累计超过 repeat-guard 即返回提示文本逼模型换路
  │     ├─ registry.invoke(...) 带超时执行 → emit TOOL_CALL_END → TOOL_RESULT
  │     └─ 结果作为 tool 角色消息按调用 id 回填进消息列表
  └─ 无工具调用且正文非空 → emit AGENT_RESULT → 收敛 completed
```

终止条件：无工具调用且产出非空正文即结束；只有思考没有正文不结束；达到迭代上限发 `EXCEED_MAX_ITERS`，把最后一段助手文本作为结果返回并收敛（不补造工具错误结果）。

### 事件推送与落库

```
事件产生 → 加入批缓冲
  ├─ 落库：每 200ms（RunConst.EVENT_BATCH_INTERVAL_MS）定时 flush 整批 → wb_event 批量 insert
  │        （失败只记 error，不中断输出）
  └─ 推送：同一批内连续且同类型的 text.delta / thinking.delta 合为一帧（textDelta 拼接、
           seq 取批内最后一条）；类型切换与其余事件原样推送，保证时间线边界不丢。
           落库保持逐条，回放粒度不受合帧影响。
```

累积快照不随每个 delta 下发，避免 O(n²) 的文本拷贝。`run.end` 与 `run.error` 必有且仅有其一；单条增量超 4KB 截断，完整内容以 `run.result` 为准。

### 工具执行与输出裁剪

```
模型产出 tool_calls（可能多个，逐个顺序执行）
  → 参数按声明名绑定：缺失时基本类型给零值、包装类型给 null，由工具实现兜底
  → 工具实现内再查一次 ToolGate（禁用即返回 ERROR: 文本）
  → 执行带超时，超时/异常统一转 ERROR: 文本
  → 输出裁剪：控制字符清理 + 长度裁剪（保尾，超 8000 字符在开头标注「已丢弃前 N 字符」）
  → 作为 tool 角色消息按调用 id 回填，回到模型
```

Python 的 traceback 末行才是异常原因，故保尾不保头。启停时机：`specs(toolGate::isEnabled)` 在每次模型调用前求值，页面开关即时生效；工具实现内部再校验一次，覆盖页面手工调用路径。

### Python 执行

```
execute(request)
  ├─ 1 探测解释器，未就绪抛 PYTHON_NOT_READY
  ├─ 2 工作目录 normalize 后必须落在数据目录内（越界抛 PYTHON_UNSAFE_PATH）；
  │    脚本内容出现 ../ 或 ..\ 直接拒绝
  ├─ 3 超时归一：取调用值与配置默认值的较小者，封顶 300 秒
  ├─ 4 脚本落盘到临时文件 lwb-python-{ulid}.py
  ├─ 5 ProcessBuilder 启动，工作目录设为 workDir，注入 PYTHONIOENCODING=utf-8 与
  │    PYTHONDONTWRITEBYTECODE=1；stdout / stderr 重定向到临时文件
  ├─ 6 超时后按进程树终止（ProcessHandle.descendants() 全部 destroyForcibly）
  ├─ 7 读回双通道输出，清 ANSI 与控制字符，超长保尾截断 8000 字符
  └─ 8 finally 删除脚本与两个输出临时文件
```

**重定向到文件而不是管道**：管道缓冲写满会阻塞子进程，而 `waitFor` 之后才读流会造成死锁；重定向到临时文件后 `waitFor` 与读取互不干扰。执行工作目录由工具层传入按用户隔离的 `{data-dir}/workspace/{userId}`，与文件工具共享同一工作区，`workDir` 为空时才落到 `{data-dir}/python`。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 内核零框架依赖、全部协作者构造注入 | 不联网不起 Spring 即可单测整条循环 | 装配代码由自己写 |
| 每次运行新建 `AgentLoop` | 天然无跨会话状态，工具启停即时生效 | 每轮少量构建开销 |
| `ModelClient` 按 provider+model 缓存 | 省去连接与策略的重复构建 | 供应方配置变更需主动失效缓存 |
| 上下文由消息索引显式组装 | 只有一份事实来源，回放与重放一致 | 每轮多读一次历史消息 |
| 工具结果按调用下标严格回填 | 模型看到的顺序与它声明的顺序一致 | 并发执行后需要缓冲重排 |
| 协作式取消 | 不产生半写状态，无 `Thread.stop` 风险 | 取消延迟取决于最近的检查点 |
| 终态事件由 service 层合成 | 内核只负责干活，运行状态只有一处写 | 内核事件集合与工作台事件集合不完全同构 |
| 内核事件封闭集合 | 归一表穷举即可，编译期可查漏 | 新增能力必须显式加事件类型 |
| 攒批写入 | 写放大降低一个量级 | 断电可能丢最后一批，靠终态兜底 |
| 失败以文本返回而非抛异常 | 模型能自我纠错，不中断运行 | 需要靠 `ERROR:` 前缀识别失败 |
| 输出保尾不保头 | 异常原因不被截断 | 看不到开头输出 |
| 注册即编译 Schema | 工具定义错误在启动期暴露 | 反射元数据需在注册期一次算清 |
| 启停在下发时求值 + 实现内二次校验 | 开关即时生效，两条调用路径都不漏 | 每次模型调用多一轮开关查询 |
| 优先用系统 Python | 零镜像体积负担 | 依赖镜像/宿主机已装 Python |
| 脚本落盘再执行 | 支持多行与复杂语法 | 需要清理临时文件 |
| 按进程树终止 | 子进程不会被遗留 | 依赖容器内 POSIX 进程语义 |

## 约束

- 禁止在 `agent` 包引用 `api` 层任何类型；禁止在 `AgentLoop` 内直接 `new` 协作者。
- 每个模型调用必须带超时（流式 180s / 同步 120s / 建连 30s）；建连或读超时抛 `MODEL_CALL_TIMEOUT`，其余出站失败抛 `MODEL_CALL_FAILED`；流式路径超时由 service 层把运行落为 `failed`。
- 工具输出进入上下文前必须经过控制字符清理与长度裁剪；每个工具必须带超时，工具方法必须返回 `String`。
- 写文件类工具必须把路径限定在当前用户工作区内（拒绝绝对路径与任何 `..` 片段）；禁止在工具内读取环境变量中的密钥。
- 新增工具必须同步写入 `wb_tool` 初始化数据，并在注册期通过 Schema 校验。
- Python 单次执行超时上限 300 秒；临时脚本必须 `finally` 删除；不安装依赖包。
- 事件 `payload` 必须脱敏；`run.end` 与 `run.error` 必有且仅有其一；SSE 推送失败（客户端断开）即取消运行。
