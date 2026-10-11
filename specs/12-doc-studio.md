# 12 · 创作台

## 定位

创作台是工作台的产出端：Agent 负责跑，创作台负责把跑出来的东西落成可管理、可检索、可回溯的作品。

边界：本模块管文档正文、版本与全文索引，不做协同编辑，不做权限共享。

## 设计

| 项 | 规则 |
|---|---|
| 正文存储 | `wb_doc.content`，PG 为唯一权威 |
| 检索副本 | `idx:lw:doc`，存去标签纯文本，覆盖写 |
| 版本 | 每次保存写 `wb_doc_version` 快照，保留最近 30 条 |
| 文档类型 | `markdown`（源码编辑）/ `rich`（富文本 HTML）/ `code`（纯文本代码） |
| 状态 | `draft` `published` `archived`，只影响列表筛选，不影响编辑 |
| 正文上限 | 200000 字 |

三种类型存的都是文本：`rich` 存 WangEditor 产出的 HTML，纯文本副本由后端剥标签得到，索引只吃纯文本。

### AI 辅助写作

| action | 提示词意图 |
|---|---|
| `continue` | 按已有正文风格续写 |
| `polish` | 润色选区，保持原意 |
| `expand` | 扩写选区，补充论据与细节 |
| `summary` | 摘要选区 |
| `outline` | 按主题产出大纲 |
| `translate` | 翻译选区（`instruction` 写目标语言） |

调用链：动作模板 + 选区 + 补充要求 + 可选知识库片段 → `ModelFactory` 同步调用 → 返回纯文本。不建 Run，不落事件表。

## 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/doc/page` | 分页，参数 `keyword` `category` `state` |
| GET | `/api/v1/doc/detail/{docId}` | 详情含正文 |
| POST | `/api/v1/doc/create` | 新建 |
| POST | `/api/v1/doc/update` | 保存正文，写版本 + 同步索引 |
| POST | `/api/v1/doc/delete/{docId}` | 删文档、版本与索引 |
| GET | `/api/v1/doc/search` | 全文检索，走 `idx:lw:doc` |
| GET | `/api/v1/doc/version/page` | 版本列表，参数 `docId` |
| GET | `/api/v1/doc/version/detail/{versionId}` | 版本快照 |
| POST | `/api/v1/doc/version/restore/{versionId}` | 回滚 |
| POST | `/api/v1/doc/assist` | AI 辅助，返回 `{ text }` |

## 关键流程

```
保存正文
  ├─ 1 校验归属与长度（≤200000 字）
  ├─ 2 由正文算纯文本副本与字数
  ├─ 3 更新 wb_doc：content / content_text / word_count / version_no+1
  ├─ 4 写 wb_doc_version 快照（同事务）
  ├─ 5 覆盖写 idx:lw:doc（失败只 warn，PG 为准）
  └─ 6 裁剪超出 30 条的历史版本

AI 辅助
  ├─ 1 按 action 取动作模板
  ├─ 2 kbId 存在 → 检索 3 段分块拼入上下文
  ├─ 3 组装消息 → 模型同步调用（带超时）
  └─ 4 返回纯文本，前端决定插入或替换选区

回滚版本
  ├─ 1 取版本快照并校验归属
  ├─ 2 以快照内容走一次"保存正文"（版本号继续递增）
  └─ 3 返回新版本号
```

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 每次保存即写全量版本 | 回溯无需 diff，实现简单 | 长文档版本行占空间 |
| 索引只存纯文本 | 检索结果干净，索引体积小 | 富文本格式信息不可检索 |
| 索引写失败不回滚 | 编辑永远可用，不被检索设施拖垮 | 索引可能短暂落后，需可重建 |
| AI 辅助同步返回 | 前端一次拿到结果即可插入 | 长文辅助要等待，无流式反馈 |
| 不做协同编辑 | 无 CRDT、无锁冲突处理 | 多人同时编辑同一文档会互相覆盖 |

## 约束

- 正文超过 200000 字抛 `DOC_CONTENT_TOO_LONG`（8703）。
- 所有文档查询必须带 `user_id`，跨用户访问抛 `DOC_NOT_FOUND`（8701）。
- 版本快照只读，修改文档只能通过 `doc/update`。
