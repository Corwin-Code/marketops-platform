"""Synthetic secret-delivery tests. No metadata, Lockbox or Docker calls occur."""
import copy
import importlib.util
import json
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("yandex_runtime", ROOT / "infra/yandex/runtime/bootstrap.py")
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


def manifest():
    environment = {key: "public-configuration" for key in runtime.ENVIRONMENT_KEYS}
    environment.update(SPRING_PROFILES_ACTIVE="production", SPRING_CONFIG_IMPORT="configtree:/run/marketops/config/",
                       SPRING_FLYWAY_ENABLED="false", MARKETOPS_PRICE_WRITE_WORKER_ENABLED="false",
                       MARKETOPS_ACQUISITION_SCHEDULER_ENABLED="false", MARKETOPS_SECRET_MOUNT_DIRECTORY="/run/marketops/credentials")
    environment.update(MARKETOPS_ENVIRONMENT="staging", SPRING_DATASOURCE_URL="jdbc:postgresql://c-"+"d"*20+
                       ".rw.mdb.yandexcloud.net:6432/marketops?sslmode=verify-full&sslrootcert=/opt/marketops/certs/yandex-root.crt&targetServerType=primary")
    proof=(ROOT / "infra/yandex/environments/staging/tests/migration-result.fixture.json").read_text()
    return {"role": "application", "backend_image": "cr.yandex/registry/api@sha256:" + "1" * 64,
            "console_image": "cr.yandex/registry/console@sha256:" + "2" * 64, "environment": environment,
            "migration_evidence": {"document": proof, "sha256": hashlib.sha256(proof.encode()).hexdigest()},
            "secrets": {name: {"secret_id": "s" * 20, "version_id": "v" * 20, "key": str(n)}
                        for n, name in enumerate(sorted(runtime.REQUIRED_FILES))}}


def transport_for(config):
    payload = {"versionId": "v" * 20, "entries": [{"key": binding["key"], "textValue": "synthetic-delivery-value-" + binding["key"]}
                                                        for binding in config["secrets"].values()]}
    return Mock(side_effect=[{"access_token": "synthetic-instance-token"}, payload])


class YandexRuntimeTests(unittest.TestCase):
    def test_migration_refusals_precede_identity_and_secret_access(self):
        mutations = [
            lambda e: e.update(migrationResult="FAILED"),
            lambda e: e.update(serviceProfile="YANDEX_MANAGED_EMULATION"),
            lambda e: e.update(postgresqlMajor=18),
            lambda e: e.update(attestationState="NOT_ATTESTED"),
            lambda e: e.update(environmentReference="production"),
            lambda e: e.update(databaseUrlSha256="0"*64),
            lambda e: e.update(providerControlPlaneApplied=False),
            lambda e: e["extensions"].update(pgcrypto="1.2"),
            lambda e: e["extensionFacts"]["btree_gist"].update(schema="unsafe"),
            lambda e: e["roleAssertions"].update(providerSqlExtensionDdlDenied=False),
            lambda e: e.pop("bootstrapEvidenceSha256"),
            lambda e: e.pop("artifactSha256"),
            lambda e: e["flywayHistoryAfter"].update(v0002="CONFLICT"),
            lambda e: e["flywayHistoryAfter"]["migrations"][-1].update(success=False),
            lambda e: e.update(schemaVersionAfter="0002"),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                config=manifest()
                evidence=json.loads(config["migration_evidence"]["document"])
                mutate(evidence)
                document=json.dumps(evidence)
                config["migration_evidence"]={"document":document,"sha256":hashlib.sha256(document.encode()).hexdigest()}
                transport=Mock()
                with self.assertRaises(ValueError):
                    runtime.deliver(config,Path("/unreachable"),transport)
                transport.assert_not_called()
        for corrupt in [lambda c: c.pop("migration_evidence"),
                        lambda c: c["migration_evidence"].update(sha256="0"*64)]:
            config=manifest(); corrupt(config); transport=Mock()
            with self.assertRaises(ValueError):
                runtime.deliver(config,Path("/unreachable"),transport)
            transport.assert_not_called()

    def test_runtime_image_must_contain_the_same_artifact_that_completed_migration(self):
        execute=Mock(return_value=Mock(stdout=("8"*64+"\n").encode()))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError,"migrated artifact"):
                runtime.start(manifest(),Path(directory).resolve(),"synthetic-instance-token",execute)
        self.assertFalse(any(c.args[0][:2]==["docker","run"] for c in execute.call_args_list))

    def test_complete_pinned_delivery_is_cached_bounded_and_private(self):
        config = manifest()
        transport = transport_for(config)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            token = runtime.deliver(config, root, transport)
            self.assertEqual("synthetic-instance-token", token)
            self.assertEqual(2, transport.call_count)
            for name, binding in config["secrets"].items():
                self.assertEqual("synthetic-delivery-value-" + binding["key"], (root / name).read_text())
                self.assertEqual(0o600, (root / name).stat().st_mode & 0o777)
            url, headers = transport.call_args_list[0].args
            self.assertEqual("http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token", url)
            self.assertEqual({"Metadata-Flavor": "Google"}, headers)
            self.assertEqual("https://payload.lockbox.api.cloud.yandex.net/lockbox/v1/secrets/" + "s" * 20 + "/payload?versionId=" + "v" * 20, transport.call_args.args[0])

    def test_invalid_manifests_cannot_reach_identity_or_secret_transport(self):
        mutations = [
            lambda x: x.update(role="migration"),
            lambda x: x.update(backend_image="cr.yandex/registry/api:latest"),
            lambda x: x.update(console_image="evil.invalid/image@sha256:" + "1" * 64),
            lambda x: x.update(unexpected="field"),
            lambda x: x["environment"].update(SPRING_FLYWAY_ENABLED="true"),
            lambda x: x["environment"].update(MARKETOPS_PRICE_WRITE_WORKER_ENABLED="true"),
            lambda x: x["environment"].update(MARKETOPS_ACQUISITION_SCHEDULER_ENABLED="true"),
            lambda x: x["environment"].update(SPRING_CONFIG_IMPORT="file:/tmp/unsafe"),
            lambda x: x["environment"].update(MARKETOPS_SECRET_MOUNT_DIRECTORY="/disk"),
            lambda x: x["environment"].update(SPRING_PROFILES_ACTIVE="local"),
            lambda x: x["environment"].update(LD_PRELOAD="anything"),
            lambda x: x["environment"].update(MARKETOPS_OIDC_AUDIENCE="line1\nline2"),
            lambda x: x["environment"].update(MARKETOPS_OIDC_AUDIENCE=""),
            lambda x: x["environment"].update(MARKETOPS_OIDC_AUDIENCE="x" * 256),
            lambda x: x["environment"].update(MARKETOPS_OIDC_AUDIENCE="wrong audience"),
            lambda x: x["secrets"].clear(),
            lambda x: x["secrets"].update({"../../etc/passwd": next(iter(x["secrets"].values()))}),
            lambda x: x["secrets"]["config/spring.datasource.password"].update(version_id="latest"),
            lambda x: x["secrets"]["config/spring.datasource.password"].update(secret_id="s/../other"),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                config = manifest()
                mutate(config)
                transport = Mock()
                with self.assertRaises(ValueError):
                    runtime.deliver(config, Path("/unreachable"), transport)
                transport.assert_not_called()

    def test_partial_wrong_version_duplicate_missing_and_oversized_payloads_create_no_files(self):
        invalid = [
            {"versionId": "wrong", "entries": []},
            {"versionId": "v" * 20, "entries": [{"key": "0", "textValue": "first"}, {"key": "0", "textValue": "second"}]},
            {"versionId": "v" * 20, "entries": [{"key": "0", "textValue": "present-but-incomplete"}]},
            {"versionId": "v" * 20, "entries": [{"key": "0", "textValue": "x" * 16385}]},
        ]
        for payload in invalid:
            with self.subTest(payload=payload["versionId"]), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                transport = Mock(side_effect=[{"access_token": "synthetic-instance-token"}, payload])
                with self.assertRaises(ValueError):
                    runtime.deliver(manifest(), root, transport)
                self.assertEqual([], list(root.iterdir()))

    def test_unavailable_identity_or_provider_failure_never_starts_a_container(self):
        for response in [{}, {"access_token": ""}, {"access_token": "bad\nheader"}]:
            transport = Mock(return_value=response)
            with self.assertRaises(ValueError):
                runtime.deliver(manifest(), Path("/unreachable"), transport)
            self.assertEqual(1, transport.call_count)
        with tempfile.TemporaryDirectory() as directory:
            transport = Mock(side_effect=[{"access_token": "synthetic"}, OSError("synthetic outage")])
            with self.assertRaises(OSError):
                runtime.deliver(manifest(), Path(directory).resolve(), transport)
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_symlink_cannot_redirect_a_delivered_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            outside = root / "outside"
            outside.mkdir()
            (root / "config").symlink_to(outside, target_is_directory=True)
            config = manifest()
            with self.assertRaises(ValueError):
                runtime.deliver(config, root, transport_for(config))
            self.assertEqual([], list(outside.iterdir()))

    def test_container_inputs_are_pinned_and_secret_values_never_enter_arguments(self):
        for role in ["application", "worker"]:
            config = manifest()
            config["role"] = role
            execute = Mock(return_value=Mock(stdout=("7"*64+"\n").encode()))
            with tempfile.TemporaryDirectory() as directory, patch.object(runtime.os, "chown"):
                root = Path(directory).resolve()
                (root / "docker-config").mkdir()
                cache = root / "docker-config/config.json"
                cache.write_text("synthetic cached token")
                runtime.start(config, root, "synthetic-instance-token", execute)
                self.assertFalse(cache.exists())
                self.assertEqual(b"synthetic-instance-token", execute.call_args_list[0].kwargs["input"])
                commands = [call.args[0] for call in execute.call_args_list]
                self.assertNotIn("synthetic-instance-token", json.dumps(commands))
                launches = [command for command in commands if command[:2] == ["docker", "run"]]
                self.assertEqual(2 if role == "application" else 1, len(launches))
                for command in launches:
                    for argument in ["--read-only", "--cap-drop", "ALL", "no-new-privileges", "10001:10001"]:
                        self.assertIn(argument, command)
                    self.assertEqual("no",command[command.index("--restart")+1])
                    self.assertIn("org.marketops.managed=true",command)

    def test_restart_cleanup_only_stops_and_removes_owned_containers(self):
        execute=Mock(return_value=Mock(stdout=b"marketops-api|exited\nmarketops-console|running\nunrelated-owned|running\n"))
        runtime.stop(manifest(),execute)
        commands=[call.args[0] for call in execute.call_args_list]
        self.assertEqual(["docker","rm","marketops-api"],commands[1])
        self.assertEqual(["docker","stop","--time","30","marketops-console"],commands[2])
        self.assertEqual(["docker","rm","marketops-console"],commands[3])
        self.assertNotIn("unrelated-owned",json.dumps(commands))
        empty=Mock(return_value=Mock(stdout=b""))
        runtime.stop(manifest(),empty)
        self.assertEqual(1,empty.call_count)

    def test_supervision_detects_either_container_exit_and_forces_full_restart(self):
        running=b"marketops-api|running\nmarketops-console|running\n"
        for stopped in [b"marketops-api|exited\nmarketops-console|running\n",b"marketops-api|running\n"]:
            execute=Mock(side_effect=[Mock(stdout=running),Mock(stdout=stopped)])
            pause=Mock()
            with self.assertRaisesRegex(RuntimeError,"container exited"):
                runtime.supervise(manifest(),execute,pause)
            pause.assert_called_once_with(5)
        ambiguous=Mock(return_value=Mock(stdout=b"marketops-api|running\nmarketops-api|exited\n"))
        with self.assertRaises(ValueError):
            runtime.owned_states(manifest(),ambiguous)

    def test_failed_image_pull_clears_the_token_cache_and_launches_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "docker-config").mkdir()
            cache = root / "docker-config/config.json"
            cache.write_text("synthetic cached token")
            execute = Mock(side_effect=[None, OSError("synthetic registry failure")])
            with self.assertRaises(OSError):
                runtime.start(manifest(), root, "synthetic-instance-token", execute)
            self.assertFalse(cache.exists())
            self.assertEqual(2, execute.call_count)

    def test_redirects_are_never_followed(self):
        with self.assertRaises(ValueError):
            runtime.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://example.invalid")

    def test_http_reader_disables_proxies_bounds_bytes_and_uses_a_timeout(self):
        response = Mock()
        response.read.return_value = b'{}'
        context = Mock()
        context.__enter__ = Mock(return_value=response)
        context.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = context
        with patch.object(runtime.urllib.request, "build_opener", return_value=opener) as build:
            self.assertEqual({}, runtime.read_json("https://example.invalid", {}))
            self.assertEqual({}, build.call_args.args[1].proxies)
            self.assertEqual(15, opener.open.call_args.kwargs["timeout"])
            response.read.assert_called_once_with(runtime.MAX_RESPONSE + 1)
            response.read.return_value = b"x" * (runtime.MAX_RESPONSE + 1)
            with self.assertRaises(ValueError):
                runtime.read_json("https://example.invalid", {})


if __name__ == "__main__":
    unittest.main()
