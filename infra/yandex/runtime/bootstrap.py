#!/usr/bin/env python3
"""VM-only bootstrap. Terraform contains reference metadata, never secret payloads.

Network destinations are constants; redirects and environment proxies are disabled.
Tests inject transport/process boundaries and never contact a real account.
"""
import json
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

MAX_RESPONSE = 1024 * 1024
IMAGE = re.compile(r"cr[.]yandex/[a-z0-9/_.-]+@sha256:[0-9a-f]{64}\Z")
IDENTIFIER = re.compile(r"[a-z0-9]{20}\Z")
CONFIG_FILES = frozenset({"config/spring.datasource.password", "config/marketops.object-storage.access-key-id"})
REQUIRED_FILES = CONFIG_FILES | {"credentials/object-storage/signing-key"}
ENVIRONMENT_KEYS = frozenset({
    "SPRING_PROFILES_ACTIVE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_ENABLED", "SPRING_CONFIG_IMPORT",
    "MARKETOPS_SECRET_MOUNT_DIRECTORY", "MARKETOPS_ENVIRONMENT", "MARKETOPS_OBJECT_STORAGE_ENDPOINT",
    "MARKETOPS_OBJECT_STORAGE_REGION", "MARKETOPS_OBJECT_STORAGE_BUCKET", "MARKETOPS_OBJECT_STORAGE_CREDENTIAL_REFERENCE",
    "MARKETOPS_OIDC_ISSUER_URI", "MARKETOPS_OIDC_JWK_SET_URI", "MARKETOPS_OIDC_AUDIENCE",
    "MARKETOPS_ACQUISITION_SCHEDULER_ENABLED", "MARKETOPS_PRICE_WRITE_WORKER_ENABLED"
})
ROLE_ASSERTIONS = frozenset({"serverMajorIs17", "currentUserIsMigration", "runtimeRolesAreNonPrivileged",
    "applicationCannotCreateDatabaseObjects", "applicationIsNotMigrationMember", "managedExtensionsNotOwnedByRuntimeRoles",
    "providerSqlExtensionDdlDenied", "extensionMembersInSecureSchema", "runtimeCannotAssumeExtensionOwner"})

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result

def validate_migration(manifest):
    envelope = manifest["migration_evidence"]
    if not isinstance(envelope, dict) or set(envelope) != {"document", "sha256"}:
        raise ValueError("closed migration evidence envelope required")
    document = envelope["document"]
    if not isinstance(document, str) or len(document.encode("utf-8")) > 131072:
        raise ValueError("bounded migration evidence required")
    if hashlib.sha256(document.encode("utf-8")).hexdigest() != envelope["sha256"]:
        raise ValueError("migration evidence digest mismatch")
    evidence = json.loads(document, object_pairs_hook=unique_object)
    expected = {"schemaVersion": "2.0", "artifactKind": "MIGRATION_ATTEMPT", "serviceProfile": "YANDEX_MANAGED",
                "migrationResult": "SUCCESS", "failureStage": "NONE", "attestationState": "ATTESTED",
                "expectedPostgresqlMajor": 17, "postgresqlMajor": 17,
                "canonicalV0002Path": "db/migration/V0002__enable_btree_gist_extension.sql",
                "canonicalV0002GitBlob": "bd3e55ea737ffda9d519a931eea1f3cc58b8c522",
                "canonicalV0002Sha256": "438f67ccf3c2f640a1e7a4e325e24fb60d1eb4f363ab545e1e69babba202db16",
                "canonicalV0002FlywayChecksum": 1291326236, "resolvedVersion": "0002",
                "resolvedDescription": "enable btree gist extension",
                "providerDocumentSha256": "34e1f92c87f22eb1256f49b2a31c49911cd62bb7c18ce4f7960e43f585584c96",
                "executorMode": "EXTERNALLY_SATISFIED_PROVIDER_EXTENSION",
                "extensions": {"btree_gist": "1.7", "pgcrypto": "1.3"},
                "environmentReference": manifest["environment"]["MARKETOPS_ENVIRONMENT"]}
    if not isinstance(evidence, dict) or any(evidence.get(k) != v for k, v in expected.items()):
        raise ValueError("completed managed migration evidence required")
    if evidence.get("providerControlPlaneApplied") is not True:
        raise ValueError("real applied-resource evidence required for runtime")
    facts = evidence.get("extensionFacts", {})
    if set(facts) != {"btree_gist", "pgcrypto"} or any(
            facts[name].get("schema") != "public" or facts[name].get("version") != version
            or facts[name].get("owner") in {None, "marketops_app", "marketops_migration"}
            for name, version in expected["extensions"].items()):
        raise ValueError("migration extension facts inconsistent")
    assertions = evidence.get("roleAssertions")
    if not isinstance(assertions, dict) or set(assertions) != ROLE_ASSERTIONS or any(v is not True for v in assertions.values()):
        raise ValueError("migration privilege assertions incomplete")
    for key in ["artifactSha256", "databaseUrlSha256", "bootstrapEvidenceSha256", "providerEvidenceSha256",
                "providerDocumentSha256", "startedEvidenceSha256", "attestedEvidenceSha256"]:
        if not isinstance(evidence.get(key), str) or not re.fullmatch(r"[0-9a-f]{64}", evidence[key]):
            raise ValueError("migration custody identity missing")
    if evidence["databaseUrlSha256"] != hashlib.sha256(manifest["environment"]["SPRING_DATASOURCE_URL"].encode("utf-8")).hexdigest():
        raise ValueError("migration evidence belongs to a different database")
    for key in ["repositoryCommit", "repositoryTree"]:
        if not isinstance(evidence.get(key), str) or not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", evidence[key]):
            raise ValueError("migration release identity missing")
    after = evidence.get("flywayHistoryAfter", {})
    migrations = after.get("migrations", [])
    if after.get("state") != "PRESENT" or after.get("v0002") != "CANONICAL" or not migrations \
            or migrations[-1].get("version") != evidence.get("schemaVersionAfter") \
            or any(row.get("success") is not True for row in migrations):
        raise ValueError("migration history is not complete")
    return evidence

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("bootstrap redirects are forbidden")

def read_json(url, headers):
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
    request = urllib.request.Request(url, headers=headers)
    with opener.open(request, timeout=15) as response:
        payload = response.read(MAX_RESPONSE + 1)
    if len(payload) > MAX_RESPONSE:
        raise ValueError("bootstrap response exceeds bound")
    return json.loads(payload)

def validate(manifest):
    if set(manifest) != {"role", "backend_image", "console_image", "environment", "secrets", "migration_evidence"}:
        raise ValueError("closed runtime manifest required")
    if manifest["role"] not in {"application", "worker"}:
        raise ValueError("unknown runtime role")
    for key in ["backend_image", "console_image"]:
        if not isinstance(manifest[key], str) or not IMAGE.fullmatch(manifest[key]):
            raise ValueError("digest-pinned Yandex image required")
    environment = manifest["environment"]
    if set(environment) != ENVIRONMENT_KEYS or any(not isinstance(v, str) or "\n" in v or "\r" in v for v in environment.values()):
        raise ValueError("closed non-secret environment required")
    for key in ["MARKETOPS_ACQUISITION_SCHEDULER_ENABLED", "MARKETOPS_PRICE_WRITE_WORKER_ENABLED", "SPRING_FLYWAY_ENABLED"]:
        if environment[key] != "false":
            raise ValueError("bootstrap does not authorize execution or migrations")
    if environment["SPRING_PROFILES_ACTIVE"] != "production" or environment["SPRING_CONFIG_IMPORT"] != "configtree:/run/marketops/config/":
        raise ValueError("production configuration boundary required")
    if environment["MARKETOPS_SECRET_MOUNT_DIRECTORY"] != "/run/marketops/credentials":
        raise ValueError("ephemeral credential mount required")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}",
                        environment["MARKETOPS_OIDC_AUDIENCE"]):
        raise ValueError("nonblank bounded OIDC audience required")
    if not re.fullmatch(r"jdbc:postgresql://c-[a-z0-9]{20}[.]rw[.]mdb[.]yandexcloud[.]net:6432/marketops[?]"
                        r"sslmode=verify-full&sslrootcert=/opt/marketops/certs/yandex-root[.]crt&targetServerType=primary",
                        environment["SPRING_DATASOURCE_URL"]):
        raise ValueError("verified private database destination required")
    validate_migration(manifest)
    if not REQUIRED_FILES.issubset(manifest["secrets"]):
        raise ValueError("required secret references missing")
    for destination, binding in manifest["secrets"].items():
        if destination not in CONFIG_FILES and not re.fullmatch(r"credentials/[a-z0-9][a-z0-9-]{0,62}(?:/[a-z0-9][a-z0-9._-]{0,62}){1,4}", destination):
            raise ValueError("invalid secret destination")
        if any(part in {".", ".."} for part in destination.split("/")):
            raise ValueError("invalid secret destination")
        if set(binding) != {"secret_id", "version_id", "key"} or any(not IDENTIFIER.fullmatch(binding[n]) for n in ["secret_id", "version_id"]):
            raise ValueError("pinned Lockbox reference required")
        if not isinstance(binding["key"], str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", binding["key"]):
            raise ValueError("invalid Lockbox entry key")

def deliver(manifest, root, transport=read_json):
    validate(manifest)
    token = transport("http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token", {"Metadata-Flavor": "Google"}).get("access_token")
    if not isinstance(token, str) or not token or "\n" in token or "\r" in token:
        raise ValueError("instance identity unavailable")
    payloads = {}
    staged = []
    for destination, binding in manifest["secrets"].items():
        identity = (binding["secret_id"], binding["version_id"])
        if identity not in payloads:
            url = "https://payload.lockbox.api.cloud.yandex.net/lockbox/v1/secrets/" + identity[0] + "/payload?versionId=" + identity[1]
            result = transport(url, {"Authorization": "Bearer " + token})
            if result.get("versionId") != identity[1] or not isinstance(result.get("entries"), list):
                raise ValueError("Lockbox version mismatch")
            entries = result["entries"]
            if len({entry.get("key") for entry in entries}) != len(entries):
                raise ValueError("duplicate Lockbox entry")
            payloads[identity] = {entry["key"]: entry.get("textValue") for entry in entries}
        value = payloads[identity].get(binding["key"])
        if not isinstance(value, str) or not value or len(value.encode("utf-8")) > 16384:
            raise ValueError("missing or oversized secret value")
        staged.append((destination, value))
    # Complete and validate all payloads before changing any mounted file.
    for destination, value in staged:
        target = root / destination
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if target.is_symlink() or any(parent.is_symlink() for parent in target.parents):
            raise ValueError("secret mount contains a symlink")
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(value.encode("utf-8"))
    return token

def run(command, **kwargs):
    return subprocess.run(command, check=True, stdout=kwargs.pop("stdout", subprocess.DEVNULL), stderr=subprocess.DEVNULL, timeout=120, **kwargs)

def start(manifest, root, token, execute=run):
    validate(manifest)
    execute(["docker", "login", "--username", "iam", "--password-stdin", "cr.yandex"], input=token.encode())
    try:
        execute(["docker", "pull", manifest["backend_image"]])
        if manifest["role"] == "application":
            execute(["docker", "pull", manifest["console_image"]])
    finally:
        # Docker's token cache lives in tmpfs and is removed before starting workloads.
        config = root / "docker-config" / "config.json"
        config.unlink(missing_ok=True)
    # The image build verifies this label against the actual JAR bytes. A runtime
    # image from a different artifact must not start against this migration proof.
    metadata = execute(["docker", "image", "inspect", "--format",
                        '{{ index .Config.Labels "org.marketops.artifact-sha256" }}', manifest["backend_image"]],
                       stdout=subprocess.PIPE).stdout
    if not isinstance(metadata, bytes) or len(metadata) > 128 \
            or metadata.strip().decode("ascii") != validate_migration(manifest)["artifactSha256"]:
        raise ValueError("runtime image differs from the migrated artifact")
    for path in [root] + list(root.rglob("*")):
        if path.name != "docker-config":
            os.chown(path, 10001, 10001)
    base = ["docker", "run", "--detach", "--restart", "no", "--label", "org.marketops.managed=true", "--network", "host", "--read-only",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", "10001:10001", "--tmpfs", "/tmp:rw,noexec,nosuid,size=128m"]
    command = base + ["--name", "marketops-api", "--mount", "type=bind,src="+str(root)+",dst=/run/marketops,readonly"]
    for key, value in sorted(manifest["environment"].items()):
        command += ["--env", key+"="+value]
    execute(command + [manifest["backend_image"]])
    if manifest["role"] == "application":
        execute(base + ["--name", "marketops-console", manifest["console_image"]])

def owned_states(manifest, execute=run):
    validate(manifest)
    names = ["marketops-api"] + (["marketops-console"] if manifest["role"] == "application" else [])
    payload = execute(["docker", "ps", "--all", "--filter", "label=org.marketops.managed=true",
                       "--format", "{{.Names}}|{{.State}}"], stdout=subprocess.PIPE).stdout
    if not isinstance(payload, bytes) or len(payload) > 65536:
        raise ValueError("bounded owned-container status required")
    states = {}
    for line in payload.decode("ascii").splitlines():
        name, state = line.split("|")
        if name in names:
            if name in states or state not in {"running", "created", "exited", "paused", "dead", "restarting", "removing"}:
                raise ValueError("ambiguous owned-container status")
            states[name] = state
    return states

def stop(manifest, execute=run):
    # Only containers carrying our ownership label are eligible for removal.
    # Missing containers are normal on the first boot; name collisions are not force-removed.
    for name, state in owned_states(manifest, execute).items():
        if state in {"running", "paused", "restarting"}:
            execute(["docker", "stop", "--time", "30", name])
        execute(["docker", "rm", name])

def supervise(manifest, execute=run, pause=time.sleep):
    names = {"marketops-api"} | ({"marketops-console"} if manifest["role"] == "application" else set())
    while True:
        if owned_states(manifest, execute) != {name: "running" for name in names}:
            raise RuntimeError("owned runtime container exited")
        pause(5)

def main():
    manifest = json.loads(Path(sys.argv[1]).read_text())
    root = Path("/run/marketops")
    # The systemd RuntimeDirectory is under Linux /run (tmpfs); refuse an alternate disk mount.
    mountinfo = Path("/proc/self/mountinfo").read_text().splitlines()
    if not any(" /run " in line and " - tmpfs " in line for line in mountinfo):
        raise ValueError("ephemeral /run mount required")
    if sys.argv[2:] == ["--stop"]:
        stop(manifest)
    elif len(sys.argv) == 2:
        # Docker never auto-starts a stale container against an empty/replaced tmpfs.
        # Each systemd restart validates migration evidence, retires owned leftovers,
        # redelivers all pinned secrets and starts the checked artifact again.
        stop(manifest)
        start(manifest, root, deliver(manifest, root))
        supervise(manifest)
    else:
        raise ValueError("unsupported bootstrap action")

if __name__ == "__main__":
    try:
        main()
        print("marketops_bootstrap_completed")
    except Exception:
        # Never print provider errors, process output, payloads, URLs or environment values.
        print("marketops_bootstrap_failed", file=sys.stderr)
        raise SystemExit(1)
