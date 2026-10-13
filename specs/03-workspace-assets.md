# 03 · 工作台资产：技能、文件、提示词

## 定位

三类用户可维护的资产，共同点是"用户自己的、可增删改的复用单元"：技能沉淀固定流程，文件承载 Agent 工作区与上传原件，提示词固化反复使用的指令。

边界：技能只管存储、检索与提示词注入，不执行技能内部流程；文件只管元数据与对象操作，不解析内容；提示词只管模板存储与变量渲染。

## 设计

### 技能：两段式渐进披露

| 部分 | 字段 | 注入时机 |
|---|---|---|
| 摘要 | `summary` | 常驻系统提示词 |
| 正文 | `content` | 命中 `trigger_words` 后展开 |

把所有技能正文常驻提示词会随技能数量线性膨胀，而绝大多数技能在一次对话里用不上，故分成两段。匹配规则：用户输入包含任一触发词（忽略大小写的包含判断）即视为命中，把该技能 `content` 追加到本轮上下文，单次对话最多展开 3 个。

| 字段 | 约束 |
|---|---|
| `skill_code` | 用户内唯一，小写字母数字加下划线 |
| `summary` | 不超过 200 字 |
| `content` | 不超过 8000 字符，超出抛 `PARAM_INVALID` |
| `trigger_words` | 逗号分隔，最多 20 个 |
| `enabled` | 关闭则不参与匹配 |

### 文件：元数据与对象分离

| 项 | 规则 |
|---|---|
| 桶 | `liteworkbench`，启动时幂等创建 |
| 对象键 | `user/{userId}/{category}/{yyyyMMdd}/{fileId}/{fileName}` |
| 单文件上限 | 100MB |
| 下载地址 | 预签名 URL，有效期 15 分钟 |

对象键中的 `category` 段当前固定为 `upload`（用户主动上传），分段结构保留，便于后续接入 Agent 制品与归档。对象键带用户前缀与 `fileId`，同名文件不互相覆盖，可按用户整体迁移。

### 提示词：模板与变量

| 项 | 规则 |
|---|---|
| 归属 | 用户自建模板带 `user_id`；内置模板 `user_id='builtin'` 且 `builtin=true` |
| 变量语法 | 正文写 `{{name}}`，`variables` 存 `[{name,label,defaultValue}]` |
| 内置模板 | 启动即写入，只读；可复制为自有模板后修改 |
| 分类 | 写作 / 改写 / 摘要 / 翻译 / 大纲 / 代码 |

内置与用户模板同表同查询：列表按 `builtin desc, use_count desc` 排序。渲染规则：变量有值替换为传入值；无值但有默认值替换为默认值；无值且无默认值抛 `PROMPT_VARIABLE_MISSING`；正文引用了未声明变量则保持原样。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/skill/page` | 分页 |
| POST | `/api/v1/skill/create` | 新建 |
| POST | `/api/v1/skill/update` | 全量更新 |
| POST | `/api/v1/skill/delete/{skillId}` | 删除 |
| GET | `/api/v1/file/page` | 分页，支持 `sessionId` `category` |
| POST | `/api/v1/file/upload` | multipart 上传 |
| GET | `/api/v1/file/url/{fileId}` | 取预签名下载地址 |
| POST | `/api/v1/file/delete/{fileId}` | 删对象与元数据，权限 `wb:file:delete` |
| GET | `/api/v1/prompt/page` | 分页，含内置 |
| GET | `/api/v1/prompt/detail/{promptId}` | 详情 |
| POST | `/api/v1/prompt/create` | 新建 |
| POST | `/api/v1/prompt/update` | 更新（内置拒绝） |
| POST | `/api/v1/prompt/delete/{promptId}` | 删除（内置拒绝） |
| POST | `/api/v1/prompt/copy/{promptId}` | 复制为自有 |
| POST | `/api/v1/prompt/render` | 变量渲染，返回文本 |

## 关键流程

```
技能命中
  ├─ 1 加载全部 enabled 技能
  ├─ 2 摘要列表注入系统提示词
  ├─ 3 输入命中触发词 → 展开对应正文（最多 3 个）
  └─ 4 未命中 → 只有摘要，正文不占用上下文

文件上传
  ├─ 1 校验大小（≤100MB）与文件名合法性
  ├─ 2 生成 fileId 与对象键 → putObject 到 MinIO
  ├─ 3 落 wb_file 元数据
  └─ 4 对象写入失败抛 FILE_UPLOAD_FAILED；元数据写入失败则删对象后同抛

文件删除
  ├─ 1 删 MinIO 对象，失败抛 FILE_DELETE_FAILED
  └─ 2 逻辑删除 wb_file 元数据

提示词渲染
  ├─ 1 取模板并校验可见（自有或内置）
  ├─ 2 逐个变量取值：传入值 → 默认值 → 抛 PROMPT_VARIABLE_MISSING
  ├─ 3 正文做 {{name}} 字面替换
  ├─ 4 自有模板 use_count + 1
  └─ 5 返回渲染后文本
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 技能两段式披露 | 上下文占用与技能数量解耦 | 触发词没写好会漏召 |
| 触发词简单包含匹配 | 零依赖、可预测 | 无法做语义召回 |
| 技能存库而非文件 | 页面可直接增删 | 版本管理交给 `updated_at` |
| 不做技能内子流程编排 | 实现简单，行为可预测 | 复杂流程需拆成多个技能 |
| 预签名直连 MinIO | 大文件不穿过应用进程 | 多一次取 URL 往返 |
| 元数据与对象分离 | 列表查询快，不打存储 | 可能不一致，失败留孤儿对象由清理任务兜 |
| 文件不做向量化 | 实现简单，依赖少 | 检索只能按名与分类 |
| 内置与自有模板同表 | 一次查询出全部，排序可控 | 内置行要处处判 `builtin` 守卫 |
| 变量字面替换 | 零依赖、用户可读可写 | 不做转义，正文含双花括号会误替换 |
| 渲染在服务端 | 前后端结果一致，可复用于 Agent | 每次渲染一次往返 |

## 约束

- 技能正文不得超过 8000 字符；技能内容不得包含密钥等敏感信息。
- 单次上传不超过 100MB（超出抛 `FILE_TOO_LARGE`）；对象键禁止包含 `..` 与控制字符。
- 删除对象失败抛 `FILE_DELETE_FAILED`，元数据删除失败记 error 并抛同码。
- 内置提示词模板修改与删除抛 `PROMPT_BUILTIN_READONLY`；用户内模板标题唯一，重名抛 `PROMPT_TITLE_CONFLICT`；正文上限 8000 字。
- 三类资产的查询一律带 `user_id`（内置提示词除外）。
