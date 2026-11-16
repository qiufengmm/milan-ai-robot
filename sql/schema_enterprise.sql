-- Run with psql from the sql directory (or use: psql -d milan_enterprise -f sql/schema_enterprise.sql).
-- Reuses the existing application tables, then adds only the employee knowledge workflow schema.
\ir schema.sql

INSERT INTO t_knowledge_base(id, name, description) VALUES
    (2, 'IT 支持', '企业内部 IT 支持与故障处理知识库')
ON CONFLICT (id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('t_knowledge_base', 'id'),
    greatest((SELECT coalesce(max(id), 1) FROM t_knowledge_base),
        (SELECT last_value FROM t_knowledge_base_id_seq)), true);

CREATE TABLE IF NOT EXISTS t_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_knowledge_base_member (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES t_user(id),
    knowledge_base_id BIGINT NOT NULL REFERENCES t_knowledge_base(id),
    access_level VARCHAR(16) NOT NULL CHECK (access_level IN ('READER', 'EDITOR')),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, knowledge_base_id)
);
CREATE INDEX IF NOT EXISTS idx_kb_member_knowledge_base ON t_knowledge_base_member(knowledge_base_id);

CREATE TABLE IF NOT EXISTS t_knowledge_document (
    id BIGSERIAL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL REFERENCES t_knowledge_base(id),
    title VARCHAR(200) NOT NULL,
    current_file_id BIGINT,
    create_by BIGINT NOT NULL REFERENCES t_user(id),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_kb ON t_knowledge_document(knowledge_base_id);

CREATE TABLE IF NOT EXISTS t_answer_feedback (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES t_chat_message(id),
    user_id BIGINT NOT NULL REFERENCES t_user(id),
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    resolved_by BIGINT REFERENCES t_user(id),
    resolution_note VARCHAR(2000),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (message_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_answer_feedback_status ON t_answer_feedback(status, create_time);

ALTER TABLE t_chat ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE t_chat ADD COLUMN IF NOT EXISTS knowledge_base_id BIGINT;
ALTER TABLE t_chat_message ADD COLUMN IF NOT EXISTS request_id VARCHAR(60);
ALTER TABLE t_chat_message ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE t_chat_message ADD COLUMN IF NOT EXISTS retrieval_query TEXT;
ALTER TABLE t_chat_message ADD COLUMN IF NOT EXISTS sources_json TEXT;
ALTER TABLE t_chat_message ADD COLUMN IF NOT EXISTS error_code VARCHAR(80);
ALTER TABLE t_ai_customer_service_file_storage ADD COLUMN IF NOT EXISTS document_id BIGINT;
ALTER TABLE t_ai_customer_service_file_storage ADD COLUMN IF NOT EXISTS version_no INTEGER;
ALTER TABLE t_ai_customer_service_file_storage ADD COLUMN IF NOT EXISTS uploaded_by BIGINT;
ALTER TABLE t_ai_customer_service_file_storage ADD COLUMN IF NOT EXISTS publish_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT';

CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_request_role
    ON t_chat_message(chat_uuid, request_id, role) WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_one_generating_message
    ON t_chat_message(chat_uuid) WHERE role = 'assistant' AND status = 'GENERATING';
CREATE INDEX IF NOT EXISTS idx_chat_owner_update ON t_chat(user_id, update_time DESC);
CREATE INDEX IF NOT EXISTS idx_file_current_publication ON t_ai_customer_service_file_storage(knowledge_base_id, publish_status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_document_version ON t_ai_customer_service_file_storage(document_id, version_no)
    WHERE document_id IS NOT NULL AND version_no IS NOT NULL;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_chat_user') THEN
        ALTER TABLE t_chat ADD CONSTRAINT fk_chat_user FOREIGN KEY (user_id) REFERENCES t_user(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_chat_knowledge_base') THEN
        ALTER TABLE t_chat ADD CONSTRAINT fk_chat_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES t_knowledge_base(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_document_current_file') THEN
        ALTER TABLE t_knowledge_document ADD CONSTRAINT fk_document_current_file
            FOREIGN KEY (current_file_id) REFERENCES t_ai_customer_service_file_storage(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_file_document') THEN
        ALTER TABLE t_ai_customer_service_file_storage ADD CONSTRAINT fk_file_document
            FOREIGN KEY (document_id) REFERENCES t_knowledge_document(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_file_uploaded_by') THEN
        ALTER TABLE t_ai_customer_service_file_storage ADD CONSTRAINT fk_file_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES t_user(id);
    END IF;
END $$;
