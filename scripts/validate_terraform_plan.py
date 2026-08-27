#!/usr/bin/env python3
"""Check actual Terraform mock-plan values and the pinned provider schema.

No provider API, credentials, apply, or state mutation is used. A plan is not
evidence of real-account provisioning, reachability or notification delivery.
"""
import hashlib
import base64
import importlib.util
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def resources(plan):
    changes = plan["resource_changes"]
    require(all(r["change"]["actions"] == ["create"] for r in changes), "Only a fresh synthetic plan is accepted")
    return {r["address"]: r["change"]["after"] for r in changes}


def cloud_config(instance):
    userdata = instance["instance_template"][0]["metadata"]["user-data"]
    require(userdata.startswith("#cloud-config\n{"), "Expected rendered JSON cloud-init")
    config = json.loads(userdata.partition("\n")[2])
    paths = [f["path"] for f in config["write_files"]]
    require(len(set(paths)) == len(paths), "Duplicate cloud-init file paths")
    return config


def cloud_manifest(instance):
    manifests = [json.loads(f["content"]) for f in cloud_config(instance)["write_files"]
                 if f["path"] == "/etc/marketops/runtime.json"]
    require(len(manifests) == 1, "Exactly one runtime manifest is required")
    return manifests[0]


def validate_telemetry(instance, environment, role, folder_id):
    config = cloud_config(instance)
    files = {f["path"]: f for f in config["write_files"]}
    for target, source, permissions in [
        ("/usr/local/lib/marketops-telemetry.py", "telemetry.py", "0755"),
        ("/etc/systemd/system/marketops-telemetry.service", "marketops-telemetry.service", "0644"),
        ("/etc/systemd/system/marketops-telemetry.timer", "marketops-telemetry.timer", "0644"),
    ]:
        require(target in files, "Telemetry runtime file missing")
        entry = files[target]
        require(entry["owner"] == "root:root" and entry["permissions"] == permissions,
                "Telemetry file ownership or permissions drift")
        content = base64.b64decode(entry["content"], validate=True) if entry.get("encoding") == "b64" else entry["content"].encode()
        require(content == (ROOT / "infra/yandex/runtime" / source).read_bytes(), "Telemetry artifact bytes drift")
    target = "/etc/marketops/telemetry.json"
    require(target in files, "Telemetry configuration missing")
    entry = files[target]
    require(entry["owner"] == "root:root" and entry["permissions"] == "0644", "Telemetry configuration permissions drift")
    require(json.loads(entry["content"]) == {"folder_id": folder_id, "environment": environment, "role": role},
            "Telemetry configuration contains an unintended field or identity")
    require(["systemctl", "enable", "--now", "marketops-telemetry.timer"] in config["runcmd"],
            "Telemetry timer is not enabled")


def validate_environment(plan, environment, runtime_expected=True):
    rows = resources(plan)
    row = rows.__getitem__
    db = row("module.database.yandex_mdb_postgresql_cluster.this")
    require(db["deletion_protection"] == (environment == "production"), "Database deletion protection drift")
    require(len(db["host"]) == 3 and all(h["assign_public_ip"] is False for h in db["host"]), "Database must span three private hosts")
    require(db["config"][0]["backup_retain_period_days"] >= 7, "PITR retention is too short")
    require(db["config"][0]["version"] == "17", "Accepted Amendment-001 pins managed PostgreSQL 17")
    extensions = row("module.database.yandex_mdb_postgresql_database.this")["extension"]
    require(len(extensions) == 2 and {e["name"] for e in extensions}
            == {"btree_gist", "pgcrypto"}, "Provider-managed extension identity drift")
    for role in ["application", "migration"]:
        user = row("module.database.yandex_mdb_postgresql_user." + role)
        require(user.get("password") is None and user.get("password_wo") is None, "Credential persisted in planned values")
        require(user["password_wo_version"] >= 1, "Credential rotation version is missing")
    schema = plan["provider_schemas"]["registry.terraform.io/yandex-cloud/yandex"]["resource_schemas"]
    require(schema["yandex_mdb_postgresql_user"]["block"]["attributes"]["password_wo"]["write_only"], "Pinned provider does not guarantee write-only persistence")
    group = lambda name: row('module.network.yandex_vpc_security_group.groups["' + name + '"]')["id"]
    require(db["security_group_ids"] == [group("database")], "Database security group drift")
    route_id = row("module.network.yandex_vpc_route_table.private")["id"]
    for zone in ["a", "b", "d"]:
        subnet = row('module.network.yandex_vpc_subnet.private["ru-central1-' + zone + '"]')
        require(subnet["route_table_id"] == route_id, "A private subnet has no NAT route")
    for port in [8080, 8088]:
        outbound = row('module.network.yandex_vpc_security_group_rule.alb_to_application["' + str(port) + '"]')
        inbound = row('module.network.yandex_vpc_security_group_rule.application_from_alb["' + str(port) + '"]')
        require(outbound["security_group_binding"] == group("load-balancer") and outbound["security_group_id"] == group("application"), "ALB egress is not bound to the application group")
        require(inbound["security_group_binding"] == group("application") and inbound["security_group_id"] == group("load-balancer"), "Application ingress is not bound to ALB")
        require(outbound["port"] == port and inbound["port"] == port, "ALB/application port mismatch")
    for name, value in rows.items():
        if "yandex_vpc_security_group_rule." in name and value["direction"] == "ingress" and "0.0.0.0/0" in (value.get("v4_cidr_blocks") or []):
            require(value["port"] == 443 and value["security_group_binding"] == group("load-balancer"), "Public ingress outside ALB HTTPS")
        if "yandex_vpc_security_group_rule." in name and value["direction"] == "egress" and "0.0.0.0/0" in (value.get("v4_cidr_blocks") or []):
            require(value["port"] == 443 and value["protocol"] == "TCP", "Unbounded public egress")
    for role in ["application", "worker", "migration"]:
        ingress = row('module.network.yandex_vpc_security_group_rule.database_ingress["' + role + '"]')
        egress = row('module.network.yandex_vpc_security_group_rule.database_egress["' + role + '"]')
        require(ingress["port"] == 6432 and ingress["security_group_binding"] == group("database") and ingress["security_group_id"] == group(role), "Database admits an unintended source")
        require(egress["port"] == 6432 and egress["security_group_binding"] == group(role) and egress["security_group_id"] == group("database"), "Workload cannot reach the private database")
    if runtime_expected:
        for role, identity in [("application", "application"), ("worker", "acquisition")]:
            instance = row('module.workload[0].yandex_compute_instance_group.runtime["' + role + '"]')
            template = instance["instance_template"][0]
            require(instance["deletion_protection"] == (environment == "production"), "Instance group deletion protection drift")
            require(template["network_interface"][0]["nat"] is False, "A workload has a public address")
            require(template["network_interface"][0]["security_group_ids"] == [group(role)], "Workload security group drift")
            require(template["service_account_id"] == row('module.workload_identity.yandex_iam_service_account.roles["' + identity + '"]')["id"], "Workload identity drift")
            require(instance["deploy_policy"][0]["max_unavailable"] == 0, "Replacement may remove all healthy instances")
            require(instance["health_check"][0]["http_options"][0]["path"] == "/actuator/health/readiness", "Traffic does not wait for DB readiness")
            manifest = cloud_manifest(instance)
            spec = importlib.util.spec_from_file_location("runtime_bootstrap", ROOT / "infra/yandex/runtime/bootstrap.py")
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            module.validate(manifest)
            require("sslmode=verify-full" in manifest["environment"]["SPRING_DATASOURCE_URL"], "Database TLS hostname verification missing")
            validate_telemetry(instance, environment, role, db["folder_id"])
        host = row("module.workload[0].yandex_alb_virtual_host.this")
        routes = {r["name"]: r["http_route"][0] for r in host["route"]}
        require(host["route"][0]["name"] == "console-api" and host["route"][-1]["name"] == "console", "Public route precedence drift")
        require(set(routes) == {"console-api", "public-metadata", "deny-other-apis", "deny-actuator", "console"}, "Public route inventory drift")
        for name in ["deny-other-apis", "deny-actuator"]:
            require(routes[name]["direct_response_action"][0]["status"] == 404, "An internal API is publicly routed")
        require(routes["console-api"]["http_match"][0]["path"][0]["prefix"] == "/api/v1/console/", "Console API prefix drift")
        require(routes["public-metadata"]["http_match"][0]["http_method"] == ["GET"], "Public metadata permits writes")
        require(routes["public-metadata"]["http_match"][0]["path"][0]["exact"] == "/api/v1/meta/status", "Public metadata route drift")
        balancer = row("module.workload[0].yandex_alb_load_balancer.this")
        require(len(balancer["listener"]) == 1 and balancer["listener"][0]["endpoint"][0]["ports"] == [443], "Public non-HTTPS listener")
        require(len(balancer["listener"][0]["tls"][0]["default_handler"][0]["certificate_ids"]) == 1, "TLS certificate is missing")
    else:
        require(not any(re.match(r"module[.]workload(?:\[|[.])", address) for address in rows),
                "Foundation must not create runtime or public application ingress")
    bucket = row("module.object_storage.yandex_storage_bucket.evidence")
    require(bucket["versioning"][0]["enabled"] and not bucket["force_destroy"], "Raw object versions are not protected")
    require(all(v is False for v in bucket["anonymous_access_flags"][0].values()), "Raw object storage is public")
    require(bucket["server_side_encryption_configuration"][0]["rule"][0]["apply_server_side_encryption_by_default"][0]["sse_algorithm"] == "aws:kms", "Raw object encryption missing")
    retention = bucket["object_lock_configuration"][0]["rule"][0]["default_retention"][0]
    require(retention["mode"] == "COMPLIANCE" and retention["days"] >= 365, "Raw retention control weakened")
    policy = json.loads(row("module.object_storage.yandex_storage_bucket_policy.evidence")["policy"])
    require(any(s.get("Sid") == "WorkloadsCannotDeleteOrRelaxCustody" and s["Effect"] == "Deny" and "s3:DeleteObjectVersion" in s["Action"] for s in policy["Statement"]), "Workloads can relax custody")
    telemetry_members = set()
    for name, value in rows.items():
        if "yandex_resourcemanager_folder_iam_member." in name:
            require(value["role"] not in {"editor", "admin", "storage.editor", "storage.viewer", "storage.uploader", "lockbox.payloadViewer"}, "Overbroad folder-level runtime authority")
            if value["role"].startswith("monitoring."):
                require(value["role"] == "monitoring.editor" and value["folder_id"] == db["folder_id"],
                        "Telemetry role or folder drift")
                telemetry_members.add(value["member"])
        if "yandex_lockbox_secret_iam_binding." in name:
            migration = "serviceAccount:" + row('module.workload_identity.yandex_iam_service_account.roles["migration"]')["id"]
            require(migration not in value["members"] or value["members"] == [migration], "Migration secret shared with a runtime workload")
    require(telemetry_members == {"serviceAccount:" + row('module.workload_identity.yandex_iam_service_account.roles["' + identity + '"]')["id"]
                                  for identity in ["application", "acquisition"]}, "Telemetry identity grant drift")
    return {"environment": environment, "resource_count": len(rows), "network": "PASS", "runtime": "PASS" if runtime_expected else "NOT_CREATED_BEFORE_MIGRATION", "secret_plan_persistence": "ABSENT", "external_verification": "NOT_PERFORMED"}


def validate_bootstrap(plan):
    rows = resources(plan)
    bucket = rows["yandex_storage_bucket.state"]
    require(bucket["versioning"][0]["enabled"] and not bucket["force_destroy"], "State must retain versions")
    require(all(v is False for v in bucket["anonymous_access_flags"][0].values()), "State bucket is public")
    require(bucket["server_side_encryption_configuration"][0]["rule"][0]["apply_server_side_encryption_by_default"][0]["sse_algorithm"] == "aws:kms", "State KMS encryption missing")
    require(rows["yandex_ydb_database_serverless.locks"]["deletion_protection"], "State lock database is disposable")
    policy = json.loads(rows["yandex_storage_bucket_policy.state"]["policy"])
    require(any(s["Effect"] == "Deny" and "aws:SecureTransport" in s.get("Condition", {}).get("Bool", {}) for s in policy["Statement"]), "State accepts plaintext transport")
    require(any(s["Effect"] == "Deny" and "aws:userid" in s.get("Condition", {}).get("StringNotEquals", {}) for s in policy["Statement"]), "State is not restricted to its infrastructure identity")
    require("yandex_audit_trails_trail.state" in rows, "State access audit is missing")
    return {"environment": "bootstrap", "resource_count": len(rows), "state_custody": "PASS", "external_verification": "NOT_PERFORMED"}


def inspect(path, environment):
    raw = Path(path).read_text()
    for role in ["migration", "application"]:
        require("synthetic-only-" + role + "-never-real" not in raw, "Ephemeral credential leaked in plan artifact")
    events = [json.loads(line) for line in raw.splitlines() if line.strip()]
    summaries = [e["test_summary"] for e in events if e.get("type") == "test_summary"]
    require(len(summaries) == 1 and summaries[0]["status"] == "pass" and summaries[0]["passed"] > 0, "Terraform test did not pass")
    plans = {e["@testrun"]: e["test_plan"] for e in events if e.get("type") == "test_plan"}
    if environment == "bootstrap":
        require(len(plans) == 1, "Expected one state-bootstrap plan")
        result = validate_bootstrap(next(iter(plans.values())))
    else:
        require(set(plans) == {"foundation_plan", "unproven_runtime_rejected", "full_environment_plan"},
                "Foundation, rejected unproven runtime and migrated-runtime plans are all required")
        unproven = resources(plans["unproven_runtime_rejected"])
        require(not any("yandex_compute_instance_group" in address for address in unproven),
                "The rejected unproven plan must contain no launchable workload")
        refusals = [e["test_run"] for e in events if e.get("type") == "test_run"
                    and e["test_run"].get("run") == "unproven_runtime_rejected"
                    and e["test_run"].get("progress") == "complete"]
        require(len(refusals) == 1 and refusals[0].get("status") == "pass",
                "Terraform must confirm the expected missing-evidence validation failure")
        foundation = validate_environment(plans["foundation_plan"], environment, runtime_expected=False)
        result = validate_environment(plans["full_environment_plan"], environment)
        result["foundation"] = foundation
        result["unproven_runtime"] = "REFUSED_EXPECTED_MISSING_MIGRATION_EVIDENCE"
    result["plan_sha256"] = hashlib.sha256(raw.encode()).hexdigest()
    result["apply"] = "NOT_EXECUTED"
    return result


if __name__ == "__main__":
    print(json.dumps(inspect(sys.argv[1], sys.argv[2]), indent=2))
