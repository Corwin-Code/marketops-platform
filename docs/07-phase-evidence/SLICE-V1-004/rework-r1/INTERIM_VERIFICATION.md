# Interim local verification — not final closure evidence

All commands run in `backend/marketops-server` with Maven wrapper, Java 21, synthetic fixtures and disposable Testcontainers PostgreSQL. No provider/account calls or shared migration occurred. These are progress observations on the working tree; final exact-head verification remains required.

- `./mvnw -B -ntp test -Dtest=DescriptionTextTest`: 5 tests passed (initial description change).
- `./mvnw -B -ntp test '-Dtest=DescriptionTextTest,*ArchitectureTest'`: 80 tests passed before the measurement changes.
- `./mvnw -B -ntp test -Dtest=DescriptionTextTest,EvidencePathQualificationTest,VersionWindowTest,VisitConversionTest`: 18 tests passed before the latest transition carry-forward change.
- `./mvnw -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=ListingReworkAuthorizationIT`: progressed from 8 authorization/projection/text scenarios to 12 passing HTTP/database scenarios. The 12 include complete zero-purchase, incomplete/changed coverage, independent summary, contradictory numerator and a complete empty denominator. The subsequent run passed 13 scenarios, including a late sale reversal, preserved original measurement, full sale-revision lineage and deterministic recalculation.

Initial failures exposed actual source-provenance and calculation-run requester violations. Human entry now records MANUAL_ENTRY, retaining the import-batch requirement for INTERNAL_IMPORT. Manual runs pass their real requester through the sole shared ledger writer. Read audit continues to require a transaction; Console GETs supply it.

The latest domain/architecture run (`./mvnw -B -ntp test '-Dtest=DescriptionTextTest,EvidencePathQualificationTest,VersionWindowTest,VisitConversionTest,*ArchitectureTest'`) passed 94 tests, including source-timezone transition carry-forward.

The new migrations were applied only to disposable test databases. Clean/upgrade migration parity, concurrency, all shared-spine regressions, fake provider protocol/worker tests, frontend/browser flows, representative performance and recovery evidence are still outstanding. Nothing in this file is LOCAL_VERIFIED or Controller approval.
