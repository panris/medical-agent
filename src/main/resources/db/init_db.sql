-- ============================================================
-- Medical Agent 数据库初始化脚本
-- PostgreSQL + pgvector
-- ============================================================

-- 启用扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============================================================
-- 1. 知识库表 (T1.1)
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_base (
    id              BIGSERIAL PRIMARY KEY,
    source_file     VARCHAR(512)    NOT NULL,           -- 原始文件名
    chunk_index     INT             NOT NULL DEFAULT 0, -- 分块序号
    content         TEXT            NOT NULL,           -- 分块文本内容
    content_ts      TSVECTOR        GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,  -- 全文搜索向量
    embedding       vector(1536),                        -- 向量嵌入
    metadata        JSONB           DEFAULT '{}',       -- 附加元数据 (department, category, source_type 等)
    created_at      TIMESTAMPTZ     DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     DEFAULT NOW()
);

-- 向量索引 (IVFFlat, 适合中等规模数据集)
CREATE INDEX IF NOT EXISTS idx_kb_embedding ON knowledge_base
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 全文搜索 GIN 索引
CREATE INDEX IF NOT EXISTS idx_kb_content_ts ON knowledge_base USING GIN (content_ts);

-- 元数据 GIN 索引
CREATE INDEX IF NOT EXISTS idx_kb_metadata ON knowledge_base USING GIN (metadata);

-- ============================================================
-- 2. 会话表 (T1.4)
-- ============================================================
CREATE TABLE IF NOT EXISTS sessions (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_summary JSONB          DEFAULT '{}',       -- 患者信息摘要
    messages        JSONB           DEFAULT '[]',       -- 完整对话历史
    state           JSONB           DEFAULT '{}',       -- AgentState 快照
    status          VARCHAR(20)     DEFAULT 'active',   -- active | completed | expired
    created_at      TIMESTAMPTZ     DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     DEFAULT NOW(),
    expires_at      TIMESTAMPTZ     DEFAULT (NOW() + INTERVAL '30 minutes')
);

-- 过期清理索引
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions (expires_at) WHERE status = 'active';

-- ============================================================
-- 3. 模型配置表 (T3.1)
-- ============================================================
CREATE TABLE IF NOT EXISTS model_configs (
    id              BIGSERIAL PRIMARY KEY,
    provider        VARCHAR(50)     NOT NULL,           -- openai / anthropic / evol / custom
    model_name      VARCHAR(100)    NOT NULL,           -- gpt-4o / claude-sonnet-4 等
    display_name    VARCHAR(100)    NOT NULL,           -- 显示名称
    base_url        VARCHAR(512)    DEFAULT '',         -- API Base URL
    api_key_enc     VARCHAR(512)    DEFAULT '',         -- 加密存储的 API Key
    purpose         VARCHAR(50)     DEFAULT 'chat',     -- chat / embedding / fallback
    max_tokens      INT             DEFAULT 2048,
    temperature     DECIMAL(3,2)    DEFAULT 0.20,
    is_active       BOOLEAN         DEFAULT FALSE,
    priority        INT             DEFAULT 0,           -- 优先级 (数字越小越优先)
    version         INT             DEFAULT 1,           -- 配置版本号 (热更新用)
    created_at      TIMESTAMPTZ     DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (provider, model_name)
);

-- ============================================================
-- 4. 问诊记录表 (合规审计)
-- ============================================================
CREATE TABLE IF NOT EXISTS consultation_records (
    id              BIGSERIAL PRIMARY KEY,
    session_id      UUID            REFERENCES sessions(id),
    intent          VARCHAR(50),                         -- router 分类结果
    retrieved_docs  JSONB           DEFAULT '[]',        -- 检索到的文档 ID 列表
    confidence      DECIMAL(3,2),                         -- 检索置信度
    diagnosis       JSONB           DEFAULT '{}',        -- 诊断结果
    disclaimer_shown BOOLEAN        DEFAULT FALSE,       -- 免责声明是否展示
    latency_ms      INT,                                 -- 总耗时
    created_at      TIMESTAMPTZ     DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cr_session ON consultation_records (session_id);

-- ============================================================
-- 5. 混合检索函数 (T1.3)
-- RRF 融合: final_score = 0.7 * (1 - cosine_distance) + 0.3 * ts_rank_normalized
-- ============================================================
CREATE OR REPLACE FUNCTION hybrid_search(
    query_text      TEXT,
    query_embedding vector(1536),
    top_k           INT DEFAULT 3,
    vector_weight   DECIMAL(3,2) DEFAULT 0.70,
    text_weight     DECIMAL(3,2) DEFAULT 0.30
)
RETURNS TABLE (
    id              BIGINT,
    content         TEXT,
    source_file     VARCHAR(512),
    chunk_index     INT,
    vector_score    DECIMAL(10,6),
    text_score      DECIMAL(10,6),
    final_score     DECIMAL(10,6)
)
LANGUAGE plpgsql STABLE
AS $$
DECLARE
    v_limit INT;
BEGIN
    v_limit := top_k * 3;  -- 多取一些候选，RRF 融合后再截断

    RETURN QUERY
    WITH vector_results AS (
        SELECT
            id,
            content,
            source_file,
            chunk_index,
            1 - (embedding <=> query_embedding) AS score,
            ROW_NUMBER() OVER (ORDER BY (embedding <=> query_embedding) ASC) AS rank
        FROM knowledge_base
        WHERE embedding IS NOT NULL
        ORDER BY embedding <=> query_embedding
        LIMIT v_limit
    ),
    text_results AS (
        SELECT
            id,
            content,
            source_file,
            chunk_index,
            ts_rank(content_ts, plainto_tsquery('simple', query_text)) AS score,
            ROW_NUMBER() OVER (ORDER BY ts_rank(content_ts, plainto_tsquery('simple', query_text)) DESC) AS rank
        FROM knowledge_base
        WHERE content_ts @@ plainto_tsquery('simple', query_text)
        ORDER BY ts_rank(content_ts, plainto_tsquery('simple', query_text)) DESC
        LIMIT v_limit
    ),
    merged AS (
        SELECT
            COALESCE(v.id, t.id) AS id,
            COALESCE(v.content, t.content) AS content,
            COALESCE(v.source_file, t.source_file) AS source_file,
            COALESCE(v.chunk_index, t.chunk_index) AS chunk_index,
            COALESCE(v.score, 0) AS vector_score,
            COALESCE(t.score, 0) AS text_score
        FROM vector_results v
        FULL OUTER JOIN text_results t ON v.id = t.id
    ),
    normalized AS (
        SELECT
            id, content, source_file, chunk_index,
            vector_score,
            text_score,
            CASE WHEN MAX(vector_score) OVER () > 0
                 THEN vector_score / MAX(vector_score) OVER ()
                 ELSE 0 END AS v_norm,
            CASE WHEN MAX(text_score) OVER () > 0
                 THEN text_score / MAX(text_score) OVER ()
                 ELSE 0 END AS t_norm
        FROM merged
        WHERE vector_score > 0 OR text_score > 0
    )
    SELECT
        id, content, source_file, chunk_index,
        vector_score,
        text_score,
        (vector_weight * v_norm + text_weight * t_norm)::DECIMAL(10,6) AS final_score
    FROM normalized
    ORDER BY final_score DESC
    LIMIT top_k;
END;
$$;

-- ============================================================
-- 6. 自动过期清理 (pg_cron 可选，或通过应用层定时任务)
-- ============================================================
-- CREATE EXTENSION IF NOT EXISTS pg_cron;
-- SELECT cron.schedule('clean_expired_sessions', '*/5 * * * *',
--     $$DELETE FROM sessions WHERE expires_at < NOW() AND status = 'active'$$);

-- ============================================================
-- 7. 更新时间触发器
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_kb_updated BEFORE UPDATE ON knowledge_base
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER trg_sessions_updated BEFORE UPDATE ON sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER trg_model_configs_updated BEFORE UPDATE ON model_configs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 8. 初始数据: 默认模型配置
-- ============================================================
INSERT INTO model_configs (provider, model_name, display_name, purpose, is_active, priority)
VALUES
    ('openai', 'gpt-4o-mini', 'GPT-4o Mini (默认)', 'chat', true, 1),
    ('openai', 'gpt-4o', 'GPT-4o', 'chat', false, 2),
    ('openai', 'text-embedding-3-small', 'Text Embedding 3 Small', 'embedding', true, 1)
ON CONFLICT (provider, model_name) DO NOTHING;
