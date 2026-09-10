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


## Canonical scope and measured source-stratum checkpoint

`checkpoint-5-test-receipts.json`: targeted unit/architecture 91 passed; actual canonical Metric Engine/database 17 passed; signed listing HTTP/database 21 passed. The final HTTP run applies V0087 and validates exact lineage coverage and digest, late-reversal source strata, retained historical inputs and the measurement/listing read boundary. Two corrected failed runs and their actual causes remain recorded with local raw-log hashes.

These results prove the local primitives and their exercised paths only. Canonical totals retain estimate/confidence metadata and do not certify a protection; measured source strata do not establish the frozen comparison method or full Description coverage. Required positive formal Outcome, plan admission and downstream control paths remain unfinished. No finding is closed by this checkpoint.


## Pre-approval frozen-plan checkpoint

`checkpoint-6-test-receipts.json`: 93 unit/architecture, 23 signed HTTP/database, 7 launch/concurrency and 7 Description write-Gate tests passed. Normal preparation freezes the resolved plan before review; first-time freeze after review/approval is refused. Review/approval captures and consumes the exact plan digest. The test preserves one-live-action exclusivity and cancels the prior unused synthetic action through the actual route before creating a new round. Read responses preserve the full frozen definition and captured plan digests.

Historical approvals without a plan binding cannot borrow one retrospectively. Existing allowance, scope and expiry checks continue to pass their targeted regressions. Full precise comparison-window/method admission, formal positive Outcome and all downstream/root-cause closure remain unfinished; this is another local engineering checkpoint, not Controller approval or production authority.

## Finite method, node windows and result evidence checkpoint

`checkpoint-7-test-receipts.json`: 104 unit/architecture, 24 signed HTTP/database including concurrent Outcome requests, and 8 schema/application-role tests passed on the final production changes and V0089. The mathematical method is explicitly parameterized; no accepted confidence level is invented. Actual Outcome execution retains method/window gaps, allows only the original fixed cohort for a qualified late correction, and cannot write a requested unproved stage as a settled Task observation. Concurrent requests produce result revisions 0 and 1 with exactly one revision edge.

The first 23-test HTTP run had one unexplained preparation 403. The isolated diagnostic and subsequent full 23- and 24-test runs passed; that does not establish its root cause or a fix. Raw-log hashes and sanitized summaries retain this limitation. Exact control/Description-coverage qualification and canonical protection consumption remain unfinished, so no formal positive Outcome or finding closure is claimed.

## Display evidence custody and exact manual verification checkpoint

`checkpoint-8-test-receipts.json`: 91 unit/architecture, 26 signed HTTP/database and 8 independent PostgreSQL application-role manual-evidence tests passed. Normal HTTP packet issuance, independent management/customer observations, rejection of a same-organization foreign-listing display and successful bound verification/read are covered. Further cases exercise wrong text, unknown display, executor evidence, stale/future evidence, the reported-operation boundary and relabelling human evidence as official. A management-only verified observation preserves unknown customer display. Historical measured display input stays unchanged after later unknown reports.

These checks prove exact instant evidence binding, not full target-version coverage or formal Outcome. The earlier intermittent preparation 403 remains documented without a claimed root cause. Final verification, all downstream controls and finding closure remain pending.

## Atomic API launch checkpoint

`checkpoint-9-test-receipts.json`: 76 architecture, 10 launch/database, 27 signed HTTP/database, 8 write-Gate and 1 V0090-to-V0091 upgrade tests passed. A normal API launch returns its one queued command. Creation failure rolls back the launch and every occupation; manual launch queues none. Historic orphan launches are preserved and cannot be converted to new commands by a later call. The authority check covers actual database function bodies and rejects a second static writer. Existing default-off dispatch and changed-text lease refusal remain exercised.

The receipt retains corrected test failures and their causes. Command-result/business propagation, broader protocol qualification, complete regression and final closure remain unfinished; 0/27 closed. The earlier intermittent preparation 403 remains unresolved.

## Frozen native response identity checkpoint

`checkpoint-10-test-receipts.json` binds source file hashes, final targeted commands and sanitized summaries: 182 shared unit/architecture, 11 native response identity/database, 7 waiting, 10 launch, 27 signed HTTP/database and 1 V0090-to-current upgrade tests passed. The exact pre-socket query is exercised against a fabricated verification case; this is not real-account evidence. Positive unique-item/task classification and foreign/duplicate/missing/type-conflicting identities are exercised through retained synthetic Raw bytes. Task-only receipts cannot prove non-application; local/no-response enquiries cannot manufacture task rejection. Raw content deduplication and prior migration constraints remain intact.

Two initial fixture failures and their corrected causes remain in the receipt. Governed Description registry configuration, exact non-echo query binding, whole request validation, business-result propagation and combined scheduler/database/fake-HTTP recovery remain required. No finding is closed. The earlier unexplained preparation 403 remains unresolved.

## Governed Description protocol configuration checkpoint

`checkpoint-11-test-receipts.json`: 76 architecture, 5 Description registry/database, 35 existing Registry workflow, 11 response/database and 27 signed listing HTTP/database checks passed. Description fields use the existing controlled draft/revision functions, and independent verification uses CONTENT_WRITE with exact configuration and descriptor evidence. Self-approval, absent response descriptor, wrong credential purpose, unscoped actor, extra authority fields and direct verified mutation are refused. Renewal keeps the prior verified configuration byte-for-byte.

Initial fixture failure was a missing API Profile; the test now creates it through normal maintenance. All account/attestation values are fictional, including the tested REAL_ACCOUNT enum branch. Request validation, non-echo query binding, full UI and combined runtime verification remain unfinished. No finding is closed; the earlier intermittent preparation 403 is still unresolved.

## Exact Description request and wire checkpoint

`checkpoint-12-test-receipts.json`: 131 unit/architecture, 6 Description registry, 35 shared registry, 11 response identity, 27 signed HTTP authorization and 10 launch/database tests passed. Actual localhost HTTP assertions inspect exact Unicode, numeric attribute, marking, JSON media type and conditional version header. Invalid target/text/body schema, ambiguous or colliding version conditions, refused destinations and revoked final authority produce no HTTP dispatch. Secret resolution follows destination preparation and resolved character buffers are cleared. Normal application-role configuration/review refuses missing or unsupported whole-card request schemas; ordinary metadata depth and default-off maintenance remain enforced.

These remain targeted synthetic checks. The loopback adapter tests mock authority and do not prove combined Worker/database/HTTP recovery. Independent technical registry evidence must still establish native partial-write semantics; the schema cannot invent official attribute/category facts or WB whole-card concurrency. Non-echo task binding, new business restoration approval, all remaining roots and final exact-head verification are unfinished. 0/27 closed; the earlier intermittent preparation 403 remains unexplained.
