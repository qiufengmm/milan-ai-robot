-- ============================================================================
--  米兰AI 机器人 · 数据库初始化脚本
-- ----------------------------------------------------------------------------
--  适用版本：PostgreSQL 16+ / pgvector 0.7+
--  目标库名：robot（可在 application-dev.yml 的 datasource.url 中修改）
--  执行方式：psql -U postgres -d robot -f sql/schema.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. 扩展
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";   -- t_vector_store.id 默认值需要
CREATE EXTENSION IF NOT EXISTS vector;        -- pgvector，向量检索核心
CREATE EXTENSION IF NOT EXISTS hstore;        -- Spring AI pgvector 元数据过滤需要


-- ---------------------------------------------------------------------------
-- 1. 智能客服 · Markdown 知识库文件表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_ai_customer_service_md_storage
(
    id                 BIGSERIAL     NOT NULL,
    original_file_name VARCHAR(160)  NOT NULL,               -- 上传时的原始文件名
    new_file_name      VARCHAR(160)  NOT NULL,               -- 落盘用的新文件名（UUID，防重名/防路径穿越）
    file_path          VARCHAR(500)  NOT NULL,               -- 本地绝对存储路径
    file_size          BIGINT        NOT NULL,               -- 文件字节数
    status             SMALLINT      DEFAULT 0,              -- 0=待处理 1=向量化中 2=已完成 3=失败
    remark             VARCHAR(200),                         -- 备注
    create_time        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT t_ai_customer_service_md_storage_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS t_ai_customer_service_md_storage_pkey
    ON t_ai_customer_service_md_storage (id);
CREATE INDEX IF NOT EXISTS idx_t_ai_customer_service_md_storage_original_file_name
    ON t_ai_customer_service_md_storage (original_file_name);
CREATE INDEX IF NOT EXISTS idx_t_ai_customer_service_md_storage_status
    ON t_ai_customer_service_md_storage (status);
CREATE INDEX IF NOT EXISTS idx_t_ai_customer_service_md_storage_created_time
    ON t_ai_customer_service_md_storage (create_time);


-- ---------------------------------------------------------------------------
-- 2. 智能对话 · 会话表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_chat
(
    id          BIGSERIAL   NOT NULL,
    uuid        VARCHAR(60) NOT NULL,                        -- 会话唯一标识，前端持有
    summary     VARCHAR(60),                                 -- 会话标题（首轮消息摘要）
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT t_chat_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS t_chat_pkey ON t_chat (id);
CREATE UNIQUE INDEX IF NOT EXISTS t_chat_uuid_key ON t_chat (uuid);
CREATE INDEX IF NOT EXISTS idx_chat_update_time ON t_chat (update_time);


-- ---------------------------------------------------------------------------
-- 3. 智能对话 · 消息表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_chat_message
(
    id                BIGSERIAL   NOT NULL,
    chat_uuid         VARCHAR(60) NOT NULL,                  -- 关联 t_chat.uuid
    content           TEXT        NOT NULL,                  -- 消息正文
    role              VARCHAR(12),                           -- user / assistant / system
    create_time       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reasoning_content TEXT,                                  -- 模型思考过程（推理内容透出）
    CONSTRAINT t_chat_message_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS t_chat_message_pkey ON t_chat_message (id);
CREATE INDEX IF NOT EXISTS idx_chat_message_uuid ON t_chat_message (chat_uuid);
CREATE INDEX IF NOT EXISTS idx_chat_message_create_time ON t_chat_message (create_time);


-- ---------------------------------------------------------------------------
-- 4. 向量表（RAG 检索用）
--    说明：Spring AI 的 PgVectorStore 在 initialize-schema 开启时会自动建表。
--         若未自动创建，可执行下面的 SQL 手动建。
--    注意：embedding 维度必须与 application-dev.yml 中的
--         spring.ai.vectorstore.pgvector.dimensions 完全一致（本项目为 1024）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_vector_store
(
    id        UUID      NOT NULL DEFAULT uuid_generate_v4(),
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1024),
    CONSTRAINT t_vector_store_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS t_vector_store_pkey ON t_vector_store (id);
-- HNSW + 余弦距离，与 distance-type: COSINE_DISTANCE 对应
CREATE INDEX IF NOT EXISTS t_vector_store_embedding_idx
    ON t_vector_store USING hnsw (embedding vector_cosine_ops);
