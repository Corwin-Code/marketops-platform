# S1-F012 asynchronous diagnostic export — implementation plan

Status: `PLANNED_NOT_IMPLEMENTED`. This records the next in-scope implementation
work. It is not an accepted Contract Amendment, a completed feature, or evidence
of external/production enablement. Candidate limits below need executable tests.

## Ownership and output

The analytics decision module owns a store/window diagnostic export. It reuses
the existing identity authorization and immutable custody APIs. It must not
introduce another fact, metric, policy, Raw writer or object-store authority.
Both `DIAGNOSTIC_VIEW` and `EVIDENCE_VIEW` must currently cover the requested
store. Creation, status and download resolve actual ownership; callers cannot
assert an organization/store for another object's UUID. Initially only the
requester may read/download their job, even if another user has store access.

The export format is versioned NDJSON with explicit, closed field lists for
metric values, metric input references, findings and finding input references.
Numbers retain canonical precision and state/confidence/freshness/definition
versions. Input references are separate records, so a metric with many inputs
does not require one unbounded JSON array. No Raw body, buyer data, freeform
source evidence note, secret locator or signed object URL is exported. Native
identity may be added only through an explicit reviewed field list; automatic
`to_jsonb(table_row)` expansion is not acceptable.

## Durable job and snapshot

Creation returns `202` and a queued job without constructing the result in the
HTTP transaction. A caller-provided idempotency key binds requester, store and
window; reusing it for a different request is rejected. Concurrency and queue
limits are enforced in the database, not a per-process map.

A fenced worker claims a queued job and rechecks the requester's current
authority. Its single snapshot SQL statement selects the latest metric/finding
versions and all their input links under one MVCC snapshot. The job records
when the snapshot was actually taken; it does not pretend that a later worker
snapshot occurred at submission time. Immutable snapshot rows then define the
export even if source data changes while the object is produced. A row limit
breach rolls back the whole snapshot and produces a safe failure, never a
truncated successful export.

Candidate operating bounds: at most two active jobs per organization; at most
one million snapshot records, 64 KiB per record, 4 MiB per part, 64 parts and
256 MiB per export; two-minute renewable worker leases, a bounded overall job
deadline/retry count, and one-hour download expiry. These are engineering
resource bounds, not real cohort capacity claims. Final implemented values and
API error behavior must be recorded with tests.

## Custody and replay

The worker reads a bounded ordinal page, concatenates the database's canonical
`payload::text` plus newline, and calls the existing custody service outside a
business transaction. Each part is content addressed and read-back verified.
The controlled part-recording function validates the live lease/fence, exact
contiguous ordinal range, size/count, and the hash of the same canonical snapshot
bytes against the custody record. It must not accept a caller's claimed digest
as proof that arbitrary bytes represent the snapshot.

Completion requires every row exactly once and a bounded manifest of ordered
part hashes, sizes and ranges. The manifest digest binds the complete export;
it must not be described as a whole-file SHA if it is a digest of part metadata.
A crash after object upload but before recording leaves an unreferenced immutable
object. Replay verifies/deduplicates those bytes and resumes from committed part
ranges. A stale worker cannot publish a part or completion. App-role direct
inserts/updates of job/snapshot/part authority are denied.

## Download and audit

The authenticated API exposes a bounded manifest and bounded parts, rechecking
requester/store authority, expiry, successful state and custody on every read.
Downloads use server-generated filenames, attachment disposition, `nosniff`,
no-store caching and a restrictive content policy. A browser may assemble the
bounded parts only after verifying all manifest hashes/ranges; a missing or
corrupt part cannot become a successful download. No endpoint accepts an object
locator from the client. Expiry ends access; it does not silently delete immutable
custody or historical audit records outside governed retention.

Audit creation, snapshot, completion, safe failure, expiry and download without
payloads/credentials/PII. Keep successful object custody separate from successful
job completion and from a user's completed browser download.

## Required verification before claiming implementation

- Real PostgreSQL ownership, revocation, direct-write denial, snapshot stability,
  row/byte/queue limits, concurrent claims, fencing and idempotency tests.
- Crash/replay at snapshot, upload, part commit and completion; no duplicated,
  omitted or reordered snapshot records; final custody corruption refusal.
- API `202`/status/manifest/part tests, malicious IDs and content headers; expiry
  and role/store/identity revocation before download.
- Browser large-export journey, failure states and safe complete download.
- Representative profile export measurement, full backend/frontend/governance
  regression and final traceability/runbook synchronization.

No schema, service, endpoint or browser export implementation is claimed by this
plan. S1-F012 also still requires the complete ephemeral failure/restore drills.
