"""Run migration assertions in a new isolated schema of a dedicated test DB.

Requires an existing test database; retains the tiny schema for inspection.
Never runs against milan_ecommerce/robot or removes files/tables.
"""
import argparse
from pathlib import Path
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", default="milan_migration_probe_20261114")
    parser.add_argument("--container", default="pgvector2")
    args = parser.parse_args()
    if args.database in {"robot", "milan_ecommerce", "postgres"}:
        raise SystemExit("Use a dedicated migration test database.")
    root = Path(__file__).resolve().parents[1]
    schema = "probe_" + uuid.uuid4().hex[:12]
    fixture = f"""
CREATE SCHEMA {schema};
SET search_path={schema},public;
CREATE TABLE t_ai_customer_service_md_storage (
id BIGSERIAL PRIMARY KEY,original_file_name VARCHAR(160) NOT NULL,new_file_name VARCHAR(160),
file_path VARCHAR(500),file_size BIGINT,status SMALLINT,remark VARCHAR(500),
create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
INSERT INTO t_ai_customer_service_md_storage(original_file_name,file_path,file_size,status)
VALUES ('ready.md','/preserved/ready.md',100,2),('pending.md','/preserved/pending.md',80,0);
CREATE TABLE t_vector_store(id UUID PRIMARY KEY,content TEXT,metadata JSON);
INSERT INTO t_vector_store VALUES('11111111-1111-1111-1111-111111111111','unchanged', '{{"mdStorageId":1}}');
"""
    chunk = (root / "sql/migrate_chunk_upload.sql").read_text(encoding="utf-8")
    rag = (root / "sql/migrate_rag_knowledge_bases.sql").read_text(encoding="utf-8")
    progress = """
INSERT INTO t_ai_customer_service_file_storage(file_name,file_md5,file_path,file_size,total_chunks,uploaded_chunks,status)
VALUES ('upload.txt','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','',20,2,0,0);
"""
    assertions = f"""
DO $$ BEGIN
IF (SELECT status FROM t_ai_customer_service_file_storage WHERE id=1)<>3 THEN RAISE EXCEPTION 'completed status changed'; END IF;
IF (SELECT status FROM t_ai_customer_service_file_storage WHERE id=2)<>1 THEN RAISE EXCEPTION 'pending status changed'; END IF;
IF (SELECT uploaded_chunks FROM t_ai_customer_service_file_storage WHERE id=3)<>0 THEN RAISE EXCEPTION 'in-progress upload reset'; END IF;
IF (SELECT file_path FROM t_ai_customer_service_file_storage WHERE id=1)<>'/preserved/ready.md' THEN RAISE EXCEPTION 'path changed'; END IF;
IF (SELECT content FROM t_vector_store LIMIT 1)<>'unchanged' THEN RAISE EXCEPTION 'vector content changed'; END IF;
IF (SELECT metadata->>'knowledge_base_id' FROM t_vector_store LIMIT 1)<>'1' THEN RAISE EXCEPTION 'ownership missing'; END IF;
IF (SELECT count(*) FROM t_rag_index_task)<>1 THEN RAISE EXCEPTION 'outbox seed mismatch'; END IF;
IF EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname='{schema}' AND indexname='uk_file_md5') THEN RAISE EXCEPTION 'global uniqueness recreated'; END IF;
END $$;
-- Same content in a different library is allowed; same library is unique.
INSERT INTO t_knowledge_base(name) VALUES ('Independent');
INSERT INTO t_ai_customer_service_file_storage(knowledge_base_id,file_name,file_md5,file_path,file_size,total_chunks)
SELECT 2,file_name,file_md5,'',file_size,total_chunks FROM t_ai_customer_service_file_storage WHERE id=3;
DO $$ BEGIN
BEGIN
INSERT INTO t_ai_customer_service_file_storage(knowledge_base_id,file_name,file_md5,file_path,file_size,total_chunks)
SELECT 1,file_name,file_md5,'',file_size,total_chunks FROM t_ai_customer_service_file_storage WHERE id=3;
RAISE EXCEPTION 'same-library duplicate accepted';
EXCEPTION WHEN unique_violation THEN NULL;
END;
END $$;
SELECT 'migration assertions passed';
"""
    script = fixture + chunk + progress + chunk + rag + chunk + rag + assertions
    result = subprocess.run(
        ["docker", "exec", "-i", args.container, "psql", "-U", "postgres", "-d", args.database, "-v", "ON_ERROR_STOP=1"],
        input=script, encoding="utf-8", capture_output=True,
    )
    out = root / "milan-ai-robot-springboot/target/migration-fixed.log"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(result.stdout + result.stderr, encoding="utf-8")
    print(f"schema={schema}, exit={result.returncode}, log={out}")
    print((result.stdout if result.returncode == 0 else result.stderr)[-1600:])
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
