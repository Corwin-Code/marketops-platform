# Interim local verification — not final closure evidence

All commands run in `backend/marketops-server` with Maven wrapper, Java 21, synthetic fixtures and disposable Testcontainers PostgreSQL. No provider/account calls or shared migration occurred. These are progress observations on the working tree; final exact-head verification remains required.

- `./mvnw -B -ntp test -Dtest=DescriptionTextTest`: 5 tests passed (initial description change).
- `./mvnw -B -ntp test '-Dtest=DescriptionTextTest,*ArchitectureTest'`: 80 tests passed before the measurement changes.
- `./mvnw -B -ntp test -Dtest=DescriptionTextTest,EvidencePathQualificationTest,VersionWindowTest,VisitConversionTest`: 18 tests passed before the latest transition carry-forward change.
- `./mvnw -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=ListingReworkAuthorizationIT`: progressed from 8 authorization/projection/text scenarios to 12 passing HTTP/database scenarios. The 12 include complete zero-purchase, incomplete/changed coverage, independent summary, contradictory numerator and a complete empty denominator. The subsequent run passed 13 scenarios, including a late sale reversal, preserved original measurement, full sale-revision lineage and deterministic recalculation.

Initial failures exposed actual source-provenance and calculation-run requester violations. Human entry now records MANUAL_ENTRY, retaining the import-batch requirement for INTERNAL_IMPORT. Manual runs pass their real requester through the sole shared ledger writer. Read audit continues to require a transaction; Console GETs supply it.

The latest domain/architecture run (`./mvnw -B -ntp test '-Dtest=DescriptionTextTest,EvidencePathQualificationTest,VersionWindowTest,VisitConversionTest,*ArchitectureTest'`) passed 94 tests, including source-timezone transition carry-forward.

The new migrations were applied only to disposable test databases. Clean/upgrade migration parity, concurrency, all shared-spine regressions, fake provider protocol/worker tests, frontend/browser flows, representative performance and recovery evidence are still outstanding. Nothing in this file is LOCAL_VERIFIED or Controller approval.


## Subsequent calibration checkpoint

- The targeted HTTP/database suite now passes 16 scenarios. Added: exact historical calibration after retirement; signed normal-role draft → professional validation → separate Owner acceptance → separate activation; purpose isolation; self-acceptance refusal; missing-category and incorrect-digest refusal with no acceptance event.
- `./mvnw -B -ntp test '-Dtest=FixedTrafficComparisonTest,DescriptionTextTest,EvidencePathQualificationTest,VersionWindowTest,VisitConversionTest,*ArchitectureTest'`: 98 tests passed after the calibration implementation.
- `FixedTrafficComparisonTest` includes source-composition-only change, high absolute rate with a deterioration, missing critical value/schedule, and absent stratum/invalid weights. The comparison helper is not yet wired into formal Outcome and is not a closure claim.
- V0082 required one SQL syntax correction (`overlaps` was a reserved keyword used as a local variable). The first self-acceptance negative also exposed a missing Console marker/SQL-state translation; the boundary now returns a sanitized 4xx and leaves acceptance absent. Both were corrected before the passing run.

The accepted original Contract, annex, V0001–V0079 and historical Maker artifacts remain preserved. The Maker acceptance/handoff documents are archived byte-for-byte, and their canonical entry points now explicitly state rework in progress. This supersedes the original unsupported LOCAL_VERIFIED counts without altering historical evidence.


## Mapping, provider timing and scoped-Gate checkpoint

- `ListingReworkAuthorizationIT` passed 19 scenarios after V0083: mapping rebind invalidates only dependent bindings, mapping version/member changes invalidate the digest, original identity lineage remains unchanged, and connection timezone does not change it. This run predates the subsequent retry/Gate changes.
- `./mvnw -B -ntp test '-Dtest=DescriptionRetryHeadersTest,RetryAfterUnitsTest,ListingDescriptionCommandWorkerTest,*ArchitectureTest,AdBidWriteDispatchTest,PriceWriteClassificationTest,PlatformHttpAdaptersTest'`: 179 tests passed. Includes real loopback HTTP native-header retention, units and ambiguity; worker observation deferral; shared transport multiplicity and existing price/advertising adapter regressions.
- Each of `ListingConversionSchemaIT` (7), `ListingContainmentIT` (5), `ListingDescriptionWriteGateIT` (7), `ListingActionLaunchIT` (7), and `ListingDescriptionRetryTimingIT` (7) passed with `./mvnw -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=<class>`, run as a separate Maven process. These 33 database scenarios are targeted regression evidence, not complete Slice verification. The timing suite exercises durable 7200-second refusal at the existing fence and new lease, unchanged approval, explicit unknown hold, and inconclusive 409/429/503 mutation responses. Native parser/adapter, worker and database checks are separate components; a combined live scheduler/DB/transport timing trace remains required.
- Two combined database runs exhausted the existing Docker data disk (over 600 unrelated historical volumes). Those runs failed and are not counted as passes. Only identified current-test resources were considered for cleanup; unrelated volumes were untouched. Independent Maven processes allowed the temporary databases to be reaped between suites. No shared database migration occurred.
- The broader suites exposed an actual cross-scope feature-flag defect, fixed in V0085. They also exposed stale test setup: the schema inventory now names all 47 actual tables; expiry fixtures retain a valid decision-before-expiry interval; command/occupation count assertions are bound to their synthetic organization. The assertions still require exact counts and the original refusal/concurrency behavior.
- `checkpoint-3-test-receipts.json` records sanitized summaries, local raw-log hashes, commands and resource digests. It is progress evidence; exact final-Head whole-scope verification is still required.

No finding is closed by this checkpoint. Source-membership completeness, exact protocol/task identity, full frozen evaluation and protection, all remaining business/UI chains, and final regression/performance/recovery evidence remain in scope.


## Frozen evaluation and canonical exact-period checkpoint

`checkpoint-4-test-receipts.json` retains exact targeted commands, sanitized summaries, local raw-log hashes and corrected failed-run causes. Latest unit/architecture subset: 91 passed. Separate database processes: schema 8, canonical Metric re-evaluation 16, signed listing HTTP/authorization 20 passed. The new positive freeze test preserves structured method/group rules with an explicit empty stop and zero tail, and rejects missing stop, malformed maturity and a mismatched package version.

The HTTP counterexample supplies a real synthetic source-summary measurement with absolute ratio 0.1 plus favorable forged profit, return, supply and bound numbers. It now produces UNDETERMINED and stores no caller-supplied conservative bound. This closes that unsafe input path only; the required positive qualified formal comparison/protection pipeline is still unfinished. Existing task-source fixtures were corrected through the actual responsibility-task service, not by weakening Outcome journal requirements.

The exact-period Metric query is tested against actual canonical engine output and revisions. It rejects larger-period substitution, preserves a frozen period when a newer shifted period exists, and selects the latest unavailable same-period revision rather than an older favorable value. V0086 adds optional explicit zero tail and aligns SQL/Java necessary-dimension and known-failure precedence. Configured stop rules with no qualified upper comparison report UNDETERMINED, not CONTINUE.

All results remain partial working-tree evidence. No finding is marked closed; no Controller approval or production enablement is claimed.
