# Custody write or verification failure

This procedure supports `evidence-write-failed` in
`infra/yandex/modules/observability/alert-requirements.json`. The metric
`raw_custody_write_failed` reports a process-observed write/verification failure
within five minutes. It resets after restart; retained logs and durable
run/command states are the incident record. See
[operational monitoring](operational-monitoring.md).

## Meaning

Acquisition, imports, command evidence or diagnostic exports could not establish
matching custody. This is not proof that the upstream operation failed. A
Marketplace write may have happened even when storing its answer failed.
Do not repeat that write: follow [command resolution](price-command-resolution.md).

The supported path writes content once, verifies its digest, and only then
records custody. A failed acquisition cannot acknowledge a cursor against
unverified bytes. Missing or corrupt existing bytes remain a failure on replay.

## Investigate without destroying evidence

1. Check process and database readiness. `/actuator/health/readiness` is **not**
   an object-storage integrity probe.
2. Correlate `raw_custody_write_failed`, `raw_custody_verification_failed` or
   `raw_custody_object_missing` with the affected operation's durable state.
   The generic error deliberately contains no provider body, credential or
   exception detail; it does not claim a precise provider failure class.
3. Under separately authorized environment access, inspect network reachability,
   exact workload identity, bucket policy, encryption and retention configuration.
   Use the reviewed IaC identity bindings rather than assuming a fixed count of
   identities. Do not disable verification or retention to make the request pass.
4. For corruption/missing bytes, preserve the custody row, object versions and
   declared hash/length. Compare an authorized recovery copy against both before
   it is used. Keep payloads and secret locators out of ordinary tickets.

## Recovery and escalation

A transient network failure can be retried only through the operation's bounded
retry/fencing path. A terminal state or unknown write needs its explicit recovery
procedure; automatic retry is not promised. For integrity failure, stop affected
writes through the approved incident controls and escalate to the Owner.
Do not overwrite a content-addressed object with different bytes, manufacture a
custody row, mutate command evidence, or delete versions to clear an alert.

The local [database/object restore drill](database-restore-drill.md) demonstrates
refusal when bytes are absent and recovery from the exact retained bytes.
Real Yandex restore, IAM changes and notification delivery require separate
permission and evidence; none was performed during this rework.
