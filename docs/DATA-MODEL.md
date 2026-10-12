# DATA-MODEL

库表结构、Manticore 检索表、ID 规范、审计字段。PG DDL 实现在 `backend/src/main/resources/db/schema.sql`，Manticore DDL 由应用启动时幂等执行（`config/ManticoreIndexConfig`）。

---

## 1. 定位

数据分五类存放，各取所长：业务关系型数据与权限主数据进 PostgreSQL；**会话消息、知识库分块、平台日志、创作文档检索副本**这类需要全文检索的文本进 Manticore；文件与知识库文档原件进 MinIO；缓存、登录态、幂等占位、限流计数与运行锁进 Redis；MQ 只做削峰管道（日志索引、知识库解析任务）。

不做多租户。数据归属以用户为单位：用户拥有的业务数据带 `user_id`（库表与 Manticore 文档皆然），平台级主数据（菜单定义、角色授权关系、工具与供应方定义）例外。

## 2. ID 规范

- 业务 ID 全部 ULID，格式 `业务前缀_` + 26 位大写 ULID；种子数据（菜单、内置提示词）例外，用 `前缀_ + 26 位零填充序号` 保证升级时可稳定对账。
- 生成入口唯一：`common.IdGenerator`。
- 关联表（`sys_user_role`、`sys_role_menu`）不引入代理主键，用复合主键。

| 前缀 | 业务 | 表 |
|---|---|---|
| `USER_` | 用户 | `sys_user` |
| `ROLE_` | 角色 | `sys_role` |
| `MENU_` | 菜单 / 权限点 | `sys_menu` |
| `SESS_` | 会话 | `wb_session` |
| `RUN_` | 运行 | `wb_run` |
| `EVNT_` | 事件 | `wb_event` |
| `MESG_` | 消息 | Manticore `lw_msg` |
| `TOOL_` | 工具 | `wb_tool` |
| `SKIL_` | 技能 | `wb_skill` |
| `FILE_` | 文件 | `wb_file` |
| `PROV_` | 模型供应方 | `wb_provider` |
| `KB_` | 知识库 | `wb_kb` |
| `KBDOC_` | 知识库文档 | `wb_kb_doc` |
| `KBCHUNK_` | 知识库分块 | Manticore `lw_kbchunk` |
| `DOC_` | 创作文档 | `wb_doc` / Manticore `lw_doc` |
| `DOCV_` | 文档版本 | `wb_doc_version` |
| `PRPT_` | 提示词 | `wb_prompt` |
| `FLOW_` | 工作流画布 | `wb_flow` |
| `TASK_` | 异步任务（预留） | `wb_task` |
| `LOGD_` | 日志文档 | Manticore `lw_log` |

## 3. 审计字段

每张表按需取用，出现即按下表语义，不重命名、不改类型。

| 字段 | 类型 | 语义 |
|---|---|---|
| `created_by` | varchar(40) | 创建人 ID |
| `created_at` | timestamptz | 创建时间 |
| `updated_by` | varchar(40) | 更新人 ID |
| `updated_at` | timestamptz | 更新时间 |
| `deleted` | boolean | 逻辑删除，默认 false |
| `state` | varchar(24) | 业务状态枚举码 |
| `remark` | varchar(512) | 备注 |

## 4. 用户与权限表

### 4.1 sys_user

| 字段 | 类型 | 说明 |
|---|---|---|
| `user_id` | varchar(40) PK | `USER_` |
| `username` | varchar(64) | 登录名，全局唯一（`deleted = false` 范围内） |
| `password_hash` | varchar(256) | 口令摘要，格式 `pbkdf2$迭代次数$盐$Base64`；校验兼容旧 `SHA-256(密码 + 盐)` 记录 |
| `password_salt` | varchar(32) | 随机盐 |
| `nickname` | varchar(64) | 显示名 |
| `email` | varchar(128) | 邮箱 |
| `avatar_url` | varchar(512) | 头像 |
| `last_login_at` | timestamptz | 最近登录 |
| `pwd_reset_required` | boolean | 是否必须先改密（种子账号为 true），改密成功后置 false |
| `state` | varchar(24) | `normal` `disabled` `locked` |
| 审计字段 | | |

### 4.2 sys_role

| 字段 | 类型 | 说明 |
|---|---|---|
| `role_id` | varchar(40) PK | `ROLE_` |
| `role_code` | varchar(64) | 角色码，全局唯一（`deleted = false` 范围内） |
| `role_name` | varchar(64) | 角色名 |
| `sort_no` | int | 排序 |
| `state` | varchar(24) | `normal` `disabled` |
| 审计字段 | | |

内置角色码：`super_admin`（超级管理员，内置不可删）、`admin`（平台管理员）、`user`（普通用户）。

### 4.3 sys_menu（平台级）

| 字段 | 类型 | 说明 |
|---|---|---|
| `menu_id` | varchar(40) PK | `MENU_` |
| `parent_id` | varchar(40) | 父菜单，根为 `0` |
| `menu_name` | varchar(64) | 菜单名，**存 i18n key 而非中文**，如 `menu.userManage`、`menu.groupSystem` |
| `menu_type` | varchar(16) | `dir` 目录 / `menu` 路由页 / `button` 权限点 |
| `route_path` | varchar(128) | 前端路由路径 |
| `component` | varchar(128) | 前端组件路径 |
| `icon` | varchar(64) | 图标名 |
| `perm_code` | varchar(128) | 权限标识 `system:user:create` |
| `sort_no` | int | 排序 |
| `visible` | boolean | 是否显示 |
| `state` | varchar(24) | `normal` `disabled` |
| 审计字段 | | |

**menu_name 的 i18n key 规则**：不得让同一段"既是叶子又是分组"（`menu.system` 与 `menu.system.user` 会互相遮蔽，vue-i18n 点路径解析下菜单退化成裸 key 或直接显示英文）。路由菜单 `menu.<camelCase>`、目录 `menu.group<Thing>`、权限点 `menu.<路由key><Action>`。

**种子对账**：种子 ID 为 `MENU_ + 26 位零填充序号`（如 `MENU_00000000000000000000000011`），启动时按 ID upsert 回写全部字段，并软删"`route_path` 或 `perm_code` 命中种子、但 `menu_id` 不在种子内"的历史残留行。同理，内置提示词 ID 为 `PRPT_ + 26 位零填充序号`。

### 4.4 sys_user_role / sys_role_menu

| 表 | 主键 | 字段 |
|---|---|---|
| `sys_user_role` | `(user_id, role_id)` | + `created_by` `created_at` |
| `sys_role_menu` | `(role_id, menu_id)` | + `created_by` `created_at` |

## 5. 业务表

用户拥有的业务表带 `user_id varchar(40)`，归属条件显式写入 SQL。

### 5.1 wb_session

| 字段 | 类型 | 说明 |
|---|---|---|
| `session_id` | varchar(40) PK | `SESS_` |
| `user_id` | varchar(40) | 归属用户 |
| `title` | varchar(128) | 会话标题 |
| `provider_code` | varchar(24) | `openai` / `anthropic` / `ollama` |
| `model_name` | varchar(64) | 模型名 |
| `system_prompt` | text | 系统提示词 |
| `message_count` | int | 消息数 |
| `run_count` | int | 运行数 |
| `total_tokens` | bigint | 累计用量 |
| `last_active_at` | timestamptz | 最近活跃 |
| 审计字段 | | |

索引：`(user_id)`，`deleted = false` 范围内。

### 5.2 wb_run

| 字段 | 类型 | 说明 |
|---|---|---|
| `run_id` | varchar(40) PK | `RUN_` |
| `user_id` / `session_id` | varchar(40) | 归属 |
| `parent_run_id` | varchar(40) | 父运行，重试/分支时使用 |
| `relation` | varchar(16) | `original`（会话对话） / `flow`（工作流执行）；`retry` `fork` `recovery` 与 `parent_run_id` 同为预留 |
| `prompt` | text | 本次输入 |
| `model_name` | varchar(64) | 实际使用模型 |
| `state` | varchar(16) | `queued` `running` `success` `failed` `aborted` |
| `stop_reason` | varchar(24) | `completed` `max_iters` `aborted` `error` |
| `iter_count` | int | 迭代轮次 |
| `input_tokens` / `output_tokens` | int | 用量 |
| `created_at` | timestamptz | 创建时间 |
| `started_at` / `ended_at` | timestamptz | 起止 |
| `duration_ms` | bigint | 耗时 |

索引：`(session_id, created_at DESC)`、`(user_id, created_at DESC)`。`session_id` 可空：工作流执行不挂会话，这类运行的 `session_id` 为 NULL。

### 5.3 wb_event

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | varchar(40) PK | `EVNT_` |
| `user_id` | varchar(40) | 归属用户 |
| `run_id` / `session_id` | varchar(40) | 归属 |
| `seq` | int | 运行内序号，从 1 递增 |
| `event_type` | varchar(32) | 归一后的事件类型 |
| `block_type` | varchar(16) | `text` `thinking` `tool_call` `tool_result` |
| `text_delta` | text | 增量文本 |
| `payload` | jsonb | 完整事件体 |
| `occurred_at` | timestamptz | 发生时间 |

索引：`(run_id, seq)` 唯一，`(session_id, occurred_at)`。

### 5.4 会话消息（Manticore `lw_msg`，无库表）

消息是典型的"写完读、按会话整页读、偶尔全文找"的数据，落 Manticore 表 `lw_msg`，不建 PG 库表。字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `message_id` | string | `MESG_`，业务主键 |
| `user_id` | string | 归属用户，过滤属性 |
| `session_id` | string | 归属会话，过滤属性 |
| `run_id` | string | 产生消息的运行，可空 |
| `role` | string | `user` `assistant` `tool` `system` |
| `content` | text indexed stored | 正文，全文检索主体 |
| `token_count` | int | 估算用量 |
| `ts` | bigint | 毫秒时间戳，会话内排序依据 |

文档数字 ID 由引擎自动分配，业务主键走 `message_id` 列。无 TTL：对话是用户资产，不随时间蒸发；同毫秒写入的次序由 ULID 单调性兜底，读侧按 `(ts, message_id)` 内存稳定排序。会话删除时按 `session_id` 删除对应文档。

### 5.5 wb_tool（平台级）

| 字段 | 类型 | 说明 |
|---|---|---|
| `tool_id` | varchar(40) PK | `TOOL_` |
| `tool_name` | varchar(64) UNIQUE | 调用名 |
| `tool_label` | varchar(64) | 展示名 |
| `category` | varchar(24) | `runtime` `file` `network` |
| `risk_level` | varchar(16) | `safe` `confirm` `danger` |
| `enabled` | boolean | 是否启用 |
| `schema_json` | jsonb | 参数 Schema |

### 5.6 wb_skill

| 字段 | 类型 | 说明 |
|---|---|---|
| `skill_id` | varchar(40) PK | `SKIL_` |
| `user_id` | varchar(40) | 归属用户 |
| `skill_code` | varchar(64) | 技能标识，用户内唯一 |
| `skill_name` | varchar(128) | 名称 |
| `summary` | varchar(512) | 摘要，常驻提示词 |
| `content` | text | 正文，命中后展开 |
| `trigger_words` | varchar(512) | 触发词，逗号分隔 |
| `enabled` | boolean | 是否启用 |
| 审计字段 | | |

### 5.7 wb_file

| 字段 | 类型 | 说明 |
|---|---|---|
| `file_id` | varchar(40) PK | `FILE_` |
| `user_id` / `session_id` | varchar(40) | 归属，会话可空 |
| `file_name` | varchar(255) | 文件名 |
| `object_key` | varchar(512) | MinIO 对象键，含用户前缀 |
| `bucket_name` | varchar(64) | 桶名 |
| `file_size` | bigint | 字节数 |
| `content_type` | varchar(128) | MIME |
| `category` | varchar(24) | `upload` `artifact` `log` |
| 审计字段 | | |

### 5.8 wb_provider（平台级）

| 字段 | 类型 | 说明 |
|---|---|---|
| `provider_id` | varchar(40) PK | `PROV_` |
| `provider_code` | varchar(24) | `openai` / `anthropic` / `ollama`，全局唯一 |
| `provider_name` | varchar(64) | 展示名 |
| `base_url` | varchar(512) | 端点 |
| `api_key_enc` | varchar(512) | 加密存储的密钥，`ollama` 可空 |
| `default_model` | varchar(64) | 默认模型 |
| `enabled` | boolean | 是否启用 |
| 审计字段 | | |

### 5.9 wb_task

| 字段 | 类型 | 说明 |
|---|---|---|
| `task_id` | varchar(40) PK | `TASK_` |
| `user_id` | varchar(40) | 归属用户 |
| `task_type` | varchar(32) | 任务类型 |
| `payload` | jsonb | 参数 |
| `state` | varchar(16) | `pending` `running` `success` `failed` |
| `retry_count` | int | 重试次数 |
| `error_message` | text | 错误信息 |

### 5.10 wb_kb

| 字段 | 类型 | 说明 |
|---|---|---|
| `kb_id` | varchar(40) PK | `KB_` |
| `user_id` | varchar(40) | 归属用户 |
| `kb_name` | varchar(128) | 知识库名，用户内唯一 |
| `description` | varchar(512) | 描述 |
| `doc_count` / `chunk_count` | int | 文档与分块计数，冗余免实时聚合 |
| 审计字段 | | |

### 5.11 wb_kb_doc

| 字段 | 类型 | 说明 |
|---|---|---|
| `doc_id` | varchar(40) PK | `KBDOC_` |
| `user_id` / `kb_id` | varchar(40) | 归属 |
| `doc_name` | varchar(255) | 文档名 |
| `object_key` / `bucket_name` | varchar | MinIO 对象键（原件 `user/{userId}/kbdoc/...`）与桶 |
| `file_size` | bigint | 字节数 |
| `content_type` | varchar(128) | MIME |
| `char_count` | int | 提取出的正文总字符数 |
| `chunk_count` | int | 分块数 |
| `state` | varchar(16) | `pending` `processing` `ready` `failed` |
| `error_message` | text | 解析失败原因 |
| 审计字段 | | |

分块正文不落 PG：解析完成后按块写入 Manticore `lw_kbchunk`，原件在 MinIO，可随时重解析。解析走 MQ（`lw.kb.parse`）异步执行，上传接口只做 MinIO 落盘 + 置 `pending`。

### 5.12 wb_doc（创作文档）

| 字段 | 类型 | 说明 |
|---|---|---|
| `doc_id` | varchar(40) PK | `DOC_` |
| `user_id` | varchar(40) | 归属用户 |
| `doc_title` | varchar(255) | 标题 |
| `doc_type` | varchar(16) | `markdown` `rich` `code` |
| `category` | varchar(64) | 分类，便于创作台分组 |
| `tags` | varchar(512) | 逗号分隔标签 |
| `summary` | varchar(512) | 摘要，列表页展示 |
| `content` | text | 正文（Markdown 源码 / 富文本 HTML / 代码文本） |
| `content_text` | text | 去标签纯文本，供索引与字数统计 |
| `word_count` | int | 字数 |
| `version_no` | int | 当前版本号，每次保存 +1 |
| `source_kb_id` | varchar(40) | 引用知识库（可选，AI 辅助写作时检索范围） |
| `state` | varchar(24) | `draft` `published` `archived` |
| 审计字段 | | |

正文存 PG 保证强一致与事务；同时把 `content_text` 写 Manticore `lw_doc` 供全站检索，两份数据以 PG 为准，索引可重建。

### 5.13 wb_doc_version（文档版本）

| 字段 | 类型 | 说明 |
|---|---|---|
| `version_id` | varchar(40) PK | `DOCV_` |
| `doc_id` | varchar(40) | 归属文档 |
| `user_id` | varchar(40) | 归属用户 |
| `version_no` | int | 版本号，文档内递增 |
| `doc_title` | varchar(255) | 快照标题 |
| `content` | text | 快照正文 |
| `change_note` | varchar(255) | 变更说明 |
| `created_by` / `created_at` | | 只留创建审计，版本不可改 |

每次保存正文即写一条版本，保留最近 30 条，超出由清理接口按 `version_no` 裁剪。

### 5.14 wb_prompt（提示词）

| 字段 | 类型 | 说明 |
|---|---|---|
| `prompt_id` | varchar(40) PK | `PRPT_` |
| `user_id` | varchar(40) | 归属用户；`builtin` 行为内置模板 |
| `prompt_title` | varchar(128) | 标题 |
| `category` | varchar(64) | 分类（写作 / 改写 / 摘要 / 翻译 / 代码 …） |
| `content` | text | 模板正文，变量写作 `{{name}}` |
| `variables` | jsonb | 变量定义数组：`[{name, label, defaultValue}]` |
| `builtin` | boolean | 是否内置（内置只读，用户可复制为自有） |
| `use_count` | int | 使用次数，排序用 |
| 审计字段 | | |

### 5.15 wb_flow（工作流画布）

| 字段 | 类型 | 说明 |
|---|---|---|
| `flow_id` | varchar(40) PK | `FLOW_` |
| `user_id` | varchar(40) | 归属用户 |
| `flow_name` | varchar(128) | 名称，用户内唯一 |
| `description` | varchar(512) | 描述 |
| `graph_json` | jsonb | X6 画布结构：`{nodes:[], edges:[]}` |
| `node_count` | int | 节点数，冗余 |
| `last_run_id` | varchar(40) | 最近一次运行的 Run |
| `state` | varchar(24) | `draft` `enabled` `disabled` |
| 审计字段 | | |

节点类型：`start` `llm` `tool` `python` `knowledge` `end`。画布结构只存 JSON，不建节点表：节点是画布的内部结构，没有独立查询需求。

## 6. Manticore 检索表

四张表，应用启动时幂等创建（`CREATE TABLE IF NOT EXISTS`）。所有表统一带两个建表选项（缺一不可）：

| 选项 | 值 | 作用 |
|---|---|---|
| `charset_table` | `'non_cont,cjk'` | 拉丁字母数字 + CJK 表意文字入词典；不写该选项默认表在此版本不建索引 |
| `morphology` | `'icu_chinese'` | ICU 中文词典分词，中英混排按词切分；不引语言包、不用 ngram |

文本列一律声明 `text indexed stored`：只写 `stored` 不建全文索引，只写默认 `text` 则检索命中后取不回原文。

文档数字 ID 由引擎自动分配，业务主键走 string 列；过滤、排序全部走属性列（string / int / bigint）。

### 6.1 lw_log（平台日志）

```sql
CREATE TABLE IF NOT EXISTS lw_log (
    log_id    string,
    ts        bigint,
    level     string,
    logger    string,
    thread    string,
    trace_id  string,
    message   text indexed stored,
    exception text indexed stored
) charset_table='non_cont,cjk' morphology='icu_chinese'
```

检索：`level` 等值 + `ts` 范围过滤，关键字全文走 `message`，按 `ts` 倒序分页。保留 30 天：应用定时任务每日执行 `DELETE FROM lw_log WHERE ts < 截止毫秒`（Manticore 无文档级 TTL）。

### 6.2 lw_msg（会话消息）

见 5.4。会话消息按 `session_id` 过滤 + `ts` 升序读取；跨会话全文查找按 `user_id` 过滤 + `content` 全文。无 TTL。

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

检索：`user_id` / `kb_id` 等值过滤 + `content` 全文，按相关度排序返回；高亮由前端按关键字分段渲染，后端只回原文。无 TTL。文档重解析或删除时按 `doc_id` 删除旧分块后整批重写。

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

检索：`user_id` 必带 + 可选 `category` 等值 +（`doc_title` 或 `content`）全文，默认按 `updated_at` 倒序。文档保存即覆盖写（先删同 `doc_id` 文档再插入），删除即删。无 TTL。

## 7. Redis 键

| 键 | 类型 | 用途 |
|---|---|---|
| `lw:lock:run:{sessionId}` | string | 会话串行锁，防并发运行 |
| `lw:cache:provider:{code}` | string | 供应方配置 ID 缓存（命中后回源取详情），TTL 5min |
| `lw:cache:perm:{userId}` | set | 用户权限标识缓存，TTL 10min |
| `lw:cache:menu:{userId}` | string | 用户可见菜单树缓存（JSON），TTL 10min，菜单 / 授权 / 用户角色变更即清 |
| `lw:cache:overview` | string | 仪表盘汇总指标缓存（JSON），TTL 60s |
| `lw:auth:fail:{username}` | string | 登录失败连续计数，达到 5 次锁定 10 分钟 |
| `lw:idem:{prefix}:{userId}:{digest}` | string | 幂等占位，TTL 默认 10s |
| `lw:rl:{prefix}:{主体}:{窗口号}` | string | 接口限流计数（@RateLimit 固定窗口），TTL 窗口 + 5s |
| `satoken:login:session:{userId}` | — | Sa-Token 会话（库自带命名空间） |

## 8. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 行级用户隔离（不做租户） | 模型简单，单用户视角清晰，无跨租户越权面 | 组织级汇总统计需按 `user_id` 聚合 |
| 菜单定义为平台级 | 功能升级一处生效，用户零维护 | 用户不能自定义菜单形态 |
| 工具与供应方定义为平台级 | 工具实现与启停、模型端点全局一致，风险标注统一 | 不能按用户私有化定义 |
| 关联表用复合主键 | 无冗余列，天然防重 | 与"全表带 ULID 主键"的直觉不一致 |
| 事件表冗余 `session_id` | 按会话检索无需 join | 多存一列，写入时多带字段 |
| 事件序号 `seq` 每次运行内存分配、从 1 递增 | 无共享计数器，无并发争用 | 运行不跨重启续写，回放一律走 `wb_event` |
| 用量字段冗余在 Run 上 | 统计免 join 事件表 | 汇总与明细可能短暂不一致 |
| 日志只保留 30 天 | 存储可控 | 长周期审计需外部归档 |
| 密钥加密存库 | 配置可在页面修改 | 需要管理加密密钥 |
| `wb_task` 保留表结构但本期不暴露接口 | 异步任务入口已预留 | 短期无消费者，表为空 |
| 文档正文存 PG、索引存 Manticore | 编辑强一致，检索低成本 | 双写，索引落后时以 PG 为准，可重建 |
| 每次保存即写版本行 | 回溯简单，无需 diff 算法 | 长文档版本行占空间，按 30 条裁剪 |
| 画布结构存单个 jsonb | 节点无独立查询需求，一次读写整个图 | 不能按节点做条件统计，图过大时更新成本高 |
| 提示词内置行归 `builtin` 用户 | 内置模板与用户模板同表同查询，免分表 | 内置行不可编辑，用户只能复制后用 |
| 消息与知识块进 Manticore、不落 PG | 全文检索免建倒排、写路径短、与日志同一套检索设施 | 检索表与 PG 分居两处，清理与备份策略要分别覆盖 |
| 知识块不落库表，原件进 MinIO | 可整篇重解析，块结构随分块策略升级 | 分块变更需重建索引 |
| 概览与菜单树走短 TTL 缓存 | 仪表盘与路由加载不打穿数据库 | 授权与统计数据有最长 1–10 分钟延迟 |
