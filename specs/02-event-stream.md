# 02 · 事件流

## 定位

事件流是运行的唯一事实来源。一次 Run 由有序事件构成，落库后可回放，前端只需一个 reducer。

边界：从 AgentScope 事件到工作台事件的归一化在本模块一处完成，前端不感知 SDK 版本差异。

## 设计

| 概念 | 说明 |
|---|---|
| Run | 一次 Agent 执行，有状态机与用量 |
| Event | Run 内的最小事实单元，带 `seq` 严格递增 |
| Seq | 运行内序号，从 1 开始，由内存计数器分配 |

归一映射表：

| AgentScope 事件 | 工作台类型 | blockType |
|---|---|---|
| `AgentStartEvent` | `run.start` | — |
| `ThinkingBlockDeltaEvent` | `thinking.delta` | `thinking` |
| `TextBlockDeltaEvent` | `text.delta` | `text` |
| `ToolCallStartEvent` | `tool.call`（phase=start） | `tool_call` |
| `ToolCallEndEvent` | `tool.call`（phase=end） | `tool_call` |
| `ToolResultStartEvent` | `tool.result` | `tool_result` |
| `ToolResultEndEvent` | `tool.result` | `tool_result` |
| `ModelCallStartEvent` | `model.call` | — |
| `ModelCallEndEvent` | `model.call` | — |
| `AgentResultEvent` | `run.result` | `text` |
| `ExceedMaxItersEvent` | `run.exceed` | — |
| `AgentEndEvent` | `run.end` | — |

未列出的事件类型归一为 `custom`，不丢弃，便于后续扩展。

## 契约

```java
public record RunEventDTO(
    String runId, String sessionId, long seq,
    String eventType, String blockType,
    String textDelta, Map<String, Object> payload,
    long occurredAt) {}
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

文本增量逐条直推会把连接线程打满，也会让客户端每条都触发一次重排。推送按 **16ms 窗口合帧**：窗口内的 `text.delta` 合并为一条 `textDelta` 后发出，`seq` 以合并前最后一条为准。落库攒批（100 条 / 200ms）与推送合帧互相独立。

累积快照不随每个 delta 下发，避免 O(n²) 的文本拷贝。

### 中断

```
POST /api/v1/run/abort/{runId} → Redis 写标记
  → 订阅侧检测 → 取消订阅 → 发 run.end(status=aborted)
```

`run.result`（completed）与中断标记在同一窗口内同时到达时，**中断裁决优先**，终态记 `aborted`，不写助手消息。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 攒批写入 | 写放大降低一个量级 | 断电可能丢最后一批，靠终态兜底 |
| 未识别事件归一为 custom | SDK 升级不会静默丢事件 | 前端需要容忍未知类型 |
| seq 内存分配 | 严格递增且无锁竞争 | 运行不跨重启续写，回放一律读 `wb_event` |

## 约束

- `run.end` 与 `error` 必有且仅有其一。
- 事件 `payload` 必须脱敏，不得含密钥。
- 单条 `textDelta` 超过 4KB 时截断，完整内容以 `run.result` 为准。
