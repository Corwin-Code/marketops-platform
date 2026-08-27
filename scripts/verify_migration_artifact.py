#!/usr/bin/env python3
"""Verify the packaged migration classpath and isolated images; never deploy or use cloud credentials."""
import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROBE = r'''
package com.mimococo.marketops.shared.internal.migration;
public final class PackagedMigrationProbe {
    private static Object call(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, values);
    }
    public static void main(String[] args) throws Exception {
        var loader = PackagedMigrationProbe.class.getClassLoader();
        var type = Class.forName("com.mimococo.marketops.shared.internal.migration.ManagedMigrationResources", true, loader);
        var constructor = type.getDeclaredConstructor(ClassLoader.class);
        constructor.setAccessible(true);
        var resources = constructor.newInstance(loader);
        var selected = new java.util.ArrayList<Object>((java.util.Collection<?>)call(resources, "getResources",
                new Class<?>[]{String.class, String[].class}, "V", new String[]{".sql"}));
        var canonical = call(resources, "canonicalV0002", new Class<?>[]{});
        if (selected.stream().anyMatch(item -> {
            try { return call(item,"getFilename",new Class<?>[]{}).equals("V0002__enable_btree_gist_extension.sql"); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        })) throw new IllegalStateException("Canonical SQL V0002 is exposed to the managed resolver");
        selected.add(canonical);
        var inventory = new java.util.TreeMap<String,String>();
        for (var resource : selected) {
            String name = (String)call(resource, "getFilename", new Class<?>[]{});
            try (var reader = (java.io.Reader)call(resource, "read", new Class<?>[]{})) {
                var text = new java.io.StringWriter(); reader.transferTo(text);
                var digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (inventory.put(name,java.util.HexFormat.of().formatHex(digest)) != null) throw new IllegalStateException("Duplicate migration");
            }
        }
        inventory.forEach((name, digest) -> System.out.println(name + " " + digest));
    }
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "build/migration-runtime-evidence")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    artifacts = list((ROOT / "backend/marketops-server/target").glob("marketops-server-*.jar"))
    if len(artifacts) != 1:
        raise ValueError("Exactly one fully verified application JAR is required")
    artifact = artifacts[0]
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    canonical = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in
                 (ROOT / "backend/marketops-server/src/main/resources/db/migration").glob("*.sql")}
    with zipfile.ZipFile(artifact) as jar:
        if any("BrowserFixtureApplication" in name or "BrowserSigningFixture" in name for name in jar.namelist()):
            raise ValueError("Synthetic browser authority must never enter the production artifact")
        packaged = {Path(p).name: hashlib.sha256(jar.read(p)).hexdigest() for p in jar.namelist()
                    if p.startswith("BOOT-INF/classes/db/migration/") and p.endswith(".sql")}
    if packaged != canonical:
        raise ValueError("Packaged migrations differ from the canonical source inventory")

    environment = {k: v for k, v in os.environ.items() if k in {"PATH", "HOME", "JAVA_HOME", "LANG", "TMPDIR"}}
    logs = {}

    def run(name, command, expected=0):
        path = output / (name + ".log")
        with path.open("wb") as log:
            result = subprocess.run(command, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        logs[name] = hashlib.sha256(path.read_bytes()).hexdigest()
        if result.returncode != expected:
            raise RuntimeError("Packaged runtime verification failed: " + name)
        return path.read_text()

    with tempfile.TemporaryDirectory(prefix="marketops-packaged-runtime-") as directory:
        scratch = Path(directory)
        source = scratch / "PackagedMigrationProbe.java"
        source.write_text(PROBE)
        run("compile-probe", ["javac", "--release", "21", "-d", str(scratch), str(source)])
        actual = run("packaged-resolver", ["java", "-Dloader.path=" + str(scratch),
                     "-Dloader.main=com.mimococo.marketops.shared.internal.migration.PackagedMigrationProbe",
                     "-cp", str(artifact), "org.springframework.boot.loader.launch.PropertiesLauncher"])
        resolved = dict(line.split(" ", 1) for line in actual.splitlines() if line.startswith("V"))
        if resolved != canonical:
            raise ValueError("Packaged public resolver inventory differs from canonical SQL")
        # Read only local Docker endpoint metadata, then use an empty auth config.
        endpoint = run("docker-endpoint", ["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]).strip()
        if not endpoint.startswith("unix://"):
            raise ValueError("Only the local Docker daemon is permitted")
        config = scratch / "docker-config"
        config.mkdir()
        plugin_roots = [Path("/Applications/Docker.app/Contents/Resources/cli-plugins"),
                        Path("/usr/local/lib/docker/cli-plugins"), Path("/usr/libexec/docker/cli-plugins"),
                        Path.home() / ".docker/cli-plugins"]
        plugin_roots = [str(p) for p in plugin_roots if (p / "docker-buildx").is_file()]
        (config / "config.json").write_text(json.dumps({"auths": {}, "cliPluginsExtraDirs": plugin_roots}) + "\n")
        environment.update(DOCKER_CONFIG=str(config), DOCKER_HOST=endpoint, DOCKER_BUILDKIT="1")
        # Do not send a working tree, local Raw custody, environment files or test
        # reports to the daemon. The context contains only the approved JAR and
        # the exact public runtime files consumed by these two Dockerfiles.
        context = scratch / "image-context"
        inputs = [artifact.relative_to(ROOT)] + [Path("infra/yandex/runtime") / name for name in
                 ["backend.Dockerfile", "migration.Dockerfile", "migration-logback.xml", "certs/yandex-root.crt"]]
        build_inputs = {}
        for relative in inputs:
            destination = context / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
            build_inputs[str(relative)] = hashlib.sha256(destination.read_bytes()).hexdigest()
        identities = {}
        for role in ["backend", "migration"]:
            image_id = output / (role + ".image-id")
            run(role + "-build", ["docker", "build", "--network=none", "--pull=false",
                "--build-arg", "ARTIFACT_SHA256=" + digest, "--iidfile", str(image_id),
                "--tag", "marketops-" + role + "-verification:" + digest[:16],
                "--file", str(context / "infra/yandex/runtime" / (role + ".Dockerfile")), str(context)])
            identities[role] = image_id.read_text().strip()
            label = run(role + "-artifact-label", ["docker", "image", "inspect", "--format",
                        '{{ index .Config.Labels "org.marketops.artifact-sha256" }}', identities[role]]).strip()
            if label != digest:
                raise ValueError("Image label does not bind the verified migration artifact")
        run("wrong-artifact-build-refused", ["docker", "build", "--network=none", "--pull=false",
            "--build-arg", "ARTIFACT_SHA256=" + "0" * 64, "--file", str(context / "infra/yandex/runtime/migration.Dockerfile"), str(context)], expected=1)
        refusal = run("missing-envelope-refused", ["docker", "run", "--rm", "--network=none", "--read-only",
            "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=10001:10001",
            "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m", identities["migration"], "--migrate", "/run/unmounted.properties"], expected=1)
        if "MIGRATION_FAILED" not in refusal or "jdbc:postgresql" in refusal:
            raise ValueError("Packaged entry point did not fail with the redacted refusal")
    checkout = run("checkout-identity", ["git", "rev-parse", "HEAD", "HEAD^{tree}"]).splitlines()
    dirty = bool(run("checkout-status", ["git", "status", "--porcelain"]).strip())
    report = {"scope": "LOCAL_PACKAGED_ARTIFACT_AND_ISOLATED_CONTAINER_VERIFICATION", "artifactSha256": digest,
              "testedCheckoutCommit": checkout[0], "testedCheckoutTree": checkout[1], "uncommittedWorktree": dirty,
              "buildContextInputs": build_inputs,
              "migrationInventory": canonical, "images": identities, "logs": logs, "result": "PASS",
              "databaseConnections": "NONE", "providerCalls": "NONE", "credentials": "NONE", "deployment": "NOT_EXECUTED"}
    (output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k not in {"migrationInventory", "logs"}}, indent=2))


if __name__ == "__main__":
    main()
