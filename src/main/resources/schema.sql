CREATE TABLE IF NOT EXISTS vtj_projects (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    platform VARCHAR(32) NOT NULL DEFAULT 'web',
    dsl JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vtj_files (
    id VARCHAR(128) PRIMARY KEY,
    project_id VARCHAR(128),
    platform VARCHAR(32) NOT NULL DEFAULT 'web',
    name VARCHAR(255),
    dsl JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vtj_files_project ON vtj_files(project_id);

CREATE TABLE IF NOT EXISTS vtj_histories (
    id VARCHAR(128) PRIMARY KEY,
    project_id VARCHAR(128),
    history JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vtj_history_items (
    file_id VARCHAR(128) NOT NULL,
    id VARCHAR(128) NOT NULL,
    item JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (file_id, id)
);

CREATE TABLE IF NOT EXISTS vtj_materials (
    project_id VARCHAR(128) PRIMARY KEY,
    materials JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vtj_static_files (
    id VARCHAR(128) PRIMARY KEY,
    project_id VARCHAR(128),
    filename VARCHAR(512) NOT NULL,
    filepath VARCHAR(1024) NOT NULL,
    content_type VARCHAR(255),
    size_bytes BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vtj_static_project ON vtj_static_files(project_id);

CREATE TABLE IF NOT EXISTS vtj_templates (
    id VARCHAR(128) PRIMARY KEY,
    platform VARCHAR(32) NOT NULL DEFAULT 'web',
    category VARCHAR(128),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    cover VARCHAR(1024),
    dsl JSONB NOT NULL DEFAULT '{}'::jsonb,
    creator VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vtj_templates_platform ON vtj_templates(platform);

-- AI 代理网关：大模型配置。api_key 以密文存储（AES-GCM），禁止明文落库。
-- type 用于场景路由：MULTIMODAL(设计稿/UI) 与 CODING(逻辑/代码) 分别选取各自 enabled 的配置。
CREATE TABLE IF NOT EXISTS vtj_llm_config (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(32) NOT NULL DEFAULT 'CODING',
    provider VARCHAR(128),
    api_key TEXT,
    base_url VARCHAR(1024) NOT NULL,
    model VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vtj_llm_config_type ON vtj_llm_config(type, enabled);

-- AI 会话主题：completions 回调仅携带 tid，需据此取回原始 prompt 与场景，故 topic 必须持久化。
CREATE TABLE IF NOT EXISTS vtj_ai_topic (
    id VARCHAR(128) PRIMARY KEY,
    prompt TEXT,
    scene VARCHAR(32),
    model VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

