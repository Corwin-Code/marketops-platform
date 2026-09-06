# Diagnostic priority-page regression and bounded metric read

The complete local backend run on `dc94d95e117317bafcb4119aaecad531b55a45b7`
finished with 2,768 passed nodes, one failure, zero errors and zero skips.
Its source inventory remained unchanged throughout the command. The failed
`RepresentativePerformanceIT` maximum-page case returned all 500 expected rows,
but its P95 was 3,537.203292 ms against the unchanged 3,000 ms requirement.
All 25 samples, including the four early slow samples, remain in the original
report. Attribution of those samples to host contention has not been established.

`AnalyticsQueryService.priorityQueue` read two current metrics separately for
each subject, producing 1,000 additional metric SQL round trips at the maximum
page size. It now obtains those two metrics in one bounded query for the exact
subjects already selected by the authorized store queue. The existing
`mart.metric_value_verification` function, subject kind, window and latest-value
ordering are shared with scalar reads. Missing and unavailable values, monetary
score limits, currencies, queue order and blocking-rule details retain their
previous behavior. The read-only transaction and its five-second timeout remain.

The production scalar-call scan leaves the single-subject `current` entry point.
Evidence-edge and finding-input expansion are separate reading paths and remain
within the complete regression scope. This change does not claim that every
database access in the application has been batched.

The existing performance test retains three warmups, 25 measured requests, full
response assertions and every original latency threshold. It now also records
actual metric SELECT counts and requires one metric query for each priority-page
request. Existing application/PostgreSQL metric reevaluation scenarios compare
complete batched and scalar records after successful, failed, future, revised
and unavailable-value histories. They check window/kind isolation, missing and
duplicate subjects, empty input and the 500-subject bound. Three new unit tests
check output order, amounts, unknowns, score limits and bounded dispatch.

Precommit validation passed 17 selected unit/architecture tests and 38 selected
integration tests. Both executions explicitly measured a modified worktree rooted
at `dc94d95`, with inventory SHA-256
`508585b8acb0ced1dc762e2465c2f8fce62cc1b14c49bce8cbd219bfc3a262bb`.
These are targeted diagnostics. A new clean checkpoint still requires the full
verification layers, the unchanged maximum-page SLO and exact remote CI.

The failed complete backend archive contains 2,405 original members, is
49,014,203 bytes, and has SHA-256
`d6108e1e0fc163ab8a58c7736aa234079db75f4acdf33ba2b24fc1e406ac3300`.
Its original local location is
`/tmp/slice3-backend-checkpoint-archive-dc94d95-r6-failed/`.
Original targeted receipts are under
`/tmp/slice3-priority-batch-targeted-unit-r1/` and
`/tmp/slice3-priority-batch-targeted-integration-r1/`.
Portable final-handoff registration remains pending; these paths do not imply
remote publication. The failed run cannot supply a successful current backend
layer, including when individual capacity testcases passed within it.

An initial progress summary omitted Maven `[ERROR]` lines and incorrectly called
the performance testcase passed. The original JUnit exposed the error; the user
was promptly given the corrected result. No stored failed measurement was changed.

Accepted Contract, Frozen Finding Set and Owner decisions remain unchanged.
`production_write_enabled=false`; independent Controller acceptance, production
enablement, Ready and merge are not claimed.
