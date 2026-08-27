# Diagnostic export

This is an internal asynchronous derived artifact, not a new fact, metric,
policy or Raw authority. It does not enable Marketplace writes. Local executable
evidence and real environment acceptance remain separate.

## Operator flow

From the store work list, select **Prepare export**. The console submits
`POST /api/v1/console/diagnosis/stores/{storeId}/exports?window=D30` with an
`Idempotency-Key`, receives a small `202` job handle and polls its status.
A lost submission response can be retried with the same key. Reusing a key for
a different window/store is a conflict. Only the original requester can read
the job, and both `DIAGNOSTIC_VIEW` and `EVIDENCE_VIEW` must still cover its
stored owner. Authorization is checked again after object I/O.

**Download verified export** reads a bounded manifest and then bounded parts.
The browser verifies manifest SHA-256, job/store/window/snapshot identity,
contiguous ranges, lengths, every part hash and record count before creating a
download. A missing or corrupt final part cannot produce a partial file. The
result is UTF-8 NDJSON with a server-generated filename. Raw bytes, signed
storage URLs, credentials, buyer data and freeform source/finding notes are not
included. Numbers are decimal strings, preserving their precision.

Stopping browser waiting does not cancel the durable background job. A failed
or expired job requires a new request key. HTTP `429` means the queue is full;
`409` means unavailable/incomplete/expired or failed integrity; `403` means
current ownership or permission is insufficient. Error responses never echo
payloads or storage locators.

## Format and snapshot

`marketops-diagnostic-ndjson-v1` contains explicit `METRIC`, `METRIC_INPUT`,
`FINDING` and `FINDING_INPUT` records, each with `schemaVersion: 1`.
The export covers store and platform listing variant subjects belonging to
that store. It does not export cross-store product variant aggregates.
Metric/finding versions, time windows, value/confidence/decline states,
definition versions, input digests and evidence reference identifiers are
preserved. Findings retain their exact referenced metric versions, even when
those are no longer the current metric; `current` distinguishes them. A finding
whose metric input is outside the authorized snapshot fails the job rather than
silently dropping that input.

The worker materializes all record families in **one SQL statement**. Its
snapshot time is the materialization time, not the HTTP submission time.
Afterward immutable snapshot rows define the output despite later calculations.
SQL uses explicit field lists, not automatic serialization of entire source
rows. The function has transaction-local bulk query settings
`enable_nestloop=off` and `jit=off`; common interactive queries are unchanged.
This avoids repeated CTE scans under fresh statistics. The extracted production
read statement and its actual settings are measured with `EXPLAIN ANALYZE`.

## Bounds and runtime

| Boundary | Enforced value |
| --- | --- |
| Active jobs | 2 per organization, serialized by a database lock |
| New jobs | 10 per organization in a rolling hour |
| Snapshot | 1,000,000 records; 64 KiB per record; 256 MiB total |
| Transfer | At most 64 parts, each at most 4 MiB |
| Claim | Two-minute renewable, unpredictable lease; maximum 5 attempts |
| Overall job deadline | 15 minutes from submission |
| Snapshot query timeout | 30 seconds; rollback, never truncated success |
| Other repository transaction timeout | 5 seconds |
| Download access | One hour after successful publication |
| Browser per-request timeout | 10 seconds; bounded streaming reads |

Local/CI default `marketops.diagnostic-export.worker-enabled=false`; tests call
the worker explicitly. Staging/production profiles enable this internal worker
by default and allow an explicit `MARKETOPS_DIAGNOSTIC_EXPORT_WORKER_ENABLED`
override. Deployment remains separately unauthorized during this rework.
Acquisition and price-write worker switches are unchanged. All storage I/O runs
outside business transactions, through the existing `RawCustody` authority.
The local filesystem custody adapter uses the same 8 MiB body ceiling as the
managed adapter and non-replacing hard-link publication; export parts fit below
that ceiling. Unsupported local filesystems fail rather than silently weakening
publication.

Expiry ends access; it does not delete immutable custody or historical audit
records. Include export artifacts and materialized rows in the environment's
capacity and governed retention review. These bounds are engineering resource
limits, not a real Owner cohort capacity claim.

## Failure and recovery

The database controls job/snapshot/part writers. App-role direct
`INSERT/UPDATE/DELETE/TRUNCATE` is denied. Part publication recomputes SHA-256 over
the canonical snapshot bytes, checks the custody record, and requires exact
contiguous ordinal ranges. Completion requires every row and byte exactly once.
The manifest hash binds the manifest text; it is not a whole-file SHA-256.

A crash after upload but before part commit leaves an unreferenced immutable
object. The next lease reuses those exact bytes and resumes from committed
ranges. A stale lease cannot publish, complete or fail a newer attempt.
Storage/database failures use bounded retries; lost database acknowledgment
remains recoverable and is not described as success. Revocation, invalid
snapshot or exceeded bounds fail closed. Deadline/retry exhaustion frees the
queue. Do not edit job/snapshot/part rows to force success.

Audit events distinguish submission, claim, snapshot, part recording,
completion, failure, expiry and authorized/verified download reads. They do not
claim the browser saved a file. Correlation uses the job UUID, without exported
payloads. Raw custody remains subject to existing governed retention.

## Executable verification

- `DiagnosticExportIT`: PG17 API/DB ownership, concurrent claims, idempotency,
  direct-write denial, over-one-million-record rollback, snapshot stability,
  fenced replay, wrong custody/ranges, expiry and revocation during reads.
- `RepresentativePerformanceIT`: existing synthetic SKU/order/fact benchmark,
  large asynchronous export, standard PG17 `pg_dump`/`pg_restore`, restored
  history/privileges/counts/manifest and object loss/recovery. Inspect the actual
  result before claiming a run passed.
- `FilesystemObjectStorageTest`: non-replacement under concurrent writers,
  bounded reads/writes and leaf-symlink refusal.
- Frontend API/component tests and browser network-fixture export tests: no
  partial download after a corrupt final part. Those fixtures are UI evidence,
  not signed bearer, real database or provider evidence.

The full authenticated business browser journey, final exact-commit regression,
remote CI, real provider restore/PITR and production capacity remain distinct
obligations. No production restore or provider operation is authorized here.
