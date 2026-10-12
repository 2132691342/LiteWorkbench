# 03 · 工具系统

## 定位

工具是 Agent 接触外部世界的唯一出口。本模块定义工具有哪些、谁能调、超时多久、输出怎么裁剪。

边界：工具定义存 `wb_tool`（平台级，仅管理角色可写），实现在 `agent/tool/WorkbenchTools.java`，注册、参数绑定与输出裁剪在 `agent/tool/ToolRegistry.java`。

## 设计

工具用 `@AgentTool` + `@AgentToolParam` 声明，`ToolRegistry.register(Object)` 扫描注册。注册期**即编译 JSON Schema**（参数缺注解、返回类型不是 `String`、工具名重复都在启动期抛错），启用状态从 `wb_tool` 读取。

| 工具 | Label | 类别 | 风险 |
|---|---|---|---|
| `python_exec` | Python 执行 | runtime | confirm |
| `file_read` | 读取文件 | file | safe |
| `file_write` | 写入文件 | file | confirm |
| `http_get` | HTTP 请求 | network | safe |
| `kb_search` | 知识库检索 | knowledge | safe |

风险等级（`ToolRiskEnum`）：

| 等级 | 语义 | 处理 |
|---|---|---|
| `safe` | 只读，无副作用 | 直接执行 |
| `confirm` | 有副作用 | Agent 内由平台级启停控制；页面手工调用（`/tool/invoke`）必须显式 `confirmed: true` |
| `danger` | 可能造成不可逆破坏 | 与 `confirm` 同等对待；当前内置工具无此等级 |

## 契约

```java
public class WorkbenchTools {

    /** 执行 Python 脚本 */
    @AgentTool(name = "python_exec", risk = ToolRiskEnum.CONFIRM,
            description = "执行一段 Python 代码并返回标准输出；报错时返回完整 traceback，可据此修正后重试")
    public String pythonExec(
            @AgentToolParam(name = "code", description = "要执行的 Python 源码") String code,
            @AgentToolParam(name = "timeoutSeconds", description = "超时秒数，默认 60，上限 300",
                    required = false, type = "integer") Integer timeoutSeconds) { ... }
}
```

工具方法统一返回 `String`：失败时返回以 `ERROR:` 开头的文本，`AgentLoop` 据此把事件标 `isError`，模型看到的是可读错误而不是异常中断。

`ToolRegistry` 对外能力：

| 方法 | 说明 |
|---|---|
| `register(bean)` | 扫描注解方法，编译并缓存参数 Schema；注解错用与重名在此抛异常 |
| `names()` | 已注册工具名 |
| `riskOf(name)` | 风险等级，未注册返回 null |
| `specs(enabled)` | 产出下发给模型的 `tools` 数组，`enabled` 谓词用于过滤平台级启停 |
| `invoke(name, argsJson, timeoutSeconds)` | 提交到固定线程池执行，超时/异常/未知工具统一转 `ERROR:` 文本 |
| `shutdown()` | 运行结束回收线程池 |

## 关键流程

```
模型产出 tool_calls（可能多个，逐个顺序执行）
  → AgentLoop：重复调用护栏（同工具同参数计数）→ registry.invoke(...)
  → 参数按声明名绑定：缺失时基本类型给零值、包装类型给 null，由工具实现自行兜底
  → 工具实现内再查一次 ToolGate（禁用即返回 ERROR: 文本）
  → 执行带超时，超时/异常都转 ERROR: 文本
  → 输出裁剪：控制字符清理 + 长度裁剪（保尾）
  → 作为 tool 角色消息按调用 id 回填，回到模型
```

**输出裁剪**：Python 的 traceback 末行才是异常原因，故超过 8000 字符时保留**结尾**并在开头标注「已丢弃前 N 字符」。`file_read` / `http_get` 额外做控制字符清理。

**重复调用护栏**：同一工具同一参数在整个运行内累计调用次数**超过** `repeat-guard`（默认 3）即不再执行，直接返回提示文本逼模型换路——即第 4 次同参调用被拦截。

**启停时机**：`specs(toolGate::isEnabled)` 在每次模型调用前求值，页面开关即时生效；工具实现内部再校验一次，覆盖页面手工调用路径。

## REST 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tool/list` | 工具列表，权限 `wb:tool:list` |
| POST | `/api/v1/tool/toggle/{toolId}` | 启停，权限 `wb:tool:toggle`，加 `@Idempotent` |
| POST | `/api/v1/tool/invoke/{toolName}` | 页面手工执行，权限 `wb:tool:invoke`，加 `@Idempotent` |

`/tool/invoke/{toolName}` 请求体 `{ "args": {...}, "confirmed": false, "timeoutSeconds": 120 }`。执行前依次校验：工具存在（否则 `TOOL_NOT_FOUND`）→ 已启用（否则 `TOOL_DISABLED`）→ 非 `safe` 必须带 `"confirmed": true`（否则 `TOOL_CONFIRM_REQUIRED`）。页面调用与 Agent 内执行共用同一注册表、同一输出裁剪。

**超时取值**：页面手工调用默认 120 秒（可用 `timeoutSeconds` 覆盖）；Agent 内单次工具默认 60 秒（`loop.tool-timeout-seconds`）；`python_exec` 自身默认 60 秒且受运行时 300 秒硬上限约束（见 spec 04）；`http_get` 固定 60 秒。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 失败以文本返回而非抛异常 | 模型能自我纠错，不中断运行 | 需要靠 `ERROR:` 前缀识别失败 |
| 输出保尾不保头 | 异常原因不被截断 | 看不到开头输出 |
| 注册即编译 Schema | 工具定义错误在启动期暴露 | 反射元数据需在注册期一次算清 |
| 启停在下发时求值 + 实现内二次校验 | 开关即时生效，两条调用路径都不漏 | 每次模型调用多一轮开关查询 |
| 审批闸门而非审批流 | 流程短，只有一个确认位 | 危险工具靠 `risk_level` 标注 + 平台级启停兜底 |

## 约束

- 每个工具必须带超时；工具方法必须返回 `String`。
- 禁止在工具内读取环境变量中的密钥。
- 写文件类工具必须把路径限定在当前用户工作区内（拒绝绝对路径与任何 `..` 片段）。
- 新增工具必须同步写入 `wb_tool` 初始化数据，并在注册期通过 Schema 校验。
