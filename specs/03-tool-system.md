# 03 · 工具系统

## 定位

工具是 Agent 接触外部世界的唯一出口。本模块定义工具有哪些、谁能调、超时多久、输出怎么裁剪。

边界：工具定义存 `wb_tool`（平台级），实现在 `agent/tool/WorkbenchTools.java`，注册与参数校验在 `agent/tool/ToolRegistry.java`。

## 设计

工具用自研注解 `@AgentTool` + `@AgentToolParam` 声明，`ToolRegistry.register(Object)` 扫描注册。注册时**即编译 JSON Schema**，Schema 错误在启动期暴露而不是运行期；启用状态从 `wb_tool` 读取，禁用则不注册、模型看不到该工具。

| 工具 | Label | 类别 | 风险 |
|---|---|---|---|
| `python_exec` | Python 执行 | runtime | confirm |
| `file_read` | 读取文件 | file | safe |
| `file_write` | 写入文件 | file | confirm |
| `http_get` | HTTP 请求 | network | safe |
| `kb_search` | 知识库检索 | knowledge | safe |

风险等级：

| 等级 | 语义 | 处理 |
|---|---|---|
| `safe` | 只读，无副作用 | 直接执行 |
| `confirm` | 有副作用 | Agent 内由平台级启停控制；页面手工调用（`/tool/invoke`）必须显式 `confirmed: true` |
| `danger` | 可能造成不可逆破坏 | 与 `confirm` 同等对待；当前内置工具无此等级 |

## 契约

```java
public class WorkbenchTools {

    /** 执行一段 Python 代码并返回输出 */
    @AgentTool(name = "python_exec", description = "执行一段 Python 代码并返回输出", risk = RiskEnum.CONFIRM)
    public String pythonExec(
        @AgentToolParam(name = "code", description = "Python 源码") String code,
        @AgentToolParam(name = "timeoutSeconds", description = "超时秒数", required = false) Integer timeoutSeconds) { ... }
}
```

工具方法统一返回 `String`：失败时返回以 `ERROR:` 开头的文本，让模型能自行换方案，不中断整个运行。

`ToolRegistry` 对外三个能力：

| 方法 | 说明 |
|---|---|
| `register(Object)` | 扫描注解方法，编译并缓存参数 Schema |
| `schemas(enabledNames)` | 产出下发给模型的 `tools` 数组 |
| `invoke(name, args, timeout)` | 参数校验 → 反射调用 → 输出裁剪，异常统一转 `ERROR:` 文本 |

## 关键流程

```
模型产出 tool_calls（可能多个）
  → ToolRegistry 校验：工具存在、参数按 Schema 绑定
  → 启用状态在工具实现内读取（禁用即返回 ERROR: 文本）
  → 执行（带超时），异常与超时都转 IsError 结果
  → 输出裁剪：控制字符清理 + 长度裁剪（保留结尾）
  → 按调用下标严格回填，作为 tool 角色消息回到模型
```

**输出裁剪**：Python 的 traceback 末行才是异常原因，故超时或超长时保留**结尾**而非开头，上限 8000 字符，并在末尾追加"已丢弃 N 行"的说明。

**重复调用护栏**：同一工具同一参数在整个运行内累计调用 3 次即判失败，逼模型换路；被审批拒绝也计数。

## REST 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tool/list` | 工具列表，权限 `wb:tool:list` |
| POST | `/api/v1/tool/toggle/{toolId}` | 启停，权限 `wb:tool:toggle`；启用状态在每次执行前读取，即时生效 |
| POST | `/api/v1/tool/invoke/{toolName}` | 页面手工执行，权限 `wb:tool:invoke`，加 `@Idempotent` |

`/tool/invoke/{toolName}` 请求体 `{ "args": {...}, "confirmed": false }`。`safe` 直接执行；`confirm` / `danger` 必须带 `"confirmed": true`，否则抛 `TOOL_CONFIRM_REQUIRED`（5005）。参数按名取用，缺失时以 `null` 传入并由工具实现自行兜底；页面调用与 Agent 内执行共用同一实现、同一超时与同一输出裁剪。

**超时取值优先级**：调用参数 `timeoutSeconds` > 工具默认 60 秒；`python_exec` 另受运行时 300 秒硬上限约束（见 spec 04），`http_get` 固定 60 秒。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 失败以文本返回而非抛异常 | 模型能自我纠错，不中断运行 | 需要靠 `ERROR:` 前缀识别失败 |
| 输出保尾不保头 | 异常原因不被截断 | 看不到开头输出 |
| 注册即编译 Schema | 工具定义错误在启动期暴露 | 反射缓存需在注册期一次算清 |
| 启用状态从库读取 | 页面可动态开关、即时生效 | 每次执行多一次查询（有本地缓存兜底） |
| 审批闸门而非审批流 | 流程短，一次回调面 | 危险工具靠 `risk_level` 标注 + 平台级启停兜底 |

## 约束

- 每个工具必须带超时，默认 60 秒。
- 禁止在工具内读取环境变量中的密钥。
- 新增工具必须同步写入 `wb_tool` 初始化数据，并在 `ToolRegistry` 的启动自检中通过 Schema 校验。
