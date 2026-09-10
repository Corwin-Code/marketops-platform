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
