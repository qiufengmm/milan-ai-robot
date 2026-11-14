-- Chapter 14 upgrade. Back up first; execute with psql -v ON_ERROR_STOP=1.
-- Status conversion occurs ONLY when the legacy table is renamed.
BEGIN;
DO $$
DECLARE legacy regclass := to_regclass(format('%I.t_ai_customer_service_md_storage', current_schema()));
        current_table regclass := to_regclass(format('%I.t_ai_customer_service_file_storage', current_schema()));
BEGIN
    IF legacy IS NOT NULL AND current_table IS NOT NULL THEN
        RAISE EXCEPTION 'Both legacy and current file tables exist. Inspect before migration.';
    ELSIF legacy IS NOT NULL THEN
        ALTER TABLE t_ai_customer_service_md_storage RENAME TO t_ai_customer_service_file_storage;
        UPDATE t_ai_customer_service_file_storage SET status = status + 1 WHERE status BETWEEN 0 AND 3;
    ELSIF current_table IS NULL THEN
        RAISE EXCEPTION 'No file table found; initialize a new database with schema.sql.';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 't_ai_customer_service_file_storage'
                 AND column_name = 'original_file_name') THEN
        ALTER TABLE t_ai_customer_service_file_storage RENAME COLUMN original_file_name TO file_name;
    END IF;
END $$;
ALTER TABLE t_ai_customer_service_file_storage ADD COLUMN IF NOT EXISTS file_md5 VARCHAR(32);
ALTER TABLE t_ai_customer_service_file_storage ADD COLUMN IF NOT EXISTS total_chunks INTEGER;
ALTER TABLE t_ai_customer_service_file_storage ADD COLUMN IF NOT EXISTS uploaded_chunks INTEGER NOT NULL DEFAULT 0;
-- Identity placeholders are NOT content checksums; retain historical uploads separately.
UPDATE t_ai_customer_service_file_storage
SET file_md5 = md5('legacy:' || id::text || ':' || coalesce(file_path,'')),
    total_chunks = coalesce(total_chunks, 1), uploaded_chunks = 1
WHERE file_md5 IS NULL;
UPDATE t_ai_customer_service_file_storage SET total_chunks = 1 WHERE total_chunks IS NULL;
ALTER TABLE t_ai_customer_service_file_storage ALTER COLUMN file_md5 SET NOT NULL;
ALTER TABLE t_ai_customer_service_file_storage ALTER COLUMN total_chunks SET NOT NULL;
-- Preserve the unused legacy new_file_name column for reversibility.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=current_schema() AND table_name='t_ai_customer_service_file_storage'
                     AND column_name='knowledge_base_id') THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uk_file_md5 ON t_ai_customer_service_file_storage(file_md5);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_file_storage_file_name ON t_ai_customer_service_file_storage(file_name);
CREATE INDEX IF NOT EXISTS idx_file_storage_status ON t_ai_customer_service_file_storage(status);
CREATE INDEX IF NOT EXISTS idx_file_storage_create_time ON t_ai_customer_service_file_storage(create_time);
CREATE TABLE IF NOT EXISTS t_file_chunk_info (
    id BIGSERIAL PRIMARY KEY, file_md5 VARCHAR(32) NOT NULL, chunk_number INTEGER NOT NULL,
    chunk_path VARCHAR(500) NOT NULL, chunk_size BIGINT NOT NULL, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_file_chunk_info_file_md5 ON t_file_chunk_info(file_md5);
COMMIT;
