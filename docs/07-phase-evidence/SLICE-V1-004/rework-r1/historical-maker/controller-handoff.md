# SLICE-V1-004 — Level 1 local checkpoint handoff to the Controller

```yaml
record_id: CLAUDE_SLICE_V1_004_LEVEL_1_LOCAL_CHECKPOINT_HANDOFF_R1
record_date: 2026-09-09
maker: CLAUDE
authority_consumed: SLICE_V1_004_CONTRACT_S15_LEVEL_1_LOCAL_ONLY
authority_source: docs/08-handoffs/OWNER-SLICE-V1-004-CONTRACT-ACCEPTANCE-EVIDENCE.md
contract: docs/03-work-items/SLICE-V1-004-promotion-listing-conversion.md
contract_sha256: 5a1761ad614426ad3cba9594f481e293b584d69e96c6d893cf502a5062cfc983
contract_git_blob_sha1: 8e89dec6b67e1e4d1e9f5ea05f17cedbd1985ea9
annex: docs/03-work-items/SLICE-V1-004-promotion-listing-conversion-acceptance.md
annex_sha256: c77089fc78183d6289ed0023d4d0dbee915f61e8d917a7f49ba8564b0dc2a48d
annex_git_blob_sha1: 4d9e93c17e26deee5d2222c8fc51d61d619aa9c8
owner_acceptance_receipt_sha256: 5aa9b84b5c889e3c8dcb82f7d436c6a71a3eb2810d3a7d18855052391df87dcb
owner_acceptance_statement_sha256: 2d49c1bda22fd55e8f7d14af6672c2d545ac530ebac2ae4cd52dd2b162c19ed3
accepted_amendments: NONE
source_base_commit: 0f26d0ed387fd0e20c2137b11760ae0bb0f3e5bd
source_base_tree: 9d65c590b4c6a5e08ea2692d5d8f7a3b9645f400
branch: claude/slice-v1-004-local-implementation-pmrr80
checkpoint_shape: ONE_LOCAL_COMMIT_ON_TOP_OF_SOURCE_BASE_NO_OTHER_PARENT
checkpoint_commit: REPORTED_BY_READBACK_NOT_PREFILLED
remote_publication: NONE
draft_pr: NONE
level_2_environment: NONE
real_provider_call: NONE
gate_ev: NOT_AUTHORIZED_NOT_USED
gate_e: NOT_AUTHORIZED_NOT_USED
production_write_enabled: false
controller_verdict: PENDING_INDEPENDENT_DEEP_REVIEW
next_authorized_actor: CONTROLLER
```

This is the one handoff the Contract asks for after Level 1 work. It records
what exists in the local checkpoint and what was observed while producing it.
It is not a Controller verdict, not a claim that any platform capability is
verified, and not production readiness. Where a test or drill did not run, it
says `NOT_RUN` and why.

## 1. Identity and authority

The Contract and annex bytes accepted by the Human Owner were copied into their
canonical paths unchanged. Their SHA-256 values were recomputed in the working
tree and in the Git index before the checkpoint, and the index blob identities
above are the ones Git will carry into the commit. The same check is repeated
against `HEAD:<path>` after the checkpoint and reported with the commit
identity in the chat handoff; the commit hash is deliberately not written into
this file because a commit cannot contain its own future hash.

The local working tree started from the accepted source base and nothing was
pulled, rebased, reset or cleaned. Every change is on the named branch on top
of that base as one checkpoint commit. No remote ref was read for authority or
written; the repository remote is untouched.

Authority consumed is exactly Contract §15 Level 1: read/modify source,
frontend, tests, configuration, canonical docs and the evolvable Design; add
forward migrations; build and run unit, integration, architecture and security
tests against isolated temporary databases, local HTTP and fakes; local Git
status/diff/log/add/commit. Nothing outside that row was done. Ordinary design,
test and local-commit approvals were not re-requested, as the Owner directed.

## 2. What was built

One new Spring Modulith module, `listingconversion`, plus bounded additions to
three existing modules, six forward migrations, a bilingual Console area and
the governance/traceability updates. Counts are from `git status` of the
checkpoint (paths are listed in the appendix):

| Area | New | Modified |
| --- | --- | --- |
| `listingconversion` (API types, application services, domain, JDBC, web) | 62 | 0 |
| `marketplaceintegration` (description command path, adapter, port, guard) | 18 | 8 |
| `operationsworkflow` (listing intake, launch, decision authority) | 8 | 7 |
| `identityaccess`, `analyticsdecision`, `adminobservability`, `shared` | 0 | 5 |
| Migrations V0074–V0079 | 6 | 0 (V0001–V0073 byte-identical to base) |
| `application.yaml` | 0 | 1 (two new worker flags, both `false`) |
| Backend tests (unit, architecture, IT, fixture) | 22 | 8 |
| Frontend (`src/listing`, `src/api/listingConversion.ts`, shell, tests) | 13 | 2 |
| Governance scripts and their tests | 0 | 5 |
| Canonical docs, runbooks, evidence, handoffs | 20 | 9 |

### 2.1 Shared-Spine touches and why each exists

| File | Change |
| --- | --- |
| `adminobservability/audit/AuditSourceDomain.java` | adds `LISTING_CONVERSION` so every listing read and change audits under its own domain |
| `analyticsdecision/SubjectKind.java` | adds the listing subject kind used by recommendations and evaluations |
| `identityaccess/ActionScopeCode.java` | adds the twelve listing scopes registered by V0074 |
| `operationsworkflow/ActionKind.java`, `GuardrailReason.java` | adds `LISTING_DESCRIPTION_CHANGE` and `LISTING_PROMOTION_ACTION`; listing guardrail reasons |
| `operationsworkflow/.../ApprovalService.java`, `ExecutionService.java`, `RecommendationService.java`, `GuardrailService.java`, `GuardrailRepository.java` | listing branch of the existing recommendation → guardrail → approval → execution chain; approval never creates a command; `previewListingAction` uses the database authority snapshot |
| `marketplaceintegration/.../CredentialDirectory.java`, `CredentialLookupRepository.java` | credential purpose `CONTENT_WRITE` resolution (no credential material is read or logged) |
| `marketplaceintegration/.../KillSwitchService.java`, `KillSwitchRepository.java` | `listing-description-write` flag family |
| `marketplaceintegration/.../WriteOperationSpec.java`, `WriteOperationRepository.java`, `PlatformCallSpecRepository.java`, `RequestTemplate.java` | description write registry shape and template checks; vendor DTOs stay inside the adapter |
| `shared/ErrorCode.java`, `ConsoleProblemAdvice.java` | thirteen listing error codes appended after `RAW_EVIDENCE_MISSING`; the order pin in `ErrorCodeTest` was extended, not reordered |

No second Raw store, Metric, Policy, Command or audit writer was created. Raw
provider responses are custodied through the existing `RawCustodyService`
under `lc-description-response`. Money stays decimal with explicit currency.

### 2.2 Console surface

Five package-private `@RestController @ConsoleApi` controllers under
`/api/v1/console/listing/{health,actions,manual,governance,catalogue}` and one
under `/api/v1/console/listing/description-commands` (marketplaceintegration).
Every endpoint narrows by `permittedStoreIds`, audits a READ or the change
under `LISTING_CONVERSION`, and answers refusals through
`OperationRejectedException.of(ErrorCode.X)`. Launch, occupation release,
containment stop/attest/consent and command resolution require step-up.

The desktop Console adds one queue entry and a `ListingConversionShell` with
Health, Actions, Manual, Governance panels in Chinese and Russian; the
language is remembered per browser and never changes permissions or amounts.
`backend-codes.json` carries 46 code families / 236 codes in both languages
and `ListingConversionCodesTest` fails the backend build if a family is missing.

### 2.3 Write authority, as enforced by the database

- `ops.lc_description_command`, `ops.lc_launch`, `ops.lc_exposure_occupation`
  and `ops.lc_containment` have no Java writer; only SECURITY DEFINER functions
  in V0077/V0078 insert, each consuming a one-use invocation proof issued by
  `iam.issue_ad_control_invocation_grant` for the listing purposes.
- `ops.lc_gate_authority` (with `production_write_enabled`), the Pilot
  allowlist, `core.lc_calibration_package/_value/_category`,
  `ops.lc_exposure_allowance` and `core.lc_summary_equivalence_profile` are
  Owner-published: no Java writer, pinned by `SoleAuthorityArchitectureTest`
  TC-AUTHORITY-004. No such row exists in any fixture except the fictional
  test fixture, where `production_write_enabled=false`.
- `ops.evaluate_lc_description_write_gate` closes on any of:
  `PRODUCTION_WRITE_DISABLED`, `ENTITY_NOT_ALLOWLISTED`, kill switch,
  `ACTION_NOT_LAUNCHED`, `SCOPE_CONTAINED`, binding gap, calibration not
  current, authorization expired, `KIZ_MARKED_UNDECLARED`. Leasing a command
  while the gate is closed is refused inside the database.
- `PlatformHttpDescriptionWriteAdapter` refuses before any socket when the
  capability has no verified operation, the credential purpose is wrong, the
  rendered body is not exactly one description attribute, or the destination
  is not registered. `marketops.listing-description-write.worker-enabled` is
  `false`, so no process in this checkpoint even attempts.
- Success requires readback; `UNKNOWN_REQUIRES_READBACK` has no edge to
  `EXECUTING` and only `MANUAL_RESOLUTION` or `READBACK_PENDING` follow it.
  There is no blind retry.

## 3. Migration chain, compatibility and rollback

| Version | Bytes | Lines | SHA-256 |
| --- | --- | --- | --- |
| V0074 | 25911 | 456 | `d0e4f69901e7…` |
| V0075 | 31910 | 571 | `be3289b284de…` |
| V0076 | 19926 | 370 | `e6ba9ea30a68…` |
| V0077 | 91248 | 1648 | `40e3c8fe150b…` |
| V0078 | 75771 | 1304 | `f7d5f190147b…` |
| V0079 | 19240 | 339 | `a1ae88615f82…` |

Full digests and per-version authority are in
[`MIGRATION-INVENTORY.json`](MIGRATION-INVENTORY.json). V0001–V0073 are
byte-identical to the source base (`git diff --quiet HEAD -- …/db/migration`).

Compatibility effects on existing objects, all widening:

- `platform.capability_operation_readback_shape_ck` now also admits a
  `description_observed_text_pointer` (V0074); existing price/ad rows satisfy
  the old branch unchanged.
- `ops.bind_price_authority_snapshot()` is `CREATE OR REPLACE`d in V0077 with a
  listing branch evaluated before the unchanged AD_BID and price branches;
  price and ad authority binding behave as before (AdBidGuardrailTest and the
  full unit suite pass).
- Vocabulary inserts (audit domain, action scopes, role matrix, workflow kinds,
  credential purpose, invocation purposes, control route inventory rows for all
  43 `lc_` tables as `NO_ROUTE`).

No data backfill exists or is needed: every new table starts empty and every
listing fact arrives through provenance-bound intake. Rollback is a later
forward migration; nothing in this checkpoint edits an applied migration.
`FlywayMigrationIT.APPROVED_MIGRATIONS` was extended to V0079 and the test
compiles; it is `NOT_RUN` here (no container runtime).

## 4. Clause → source → test → evidence

The per-criterion table (36 functional groups, 6 non-functional groups, 27
official-API scenarios) is [`acceptance-status.md`](acceptance-status.md) with
its machine form [`S4-AC-STATUS.json`](S4-AC-STATUS.json):
`LOCAL_VERIFIED 62`, `LOCAL_PARTIAL 5`, `NOT_RUN 2`. The clause-group view
below is what `docs/01-requirements/v1-traceability.csv` rows `S4-C01`..`S4-REL`
carry (status `IMPLEMENTING`, with the Level 1 qualifier in `notes`).

| Group | Principal source | Principal tests | Local status |
| --- | --- | --- | --- |
| C01 objective, candidates, responsibility | `ListingActionService`, `ListingActionIntakeService`, V0077 | ListingGuardrailTest, AffectedSetResolutionTest, ListingActionLaunchIT (compiled) | LOCAL_VERIFIED |
| C02 primary metric and evidence paths | `VisitConversion`, `EvidencePathQualification`, `VersionWindow`, V0075 | VisitConversionTest, EvidencePathQualificationTest, VersionWindowTest | LOCAL_VERIFIED |
| C03 protections and dependencies | `ProtectionVector`, `PromotionSimulator`, V0079 | ProtectionVectorTest, PromotionSimulatorTest | LOCAL_VERIFIED |
| C04 exact Description action, verification, recovery | `ListingDescriptionCommandWorker`, `DescriptionChangeGuard`, adapter, V0078 | DescriptionChangeGuardTest, ListingDescriptionCommandWorkerTest, RetryAfterUnitsTest, ListingDescriptionWriteGateIT (compiled), RecommendationStateTest TC-WF-004 | LOCAL_VERIFIED; real endpoint semantics EXTERNAL (OQ-121/122) |
| C05 cumulative allowance, containment, isolation | `ListingActionLaunchService`, `AllowanceCheck`, `IsolationScope`, V0077 | AllowanceCheckTest, IsolationScopeTest, ListingActionLaunchIT and ListingContainmentIT (compiled), local PG flow | LOCAL_VERIFIED |
| C06 queue, clocks, Console response | `ResponsibilityClocks`, `RecalculationService`, `src/listing` | ResponsibilityClocksTest, ListingConversion.test.tsx | LOCAL_VERIFIED |
| C07 two simple promotions lifecycle | `ManualPathService`, `PromotionSimulator` | PromotionSimulatorTest, ListingConversionForms.test.tsx | LOCAL_VERIFIED |
| C08 permission views, evidence, policy lifecycle | `CalibrationService`, V0074, V0076 | SoleAuthorityArchitectureTest TC-AUTHORITY-004, SchemaVocabularyAgreementTest TC-VOCAB-005/006, ListingConversionSchemaIT (compiled) | LOCAL_VERIFIED |
| C09 reports, knowledge, bounded AI | `ListingFactIntakeService`, `ListingHealthConsoleController` | ListingConversion.test.tsx, ListingConversionApi.test.ts | LOCAL_PARTIAL (no listing-specific AI projection; bounded AI Gateway reused as is) |
| C10 formal Outcome, nodes, stop | `EvaluationService`, `ProtectionVector`, V0079 | ProtectionVectorTest, ListingConversion.test.tsx TC-UI-LC-008 | LOCAL_VERIFIED |
| C11 Listing Health and collaboration | `ListingHealthAssessment`, `ListingHealthService`, `GovernanceService` | ListingHealthAssessmentTest, MaterialityClassifierTest | LOCAL_VERIFIED (C11-AC2 LOCAL_PARTIAL) |
| C12 dual platform, Pilot, batches, entry points | `src/listing`, `internal/web`, V0074 | ListingConversionCodesTest, ListingDescriptionWriteGateIT TC-LC-GATE-001/006 (compiled) | LOCAL_VERIFIED; no Pilot allowlist row exists |
| NFR inherited groups | whole checkpoint | see §5 | LOCAL_PARTIAL (NFR-AC1, NFR-AC2 NOT_RUN) |
| §17 API scenarios T01–T27 | fact tables, worker, adapter | as listed per scenario | LOCAL_VERIFIED against fictional fixtures only |

The 85 Owner decisions, the three local substitutions (DELTA-01/02/03 not
required) and Q085-B were implemented as written; no clause was reinterpreted
and no core requirement was found broken by reliable new evidence, so no
minimal-difference proposal is attached.

## 5. Commands, environments and results

Exact commands and results are in [`executable-evidence.md`](executable-evidence.md).
Summary as observed:

| Check | Result |
| --- | --- |
| `./mvnw -q -B -ntp test` (unit + architecture; Surefire excludes `*IT`) | 1716 tests, 0 failures, 0 errors, 0 skipped |
| `./mvnw -q -B -ntp -DskipTests test-compile` | every integration test compiles |
| Local isolated PostgreSQL 16, V0001–V0079 clean apply, role privileges, launch/allowance/gate/containment/re-enable/recovery flows through the application role | as recorded, every refusal by its named rule |
| Frontend `lint`, `typecheck`, `format:check`, `build`, `verify:bundle` | exit 0 |
| Frontend `test:ci` | 25 files, 379 tests; coverage statements 87.96 / branches 82.07 / functions 88.95 / lines 88.88 |
| `python3 scripts/validate_governance.py` | passed |
| `python3 scripts/validate_production_readiness.py` | passed |
| `python3 -m unittest discover -s tests -p 'test_*.py'` | 422 tests, OK |
| Secret/PII pattern scan over every changed path | no match |

Isolation: the PostgreSQL instance was started inside the session on
`127.0.0.1:55432` with local-only roles and discarded data; no shared,
integration, sandbox or production database, credential, account or Provider
endpoint was reachable or used.

### 5.1 NOT_RUN, with the blocking reason

| Item | Reason |
| --- | --- |
| Testcontainers integration tests (`FlywayMigrationIT`, `ControlEpochTriggerIT`, `ListingConversionSchemaIT`, `ListingActionLaunchIT`, `ListingDescriptionWriteGateIT`, `ListingContainmentIT`, all other `*IT`) | no container runtime in the session; compiled only; the same database functions were exercised by hand on the local isolated instance |
| Two-connection launch race (`ListingActionLaunchIT` TC-LC-LAUNCH-007) | same; the allowance function serialises on the allowance rows and the one-use proof, verified by reading, not by a concurrent run |
| Browser end-to-end scenarios | no built environment in the session |
| Availability, capacity, PITR, Raw custody drills (NFR-AC1, NFR-AC2) | require a built environment |
| Anything real (`F-M01`, `F-M02`, `F-S01`, `F-W01`, `F-W02`, `E-04`) | not authorized, not attempted |

## 6. Impact analysis

**Security and permissions.** Twelve new action scopes (`LISTING_CONVERSION_VIEW`,
`LISTING_ACTION_PREPARE/REVIEW/APPROVE_ORDINARY/APPROVE_MATERIAL/LAUNCH`,
`LISTING_MANUAL_EXECUTE/VERIFY`, `LISTING_CONTAINMENT_STOP/ATTEST/CONSENT`,
`LISTING_PROMOTION_MANAGE`) with a role matrix in V0074; `AUDITOR` holds view
only. Author/reviewer/approver/executor independence is enforced by the
database (`MO092`), not by the Console. Step-up is required for launch,
release, containment and command resolution. The application role cannot
insert commands, launches, occupations, containments or any Owner-published
row. The credential purpose `CONTENT_WRITE` is resolved by reference; no
credential value crosses a module boundary or a log line.

**Concurrency.** Allowance acquisition, occupation release and gate
evaluation are database functions running under row locks on the allowance
and action rows, consuming a one-use proof bound to the calling session; a
duplicate `create_lc_description_command` returns the same command id; leases
carry a fence and expire into recovery, never into a second executor.

**Recovery.** `recoverExpiredLeases` returns an expired lease to
`READBACK_PENDING` or `MANUAL_RESOLUTION`; `UNKNOWN_REQUIRES_READBACK` is
terminal for automation; compensation is a new audited command version, never
an in-place edit; the four runbooks under `docs/06-runbooks/listing-*.md`
describe each path with its refusal codes.

**UI and NFR.** UTF-8 Russian text end to end, keyboard-operable forms,
paginated lists with bounded limits, correlation identifiers on every attempt,
audit on every read and change, structured logs. Load, availability and PITR
were not measured (§5.1).

**AI.** No listing-specific AI projection or prompt was added; on-demand
assistance reuses the existing bounded AI Gateway with its existing
projection rules. Deterministic Listing Health, calibration, guardrail and gate
functions remain the only authority.

**Raw and data.** New fact tables are provenance-bound (`core.fact_provenance`)
and append-only with supersession, never overwrite. Visit facts carry an opaque
`visit_key`, channel and sellability; no buyer identity, contact or address
column exists in any `lc_` table. Raw provider responses are custodied by
digest through the existing Raw service.

**Provider.** Zero real calls. The only new outbound path is the description
adapter, which refuses before dispatch for an unverified capability, and the
capability is `UNVERIFIED` on both platforms
(`docs/04-api/V1_CAPABILITY_MATRIX.md` §5a).

## 7. Unmet external obligations and their consumers

| Obligation | State | Consumer |
| --- | --- | --- |
| `F-M01`, `F-M02` — real Ozon description-write evidence (attribute identity, endpoints, permission, length, marking declaration, retry-after unit, readback pointer) | EXTERNAL EVIDENCE REQUIRED; OQ-121 | Gate EV for Ozon; capability verification |
| `F-W01`, `F-W02` — equivalent Wildberries evidence or accepted Manual-only state | EXTERNAL EVIDENCE REQUIRED; OQ-122 | Gate EV for Wildberries |
| `F-S01` — official visit and purchase-attribution evidence or a proven summary equivalence profile | EXTERNAL EVIDENCE REQUIRED; OQ-124 | conversion measurement and Outcome evaluation on a real store |
| `E-04` — exact listing Gate EV envelope | NOT AUTHORIZED; OQ-125 | first real description write for evidence only |
| Real calibration package values and allowances | OPERATIONAL CONFIGURATION REQUIRED; OQ-123 | approval and launch on a real store |
| `S3-REL-001..024` | unchanged, production-blocking | SLICE-V1-003 release |

Documents in this checkpoint do not substitute for real account, display or
recovery proof, and no unproven metric was replaced by a proxy.

## 8. No-secret / no-PII confirmation

Every changed path was scanned for key, token, private-key, bearer,
e-mail, phone and identifier patterns with no match. Fixtures are fictional
(`lc-fictional-positive.sql` over `r1-fictional-positive.sql`); local database
roles use session-local passwords that exist only inside the discarded
instance and are not in the repository. No production data, credential or
Buyer PII was requested, seen or committed.

## 9. What the Controller is asked to do

Perform the one comprehensive Deep Review over the exact local checkpoint and
freeze the complete Finding Set. Claude did not review its own work for
release, did not open a Draft PR, and did not push. Remote transport of this
checkpoint requires its own valid, separately identified authority; a Level 3
publication decision on the exact commit and tree is the Controller's call,
and merge would still imply no production enablement.

## Appendix — changed paths in the checkpoint

Generated from `git status --porcelain -uall` immediately before the commit
(`A` added, `M` modified, `??` new; the contract and annex show as `A`).

```text
 M backend/marketops-server/src/main/java/com/mimococo/marketops/adminobservability/audit/AuditSourceDomain.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/analyticsdecision/SubjectKind.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/identityaccess/ActionScopeCode.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/adapter/http/RequestTemplate.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/application/CredentialDirectory.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/application/KillSwitchService.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/domain/WriteOperationSpec.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/infrastructure/jdbc/CredentialLookupRepository.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/infrastructure/jdbc/KillSwitchRepository.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/infrastructure/jdbc/PlatformCallSpecRepository.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/infrastructure/jdbc/WriteOperationRepository.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/ActionKind.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/GuardrailReason.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/internal/application/ApprovalService.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/internal/application/ExecutionService.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/internal/application/GuardrailService.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/internal/application/RecommendationService.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/internal/infrastructure/jdbc/GuardrailRepository.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/shared/ErrorCode.java
 M backend/marketops-server/src/main/java/com/mimococo/marketops/shared/internal/errors/ConsoleProblemAdvice.java
 M backend/marketops-server/src/main/resources/application.yaml
 M backend/marketops-server/src/test/java/com/mimococo/marketops/advertisingefficiency/AdvertisingNonGoalsTest.java
 M backend/marketops-server/src/test/java/com/mimococo/marketops/architecture/ModulithArchitectureTest.java
 M backend/marketops-server/src/test/java/com/mimococo/marketops/architecture/SchemaVocabularyAgreementTest.java
 M backend/marketops-server/src/test/java/com/mimococo/marketops/architecture/SoleAuthorityArchitectureTest.java
 M backend/marketops-server/src/test/java/com/mimococo/marketops/database/FlywayMigrationIT.java
 M backend/marketops-server/src/test/java/com/mimococo/marketops/operationsworkflow/RecommendationStateTest.java
 M backend/marketops-server/src/test/java/com/mimococo/marketops/operationsworkflow/internal/application/AdBidGuardrailTest.java
 M backend/marketops-server/src/test/java/com/mimococo/marketops/shared/ErrorCodeTest.java
 M docs/00-governance/CURRENT_STATE.md
 M docs/00-governance/DECISION_LOG.md
 M docs/00-governance/OPEN_QUESTIONS.md
 M docs/01-requirements/v1-traceability.csv
AM docs/02-architecture/designs/SLICE-V1-004-design.md
A  docs/03-work-items/SLICE-V1-004-promotion-listing-conversion-acceptance.md
A  docs/03-work-items/SLICE-V1-004-promotion-listing-conversion.md
 M docs/03-work-items/V1_DELIVERY_SLICES.md
 M docs/04-api/V1_CAPABILITY_MATRIX.md
 M docs/05-testing/V1_PRODUCTION_ASSURANCE_MATRIX.md
 M docs/06-runbooks/README.md
 M docs/07-phase-evidence/README.md
A  docs/08-handoffs/OWNER-SLICE-V1-004-CONTRACT-ACCEPTANCE-EVIDENCE.md
A  docs/08-handoffs/OWNER-SLICE-V1-004-CONTRACT-ACCEPTANCE-STATEMENT.txt
 M frontend/marketops-console/src/ConsoleShell.tsx
 M frontend/marketops-console/src/api/console.ts
 M scripts/validate_governance.py
 M scripts/validate_production_readiness.py
 M tests/test_finalize_slice3_rework_assessment.py
 M tests/test_validate_governance.py
 M tests/test_validate_production_readiness.py
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/AllowanceView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/BatchView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/CandidateKind.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/CandidateView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ContainmentCauseClass.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ContainmentView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ConversionMeasurementView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/EvaluationView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/EvidencePath.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ExecutionPath.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/LateAssociationView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ListingActionState.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ListingActionView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ListingConversionCodes.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ListingHealthView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ManualPacketView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/MaterialityRoute.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/NodeVerdict.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/PromotionEngagementView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ProtectionVerdict.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/RatioState.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/RecalculationClass.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/RecalculationQueueView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/ReleaseBasis.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/SimulationView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/CalibrationService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/ConversionMeasurementService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/EvaluationService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/GovernanceService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/ListingActionDecisionService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/ListingActionService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/ListingConversionScheduler.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/ListingFactIntakeService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/ListingHealthService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/ListingScopeAuthorization.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/ManualPathService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/application/RecalculationService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/config/ListingConversionProperties.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/config/ListingConversionRuntimeConfiguration.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/AffectedSetResolution.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/AllowanceCheck.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/EvidencePathQualification.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/IsolationScope.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/ListingHealthAssessment.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/MaterialityClassifier.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/PromotionSimulator.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/ProtectionVector.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/ResponsibilityClocks.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/VersionWindow.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/domain/VisitConversion.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/infrastructure/jdbc/CalibrationRepository.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/infrastructure/jdbc/EvaluationRepository.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/infrastructure/jdbc/GovernanceRepository.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/infrastructure/jdbc/ListingActionRepository.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/infrastructure/jdbc/ListingFactRepository.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/infrastructure/jdbc/ListingHealthRepository.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/infrastructure/jdbc/ManualPathRepository.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/web/ListingActionConsoleController.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/web/ListingCatalogueController.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/web/ListingGovernanceConsoleController.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/web/ListingHealthConsoleController.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/listingconversion/internal/web/ListingManualConsoleController.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/ListingDescriptionCommandGateway.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/ListingDescriptionCommandRequest.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/ListingDescriptionCommandState.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/ListingDescriptionCommandView.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/adapter/http/PlatformHttpDescriptionWriteAdapter.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/application/ListingDescriptionCommandScheduler.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/application/ListingDescriptionCommandService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/application/ListingDescriptionCommandWorker.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/application/ListingDescriptionResolutionService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/config/ListingDescriptionWriteProperties.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/config/ListingDescriptionWriteRuntimeConfiguration.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/domain/DescriptionChangeGuard.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/domain/RetryAfterUnits.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/infrastructure/jdbc/ListingDescriptionCommandRepository.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/internal/web/ListingDescriptionCommandConsoleController.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/port/DescriptionWritePort.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/port/DescriptionWriteRequest.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/port/DescriptionWriteResult.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/ListingActionDecisionAuthority.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/ListingActionIntake.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/ListingActionLaunch.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/ListingActionProposal.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/ListingDecisionScope.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/ListingImpactPreview.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/internal/application/ListingActionIntakeService.java
?? backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/internal/application/ListingActionLaunchService.java
?? backend/marketops-server/src/main/resources/db/migration/V0074__widen_shared_spine_for_listing_conversion.sql
?? backend/marketops-server/src/main/resources/db/migration/V0075__create_listing_conversion_facts_and_health.sql
?? backend/marketops-server/src/main/resources/db/migration/V0076__create_listing_calibration_and_exposure_allowance.sql
?? backend/marketops-server/src/main/resources/db/migration/V0077__create_listing_actions_launch_manual_path_and_containment.sql
?? backend/marketops-server/src/main/resources/db/migration/V0078__create_listing_description_command_outbox_readback_and_gate.sql
?? backend/marketops-server/src/main/resources/db/migration/V0079__create_listing_evaluation_outcome_late_association_and_recalculation.sql
?? backend/marketops-server/src/test/java/com/mimococo/marketops/ListingActionLaunchIT.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/ListingContainmentIT.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/ListingConversionFixture.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/ListingDescriptionWriteGateIT.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/database/ListingConversionSchemaIT.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/ListingConversionCodesTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/AffectedSetResolutionTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/AllowanceCheckTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/EvidencePathQualificationTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/IsolationScopeTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/ListingHealthAssessmentTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/MaterialityClassifierTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/PromotionSimulatorTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/ProtectionVectorTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/ResponsibilityClocksTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/VersionWindowTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/listingconversion/internal/domain/VisitConversionTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/marketplaceintegration/internal/application/ListingDescriptionCommandWorkerTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/marketplaceintegration/internal/domain/DescriptionChangeGuardTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/marketplaceintegration/internal/domain/RetryAfterUnitsTest.java
?? backend/marketops-server/src/test/java/com/mimococo/marketops/operationsworkflow/internal/application/ListingGuardrailTest.java
?? backend/marketops-server/src/test/resources/listing/lc-fictional-positive.sql
?? docs/06-runbooks/listing-containment-and-reenablement.md
?? docs/06-runbooks/listing-description-command-resolution.md
?? docs/06-runbooks/listing-launch-and-allowance.md
?? docs/06-runbooks/listing-manual-path.md
?? docs/07-phase-evidence/SLICE-V1-003/post-merge-readback-20260907/POST-MERGE-ACCEPTANCE-AND-CLOSURE-SNAPSHOT.md
?? docs/07-phase-evidence/SLICE-V1-003/post-merge-readback-20260907/SHA256SUMS
?? docs/07-phase-evidence/SLICE-V1-003/post-merge-readback-20260907/VERDICT.json
?? docs/07-phase-evidence/SLICE-V1-004/CONTRACT-NAVIGATION-INDEX.json
?? docs/07-phase-evidence/SLICE-V1-004/CONTRACT-NAVIGATION-INDEX.md
?? docs/07-phase-evidence/SLICE-V1-004/MIGRATION-INVENTORY.json
?? docs/07-phase-evidence/SLICE-V1-004/S4-AC-STATUS.json
?? docs/07-phase-evidence/SLICE-V1-004/acceptance-status.md
?? docs/07-phase-evidence/SLICE-V1-004/controller-handoff.md
?? docs/07-phase-evidence/SLICE-V1-004/executable-evidence.md
?? docs/08-handoffs/OWNER-SLICE-V1-003-FORMAL-CLOSURE-RECEIPT-ECB3385-R1.md
?? frontend/marketops-console/src/__tests__/ListingConversion.test.tsx
?? frontend/marketops-console/src/__tests__/ListingConversionApi.test.ts
?? frontend/marketops-console/src/__tests__/ListingConversionForms.test.tsx
?? frontend/marketops-console/src/api/listingConversion.ts
?? frontend/marketops-console/src/listing/ListingActionsPanel.tsx
?? frontend/marketops-console/src/listing/ListingCommon.tsx
?? frontend/marketops-console/src/listing/ListingConversionShell.tsx
?? frontend/marketops-console/src/listing/ListingGovernancePanel.tsx
?? frontend/marketops-console/src/listing/ListingHealthPanel.tsx
?? frontend/marketops-console/src/listing/ListingManualPanel.tsx
?? frontend/marketops-console/src/listing/i18n/backend-codes.json
?? frontend/marketops-console/src/listing/i18n/language.tsx
?? frontend/marketops-console/src/listing/i18n/ui.ts
```
