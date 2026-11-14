"""Initialize a one-off PostgreSQL database from sql/schema.sql and validate it.

The database is kept for inspection and reruns refuse to overwrite it.
"""
from pathlib import Path
import subprocess


def run(command, sql=None):
    result = subprocess.run(command, input=sql, text=True, encoding="utf-8", capture_output=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    return result.stdout


def main():
    root = Path(__file__).resolve().parents[1]
    database = "milan_fresh_schema_probe_20261114"
    present = run(["docker", "exec", "pgvector2", "psql", "-U", "postgres", "-d", "postgres",
                   "-Atc", f"SELECT 1 FROM pg_database WHERE datname='{database}'"])
    if present.strip():
        raise SystemExit(f"{database} already exists; refusing to recreate it.")
    run(["docker", "exec", "pgvector2", "createdb", "-U", "postgres", database])
    schema = (root / "sql/schema.sql").read_text(encoding="utf-8")
    run(["docker", "exec", "-i", "pgvector2", "psql", "-U", "postgres", "-d", database, "-v", "ON_ERROR_STOP=1"], schema)
    check = run(["docker", "exec", "pgvector2", "psql", "-U", "postgres", "-d", database, "-Atc",
                 "SELECT (SELECT name FROM t_knowledge_base WHERE id=1), "
                 "(SELECT count(*) FROM t_ai_customer_service_file_storage), "
                 "(SELECT count(*) FROM t_rag_index_task);"])
    if check.strip() != "米兰数码商城|0|0":
        raise SystemExit(f"Unexpected fresh schema state: {check}")
    print(f"Fresh schema verified in {database}: {check.strip()}")


if __name__ == "__main__":
    main()
