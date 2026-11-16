#!/usr/bin/env python3
"""Create the small HR/IT demo roster and import/publish the fictional corpus.

Passwords are read from environment variables and never written to the mapping.
This script writes data only to the explicitly selected enterprise API base URL.
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.cookiejar import CookieJar
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MANIFEST = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.cookies = CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None

    def request(self, method, path, payload=None, content_type="application/json", extra_headers=None):
        headers = {"Accept": "application/json"}
        body = None
        if payload is not None:
            if content_type == "application/json":
                body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            else:
                body = payload
            headers["Content-Type"] = content_type
        if extra_headers:
            headers.update(extra_headers)
        if method.upper() not in ("GET", "HEAD", "OPTIONS"):
            self.refresh_csrf()
            headers[self.csrf["headerName"]] = self.csrf["token"]
        req = urllib.request.Request(self.base + path, data=body, headers=headers, method=method.upper())
        try:
            with self.opener.open(req, timeout=90) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"HTTP {error.code} {method} {path}: {raw[:500]}") from error

    def refresh_csrf(self):
        _, response = self.request_without_csrf("GET", "/auth/csrf")
        self.csrf = response["data"]

    def request_without_csrf(self, method, path, payload=None):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        with self.opener.open(req, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None

    def login(self, username, password):
        self.refresh_csrf()
        self.request("POST", "/auth/login", {"username": username, "password": password})
        self.refresh_csrf()

    def data(self, method, path, payload=None):
        _, response = self.request(method, path, payload)
        if not response or response.get("success") is False:
            raise RuntimeError(f"业务请求失败：{method} {path}: {response}")
        return response.get("data")


def require_env(name):
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"请先设置环境变量 {name}；密码不会从命令行参数或仓库文件读取。")
    return value


def multipart(fields, file_field, filename, content):
    boundary = "----milan-enterprise-" + uuid.uuid4().hex
    chunks = []
    for key, value in fields.items():
        chunks.extend([f"--{boundary}\r\n".encode(),
                       f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode(),
                       str(value).encode(), b"\r\n"])
    chunks.extend([f"--{boundary}\r\n".encode(),
                   f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'.encode(),
                   b"Content-Type: text/markdown; charset=utf-8\r\n\r\n", content, b"\r\n",
                   f"--{boundary}--\r\n".encode()])
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def ensure_roster(api):
    users = api.data("GET", "/admin/users")
    users_by_name = {user["username"]: user for user in users}
    roster = {"admin": {"id": None, "username": require_env("ENTERPRISE_ADMIN_USERNAME")}}
    passwords = {}
    for user in MANIFEST["users"]:
        key = user["key"]
        username = os.environ.get(f"ENTERPRISE_{key.upper()}_USERNAME", user["username"])
        password = require_env(f"ENTERPRISE_{key.upper()}_PASSWORD")
        passwords[key] = password
        found = users_by_name.get(username)
        if found is None:
            found = api.data("POST", "/admin/users", {
                "username": username, "displayName": user["displayName"], "password": password
            })
            print(f"created user {username}")
        roster[key] = {"id": found["id"], "username": username}

    bases = api.data("GET", "/customer-service/knowledge-bases")
    bases_by_name = {base["name"]: base for base in bases}
    for spec in MANIFEST["knowledgeBases"]:
        base = bases_by_name.get(spec["name"])
        if base is None:
            knowledge_base_id = api.data("POST", "/customer-service/knowledge-bases", {
                "name": spec["name"], "description": spec["description"]
            })
            base = {"id": knowledge_base_id, "name": spec["name"]}
            print(f"created knowledge base {spec['name']}")
        roster["kb:" + spec["name"]] = {"id": base["id"], "name": base["name"]}
        current_members = api.data("GET", f"/admin/knowledge-bases/{base['id']}/members")
        by_user = {member["userId"]: member for member in current_members}
        editor_id = roster[spec["editor"]]["id"]
        targets = [(editor_id, "EDITOR")] + [(roster[key]["id"], "READER") for key in spec["readers"]]
        for user_id, level in targets:
            if by_user.get(user_id, {}).get("accessLevel") != level:
                api.data("PUT", f"/admin/knowledge-bases/{base['id']}/members", {
                    "userId": user_id, "accessLevel": level
                })
    return roster


def find_document(api, knowledge_base_id, file_name):
    for document in api.data("GET", f"/customer-service/documents?knowledgeBaseId={knowledge_base_id}"):
        for version in document["versions"]:
            if version["fileName"] == file_name:
                return document, version
    return None, None


def upload_and_publish(api, knowledge_base_id, file_path, document_id=None):
    content = file_path.read_bytes()
    digest = hashlib.md5(content).hexdigest()
    check = api.data("POST", "/customer-service/file/check", {
        "knowledgeBaseId": knowledge_base_id, "fileMd5": digest
    })
    if check.get("exists") and not check.get("needUpload"):
        raise RuntimeError(f"文件 {file_path.name} 已存在但未关联到清单逻辑文档；为避免串版本，停止自动导入。")
    chunk_size = 2 * 1024 * 1024
    pieces = [content[i:i + chunk_size] for i in range(0, len(content), chunk_size)]
    for index, piece in enumerate(pieces):
        fields = {"knowledgeBaseId": knowledge_base_id, "fileMd5": digest, "fileName": file_path.name,
                  "fileSize": len(content), "chunkNumber": index, "totalChunks": len(pieces)}
        if document_id is not None:
            fields["documentId"] = document_id
        body, content_type = multipart(fields, "chunk", file_path.name, piece)
        api.request("POST", "/customer-service/file/upload-chunk", body, content_type)
    api.data("POST", "/customer-service/file/merge-chunk", {"knowledgeBaseId": knowledge_base_id, "fileMd5": digest})

    deadline = time.time() + 600
    document = version = None
    while time.time() < deadline:
        document, version = find_document(api, knowledge_base_id, file_path.name)
        if version and int(version["status"]) == 3 and version["indexStatus"] == "SYNCED":
            break
        time.sleep(5)
    else:
        raise TimeoutError(f"{file_path.name} 在10分钟内未达到已解析且已同步状态")
    api.data("POST", f"/customer-service/documents/{document['id']}/publish", {"fileId": version["id"]})
    return {"documentId": document["id"], "fileId": version["id"], "versionNo": version["versionNo"],
            "fileName": file_path.name, "knowledgeBaseId": knowledge_base_id, "publishStatus": "PUBLISHED"}


def ensure_existing_version_published(api, document, version):
    state = version.get("publishStatus")
    if state in ("PUBLISHED", "ARCHIVED", "WITHDRAWN"):
        return state
    if state != "DRAFT" or int(version.get("status", -1)) != 3 or version.get("indexStatus") != "SYNCED":
        raise RuntimeError(f"已有版本 {version.get('fileName', '')} 未就绪；先在资料管理中检查解析和索引状态。")
    api.data("POST", f"/customer-service/documents/{document['id']}/publish", {"fileId": version["id"]})
    return "PUBLISHED"


def main():
    base = os.environ.get("ENTERPRISE_API_BASE", "http://localhost:8080").rstrip("/")
    api = Api(base)
    api.login(require_env("ENTERPRISE_ADMIN_USERNAME"), require_env("ENTERPRISE_ADMIN_PASSWORD"))
    roster = ensure_roster(api)
    mapping = {"fictional": True, "apiBase": base, "users": roster, "documents": {}}

    for spec in MANIFEST["documents"]:
        kb_id = roster["kb:" + spec["knowledgeBase"]]["id"]
        versions = spec.get("versions", [spec["file"]])
        document_id = None
        for filename in versions:
            found_doc, found_version = find_document(api, kb_id, filename)
            if found_doc:
                document_id = found_doc["id"]
            if found_version:
                publish_status = ensure_existing_version_published(api, found_doc, found_version)
                result = {"documentId": found_doc["id"], "fileId": found_version["id"],
                          "versionNo": found_version["versionNo"], "fileName": filename,
                          "knowledgeBaseId": kb_id, "publishStatus": publish_status, "reused": True}
            else:
                path = ROOT / filename
                result = upload_and_publish(api, kb_id, path, document_id)
                document_id = result["documentId"]
            mapping["documents"].setdefault(spec["key"], []).append(result)
            (ROOT / "import-results.json").write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"published {filename}: document={result['documentId']} file={result['fileId']} v{result['versionNo']}", flush=True)

    out_path = ROOT / "import-results.json"
    out_path.write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"mapping written to {out_path}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"import failed: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
