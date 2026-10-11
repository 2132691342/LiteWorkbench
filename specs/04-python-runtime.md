# 04 · Python 运行时

## 定位

内置 Python 是工作台唯一预置的脚本运行时，让 Agent 能直接算数、处理数据、生成图表而不依赖外部环境。

边界：只负责"找到一个可用的解释器 + 安全地执行一段脚本"，不解释脚本内容，不做包管理。

## 设计

探测顺序：

```
1 配置项指定的绝对路径 liteworkbench.runtime.python.path
2 数据目录下的内置解释器 {liteworkbench.runtime.data-dir}/runtime/python/bin/python3（容器内可挂载）
3 系统 PATH 中的 python3 / python
```

探测结果缓存，页面可触发重新探测（`POST /api/v1/runtime/python/reprobe`，权限 `monitor:python:reprobe`）。执行工作目录限制在 `{liteworkbench.runtime.data-dir}/python` 内。

| 状态 | 含义 |
|---|---|
| `READY` | 找到可用解释器 |
| `NOT_FOUND` | 三种方式都没找到 |
| `BAD_VERSION` | 找到但版本低于 3.8 |

**为什么不做嵌入解压**：容器镜像里已经装好 Python，再嵌一份上百 MB 的运行时只会让镜像变大。内置路径仅作为兜底位置保留。

## 契约

```java
public interface PythonRuntime {
    /** 探测结果，带版本与来源 */
    RuntimeStatus detect();
    /** 执行脚本，返回标准输出与错误输出 */
    ExecResult execute(ExecRequest request);
}

public record ExecRequest(String code, long timeoutSeconds, Path workDir) {}
public record ExecResult(int exitCode, String stdout, String stderr, boolean timeout) {}
```

## 关键流程

```
execute(request)
  ├─ 1 探测解释器，未就绪抛 PYTHON_NOT_READY
  ├─ 2 安全校验：代码与工作目录不得越出数据目录（越界抛 PYTHON_UNSAFE_PATH）
  ├─ 3 脚本落盘到临时目录 lwb-python-{ulid}.py
  ├─ 4 ProcessBuilder 启动，工作目录设为 workDir
  ├─ 5 超时后按进程树终止（ProcessHandle.descendants() 全部 destroyForcibly）
  ├─ 6 双通道收集输出，控制字符清理
  ├─ 7 超长时保尾截断 8000 字符
  └─ 8 finally 删除临时脚本
```

## REST 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/runtime/python` | 探测结果（状态、版本、来源路径），权限 `monitor:health:list` |
| POST | `/api/v1/runtime/python/reprobe` | 清缓存重新探测，权限 `monitor:python:reprobe` |

## 错误码

| 码 | 场景 |
|---|---|
| 7001 | 未找到可用解释器 |
| 7002 | 内置运行时目录缺失 |
| 7003 | 脚本执行失败（非 0 退出或超时） |
| 7004 | 脚本含不安全路径引用 |

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 优先用系统 Python | 零镜像体积负担 | 依赖宿主机有 Python |
| 脚本落盘再执行 | 支持多行与复杂语法 | 需要清理临时文件 |
| 按进程组终止 | 子进程不会被遗留 | 依赖 POSIX 进程组语义，容器内执行 |
| 输出保尾截断 | 异常原因不被截掉 | 长输出的前半部分丢失 |

## 约束

- 单次执行超时上限 300 秒。
- 临时脚本必须 `finally` 删除，禁止残留。
- 工作目录限制在配置的数据目录内，禁止越界。
- 不安装依赖包，脚本只能用标准库与镜像预装的包。
