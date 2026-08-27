# Operational signals and notification verification

This is a deployment procedure, not authorization to deploy or contact Yandex.
The rework may test transports with synthetic responses. Actual alert creation,
metric receipt and notification delivery require later environment authority.

## Signal path

The application supplies a bounded, aggregate snapshot at
`GET /actuator/operations` to an actual loopback client only. Forwarded headers
do not grant access. The ALB denies `/actuator/` publicly. No user, store,
credential, object locator or free-form provider content is a metric label.

The VM's `marketops-telemetry.timer` runs every thirty seconds. Its Python
one-shot service validates snapshot shape and freshness, obtains a short-lived
instance identity token, and submits integer gauges to the fixed Yandex custom
metrics endpoint. It uses no database or Marketplace secret. Redirects and
environment proxies are disabled; response bytes and total execution time are
bounded. Failure logs contain only `operational_telemetry_failed`, not the token
or response body. Failed delivery must produce No Data, never a healthy zero.

Yandex documents custom metric writes through its [Monitoring API](https://yandex.cloud/en/docs/monitoring/api-ref/MetricsData/write).
Its currently documented narrowest service role for uploads is
[`monitoring.editor`](https://yandex.cloud/en/docs/monitoring/security/), which
also permits dashboard changes. The Terraform grant is restricted to the
environment folder and application/acquisition identities; it grants no IAM,
database or secret authority. This residual provider-role breadth is explicit.

## Six required alerts

Use the exact controls in `infra/yandex/modules/observability/alert-requirements.json`.
Select `service=custom`, `application=marketops`, the exact environment, and the
listed metric name. Aggregate **MAX** across instances/roles; shared DB counts
must not be added across replicas. Keep No Data at ALARM for every control.

| Metric | Meaning | Trigger / window |
| --- | --- | --- |
| `price_command_awaiting_operator` | Commands in unknown, mismatch, manual resolution or failed compensation states | >0 / 300 seconds |
| `price_command_readback_mismatch` | Commands whose last durable state is readback mismatch | >0 / 300 seconds |
| `ingestion_run_backlog_age_seconds` | Age of oldest queued, retrying or in-flight run | >900 / 900 seconds |
| `price_command_gate_closed` | At least one pending/retrying command is blocked by the real DB gate | >0 / 900 seconds |
| `raw_custody_write_failed` | This process observed a custody write/verification failure in the last five minutes | >0 / 300 seconds |
| `database_readiness_failed` | Bounded operational DB query could not complete | >0 / 180 seconds |

On DB failure the snapshot contains only `database_readiness_failed=1`.
Business metrics are absent because their values are unknown. The custody flag
is process-local and resets on restart; durable run/command states and custody
logs remain the incident evidence. Host/application loss is detected by No Data.

## Provision and verify in an authorized environment

The pinned Terraform provider has no native alert resource; no `local-exec`
or undocumented provider API is substituted. Follow Yandex's documented
[alert creation procedure](https://yandex.cloud/en/docs/monitoring/operations/alert/create-alert)
for each of the six controls. Record the exact alert ID, selector, aggregation,
threshold, window, No Data behavior, channel ID, creation time and reviewer in
the environment evidence. Creation alone does not verify notifications.

1. Check `systemctl status marketops-telemetry.timer` and the service journal.
   A healthy host must emit all six gauges within two intervals.
2. Confirm those labels and values in Monitoring. Test that the public hostname
   returns 404 for `/actuator/operations` and a non-loopback direct client is denied.
3. In a separately authorized staging fault drill, induce each mapped condition,
   observe the expected metric and alert, and obtain a human acknowledgement
   from the configured channel. Record recovery and resolution as well.
4. Stop telemetry in that staging drill and confirm No Data reaches the channel.
   A green dashboard while telemetry is absent is a failed verification.
5. Store non-secret configuration/evidence hashes. Do not store IAM tokens,
   database passwords, raw provider content or notification-recipient PII.

Until all six creation/delivery/recovery proofs exist, Terraform's
`alert_configuration_required.verified` remains `false` and production
enablement remains blocked. Local transport/failure tests cannot set it true.
