# Synthetic diagnostic performance profile V1

Status: `PARTIAL_LOCAL_VERIFICATION`; S1-F012 remains open. This is an engineering test profile,
not an accepted business cohort, provider verification or production capacity claim.

## Scope and assumptions

The Owner's current SKU count and daily order volume have not been supplied to
this rework. The reproducible profile assumes a hypothetical starting point of
500 SKUs and 100 orders/day, then tests 5,000 SKUs and 2,000 orders/day. Those
multipliers exercise the Baseline's 10× SKU / 20× daily-order planning shape;
they do not prove those multipliers against the actual business.

The fixture has one synthetic organization, three marketplace accounts/stores,
and an 80% / 15% / 5% SKU distribution. All identities and source keys are
synthetic. No credential is provisioned, no marketplace/AI capability is
verified, and no price command or provider call is made. The test principal is
local fixture identity, not evidence of OIDC provider interoperability.

| Dataset component | Profile |
| --- | ---: |
| Product and listing variants | 5,000 each |
| Orders across 180 days | 360,000 |
| COMPLETED + SETTLED sales facts | 720,000 |
| Canonical metric versions | 825,240 |
| Metric provenance references | 2,475,720 |
| Diagnosis findings and input links | 285,660 each |
| Recommendations, historical + current | 30,000 |

All 26 metric codes, all nine rules and D7/D14/D30 are represented. Each subject
has two versions; ten frequently updated subjects have sixty. Each metric keeps
three actual fixture provenance references. Available, unavailable, stale,
triggered, clear and declined states are retained in the responses. Metric and
finding values are explicitly materialized benchmark fixtures; their arithmetic
is not offered as pipeline-correctness evidence. The normal service/DB flow
tests establish that separately. The 180-day sales distribution is not proof of
Raw retention or a multi-year ledger recovery drill.

## Reproduction and measurement

From `backend/marketops-server`:

```bash
./mvnw -B -Dtest=RequestTemplateTest -Dit.test=RepresentativePerformanceIT integration-test failsafe:verify
```

`RepresentativePerformanceIT` creates its own PostgreSQL 18.4 Testcontainers
server and migrates the real schema. Its generator is
`src/test/resources/performance/representative-v1.sql`. Only fixture setup uses
that container's generated administrator identity because production roles are
denied temporary-table creation. No grants, constraints or triggers are relaxed.
Measured requests execute through `marketops_app`, real object authorization,
repositories and JSON serialization in MockMvc.

There are three warmups and 25 measured requests per case. P95 uses the nearest
rank. The priority queue is tested in all three windows, at the default 50 and
maximum 500 rows, and on the smaller store. SKU 360 and metric history include
the frequently updated subject and all windows. Evidence includes a single trail
and a batch. The test captures the actual application's prepared statements and
bindings, then runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` without disabling
sequential scans or forcing an index. It records real index definitions,
readiness/validity, database size, row counts, generator SHA and every latency
sample in `target/performance/representative-v1.json`.

The Baseline targets remain priority/Command Center P95 ≤ 3 seconds and SKU 360
P95 ≤ 4 seconds. A 4-second test ceiling is also applied to history/evidence.
Production diagnostic and evidence services now use a 5-second budget per read
transaction. The benchmark has no connection-level timeout override; its tracing
wrapper preserves the original datasource's transaction resource and asserts
that diagnostic SQL runs on a read-only connection. Standalone EXPLAIN statements
have their own 5-second test bound. The entire disposable performance test has a
10-minute bound. Export/queue limits are not yet implemented.

## Local checkpoint 48

The [checkpoint and hash bindings](performance-checkpoint-48.json) record 16 API
cases, 25 measured requests per case, nine actual prepared-SQL plans and the
five-second read-budget lock/recovery test. Full raw measurements are in
[performance-baseline-48.json](performance-baseline-48.json). P95 results:

| Case | P95 ms |
| --- | ---: |
| Priority queue, 50 rows, worst D7/D14/D30 | 209.59 |
| Priority queue, maximum 500 rows | 624.82 |
| SKU 360 hot subject, worst window | 36.74 |
| History, worst window | 18.92 |
| Single / batch evidence | 8.66 / 13.39 |

Unavailable, stale and cold-subject cases also passed. Response checks require
the expected row/metric/finding counts, three retained provenance references,
and the appropriate state/absence qualifiers. The measured dataset occupies
about 1.88 GB. No index was forced and no lineage was removed for speed.

The independent `OperatingFlowIT` case holds real exclusive table locks against
metric and evidence reads. Each read is cancelled under the production budget,
and reads succeed after the locks are released (combined test: 10.176 seconds).
Its command and JUnit identity are in the checkpoint. Initial 46/47 measurements
are retained as historical artifacts: their tracing wrapper used an independent
datasource resource key. Checkpoint 48 corrects that instrumentation and is the
transaction-aware result; do not substitute the older reports for it.

## Remaining evidence

[Full backend checkpoint 49](backend-performance-checkpoint-49.json) then passes
821 unit + 342 integration tests and governance with the same profile; its
[performance result](performance-baseline-49.json) also records runtime and
PostgreSQL settings. Final independent integration/CI and exact published
candidate identities are still pending. Network,
TLS, JWKS retrieval, browser rendering,
multi-user load, actual Owner cohort sizing and controlled-production performance
are outside this local measurement. Authenticated browser business journeys,
asynchronous export (snapshot, custody, expiry and safe download), queue/export
bounds, and complete failure/restore drills remain required
work within S1-F012. No Acceptance item is marked MET by this profile alone.
The [export implementation plan](async-export-implementation-plan.md) is planning,
not a claim that an export endpoint or worker exists.
