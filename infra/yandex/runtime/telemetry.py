#!/usr/bin/env python3
"""Bounded host telemetry delivery. It never reads database or Marketplace secrets."""
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import socket
import sys
import urllib.parse
import urllib.request

SNAPSHOT_URL = "http://127.0.0.1:8080/actuator/operations"
TOKEN_URL = "http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token"
WRITE_URL = "https://monitoring.api.cloud.yandex.net/monitoring/v2/data/write"
SIGNALS = frozenset({"price_command_awaiting_operator", "price_command_readback_mismatch",
                     "ingestion_run_backlog_age_seconds", "price_command_gate_closed",
                     "raw_custody_write_failed", "database_readiness_failed"})
MAX_BYTES = 16384


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("telemetry redirect refused")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate telemetry field")
        result[key] = value
    return result


def send(url, headers, body=None):
    allowed = url in {SNAPSHOT_URL, TOKEN_URL} or url.startswith(WRITE_URL + "?folderId=")
    if not allowed:
        raise ValueError("telemetry destination refused")
    request = urllib.request.Request(url, headers=headers, data=body)
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=5) as response:
        if response.status != 200:
            raise ValueError("telemetry response refused")
        payload = response.read(MAX_BYTES + 1)
        if len(payload) > MAX_BYTES:
            raise ValueError("telemetry response too large")
    return json.loads(payload, object_pairs_hook=unique_object)


def deliver(config, transport=send, now=None, instance=None):
    if not isinstance(config, dict) or set(config) != {"folder_id", "environment", "role"}:
        raise ValueError("closed telemetry configuration required")
    if not isinstance(config["folder_id"], str) or not re.fullmatch(r"[a-z0-9]{20}", config["folder_id"]) \
            or config["environment"] not in {"staging", "production"} or config["role"] not in {"application", "worker"}:
        raise ValueError("telemetry identity refused")
    instance = socket.gethostname() if instance is None else instance
    if not re.fullmatch(r"[a-zA-Z0-9_.-]{1,63}", instance):
        raise ValueError("telemetry instance label refused")
    snapshot = transport(SNAPSHOT_URL, {})
    if not isinstance(snapshot, dict) or set(snapshot) != {"schemaVersion", "observedAt", "signals"} \
            or type(snapshot["schemaVersion"]) is not int or snapshot["schemaVersion"] != 1 \
            or not isinstance(snapshot["observedAt"], str):
        raise ValueError("telemetry snapshot refused")
    observed = datetime.fromisoformat(snapshot["observedAt"].replace("Z", "+00:00"))
    now = datetime.now(timezone.utc) if now is None else now
    if observed.tzinfo is None or not -15 <= (now - observed).total_seconds() <= 90:
        raise ValueError("telemetry snapshot stale")
    signals = snapshot["signals"]
    if not isinstance(signals, dict) or not set(signals) <= SIGNALS \
            or signals.get("database_readiness_failed") not in {0, 1} \
            or any(type(value) is not int or not 0 <= value <= 10**12 for value in signals.values()):
        raise ValueError("telemetry values refused")
    if signals["database_readiness_failed"] == 0 and set(signals) != SIGNALS:
        raise ValueError("healthy telemetry must include every signal")
    if signals["database_readiness_failed"] == 1 and set(signals) != {"database_readiness_failed"}:
        raise ValueError("unavailable database cannot claim healthy business signals")
    identity = transport(TOKEN_URL, {"Metadata-Flavor": "Google"})
    token = identity.get("access_token") if isinstance(identity, dict) else None
    if not isinstance(token, str) or not 1 <= len(token) <= 8192 or not re.fullmatch(r"[A-Za-z0-9._~-]+", token):
        raise ValueError("telemetry identity unavailable")
    metrics = [{"name": name, "type": "IGAUGE", "value": value} for name, value in sorted(signals.items())]
    body = json.dumps({"ts": snapshot["observedAt"], "labels": {"application": "marketops",
        "environment": config["environment"], "role": config["role"], "instance": instance}, "metrics": metrics}, separators=(",", ":")).encode()
    result = transport(WRITE_URL + "?" + urllib.parse.urlencode({"folderId": config["folder_id"], "service": "custom"}),
                       {"Authorization": "Bearer " + token, "Content-Type": "application/json"}, body)
    if not isinstance(result, dict) or str(result.get("writtenMetricsCount")) != str(len(metrics)) \
            or result.get("errorMessage") not in {None, ""}:
        raise ValueError("telemetry write incomplete")
    return {"metric_count": len(metrics), "database_available": signals["database_readiness_failed"] == 0}


def main():
    try:
        if len(sys.argv) != 2:
            raise ValueError("telemetry configuration required")
        payload = Path(sys.argv[1]).read_bytes()
        if len(payload) > 1024:
            raise ValueError("telemetry configuration too large")
        result = deliver(json.loads(payload, object_pairs_hook=unique_object))
        print(json.dumps({"event": "operational_telemetry_delivered", **result}))
        return 0
    except Exception:
        # HTTP error bodies, IAM tokens and internal addresses never enter logs.
        print('{"event":"operational_telemetry_failed"}', file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
