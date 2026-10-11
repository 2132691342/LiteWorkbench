# DATA-MODEL

库表结构、Redisearch 索引、ID 规范、审计字段。DDL 实现在 `server/src/main/resources/db/schema.sql`。

---

## 1. 定位

数据分四类存放，各取所长：业务关系型数据与权限主数据进 PostgreSQL；可检索的日志文本进 Redisearch；文件二进制进 MinIO；临时状态、登录态与计数器进 Redis。

不做多租户。数据归属以用户为单位：用户拥有的业务数据带 `user_id`，平台级主数据（菜单定义、角色授权关系、工具与供应方定义）例外。

## 2. ID 规范

- 全部 ULID，格式 `业务前缀_` + 26 位大写 ULID。
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
| `MESG_` | 消息 | `wb_message` |
| `TOOL_` | 工具 | `wb_tool` |
| `SKIL_` | 技能 | `wb_skill` |
| `FILE_` | 文件 | `wb_file` |
| `PROV_` | 模型供应方 | `wb_provider` |
| `TASK_` | 异步任务（预留） | `wb_task` |
| `SETG_` | 系统设置 | `wb_setting` |
| `LOGD_` | 日志文档 | Redisearch `idx:lw:log` |

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
| `password_hash` | varchar(128) | SHA-256(密码 + salt) 摘要 |
| `password_salt` | varchar(32) | 随机盐 |
| `nickname` | varchar(64) | 显示名 |
| `email` | varchar(128) | 邮箱 |
| `avatar_url` | varchar(512) | 头像 |
| `last_login_at` | timestamptz | 最近登录 |
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
| `menu_name` | varchar(64) | 菜单名（i18n key） |
| `menu_type` | varchar(16) | `dir` 目录 / `menu` 路由页 / `button` 权限点 |
| `route_path` | varchar(128) | 前端路由路径 |
| `component` | varchar(128) | 前端组件路径 |
| `icon` | varchar(64) | 图标名 |
| `perm_code` | varchar(128) | 权限标识 `system:user:create` |
| `sort_no` | int | 排序 |
| `visible` | boolean | 是否显示 |
| `state` | varchar(24) | `normal` `disabled` |
| 审计字段 | | |

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
| `relation` | varchar(16) | `original` / `retry` / `fork` / `recovery`，MVP 恒为 `original`，`parent_run_id` 同为预留 |
| `prompt` | text | 本次输入 |
| `model_name` | varchar(64) | 实际使用模型 |
| `status` | varchar(16) | `queued` `running` `success` `failed` `aborted` |
| `stop_reason` | varchar(24) | `completed` `max_iters` `aborted` `error` |
| `iter_count` | int | 迭代轮次 |
| `input_tokens` / `output_tokens` | int | 用量 |
| `started_at` / `ended_at` | timestamptz | 起止 |
| `duration_ms` | bigint | 耗时 |

索引：`(session_id, created_at DESC)`、`(user_id, created_at DESC)`。

### 5.3 wb_event

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_id` | varchar(40) PK | `EVNT_` |
| `run_id` / `session_id` | varchar(40) | 归属 |
| `seq` | int | 运行内序号，从 1 递增 |
| `event_type` | varchar(32) | 归一后的事件类型 |
| `block_type` | varchar(16) | `text` `thinking` `tool_call` `tool_result` |
| `text_delta` | text | 增量文本 |
| `payload` | jsonb | 完整事件体 |
| `occurred_at` | timestamptz | 发生时间 |

索引：`(run_id, seq)` 唯一，`(session_id, occurred_at)`。

### 5.4 wb_message

| 字段 | 类型 | 说明 |
|---|---|---|
| `message_id` | varchar(40) PK | `MESG_` |
| `session_id` / `run_id` | varchar(40) | 归属 |
| `role_code` | varchar(16) | `user` `assistant` `tool` |
| `content` | text | 正文 |
| `token_count` | int | 估算用量 |

索引：`(session_id, created_at)`。

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
| `status` | varchar(16) | `pending` `running` `success` `failed` |
| `retry_count` | int | 重试次数 |
| `error_message` | text | 错误信息 |

### 5.10 wb_setting

| 字段 | 类型 | 说明 |
|---|---|---|
| `setting_id` | varchar(40) PK | `SETG_` |
| `user_id` | varchar(40) | 归属用户 |
| `setting_key` | varchar(64) | 键，用户内唯一 |
| `setting_value` | text | 值 |
| `value_type` | varchar(16) | `string` `int` `bool` `json` |

## 6. Redisearch 索引

索引名 `idx:lw:log`，哈希文档，键前缀 `lw:log:`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `log_id` | TAG | `LOGD_` |
| `ts` | NUMERIC SORTABLE | 毫秒时间戳 |
| `level` | TAG | `ERROR` `WARN` `INFO` |
| `logger` | TAG | 记录器名 |
| `thread` | TAG | 线程名 |
| `trace_id` | TAG | 链路标识 |
| `message` | TEXT | 正文 |
| `exception` | TEXT | 异常堆栈 |

检索语法：`@level:{ERROR} @message:timeout`，按 `ts` 倒序。

## 7. Redis 键

| 键 | 类型 | 用途 |
|---|---|---|
| `lw:run:{runId}:abort` | string | 中断标记，TTL 1h |
| `lw:lock:run:{sessionId}` | string | 会话串行锁，防并发运行 |
| `lw:cache:provider:{code}` | string | 供应方配置 ID 缓存（命中后回源取详情），TTL 5min |
| `lw:cache:perm:{userId}` | set | 用户权限标识缓存，TTL 10min |
| `lw:idem:{prefix}:{userId}:{digest}` | string | 幂等占位，TTL 默认 10s |
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
