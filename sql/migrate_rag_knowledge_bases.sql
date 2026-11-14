-- Chapter 15 upgrade. Run migrate_chunk_upload.sql first. Safe to rerun.
-- Reuses embeddings; unowned vectors are retained but never served.
BEGIN;
CREATE TABLE IF NOT EXISTS t_knowledge_base (
    id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, description VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO t_knowledge_base(id,name,description) VALUES
    (1,'米兰数码商城','虚构电商演示知识库：商品咨询、配送、退换货及保修规则') ON CONFLICT(id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('t_knowledge_base','id'),
    greatest((SELECT coalesce(max(id),1) FROM t_knowledge_base),
        (SELECT last_value FROM t_knowledge_base_id_seq)), true);
ALTER TABLE t_ai_customer_service_file_storage ADD COLUMN IF NOT EXISTS knowledge_base_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE t_file_chunk_info ADD COLUMN IF NOT EXISTS knowledge_base_id BIGINT NOT NULL DEFAULT 1;
DROP INDEX IF EXISTS uk_file_md5;
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_kb_md5 ON t_ai_customer_service_file_storage(knowledge_base_id,file_md5);
-- Fail visibly on old duplicate rows; do not silently discard stored chunks.
CREATE UNIQUE INDEX IF NOT EXISTS uk_chunk_kb_md5_number ON t_file_chunk_info(knowledge_base_id,file_md5,chunk_number);
CREATE INDEX IF NOT EXISTS idx_file_kb_status ON t_ai_customer_service_file_storage(knowledge_base_id,status);
CREATE TABLE IF NOT EXISTS t_rag_index_task (
    file_id BIGINT PRIMARY KEY, knowledge_base_id BIGINT NOT NULL,
    operation VARCHAR(8) NOT NULL CHECK(operation IN ('UPSERT','DELETE')),
    attempts INTEGER NOT NULL DEFAULT 0, last_error VARCHAR(300),
    next_attempt_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- No file FK: DELETE tasks must survive removal of their owning file.
CREATE INDEX IF NOT EXISTS idx_rag_index_task_due ON t_rag_index_task(next_attempt_time);
UPDATE t_vector_store v
SET metadata=jsonb_set(coalesce(v.metadata::jsonb,'{}'::jsonb),'{knowledge_base_id}',to_jsonb(f.knowledge_base_id::text))::json
FROM t_ai_customer_service_file_storage f
WHERE v.metadata->>'mdStorageId'=f.id::text
  AND (v.metadata->>'knowledge_base_id') IS DISTINCT FROM f.knowledge_base_id::text;
INSERT INTO t_rag_index_task(file_id,knowledge_base_id,operation)
SELECT id,knowledge_base_id,'UPSERT' FROM t_ai_customer_service_file_storage WHERE status=3
ON CONFLICT(file_id) DO NOTHING;
COMMIT;
