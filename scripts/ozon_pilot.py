#!/usr/bin/env python3
"""Connect the Ozon pilot account to MarketOps for reading, one governed step at a time.

Run from the repository root. Every step except ``probe`` needs the local backend
(``--api``, default http://127.0.0.1:8080). Every step takes ``--capability``:
``connectivity`` (POST /v1/roles, the default) or ``catalog`` (product identity,
names and barcodes from POST /v4/product/info/attributes).

  probe      Call /v1/roles to check the key (read-only roles, expiry), then make
             the capability's real calls and keep every answer, together with the
             official OpenAPI document, as verification evidence. Talks to Ozon only.
  setup      Register the pilot account and store, its READ credential, a reader
             service account with a READ grant on the account, and the capability's
             registry rows, ingestion job and normalization mapping, through the
             loopback maintenance API.
  reviewer   Give a second Keycloak user the OWNER role and KILL_SWITCH_OPERATE, so
             registry evidence can be submitted by one Owner and approved by another.
  verify     Draft the Ozon profile, the two authentication headers and the
             capability's endpoint, submit the probe evidence as one Owner and
             approve it as the other, through the authenticated console API.
  run        Queue one manual run of the capability's job and execute it.
  normalize  Turn what the capability's job stored into canonical facts.

Secrets. The Api-Key is read only by ``probe``, which sends it to api-seller.ozon.ru
and nowhere else and never prints it; the backend resolves its own copy from the
secret mount at call time. The Client-Id is read from the mount by ``probe`` and
``setup``; ``setup`` stores it as the account's native key. Keycloak passwords are
asked for by ``verify`` and are neither stored nor printed. Evidence files hold the
store's own catalog data and are kept owner-only outside the repository; only
counts are printed.

The key files are expected at <mount>/ozon/<pilot>/seller-api-key and
<mount>/ozon/<pilot>/client-id, owner-only, exactly as the backend reads them.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import html
import http.client
import http.cookiejar
import json
import os
import re
import secrets
import ssl
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# --- Platform facts -------------------------------------------------------------
# Verified 2026-09-28/29 against the official Ozon Seller API documentation,
# https://docs.ozon.ru/api/seller/ ("Документация Ozon Seller API (2.1)"), and the
# OpenAPI document it loads, https://docs.ozon.ru/api/seller/swagger.json
# (openapi 3.0.0, info.version 2.1, 481 paths):
#   - requests go to https://api-seller.ozon.ru and carry the Client-Id and
#     Api-Key headers; the Seller API works in UTC;
#   - a key is valid for 3 months; POST /v1/roles (operationId
#     AccessAPI_RolesByToken) takes no request body and answers
#     {"expires_at": <date-time>, "roles": [{"name": ..., "methods": [...]}]}
#     for the calling key, without any store data;
#   - POST /v4/product/info/attributes takes {filter{visibility}, last_id, limit
#     ≤ 1000} and answers {result: [{id, offer_id, name, barcode, barcodes, sku,
#     attributes, ...}], last_id, total}; "leave last_id empty on the first
#     request, then pass last_id from the previous answer". The documentation
#     does not state how the listing ends. The real account (2026-09-29) ends it
#     with a page shorter than the limit that still carries a last_id; a request
#     with that last_id is answered HTTP 404 {"code": 5, "message": "item not
#     found"}. The probe pages to the end and records the signal every time;
#   - a method without its own limit allows at most 50 requests per second per
#     Client-Id across all methods; a 429 carries Retry-After in seconds;
#   - 403 "Offer not signed" when the seller offer is not accepted.
OZON_HOST = "api-seller.ozon.ru"
OZON_BASE_URL = f"https://{OZON_HOST}"
ROLES_PATH = "/v1/roles"
OFFICIAL_SOURCE_URL = "https://docs.ozon.ru/api/seller/swagger.json"
REQUEST_TIMEOUT_SECONDS = 30
MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024
MAXIMUM_OPENAPI_BYTES = 32 * 1024 * 1024
# platform.registry_verification_case: valid_until <= tested_at + 30 days.
EVIDENCE_WINDOW = timedelta(days=30)
# The backend renders {limit} as 100; the probe asks for exactly what runs will.
PAGE_SIZE = 100
MAXIMUM_PROBE_PAGES = 60
PROBE_PAUSE_SECONDS = 0.3

PLATFORM = "OZON"
OWNER_LABEL = "owner"
PROFILE_DEFINITION = {
    "base_url": OZON_BASE_URL,
    "request_timeout_ms": REQUEST_TIMEOUT_SECONDS * 1000,
    "max_response_bytes": MAXIMUM_RESPONSE_BYTES,
    "owner_label": OWNER_LABEL,
}
HEADER_DEFINITIONS = (
    {"header_name": "Client-Id", "value_source": "ACCOUNT_NATIVE_KEY", "value_template": "{value}",
     "credential_purpose": "READ", "ordinal": 1, "owner_label": OWNER_LABEL},
    {"header_name": "Api-Key", "value_source": "RESOLVED_SECRET", "value_template": "{value}",
     "credential_purpose": "READ", "ordinal": 2, "owner_label": OWNER_LABEL},
)

# One entry per capability: its registry rows, the job that reads it, and the
# normalization mapping that turns its answers into facts.
CAPABILITIES = {
    "connectivity": {
        "code": "ozon-seller-connectivity",
        "display": "Ozon Seller API key connectivity",
        "description": "Proves the account's key is accepted and shows its roles and expiry "
                       "(POST /v1/roles; official docs checked 2026-09-28).",
        "manifest": "roles-latest.json",
        "endpoint": {
            "code": "ozon-roles-v1", "api_version": "v1", "schema_version": "v1RolesByTokenResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; /v1/roles states none. Our cap: 10/min. "
                         "https://docs.ozon.ru/api/seller/ checked 2026-09-28",
            "freshness": "Each call answers the key's current roles and expiry.",
            "definition": {
                "http_method": "POST", "path_template": ROLES_PATH, "operation_function": "READ_DATA",
                "query_template": None, "body_template": None,
                "response_content_type": "application/json", "continuation_pointer": None,
                "pagination_model": "NONE", "rate_limit_per_minute": 10,
                "continuation_end_rule": "JSON_NULL", "records_pointer": None,
            },
        },
        "job": {"suffix": "roles", "dataset": "UNKNOWN", "display": "Ozon 试点：API key 连通性"},
        "mapping": None,
    },
    "catalog": {
        "code": "ozon-catalog-read",
        "display": "Ozon catalog: product identity, names and barcodes",
        "description": "Reads every product's Ozon id, seller offer id, name and barcode "
                       "(POST /v4/product/info/attributes, paged by last_id; official docs "
                       "checked 2026-09-29).",
        "manifest": "catalog-latest.json",
        "endpoint": {
            "code": "ozon-product-attributes-v4", "api_version": "v4",
            "schema_version": "v4GetProductAttributesResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; this method states none. Our cap: 60/min, 100 products per page. "
                         "https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "A full re-read on every run; the listing is a snapshot of the catalog.",
            "definition": {
                "http_method": "POST", "path_template": "/v4/product/info/attributes",
                "operation_function": "READ_DATA", "query_template": None,
                "body_template": '{"filter":{"visibility":"ALL"},"last_id":"{cursor}","limit":{limit}}',
                "response_content_type": "application/json", "continuation_pointer": "/last_id",
                "pagination_model": "CURSOR", "rate_limit_per_minute": 60,
                # Probed 2026-09-29: the last page is short and still carries a
                # last_id; a request with it is answered 404 {"code": 5}.
                "continuation_end_rule": "SHORT_PAGE_OR_NOT_FOUND", "records_pointer": "/result",
            },
            "probe_body": lambda cursor: {"filter": {"visibility": "ALL"}, "last_id": cursor, "limit": PAGE_SIZE},
            "token_key": "last_id",
            "records_key": "result",
        },
        "job": {"suffix": "catalog", "dataset": "LISTING", "display": "Ozon 试点：商品目录"},
        # Ozon's product id is both the listing and its only variant; the seller's
        # offer id is the stock-keeping unit a company matches on.
        "mapping": {
            "dataset": "LISTING", "version": 1, "record_pointer": "/result",
            "fields": {"nativeListingKey": "/id", "nativeVariantKey": "/id", "nativeSkuKey": "/offer_id",
                       "title": "/name", "nativeBarcode": "/barcode"},
        },
    },
}

DEFAULT_SECRET_MOUNT = Path.home() / ".marketops-platform" / "secrets"
DEFAULT_EVIDENCE_ROOT = Path.home() / ".marketops-platform" / "evidence"
MAXIMUM_SECRET_BYTES = 16 * 1024
PILOT_CODE = re.compile(r"^[a-z0-9]([a-z0-9-]{0,40}[a-z0-9])?$")

DEFAULT_ISSUER = "https://localhost/realms/marketops"
DEFAULT_CLIENT_ID = "marketops-console"
DEFAULT_REDIRECT_URI = "http://127.0.0.1:5173/signed-in"
DEFAULT_AUDIENCE = "marketops"
LOCAL_OIDC_CA = REPO_ROOT / ".tmp" / "local-oidc" / "tls-cert.pem"


# --- Names ----------------------------------------------------------------------

class Pilot:
    """Every code and path derived from the pilot's short code."""

    def __init__(self, code: str, mount: Path, evidence_root: Path) -> None:
        if not PILOT_CODE.match(code):
            sys.exit(f"--pilot must be lowercase letters, digits and hyphens: {code}")
        self.code = code
        self.account_code = f"ozon-{code}"
        self.store_code = f"ozon-{code}"
        self.credential_code = f"ozon-{code}-read"
        self.service_account_code = f"ozon-{code}-reader"
        self.secret_dir = mount / "ozon" / code
        self.api_key_file = self.secret_dir / "seller-api-key"
        self.client_id_file = self.secret_dir / "client-id"
        self.secret_reference = f"secret-ref://ozon/{code}/seller-api-key"
        self.mount = mount
        self.evidence_dir = evidence_root / "ozon" / code

    def job_code(self, capability: dict) -> str:
        return f"ozon-{self.code}-{capability['job']['suffix']}"

    def manifest(self, capability: dict) -> Path:
        return self.evidence_dir / capability["manifest"]

    def evidence_ref(self, file_name: str) -> str:
        return f"evidence://ozon/{self.code}/{file_name}"


def capability_from(args: argparse.Namespace) -> dict:
    return CAPABILITIES[args.capability]


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


def checked_mount(value: str) -> Path:
    """The secret mount, refused unless the backend would read exactly this directory."""
    mount = Path(value)
    if value.startswith("~") or not mount.is_absolute():
        sys.exit(f"secret mount must be an absolute path without '~': {value}")
    if mount == REPO_ROOT or REPO_ROOT in mount.parents:
        sys.exit("secret mount must be outside the repository")
    if os.path.realpath(mount) != os.path.abspath(mount):
        sys.exit(f"secret mount must not pass through a symbolic link: {value}")
    return mount


def pilot_from(args: argparse.Namespace) -> Pilot:
    mount = checked_mount(str(args.secret_mount or os.environ.get("MARKETOPS_SECRET_MOUNT_DIRECTORY")
                              or env_local_value("MARKETOPS_SECRET_MOUNT_DIRECTORY")
                              or DEFAULT_SECRET_MOUNT))
    evidence_root = Path(args.evidence_root).expanduser() if args.evidence_root else DEFAULT_EVIDENCE_ROOT
    if evidence_root == REPO_ROOT or REPO_ROOT in evidence_root.resolve().parents:
        sys.exit("the evidence directory must be outside the repository")
    return Pilot(args.pilot, mount, evidence_root)


# --- Secret files -----------------------------------------------------------------

def owner_only_directory(path: Path) -> bool:
    """A real directory (not a link) that neither group nor others can write."""
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        return False
    return stat.S_ISDIR(info.st_mode) and not info.st_mode & (stat.S_IWGRP | stat.S_IWOTH)


def read_secret(pilot: Pilot, path: Path, what: str) -> str:
    """Read one value the way the backend's resolver does, or stop with a reason.

    Every directory from the mount down must be owner-only and not a link, the
    file must be a regular file of at most 16 KiB, and one trailing newline is
    ignored. The value itself never appears in a message.
    """
    directory = pilot.mount
    for part in [None, *path.relative_to(pilot.mount).parts[:-1]]:
        if part is not None:
            directory = directory / part
        if not owner_only_directory(directory):
            sys.exit(f"{what}: {directory} must be a directory only you can write "
                     f"(run: chmod 700 '{directory}')")
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        sys.exit(f"{what}: {path} does not exist")
    if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= MAXIMUM_SECRET_BYTES:
        sys.exit(f"{what}: {path} must be a regular, non-empty file of at most 16 KiB")
    if info.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        sys.exit(f"{what}: {path} must be readable by you only (run: chmod 600 '{path}')")
    value = path.read_bytes().decode("utf-8", errors="strict")
    if value.endswith("\n"):
        value = value[:-1]
    if not value or not re.fullmatch(r"[!-~]+", value):
        sys.exit(f"{what}: {path} must hold a single value without spaces or line breaks")
    return value


# --- Small helpers ----------------------------------------------------------------

def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_instant(value: str) -> datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    # Python 3.9 accepts only 3- or 6-digit fractions: pad or cut to microseconds.
    text = re.sub(r"\.(\d+)", lambda fraction: "." + fraction.group(1)[:6].ljust(6, "0"), text, count=1)
    moment = datetime.fromisoformat(text)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(timezone.utc)


def private_write(path: Path, data: bytes) -> None:
    """Write a file readable by its owner only, replacing any old copy atomically."""
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=".evidence-")
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
    except BaseException:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise


def tls_context(ca_file: str | None) -> ssl.SSLContext:
    return ssl.create_default_context(cafile=ca_file) if ca_file else ssl.create_default_context()


# --- probe ------------------------------------------------------------------------

def post_ozon(path: str, body: dict | None, client_id: str, api_key: str,
              context: ssl.SSLContext) -> tuple[int, str, bytes]:
    """One Seller API call exactly as the registered endpoint sends it."""
    headers = {"Client-Id": client_id, "Api-Key": api_key, "Accept": "application/json"}
    payload = None
    if body is not None:
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    connection = http.client.HTTPSConnection(OZON_HOST, 443, timeout=REQUEST_TIMEOUT_SECONDS,
                                             context=context)
    try:
        connection.request("POST", path, body=payload, headers=headers)
        response = connection.getresponse()
        answer = response.read(MAXIMUM_RESPONSE_BYTES + 1)
        if len(answer) > MAXIMUM_RESPONSE_BYTES:
            sys.exit(f"probe: the answer of {path} is larger than the registered response limit")
        content_type = (response.getheader("Content-Type") or "").split(";", 1)[0].strip()
        return response.status, content_type, answer
    except ssl.SSLCertVerificationError as failure:
        sys.exit(f"probe: TLS certificate of {OZON_HOST} could not be verified ({failure.verify_message}); "
                 "pass --ca-file with a trusted root bundle")
    except OSError as failure:
        sys.exit(f"probe: cannot reach {OZON_HOST}: {failure}")
    finally:
        connection.close()


# A documented method whose summary starts with one of these verbs changes the
# seller's data. The official summaries are Russian; report generation
# (/report/ paths) only queues a report and is not counted as a change.
MUTATING_SUMMARY = re.compile(
    r"^(Создать|Создайте|Cоздать|Создание|Обновить|Обновление|Удалить|Удаление|Изменить|Изменение|"
    r"Установить|Установка|Добавить|Загрузить|Загрузка|Отправить|Отменить|Перенести|Вернуть|"
    r"Привязать|Отвязать|Согласовать|Отклонить|Подтвердить|Подтверждение|Собрать|Частичная сборка|"
    r"Разделить|Передать|Открыть|Включить|Поставить|Снять|Указать|Уточнить|Проверить и сохранить|"
    r"Наполнить|Разместить|Убрать|Настроить|Управлять|Перевести|Отредактировать|Редактировать|"
    r"Редактирование|Оставить|Отметить|Связать|Свяжите|Подключить|Сгенерировать)")


def official_source(pilot: Pilot, given: str) -> tuple[Path, str, dict]:
    """The official OpenAPI document the operator saved, kept beside the evidence.

    docs.ozon.ru answers scripted downloads with an anti-bot challenge that
    needs a browser, so the document is saved from a browser rather than
    fetched here.
    """
    path = Path(given).expanduser()
    if not path.is_file() or path.stat().st_size > MAXIMUM_OPENAPI_BYTES:
        sys.exit(f"probe: {path} is not a readable OpenAPI file")
    data = path.read_bytes()
    try:
        document = json.loads(data)
        operation = document["paths"][ROLES_PATH]["post"]
    except (ValueError, KeyError, TypeError):
        sys.exit(f"probe: {path} is not the Seller API OpenAPI document (no {ROLES_PATH}); save "
                 f"{OFFICIAL_SOURCE_URL} again as the raw JSON, not as a web page")
    if "requestBody" in operation:
        sys.exit(f"probe: the official {ROLES_PATH} now declares a request body; the registered "
                 "request has to be reviewed before any evidence is recorded")
    version = str(document.get("info", {}).get("version", "unknown"))
    target = pilot.evidence_dir / f"ozon-seller-openapi-{utc_now():%Y%m%d}.json"
    private_write(target, data)
    print(f"official source: {OFFICIAL_SOURCE_URL} (info.version {version}) kept at {target}")
    return target, hashlib.sha256(data).hexdigest(), document


def mutating_methods(document: dict) -> set[str]:
    """Documented methods that change data, judged from their official summaries."""
    found = set()
    for path, operations in document.get("paths", {}).items():
        if "/report/" in path or not isinstance(operations, dict):
            continue
        for operation in operations.values():
            if isinstance(operation, dict) and MUTATING_SUMMARY.match(str(operation.get("summary", "")).strip()):
                found.add(path)
    return found


def check_key(pilot: Pilot, args: argparse.Namespace, client_id: str, api_key: str,
              context: ssl.SSLContext) -> dict | None:
    """Call /v1/roles, keep the answer, and refuse a key that can change the store.

    Returns what the evidence needs, or ``None`` after printing why nothing can
    be recorded.
    """
    tested_at = utc_now()
    status, content_type, body = post_ozon(ROLES_PATH, None, client_id, api_key, context)
    file_name = f"roles-{tested_at:%Y%m%d-%H%M%S}z.json"
    private_write(pilot.evidence_dir / file_name, body)
    digest = hashlib.sha256(body).hexdigest()
    print(f"POST {OZON_BASE_URL}{ROLES_PATH} -> HTTP {status} ({content_type or 'no content type'})")
    print(f"answer kept at {pilot.evidence_dir / file_name} (sha256 {digest})")
    try:
        answer = json.loads(body) if content_type == "application/json" else None
    except ValueError:
        answer = None
    if status != 200 or not isinstance(answer, dict):
        if isinstance(answer, dict):
            print(f"Ozon refused: code={answer.get('code')} message={answer.get('message')}")
        print("no evidence recorded: only a successful answer can verify the endpoint")
        return None
    try:
        key_expires = parse_instant(str(answer["expires_at"]))
        granted = {str(role["name"]): [str(method) for method in (role.get("methods") or [])]
                   for role in answer["roles"]}
    except (KeyError, TypeError, ValueError):
        print("the answer does not have the documented shape (expires_at, roles[].name); nothing recorded")
        return None
    print(f"key expires {iso(key_expires)} ({(key_expires - tested_at).days} days left)")
    print("roles on this key:")
    for name, methods in granted.items():
        print(f"  - {name} ({len(methods)} methods)")

    if not args.official_source_file:
        print(f"no evidence recorded yet: open {OFFICIAL_SOURCE_URL} in a browser, save it as a "
              "JSON file and run the probe again with --official-source-file <file> "
              "(make ozon-probe OFFICIAL_SOURCE=<file>)")
        return None
    source_file, source_digest, document = official_source(pilot, args.official_source_file)

    # A read-only integration keeps a read-only key: a role that can change the
    # store is refused unless the operator accepts it explicitly.
    mutating = mutating_methods(document)
    documented = set(document.get("paths", {}))
    writers = {name: sorted(set(methods) & mutating) for name, methods in granted.items()}
    writers = {name: found for name, found in writers.items() if found}
    unclassified = sorted({m for methods in granted.values() for m in methods} - documented)
    if unclassified:
        print(f"{len(unclassified)} granted methods are not in the official document and were not classified")
    if writers:
        print("roles that can change the store:")
        for name, found in sorted(writers.items(), key=lambda item: -len(item[1])):
            print(f"  - {name}: {len(found)} methods, e.g. {', '.join(found[:3])}")
        if not args.allow_write_roles:
            print("no evidence recorded: generate a key with read-only roles only and probe again "
                  "(or pass --allow-write-roles to accept these roles on purpose)")
            return None
        print("--allow-write-roles given: recording evidence for a key that can change the store")
    return {"testedAt": tested_at, "keyExpires": key_expires, "granted": granted, "writers": writers,
            "document": document, "rolesFile": file_name, "rolesDigest": digest,
            "sourceFile": source_file, "sourceDigest": source_digest}


def probe_pages(pilot: Pilot, capability: dict, key: dict, client_id: str, api_key: str,
                context: ssl.SSLContext) -> tuple[str, str] | None:
    """Page one capability's endpoint to its end and keep every answer as a bundle.

    The bundle is what the verification case points at: each page's own file
    and digest, how many records it held, and the signal that ended the
    listing. Record contents are never printed.
    """
    endpoint = capability["endpoint"]
    path = endpoint["definition"]["path_template"]
    if not any(path in methods for methods in key["granted"].values()):
        print(f"the key has no role that allows {path}; generate one with the needed read-only role")
        return None
    operation = key["document"].get("paths", {}).get(path, {}).get("post")
    if not isinstance(operation, dict) or "requestBody" not in operation:
        print(f"the official document no longer describes POST {path} with a body; review before recording")
        return None

    rule = endpoint["definition"]["continuation_end_rule"]
    short_rule = rule in ("SHORT_PAGE", "SHORT_PAGE_OR_NOT_FOUND")
    stamp = f"{key['testedAt']:%Y%m%d-%H%M%S}z"
    suffix = capability["job"]["suffix"]

    def call(cursor: str, file_name: str) -> tuple[dict, dict | None]:
        status, content_type, body = post_ozon(path, endpoint["probe_body"](cursor), client_id, api_key, context)
        private_write(pilot.evidence_dir / file_name, body)
        try:
            answer = json.loads(body) if content_type == "application/json" else None
        except ValueError:
            answer = None
        return {"file": file_name, "sha256": hashlib.sha256(body).hexdigest(), "status": status,
                "contentType": content_type}, (answer if isinstance(answer, dict) else None)

    pages, cursor, end_signal, total_records, follow_up = [], "", None, 0, None
    for index in range(MAXIMUM_PROBE_PAGES):
        if index:
            time.sleep(PROBE_PAUSE_SECONDS)
        page, answer = call(cursor, f"{suffix}-{stamp}-p{index:03d}.json")
        pages.append(page)
        if page["status"] == 404 and index > 0 and rule == "SHORT_PAGE_OR_NOT_FOUND":
            end_signal = "NOT_FOUND_AFTER_CURSOR"
            break
        if page["status"] != 200 or answer is None:
            detail = f" code={answer.get('code')} message={answer.get('message')}" if answer else ""
            print(f"POST {path} page {index + 1} -> HTTP {page['status']}{detail}; nothing recorded")
            return None
        records = answer.get(endpoint["records_key"])
        token = answer.get(endpoint["token_key"])
        if not isinstance(records, list) or not (token is None or isinstance(token, str)):
            print(f"page {index + 1} does not have the documented shape ({endpoint['records_key']}[], "
                  f"{endpoint['token_key']}); nothing recorded")
            return None
        page["records"] = len(records)
        page["token"] = "null" if token is None else ("empty" if token == "" else "present")
        total_records += len(records)
        if short_rule and len(records) < PAGE_SIZE:
            end_signal = "SHORT_PAGE"
            # Evidence that the short page really was the last one: the next
            # request must answer "not found" or no records at all.
            if token:
                time.sleep(PROBE_PAUSE_SECONDS)
                follow_up, after = call(token, f"{suffix}-{stamp}-after.json")
                more = after.get(endpoint["records_key"]) if after else None
                follow_up["records"] = len(more) if isinstance(more, list) else None
                if follow_up["status"] == 200 and follow_up["records"]:
                    print("a short page was followed by more records; the short-page rule would lose "
                          "data, nothing recorded")
                    return None
            break
        if endpoint["token_key"] not in answer:
            print(f"page {index + 1} has no {endpoint['token_key']}; nothing recorded")
            return None
        if token is None:
            end_signal = "JSON_NULL"
        elif not records:
            end_signal = "EMPTY_RECORDS"
        elif token == "":
            end_signal = "EMPTY_TOKEN"
        if end_signal:
            break
        cursor = token
    if end_signal is None:
        print(f"the listing did not end within {MAXIMUM_PROBE_PAGES} pages; nothing recorded")
        return None
    # The backend ends a listing on JSON null always, and on the other signals
    # only when the endpoint's recorded rule names them.
    accepted = {"JSON_NULL",
                *({"EMPTY_RECORDS"} if rule in ("EMPTY_RECORDS", "EMPTY_TOKEN_OR_RECORDS") else ()),
                *({"EMPTY_TOKEN"} if rule in ("EMPTY_TOKEN", "EMPTY_TOKEN_OR_RECORDS") else ()),
                *({"SHORT_PAGE"} if short_rule else ()),
                *({"NOT_FOUND_AFTER_CURSOR"} if rule == "SHORT_PAGE_OR_NOT_FOUND" else ())}
    if end_signal not in accepted:
        print(f"the listing ended with {end_signal}, which the registered rule {rule} does not accept")
        return None
    after_note = ""
    if follow_up is not None:
        after_note = f"; the next request was answered HTTP {follow_up['status']}"
    print(f"POST {path}: {len(pages)} pages, {total_records} records, ended with {end_signal}{after_note}")

    bundle = {"capability": capability["code"], "endpoint": endpoint["code"], "path": path,
              "testedAt": iso(key["testedAt"]), "pageSize": PAGE_SIZE, "endRule": rule,
              "endSignal": end_signal, "records": total_records, "pages": pages,
              "followUp": follow_up}
    bundle_name = f"{capability['job']['suffix']}-{stamp}-bundle.json"
    data = (json.dumps(bundle, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    private_write(pilot.evidence_dir / bundle_name, data)
    return bundle_name, hashlib.sha256(data).hexdigest()


def command_probe(args: argparse.Namespace) -> int:
    pilot = pilot_from(args)
    capability = capability_from(args)
    client_id = read_secret(pilot, pilot.client_id_file, "Client-Id")
    api_key = read_secret(pilot, pilot.api_key_file, "Api-Key")
    context = tls_context(args.ca_file)

    key = check_key(pilot, args, client_id, api_key, context)
    if key is None:
        return 1
    if capability["endpoint"]["definition"]["path_template"] == ROLES_PATH:
        evidence_name, evidence_digest = key["rolesFile"], key["rolesDigest"]
    else:
        probed = probe_pages(pilot, capability, key, client_id, api_key, context)
        if probed is None:
            return 1
        evidence_name, evidence_digest = probed
    del api_key

    valid_until = min(key["testedAt"] + EVIDENCE_WINDOW, key["keyExpires"])
    manifest = {
        "pilot": pilot.code,
        "capability": capability["code"],
        "testedAt": iso(key["testedAt"]),
        "validUntil": iso(valid_until),
        "keyExpiresAt": iso(key["keyExpires"]),
        "roles": [{"name": name, "methods": len(methods)} for name, methods in key["granted"].items()],
        "writeRolesAccepted": sorted(key["writers"]),
        "evidenceClass": "REAL_ACCOUNT",
        "accountEvidenceRef": pilot.evidence_ref(evidence_name),
        "accountEvidenceSha256": evidence_digest,
        "officialSourceUrl": OFFICIAL_SOURCE_URL,
        "officialSourceSha256": key["sourceDigest"],
        "officialSourceFile": str(key["sourceFile"]),
    }
    private_write(pilot.manifest(capability), (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode())
    print(f"evidence recorded, valid until {iso(valid_until)}; summary kept at {pilot.manifest(capability)}")
    return 0


def load_manifest(pilot: Pilot, capability: dict) -> dict:
    try:
        manifest = json.loads(pilot.manifest(capability).read_text(encoding="utf-8"))
    except FileNotFoundError:
        sys.exit(f"no probe evidence at {pilot.manifest(capability)}; run the probe for this capability first")
    if parse_instant(manifest["validUntil"]) <= utc_now():
        sys.exit(f"the probe evidence expired at {manifest['validUntil']}; run the probe again")
    return manifest


# --- Maintenance API --------------------------------------------------------------

class Admin:
    """The loopback maintenance API."""

    def __init__(self, base: str, operator: str) -> None:
        self.base = base.rstrip("/") + "/api/v1/admin/metadata"
        self.operator = operator

    def call(self, method: str, path: str, body: dict | None = None) -> tuple[int, object]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if method != "GET":
            request.add_header("Content-Type", "application/json")
            request.add_header("X-Operator", self.operator)
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
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

    def find(self, path: str, params: dict, match) -> dict | None:
        """Page through a code-ordered list and return the first item ``match`` accepts."""
        after = None
        seen: set[str] = set()
        while True:
            query = dict(params, limit=50, **({"afterCode": after} if after else {}))
            items = self.require("GET", f"{path}?{urllib.parse.urlencode(query)}", None, 200)
            for item in items:
                if match(item):
                    return item
            if len(items) < 50:
                return None
            last = items[-1]
            after = (last.get("account") or last).get("code") or last.get("capabilityCode") \
                or last.get("endpointCode")
            if not after or after in seen:
                return None
            seen.add(after)


def only(items: list, what: str, code: str | None, key: str = "code") -> dict:
    """The item with this code, or the single item when no code is given."""
    if code:
        found = [item for item in items if item.get(key) == code]
        if not found:
            sys.exit(f"no {what} with code {code}")
        return found[0]
    if len(items) != 1:
        sys.exit(f"there are {len(items)} {what}s; name one with its --{what.replace(' ', '-')}-code")
    return items[0]


def organization(admin: Admin, code: str | None) -> dict:
    return only(admin.require("GET", "/organizations?limit=50", None, 200), "organization", code)


def pilot_account(admin: Admin, pilot: Pilot, organization_id: str) -> dict | None:
    return admin.find("/marketplace-accounts", {"organizationId": organization_id},
                      lambda item: item.get("code") == pilot.account_code)


def find_capability(admin: Admin, capability: dict) -> dict | None:
    return admin.find("/capabilities", {"platformCode": PLATFORM},
                      lambda item: item.get("capabilityCode") == capability["code"])


def find_endpoint(admin: Admin, capability: dict) -> dict | None:
    return admin.find("/endpoints", {"platformCode": PLATFORM},
                      lambda item: item.get("endpointCode") == capability["endpoint"]["code"])


def find_job(admin: Admin, pilot: Pilot, capability: dict, account_id: str) -> dict | None:
    jobs = admin.require("GET", f"/ingestion-jobs?marketplaceAccountId={account_id}", None, 200)
    return next((j for j in jobs if j.get("jobCode") == pilot.job_code(capability)), None)


def command_setup(args: argparse.Namespace) -> int:
    pilot = pilot_from(args)
    capability = capability_from(args)
    manifest = load_manifest(pilot, capability)
    client_id = read_secret(pilot, pilot.client_id_file, "Client-Id")
    if not (pilot.api_key_file.is_file() and pilot.api_key_file.stat().st_size > 0):
        sys.exit(f"Api-Key: {pilot.api_key_file} does not exist")
    admin = Admin(args.api, args.operator)
    now = utc_now()
    result: dict[str, object] = {}

    org = organization(admin, args.organization_code)
    legal = only(admin.require("GET", f"/legal-entities?organizationId={org['id']}&limit=50", None, 200),
                 "legal entity", args.legal_entity_code)

    account = pilot_account(admin, pilot, org["id"])
    if account is None:
        account = admin.require("POST", "/marketplace-accounts", {
            "legalEntityId": legal["id"], "platformCode": PLATFORM, "code": pilot.account_code,
            "displayName": args.display_name, "nativeAccountKey": client_id}, 201)
        result["account"] = "registered"
    elif account.get("nativeAccountKey") != client_id:
        sys.exit(f"account {pilot.account_code} exists with a different Client-Id; "
                 "update it on purpose through PUT /marketplace-accounts/{id} first")
    else:
        result["account"] = "already registered"
    del client_id

    store = admin.find("/stores", {"organizationId": org["id"]},
                       lambda item: item.get("code") == pilot.store_code)
    if store is None:
        store = admin.require("POST", "/stores", {
            "marketplaceAccountId": account["id"], "code": pilot.store_code,
            "displayName": args.display_name, "timezone": "Europe/Moscow", "currencyCode": "RUB"}, 201)
        result["store"] = "registered"
    else:
        result["store"] = "already registered"

    credentials = admin.require("GET", f"/credentials?marketplaceAccountId={account['id']}&limit=50",
                                None, 200)
    credential = next((c for c in credentials
                       if (c.get("credential") or c).get("code") == pilot.credential_code), None)
    if credential is None:
        credential = admin.require("POST", "/credentials", {
            "marketplaceAccountId": account["id"], "code": pilot.credential_code,
            "displayName": "Ozon Seller API 只读 key", "purposeCode": "READ", "scopeMode": "ACCOUNT",
            "secretReference": pilot.secret_reference, "effectiveFrom": iso(now),
            "expiresAt": manifest["keyExpiresAt"], "custodianLabel": OWNER_LABEL, "storeIds": []}, 201)
        result["credential"] = f"registered, expires {manifest['keyExpiresAt']}"
    else:
        result["credential"] = "already registered"

    reader = admin.find("/service-accounts", {"organizationId": org["id"]},
                        lambda item: (item.get("account") or item).get("code") == pilot.service_account_code)
    if reader is None:
        reader = admin.require("POST", "/service-accounts", {
            "organizationId": org["id"], "code": pilot.service_account_code,
            "displayName": "Ozon 试点只读采集", "purpose": "Read the Ozon pilot account through the Seller API",
            "ownerLabel": OWNER_LABEL, "expiresAt": iso(now + timedelta(days=365))}, 201)
        result["serviceAccount"] = "registered"
    else:
        result["serviceAccount"] = "already registered"
    reader = reader.get("account") or reader

    grants = admin.require("GET", f"/scope-grants?serviceAccountId={reader['id']}&limit=50", None, 200)
    if not any(g.get("permissionCode") == "READ" and g.get("resourceType") == "MARKETPLACE_ACCOUNT"
               and g.get("resourceId") == account["id"] and g.get("status") == "ACTIVE" for g in grants):
        admin.require("POST", "/scope-grants", {
            "serviceAccountId": reader["id"], "permissionCode": "READ",
            "resourceType": "MARKETPLACE_ACCOUNT", "resourceId": account["id"],
            "effectiveFrom": iso(now), "effectiveTo": None,
            "reason": "Ozon pilot read-only acquisition"}, 201)
        result["scopeGrant"] = "granted READ on the account"
    else:
        result["scopeGrant"] = "already granted"

    registered = find_capability(admin, capability)
    if registered is None:
        registered = admin.require("POST", "/capabilities", {
            "platformCode": PLATFORM, "capabilityCode": capability["code"],
            "displayName": capability["display"], "description": capability["description"],
            "appliesTo": "MARKETPLACE_ACCOUNT", "readWriteClass": "READ",
            "subscriptionRequired": "NO", "ownerLabel": OWNER_LABEL}, 201)
        result["capability"] = f"{capability['code']} registered"
    else:
        result["capability"] = f"{capability['code']} already registered"

    spec = capability["endpoint"]
    definition = spec["definition"]
    endpoint = find_endpoint(admin, capability)
    if endpoint is None:
        endpoint = admin.require("POST", "/endpoints", {
            "platformCode": PLATFORM, "endpointCode": spec["code"], "apiVersion": spec["api_version"],
            "httpMethod": definition["http_method"], "pathTemplate": definition["path_template"],
            "capabilityId": registered["id"], "readWriteClass": "READ",
            "paginationModel": definition["pagination_model"],
            "rateLimitPerMinute": definition["rate_limit_per_minute"], "rateLimitNote": spec["rate_note"],
            "quotaNote": None, "idempotencySupport": "YES", "lateDataBehavior": None,
            "freshnessExpectation": spec["freshness"], "businessKeyNote": None,
            "schemaVersion": spec["schema_version"], "ownerLabel": OWNER_LABEL}, 201)
        result["endpoint"] = f"{spec['code']} registered"
    else:
        result["endpoint"] = f"{spec['code']} already registered"

    job = find_job(admin, pilot, capability, account["id"])
    if job is None:
        job = admin.require("POST", "/ingestion-jobs", {
            "marketplaceAccountId": account["id"], "serviceAccountId": reader["id"],
            "endpointId": endpoint["id"], "storeId": store["id"],
            "datasetKind": capability["job"]["dataset"], "jobCode": pilot.job_code(capability),
            "displayName": capability["job"]["display"]}, 201)
        result["job"] = f"{pilot.job_code(capability)} registered"
    else:
        result["job"] = f"{pilot.job_code(capability)} already registered"

    mapping = capability["mapping"]
    if mapping is not None:
        existing = [m for m in admin.require("GET", "/normalization-mappings", None, 200)
                    if m.get("platformCode") == PLATFORM and m.get("datasetKind") == mapping["dataset"]]
        same = next((m for m in existing if m.get("mappingVersion") == mapping["version"]), None)
        if same is None:
            live = [m for m in existing if m.get("status") == "ACTIVE"]
            if live:
                sys.exit(f"another live {PLATFORM}/{mapping['dataset']} mapping exists "
                         f"(version {live[0].get('mappingVersion')}); retire it on purpose first")
            created = admin.require("POST", "/normalization-mappings", {
                "platformCode": PLATFORM, "datasetKind": mapping["dataset"],
                "mappingVersion": mapping["version"], "recordPointer": mapping["record_pointer"],
                "fieldPointers": mapping["fields"], "ownerLabel": OWNER_LABEL}, 201)
            same = {"id": created["id"], "verificationState": "UNVERIFIED", "version": 0}
            result["mapping"] = f"{PLATFORM}/{mapping['dataset']} v{mapping['version']} registered"
        else:
            result["mapping"] = f"{PLATFORM}/{mapping['dataset']} v{mapping['version']} already registered"
        if same.get("verificationState") != "VERIFIED":
            admin.require("POST", f"/normalization-mappings/{same['id']}/verification", {
                "evidenceRef": manifest["accountEvidenceRef"],
                "verifiedSourceTitle": f"Ozon Seller API {spec['definition']['path_template']} answers "
                                       f"of the pilot account, {manifest['testedAt']}",
                "expectedVersion": same.get("version", 0)}, 204)
            result["mapping"] += ", verified against the probe answers"

    result.update({"organizationId": org["id"], "marketplaceAccountId": account["id"],
                   "storeId": store["id"], "capabilityId": registered["id"],
                   "endpointId": endpoint["id"], "jobId": job["id"]})
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("next: the registry rows are still UNVERIFIED; run the verify step with two Owners")
    return 0


# --- reviewer ---------------------------------------------------------------------

def command_reviewer(args: argparse.Namespace) -> int:
    admin = Admin(args.api, args.operator)
    org = organization(admin, args.organization_code)
    providers = [p for p in admin.require("GET", "/identity-providers", None, 200)
                 if p.get("status") == "ACTIVE"]
    provider = only(providers, "identity provider", args.identity_provider_code)
    user = admin.require("POST", "/users", {
        "organizationId": org["id"], "identityProviderId": provider["id"],
        "externalSubject": args.subject, "loginHint": args.login_hint,
        "displayName": args.display_name}, 201)
    admin.require("POST", f"/users/{user['id']}/roles", {"role": "OWNER"}, 201)
    admin.require("POST", f"/users/{user['id']}/scope-grants", {
        "action": "KILL_SWITCH_OPERATE", "resourceType": "ORGANIZATION", "resourceId": org["id"]}, 201)
    print(json.dumps({"userId": user["id"], "loginHint": args.login_hint, "role": "OWNER",
                      "grants": ["KILL_SWITCH_OPERATE"]}, ensure_ascii=False, indent=2))
    return 0


# --- Console API ------------------------------------------------------------------

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def sign_in(args: argparse.Namespace, label: str) -> tuple[str, str]:
    """Walk the console's PKCE sign-in for one person; returns (username, access token)."""
    username = input(f"{label} username: ").strip()
    password = getpass.getpass(f"{label} password: ")
    ca_file = args.oidc_ca_file or (str(LOCAL_OIDC_CA) if LOCAL_OIDC_CA.is_file() else None)
    context = tls_context(ca_file)
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context),
                                         urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
                                         NoRedirect)
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    query = urllib.parse.urlencode({
        "response_type": "code", "client_id": args.client_id, "redirect_uri": args.redirect_uri,
        "scope": "openid profile marketops.console", "state": secrets.token_urlsafe(16),
        "nonce": secrets.token_urlsafe(16), "audience": args.audience, "code_challenge": challenge,
        "code_challenge_method": "S256", "acr_values": "mfa"})
    issuer = args.issuer.rstrip("/")
    page = opener.open(f"{issuer}/protocol/openid-connect/auth?{query}", timeout=30).read().decode()
    form = re.search(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"', page)
    if form is None:
        sys.exit(f"{label}: the sign-in page did not show the login form")
    body = urllib.parse.urlencode({"username": username, "password": password, "credentialId": ""}).encode()
    del password
    try:
        opener.open(html.unescape(form.group(1)), data=body, timeout=30)
        sys.exit(f"{label}: sign-in failed for {username} (wrong password or an extra step required)")
    except urllib.error.HTTPError as redirect:
        location = redirect.headers.get("Location") or ""
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("code", [None])[0]
    if not code:
        sys.exit(f"{label}: sign-in for {username} did not return an authorization code")
    token = json.loads(opener.open(f"{issuer}/protocol/openid-connect/token", data=urllib.parse.urlencode({
        "grant_type": "authorization_code", "code": code, "client_id": args.client_id,
        "redirect_uri": args.redirect_uri, "code_verifier": verifier}).encode(), timeout=30).read())
    return username, token["access_token"]


class Console:
    """The authenticated registry-verification console API, as one signed-in person."""

    def __init__(self, base: str, token: str) -> None:
        self.base = base.rstrip("/") + "/api/v1/console/registry-verification"
        self.token = token

    def call(self, method: str, path: str, body: dict | None = None, *ok: int) -> object:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        request.add_header("Authorization", "Bearer " + self.token)
        if body is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
                status, payload = response.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                status, payload = error.code, (json.loads(raw) if raw else None)
            except json.JSONDecodeError:
                status, payload = error.code, None
        if status not in ok:
            title = payload.get("title") if isinstance(payload, dict) else None
            sys.exit(f"{method} {path} -> HTTP {status} {title or ''}".rstrip())
        return payload


def differs(row: dict | None, definition: dict) -> bool:
    return row is None or any(row.get(key) != value for key, value in definition.items())


def command_verify(args: argparse.Namespace) -> int:
    pilot = pilot_from(args)
    capability = capability_from(args)
    manifest = load_manifest(pilot, capability)
    source = Path(manifest["officialSourceFile"])
    if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != manifest["officialSourceSha256"]:
        sys.exit(f"the official source kept at {source} changed since the probe; run the probe again")
    admin = Admin(args.api, args.operator)
    org = organization(admin, args.organization_code)
    account = pilot_account(admin, pilot, org["id"])
    registered = find_capability(admin, capability)
    endpoint = find_endpoint(admin, capability)
    if account is None or registered is None or endpoint is None:
        sys.exit("the pilot account, capability or endpoint is missing; run the setup step first")

    print("Two different Owners are needed: one submits the evidence, the other approves it.")
    submitter_name, submitter_token = sign_in(args, "Submitting Owner")
    reviewer_name, reviewer_token = sign_in(args, "Reviewing Owner")
    if submitter_name == reviewer_name:
        sys.exit("the reviewing Owner must be a different person from the submitting Owner")
    submitter = Console(args.api, submitter_token)
    reviewer = Console(args.api, reviewer_token)
    scope = f"/accounts/{account['id']}/capabilities/{registered['id']}"

    snapshot = submitter.call("GET", scope, None, 200)["snapshot"]
    profile = snapshot.get("profile")
    if differs(profile, PROFILE_DEFINITION):
        if profile is not None and profile.get("verification_state") == "VERIFIED":
            sys.exit("the verified Ozon profile differs from this script; open a registry revision first")
        submitter.call("POST", f"{scope}/draft", {"kind": "PROFILE", "id": None,
                       "expectedVersion": -1 if profile is None else profile["version"],
                       "definition": PROFILE_DEFINITION}, 201)
        print("drafted the Ozon API profile")
    header_ids = []
    for definition in HEADER_DEFINITIONS:
        row = next((h for h in snapshot.get("headers") or []
                    if str(h.get("header_name", "")).lower() == definition["header_name"].lower()), None)
        if differs(row, definition):
            if row is not None and row.get("verification_state") == "VERIFIED":
                sys.exit(f"the verified {definition['header_name']} header differs; open a registry revision first")
            created = submitter.call("POST", f"{scope}/draft", {
                "kind": "HEADER", "id": None if row is None else row["id"],
                "expectedVersion": -1 if row is None else row["version"], "definition": definition}, 201)
            header_ids.append(created["id"])
            print(f"drafted the {definition['header_name']} header")
        else:
            header_ids.append(row["id"])
    wanted = capability["endpoint"]["definition"]
    row = next((e for e in snapshot.get("endpoints") or [] if e.get("id") == endpoint["id"]), None)
    if differs(row, wanted):
        if row is None:
            sys.exit(f"endpoint {capability['endpoint']['code']} is not under capability {capability['code']}")
        if row.get("verification_state") == "VERIFIED":
            sys.exit(f"the verified {capability['endpoint']['code']} endpoint differs; "
                     "open a registry revision first")
        submitter.call("POST", f"{scope}/draft", {"kind": "ENDPOINT", "id": endpoint["id"],
                       "expectedVersion": row["version"], "definition": wanted}, 201)
        print(f"drafted the {capability['endpoint']['code']} endpoint")

    digest = submitter.call("GET", scope, None, 200)["digest"]
    evidence = {key: manifest[key] for key in ("officialSourceUrl", "officialSourceSha256",
                                               "accountEvidenceRef", "accountEvidenceSha256",
                                               "evidenceClass", "testedAt", "validUntil")}
    case = submitter.call("POST", f"{scope}/cases", {"endpointIds": [endpoint["id"]],
                          "authHeaderIds": header_ids, "evidence": evidence,
                          "expectedDigest": digest}, 201)
    print(f"{submitter_name} submitted case {case['id']}")
    submitted = reviewer.call("GET", f"/cases/{case['id']}", None, 200)
    reviewer.call("POST", f"/cases/{case['id']}/review",
                  {"expectedVersion": submitted["version"], "approve": True}, 204)
    approved = reviewer.call("GET", f"/cases/{case['id']}", None, 200)
    print(json.dumps({"capability": capability["code"], "case": case["id"], "state": approved.get("state"),
                      "currentEvidence": approved.get("currentEvidence"),
                      "validUntil": approved.get("validUntil"), "reviewedBy": reviewer_name},
                     ensure_ascii=False, indent=2))
    return 0 if approved.get("state") == "APPROVED" else 1


# --- run and normalize --------------------------------------------------------------

def pilot_job(args: argparse.Namespace) -> tuple[Admin, Pilot, dict, dict]:
    pilot = pilot_from(args)
    capability = capability_from(args)
    admin = Admin(args.api, args.operator)
    org = organization(admin, args.organization_code)
    account = pilot_account(admin, pilot, org["id"])
    if account is None:
        sys.exit("the pilot account is missing; run the setup step first")
    job = find_job(admin, pilot, capability, account["id"])
    if job is None:
        sys.exit(f"job {pilot.job_code(capability)} is missing; run the setup step for this capability first")
    return admin, pilot, capability, job


def command_run(args: argparse.Namespace) -> int:
    admin, _, _, job = pilot_job(args)
    run = admin.require("POST", f"/ingestion-jobs/{job['id']}/runs", {}, 201)
    outcome = admin.require("POST", f"/ingestion-runs/{run['id']}/execution", {}, 200)
    print(json.dumps(outcome, ensure_ascii=False, indent=2))
    return 0 if outcome.get("run", {}).get("state") == "SUCCEEDED" else 1


def command_normalize(args: argparse.Namespace) -> int:
    admin, _, capability, job = pilot_job(args)
    if capability["mapping"] is None:
        sys.exit(f"capability {capability['code']} produces no facts to normalize")
    summary = admin.require("POST", f"/ingestion-jobs/{job['id']}/normalization-passes", {}, 200)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary.get("lastReason") == "NOTHING_TO_PROCESS" else 1


# --- CLI ------------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)

    def common(sub: argparse.ArgumentParser, backend: bool = True) -> None:
        sub.add_argument("--pilot", default="pilot", help="short code of the pilot account (default: pilot)")
        sub.add_argument("--capability", choices=sorted(CAPABILITIES), default="connectivity",
                         help="which capability to work on (default: connectivity)")
        sub.add_argument("--secret-mount", help="secret mount; default: MARKETOPS_SECRET_MOUNT_DIRECTORY "
                                                f"or {DEFAULT_SECRET_MOUNT}")
        sub.add_argument("--evidence-root", help=f"evidence directory; default: {DEFAULT_EVIDENCE_ROOT}")
        if backend:
            sub.add_argument("--api", default="http://127.0.0.1:8080", help="backend base URL")
            sub.add_argument("--operator", default="owner-local", help="operator recorded in the audit")
            sub.add_argument("--organization-code", help="organization code when there is more than one")

    probe = commands.add_parser("probe", help="check the key and keep the capability's evidence")
    common(probe, backend=False)
    probe.add_argument("--ca-file", help="trusted root bundle for api-seller.ozon.ru")
    probe.add_argument("--official-source-file", help="the OpenAPI document saved from "
                                                      f"{OFFICIAL_SOURCE_URL} in a browser")
    probe.add_argument("--allow-write-roles", action="store_true",
                       help="record evidence even though the key has roles that can change the store")
    probe.set_defaults(handler=command_probe)

    setup = commands.add_parser("setup", help="register the account, credential and capability rows")
    common(setup)
    setup.add_argument("--legal-entity-code", help="legal entity code when there is more than one")
    setup.add_argument("--display-name", default="Ozon 试点店铺", help="account and store display name")
    setup.set_defaults(handler=command_setup)

    reviewer = commands.add_parser("reviewer", help="make a second Keycloak user an Owner who can review")
    reviewer.add_argument("--api", default="http://127.0.0.1:8080", help="backend base URL")
    reviewer.add_argument("--operator", default="owner-local", help="operator recorded in the audit")
    reviewer.add_argument("--organization-code", help="organization code when there is more than one")
    reviewer.add_argument("--identity-provider-code", help="identity provider code when there is more than one")
    reviewer.add_argument("--subject", required=True, help="the Keycloak user's ID (its sub)")
    reviewer.add_argument("--login-hint", default="owner-reviewer", help="login hint for the profile")
    reviewer.add_argument("--display-name", default="Local Owner (reviewer)", help="profile display name")
    reviewer.set_defaults(handler=command_reviewer)

    verify = commands.add_parser("verify", help="submit and approve the probe evidence with two Owners")
    common(verify)
    verify.add_argument("--issuer", default=DEFAULT_ISSUER, help="OIDC issuer")
    verify.add_argument("--client-id", default=DEFAULT_CLIENT_ID, help="console OIDC client")
    verify.add_argument("--redirect-uri", default=DEFAULT_REDIRECT_URI, help="registered console redirect URI")
    verify.add_argument("--audience", default=DEFAULT_AUDIENCE, help="API audience")
    verify.add_argument("--oidc-ca-file", help=f"CA for the issuer; default: {LOCAL_OIDC_CA} when present")
    verify.set_defaults(handler=command_verify)

    run = commands.add_parser("run", help="queue and execute one manual run of the capability's job")
    common(run)
    run.set_defaults(handler=command_run)

    normalize = commands.add_parser("normalize", help="normalize what the capability's job stored")
    common(normalize)
    normalize.set_defaults(handler=command_normalize)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
