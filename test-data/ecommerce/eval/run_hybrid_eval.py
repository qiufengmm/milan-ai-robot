"""Capture full hybrid-retrieval evidence and SSE answers without resampling successes."""
import argparse
import json
import re
import time
import urllib.error
import ssl
import urllib.request
from pathlib import Path

EVIDENCE = "RAG_EVIDENCE "
TRANSIENT_NAMES = {
    "TimeoutError", "ConnectionError", "ConnectionResetError", "SSLError",
    "RemoteDisconnected", "IncompleteRead", "BrokenPipeError", "ContentTooShortError",
}


class SseProtocolError(ValueError):
    pass


def safe_error_type(error):
    """Unwrap urllib transport wrappers while keeping exception text/secrets out of output."""
    reason = getattr(error, "reason", None)
    if reason is not None:
        return type(reason).__name__
    return type(error).__name__


def parse_sse(raw):
    parts = []
    for block in re.split(r"\r?\n\r?\n", raw.decode("utf-8", "replace")):
        event = ""
        data = []
        for line in block.splitlines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].lstrip())
        if event == "error":
            raise SseProtocolError("SSE event reported an error")
        value = "".join(data)
        if value == "[DONE]":
            continue
        if value:
            try:
                payload = json.loads(value)
            except json.JSONDecodeError:
                payload = None
            if isinstance(payload, dict) and payload.get("error") is not None:
                raise SseProtocolError("SSE payload reported an error")
            parts.append(value)
    return "".join(parts)


def evidence_since(log_path, offset, query, kb):
    try:
        with open(log_path, "rb") as f:
            f.seek(offset)
            text = f.read().decode("utf-8", "replace")
    except FileNotFoundError:
        return None
    found = []
    for line in text.splitlines():
        pos = line.find(EVIDENCE)
        if pos < 0:
            continue
        try:
            item = json.loads(line[pos + len(EVIDENCE):])
            if item.get("query") == query and item.get("knowledgeBaseId") == kb:
                found.append(item)
        except json.JSONDecodeError:
            continue
    return found[-1] if found else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--endpoint", default="http://127.0.0.1:8080/customer-service/chat/completion")
    project_root = Path(__file__).resolve().parents[3]
    ap.add_argument("--log", default=str(project_root / "milan-ai-robot-springboot/logs/application.log"))
    ap.add_argument("--questions", default=str(Path(__file__).with_name("eval_questions.json")))
    ap.add_argument("--out", required=True)
    ap.add_argument("--kb", type=int, default=1)
    ap.add_argument("--max-retries", type=int, default=2)
    ap.add_argument("--pause", type=float, default=0.5)
    ap.add_argument("--mode", choices=("hybrid", "vector-baseline"), default="hybrid")
    args = ap.parse_args()
    out = Path(args.out)
    if out.exists():
        raise SystemExit(f"Refusing to overwrite existing result: {out}")
    questions = json.loads(Path(args.questions).read_text(encoding="utf-8"))
    log = Path(args.log)
    results = []
    baseline = args.mode == "vector-baseline"
    config = {"retrievalMode": args.mode,
              "chatModel": "qwen3.8-flash", "temperature": 0.0,
              "embeddingModel": "qwen3.7-text-embedding-flash", "embeddingDimensions": 1024,
              "vectorTopK": 3 if baseline else 10,
              "keywordEnabled": not baseline, "keywordTopK": 10,
              "rrfEnabled": not baseline, "rrfK": 60,
              "fusedTopK": 3 if baseline else 10,
              "rerankEnabled": not baseline,
              "rerankModel": None if baseline else "qwen3-rerank",
              "finalTopN": 3, "knowledgeBaseId": args.kb}
    for item in questions:
        attempts = []
        answer = ""
        for number in range(args.max_retries + 1):
            offset = log.stat().st_size if log.exists() else 0
            started = time.time()
            error = None
            status_code = None
            try:
                payload = json.dumps({"knowledgeBaseId": args.kb, "message": item["q"]},
                                     ensure_ascii=False).encode()
                req = urllib.request.Request(args.endpoint, data=payload,
                    headers={"Content-Type": "application/json; charset=utf-8"})
                with urllib.request.urlopen(req, timeout=180) as response:
                    raw = response.read()
                answer = parse_sse(raw)
            except urllib.error.HTTPError as exc:
                status_code = exc.code
                error = "HTTPError"
            except (TimeoutError, ConnectionError, urllib.error.URLError) as exc:
                error = safe_error_type(exc)
            except Exception as exc:
                error = safe_error_type(exc)
            duration = round(time.time() - started, 2)
            ev = evidence_since(log, offset, item["q"], args.kb)
            attempts.append({"number": number + 1, "answer": answer, "elapsedSeconds": duration,
                             "errorType": error, "httpStatus": status_code, "evidence": ev})
            # Retry only recognized transport failures and only when no text arrived.
            transient = error in TRANSIENT_NAMES
            if answer.strip() or not transient or number == args.max_retries:
                break
            time.sleep(2)
        result = {**item, "knowledgeBaseId": args.kb, "answer": answer,
                  "attempts": attempts, "evidenceMissing": not bool(attempts[-1]["evidence"]),
                  "expectedConfiguration": config}
        results.append(result)
        out.parent.mkdir(parents=True, exist_ok=True)
        temporary = out.with_name(out.name + ".partial")
        temporary.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(out)
        print(f"{item['id']}: chars={len(answer)} attempts={len(attempts)} evidence={not result['evidenceMissing']}", flush=True)
        time.sleep(args.pause)
    print(f"Saved {len(results)} rows to {out}")


if __name__ == "__main__":
    main()
