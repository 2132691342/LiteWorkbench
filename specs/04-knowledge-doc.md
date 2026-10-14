# 04 · 知识库与创作台

## 定位

知识库把上传文档变成可检索的分块，让 Agent 与页面都能"带着资料回答"；创作台是产出端，把跑出来的东西落成可管理、可检索、可回溯的作品。

边界：知识库管文档解析、分块与检索，不做向量化；创作台管文档正文、版本与全文索引，不做协同编辑与权限共享。

不回答：接口路径与出入参（见 docs/API-CONTRACT.md §12、§15）；`wb_kb` / `wb_kb_doc` / `wb_doc` / `wb_doc_version` 字段与 `lw_kbchunk` / `lw_doc` 表结构（见 docs/DATA-MODEL.md §5.9–§5.12、§6.3、§6.4）；分块参数配置项（见 `application.yaml` 的 `liteworkbench.kb.chunk.*`，未外部化为环境变量）；`kb_search` 在内核里的注册与下发（见 specs/01）；知识库解析 MQ 拓扑的构建与对账（见 specs/05）。

## 设计

### 知识库

原件进 MinIO（键 `user/{userId}/kbdoc/{yyyyMMdd}/{docId}/{fileName}`）、元数据进 PG（`wb_kb_doc`）、分块进 Manticore（`lw_kbchunk`），解析经 MQ 异步执行（交换机 `lw.kb.exchange`、路由键 `lw.kb.parse`、队列 `lw.kb.parse.queue`，持久化且失败不重回队列），上传接口零等待。

| 项 | 规则 |
|---|---|
| 单文档上限 | 20MB（`KbConst.MAX_DOC_SIZE`），单个知识库最多 200 篇（`MAX_DOC_COUNT`） |
| 正文提取上限 | 500000 字符（`MAX_DOC_CHARS`），超出截断 |
| 支持格式 | 直读 `txt` / `md` / `csv` / `json` / `html` / `xml` / `log`；解析 `docx`（解包取 `word/document.xml` 按段落聚合）/ `pdf`（pdfbox） |
| 分块 | 目标 600 字符、相邻重叠 120 字符（`liteworkbench.kb.chunk.*`），按空行切段后贪心合并，超长段二次硬切，相邻块带上块尾部重叠 |
| 状态 | `pending` → `processing` → `ready` / `failed`（失败存原因，可重解析） |
| 检索 | Manticore 全文（`charset_table='non_cont,cjk'` + `icu_chinese`），`user_id` 必带、`kb_id` 可选，关键字同时匹配正文与文档名；有关键字按相关度返回分块，无关键字按文档内序号 |

检索有两条消费路径：页面检索（`KbDocService.search`，分页，单页上限 50）与 Agent 工具 `kb_search`（`KbDocService` 实现 `KbSearchPort`，返回带文档名与块序号的片段，条数上限 `KbConst.TOOL_SEARCH_LIMIT`(5)、拼接上限 `KbConst.TOOL_OUTPUT_MAX`(6000) 字符，命中为空时返回"未检索到相关内容"文本）。服务端不返回高亮标记，行内关键字高亮由前端按 `keyword` 渲染。

解析是幂等重放：`deleteByDoc` 清旧分块 → `addBatch` 批量写新分块 → 更新 `wb_kb_doc` 字数与分块数 → 置 `ready`；异常置 `failed` 并写截断到 500 字符的原因，不外抛（消息不重回队列，重放靠页面触发）。`reparse` 拒绝 `pending` 与 `processing` 状态的文档，`ready` / `failed` 均可重放。分块与索引可随时重放：原件在 MinIO，删除文档即清分块索引。

### 创作台

| 项 | 规则 |
|---|---|
| 正文存储 | `wb_doc.content`，PG 为唯一权威 |
| 检索副本 | Manticore `lw_doc`，存去标签纯文本，覆盖写（先删同 `doc_id` 再插入），失败只 warn |
| 版本 | 每次保存写 `wb_doc_version` 快照，保留最近 30 条（`DocConst.VERSION_KEEP`） |
| 文档类型 | `markdown`（源码编辑）/ `rich`（富文本 HTML）/ `code`（纯文本代码） |
| 状态 | `draft` `published` `archived`，只影响列表筛选 |
| 正文上限 | 200000 字（`DocConst.CONTENT_MAX`，超出抛 `DOC_CONTENT_TOO_LONG`） |

三种类型存的都是文本：`rich` 存编辑器产出的 HTML，纯文本副本由后端剥掉 `script` / `style` 块与标签、还原常见实体、折叠空白得到（`toPlainText`），索引只吃纯文本；字数按纯文本剔除空白后的字符数近似。新建文档正文为空也写一条 v1 快照，保证版本链完整。创作文档是唯一 PG 与 Manticore 双写的资产：PG 保证编辑事务（正文 + 版本同事务），Manticore 提供全站检索（按 `user_id` 必带、标题与正文任一命中、按更新时间倒序），索引落后时以 PG 为准。回滚以快照正文再走一次保存，版本号继续递增，不做历史改写。

### AI 辅助写作

| action | 提示词意图 |
|---|---|
| `continue` | 按已有正文风格续写 |
| `polish` | 润色选区，保持原意 |
| `expand` | 扩写选区，补充论据与细节 |
| `summary` | 摘要选区 |
| `outline` | 按主题产出大纲 |
| `translate` | 翻译选区（`instruction` 写目标语言） |

调用链：动作模板（`DocConst.ASSIST_TEMPLATES`）+ 选区 + 补充要求 + 可选知识库片段 → 模型流式调用 → SSE 边推边渲染。**不建 Run**：不写 `wb_run`、不落 `wb_event`，用量与耗时打 `[doc.assist]` 日志；客户端断开连接即取消模型调用。流式执行借用运行编排池（`lwRunExecutor`，见 AGENTS.md §17），请求线程只做校验与派发。

- 选区为空时取整篇正文前 12000 字（`ASSIST_INPUT_MAX`），保证动作始终有输入；无选区且无 `docId` 直接拒绝。
- `kbId` 存在时检索 3 段分块拼进【参考资料】段（`ASSIST_KB_TOP`），检索关键字优先取补充要求、否则取选区前 60 字。
- 提示词按固定分段拼装：【参考资料】→【正文】→【补充要求】（`translate` 动作用【目标语言】）。
- 增量按 120ms 合帧推 `assist.delta`；结束推 `assist.end`（含全文与耗时）；异常推 `assist.error`；首帧 `assist.start` 带 action。

## 契约

```java
public interface DocAssistSink {

    /** 推送一帧，eventType 为 assist.start / assist.delta / assist.end / assist.error */
    void send(String eventType, String payloadJson);

    /** 关闭通道 */
    void complete();

    /** 客户端是否已断开：断连后模型出站必须立即关闭连接，不能只等下一次推送失败 */
    boolean isClientGone();
}
```

SSE 单通道 `assist.event`，帧负载是 `{type, ...}` 结构（`type` 与业务字段同层）；HTTP 类型只出现在 `api` 层的 `SseDocAssistSink`。接口路径与出入参见 docs/API-CONTRACT.md §12、§15。

`KbDocService` 同时实现内核端口 `KbSearchPort.search(userId, keyword, kbId, limit)`，把分块拼成带来源标注的文本返回模型；`limit` 被夹到 `[1, 5]`。

`UploadFileDTO`（文件名 / 大小 / contentType / 输入流）是上传与解析共用的传输对象；`KbDocParseDTO` 是上传与 MQ 消费共用的解析任务负载（`docId` / `userId` / `kbId` / `bucketName` / `objectKey` / `docName`）。

## 关键流程

```
知识库上传
  ├─ 1 校验归属与文件（非空 / ≤20MB / 扩展名白名单）                    KbDocService
  ├─ 2 原件写 MinIO user/{userId}/kbdoc/...，失败抛 FILE_UPLOAD_FAILED
  ├─ 3 落库 wb_kb_doc(state=pending)；元数据写失败则删对象后同抛
  ├─ 4 发 MQ lw.kb.parse（削峰，解析不占请求线程）；投递失败就地置 failed，随后同步知识库计数
  └─ 5 消费端 → KbDocParseConsumer → KbDocService.parse()
        ├─ 置 processing；已是 ready 则直接跳过
        ├─ MinIO 拉原件，DocTextExtractor 提取纯文本（超 500000 字截断）
        ├─ KbTextChunker 按段落贪心合并分块（600 字 / 重叠 120 字）
        ├─ 先删该文档旧分块，再批量写 lw_kbchunk（幂等重放）
        ├─ 更新 wb_kb_doc 字数与分块数，置 ready
        └─ 失败置 failed + 原因（≤500 字），不外抛

创作文档保存
  ├─ 1 校验归属与长度（≤200000 字）、标题用户内唯一
  ├─ 2 由正文算纯文本副本与字数
  ├─ 3 更新 wb_doc：content / content_text / word_count / version_no+1
  ├─ 4 写 wb_doc_version 快照（同事务），裁剪超出 30 条的历史版本
  └─ 5 覆盖写 lw_doc（先删同键再插入，失败只 warn，PG 为准）

AI 辅助（SSE）
  ├─ 1 按 action 取动作模板，未识别即拒（PARAM_INVALID）
  ├─ 2 解析选区（选区 → 正文前 12000 字）
  ├─ 3 kbId 存在 → 检索 3 段分块拼入【参考资料】
  ├─ 4 组装提示词 → 派发到运行编排池，模型流式调用（读超时取 stream-timeout-seconds）
  ├─ 5 增量按 120ms 合帧推 assist.delta；推送失败即置取消标志，模型在下一检查点断开
  ├─ 6 结束推 assist.end（含全文与耗时）或 assist.error，最后关闭通道
  └─ 7 前端边收边增量渲染，结束后决定插入 / 替换选区

删除级联
  ├─ 删文档：清该文档分块索引 → 删 MinIO 原件（失败仅告警）→ 逻辑删 wb_kb_doc → 同步知识库计数
  └─ 删知识库：清该库全部分块 → 逐个删原件与文档行 → 逻辑删 wb_kb
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 知识检索用关键词全文（Manticore + ICU 分词） | 零额外依赖、零 embedding 成本，中文按词典切分 | 无语义召回，同义改写查不到 |
| 解析走 MQ 异步 | 上传零等待，失败可重放 | 状态需轮询，链路多一跳 |
| 分块带重叠 | 跨块语义不被切断 | 索引体积增加约 20% |
| 分块由段落贪心合并 | 块边界贴近作者分段，不切断句子 | 段落极短时块数偏多 |
| 每次保存即写全量版本 | 回溯无需 diff，实现简单 | 长文档版本行占空间 |
| 索引只存纯文本 | 检索结果干净，索引体积小 | 富文本格式信息不可检索 |
| 索引写失败不回滚 | 编辑永远可用，不被检索设施拖垮 | 索引可能短暂落后，需可重建 |
| 高亮交给前端 | 服务端不拼标记，响应更小 | 前端需自己按关键字匹配 |
| AI 辅助流式但不建 Run | 编辑器即时可读、断连即取消，零事件表开销 | 辅助用量不进运行统计，只留日志 |
| 辅助借用运行编排池 | 不为低频功能多开一个池 | 与对话运行争用同一池的并发额度 |
| 不做协同编辑 | 无 CRDT、无锁冲突处理 | 多人同时编辑同一文档会互相覆盖 |
| 回滚走一次普通保存 | 复用同一链路，版本链单调 | 回滚也会新增一条版本 |

## 约束

- 知识库与文档的一切查询必须带 `user_id`；跨用户访问抛 `KB_NOT_FOUND` / `KB_DOC_NOT_FOUND` / `DOC_NOT_FOUND`（版本为 `DOC_VERSION_NOT_FOUND`）。
- 分块索引写入必须"先删同键再插入"，删除结果按 `deleted` 计数对账。
- 正文超过 200000 字抛 `DOC_CONTENT_TOO_LONG`；版本快照只读，修改只能通过 `doc/update`。
- 文档标题用户内唯一，重名抛 `DOC_TITLE_CONFLICT`。
- AI 辅助不得写入 `wb_run` / `wb_event`；用量必须打日志（`[doc.assist]` 前缀）。
- 文档原件与分块索引必须级联清理：删知识库清全部文档与分块，删文档清分块；原件删除失败只告警（行与索引已清，孤儿对象可按 `user/{userId}/kbdoc` 前缀清扫）。
- 解析失败必须落库原因且可重解析；MQ 投递失败由上传与重解析链路就地置 `failed`（不做本地降级、不入队重试），因此不会留下既处理不了又拒绝重放的 `pending` 文档。
