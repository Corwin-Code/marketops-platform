"""Host telemetry contract and fault injection; no network or provider calls."""
from datetime import datetime, timedelta, timezone
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("yandex_telemetry", ROOT / "infra/yandex/runtime/telemetry.py")
telemetry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(telemetry)
NOW = datetime(2026, 8, 28, tzinfo=timezone.utc)


def config():
    return {"folder_id": "f" * 20, "environment": "staging", "role": "application"}


def snapshot():
    return {"schemaVersion": 1, "observedAt": NOW.isoformat(), "signals": dict.fromkeys(telemetry.SIGNALS, 0)}


class YandexTelemetryTests(unittest.TestCase):
    def test_complete_delivery_has_closed_labels_and_fixed_destinations(self):
        transport = Mock(side_effect=[snapshot(), {"access_token": "synthetic-instance-identity"}, {"writtenMetricsCount": "6"}])
        result = telemetry.deliver(config(), transport, NOW, "synthetic-host")
        self.assertEqual({"metric_count": 6, "database_available": True}, result)
        self.assertEqual(telemetry.SNAPSHOT_URL, transport.call_args_list[0].args[0])
        self.assertEqual(telemetry.TOKEN_URL, transport.call_args_list[1].args[0])
        url, headers, body = transport.call_args.args
        self.assertEqual(telemetry.WRITE_URL + "?folderId=" + "f" * 20 + "&service=custom", url)
        self.assertEqual("Bearer synthetic-instance-identity", headers["Authorization"])
        data = json.loads(body)
        self.assertEqual(telemetry.SIGNALS, {metric["name"] for metric in data["metrics"]})
        self.assertEqual({"application", "environment", "role", "instance"}, set(data["labels"]))
        self.assertNotIn("synthetic-instance-identity", body.decode())

    def test_unavailable_database_sends_no_fabricated_business_zeros(self):
        sample = snapshot(); sample["signals"] = {"database_readiness_failed": 1}
        transport = Mock(side_effect=[sample, {"access_token": "synthetic-token"}, {"writtenMetricsCount": "1"}])
        self.assertFalse(telemetry.deliver(config(), transport, NOW, "host")["database_available"])
        self.assertEqual(1, len(json.loads(transport.call_args.args[2])["metrics"]))

    def test_bad_identity_config_and_labels_fail_before_network(self):
        for key, value in [("folder_id", "bad/path"), ("environment", "local"), ("role", "migration")]:
            changed = config(); changed[key] = value; network = Mock()
            with self.assertRaises(ValueError): telemetry.deliver(changed, network, NOW, "host")
            network.assert_not_called()
        with self.assertRaises(ValueError): telemetry.deliver(config(), Mock(), NOW, "bad\nlabel")

    def test_malformed_stale_or_incomplete_samples_never_get_a_token(self):
        samples = [None, {}, {**snapshot(), "schemaVersion": 2},
                   {**snapshot(), "observedAt": (NOW - timedelta(seconds=91)).isoformat()},
                   {**snapshot(), "observedAt": (NOW + timedelta(seconds=16)).isoformat()}]
        for signals in [{}, {"database_readiness_failed": 0}, {**snapshot()["signals"], "unexpected": 0},
                        {**snapshot()["signals"], "database_readiness_failed": 1},
                        {**snapshot()["signals"], "price_command_gate_closed": -1},
                        {**snapshot()["signals"], "price_command_gate_closed": True}]:
            samples.append({**snapshot(), "signals": signals})
        for sample in samples:
            network = Mock(return_value=sample)
            with self.subTest(sample=sample), self.assertRaises((ValueError, TypeError)):
                telemetry.deliver(config(), network, NOW, "host")
            self.assertEqual(1, network.call_count)

    def test_incomplete_push_and_bad_token_are_failures(self):
        for response in [{"writtenMetricsCount": "5"}, {"writtenMetricsCount": "6", "errorMessage": "failure"}, {}]:
            network = Mock(side_effect=[snapshot(), {"access_token": "synthetic-token"}, response])
            with self.assertRaises(ValueError): telemetry.deliver(config(), network, NOW, "host")
        network = Mock(side_effect=[snapshot(), {"access_token": "bad\nheader"}])
        with self.assertRaises(ValueError): telemetry.deliver(config(), network, NOW, "host")
        self.assertEqual(2, network.call_count)

    def test_transport_never_follows_redirect_or_environment_proxy_and_bounds_responses(self):
        with self.assertRaises(ValueError): telemetry.NoRedirect().redirect_request(None, None, 302, None, None, "https://elsewhere.invalid")
        response = Mock(); response.__enter__ = Mock(return_value=response); response.__exit__ = Mock(return_value=False)
        response.status = 200; response.read.return_value = b"x" * (telemetry.MAX_BYTES + 1)
        opener = Mock(); opener.open.return_value = response
        with patch.object(telemetry.urllib.request, "build_opener", return_value=opener) as build:
            with self.assertRaises(ValueError): telemetry.send(telemetry.SNAPSHOT_URL, {})
            self.assertEqual({}, build.call_args.args[1].proxies)
            self.assertEqual(5, opener.open.call_args.kwargs["timeout"])
            response.read.assert_called_once_with(telemetry.MAX_BYTES + 1)
        with self.assertRaises(ValueError): telemetry.unique_object([("key", 1), ("key", 2)])
        with self.assertRaises(ValueError): telemetry.send("https://elsewhere.invalid", {})

    def test_cli_failure_does_not_log_sensitive_error_body(self):
        with patch.object(telemetry.sys, "argv", ["telemetry", "does-not-exist"]), patch.object(telemetry.sys, "stderr", new_callable=io.StringIO) as error:
            self.assertEqual(1, telemetry.main())
            self.assertEqual('{"event":"operational_telemetry_failed"}\n', error.getvalue())


if __name__ == "__main__":
    unittest.main()
