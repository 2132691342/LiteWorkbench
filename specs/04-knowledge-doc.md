# 04 · 知识库与创作台

## 定位

知识库把上传文档变成可检索的分块，让 Agent 与页面都能"带着资料回答"；创作台是产出端，把跑出来的东西落成可管理、可检索、可回溯的作品。

边界：知识库管文档解析、分块与检索，不做向量化；创作台管文档正文、版本与全文索引，不做协同编辑与权限共享。

## 设计

### 知识库

原件进 MinIO、元数据进 PG、分块进 Manticore，解析经 MQ 异步执行，上传接口零等待。

| 项 | 规则 |
|---|---|
| 单文档上限 | 20MB，单个知识库最多 200 篇 |
| 正文提取上限 | 500000 字符，超出截断 |
| 支持格式 | 直读 `txt` / `md` / `csv` / `json` / `html` / `xml` / `log`；解析 `docx`（解包取 XML）/ `pdf`（pdfbox） |
| 分块 | 目标 600 字符、相邻重叠 120 字符（`liteworkbench.kb.chunk.*`），按段落贪心合并，超长段二次切分 |
| 状态 | `pending` → `ready` / `failed`（失败存原因，可重解析） |
| 检索 | Manticore 全文（ICU 中文分词），按相关度返回分块，行内关键字高亮 |

检索有两条消费路径：页面 `GET /kb/search` 与 Agent 工具 `kb_search`（经 `agent/spi/KbSearchPort` 端口调 `KbDocService`，返回带文档名与序号的片段，上限 5 条、拼接上限 6000 字符）。分块与索引可随时重放：原件在 MinIO，删除文档即清分块索引。

### 创作台

| 项 | 规则 |
|---|---|
| 正文存储 | `wb_doc.content`，PG 为唯一权威 |
| 检索副本 | Manticore `lw_doc`，存去标签纯文本，覆盖写 |
| 版本 | 每次保存写 `wb_doc_version` 快照，保留最近 30 条 |
| 文档类型 | `markdown`（源码编辑）/ `rich`（富文本 HTML）/ `code`（纯文本代码） |
| 状态 | `draft` `published` `archived`，只影响列表筛选 |
| 正文上限 | 200000 字 |

三种类型存的都是文本：`rich` 存 WangEditor 产出的 HTML，纯文本副本由后端剥标签得到，索引只吃纯文本。创作文档是唯一 PG 与 Manticore 双写的资产：PG 保证编辑事务，Manticore 提供全站检索，索引落后时以 PG 为准。

### AI 辅助写作

| action | 提示词意图 |
|---|---|
| `continue` | 按已有正文风格续写 |
| `polish` | 润色选区，保持原意 |
| `expand` | 扩写选区，补充论据与细节 |
| `summary` | 摘要选区 |
| `outline` | 按主题产出大纲 |
| `translate` | 翻译选区（`instruction` 写目标语言） |

调用链：动作模板 + 选区 + 补充要求 + 可选知识库片段 → 模型流式调用 → SSE 边推边渲染。**不建 Run**：不写 `wb_run`、不落 `wb_event`，用量与耗时打 `[doc.assist]` 日志；客户端断开连接即取消模型调用。流式执行用独立线程池（`lw-assist`），请求线程只做派发。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/kb/page` | 知识库分页，权限 `wb:kb:list` |
| GET | `/api/v1/kb/detail/{kbId}` | 详情 |
| POST | `/api/v1/kb/create` | 新建，权限 `wb:kb:create` |
| POST | `/api/v1/kb/update` | 修改，权限 `wb:kb:update` |
| POST | `/api/v1/kb/delete/{kbId}` | 级联清文档与分块，权限 `wb:kb:delete` |
| GET | `/api/v1/kb/doc/page` | 文档分页，参数 `kbId` |
| POST | `/api/v1/kb/doc/upload/{kbId}` | multipart 上传，解析经 MQ 异步执行，权限 `wb:kb:upload` |
| POST | `/api/v1/kb/doc/delete/{docId}` | 删除文档与分块 |
| POST | `/api/v1/kb/doc/reparse/{docId}` | 重解析失败文档 |
| GET | `/api/v1/kb/search` | 分块检索，参数 `keyword`、可选 `kbId`，权限 `wb:kb:search` |
| GET | `/api/v1/doc/page` | 文档分页，参数 `keyword` `category` `state` |
| GET | `/api/v1/doc/detail/{docId}` | 详情含正文 |
| POST | `/api/v1/doc/create` | 新建 |
| POST | `/api/v1/doc/update` | 保存正文，写版本 + 同步索引 |
| POST | `/api/v1/doc/delete/{docId}` | 删文档、版本与索引 |
| GET | `/api/v1/doc/search` | 全文检索，走 Manticore `lw_doc` |
| GET | `/api/v1/doc/version/page` | 版本列表，参数 `docId` |
| GET | `/api/v1/doc/version/detail/{versionId}` | 版本快照 |
| POST | `/api/v1/doc/version/restore/{versionId}` | 回滚 |
| POST | `/api/v1/doc/assist/stream` | AI 辅助，SSE 流式返回（`assist.delta` / `assist.end` / `assist.error`） |

## 关键流程

```
知识库上传
  ├─ 1 校验归属与文件（大小 / 扩展名 / 库内篇数）              KbDocService
  ├─ 2 原件写 MinIO user/{userId}/kbdoc/...，失败即回滚
  ├─ 3 落库 wb_kb_doc(state=pending)
  └─ 4 发 MQ lw.kb.parse（削峰，解析不占请求线程）
        → KbDocParseConsumer
            ├─ 5 MinIO 拉原件，DocTextExtractor 提取纯文本
            ├─ 6 KbTextChunker 按段落贪心合并分块（600 字 / 重叠 120 字）
            ├─ 7 分块批量写 Manticore lw_kbchunk（先删同键再插入，幂等）
            └─ 8 更新 wb_kb_doc(ready) 与 wb_kb 计数；失败置 failed + 原因

创作文档保存
  ├─ 1 校验归属与长度（≤200000 字）
  ├─ 2 由正文算纯文本副本与字数
  ├─ 3 更新 wb_doc：content / content_text / word_count / version_no+1
  ├─ 4 写 wb_doc_version 快照（同事务），裁剪超出 30 条的历史版本
  └─ 5 覆盖写 lw_doc（失败只 warn，PG 为准）

AI 辅助（SSE）
  ├─ 1 按 action 取动作模板
  ├─ 2 kbId 存在 → 检索 3 段分块拼入上下文
  ├─ 3 组装消息 → 模型流式调用（独立 lw-assist 线程池，带超时）
  ├─ 4 增量按 120ms 合帧推 assist.delta；结束推 assist.end（含全文与耗时）
  └─ 5 断连即取消；前端边收边增量渲染，结束后决定插入 / 替换选区
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 知识检索用关键词全文（Manticore + ICU 分词） | 零额外依赖、零 embedding 成本，中文按词典切分 | 无语义召回，同义改写查不到 |
| 解析走 MQ 异步 | 上传零等待，失败可重放 | 状态需轮询，链路多一跳 |
| 分块带重叠 | 跨块语义不被切断 | 索引体积增加约 20% |
| 每次保存即写全量版本 | 回溯无需 diff，实现简单 | 长文档版本行占空间 |
| 索引只存纯文本 | 检索结果干净，索引体积小 | 富文本格式信息不可检索 |
| 索引写失败不回滚 | 编辑永远可用，不被检索设施拖垮 | 索引可能短暂落后，需可重建 |
| AI 辅助流式但不建 Run | 编辑器即时可读、断连即取消，零事件表开销 | 辅助用量不进运行统计，只留日志 |
| 不做协同编辑 | 无 CRDT、无锁冲突处理 | 多人同时编辑同一文档会互相覆盖 |

## 约束

- 知识库与文档的一切查询必须带 `user_id`；跨用户访问抛 `KB_NOT_FOUND` / `DOC_NOT_FOUND`。
- 分块索引写入必须"先删同键再插入"，删除结果按 `deleted` 计数对账。
- 正文超过 200000 字抛 `DOC_CONTENT_TOO_LONG`；版本快照只读，修改只能通过 `doc/update`。
- AI 辅助不得写入 `wb_run` / `wb_event`；用量必须打日志（`[doc.assist]` 前缀）。
- 文档原件与分块索引必须级联清理：删知识库清全部文档与分块，删文档清分块。
