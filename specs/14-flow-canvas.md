# 14 · 工作流画布

## 定位

把一次创作拆成可编排的多步流程：取数 → 检索 → 生成 → 执行 → 汇总，画布负责定义顺序，执行器负责干活。

边界：本模块管画布结构、校验与串行执行，不做并行分支、不做定时调度、不做节点级重试。

## 设计

| 项 | 规则 |
|---|---|
| 结构存储 | `wb_flow.graph_json`，X6 的 `{nodes,edges}` 原样存 |
| 节点类型 | `start` `llm` `tool` `python` `knowledge` `end` |
| 校验 | 唯一 `start`、至少一 `end`、无环、无孤立节点 |
| 执行 | 拓扑序串行，变量沿边传递，节点输出写入上下文 |
| 结果 | 逐节点落 `wb_event`（`event_type=flow.node`） |

节点配置：

| 类型 | 关键配置 | 输出 |
|---|---|---|
| `start` | `inputs`：初始变量表 | 初始上下文 |
| `llm` | `prompt`（支持 `{{var}}`）、`providerCode`、`model` | 模型文本，写入 `outputVar` |
| `tool` | `toolName`、`params` | 工具输出，写入 `outputVar` |
| `python` | `script`（支持 `{{var}}`）、`timeoutSeconds` | stdout，写入 `outputVar` |
| `knowledge` | `kbId`、`keyword`、`topK` | 拼装片段，写入 `outputVar` |
| `end` | `template`：汇总模板 | 最终结果文本 |

超时：`llm` 走供应方配置超时，`python` 默认 120s 上限 600s，`knowledge` 与 `tool` 走各自执行器默认。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/flow/page` | 分页，参数 `keyword` |
| GET | `/api/v1/flow/detail/{flowId}` | 详情含 `graphJson` |
| POST | `/api/v1/flow/create` | 新建 |
| POST | `/api/v1/flow/update` | 保存并校验画布 |
| POST | `/api/v1/flow/delete/{flowId}` | 删除 |
| POST | `/api/v1/flow/run` | 执行，返回 `runId` 与节点结果 |
| GET | `/api/v1/flow/run/detail/{runId}` | 运行明细 |

## 关键流程

```
保存画布
  ├─ 1 解析 graph_json
  ├─ 2 校验：唯一 start / 至少一个 end / 无环 / 无孤立节点
  ├─ 3 不合法抛 FLOW_GRAPH_INVALID（8903）并带原因
  └─ 4 落库，node_count 同步

执行
  ├─ 1 建 Run（wb_run，relation=flow）
  ├─ 2 拓扑排序得到节点序列
  ├─ 3 逐节点执行
  │     ├─ 渲染节点配置里的 {{var}}
  │     ├─ 调执行器（带超时）
  │     ├─ 输出写入上下文 outputVar
  │     └─ 落一条 flow.node 事件（含耗时与输出摘要）
  ├─ 4 节点抛错即中止，运行置 failed，已产生输出保留在事件里
  └─ 5 成功则 end 节点产出最终文本，置 run success
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 串行执行 | 语义可预测、变量传递简单、出错点明确 | 无依赖分支也只能排队 |
| 整图存一个 jsonb | 保存与读取各一次，前端直接接管渲染 | 节点级统计要解析 JSON |
| 用 X6 而非 BPMN-JS | 结构即 JSON，与自有执行器直接对齐 | 不支持导入外部 BPMN 2.0 文件 |
| 节点失败即中止 | 不产生半截结果，排查简单 | 无跳过与重试策略 |
| 输出摘要落事件 | 运行可在既有事件时间线里回看 | 大输出被裁剪，完整结果只在返回值里 |

## 约束

- 画布节点数上限 50，超出抛 `FLOW_GRAPH_INVALID`（8903）。
- 校验不通过的画布一律拒绝保存，不允许存半成品图。
- 所有画布查询必须带 `user_id`，跨用户访问抛 `FLOW_NOT_FOUND`（8901）。
- `python` 节点脚本内容出现 `..` 抛 `PYTHON_UNSAFE_PATH`（7004）；`file_read` / `file_write` 的入参路径另做绝对路径与越界校验。
