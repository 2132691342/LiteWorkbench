-- LiteWorkbench 库表定义：全部幂等，可重复执行；种子数据由 DataInitializer 写入
-- 公共约定：主键 = 业务前缀_ULID；时间 timestamptz；逻辑删除 deleted；用户资源带 user_id

-- ============================== 用户与权限 ==============================

-- 用户
CREATE TABLE IF NOT EXISTS sys_user (
    user_id       varchar(40)  PRIMARY KEY,
    username      varchar(64)  NOT NULL,
    password_hash varchar(128) NOT NULL,
    password_salt varchar(32)  NOT NULL,
    nickname      varchar(64),
    email         varchar(128),
    avatar_url    varchar(512),
    last_login_at timestamptz,
    state         varchar(24)  NOT NULL DEFAULT 'normal',
    created_by    varchar(40),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    varchar(40),
    updated_at    timestamptz,
    deleted       boolean      NOT NULL DEFAULT false,
    remark        varchar(512)
);
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

-- 会话消息
CREATE TABLE IF NOT EXISTS wb_message (
    message_id  varchar(40) PRIMARY KEY,
    user_id     varchar(40) NOT NULL,
    session_id  varchar(40) NOT NULL,
    run_id      varchar(40),
    role_code   varchar(16) NOT NULL,
    content     text,
    token_count int         NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    deleted     boolean     NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_wb_message_session ON wb_message (session_id, created_at);

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

-- 文件对象
CREATE TABLE IF NOT EXISTS wb_file (
    file_id      varchar(40)  PRIMARY KEY,
    user_id      varchar(40)  NOT NULL,
    session_id   varchar(40),
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

-- 模型供应方（平台级）
CREATE TABLE IF NOT EXISTS wb_provider (
    provider_id   varchar(40)  PRIMARY KEY,
    provider_code varchar(24)  NOT NULL,
    provider_name varchar(64)  NOT NULL,
    base_url      varchar(512),
    api_key_enc   varchar(512),
    default_model varchar(64),
    enabled       boolean      NOT NULL DEFAULT true,
    created_by    varchar(40),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    varchar(40),
    updated_at    timestamptz,
    deleted       boolean      NOT NULL DEFAULT false,
    remark        varchar(512)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_provider_code ON wb_provider (provider_code) WHERE deleted = false;

-- 异步任务
CREATE TABLE IF NOT EXISTS wb_task (
    task_id       varchar(40) PRIMARY KEY,
    user_id       varchar(40) NOT NULL,
    task_type     varchar(32) NOT NULL,
    payload       jsonb,
    state         varchar(16) NOT NULL DEFAULT 'pending',
    retry_count   int         NOT NULL DEFAULT 0,
    error_message text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz
);
CREATE INDEX IF NOT EXISTS idx_wb_task_owner ON wb_task (user_id, state);

-- 用户设置（用户级键值）
CREATE TABLE IF NOT EXISTS wb_setting (
    setting_id    varchar(40) PRIMARY KEY,
    user_id       varchar(40) NOT NULL,
    setting_key   varchar(64) NOT NULL,
    setting_value text,
    value_type    varchar(16) NOT NULL DEFAULT 'string',
    updated_by    varchar(40),
    updated_at    timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wb_setting_key ON wb_setting (user_id, setting_key);

-- ============================== 内置工具种子 ==============================

INSERT INTO wb_tool (tool_id, tool_name, tool_label, category, risk_level, enabled)
VALUES
    ('TOOL_00000000000000000000000001', 'python_exec', 'Python 执行', 'runtime', 'confirm', true),
    ('TOOL_00000000000000000000000002', 'file_read', '读取文件', 'file', 'safe', true),
    ('TOOL_00000000000000000000000003', 'file_write', '写入文件', 'file', 'confirm', true),
    ('TOOL_00000000000000000000000004', 'http_get', 'HTTP 请求', 'network', 'safe', true)
ON CONFLICT (tool_id) DO NOTHING;

-- ============================== 旧库升级兜底（幂等） ==============================

-- v1.0.0：业务状态列统一为 state，事件与消息补归属用户
ALTER TABLE wb_run ADD COLUMN IF NOT EXISTS state varchar(16) NOT NULL DEFAULT 'queued';
ALTER TABLE wb_task ADD COLUMN IF NOT EXISTS state varchar(16) NOT NULL DEFAULT 'pending';
ALTER TABLE wb_event ADD COLUMN IF NOT EXISTS user_id varchar(40) NOT NULL DEFAULT '';
ALTER TABLE wb_message ADD COLUMN IF NOT EXISTS user_id varchar(40) NOT NULL DEFAULT '';
