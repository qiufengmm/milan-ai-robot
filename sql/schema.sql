-- ============================================================================
--  企业内部知识库 · 基础数据表
-- ----------------------------------------------------------------------------
--  适用版本：PostgreSQL 16+ / pgvector 0.7+
--  目标库名：milan_enterprise（完整初始化入口为 schema_enterprise.sql）
--  执行方式：psql -U postgres -d milan_enterprise -f sql/schema_enterprise.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. 扩展
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";   -- t_vector_store.id 默认值需要
CREATE EXTENSION IF NOT EXISTS vector;        -- pgvector，向量检索核心
CREATE EXTENSION IF NOT EXISTS hstore;        -- Spring AI pgvector 元数据过滤需要


-- ---------------------------------------------------------------------------
-- 1. 企业知识库
CREATE TABLE IF NOT EXISTS t_knowledge_base (
    id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, description VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO t_knowledge_base(id,name,description) VALUES
    (1,'员工服务','员工政策与人事服务知识库') ON CONFLICT(id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('t_knowledge_base','id'),
    greatest((SELECT coalesce(max(id),1) FROM t_knowledge_base),
        (SELECT last_value FROM t_knowledge_base_id_seq)), true);

-- 1.1 企业知识库资料表
--    支持大文件分片上传 / 断点续传 / 秒传：
--      file_md5        文件身份标识，唯一索引，秒传去重靠它
--      total_chunks    总分片数
--      uploaded_chunks 已上传分片数
--      status          0-上传中 1-待处理 2-向量化中 3-已完成 4-失败
--    remark 保持 VARCHAR(500)：与后端 @Size(max = 500)、前端 :maxlength="500" 三处对齐
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_ai_customer_service_file_storage
(
    id              BIGSERIAL     NOT NULL,
    knowledge_base_id BIGINT      NOT NULL DEFAULT 1,
    file_name       VARCHAR(160)  NOT NULL,               -- 上传时的原始文件名
    file_md5        VARCHAR(32)   NOT NULL,               -- 文件 MD5，用于秒传和去重
    file_path       VARCHAR(500)  NOT NULL,               -- 合并后的本地绝对存储路径（上传中为空串）
    file_size       BIGINT        NOT NULL,               -- 原始文件字节数
    total_chunks    INTEGER       NOT NULL,               -- 总分片数
    uploaded_chunks INTEGER       NOT NULL DEFAULT 0,     -- 已上传分片数
    status          SMALLINT      DEFAULT 0,              -- 0=上传中 1=待处理 2=向量化中 3=已完成 4=失败
    remark          VARCHAR(500),                         -- 备注
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT t_ai_customer_service_file_storage_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS t_ai_customer_service_file_storage_pkey
    ON t_ai_customer_service_file_storage (id);
-- 知识库内文件 MD5 唯一索引：秒传去重的依据，同时也是「同一文件不重复入库」的保障
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_kb_md5
    ON t_ai_customer_service_file_storage (knowledge_base_id, file_md5);
CREATE INDEX IF NOT EXISTS idx_file_storage_file_name
    ON t_ai_customer_service_file_storage (file_name);
CREATE INDEX IF NOT EXISTS idx_file_storage_status
    ON t_ai_customer_service_file_storage (status);
CREATE INDEX IF NOT EXISTS idx_file_storage_create_time
    ON t_ai_customer_service_file_storage (create_time);


-- ---------------------------------------------------------------------------
-- 1.1 企业知识库文件分片表
--     记录哪些分片已经上传成功，断点续传时把这些序号返回给前端跳过，
--     合并完成后连同磁盘上的分片目录一起清理掉。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_file_chunk_info
(
    id           BIGSERIAL     NOT NULL,
    knowledge_base_id BIGINT       NOT NULL DEFAULT 1,
    file_md5     VARCHAR(32)   NOT NULL,                  -- 所属原始文件的 MD5
    chunk_number INTEGER       NOT NULL,                  -- 分片序号，从 0 开始
    chunk_path   VARCHAR(500)  NOT NULL,                  -- 分片文件落盘路径
    chunk_size   BIGINT        NOT NULL,                  -- 分片字节数
    create_time  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT t_file_chunk_info_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS t_file_chunk_info_pkey ON t_file_chunk_info (id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_chunk_kb_md5_number ON t_file_chunk_info (knowledge_base_id, file_md5, chunk_number);

CREATE TABLE IF NOT EXISTS t_rag_index_task (
    file_id BIGINT PRIMARY KEY, knowledge_base_id BIGINT NOT NULL,
    operation VARCHAR(8) NOT NULL CHECK(operation IN ('UPSERT','DELETE')),
    attempts INTEGER NOT NULL DEFAULT 0, last_error VARCHAR(300),
    next_attempt_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_rag_index_task_due ON t_rag_index_task(next_attempt_time);


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
    CONSTRAINT t_chat_message_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS t_chat_message_pkey ON t_chat_message (id);
CREATE INDEX IF NOT EXISTS idx_chat_message_uuid ON t_chat_message (chat_uuid);
CREATE INDEX IF NOT EXISTS idx_chat_message_create_time ON t_chat_message (create_time);


-- ---------------------------------------------------------------------------
-- 4. 向量表（RAG 检索用）
--    说明：Spring AI 的 PgVectorStore 在 initialize-schema 开启时会自动建表。
--         若未自动创建，可执行下面的 SQL 手动建。
--    注意：embedding 维度必须与 application.yml 中的
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
