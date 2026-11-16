#!/usr/bin/env python3
"""Run enterprise evaluation cases against a deliberately configured enterprise app.

This script spends model quota. It is provided for a later, explicitly approved run;
it is not invoked as part of source implementation or build verification.
"""
import argparse
import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.cookiejar import CookieJar
from pathlib import Path

HERE = Path(__file__).resolve().parent
TLS_FAILURE = re.compile(r"(?:ssl|tls).*(?:handshake|eof|unexpected eof)|(?:handshake|eof).*(?:ssl|tls)", re.I)


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.cookies = CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None

    def raw(self, method, path, body=None, content_type="application/json", accept="application/json"):
        headers = {"Accept": accept}
        if body is not None:
            headers["Content-Type"] = content_type
        if method.upper() not in ("GET", "HEAD", "OPTIONS"):
            self.refresh_csrf()
            headers[self.csrf["headerName"]] = self.csrf["token"]
        request = urllib.request.Request(self.base + path, data=body, headers=headers, method=method.upper())
        try:
            with self.opener.open(request, timeout=240) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def refresh_csrf(self):
        status, raw = self.raw_no_csrf("GET", "/auth/csrf")
        if status != 200:
            raise RuntimeError(f"无法获取CSRF token: HTTP {status}")
        self.csrf = json.loads(raw)["data"]

    def raw_no_csrf(self, method, path, body=None):
        request = urllib.request.Request(self.base + path, data=body,
            headers={"Accept": "application/json", **({"Content-Type": "application/json"} if body else {})},
            method=method.upper())
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def json(self, method, path, value=None):
        body = json.dumps(value, ensure_ascii=False).encode("utf-8") if value is not None else None
        status, raw = self.raw(method, path, body)
        if status < 200 or status >= 300:
            raise RuntimeError(f"HTTP {status} {path}: {raw.decode('utf-8', errors='replace')[:500]}")
        response = json.loads(raw) if raw else None
        if response and response.get("success") is False:
            raise RuntimeError(response.get("message", "API业务失败"))
        return response.get("data") if response else None

    def login(self, actor):
        username = os.environ.get(f"ENTERPRISE_{actor.upper()}_USERNAME", {
            "hr": "demo.hr", "it": "demo.it", "employee": "demo.employee"
        }.get(actor, ""))
        password = os.environ.get(f"ENTERPRISE_{actor.upper()}_PASSWORD")
        if not username or not password:
            raise RuntimeError(f"缺少 {actor} 登录配置：ENTERPRISE_{actor.upper()}_USERNAME/PASSWORD")
        self.refresh_csrf()
        body = json.dumps({"username": username, "password": password}).encode("utf-8")
        status, raw = self.raw("POST", "/auth/login", body)
        if status != 200:
            raise RuntimeError(f"{actor}登录失败: HTTP {status}")
        self.refresh_csrf()

    def send_sse(self, chat_id, request_id, question):
        body = json.dumps({"chatId": chat_id, "requestId": request_id, "message": question}, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
        self.refresh_csrf()
        headers[self.csrf["headerName"]] = self.csrf["token"]
        request = urllib.request.Request(self.base + "/customer-service/chat/completion", data=body,
                                         headers=headers, method="POST")
        started = time.monotonic()
        answer, sources, events, answer_id, error_payload, cancelled = [], [], [], None, None, False
        try:
            with self.opener.open(request, timeout=240) as response:
                if response.status != 200 or "text/event-stream" not in response.headers.get("Content-Type", ""):
                    raise RuntimeError(f"流协议错误：HTTP {response.status}")
                event_name = "message"
                data_lines = []
                for raw_line in response:
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                    if line.startswith("event:"):
                        event_name = line[6:].strip()
                    elif line.startswith("data:"):
                        data_lines.append(line[5:].lstrip())
                    elif not line and data_lines:
                        payload_text = "\n".join(data_lines)
                        payload = json.loads(payload_text)
                        events.append(event_name)
                        if event_name == "meta":
                            answer_id = payload.get("answerId")
                        elif event_name == "sources":
                            sources = payload if isinstance(payload, list) else []
                        elif event_name == "delta":
                            answer.append(payload.get("text", ""))
                        elif event_name == "error":
                            error_payload = payload
                        elif event_name in ("cancel", "cancelled"):
                            cancelled = True
                        event_name, data_lines = "message", []
                elapsed = time.monotonic() - started
                if "done" not in events and not error_payload and not cancelled:
                    error_payload = {"code": "MISSING_DONE", "message": "流在done之前结束；可能是客户端取消或连接中断"}
                return {"answer": "".join(answer), "sources": sources, "events": events,
                        "answerId": answer_id, "elapsedSeconds": round(elapsed, 3),
                        "done": "done" in events, "cancelled": cancelled, "error": error_payload}
        except (urllib.error.URLError, ssl.SSLError) as error:
            reason = getattr(error, "reason", error)
            if isinstance(reason, (ssl.SSLError, ssl.SSLEOFError)) or TLS_FAILURE.search(str(reason)):
                raise TlsHandshakeFailure(f"{type(reason).__name__}: {reason}") from error
            raise


class TlsHandshakeFailure(Exception):
    pass


def classify_turn(result):
    if result.get("cancelled") or "cancel" in result.get("events", []) or "cancelled" in result.get("events", []):
        return "CANCELLED"
    error = result.get("error")
    if error and (not isinstance(error, dict) or error.get("code") != "MISSING_DONE"):
        return "ERROR"
    if not result.get("done"):
        return "INCOMPLETE_NO_DONE"
    if not str(result.get("answer", "")).strip():
        return "EMPTY_ANSWER"
    return "DONE"


def run_permission(api, case, library_ids):
    operation = case["id"]
    if operation == "ACL01":
        target_library_id = os.environ.get("ENTERPRISE_UNASSIGNED_KB_ID")
        if not target_library_id:
            return {"status": "CONFIG_REQUIRED", "detail": "设置 ENTERPRISE_UNASSIGNED_KB_ID"}
        bases = api.json("GET", "/customer-service/knowledge-bases") or []
        passed = all(str(base["id"]) != target_library_id for base in bases)
        return {"status": "PASS" if passed else "FAIL", "targetKnowledgeBaseId": target_library_id,
                "observedLibraryIds": [b["id"] for b in bases]}
    if operation == "ACL02":
        kb_id = library_ids.get(case["kb"])
        boundary = "----eval-" + uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"knowledgeBaseId\"\r\n\r\n{kb_id}\r\n"
                f"--{boundary}--\r\n").encode()
        status, raw = api.raw("POST", "/customer-service/file/upload-chunk", body,
                              f"multipart/form-data; boundary={boundary}")
        return {"status": "PASS" if status == 403 else "FAIL", "knowledgeBaseId": kb_id, "httpStatus": status,
                "response": raw.decode("utf-8", errors="replace")[:300]}
    env_name = "ENTERPRISE_FOREIGN_ANSWER_ID" if operation == "ACL03" else "ENTERPRISE_OTHER_CHAT_UUID"
    target = os.environ.get(env_name)
    if not target:
        return {"status": "CONFIG_REQUIRED", "detail": f"设置 {env_name}"}
    path = (f"/customer-service/chat/sources/{urllib.parse.quote(target)}" if operation == "ACL03"
            else f"/chat/{urllib.parse.quote(target)}")
    status, raw = api.raw("GET", path)
    return {"status": "PASS" if status == 404 else "FAIL", "targetResourceId": target, "httpStatus": status,
            "response": raw.decode("utf-8", errors="replace")[:300]}


def run_chat_case(base, case, library_ids, timeout_pause, result_path, results):
    api = Api(base)
    api.login(case["actor"])
    if case["category"] == "permission":
        result = run_permission(api, case, library_ids)
        evaluation = {**case, "status": result.pop("status"), "permissionCheck": result,
                      "retrievalCorrect": None, "answerCorrect": None, "evidenceSupported": None}
        results[case["id"]] = evaluation
        result_path.write_text(json.dumps(list(results.values()), ensure_ascii=False, indent=2), encoding="utf-8")
        return evaluation

    kb_id = library_ids.get(case.get("kb"))
    if kb_id is None:
        raise RuntimeError(f"没有找到知识库 {case.get('kb')}；请先运行 setup_demo.py")
    first_question = case["turns"][0]
    chat = api.json("POST", "/chat/new", {"knowledgeBaseId": kb_id, "message": first_question})
    turns = []
    for turn_index, question in enumerate(case["turns"], 1):
        request_id = str(uuid.uuid4())
        attempts = []
        while True:
            started = time.monotonic()
            try:
                response = api.send_sse(chat["uuid"], request_id, question)
                attempts.append({"elapsedSeconds": round(time.monotonic() - started, 3),
                                 "events": response["events"], "error": response["error"]})
                break
            except TlsHandshakeFailure as error:
                attempts.append({"elapsedSeconds": round(time.monotonic() - started, 3),
                                 "events": [], "error": f"TLS_HANDSHAKE: {error}"})
                if len(attempts) >= 2:
                    response = {"answer": "", "sources": [], "events": [], "answerId": None,
                                "elapsedSeconds": round(time.monotonic() - started, 3), "done": False,
                                "error": f"TLS握手失败，已按规则重试一次：{error}"}
                    break
                time.sleep(timeout_pause)
            except Exception as error:
                attempts.append({"elapsedSeconds": round(time.monotonic() - started, 3),
                                 "events": [], "error": f"{type(error).__name__}: {error}"})
                response = {"answer": "", "sources": [], "events": [], "answerId": None,
                            "elapsedSeconds": round(time.monotonic() - started, 3), "done": False,
                            "error": f"{type(error).__name__}: {error}"}
                break
        turns.append({"question": question, **response, "attempts": attempts,
                      "retrievalCorrect": None, "answerCorrect": None, "evidenceSupported": None})
        result = {**case, "status": classify_turn(response),
                  "chatId": chat["uuid"], "turnResults": turns,
                  "retrievalCorrect": None, "answerCorrect": None, "evidenceSupported": None}
        results[case["id"]] = result
        result_path.write_text(json.dumps(list(results.values()), ensure_ascii=False, indent=2), encoding="utf-8")
        if not response["done"] or not response["answer"].strip():
            break
    return results[case["id"]]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("ENTERPRISE_API_BASE", "http://localhost:8080"))
    parser.add_argument("--questions", type=Path, default=HERE / "questions.json")
    parser.add_argument("--out", type=Path, default=HERE / "results.json")
    parser.add_argument("--pause", type=float, default=10.0, help="wait before one allowed TLS handshake retry")
    parser.add_argument("--ids", help="comma-separated case IDs for the initial six-case smoke run")
    args = parser.parse_args()
    cases = json.loads(args.questions.read_text(encoding="utf-8"))
    if len(cases) != 60:
        raise RuntimeError(f"评测集应为60个用例，实际{len(cases)}")
    if args.ids:
        requested = [value.strip() for value in args.ids.split(",") if value.strip()]
        by_id = {case["id"]: case for case in cases}
        unknown = [case_id for case_id in requested if case_id not in by_id]
        if unknown:
            raise RuntimeError(f"不存在的用例ID：{', '.join(unknown)}")
        cases = [by_id[case_id] for case_id in requested]

    admin = Api(args.base)
    admin.login("admin")
    bases = admin.json("GET", "/customer-service/knowledge-bases") or []
    library_ids = {base["name"]: base["id"] for base in bases}
    results = {}
    for index, case in enumerate(cases, 1):
        started = time.monotonic()
        try:
            result = run_chat_case(args.base, case, library_ids, args.pause, args.out, results)
        except Exception as error:
            result = {**case, "status": "FAILED_SETUP", "error": f"{type(error).__name__}: {error}",
                     "retrievalCorrect": None, "answerCorrect": None, "evidenceSupported": None}
            results[case["id"]] = result
            args.out.write_text(json.dumps(list(results.values()), ensure_ascii=False, indent=2), encoding="utf-8")
        elapsed = round(time.monotonic() - started, 2)
        print(f"[{index}/{len(cases)}] {case['id']} {result['status']} {elapsed}s", flush=True)
    total_requests = sum(max(1, len(item.get("turns", []))) for item in cases)
    chat_turns = sum(len(item.get("turns", [])) for item in cases)
    print(f"已保存 {len(results)} 个用例，{chat_turns} 个聊天轮次 + {total_requests - chat_turns} 个权限请求 = {total_requests} 个评测请求；答案/检索/证据仍须人工分开判定。")
    print(f"结果文件：{args.out}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"评测准备失败：{type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
