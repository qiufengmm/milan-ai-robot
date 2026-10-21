"""米兰数码商城智能客服 · 评测执行脚本

对已启动的后端（默认 http://localhost:8080）逐条发送评测问题，
记录 SSE 回答原文 + 检索块的常见问法预览（从应用日志中的增强提示词解析）。

用法：
    python run_eval.py --out results.json --log /tmp/app-run.log
"""

import argparse
import json
import re
import time
import urllib.request
from pathlib import Path

ENDPOINT = "http://localhost:8080/customer-service/chat/completion"

TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}")
MARKER = "## 重新构建的增强提示词: "


def ask(question: str, timeout: int = 180) -> str:
    payload = json.dumps({"message": question}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        ENDPOINT,
        data=payload,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    chunks = []
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            if line.startswith("data:"):
                chunks.append(line[5:])
    return "".join(chunks)


def read_log_since(path: Path, offset: int):
    if not path.exists():
        return "", offset
    with path.open("r", encoding="utf-8", errors="replace") as f:
        f.seek(offset)
        text = f.read()
        return text, f.tell()


def parse_retrieved(log_text: str):
    """从日志片段中取最后一次增强提示词里的上下文块标识。"""
    idx = log_text.rfind(MARKER)
    if idx < 0:
        return []
    tail = log_text[idx + len(MARKER):]
    # 截断到下一个日志时间戳行
    lines = []
    for line in tail.splitlines():
        if TS_RE.match(line):
            break
        lines.append(line)
    body = "\n".join(lines)

    # 上下文里每个知识块以 “常见问法：” 开头，取该行前 70 字作为块标识
    blocks = []
    for line in body.splitlines():
        s = line.strip()
        if s.startswith("常见问法："):
            blocks.append(s.split("答案：")[0][:70])
    return blocks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--questions", default=str(Path(__file__).with_name("eval_questions.json")))
    ap.add_argument("--out", default=str(Path(__file__).with_name("results.json")))
    ap.add_argument("--log", default="/tmp/app-run.log")
    ap.add_argument("--pause", type=float, default=1.0)
    args = ap.parse_args()

    questions = json.loads(Path(args.questions).read_text(encoding="utf-8"))
    log_path = Path(args.log)

    results = []
    for i, item in enumerate(questions, 1):
        offset = log_path.stat().st_size if log_path.exists() else 0
        started = time.time()
        answer, err, attempts = "", None, 0
        # 演示环境可能出现 TLS 中断、后端或数据库暂不可用。
        # 只对「传输/服务异常且没有拿到回答」的情况重试，已返回的回答不做重试，
        # 避免受重试影响而对结果做选择性采样。
        for attempts in range(1, 4):
            try:
                answer = ask(item["q"])
                err = None
            except Exception as exc:
                answer, err = "", f"{type(exc).__name__}: {exc}"
            if answer.strip():
                break
            time.sleep(10)
        cost = round(time.time() - started, 2)

        time.sleep(0.3)
        log_text, _ = read_log_since(log_path, offset)
        retrieved = parse_retrieved(log_text)

        results.append({
            **item,
            "answer": answer,
            "retrieved": retrieved,
            "elapsed_s": cost,
            "attempts": attempts,
            "error": err,
        })
        print(f"[{i}/{len(questions)}] {item['id']} {item['q']} -> {len(answer)} 字, "
              f"{cost}s, 检索 {len(retrieved)} 块, 尝试 {attempts} 次", flush=True)
        time.sleep(args.pause)

    Path(args.out).write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n已写入 {args.out}")


if __name__ == "__main__":
    main()
