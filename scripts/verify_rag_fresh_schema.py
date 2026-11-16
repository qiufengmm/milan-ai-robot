"""Verify complete enterprise initialization and rerun preservation in a new DB.

The dedicated database is kept for inspection. No existing database is overwritten.
"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import subprocess


def run(command, sql=None):
    result = subprocess.run(command, input=sql, encoding="utf-8", capture_output=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", default="milan_enterprise_schema_probe_" + datetime.now().strftime("%Y%m%d_%H%M%S"))
    parser.add_argument("--container", default="pgvector2")
    args = parser.parse_args()
    if not re.fullmatch(r"milan_enterprise_schema_probe_[a-z0-9_]+", args.database):
        raise SystemExit("Use a new milan_enterprise_schema_probe_* database.")
    root = Path(__file__).resolve().parents[1]
    present = run(["docker", "exec", args.container, "psql", "-U", "postgres", "-d", "postgres",
                   "-Atc", f"SELECT 1 FROM pg_database WHERE datname='{args.database}'"])
    if present.strip():
        raise SystemExit("Probe database already exists; refusing to overwrite it.")
    run(["docker", "exec", args.container, "createdb", "-U", "postgres", args.database])
    command = ["docker", "exec", "-i", args.container, "psql", "-X", "-U", "postgres",
               "-d", args.database, "-v", "ON_ERROR_STOP=1", "-At"]

    def sql_file(name):
        path = (root / "sql" / name).resolve()
        if not path.is_relative_to((root / "sql").resolve()):
            raise ValueError("SQL include outside sql directory")
        return re.sub(r"(?m)^\\ir\s+(\S+)\s*$", lambda m: sql_file(m.group(1)), path.read_text(encoding="utf-8"))

    schema = sql_file("schema_enterprise.sql")
    run(command, schema)
    initial = json.loads(run(command, """
SELECT json_build_object('libraries',(SELECT json_agg(name ORDER BY id) FROM t_knowledge_base),
 'users',(SELECT count(*) FROM t_user),'documents',(SELECT count(*) FROM t_knowledge_document),
 'files',(SELECT count(*) FROM t_ai_customer_service_file_storage),
 'reasoningColumn',(SELECT count(*) FROM information_schema.columns
   WHERE table_schema='public' AND table_name='t_chat_message' AND column_name='reasoning_content'));
"""))
    if initial != {"libraries":["员工服务","IT 支持"],"users":0,"documents":0,"files":0,"reasoningColumn":0}:
        raise SystemExit("Unexpected enterprise schema state: " + json.dumps(initial,ensure_ascii=False))
    run(command, """
INSERT INTO t_user(username,password_hash,display_name,role) VALUES
 ('cleanup.probe','not-a-real-login-hash','验收成员','USER');
INSERT INTO t_knowledge_base_member(user_id,knowledge_base_id,access_level)
 SELECT id,1,'EDITOR' FROM t_user WHERE username='cleanup.probe';
INSERT INTO t_ai_customer_service_file_storage
 (knowledge_base_id,file_md5,file_name,file_path,file_size,total_chunks,uploaded_chunks,status,version_no,publish_status,uploaded_by)
 SELECT 1,'00000000000000000000000000000000','probe.txt','/probe/fixture.txt',1,1,1,3,1,'PUBLISHED',id FROM t_user;
INSERT INTO t_knowledge_document(knowledge_base_id,title,current_file_id,create_by)
 SELECT 1,'验收资料',f.id,u.id FROM t_ai_customer_service_file_storage f CROSS JOIN t_user u;
UPDATE t_ai_customer_service_file_storage SET document_id=(SELECT id FROM t_knowledge_document);
UPDATE t_knowledge_base SET name='员工服务验收自定义' WHERE id=1;
""")
    fingerprint = """
SELECT md5(json_build_object(
 'libraries',(SELECT json_agg(x ORDER BY id) FROM t_knowledge_base x),
 'users',(SELECT json_agg(x ORDER BY id) FROM t_user x),
 'members',(SELECT json_agg(x ORDER BY id) FROM t_knowledge_base_member x),
 'documents',(SELECT json_agg(x ORDER BY id) FROM t_knowledge_document x),
 'files',(SELECT json_agg(x ORDER BY id) FROM t_ai_customer_service_file_storage x))::text);
"""
    before = run(command, fingerprint).strip()
    run(command, schema)
    after = run(command, fingerprint).strip()
    if before != after:
        raise SystemExit("Second initialization changed users/members/publications or administrator names.")
    print(json.dumps({"database":args.database,"freshSchema":"PASS","rerunPreservation":"PASS",
                      "fingerprint":after,"initialState":initial}, ensure_ascii=False))


if __name__ == "__main__":
    main()
