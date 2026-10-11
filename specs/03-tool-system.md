# 03 · 工具系统

## 定位

工具是 Agent 接触外部世界的唯一出口。本模块定义工具有哪些、谁能调、超时多久、输出怎么裁剪。

边界：工具定义存 `wb_tool`，实现在 `agent/WorkbenchTools.java`，执行受统一超时与沙箱约束。

## 设计

工具用 AgentScope 的 `@Tool` + `@ToolParam` 声明，由 `Toolkit.registerTool` 注册。启用状态从 `wb_tool` 读取，禁用则不注册。

| 工具 | Label | 类别 | 风险 | 审批 |
|---|---|---|---|---|
| `python_exec` | 执行 Python | runtime | confirm | 否 |
| `file_read` | 读取文件 | file | safe | 否 |
| `file_write` | 写入文件 | file | confirm | 否 |
| `http_get` | 发起请求 | network | safe | 否 |

风险等级：

| 等级 | 语义 | 处理 |
|---|---|---|
| `safe` | 只读，无副作用 | 直接执行 |
| `confirm` | 有副作用 | 直接执行，但在时间线上标黄 |
| `danger` | 可能造成不可逆破坏 | 需页面确认，未实现前一律禁用 |

## 契约

```java
public class WorkbenchTools {
    @Tool(name = "python_exec", description = "执行一段 Python 代码并返回输出")
    public String pythonExec(
        @ToolParam(name = "code", description = "Python 源码") String code,
        @ToolParam(name = "timeoutSeconds", description = "超时秒数", required = false) Integer timeoutSeconds);
}
```

返回值为字符串，失败时返回以 `ERROR:` 开头的文本，让模型能自行换方案。

## 关键流程

```
模型产出 ToolUseBlock
  → Toolkit 路由到实现
  → 前置检查：工具是否启用、参数是否合法
  → 执行（带超时）
  → 输出裁剪：控制字符清理 + 结尾保留
  → 返回 ToolResultBlock
```

**输出裁剪**：Python 的 traceback 末行才是异常原因，故超时或超长时保留**结尾**而非开头，上限 8000 字符。

## REST 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tool/list` | 工具列表，权限 `wb:tool:list` |
| POST | `/api/v1/tool/toggle/{toolId}` | 启停，权限 `wb:tool:toggle`；工具启用状态在每次执行前读取，即时生效 |
| POST | `/api/v1/tool/invoke/{toolName}` | 页面手工执行，权限 `wb:tool:invoke`，加 `@Idempotent` |

`/tool/invoke/{toolName}` 请求体 `{ "args": {...}, "confirmed": false }`。`safe` 直接执行；`confirm` 必须带 `"confirmed": true`；`danger` 在页面确认流程实现前一律返回 `FORBIDDEN`。参数校验失败抛 `TOOL_PARAM_INVALID`（5004），与 Agent 内执行共用同一套前置检查。

**超时取值优先级**：调用参数 `timeoutSeconds` > 工具默认 60 秒；`python_exec` 另受运行时 300 秒硬上限约束（见 spec 04）。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 失败以文本返回而非抛异常 | 模型能自我纠错，不中断运行 | 需要靠前缀识别失败 |
| 输出保尾不保头 | 异常原因不被截断 | 看不到开头输出 |
| 启用状态从库读取 | 页面可动态开关 | 变更后需 evict Agent 缓存 |
| 不做工具审批流 | 流程短 | 危险工具靠 `risk_level` 标注 + 平台级启停兜底（`wb_tool` 为平台级定义） |

## 约束

- 每个工具必须带超时，默认 60 秒。
- 禁止在工具内读取环境变量中的密钥。
- 新增工具必须同步写入 `wb_tool` 初始化数据。
