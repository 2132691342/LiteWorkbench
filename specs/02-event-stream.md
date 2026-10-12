# 02 · 事件流

## 定位

事件流是运行的唯一事实来源。一次 Run 由有序事件构成，落库后可回放，前端只需一个 reducer。

边界：从内核事件到工作台事件的归一化在本模块一处完成，前端不感知模型协议与内核版本差异。

## 设计

| 概念 | 说明 |
|---|---|
| Run | 一次 Agent 执行，有状态机与用量 |
| Event | Run 内的最小事实单元，带 `seq` 严格递增 |
| Seq | 运行内序号，从 1 开始，由内存计数器分配 |

内核事件是封闭集合（`agent/event/AgentEventType`），归一映射表：

| 内核事件 | 工作台类型 | blockType |
|---|---|---|
| `AGENT_START` | `run.start` | — |
| `THINKING_DELTA` | `thinking.delta` | `thinking` |
| `TEXT_DELTA` | `text.delta` | `text` |
| `TOOL_CALL_START` | `tool.call`（phase=start） | `tool_call` |
| `TOOL_CALL_END` | `tool.call`（phase=end） | `tool_call` |
| `TOOL_RESULT` | `tool.result` | `tool_result` |
| `MODEL_CALL_START` | `model.call`（phase=start） | — |
| `MODEL_CALL_END` | `model.call`（phase=end，携带用量） | — |
| `AGENT_RESULT` | `run.result` | `text` |
| `EXCEED_MAX_ITERS` | `run.exceed` | — |
| `AGENT_END` | `run.end` | — |
| `AGENT_ERROR` | `run.error` | — |

协议差异（OpenAI 的 `data:` SSE、Anthropic 的命名事件、Ollama 的 NDJSON）在 `agent/model` 内被拉平，归一表只面对内核事件，因此换模型协议不动前端、不动落库结构。

## 契约

```java
public record RunEventDTO(
    String runId, String sessionId, Long seq,
    String eventType, String blockType,
    String textDelta, Map<String, Object> payload,
    Long occurredAt) {}
```

SSE 帧（单一通道，`id` 字段为 `seq`，客户端可携 `Last-Event-ID` 断点续拉）：

```
id: 12
event: run.event
data: {"runId":"RUN_...","seq":12,"eventType":"text.delta","textDelta":"你好"}
```

## 关键流程

### 落库攒批

```
事件产生 → 加入批缓冲
  ├─ 满 100 条 → flush
  └─ 距上次 flush 超 200ms → flush
flush → wb_event 批量 insert
```

### 回放

```
GET /api/v1/run/events/{runId}?afterSeq=0
  → 按 (run_id, seq) 升序读
  → 前端按序重放，重建消息与工具调用
```

### 推送

文本增量逐条直推会把连接线程打满，也会让客户端每条都触发一次重排。事件先按 **100 条 / 200ms** 攒批，推送时对批内**连续且同类型**的 `text.delta` / `thinking.delta` 合帧：`textDelta` 拼接为一条、`seq` 取批内最后一条；类型切换（text ↔ thinking）与其余事件原样推送，保证时间线区域边界不丢。落库保持逐条，回放粒度不受合帧影响。

累积快照不随每个 delta 下发，避免 O(n²) 的文本拷贝。

### 中断

```
POST /api/v1/run/abort/{runId}
  → 有活动实例：RunHandle.cancel()（置取消标志 + 关闭流）→ 内核在检查点收敛 → run.end(status=aborted)
  → 无活动实例（进程重启后的孤儿运行）：直接结算为 aborted
```

取消凭据是进程内的活动运行表，因此只对当前实例生效；单实例部署下等价于全局生效。已进入终态的运行抛 `RUN_NOT_ABORTABLE`。

`run.result`（completed）与中断同时到达时，**中断裁决优先**，终态记 `aborted`，不写助手消息。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 攒批写入 | 写放大降低一个量级 | 断电可能丢最后一批，靠终态兜底 |
| 内核事件封闭集合 | 归一表穷举即可，编译期可查漏 | 新增能力必须显式加事件类型 |
| seq 内存分配 | 严格递增且无锁竞争 | 运行不跨重启续写，回放一律读 `wb_event` |
| 协作式取消 | 无半写状态 | 取消延迟取决于检查点密度 |

## 约束

- `run.end` 与 `run.error` 必有且仅有其一。
- 事件 `payload` 必须脱敏，不得含密钥。
- 单条增量（`text.delta` / `thinking.delta`）超过 4KB 时截断，完整内容以 `run.result` 为准。
