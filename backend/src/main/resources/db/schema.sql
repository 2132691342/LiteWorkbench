-- LiteWorkbench 库表定义：全部幂等，可重复执行；种子数据见同目录 data.sql（先建表后写数，由 spring.sql.init 顺序执行）
-- 公共约定：主键 = 业务前缀_ULID；时间 timestamptz；逻辑删除 deleted；用户资源带 user_id

-- ============================== 用户与权限 ==============================

-- 用户
CREATE TABLE IF NOT EXISTS sys_user (
    user_id             varchar(40)  PRIMARY KEY,
    username            varchar(64)  NOT NULL,
    password_hash       varchar(256) NOT NULL,
    password_salt       varchar(32)  NOT NULL,
    nickname            varchar(64),
    email               varchar(128),
    avatar_url          varchar(512),
    last_login_at       timestamptz,
    pwd_reset_required  boolean      NOT NULL DEFAULT false,
    state               varchar(24)  NOT NULL DEFAULT 'normal',
    created_by          varchar(40),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_by          varchar(40),
    updated_at          timestamptz,
    deleted             boolean      NOT NULL DEFAULT false,
    remark              varchar(512)
);
-- 已有库的列补齐，幂等
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS pwd_reset_required boolean NOT NULL DEFAULT false;
ALTER TABLE sys_user ALTER COLUMN password_hash TYPE varchar(256);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_username ON sys_user (username) WHERE deleted = false;

-- 角色
CREATE TABLE IF NOT EXISTS sys_role (
    role_id     varchar(40) PRIMARY KEY,
    role_code   varchar(64) NOT NULL,
    role_name   varchar(64) NOT NULL,
    sort_no     int         NOT NULL DEFAULT 0,
    state       varchar(24) NOT NULL DEFAULT 'normal',
    created_by  varchar(40),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  varchar(40),
    updated_at  timestamptz,
    deleted     boolean     NOT NULL DEFAULT false,
    remark      varchar(512)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_code ON sys_role (role_code) WHERE deleted = false;

-- 菜单与权限点（平台级定义）
CREATE TABLE IF NOT EXISTS sys_menu (
    menu_id    varchar(40)  PRIMARY KEY,
    parent_id  varchar(40)  NOT NULL DEFAULT '0',
    menu_name  varchar(64)  NOT NULL,
    menu_type  varchar(16)  NOT NULL,
    route_path varchar(128),
    component  varchar(128),
    icon       varchar(64),
    perm_code  varchar(128),
    sort_no    int          NOT NULL DEFAULT 0,
    visible    boolean      NOT NULL DEFAULT true,
    state      varchar(24)  NOT NULL DEFAULT 'normal',
    created_by varchar(40),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_by varchar(40),
    updated_at timestamptz,
    deleted    boolean      NOT NULL DEFAULT false,
    remark     varchar(512)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_menu_perm ON sys_menu (perm_code) WHERE deleted = false AND perm_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sys_menu_parent ON sys_menu (parent_id);
-- 种子标记：data.sql 写入的行置位，管理员自建菜单为 false，对账时据此区分"平台种子"与"运营新增"
ALTER TABLE sys_menu ADD COLUMN IF NOT EXISTS seeded boolean NOT NULL DEFAULT false;

-- 用户-角色
CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id    varchar(40) NOT NULL,
    role_id    varchar(40) NOT NULL,
    created_by varchar(40),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

-- 角色-菜单
CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id    varchar(40) NOT NULL,
    menu_id    varchar(40) NOT NULL,
    created_by varchar(40),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, menu_id)
);

-- ============================== 工作台业务 ==============================

-- 会话
CREATE TABLE IF NOT EXISTS wb_session (
    session_id      varchar(40)  PRIMARY KEY,
    user_id         varchar(40)  NOT NULL,
    title           varchar(128) NOT NULL DEFAULT '新会话',
    provider_code   varchar(24),
    model_name      varchar(64),
    system_prompt   text,
    message_count   int          NOT NULL DEFAULT 0,
    run_count       int          NOT NULL DEFAULT 0,
    total_tokens    bigint       NOT NULL DEFAULT 0,
    last_active_at  timestamptz,
    state           varchar(24)  NOT NULL DEFAULT 'normal',
    created_by      varchar(40),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_by      varchar(40),
    updated_at      timestamptz,
    deleted         boolean      NOT NULL DEFAULT false,
    remark          varchar(512)
);
CREATE INDEX IF NOT EXISTS idx_wb_session_owner ON wb_session (user_id) WHERE deleted = false;
-- 老库补列：会话绑定的工作区，NULL 回落到用户默认工作区
ALTER TABLE wb_session ADD COLUMN IF NOT EXISTS workspace_id varchar(40);

-- 运行
CREATE TABLE IF NOT EXISTS wb_run (
    run_id        varchar(40) PRIMARY KEY,
    user_id       varchar(40) NOT NULL,
    session_id    varchar(40) NOT NULL,
    parent_run_id varchar(40),
    relation      varchar(16) NOT NULL DEFAULT 'original',
    prompt        text,
    model_name    varchar(64),
    state         varchar(16) NOT NULL DEFAULT 'queued',
    stop_reason   varchar(24),
    iter_count    int         NOT NULL DEFAULT 0,
    input_tokens  int         NOT NULL DEFAULT 0,
    output_tokens int         NOT NULL DEFAULT 0,
    started_at    timestamptz,
    ended_at      timestamptz,
    duration_ms   bigint,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wb_run_session ON wb_run (session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wb_run_owner ON wb_run (user_id, created_at DESC);

-- 运行事件流
CREATE TABLE IF NOT EXISTS wb_event (
    event_id    varchar(40) PRIMARY KEY,
    user_id     varchar(40) NOT NULL,
    run_id      varchar(40) NOT NULL,
    session_id  varchar(40) NOT NULL,
    seq         int         NOT NULL,
    event_type  varchar(32) NOT NULL,
    block_type  varchar(16),
    text_delta  text,
    payload     jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_event_seq ON wb_event (run_id, seq);
CREATE INDEX IF NOT EXISTS idx_wb_event_session ON wb_event (session_id, occurred_at);

-- 会话消息不建表：消息只存 Manticore 表 lw_msg（见 docs/DATA-MODEL.md §6.2）

-- 工具定义（平台级）
CREATE TABLE IF NOT EXISTS wb_tool (
    tool_id     varchar(40) PRIMARY KEY,
    tool_name   varchar(64) NOT NULL,
    tool_label  varchar(64) NOT NULL,
    category    varchar(24) NOT NULL,
    risk_level  varchar(16) NOT NULL DEFAULT 'safe',
    enabled     boolean     NOT NULL DEFAULT true,
    schema_json jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_tool_name ON wb_tool (tool_name);

-- 技能
CREATE TABLE IF NOT EXISTS wb_skill (
    skill_id      varchar(40)  PRIMARY KEY,
    user_id       varchar(40)  NOT NULL,
    skill_code    varchar(64)  NOT NULL,
    skill_name    varchar(128) NOT NULL,
    summary       varchar(512),
    content       text,
    trigger_words varchar(512),
    enabled       boolean      NOT NULL DEFAULT true,
    created_by    varchar(40),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    varchar(40),
    updated_at    timestamptz,
    deleted       boolean      NOT NULL DEFAULT false,
    remark        varchar(512)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_skill_code ON wb_skill (user_id, skill_code) WHERE deleted = false;
ALTER TABLE wb_skill ADD COLUMN IF NOT EXISTS builtin boolean NOT NULL DEFAULT false;

-- 内置技能种子：平台级定义（data.sql 写入），用户首次使用与升级补种时按此表复制成 wb_skill 行
-- 自然主键用 skill_code：种子按码对账，不参与业务 ID 规范
CREATE TABLE IF NOT EXISTS wb_skill_seed (
    skill_code    varchar(64)  PRIMARY KEY,
    skill_name    varchar(128) NOT NULL,
    summary       varchar(512),
    content       text,
    trigger_words varchar(512),
    sort_no       int          NOT NULL DEFAULT 0,
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- 文件对象
CREATE TABLE IF NOT EXISTS wb_file (
    file_id      varchar(40)  PRIMARY KEY,
    user_id      varchar(40)  NOT NULL,
    session_id   varchar(40),
    workspace_id varchar(40),
    file_name    varchar(255) NOT NULL,
    object_key   varchar(512) NOT NULL,
    bucket_name  varchar(64)  NOT NULL,
    file_size    bigint       NOT NULL DEFAULT 0,
    content_type varchar(128),
    category     varchar(24)  NOT NULL DEFAULT 'upload',
    created_by   varchar(40),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    deleted      boolean      NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_wb_file_owner ON wb_file (user_id, created_at DESC) WHERE deleted = false;
-- 老库补列：工作区归属，历史文件归入用户默认工作区
ALTER TABLE wb_file ADD COLUMN IF NOT EXISTS workspace_id varchar(40);
CREATE INDEX IF NOT EXISTS idx_wb_file_ws ON wb_file (workspace_id) WHERE deleted = false;

-- 工作区：用户级资产容器，每个工作区绑定一个独立 MinIO 桶，对象键前缀 user/{userId}/ws/{workspaceId}/
CREATE TABLE IF NOT EXISTS wb_workspace (
    workspace_id   varchar(40)  PRIMARY KEY,
    user_id        varchar(40)  NOT NULL,
    workspace_name varchar(64)  NOT NULL,
    bucket_name    varchar(63)  NOT NULL,
    description    varchar(512),
    is_default     boolean      NOT NULL DEFAULT false,
    state          varchar(24)  NOT NULL DEFAULT 'normal',
    created_by     varchar(40),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     varchar(40),
    updated_at     timestamptz,
    deleted        boolean      NOT NULL DEFAULT false,
    remark         varchar(512)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_ws_bucket ON wb_workspace (bucket_name) WHERE deleted = false;
CREATE INDEX IF NOT EXISTS idx_wb_ws_owner ON wb_workspace (user_id) WHERE deleted = false;

-- 模型供应方（平台级）
CREATE TABLE IF NOT EXISTS wb_provider (
    provider_id      varchar(40)  PRIMARY KEY,
    provider_code    varchar(24)  NOT NULL,
    provider_name    varchar(64)  NOT NULL,
    base_url         varchar(512),
    api_key_enc      varchar(512),
    default_model    varchar(64),
    context_window   int,
    max_output_tokens int,
    temperature      numeric(4,3),
    top_p            numeric(4,3),
    cap_vision       boolean      NOT NULL DEFAULT false,
    cap_tool         boolean      NOT NULL DEFAULT true,
    cap_reasoning    boolean      NOT NULL DEFAULT false,
    enabled          boolean      NOT NULL DEFAULT true,
    created_by       varchar(40),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_by       varchar(40),
    updated_at       timestamptz,
    deleted          boolean      NOT NULL DEFAULT false,
    remark           varchar(512)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_provider_code ON wb_provider (provider_code) WHERE deleted = false;
-- 模型参数列：NULL 表示回落全局默认，存量行不受影响
ALTER TABLE wb_provider ADD COLUMN IF NOT EXISTS context_window int;
ALTER TABLE wb_provider ADD COLUMN IF NOT EXISTS max_output_tokens int;
ALTER TABLE wb_provider ADD COLUMN IF NOT EXISTS temperature numeric(4,3);
ALTER TABLE wb_provider ADD COLUMN IF NOT EXISTS top_p numeric(4,3);
ALTER TABLE wb_provider ADD COLUMN IF NOT EXISTS cap_vision boolean NOT NULL DEFAULT false;
ALTER TABLE wb_provider ADD COLUMN IF NOT EXISTS cap_tool boolean NOT NULL DEFAULT true;
ALTER TABLE wb_provider ADD COLUMN IF NOT EXISTS cap_reasoning boolean NOT NULL DEFAULT false;

-- 知识库
CREATE TABLE IF NOT EXISTS wb_kb (
    kb_id      varchar(40)  PRIMARY KEY,
    user_id    varchar(40)  NOT NULL,
    kb_name    varchar(128) NOT NULL,
    description varchar(512),
    doc_count   int         NOT NULL DEFAULT 0,
    chunk_count int         NOT NULL DEFAULT 0,
    state       varchar(24) NOT NULL DEFAULT 'normal',
    created_by  varchar(40),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  varchar(40),
    updated_at  timestamptz,
    deleted     boolean     NOT NULL DEFAULT false,
    remark      varchar(512)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_kb_name ON wb_kb (user_id, kb_name) WHERE deleted = false;

-- 知识库文档（原件在 MinIO，分块在 Manticore）
CREATE TABLE IF NOT EXISTS wb_kb_doc (
    doc_id        varchar(40)  PRIMARY KEY,
    user_id       varchar(40)  NOT NULL,
    kb_id         varchar(40)  NOT NULL,
    doc_name      varchar(255) NOT NULL,
    object_key    varchar(512) NOT NULL,
    bucket_name   varchar(64)  NOT NULL,
    file_size     bigint       NOT NULL DEFAULT 0,
    content_type  varchar(128),
    char_count    int          NOT NULL DEFAULT 0,
    chunk_count   int          NOT NULL DEFAULT 0,
    state         varchar(16)  NOT NULL DEFAULT 'pending',
    error_message text,
    created_by    varchar(40),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    varchar(40),
    updated_at    timestamptz,
    deleted       boolean      NOT NULL DEFAULT false,
    remark        varchar(512)
);
CREATE INDEX IF NOT EXISTS idx_wb_kb_doc_kb ON wb_kb_doc (kb_id, created_at DESC) WHERE deleted = false;
CREATE INDEX IF NOT EXISTS idx_wb_kb_doc_owner ON wb_kb_doc (user_id, created_at DESC) WHERE deleted = false;

-- 创作文档
CREATE TABLE IF NOT EXISTS wb_doc (
    doc_id       varchar(40)  PRIMARY KEY,
    user_id      varchar(40)  NOT NULL,
    doc_title    varchar(255) NOT NULL,
    doc_type     varchar(16)  NOT NULL DEFAULT 'markdown',
    category     varchar(64),
    tags         varchar(512),
    summary      varchar(512),
    content      text,
    content_text text,
    word_count   int          NOT NULL DEFAULT 0,
    version_no   int          NOT NULL DEFAULT 1,
    source_kb_id varchar(40),
    state        varchar(24)  NOT NULL DEFAULT 'draft',
    created_by   varchar(40),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   varchar(40),
    updated_at   timestamptz,
    deleted      boolean      NOT NULL DEFAULT false,
    remark       varchar(512)
);
CREATE INDEX IF NOT EXISTS idx_wb_doc_owner ON wb_doc (user_id, updated_at DESC) WHERE deleted = false;
CREATE INDEX IF NOT EXISTS idx_wb_doc_category ON wb_doc (user_id, category) WHERE deleted = false;

-- 创作文档版本快照
CREATE TABLE IF NOT EXISTS wb_doc_version (
    version_id  varchar(40)  PRIMARY KEY,
    doc_id      varchar(40)  NOT NULL,
    user_id     varchar(40)  NOT NULL,
    version_no  int          NOT NULL,
    doc_title   varchar(255),
    content     text,
    change_note varchar(255),
    created_by  varchar(40),
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_doc_version_no ON wb_doc_version (doc_id, version_no);
CREATE INDEX IF NOT EXISTS idx_wb_doc_version_doc ON wb_doc_version (doc_id, version_no DESC);

-- 长期记忆：用户显式沉淀的事实与偏好，跨会话注入系统提示，按用户隔离
CREATE TABLE IF NOT EXISTS wb_memory (
    memory_id    varchar(40)  PRIMARY KEY,
    user_id      varchar(40)  NOT NULL,
    memory_key   varchar(128) NOT NULL,
    memory_value text         NOT NULL,
    category     varchar(32)  NOT NULL DEFAULT 'fact',
    weight       int          NOT NULL DEFAULT 0,
    hit_count    int          NOT NULL DEFAULT 0,
    created_by   varchar(40),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   varchar(40),
    updated_at   timestamptz,
    deleted      boolean      NOT NULL DEFAULT false,
    state        varchar(16),
    remark       varchar(512)
);
-- 同一用户同 key 只留一行有效记忆：写入走 upsert，避免模型重复记忆堆成噪音
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_memory_user_key ON wb_memory (user_id, memory_key) WHERE deleted = false;
CREATE INDEX IF NOT EXISTS idx_wb_memory_owner ON wb_memory (user_id, weight DESC, updated_at DESC) WHERE deleted = false;

-- ============================== 旧库升级兜底（幂等） ==============================
-- 只针对仍然存在的表；已下线表的语句不得保留，否则建表脚本会整段失败

ALTER TABLE wb_run ADD COLUMN IF NOT EXISTS state varchar(16) NOT NULL DEFAULT 'queued';
ALTER TABLE wb_event ADD COLUMN IF NOT EXISTS user_id varchar(40) NOT NULL DEFAULT '';

-- ============================== 已下线表清理 ==============================
-- 已下线表：wb_flow（可视化编排画布）、wb_task（异步任务预留）、wb_prompt（提示词模板，能力并入技能），DROP 幂等
DROP TABLE IF EXISTS wb_flow;
DROP TABLE IF EXISTS wb_task;
DROP TABLE IF EXISTS wb_prompt;
