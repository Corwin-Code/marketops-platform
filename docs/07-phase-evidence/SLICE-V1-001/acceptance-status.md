# SLICE-V1-001 acceptance status

```yaml
document_type: acceptance_criteria_status
slice: SLICE-V1-001
contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
accepted_amendments: SLICE-V1-001-AMENDMENT-001,SLICE-V1-001-AMENDMENT-002
amendment_001_sha256: 8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d
amendment_002_sha256: 92fdd8d67b327fbd2288ba99290b5b59f2797106c4b691ce2bff22bb80198b93
amendment_002_acceptance_evidence_sha256: f28ad2395e22a7dd996ace6db4883f35e408bb4ea24de61e777e03b8616d9923
assessed_at: 2026-08-30
assessed_against: SUPPLEMENTAL_R2_DRAFT_PR_22_CONTAINING_COMMIT
remote_publication: PUBLISHED_OPEN_DRAFT_PR_22
initial_published_head: c3d2160a9c302d993e2b01a08946f46fae0b01d5
initial_published_tree: 9d7641eccc2d233bf2c5615e7c4776721269bc15
initial_tested_merge: 353670b4a311f98b56fae593f8b2b34d5f39a80e
initial_tested_merge_tree: 9d7641eccc2d233bf2c5615e7c4776721269bc15
initial_remote_ci: PASS_12_OF_12_REQUIRED_CONTEXTS
final_candidate_identity_resolution: THIS_DOCUMENT_CONTAINING_COMMIT_AND_PR_22_LIVE_REFS
frozen_findings_sha256: 8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8
supplemental_r2_review_sha256: c772c76c89b753d4694ee5ec1eceddad3451ab7ef6acc2e36416d9d4171f26ff
deferred_evidence_register: docs/07-phase-evidence/SLICE-V1-001/deferred-evidence-register.json
production_write_enabled: false
```

## Interpretation

The Human Owner accepted exact [Amendment-001](../../03-work-items/SLICE-V1-001-AMENDMENT-001-YANDEX-MANAGED-PG-BOOTSTRAP.md)
and exact [Amendment-002](../../03-work-items/SLICE-V1-001-AMENDMENT-002-DEFERRED-REAL-INTEGRATION-AND-PREPRODUCTION-ASSURANCE.md),
each with separate durable acceptance provenance. Amendment-002 closes only
`S1-R2-G001`; it does not waive `S1-R2-F001` through `S1-R2-F009` or any retained
engineering requirement.

This is an R2 candidate assessment, not a Controller verdict. Draft PR #22 was
published under explicit Human Owner transport authority. Its initial exact
Head/tree and tested merge passed all 12 required contexts; the containing
metadata commit must be reverified locally and remotely and its final exact
identity is bound in the PR body and live refs. A non-deferred row uses
`IMPLEMENTED_UNPROVEN` until the Controller R2 verdict exists. Every exact
Amendment-002 deferred row uses
`OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001`, with its retained engineering
evidence and future obligation bound in the
[Deferred Evidence Register](deferred-evidence-register.json). It may never be
relabeled `VERIFIED`, `EXECUTABLY_VERIFIED`, `NOT_APPLICABLE`,
`REAL_PROVIDER_PROVEN` or `PRODUCTION_READY` on engineering fixtures.

No criterion is self-closed here. R1/C3 evidence remains historical and cannot
substitute for exact R2 Head verification. `S1-AC-039` remains non-deferred and
must pass on the exact R2 release Head. `S1-AC-041` also remains non-deferred;
merge, deployment and production enablement are distinct later authorizations.

## All 41 criteria

| ID | Status | Contract requirement | R2 verification / historical R1 mapping | Remaining external boundary |
| --- | --- | --- | --- | --- |
| `S1-AC-001` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | public OIDC login and mandatory MFA work in the approved environment; unauthenticated access is denied. | S1-R2-F008; historical S1-F010 | Exact deferred portion is in the Deferred Evidence Register |
| `S1-AC-002` | `IMPLEMENTED_UNPROVEN` | MarketOps backend enforces Role + Store + Platform + Action Scope; horizontal/vertical privilege escalation tests fail closed. | S1-F001, S1-F004 | No additional external boundary identified in the frozen set |
| `S1-AC-003` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | user disable/revoke and sensitive-action reauthentication/session behavior are verified and audited. | S1-F004 | EXT-001: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-004` | `IMPLEMENTED_UNPROVEN` | no Secret, Buyer PII, signed object URL or unsafe Raw content appears in Git, browser bundle, log, trace, error or AI invocation. | S1-F002, S1-F003, S1-F004 | No additional external boundary identified in the frozen set |
| `S1-AC-005` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | Yandex production topology is reproducible from reviewed IaC and uses least-privilege workload identities/roles. | S1-F010 | Amendment-001: real Yandex staging verification remains EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-006` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | PostgreSQL PITR/backup and object-storage retention/integrity controls are configured and an actual restore drill meets the accepted target. | S1-F010 | EXT-002: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-007` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | monitoring, alerting and runbooks cover the critical Slice paths; failure injection proves operator-visible degradation rather than silent loss. | S1-F005, S1-F010, S1-F012 | No additional external boundary identified in the frozen set |
| `S1-AC-008` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | current official-source and real-account evidence proves required Ozon read capabilities and `PRICE_CHANGE` write/readback behavior. | S1-F003, S1-F007 | EXT-003: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-009` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | equivalent evidence exists for Wildberries without pretending its task/status/error model is identical to Ozon. | S1-F003, S1-F007 | EXT-003: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-010` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | quotas, pagination, freshness, error/timeout/unknown-result and Credential scope are recorded with last-verified date and contract tests. | S1-F003, S1-F006, S1-F007 | EXT-003: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-011` | `IMPLEMENTED_UNPROVEN` | scheduled/manual Slice acquisition is restartable, rate-limited, retry-budgeted and fenced on a real PostgreSQL path. | S1-F005, S1-F006 | No additional external boundary identified in the frozen set |
| `S1-AC-012` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | success and business-meaningful failure bytes are immutable in the approved object store with exact hash/length/provenance and read verification. | S1-F002 | No additional external boundary identified in the frozen set |
| `S1-AC-013` | `IMPLEMENTED_UNPROVEN` | cursor cannot outrun committed verified Raw under crash/failure injection. | S1-F006 | No additional external boundary identified in the frozen set |
| `S1-AC-014` | `IMPLEMENTED_UNPROVEN` | duplicate/replay/backfill processing creates no duplicate logical effects; replay makes zero Marketplace acquisition calls. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-015` | `IMPLEMENTED_UNPROVEN` | schema drift, unknown field/state and missing/orphan object paths are observable and recoverable. | S1-F006 | No additional external boundary identified in the frozen set |
| `S1-AC-016` | `IMPLEMENTED_UNPROVEN` | pilot listings/variants map to Internal SKU or an explicit conflict queue with effective-time version history; unresolved mapping blocks write. | S1-F004 | No additional external boundary identified in the frozen set |
| `S1-AC-017` | `IMPLEMENTED_UNPROVEN` | COGS, physical stock and finance facts can be entered manually and imported through the productized Excel/CSV path with preview, hash, validation, rejection, audit and replay. | S1-F004, S1-F008 | No additional external boundary identified in the frozen set |
| `S1-AC-018` | `IMPLEMENTED_UNPROVEN` | duplicate, malformed, stale and conflicting imports are handled deterministically; no silent overwrite occurs. | S1-F008 | No additional external boundary identified in the frozen set |
| `S1-AC-019` | `IMPLEMENTED_UNPROVEN` | key funnel, stock, return, ad and profit facts are traceable to source Raw and show Source Time/Freshness/Confidence. | S1-F008 | No additional external boundary identified in the frozen set |
| `S1-AC-020` | `IMPLEMENTED_UNPROVEN` | Contribution Profit/Minimum Price and canonical/estimated states reproduce against versioned golden examples; missing inputs do not produce fake precision. | S1-R2-F001/F002/F003/F005/F006 candidate fix; Controller verification pending | No additional external boundary identified in the frozen set |
| `S1-AC-021` | `IMPLEMENTED_UNPROVEN` | Completed/Retained/Settled Sale and late-return/adjustment behavior are tested without rewriting historical source facts. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-022` | `IMPLEMENTED_UNPROVEN` | deterministic diagnosis and rule order correctly identify or decline Low Impression/CTR/Conversion, High Return, Stockout Risk, Negative Margin and Data Blocked cases. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-023` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | AI projection allowlist and PII/Secret negative tests pass. | S1-F003, S1-F009 | EXT-007: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-024` | `IMPLEMENTED_UNPROVEN` | structured AI output distinguishes Fact/Inference/Recommendation/ Unknown and rejects nonexistent Metric/Evidence references. | S1-F009 | No additional external boundary identified in the frozen set |
| `S1-AC-025` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | model failure, timeout, malformed output and provider unavailability degrade safely; no deterministic Gate is bypassed. | S1-F003, S1-F005, S1-F009 | EXT-007: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-026` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | approved golden diagnostic cases demonstrate useful cross-domain reasoning while preserving explicit uncertainty and competing explanations. | S1-F009 | EXT-004: OWNER_EVIDENCE_PENDING |
| `S1-AC-027` | `IMPLEMENTED_UNPROVEN` | Recommendation → Task → Approval/Policy Authorization is complete, scoped, expiring, attributable and immutable in audit. | S1-F001, S1-F004 | No additional external boundary identified in the frozen set |
| `S1-AC-028` | `IMPLEMENTED_UNPROVEN` | Commercial Policy versions and overrides apply deterministically; missing/invalid/expired policy denies execution. | S1-R2-F004 candidate fix; Controller verification pending | No additional external boundary identified in the frozen set |
| `S1-AC-029` | `IMPLEMENTED_UNPROVEN` | price Dry Run/Impact Preview uses current canonical facts, entity version and prior platform value; stale previews cannot execute. | S1-R2-F001/F003/F004/F005/F006 candidate fix; historical S1-F001 | No additional external boundary identified in the frozen set |
| `S1-AC-030` | `IMPLEMENTED_UNPROVEN` | command idempotency, lease/fence, retry and state transitions pass unit, property and real-database tests. | S1-F001, S1-F002, S1-F005 | No additional external boundary identified in the frozen set |
| `S1-AC-031` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | low-risk real Ozon bounded verification write reaches the intended final value, Readback and complete Audit; unknown result is safely resolvable. The operation is performed only inside an exact unexpired Gate-EV authorization envelope. | S1-F002, S1-F007 | EXT-005: GATE_EV_PENDING |
| `S1-AC-032` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | equivalent real WB bounded verification evidence exists, including native asynchronous/partial/quarantine semantics where applicable, and is generated only under its own exact Gate-EV authorization. | S1-F002, S1-F007 | EXT-005: GATE_EV_PENDING |
| `S1-AC-033` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | Restore/Compensate is actually verified on both platforms without overwriting a later change; its delta/exposure, pre-state, operator, abort, Readback and evidence retention are bounded by Gate EV. | S1-F002, S1-F005 | EXT-005: GATE_EV_PENDING |
| `S1-AC-034` | `IMPLEMENTED_UNPROVEN` | global and scoped Kill Switches prevent new writes; disabled flags are fail-closed under restart/concurrency. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-035` | `IMPLEMENTED_UNPROVEN` | browser E2E proves login → priority queue → SKU diagnosis → evidence → recommendation → approval/policy → price command → readback timeline. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-036` | `IMPLEMENTED_UNPROVEN` | UI never labels stale/estimated/unknown/readback-mismatch state as confirmed success. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-037` | `IMPLEMENTED_UNPROVEN` | common priority/SKU queries meet accepted performance targets on representative data; async export is used for large output. | S1-F012 | No additional external boundary identified in the frozen set |
| `S1-AC-038` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | support personnel can recover representative API outage, backlog, replay, AI failure, unknown write and database/object restore scenarios using committed runbooks. | S1-F005, S1-F006, S1-F010, S1-F012 | No additional external boundary identified in the frozen set |
| `S1-AC-039` | `IMPLEMENTED_UNPROVEN` | all required CI/security/contract/integration/browser checks pass on the exact release Head; no unresolved BLOCKER/MAJOR finding remains. | S1-R2-F009 candidate fix; exact R2 Head evidence and Controller verdict pending | No additional external boundary identified in the frozen set |
| `S1-AC-040` | `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | the Pilot Cohort, approved users, Stores, Capabilities, Policy limits, monitoring window and rollback/kill criteria are explicitly recorded. | Independent verification of mapped evidence pending | EXT-006: OWNER_PENDING |
| `S1-AC-041` | `IMPLEMENTED_UNPROVEN` | merge, deployment and production enablement are distinct authorizations; the code ships with production writes disabled. | S1-R2-F007/F008/F009 candidate fix; merge and later authorities remain absent | No additional external boundary identified in the frozen set |

## Criterion-specific verification sources

The [machine-readable mapping](rework-r1/criterion-evidence-map.json) binds all
41 exact criterion IDs to existing test/control paths and keeps their external
and Owner boundaries explicit. These are verification sources, not claims that
fixtures satisfy real-provider acceptance. The final delivery packet supplies
the exact commit/merge and fresh full verification/CI binding for these sources.

| Criterion | Test/control sources |
| --- | --- |
| `S1-AC-001` | `SignedBearerIdentityIT.java`; `business-journey.spec.ts` |
| `S1-AC-002` | `OperatingFlowIT.java`; `PriceWritePathIT.java`; `SignedBearerIdentityIT.java` |
| `S1-AC-003` | `SignedBearerIdentityIT.java` |
| `S1-AC-004` | `BoundedOutboundHttpTest.java`; `MountedSecretFilesystemIT.java`; `OutputValidatorTest.java`; `LoggingContractTest.java`; `DiagnosticExportIT.java`; `verify_coverage_thresholds.sh`; `operating-console.spec.ts` |
| `S1-AC-005` | `verify_terraform.py`; `validate_terraform_plan.py`; `test_validate_terraform_plan.py`; `test_yandex_runtime.py` |
| `S1-AC-006` | `RepresentativePerformanceIT.java`; `ManagedProfileMigrationIT.java` |
| `S1-AC-007` | `OperatingFlowIT.java`; `PriceCommandWorkerIT.java`; `test_yandex_telemetry.py`; `health-shell.spec.ts` |
| `S1-AC-008` | `RegistryVerificationFlowIT.java`; `PlatformHttpAdaptersTest.java` |
| `S1-AC-009` | `RegistryVerificationFlowIT.java`; `PlatformHttpAdaptersTest.java` |
| `S1-AC-010` | `PlatformHttpAdaptersTest.java`; `AcquisitionPageWorkerTest.java`; `IngestionAuthorityAndEvidenceIT.java` |
| `S1-AC-011` | `AcquisitionRunnerTest.java`; `IngestionAuthorityAndEvidenceIT.java`; `AuthorizedAcquisitionFlowIT.java` |
| `S1-AC-012` | `RawCustodyLocatorTest.java`; `FilesystemObjectStorageTest.java`; `S3CompatibleObjectStorageTest.java`; `DiagnosticExportIT.java` |
| `S1-AC-013` | `AuthorizedAcquisitionFlowIT.java`; `IngestionAuthorityAndEvidenceIT.java` |
| `S1-AC-014` | `StoredRawReplayIT.java`; `FactRecorderTest.java`; `DiagnosticExportIT.java` |
| `S1-AC-015` | `StoredRawReplayIT.java`; `NormalizationRunnerTest.java`; `AcquisitionPageWorkerTest.java` |
| `S1-AC-016` | `OperatingFlowIT.java`; `PriceWritePathIT.java` |
| `S1-AC-017` | `FileIntakeFlowIT.java`; `SpreadsheetReaderTest.java`; `ImportRowValidatorTest.java` |
| `S1-AC-018` | `FileIntakeFlowIT.java`; `ImportRowValidatorTest.java` |
| `S1-AC-019` | `OperatingFlowIT.java`; `StoredRawReplayIT.java` |
| `S1-AC-020` | `MetricEngineTest.java`; `ComputedMetricIdentityTest.java`; `AnalyticsCalculationServiceWindowTest.java`; `OperatingFlowIT.java` |
| `S1-AC-021` | `OperatingFlowIT.java` |
| `S1-AC-022` | `DiagnosisEngineTest.java`; `OperatingFlowIT.java` |
| `S1-AC-023` | `OperatingFlowIT.java`; `OutputValidatorTest.java`; `HttpModelGatewayTest.java` |
| `S1-AC-024` | `OutputValidatorTest.java` |
| `S1-AC-025` | `OperatingFlowIT.java`; `HttpModelGatewayTest.java` |
| `S1-AC-026` | `OutputValidatorTest.java` |
| `S1-AC-027` | `OperatingFlowIT.java`; `PriceWritePathIT.java`; `RegistryVerificationFlowIT.java` |
| `S1-AC-028` | `GuardrailEngineTest.java`; `MetricEngineTest.java`; `OperatingFlowIT.java` |
| `S1-AC-029` | `OperatingFlowIT.java`; `PriceWritePathIT.java` |
| `S1-AC-030` | `PriceWritePathIT.java`; `PriceCommandWorkerIT.java` |
| `S1-AC-031` | `PriceCommandWorkerIT.java`; `RegistryVerificationFlowIT.java` |
| `S1-AC-032` | `PriceCommandWorkerIT.java`; `PlatformHttpAdaptersTest.java` |
| `S1-AC-033` | `PriceCommandWorkerIT.java`; `PriceWritePathIT.java` |
| `S1-AC-034` | `PriceWritePathIT.java`; `PriceCommandWorkerIT.java`; `ApplicationEnvironmentFailClosedTest.java` |
| `S1-AC-035` | `business-journey.spec.ts` |
| `S1-AC-036` | `ConsoleJourney.test.tsx`; `operating-console.spec.ts` |
| `S1-AC-037` | `RepresentativePerformanceIT.java`; `DiagnosticExportIT.java`; `operating-console.spec.ts` |
| `S1-AC-038` | `StoredRawReplayIT.java`; `OperatingFlowIT.java`; `PriceCommandWorkerIT.java`; `RepresentativePerformanceIT.java`; `business-journey.spec.ts`; `health-shell.spec.ts` |
| `S1-AC-039` | `required-status-checks.json`; `test_required_status_checks.py`; `validate_governance.py`; `validate_production_readiness.py`; `verify_coverage_thresholds.sh`; `backend.yml`; `infrastructure.yml` |
| `S1-AC-040` | `OPEN_QUESTIONS.md` |
| `S1-AC-041` | `ApplicationEnvironmentFailClosedTest.java`; `ApplicationConfigurationTest.java`; `PriceWritePathIT.java`; `validate_governance.py`; `test_validate_governance.py` |

## Summary

| Status | Count |
| --- | --- |
| `IMPLEMENTED_UNPROVEN` | 24 |
| `OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` | 17 |

The nine Supplemental R2 engineering findings remain pending the Controller's
exact-final-Head verdict. The seventeen deferred rows remain production-blocking
future `RELEASE-V1-001` obligations. No real provider, deploy, Gate EV, Gate E,
Pilot, Slice closure or V1 completion is claimed.
