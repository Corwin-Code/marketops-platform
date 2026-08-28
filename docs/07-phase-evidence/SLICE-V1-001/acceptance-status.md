# SLICE-V1-001 acceptance status

```yaml
document_type: acceptance_criteria_status
slice: SLICE-V1-001
contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
accepted_amendments: SLICE-V1-001-AMENDMENT-001
amendment_sha256: 8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d
assessed_at: 2026-08-28
assessed_against: PR20_REWORK_SUBMITTED_FOR_CLOSURE
remote_publication: PUBLISHED_DRAFT_CANDIDATE
frozen_findings_sha256: 8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8
production_write_enabled: false
```

## Interpretation

The Human Owner accepted exact [Amendment-001](../../03-work-items/SLICE-V1-001-AMENDMENT-001-YANDEX-MANAGED-PG-BOOTSTRAP.md),
with [acceptance provenance](../../08-handoffs/OWNER-SLICE-V1-001-AMENDMENT-001-ACCEPTANCE-EVIDENCE.md).
The S1-F010 compatibility decision is no longer pending. Local PG17 bootstrap,
negative/equivalence/upgrade/restore and packaged runtime checks now pass;
C3 full local regression and remote CI also pass. No criterion status changes
merely because the decision is accepted. Real Yandex staging
verification remains required for S1-AC-005/006.

This is the candidate branch assessment, not a Controller verdict. The status
column retains the frozen review disposition pending independent closure; it
does not assert that the corrected implementation still reproduces each defect.
The correction map and executable receipts record Codex's work separately.
Criteria without an open finding also await independent verification.
External evidence is tracked independently; fixing a local defect cannot clear
its external or Owner boundary. The initial Maker assessment is preserved at
reviewed commit `30d16e5d7db2d2190635a06fececd5883093a876` and is superseded as
current state by this matrix.

Allowed states are `EXECUTABLY_VERIFIED`, `IMPLEMENTED_UNPROVEN`,
`IMPLEMENTATION_DEFECT`, `EXTERNAL_EVIDENCE_PENDING`, `GATE_EV_PENDING`,
`OWNER_PENDING`, and `NOT_APPLICABLE`. `EXECUTABLY_VERIFIED` requires the complete
criterion's applicable evidence, not merely a fixture or public documentation.
No criterion is self-closed in this candidate matrix. Use the
[final handoff index](rework-r1/final-handoff.md) and the exact-final-Head delivery
packet together; C3 evidence alone does not verify the later canonical commit.

## All 41 criteria

| ID | Status | Contract requirement | Open finding / verification | Remaining external boundary |
| --- | --- | --- | --- | --- |
| `S1-AC-001` | `IMPLEMENTATION_DEFECT` | public OIDC login and mandatory MFA work in the approved environment; unauthenticated access is denied. | S1-F010 | EXT-001: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-002` | `IMPLEMENTATION_DEFECT` | MarketOps backend enforces Role + Store + Platform + Action Scope; horizontal/vertical privilege escalation tests fail closed. | S1-F001, S1-F004 | No additional external boundary identified in the frozen set |
| `S1-AC-003` | `IMPLEMENTATION_DEFECT` | user disable/revoke and sensitive-action reauthentication/session behavior are verified and audited. | S1-F004 | EXT-001: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-004` | `IMPLEMENTATION_DEFECT` | no Secret, Buyer PII, signed object URL or unsafe Raw content appears in Git, browser bundle, log, trace, error or AI invocation. | S1-F002, S1-F003, S1-F004 | No additional external boundary identified in the frozen set |
| `S1-AC-005` | `IMPLEMENTATION_DEFECT` | Yandex production topology is reproducible from reviewed IaC and uses least-privilege workload identities/roles. | S1-F010 | Amendment-001: real Yandex staging verification remains EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-006` | `IMPLEMENTATION_DEFECT` | PostgreSQL PITR/backup and object-storage retention/integrity controls are configured and an actual restore drill meets the accepted target. | S1-F010 | EXT-002: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-007` | `IMPLEMENTATION_DEFECT` | monitoring, alerting and runbooks cover the critical Slice paths; failure injection proves operator-visible degradation rather than silent loss. | S1-F005, S1-F010, S1-F012 | No additional external boundary identified in the frozen set |
| `S1-AC-008` | `IMPLEMENTATION_DEFECT` | current official-source and real-account evidence proves required Ozon read capabilities and `PRICE_CHANGE` write/readback behavior. | S1-F003, S1-F007 | EXT-003: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-009` | `IMPLEMENTATION_DEFECT` | equivalent evidence exists for Wildberries without pretending its task/status/error model is identical to Ozon. | S1-F003, S1-F007 | EXT-003: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-010` | `IMPLEMENTATION_DEFECT` | quotas, pagination, freshness, error/timeout/unknown-result and Credential scope are recorded with last-verified date and contract tests. | S1-F003, S1-F006, S1-F007 | EXT-003: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-011` | `IMPLEMENTATION_DEFECT` | scheduled/manual Slice acquisition is restartable, rate-limited, retry-budgeted and fenced on a real PostgreSQL path. | S1-F005, S1-F006 | No additional external boundary identified in the frozen set |
| `S1-AC-012` | `IMPLEMENTATION_DEFECT` | success and business-meaningful failure bytes are immutable in the approved object store with exact hash/length/provenance and read verification. | S1-F002 | No additional external boundary identified in the frozen set |
| `S1-AC-013` | `IMPLEMENTATION_DEFECT` | cursor cannot outrun committed verified Raw under crash/failure injection. | S1-F006 | No additional external boundary identified in the frozen set |
| `S1-AC-014` | `IMPLEMENTED_UNPROVEN` | duplicate/replay/backfill processing creates no duplicate logical effects; replay makes zero Marketplace acquisition calls. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-015` | `IMPLEMENTATION_DEFECT` | schema drift, unknown field/state and missing/orphan object paths are observable and recoverable. | S1-F006 | No additional external boundary identified in the frozen set |
| `S1-AC-016` | `IMPLEMENTATION_DEFECT` | pilot listings/variants map to Internal SKU or an explicit conflict queue with effective-time version history; unresolved mapping blocks write. | S1-F004 | No additional external boundary identified in the frozen set |
| `S1-AC-017` | `IMPLEMENTATION_DEFECT` | COGS, physical stock and finance facts can be entered manually and imported through the productized Excel/CSV path with preview, hash, validation, rejection, audit and replay. | S1-F004, S1-F008 | No additional external boundary identified in the frozen set |
| `S1-AC-018` | `IMPLEMENTATION_DEFECT` | duplicate, malformed, stale and conflicting imports are handled deterministically; no silent overwrite occurs. | S1-F008 | No additional external boundary identified in the frozen set |
| `S1-AC-019` | `IMPLEMENTATION_DEFECT` | key funnel, stock, return, ad and profit facts are traceable to source Raw and show Source Time/Freshness/Confidence. | S1-F008 | No additional external boundary identified in the frozen set |
| `S1-AC-020` | `IMPLEMENTED_UNPROVEN` | Contribution Profit/Minimum Price and canonical/estimated states reproduce against versioned golden examples; missing inputs do not produce fake precision. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-021` | `IMPLEMENTED_UNPROVEN` | Completed/Retained/Settled Sale and late-return/adjustment behavior are tested without rewriting historical source facts. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-022` | `IMPLEMENTED_UNPROVEN` | deterministic diagnosis and rule order correctly identify or decline Low Impression/CTR/Conversion, High Return, Stockout Risk, Negative Margin and Data Blocked cases. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-023` | `IMPLEMENTATION_DEFECT` | AI projection allowlist and PII/Secret negative tests pass. | S1-F003, S1-F009 | EXT-007: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-024` | `IMPLEMENTATION_DEFECT` | structured AI output distinguishes Fact/Inference/Recommendation/ Unknown and rejects nonexistent Metric/Evidence references. | S1-F009 | No additional external boundary identified in the frozen set |
| `S1-AC-025` | `IMPLEMENTATION_DEFECT` | model failure, timeout, malformed output and provider unavailability degrade safely; no deterministic Gate is bypassed. | S1-F003, S1-F005, S1-F009 | EXT-007: EXTERNAL_EVIDENCE_PENDING |
| `S1-AC-026` | `IMPLEMENTATION_DEFECT` | approved golden diagnostic cases demonstrate useful cross-domain reasoning while preserving explicit uncertainty and competing explanations. | S1-F009 | EXT-004: OWNER_EVIDENCE_PENDING |
| `S1-AC-027` | `IMPLEMENTATION_DEFECT` | Recommendation → Task → Approval/Policy Authorization is complete, scoped, expiring, attributable and immutable in audit. | S1-F001, S1-F004 | No additional external boundary identified in the frozen set |
| `S1-AC-028` | `IMPLEMENTED_UNPROVEN` | Commercial Policy versions and overrides apply deterministically; missing/invalid/expired policy denies execution. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-029` | `IMPLEMENTATION_DEFECT` | price Dry Run/Impact Preview uses current canonical facts, entity version and prior platform value; stale previews cannot execute. | S1-F001 | No additional external boundary identified in the frozen set |
| `S1-AC-030` | `IMPLEMENTATION_DEFECT` | command idempotency, lease/fence, retry and state transitions pass unit, property and real-database tests. | S1-F001, S1-F002, S1-F005 | No additional external boundary identified in the frozen set |
| `S1-AC-031` | `IMPLEMENTATION_DEFECT` | low-risk real Ozon bounded verification write reaches the intended final value, Readback and complete Audit; unknown result is safely resolvable. The operation is performed only inside an exact unexpired Gate-EV authorization envelope. | S1-F002, S1-F007 | EXT-005: GATE_EV_PENDING |
| `S1-AC-032` | `IMPLEMENTATION_DEFECT` | equivalent real WB bounded verification evidence exists, including native asynchronous/partial/quarantine semantics where applicable, and is generated only under its own exact Gate-EV authorization. | S1-F002, S1-F007 | EXT-005: GATE_EV_PENDING |
| `S1-AC-033` | `IMPLEMENTATION_DEFECT` | Restore/Compensate is actually verified on both platforms without overwriting a later change; its delta/exposure, pre-state, operator, abort, Readback and evidence retention are bounded by Gate EV. | S1-F002, S1-F005 | EXT-005: GATE_EV_PENDING |
| `S1-AC-034` | `IMPLEMENTED_UNPROVEN` | global and scoped Kill Switches prevent new writes; disabled flags are fail-closed under restart/concurrency. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-035` | `IMPLEMENTED_UNPROVEN` | browser E2E proves login → priority queue → SKU diagnosis → evidence → recommendation → approval/policy → price command → readback timeline. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-036` | `IMPLEMENTED_UNPROVEN` | UI never labels stale/estimated/unknown/readback-mismatch state as confirmed success. | Independent verification of mapped evidence pending | No additional external boundary identified in the frozen set |
| `S1-AC-037` | `IMPLEMENTATION_DEFECT` | common priority/SKU queries meet accepted performance targets on representative data; async export is used for large output. | S1-F012 | No additional external boundary identified in the frozen set |
| `S1-AC-038` | `IMPLEMENTATION_DEFECT` | support personnel can recover representative API outage, backlog, replay, AI failure, unknown write and database/object restore scenarios using committed runbooks. | S1-F005, S1-F006, S1-F010, S1-F012 | No additional external boundary identified in the frozen set |
| `S1-AC-039` | `IMPLEMENTATION_DEFECT` | all required CI/security/contract/integration/browser checks pass on the exact release Head; no unresolved BLOCKER/MAJOR finding remains. | S1-F008, S1-F011, S1-F013 | No additional external boundary identified in the frozen set |
| `S1-AC-040` | `OWNER_PENDING` | the Pilot Cohort, approved users, Stores, Capabilities, Policy limits, monitoring window and rollback/kill criteria are explicitly recorded. | Independent verification of mapped evidence pending | EXT-006: OWNER_PENDING |
| `S1-AC-041` | `IMPLEMENTATION_DEFECT` | merge, deployment and production enablement are distinct authorizations; the code ships with production writes disabled. | S1-F001, S1-F013 | No additional external boundary identified in the frozen set |

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
| `S1-AC-020` | `OperatingFlowIT.java` |
| `S1-AC-021` | `OperatingFlowIT.java` |
| `S1-AC-022` | `DiagnosisEngineTest.java`; `OperatingFlowIT.java` |
| `S1-AC-023` | `OperatingFlowIT.java`; `OutputValidatorTest.java`; `HttpModelGatewayTest.java` |
| `S1-AC-024` | `OutputValidatorTest.java` |
| `S1-AC-025` | `OperatingFlowIT.java`; `HttpModelGatewayTest.java` |
| `S1-AC-026` | `OutputValidatorTest.java` |
| `S1-AC-027` | `OperatingFlowIT.java`; `PriceWritePathIT.java`; `RegistryVerificationFlowIT.java` |
| `S1-AC-028` | `GuardrailEngineTest.java`; `OperatingFlowIT.java` |
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
| `S1-AC-039` | `validate_governance.py`; `validate_production_readiness.py`; `verify_coverage_thresholds.sh`; `backend.yml`; `infrastructure.yml` |
| `S1-AC-040` | `OPEN_QUESTIONS.md` |
| `S1-AC-041` | `ApplicationEnvironmentFailClosedTest.java`; `ApplicationConfigurationTest.java`; `PriceWritePathIT.java`; `validate_governance.py`; `test_validate_governance.py` |

## Summary

| Status | Count |
| --- | --- |
| `IMPLEMENTATION_DEFECT` | 32 |
| `IMPLEMENTED_UNPROVEN` | 8 |
| `OWNER_PENDING` | 1 |

The 13 frozen findings remain open for the Controller. Codex's correction and
verification submission is distinct from that verdict. No production provider,
deploy, Gate EV, Gate E, Pilot, Slice closure or V1 completion is claimed.
