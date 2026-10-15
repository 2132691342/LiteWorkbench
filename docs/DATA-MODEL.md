# DATA-MODEL

数据模型唯一事实源：PostgreSQL 全部表的字段与索引、Manticore 四张检索表的 DDL 与建表约束、Redis 键约定、ID 与审计字段的数据侧事实。PG DDL 实现在 `backend/src/main/resources/db/schema.sql`，Manticore DDL 由应用启动幂等执行（`config/ManticoreIndexConfig`）。

---

## 1. 定位

本文回答数据放在哪、长什么样：每张表有哪些列、哪些索引，Manticore 表怎么建，Redis 键怎么拼，ID 与审计字段在本库的落地形态。

不回答：业务流程与运行链路（见 `specs/`），编码规范、命名约定、密码与权限模型（见 `AGENTS.md`），接口契约（见 `API-CONTRACT.md`）。

## 2. 存储职责划分

| 类别 | 中间件 | 内容 |
|---|---|---|
| 业务与权限主数据 | PostgreSQL | `sys_*` 全表，`wb_*` 全表（会话消息除外） |
| 全文检索文本 | Manticore | 会话消息 `lw_msg`、知识库分块 `lw_kbchunk`、创作文档检索副本 `lw_doc`、平台日志 `lw_log` |
| 文件与文档原件 | MinIO | 上传文件、知识库文档原件、运行产物 |
| 缓存、登录态、锁、幂等、限流 | Redis | 见 §7 |
| 削峰管道 | RabbitMQ | 日志索引、知识库解析任务 |

不落 PostgreSQL 的数据：会话消息（`lw_msg`）、知识库分块（`lw_kbchunk`）、平台日志（`lw_log`）、创作文档检索副本（`lw_doc`）、知识库与文件的原件（MinIO）。

不做多租户。归属以用户为单位：用户拥有的业务表带 `user_id`，平台级主数据（菜单定义、角色授权关系、内置工具与供应方定义）不带。

## 3. ID 与审计字段（数据侧）

**ID**：前缀表、ULID 规则与生成入口见 `AGENTS.md §6`。数据侧补充两点：

- 种子数据（菜单、内置提示词、内置工具）不随机生成，用 `前缀_` + 26 位零填充序号，保证升级对账时 ID 稳定，如 `MENU_00000000000000000000000011`、`TOOL_00000000000000000000000001`。
- Manticore 文档的数字主键由引擎自动分配，业务主键走 string 列：`MESG_`（`lw_msg.message_id`）、`KBCHUNK_`（`lw_kbchunk.chunk_id`）、`LOGD_`（`lw_log.log_id`）。

**审计字段**：语义与取值口径见 `AGENTS.md §7`。本库落地宽度：`created_by` / `updated_by` varchar(40)，`state` varchar(24)（`wb_run` / `wb_kb_doc` / `wb_memory` 为 varchar(16)），`remark` varchar(512)。各表「审计」行只列实际存在的列，通用 `state` 取值见 `StateEnum`（`normal` / `disabled` / `archived`）。

## 4. 用户与权限表

### 4.1 sys_user

| 字段 | 类型 | 说明 |
|---|---|---|
| `user_id` | varchar(40) PK | `USER_` |
| `username` | varchar(64) NOT NULL | 登录名 |
| `password_hash` | varchar(256) NOT NULL | 口令摘要，格式 `pbkdf2$迭代次数$盐$Base64`；校验兼容旧 `SHA-256(密码 + 盐)` 记录 |
| `password_salt` | varchar(32) NOT NULL | 随机盐 |
| `nickname` | varchar(64) | 显示名 |
| `email` | varchar(128) | 邮箱 |
| `avatar_url` | varchar(512) | 头像 |
| `last_login_at` | timestamptz | 最近登录 |
| `pwd_reset_required` | boolean NOT NULL DEFAULT false | 是否必须先改密（种子账号为 true），改密成功后置 false |
| `state` | varchar(24) NOT NULL DEFAULT 'normal' | `UserStateEnum`：`normal` `disabled` `locked` |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`uk_sys_user_username` 唯一 `(username) WHERE deleted = false`。

### 4.2 sys_role

| 字段 | 类型 | 说明 |
|---|---|---|
| `role_id` | varchar(40) PK | `ROLE_` |
| `role_code` | varchar(64) NOT NULL | 角色码 |
| `role_name` | varchar(64) NOT NULL | 角色名 |
| `sort_no` | int NOT NULL DEFAULT 0 | 排序 |
| `state` | varchar(24) NOT NULL DEFAULT 'normal' | `normal` `disabled` `archived` |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`uk_sys_role_code` 唯一 `(role_code) WHERE deleted = false`。

内置角色码（`RoleCodeConst`）：`super_admin`、`admin`、`user`。角色含义、特权划分与授权守门见 `AGENTS.md §3` 与 `specs/06`。

### 4.3 sys_menu（平台级）

| 字段 | 类型 | 说明 |
|---|---|---|
| `menu_id` | varchar(40) PK | `MENU_`，种子为 26 位零填充序号 |
| `parent_id` | varchar(40) NOT NULL DEFAULT '0' | 父菜单，根为 `0` |
| `menu_name` | varchar(64) NOT NULL | 菜单名，存 i18n key 而非中文 |
| `menu_type` | varchar(16) NOT NULL | `dir` 目录 / `menu` 路由页 / `button` 权限点 |
| `route_path` | varchar(128) | 前端路由路径 |
| `component` | varchar(128) | 前端组件路径 |
| `icon` | varchar(64) | 图标名 |
| `perm_code` | varchar(128) | 权限标识 `system:user:create` |
| `sort_no` | int NOT NULL DEFAULT 0 | 排序 |
| `visible` | boolean NOT NULL DEFAULT true | 是否显示 |
| `state` | varchar(24) NOT NULL DEFAULT 'normal' | `normal` `disabled` `archived` |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`uk_sys_menu_perm` 唯一 `(perm_code) WHERE deleted = false AND perm_code IS NOT NULL`；`idx_sys_menu_parent` `(parent_id)`。

`menu_name` 的 i18n key 规则与种子对账逻辑见 `AGENTS.md §2.5`；数据侧事实是菜单种子按固定 `menu_id` upsert，并软删路由或权限点命中种子、但 ID 不在种子内的残留行。

### 4.4 sys_user_role / sys_role_menu

| 表 | 主键 | 其余列 |
|---|---|---|
| `sys_user_role` | `(user_id, role_id)` | `created_by` `created_at` |
| `sys_role_menu` | `(role_id, menu_id)` | `created_by` `created_at` |

## 5. 业务表

用户拥有的业务表带 `user_id varchar(40)`，归属条件显式写入 SQL。

### 5.1 wb_session

| 字段 | 类型 | 说明 |
|---|---|---|
| `session_id` | varchar(40) PK | `SESS_` |
| `user_id` | varchar(40) NOT NULL | 归属用户 |
| `title` | varchar(128) NOT NULL DEFAULT '新会话' | 会话标题 |
| `provider_code` | varchar(24) | 供应方码，与 `wb_provider.provider_code` 对应 |
| `model_name` | varchar(64) | 模型名 |
| `system_prompt` | text | 系统提示词 |
| `message_count` | int NOT NULL DEFAULT 0 | 消息数 |
| `run_count` | int NOT NULL DEFAULT 0 | 运行数 |
| `total_tokens` | bigint NOT NULL DEFAULT 0 | 累计用量 |
| `last_active_at` | timestamptz | 最近活跃 |
| `state` | varchar(24) NOT NULL DEFAULT 'normal' | `normal` `disabled` `archived` |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`idx_wb_session_owner` `(user_id) WHERE deleted = false`。

### 5.2 wb_run

| 字段 | 类型 | 说明 |
|---|---|---|
| `run_id` | varchar(40) PK | `RUN_` |
| `user_id` | varchar(40) NOT NULL | 归属用户 |
| `session_id` | varchar(40) NOT NULL | 归属会话 |
| `parent_run_id` | varchar(40) | 父运行；子运行（`task_delegate`）填父运行 ID，根运行为空 |
| `relation` | varchar(16) NOT NULL DEFAULT 'original' | `original`（会话对话）/ `child`（子运行） |
| `prompt` | text | 本次输入 |
| `model_name` | varchar(64) | 实际使用模型 |
| `state` | varchar(16) NOT NULL DEFAULT 'queued' | `RunStatusEnum`：`queued` `running` `success` `failed` `aborted` |
| `stop_reason` | varchar(24) | `StopReasonEnum`：`completed` `max_iters` `aborted` `error` |
| `iter_count` | int NOT NULL DEFAULT 0 | 迭代轮次 |
| `input_tokens` | int NOT NULL DEFAULT 0 | 输入用量 |
| `output_tokens` | int NOT NULL DEFAULT 0 | 输出用量 |
| `started_at` / `ended_at` | timestamptz | 起止 |
| `duration_ms` | bigint | 耗时 |
| `created_at` | timestamptz NOT NULL DEFAULT now() | 创建时间 |

索引：`idx_wb_run_session` `(session_id, created_at DESC)`；`idx_wb_run_owner` `(user_id, created_at DESC)`。

所有运行都挂会话，`session_id` 非空；子运行与父运行同会话，Run 树按 `parent_run_id` 在场内自组装，不额外建表。子运行同样入账 token 与耗时，用量归因父子一并计入。

### 5.3 wb_event

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | varchar(40) PK | `EVNT_` |
| `user_id` | varchar(40) NOT NULL | 归属用户 |
| `run_id` | varchar(40) NOT NULL | 归属运行 |
| `session_id` | varchar(40) NOT NULL | 归属会话，冗余免 join |
| `seq` | int NOT NULL | 运行内序号，从 1 递增 |
| `event_type` | varchar(32) NOT NULL | 归一后的事件类型，见 `EventTypeEnum` |
| `block_type` | varchar(16) | `BlockTypeEnum`：`text` `thinking` `tool_call` `tool_result` |
| `text_delta` | text | 增量文本 |
| `payload` | jsonb | 完整事件体 |
| `occurred_at` | timestamptz NOT NULL DEFAULT now() | 发生时间 |

索引：`uk_wb_event_seq` 唯一 `(run_id, seq)`；`idx_wb_event_session` `(session_id, occurred_at)`。

事件归一、合帧与回放见 `specs/02` 与 `specs/05`。

### 5.4 wb_tool（平台级）

| 字段 | 类型 | 说明 |
|---|---|---|
| `tool_id` | varchar(40) PK | `TOOL_`，种子为 26 位零填充序号 |
| `tool_name` | varchar(64) NOT NULL | 调用名 |
| `tool_label` | varchar(64) NOT NULL | 展示名 |
| `category` | varchar(24) NOT NULL | `runtime` `file` `network` `knowledge` `agent` |
| `risk_level` | varchar(16) NOT NULL DEFAULT 'safe' | `ToolRiskEnum`：`safe` `confirm` `danger` |
| `enabled` | boolean NOT NULL DEFAULT true | 是否启用 |
| `schema_json` | jsonb | 参数 Schema |
| `created_at` / `updated_at` | timestamptz | 创建 / 更新时间 |

索引：`uk_wb_tool_name` 唯一 `(tool_name)`。

内置工具种子（固定 ID，`ON CONFLICT (tool_id) DO UPDATE` 幂等回写标签、分类与风险等级）：`python_exec`（runtime/confirm）、`file_read`（file/safe）、`file_write`（file/confirm）、`http_get`（network/safe）、`kb_search`（knowledge/safe）、`task_plan`（agent/safe）、`memory_search`（agent/safe）、`memory_write`（agent/confirm）、`task_delegate`（agent/confirm，默认关闭）。

### 5.5 wb_skill

| 字段 | 类型 | 说明 |
|---|---|---|
| `skill_id` | varchar(40) PK | `SKIL_` |
| `user_id` | varchar(40) NOT NULL | 归属用户 |
| `skill_code` | varchar(64) NOT NULL | 技能标识，用户内唯一 |
| `skill_name` | varchar(128) NOT NULL | 名称 |
| `summary` | varchar(512) | 摘要，常驻提示词 |
| `content` | text | 正文，命中后展开 |
| `trigger_words` | varchar(512) | 触发词，逗号分隔 |
| `enabled` | boolean NOT NULL DEFAULT true | 是否启用 |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`uk_wb_skill_code` 唯一 `(user_id, skill_code) WHERE deleted = false`。

### 5.6 wb_file

| 字段 | 类型 | 说明 |
|---|---|---|
| `file_id` | varchar(40) PK | `FILE_` |
| `user_id` | varchar(40) NOT NULL | 归属用户 |
| `session_id` | varchar(40) | 归属会话，可空 |
| `file_name` | varchar(255) NOT NULL | 文件名 |
| `object_key` | varchar(512) NOT NULL | MinIO 对象键，含用户前缀 |
| `bucket_name` | varchar(64) NOT NULL | 桶名 |
| `file_size` | bigint NOT NULL DEFAULT 0 | 字节数 |
| `content_type` | varchar(128) | MIME |
| `category` | varchar(24) NOT NULL DEFAULT 'upload' | `upload` `artifact` `log` |
| 审计 | | `created_by` `created_at` `deleted` |

索引：`idx_wb_file_owner` `(user_id, created_at DESC) WHERE deleted = false`。

### 5.7 wb_provider（平台级）

| 字段 | 类型 | 说明 |
|---|---|---|
| `provider_id` | varchar(40) PK | `PROV_` |
| `provider_code` | varchar(24) NOT NULL | 供应方码；预设 `openai` / `deepseek` / `doubao` / `grok` |
| `provider_name` | varchar(64) NOT NULL | 展示名 |
| `base_url` | varchar(512) | 端点 |
| `api_key_enc` | varchar(512) | 加密存储的密钥 |
| `default_model` | varchar(64) | 默认模型 |
| `enabled` | boolean NOT NULL DEFAULT true | 是否启用 |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`uk_wb_provider_code` 唯一 `(provider_code) WHERE deleted = false`。

预设端点与默认模型（`ProviderPresetEnum`，前端新建时下拉选择自动填充；协议通道固定为 OpenAI 兼容）：

| 预设 | base_url | default_model |
|---|---|---|
| `openai` | `https://api.openai.com/v1` | `gpt-4o` |
| `deepseek` | `https://api.deepseek.com` | `deepseek-chat` |
| `doubao` | `https://ark.cn-beijing.volces.com/api/v3` | `doubao-1.5-lite-32k-250115` |
| `grok` | `https://api.x.ai/v1` | `grok-2-1212` |

### 5.8 wb_kb

| 字段 | 类型 | 说明 |
|---|---|---|
| `kb_id` | varchar(40) PK | `KB_` |
| `user_id` | varchar(40) NOT NULL | 归属用户 |
| `kb_name` | varchar(128) NOT NULL | 知识库名，用户内唯一 |
| `description` | varchar(512) | 描述 |
| `doc_count` | int NOT NULL DEFAULT 0 | 文档计数，冗余免实时聚合 |
| `chunk_count` | int NOT NULL DEFAULT 0 | 分块计数 |
| `state` | varchar(24) NOT NULL DEFAULT 'normal' | `normal` `disabled` `archived` |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`uk_wb_kb_name` 唯一 `(user_id, kb_name) WHERE deleted = false`。

### 5.9 wb_kb_doc

| 字段 | 类型 | 说明 |
|---|---|---|
| `doc_id` | varchar(40) PK | `KBDOC_` |
| `user_id` | varchar(40) NOT NULL | 归属用户 |
| `kb_id` | varchar(40) NOT NULL | 归属知识库 |
| `doc_name` | varchar(255) NOT NULL | 文档名 |
| `object_key` | varchar(512) NOT NULL | 原件对象键 `user/{userId}/kbdoc/...` |
| `bucket_name` | varchar(64) NOT NULL | 桶名 |
| `file_size` | bigint NOT NULL DEFAULT 0 | 字节数 |
| `content_type` | varchar(128) | MIME |
| `char_count` | int NOT NULL DEFAULT 0 | 提取出的正文总字符数 |
| `chunk_count` | int NOT NULL DEFAULT 0 | 分块数 |
| `state` | varchar(16) NOT NULL DEFAULT 'pending' | `KbDocStateEnum`：`pending` `processing` `ready` `failed` |
| `error_message` | text | 解析失败原因 |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`idx_wb_kb_doc_kb` `(kb_id, created_at DESC) WHERE deleted = false`；`idx_wb_kb_doc_owner` `(user_id, created_at DESC) WHERE deleted = false`。

分块正文不落 PG，解析完成后按块写入 Manticore `lw_kbchunk`；原件在 MinIO，可随时重解析。解析链见 `specs/04`。

### 5.10 wb_doc（创作文档）

| 字段 | 类型 | 说明 |
|---|---|---|
| `doc_id` | varchar(40) PK | `DOC_` |
| `user_id` | varchar(40) NOT NULL | 归属用户 |
| `doc_title` | varchar(255) NOT NULL | 标题 |
| `doc_type` | varchar(16) NOT NULL DEFAULT 'markdown' | `DocTypeEnum`：`markdown` `rich` `code` |
| `category` | varchar(64) | 分类，创作台分组 |
| `tags` | varchar(512) | 逗号分隔标签 |
| `summary` | varchar(512) | 摘要，列表页展示 |
| `content` | text | 正文（Markdown 源码 / 富文本 HTML / 代码文本） |
| `content_text` | text | 去标签纯文本，供索引与字数统计 |
| `word_count` | int NOT NULL DEFAULT 0 | 字数 |
| `version_no` | int NOT NULL DEFAULT 1 | 当前版本号，每次保存 +1 |
| `source_kb_id` | varchar(40) | 引用知识库（可选） |
| `state` | varchar(24) NOT NULL DEFAULT 'draft' | `DocStateEnum`：`draft` `published` `archived` |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`idx_wb_doc_owner` `(user_id, updated_at DESC) WHERE deleted = false`；`idx_wb_doc_category` `(user_id, category) WHERE deleted = false`。

正文存 PG 保证强一致与事务，同时把 `content_text` 写 Manticore `lw_doc` 供全站检索，以 PG 为准，索引可重建。

### 5.11 wb_doc_version（文档版本）

| 字段 | 类型 | 说明 |
|---|---|---|
| `version_id` | varchar(40) PK | `DOCV_` |
| `doc_id` | varchar(40) NOT NULL | 归属文档 |
| `user_id` | varchar(40) NOT NULL | 归属用户 |
| `version_no` | int NOT NULL | 版本号，文档内递增 |
| `doc_title` | varchar(255) | 快照标题 |
| `content` | text | 快照正文 |
| `change_note` | varchar(255) | 变更说明 |
| `created_by` / `created_at` | | 只留创建审计，版本不可改 |

索引：`uk_wb_doc_version_no` 唯一 `(doc_id, version_no)`；`idx_wb_doc_version_doc` `(doc_id, version_no DESC)`。

每次保存正文即写一条版本，保留最近 30 条（`DocConst.VERSION_KEEP`），超出由清理接口按 `version_no` 裁剪。

### 5.12 wb_prompt（提示词）

| 字段 | 类型 | 说明 |
|---|---|---|
| `prompt_id` | varchar(40) PK | `PRPT_`，内置行为 26 位零填充序号 |
| `user_id` | varchar(40) NOT NULL | 归属用户；内置行为 `builtin` |
| `prompt_title` | varchar(128) NOT NULL | 标题 |
| `category` | varchar(64) | 分类（写作 / 改写 / 摘要 / 翻译 / 代码 …） |
| `content` | text NOT NULL | 模板正文，变量写作 `{{name}}` |
| `variables` | jsonb | 变量定义数组：`[{name, label, defaultValue}]` |
| `builtin` | boolean NOT NULL DEFAULT false | 是否内置（内置只读，用户可复制为自有） |
| `use_count` | int NOT NULL DEFAULT 0 | 使用次数，排序用 |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`idx_wb_prompt_owner` `(user_id, created_at DESC) WHERE deleted = false`。

### 5.13 wb_memory（长期记忆）

Agent 跨会话记住的用户事实与偏好。同用户同 `memory_key` 只允许一行有效记录：唯一索引 `uk_wb_memory_user_key` 只覆盖 `deleted = false`，逻辑删除后可重写同键；写入走「先 update 命中即改，否则 insert」，并发撞键由唯一索引兜底退化为更新。

| 字段 | 类型 | 说明 |
|---|---|---|
| `memory_id` | varchar(40) PK | `MEMO_` |
| `user_id` | varchar(40) NOT NULL | 归属用户，检索与注入必带 |
| `memory_key` | varchar(128) NOT NULL | 记忆键，短且可辨识 |
| `memory_value` | text NOT NULL | 记忆内容 |
| `category` | varchar(32) NOT NULL DEFAULT 'fact' | `fact` / `preference` / `constraint` / `profile` |
| `weight` | int NOT NULL DEFAULT 0 | 权重 0-9，越大越优先注入；淘汰时取最小 |
| `hit_count` | int NOT NULL DEFAULT 0 | 被检索命中次数 |
| `state` | varchar(16) | 业务状态，可空 |
| 审计 | | `created_by` `created_at` `updated_by` `updated_at` `deleted` `remark` |

索引：`uk_wb_memory_user_key` 唯一 `(user_id, memory_key) WHERE deleted = false`；`idx_wb_memory_owner` `(user_id, weight DESC, updated_at DESC) WHERE deleted = false`。

容量与注入（`MemoryConst`）：单用户上限 `MAX_PER_USER`（200），写入超限先淘汰最低权重一行；注入系统提示只取 `PROMPT_LIMIT`（5）条，总字符受 `PROMPT_MAX_CHARS`（2000）约束。记忆写入与检索经 Agent 工具 `memory_write` / `memory_search`，链路见 `specs/01`。

## 6. Manticore 检索表

四张表，应用启动时幂等创建（`CREATE TABLE IF NOT EXISTS`，`config/ManticoreIndexConfig` 接线）。统一建表约束：

| 选项 | 值 | 作用 |
|---|---|---|
| `charset_table` | `'non_cont,cjk'` | 拉丁字母数字 + CJK 表意文字入词典；缺省时该版本不建索引 |
| `morphology` | `'icu_chinese'` | ICU 中文词典分词，中英混排按词切分 |

文本列一律声明 `text indexed stored`：只写 `stored` 不建全文索引，只写默认 `text` 则检索命中后取不回原文。文档数字 ID 由引擎分配，业务主键走 string 列；过滤、排序全部走属性列（string / int / bigint）。

### 6.1 lw_log（平台日志）

```sql
CREATE TABLE IF NOT EXISTS lw_log (
    log_id     string,
    ts         bigint,
    level      string,
    logger     string,
    thread     string,
    trace_id   string,
    user_id    string,
    session_id string,
    run_id     string,
    message    text indexed stored,
    exception  text indexed stored
) charset_table='non_cont,cjk' morphology='icu_chinese'
```

`message` 是唯一全文列；`level` / `logger` / `trace_id` / `user_id` / `session_id` / `run_id` / `ts` 是过滤列，`user_id` / `session_id` / `run_id` 由 Logback MDC 注入（匿名请求与系统线程为空字符串）。保留 30 天，由应用定时任务每日执行 `DELETE FROM lw_log WHERE ts < 截止毫秒`（Manticore 无文档级 TTL）。检索、视角隔离与分布概览见 `specs/05`。

### 6.2 lw_msg（会话消息）

```sql
CREATE TABLE IF NOT EXISTS lw_msg (
    message_id  string,
    user_id     string,
    session_id  string,
    run_id      string,
    role        string,
    content     text indexed stored,
    token_count int,
    ts          bigint
) charset_table='non_cont,cjk' morphology='icu_chinese'
```

无对应 PG 表。`message_id` 为业务主键（`MESG_`），`role` 取 `MessageRoleEnum`（`user` `assistant` `tool` `system`）。会话内按 `(ts, message_id)` 稳定升序读取；跨会话全文查找按 `user_id` 过滤 + `content` 全文。无 TTL：对话是用户资产，会话删除时按 `session_id` 删除对应文档。

### 6.3 lw_kbchunk（知识库分块）

```sql
CREATE TABLE IF NOT EXISTS lw_kbchunk (
    chunk_id string,
    user_id  string,
    kb_id    string,
    doc_id   string,
    doc_name text indexed stored,
    seq      int,
    content  text indexed stored,
    ts       bigint
) charset_table='non_cont,cjk' morphology='icu_chinese'
```

检索按 `user_id` / `kb_id` 等值过滤 +（`content` 或 `doc_name`）全文，按相关度排序，高亮由前端分段渲染。无 TTL。文档重解析或删除时按 `doc_id` 删除旧分块后整批重写。

### 6.4 lw_doc（创作文档检索副本）

```sql
CREATE TABLE IF NOT EXISTS lw_doc (
    doc_id     string,
    user_id    string,
    doc_title  text indexed stored,
    category   string,
    content    text indexed stored,
    word_count int,
    updated_at bigint
) charset_table='non_cont,cjk' morphology='icu_chinese'
```

检索按 `user_id` 必带 + 可选 `category` 等值 +（`doc_title` 或 `content`）全文，默认按 `updated_at` 倒序。文档保存即覆盖写（先删同 `doc_id` 文档再插入），删除即删。无 TTL。

### 6.5 检索列与库表列的对应

| Manticore 列 | 来源 / 对应 PG 列 |
|---|---|
| `lw_msg.*` | 无 PG 表，业务主键 `message_id` 独立生成 |
| `lw_kbchunk.kb_id` / `doc_id` / `doc_name` / `user_id` | `wb_kb_doc.kb_id` / `wb_kb_doc.doc_id` / `wb_kb_doc.doc_name` / `wb_kb_doc.user_id` |
| `lw_kbchunk.chunk_id` | 检索侧独立生成（`KBCHUNK_`），无 PG 列 |
| `lw_doc.doc_id` / `user_id` / `doc_title` / `category` / `word_count` / `updated_at` | `wb_doc` 同名列（`updated_at` 取毫秒时间戳） |
| `lw_doc.content` | `wb_doc.content_text`（去标签纯文本，非 `wb_doc.content`） |
| `lw_log.*` | 无 PG 表，日志双写自 Logback |

## 7. Redis 键

前缀与拼接集中在 `domain/constant/RedisKeyConst`。

| 键 | 类型 | 用途 | TTL |
|---|---|---|---|
| `lw:lock:run:{sessionId}` | string | 会话串行锁，防并发运行（`SETNX`） | SSE 连接上限 + 60s（`SessionConst.LOCK_TTL_SECONDS`） |
| `lw:cache:provider:{code}` | string | 供应方 `provider_id` 缓存，未命中回源 | 5min |
| `lw:cache:perm:{userId}` | list | 用户权限标识集合缓存 | 10min（`liteworkbench.auth.perm-cache-seconds`，默认 600s） |
| `lw:cache:menu:{userId}` | string | 用户可见菜单树缓存（JSON） | 10min；菜单 / 授权 / 用户角色变更即清 |
| `lw:cache:overview` | string | 仪表盘汇总指标缓存（JSON） | 60s |
| `lw:auth:fail:{username}` | string | 登录失败连续计数 | 首次失败起 10min（`AuthConst.LOGIN_LOCK_SECONDS`），满 5 次锁定 |
| `lw:idem:{prefix}:{userId}:{digest}` | string | 幂等占位（`SETNX`），命中抛 `REPEAT_SUBMIT` | `@Idempotent.ttl`，默认 10s |
| `lw:rl:{prefix}:{主体}:{窗口号}` | string | 接口限流计数（固定窗口） | 窗口 + 5s |
| `satoken:login:session:{userId}` | — | Sa-Token 会话（库自带命名空间） | 随登录态 |

## 8. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 行级用户隔离（不做租户） | 模型简单，单用户视角清晰，无跨租户越权面 | 组织级汇总统计需按 `user_id` 聚合 |
| 平台级主数据不带 `user_id` | 功能升级一处生效，用户零维护 | 用户不能自定义菜单、工具与供应方 |
| 关联表用复合主键 | 无冗余列，天然防重 | 与"全表带 ULID 主键"的直觉不一致 |
| 事件表冗余 `session_id` | 按会话检索无需 join | 多存一列，写入时多带字段 |
| 事件序号 `seq` 每次运行内存分配、从 1 递增 | 无共享计数器，无并发争用 | 运行不跨重启续写，回放一律走 `wb_event` |
| 用量字段冗余在 Run 上 | 统计免 join 事件表 | 汇总与明细可能短暂不一致 |
| 每次保存即写版本行 | 回溯简单，无需 diff 算法 | 长文档版本行占空间，按 30 条裁剪 |
| 密钥加密存库 | 配置可在页面修改 | 需要管理加密密钥 |
| 日志只保留 30 天 | 存储可控 | 长周期审计需外部归档 |
| 文档正文存 PG、索引存 Manticore | 编辑强一致，检索低成本 | 双写，索引落后时以 PG 为准，可重建 |
| 消息与知识块进 Manticore、不落 PG | 全文检索免建倒排、写路径短、与日志同一套检索设施 | 检索表与 PG 分居两处，清理与备份策略要分别覆盖 |
| 知识块不落库表、原件进 MinIO | 可整篇重解析，块结构随分块策略升级 | 分块变更需重建索引 |
| 概览与菜单树走短 TTL 缓存 | 仪表盘与路由加载不打穿数据库 | 授权与统计数据有最长 1–10 分钟延迟 |
