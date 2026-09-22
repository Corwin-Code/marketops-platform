#!/usr/bin/env python3
"""Register the Qwen model provider with a running local backend.

What a provider accepts is a published fact, recorded below with its sources and
the date it was checked. Registration goes through the loopback maintenance API,
which audits every change; the provider becomes callable only once its call shape
is recorded and verified.

The API key is never read into this process, printed or sent anywhere. The
backend resolves it from the secret mount at the moment of each call.
``--install-key`` only copies the key file into that mount with owner-only
permissions.

Usage (backend running, from the repository root):

    python3 scripts/register_ai_provider.py --install-key ~/.marketops-platform/dashscope_api_key.txt
    python3 scripts/register_ai_provider.py --api http://127.0.0.1:9999 --model qwen3.8-max
"""

from __future__ import annotations

import argparse
import json
import os
import stat
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# --- Provider facts -----------------------------------------------------------
# Verified 2026-09-22 against Alibaba Cloud Model Studio documentation and with
# live calls to the workspace endpoint:
#   - OpenAI-compatible Chat API: POST {base}/chat/completions, Authorization:
#     Bearer <key>, answer at choices[0].message.content, errors as
#     {"error": {"code": ...}}.
#     https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions
#   - Workspace domain {WorkspaceId}.cn-beijing.maas.aliyuncs.com is the
#     recommended production domain; only that workspace's keys may call it.
#     https://help.aliyun.com/zh/model-studio/base-url
#   - qwen3.8-max: hybrid thinking, on by default; enable_thinking=false is a
#     top-level body member. Context 1,000,000 tokens, output up to 131,072.
#     https://help.aliyun.com/zh/model-studio/qwen3-8-max
#     https://help.aliyun.com/zh/model-studio/deep-thinking
#   - response_format json_object needs the word "JSON" in the prompt (the
#     diagnosis prompt has it). max_completion_tokens replaces the
#     soon-deprecated max_tokens from Qwen3.7-Max on.
#     https://help.aliyun.com/zh/model-studio/qwen-structured-output
# Thinking is off: it roughly doubles latency, and answers must finish inside
# the gateway's 60-second transport bound.
PROVIDER_CODE = "dashscope"
PROVIDER_NAME = "阿里云百炼 Model Studio（OpenAI 兼容）"
PROVIDER_REGION = "cn-beijing"
PROVIDER_OWNER = "owner"
PUBLIC_HOST = "dashscope.aliyuncs.com"
CHAT_PATH = "/compatible-mode/v1/chat/completions"
REQUEST_TEMPLATE = (
    '{"model":"{model}",'
    '"messages":[{"role":"system","content":"{systemPrompt}"},'
    '{"role":"user","content":"{userPrompt}"}],'
    '"max_completion_tokens":{maxOutputTokens},'
    '"stream":false,"enable_thinking":false,'
    '"response_format":{"type":"json_object"}}'
)
RESPONSE_POINTER = "/choices/0/message/content"
AUTH_HEADER = "Authorization"
AUTH_TEMPLATE = "Bearer {value}"
REQUEST_TIMEOUT_MS = 60_000
EVIDENCE_REF = "https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions"
EVIDENCE_TITLE = "阿里云百炼 OpenAI 兼容 Chat 接口与 qwen3.8-max 模型文档，2026-09-22 核验并实测"

DEFAULT_MODEL = "qwen3.8-max"
MODEL_CONTEXT_TOKENS = 1_000_000
SECRET_REFERENCE = "secret-ref://ai/dashscope-api-key"
SECRET_RELATIVE_PATH = Path("ai") / "dashscope-api-key"
DEFAULT_SECRET_MOUNT = Path.home() / ".marketops-platform" / "secrets"
MAXIMUM_KEY_BYTES = 16 * 1024


def env_local_value(name: str) -> str | None:
    """Read one plain ``NAME=value`` line from the ignored ``.env.local``."""
    path = REPO_ROOT / ".env.local"
    if not path.is_file():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == name:
            return value.strip() or None
    return None


def owner_only_directory(path: Path) -> bool:
    """A real directory (not a link) that neither group nor others can write."""
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        return False
    return stat.S_ISDIR(info.st_mode) and not info.st_mode & (stat.S_IWGRP | stat.S_IWOTH)


def installed_key_present(mount: Path, target: Path) -> bool:
    """Whether the backend will accept the installed key: owner-only directories and a non-empty regular file."""
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return False
    return (owner_only_directory(mount) and owner_only_directory(target.parent)
            and stat.S_ISREG(info.st_mode) and info.st_size > 0)


def checked_mount(value: str) -> Path:
    """The secret mount, refused unless the backend would read exactly this directory.

    The backend binds the value as a plain path: it does not expand ``~``,
    resolves a relative path against its own working directory, and refuses a
    mount reached through a link. A value it would read differently, or one
    inside the repository where a key could be committed, is refused here.
    """
    mount = Path(value)
    if value.startswith("~") or not mount.is_absolute():
        sys.exit(f"secret mount must be an absolute path without '~': {value}")
    if mount == REPO_ROOT or REPO_ROOT in mount.parents:
        sys.exit("secret mount must be outside the repository")
    if os.path.realpath(mount) != os.path.abspath(mount):
        sys.exit(f"secret mount must not pass through a symbolic link: {value}")
    return mount


def install_key(source: Path, target: Path) -> None:
    """Copy the key file into the secret mount, owner-only, replacing any old copy atomically.

    The bytes are copied without being decoded, printed or kept. A new file is
    written beside the target and renamed over it, so a reader never sees a
    half-written key and a link at the target is replaced rather than followed.
    """
    source = source.expanduser()
    if not source.is_file() or not 0 < source.stat().st_size <= MAXIMUM_KEY_BYTES:
        sys.exit(f"install-key: {source} is not a usable key file")
    if target.exists() and os.path.samefile(source, target):
        sys.exit("install-key: the source is already the installed key; nothing to copy")
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(target.parent, 0o700)
    os.chmod(target.parent.parent, 0o700)
    descriptor, temporary = tempfile.mkstemp(dir=target.parent, prefix=".key-")
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as out, source.open("rb") as src:
            while chunk := src.read(4096):
                out.write(chunk)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, target)
    except BaseException:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise


class Admin:
    """The loopback maintenance API."""

    def __init__(self, base: str, operator: str) -> None:
        self.base = base.rstrip("/") + "/api/v1/admin/metadata"
        self.operator = operator

    def call(self, method: str, path: str, body: dict | None = None) -> tuple[int, object]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if body is not None:
            request.add_header("Content-Type", "application/json")
            request.add_header("X-Operator", self.operator)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw) if raw else None
            except json.JSONDecodeError:
                return error.code, None
        except urllib.error.URLError as error:
            sys.exit(f"cannot reach the backend at {self.base}: {error.reason}")

    def require(self, method: str, path: str, body: dict | None, *ok: int) -> object:
        status, payload = self.call(method, path, body)
        if status not in ok:
            title = payload.get("title") if isinstance(payload, dict) else None
            sys.exit(f"{method} {path} -> HTTP {status} {title or ''}".rstrip())
        return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--api", default="http://127.0.0.1:8080", help="backend base URL")
    parser.add_argument("--host", help="Model Studio host; default: MARKETOPS_AI_DASHSCOPE_HOST "
                                        "from the environment or .env.local, else the shared domain")
    parser.add_argument("--model", default=DEFAULT_MODEL,
                        help="model code to register (switching models is not supported yet: "
                             "the gateway uses the alphabetically first active model)")
    parser.add_argument("--operator", default="owner-local", help="operator recorded in the audit")
    parser.add_argument("--install-key", type=Path, metavar="FILE",
                        help="copy this key file into the secret mount first")
    parser.add_argument("--secret-mount",
                        help="secret mount directory; default: MARKETOPS_SECRET_MOUNT_DIRECTORY from the "
                             f"environment or .env.local, else {DEFAULT_SECRET_MOUNT}")
    parser.add_argument("--reactivate", action="store_true",
                        help="turn a provider that was retired back on")
    args = parser.parse_args(argv)

    # Both values are resolved the way the backend resolves them, so the key
    # lands where the backend reads and the recorded URL matches the allowlist.
    host = (args.host or os.environ.get("MARKETOPS_AI_DASHSCOPE_HOST")
            or env_local_value("MARKETOPS_AI_DASHSCOPE_HOST") or PUBLIC_HOST)
    mount = checked_mount(str(args.secret_mount or os.environ.get("MARKETOPS_SECRET_MOUNT_DIRECTORY")
                              or env_local_value("MARKETOPS_SECRET_MOUNT_DIRECTORY")
                              or DEFAULT_SECRET_MOUNT))
    key_file = mount / SECRET_RELATIVE_PATH
    result: dict[str, object] = {"host": host, "model": args.model, "secretMount": str(mount)}

    if args.install_key is not None:
        install_key(args.install_key, key_file)
        result["keyInstalledAt"] = str(key_file)

    admin = Admin(args.api, args.operator)
    providers = admin.require("GET", "/ai-providers", None, 200)
    provider = next((p for p in providers if p.get("providerCode") == PROVIDER_CODE), None)
    if provider is None:
        created = admin.require("POST", "/ai-providers", {
            "providerCode": PROVIDER_CODE, "displayName": PROVIDER_NAME,
            "serviceRegionLabel": PROVIDER_REGION, "ownerLabel": PROVIDER_OWNER}, 201)
        provider = {"id": created["id"], "version": 0, "status": "RETIRED",
                    "eligibilityState": "UNVERIFIED"}
        result["provider"] = "registered"
    else:
        result["provider"] = "already registered"

    # A retired provider was switched off on purpose; a routine rerun (a new
    # key, a new workstation) must not quietly switch it back on.
    retired = provider.get("status") == "RETIRED" and provider.get("eligibilityState") == "VERIFIED"
    if retired and not args.reactivate:
        result["verification"] = "provider is retired; not reactivated (pass --reactivate to turn it on)"
        result["keyPresent"] = installed_key_present(mount, key_file)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 2
    # Otherwise the call shape is recorded on every run, so the stored URL and
    # template always match this script and the configured host.
    admin.require("POST", f"/ai-providers/{provider['id']}/verification", {
        "invocationUrl": f"https://{host}{CHAT_PATH}",
        "requestTemplate": REQUEST_TEMPLATE,
        "responsePointer": RESPONSE_POINTER,
        "authHeaderName": AUTH_HEADER,
        "authValueTemplate": AUTH_TEMPLATE,
        "requestTimeoutMillis": REQUEST_TIMEOUT_MS,
        "evidenceRef": EVIDENCE_REF,
        "verifiedSourceTitle": EVIDENCE_TITLE,
        "expectedVersion": provider["version"]}, 204)
    result["verification"] = "call shape recorded; provider active"

    status, _ = admin.call("POST", f"/ai-providers/{provider['id']}/models", {
        "modelCode": args.model, "displayName": args.model,
        "secretReference": SECRET_REFERENCE, "maximumContextTokens": MODEL_CONTEXT_TOKENS})
    if status == 201:
        result["modelRegistration"] = "registered"
    elif status == 409:
        result["modelRegistration"] = "already registered"
    else:
        sys.exit(f"model registration -> HTTP {status}")

    result["keyPresent"] = installed_key_present(mount, key_file)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not result["keyPresent"]:
        print(f"note: no usable key at {key_file}; run again with --install-key FILE", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
