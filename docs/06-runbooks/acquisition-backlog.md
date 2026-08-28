# Acquisition backlog

This procedure supports the `acquisition-backlog` control in
`infra/yandex/modules/observability/alert-requirements.json`. See
[operational monitoring](operational-monitoring.md) for delivery and No Data
semantics. Real alert provisioning and delivery remain unverified.

## Meaning and authority

`ingestion_run_backlog_age_seconds` is the age of the oldest queued, retrying,
leased or running job execution. It does not prove source completeness or that
all scheduled jobs exist. Missing telemetry is unknown, not zero backlog.
Stale source facts may block deterministic guardrails with `INPUT_TOO_STALE`.
Tell operators which data is delayed; do not promise a recovery time without
observed progress.

This rework authorizes local synthetic drills only. Production inspection or
recovery needs the environment's approved operator access; this runbook grants
no credentials, provider calls, scheduler enablement or writes.

## Inspect durable state

There is no `/api/v1/console/ingestion/jobs` route. An authorized operator can
use a read-only database session to inspect the affected job IDs and compare
checkpoint age/version with recorded run and observation evidence:

```sql
SELECT run.id, run.job_id, run.state, run.attempt_no, run.max_claims,
       run.failure_code, run.lease_expires_at, run.next_attempt_at,
       run.created_at, checkpoint.checkpoint_version, checkpoint.updated_at
FROM ops.ingestion_run AS run
LEFT JOIN ops.ingestion_checkpoint AS checkpoint ON checkpoint.job_id = run.job_id
WHERE run.state IN ('QUEUED','RETRY_WAIT','LEASED','RUNNING','BLOCKED','FAILED_TERMINAL')
ORDER BY run.created_at
LIMIT 200;
```

Scope inspection to the incident's organization/jobs under the operator's
approved access. This bounded query is an incident sample, not a complete
export. Do not attach raw provider payloads, secrets or buyer data to a ticket.

| Observation | Investigation and recovery boundary |
| --- | --- |
| Quota refusal or rate limiting | Check shared quota reservations, recorded retry time and current verified endpoint limits. Do not raise quotas to bypass a refusal. |
| Terminal retry failure | Inspect the named failure and durable checkpoint before a separately authorized scheduled/backfill execution. Terminal state does not prove that a later schedule repairs every missing source window. |
| No claims | Check process health and configured scheduler posture. Disabled is the default until separately authorized; do not enable it merely to clear an alert. |
| Expired lease | A later authorized worker can recover only under a new fence and remaining retry budget. A live lease must not be stolen. Repeated expiry requires crash investigation. |
| `BLOCKED` / schema drift | Correct and verify the recorded capability/parser contract. Do not reinterpret unknown fields or force a state transition. Recovery requires an approved execution path and verified source coverage. |

## Recovery proof

Never edit a cursor, fence, attempt or historical observation to skip work.
Confirm that committed, hash-verified Raw precedes checkpoint advancement,
that duplicate pages create no duplicate logical effects, and that backlog age
falls while source coverage becomes current. Replay consumes existing custody
and makes no Marketplace acquisition call; backfill is a different operation
and may need provider authority. Neither is a blanket remedy for absent bytes.

Local executable scenarios are indexed in
[the failure-drill evidence](../07-phase-evidence/SLICE-V1-001/rework-r1/failure-drill-index.md).
They do not establish real-account recovery or notification delivery.
