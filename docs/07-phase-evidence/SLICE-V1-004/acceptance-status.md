# SLICE-V1-004 — current acceptance status

Status: **CONTROLLER_CHANGES_REQUIRED — ROOT_CAUSE_REWORK_IN_PROGRESS**.

The frozen R1 review rejected the sufficiency of the Maker checkpoint at `f91d107c53a0cf3964ae43c0e8353e0c244a2b59`. No complete acceptance group is currently claimed LOCAL_VERIFIED for the rework head. All 27 frozen findings require closure and independent Controller verification. Partial passing tests are tracked in [the interim verification record](rework-r1/INTERIM_VERIFICATION.md), not promoted to end-to-end acceptance.

The original Maker report is preserved byte-for-byte at [historical-maker/acceptance-status.md](rework-r1/historical-maker/acceptance-status.md). The table below is retained only as the historical claim/traceability inventory to be replaced by final exact-head evidence. Its descriptions and old test references are not statements of current sufficiency.

Production writes remain disabled. Real provider/environment obligations remain open at their existing F-M/F-S/F-W/E identifiers; no new Owner product decisions are requested. Current authority is Level 1 local rework only. See [finding progress](rework-r1/finding-progress.json) and [the immutable frozen set](rework-r1/01_FROZEN_FINDING_SET.json).

## 1. Functional acceptance groups

| Group | Status | What is demonstrated | Evidence |
| --- | --- | --- | --- |
| `C01-AC1` | HISTORICAL_MAKER_CLAIM | Candidate → action → recommendation/task → binding → launch → evaluation is one traceable chain; the candidate carries evidence references, the action carries the frozen affected-set digest and author; an outcome with unmet target but complete evaluation is an honest NOT_MET result, never a success | ListingActionService; ListingActionIntakeService; EvaluationService; ListingGuardrailTest; ProtectionVectorTest#TC-LC-P04; ListingConversion.test.tsx#TC-UI-LC-008 |
| `C01-AC2` | HISTORICAL_MAKER_CLAIM | The affected set is frozen from every OBSERVED variant of the listing (core.lc_listing_affected_set_digest); an INCOMPLETE or CONFLICTED set fails Listing Health and blocks preparation; the digest is bound into review, approval binding, launch packet and command | AffectedSetResolutionTest; ListingHealthAssessmentTest#TC-LC-H05; V0075 core.lc_affected_set; V0077 lc_action_binding trigger; ListingActionLaunchIT#TC-LC-LAUNCH-005 |
| `C01-AC3` | HISTORICAL_MAKER_CLAIM | Content and promotion candidates exist side by side; a candidate never becomes an action without a person, and an action never launches without review, approval, binding, plan, health PASS and allowance | ListingActionService; V0077 ops.lc_action_moves_lawfully; ListingActionLaunchIT#TC-LC-LAUNCH-005 |
| `C02-AC1` | HISTORICAL_MAKER_CLAIM | The ratio is retained-purchase visits over distinct visits; 100 visits with 10 retained sales from 10 or from 1 visit give 0.10 and 0.01; sales quantity never substitutes for visits | VisitConversionTest#TC-LC-M01; ConversionMeasurementService |
| `C02-AC2` | HISTORICAL_MAKER_CLAIM | DETAIL and OFFICIAL_SUMMARY paths share the definition; an unqualified path yields NOT_AVAILABLE with named reasons, never zero or an allocation; a summary path needs a PROVEN owner-published equivalence profile | EvidencePathQualificationTest; VisitConversionTest#TC-LC-M02..M04; V0075 core.lc_summary_equivalence_profile |
| `C02-AC3` | HISTORICAL_MAKER_CLAIM | Sellable split and source stratification are auxiliary and reported beside the primary result; transition days are excluded whole; no auxiliary result replaces the primary | VersionWindowTest; VisitConversionTest#TC-LC-M05; ConversionMeasurementView |
| `C03-AC1` | HISTORICAL_MAKER_CLAIM | Five protections are compared independently; one FAIL fails the vector whatever the others say; one gap leaves it UNDETERMINED | ProtectionVectorTest#TC-LC-P03; V0079 ops.lc_protection_verdict_of |
| `C03-AC2` | HISTORICAL_MAKER_CLAIM | Supply coverage is a separate protection input with its own bound and direction; an unknown value is UNDETERMINED, never a pass | ProtectionVectorTest#TC-LC-P01; EvaluationService.ProtectionInputs |
| `C03-AC3` | HISTORICAL_MAKER_CLAIM | Occupations are released per basis with evidence; the promotion simulator applies the discount once and keeps the cross-period window from calibration | PromotionSimulatorTest#TC-LC-S02; V0077 ops.release_lc_occupation; ListingActionLaunchIT#TC-LC-LAUNCH-006 |
| `C04-AC1` | HISTORICAL_MAKER_CLAIM | LISTING_DESCRIPTION_CHANGE is the only write-capable listing action; binding gaps (digest moved, text moved, calibration not current, authorization expired) refuse launch and command creation; approval never creates a command; the guard refuses any body that is not one description attribute | RecommendationStateTest#TC-WF-004; DescriptionChangeGuardTest; ListingDescriptionWriteGateIT#TC-LC-GATE-004/005; V0078 ops.create_lc_description_command |
| `C04-AC2` | HISTORICAL_MAKER_CLAIM | Manual report, API acceptance, management match, display state and Outcome are separate records; UNKNOWN has no edge to EXECUTING and a retry needs database proof; the description worker mirrors the ad bid outbox | ListingDescriptionCommandWorkerTest; ListingConversionSchemaIT transition graph; V0077 lc_manual_verification trigger |
| `C04-AC3` | HISTORICAL_MAKER_CLAIM | Compensation restores the exact captured prior text and nothing else; an empty or missing prior is RESTORE_UNSUPPORTED; a third-party value routes to investigation | ListingDescriptionCommandWorkerTest; ListingDescriptionResolutionService; listing-description-command-resolution.md |
| `C05-AC1` | HISTORICAL_MAKER_CLAIM | Launch acquires every axis of the owner-published allowance under a per-allowance lock; two launches racing for one slot are serialised and exactly one wins; the loser is APPROVED_NOT_LAUNCHABLE | AllowanceCheckTest; ListingActionLaunchIT#TC-LC-LAUNCH-002/003; local flow: second launch refused on CONCURRENT_LISTINGS |
| `C05-AC2` | HISTORICAL_MAKER_CLAIM | An occupation is released only with STOP_EVIDENCE, OBLIGATION_CLEARED or NOT_APPLIED_PROVEN and evidence, never by time; a technical retry of the same command occupies nothing new | V0077 ops.release_lc_occupation; ListingActionLaunchIT#TC-LC-LAUNCH-006 |
| `C05-AC3` | HISTORICAL_MAKER_CLAIM | Containment scopes are LISTING/STORE/PLATFORM/ORGANIZATION/BATCH; isolation follows proven dependencies; re-enabling needs two people; a shortfall leaves the approval intact as APPROVED_NOT_LAUNCHABLE | IsolationScopeTest; ListingContainmentIT; ListingActionLaunchIT#TC-LC-LAUNCH-007 |
| `C06-AC1` | HISTORICAL_MAKER_CLAIM | Health queue is ordered by necessary-condition state; responsibility clocks start at first raise and only a qualified hold pauses the action clock | ResponsibilityClocksTest; ListingHealthPanel |
| `C06-AC2` | HISTORICAL_MAKER_PARTIAL | Clocks and holds are computed; staffed coverage comes from the calibration RESPONSIBILITY_COVERAGE value and no 24x7 coverage is assumed, but no coverage calendar UI exists in this Slice | ResponsibilityClocksTest; CalibrationService |
| `C06-AC3` | HISTORICAL_MAKER_CLAIM | The Console shows health, actions, allowance, launch answers, packets, containments and the recalculation queue in Chinese and Russian with the same facts; no new e-mail or private channel | ListingConversion.test.tsx; ListingConversionForms.test.tsx |
| `C07-AC1` | HISTORICAL_MAKER_CLAIM | Simulation keeps assumptions, inverse and results apart; the discount is applied once; missing inputs are UNDETERMINED and named | PromotionSimulatorTest; EvaluationService.simulate |
| `C07-AC2` | HISTORICAL_MAKER_CLAIM | Exit needs a pre-approved reason and a one-use proof; release is two steps (new transactions stopped, obligations cleared) | V0077 ops.authorize_lc_promotion_exit; ManualPathService; ListingConversionForms.test.tsx#TC-UI-LC-F05 |
| `C07-AC3` | HISTORICAL_MAKER_CLAIM | An adopted engagement (action_id null) counts once and grants no new authorization | ManualPathService.adopt; V0077 ops.lc_promotion_engagement |
| `C08-AC1` | HISTORICAL_MAKER_CLAIM | One authority: the guardrail verdict names the calibration package, the approval binds to the database authority snapshot, the Console reads the same views the API serves | ListingGuardrailTest#TC-LC-GR01/02; V0077 ops.lc_authority_snapshot; V0076 guardrail third-authority CHECK |
| `C08-AC2` | HISTORICAL_MAKER_CLAIM | Source time and acquisition time are separate on every fact and health row; recalculation classes carry 5/15/60 minute targets | V0075; V0079 ops.lc_recalculation_queue; RecalculationService |
| `C08-AC3` | HISTORICAL_MAKER_CLAIM | A calibration package activates only complete; an activated package keeps its identity; resolution is RESOLVED, CALIBRATION_UNRESOLVED or CALIBRATION_CONFLICTED; bindings name the package version and go INAPPLICABLE when it is not current | V0076 activation trigger and core.lc_resolve_calibration; ListingConversionSchemaIT; local check: empty scope → CALIBRATION_UNRESOLVED |
| `C09-AC1` | HISTORICAL_MAKER_PARTIAL | Fact intake records provenance and evidence per observation; on-demand AI assistance is not added in this Slice and reuses the existing bounded AI Gateway boundary | ListingFactIntakeService |
| `C09-AC2` | HISTORICAL_MAKER_CLAIM | Feedback themes are recorded per period with mention counts and never become facts or root causes | ListingFactIntakeService.recordFeedbackTheme; V0075 mart.lc_feedback_theme |
| `C09-AC3` | HISTORICAL_MAKER_CLAIM | Every read is authorized by store scope and audited; the full Russian text is reviewable in the Console; no download pack is added (DELTA-03 not required) | ListingActionConsoleController; ListingConversion.test.tsx |
| `C10-AC1` | HISTORICAL_MAKER_CLAIM | A node is MET only by its conservative bound after maturity; an undetermined protection never reads as a pass | ProtectionVectorTest#TC-LC-P04; V0079 ops.lc_node_result |
| `C10-AC2` | HISTORICAL_MAKER_CLAIM | Formal nodes and the stop rule are frozen into the evaluation plan at launch from the calibration package and cannot be chosen after the result | EvaluationService.freezePlan; V0077 ops.lc_evaluation_plan |
| `C10-AC3` | HISTORICAL_MAKER_CLAIM | The stop rule triggers only on an unmet target at a stop node after maturity; ending an evaluation reverses no external action | ProtectionVectorTest#TC-LC-P05; IsolationScopeTest#TC-LC-I03 |
| `C11-AC1` | HISTORICAL_MAKER_CLAIM | Three layers, no score: unknown stays UNKNOWN, opportunities are named, a health PASS is not an execution licence (launch still needs approval, binding and allowance) | ListingHealthAssessmentTest; ListingHealthPanel |
| `C11-AC2` | HISTORICAL_MAKER_PARTIAL | Collaboration links and isolation dependencies are recorded with proof references; cross-domain task handover uses the existing work task journal | GovernanceService.recordDependency; V0075 ops.lc_collaboration_link |
| `C11-AC3` | HISTORICAL_MAKER_CLAIM | Late association is LAWFUL_LATE_REPORT, UNAUTHORISED_DEVIATION or UNRESOLVED_CHANGE with its own lifecycle; an unauthorised change keeps the gap | V0079 ops.lc_late_association; GovernanceService.recordLateAssociation |
| `C12-AC1` | HISTORICAL_MAKER_CLAIM | Pilot allowlist entries and the gate authority are owner-published rows with no Java writer; the gate closes on ENTITY_NOT_ALLOWLISTED and PRODUCTION_WRITE_DISABLED independently of every other condition | ListingDescriptionWriteGateIT#TC-LC-GATE-001/006; SoleAuthorityArchitectureTest#TC-AUTHORITY-004 |
| `C12-AC2` | HISTORICAL_MAKER_CLAIM | Batch membership is append-only with sequence numbers; a member action keeps its own state and binding | V0077 ops.lc_batch_member; GovernanceService |
| `C12-AC3` | HISTORICAL_MAKER_CLAIM | Chinese and Russian desktop Console with a completeness test over every backend code family; permissions and amounts do not change with language | ListingConversionCodesTest; ListingConversion.test.tsx#TC-UI-LC-001/005 |

## 2. Inherited non-functional groups

| Group | Status | What is demonstrated | Evidence |
| --- | --- | --- | --- |
| `NFR-AC1` | HISTORICAL_NOT_RUN | Worker restart safety and lease recovery exist (recoverExpiredLeases, NEVER propagation); availability, PITR and Raw custody drills were not executed in this session | ListingDescriptionCommandWorker; database-restore-drill.md |
| `NFR-AC2` | HISTORICAL_NOT_RUN | Lists are paginated with bounded limits; no load or capacity measurement was executed | listing console controllers |
| `NFR-AC3` | HISTORICAL_MAKER_CLAIM | Store-scope authorization on every endpoint, step-up on launch/release/resolution, author/executor independence enforced in the database, application role cannot write commands, launches, occupations or containments, no secret material in fixtures | ListingConversionSchemaIT; ListingActionLaunchIT#TC-LC-LAUNCH-004; V0074 role matrix |
| `NFR-AC4` | HISTORICAL_MAKER_CLAIM | V0001–V0073 bytes preserved; six forward-only migrations applied cleanly on an isolated PostgreSQL 16; append-only observations, revisions and late associations | MIGRATION-INVENTORY.json; FlywayMigrationIT (compiled, HISTORICAL_NOT_RUN) |
| `NFR-AC5` | HISTORICAL_MAKER_CLAIM | UTF-8 Russian text end to end, language switch remembered per browser, keyboard-operable forms; browser E2E HISTORICAL_NOT_RUN | ListingConversion.test.tsx; ListingConversionForms.test.tsx |
| `NFR-AC6` | HISTORICAL_MAKER_CLAIM | Correlation identifiers on every attempt, audit on every read and change, four runbooks, structured logs; lint, typecheck, unit, architecture, governance and readiness checks executed | executable-evidence.md; docs/06-runbooks |

## 3. Official-API scenarios (Contract §17)

| Scenario | Status | What is demonstrated | Evidence |
| --- | --- | --- | --- |
| `API-T01` | HISTORICAL_MAKER_CLAIM | visits and purchases are separate facts with source channel and sellability; no ratio is emitted without visits | V0075 core.lc_visit_fact; VisitConversionTest |
| `API-T02` | HISTORICAL_MAKER_CLAIM | the summary path keeps the label text and fails closed without a PROVEN profile | EvidencePathQualificationTest#TC-LC-E02/E03 |
| `API-T03` | HISTORICAL_MAKER_CLAIM | revisions are append-only (ops.lc_outcome_revision); a first snapshot never claims later corrections | V0079 |
| `API-T04` | HISTORICAL_MAKER_CLAIM | source, acquisition and processing times are distinct columns; recalculation targets are internal | V0075; V0079 |
| `API-T05` | HISTORICAL_MAKER_CLAIM | raw responses are custodied by digest under lc-description-response; task UUIDs prove nothing about semantics | ListingDescriptionCommandWorker; RawCustodyService |
| `API-T06` | HISTORICAL_MAKER_CLAIM | purchase links reference a visit key and a sales fact; an order is never renamed a visit | V0075 core.lc_visit_purchase_link |
| `API-T07` | HISTORICAL_MAKER_CLAIM | non-target fields, a second attribute and an unverified attribute key are refused; no attribute identity is guessed | DescriptionChangeGuardTest#TC-LC-G02/G03/G07 |
| `API-T08` | HISTORICAL_MAKER_CLAIM | per-command state; acceptance with a task waits for status, never success | ListingDescriptionCommandWorkerTest#TC-LC-DW-001 |
| `API-T09` | HISTORICAL_MAKER_CLAIM | management match and display state are separate columns on the verification | V0077 ops.lc_manual_verification |
| `API-T10` | HISTORICAL_MAKER_CLAIM | exact non-empty prior only; empty prior is RESTORE_UNSUPPORTED; a later lawful text blocks restore (PRIOR_TEXT_MOVED) | ListingDescriptionCommandWorkerTest; V0078 gate |
| `API-T11` | HISTORICAL_MAKER_CLAIM | Ozon minutes and Wildberries seconds converted and capped; retry budget is separate from allowance | RetryAfterUnitsTest |
| `API-T12` | HISTORICAL_MAKER_CLAIM | a moved current text refuses command creation and closes the gate; a local lock claims nothing about external edits | ListingDescriptionWriteGateIT#TC-LC-GATE-005 |
| `API-T13` | HISTORICAL_MAKER_CLAIM | a missing or forged marking declaration is refused by the guard and an undeclared action by the gate | DescriptionChangeGuardTest#TC-LC-G06 |
| `API-T14` | HISTORICAL_MAKER_CLAIM | length bounds come only from the calibration DESCRIPTION_LENGTH_RULE; out of bounds is a precise refusal, never truncation | DescriptionChangeGuardTest#TC-LC-G07; V0078 TEXT_LENGTH_OUT_OF_BOUNDS |
| `API-T15` | HISTORICAL_MAKER_CLAIM | an empty answer is UNKNOWN_REQUIRES_READBACK, never success and never proof of non-application | ListingDescriptionCommandWorkerTest#TC-LC-DW-002 |
| `API-T16` | HISTORICAL_MAKER_CLAIM | occupations are released on evidence, not on a timer | V0077 ops.release_lc_occupation |
| `API-T17` | HISTORICAL_MAKER_CLAIM | the allowance is the one cumulative authority; nothing external starts before it is acquired | ListingActionLaunchIT; V0078 ALLOWANCE_NOT_OCCUPIED |
| `API-T18` | HISTORICAL_MAKER_CLAIM | no automatic retry; UNKNOWN stays until a readback; retry only with database proof | ListingDescriptionCommandWorkerTest#TC-LC-DW-004 |
| `API-T19` | HISTORICAL_MAKER_CLAIM | no promotion API write path exists; exit needs authorization | V0077 ops.lc_promotion_engagement; SoleAuthorityArchitectureTest |
| `API-T20` | HISTORICAL_MAKER_CLAIM | the discount is applied once; unknown fees are UNDETERMINED | PromotionSimulatorTest#TC-LC-S02/S03 |
| `API-T21` | HISTORICAL_MAKER_CLAIM | decimal money with explicit currency throughout; no zero-cost default | PromotionSimulator; listing views keep decimals as text |
| `API-T22` | HISTORICAL_MAKER_PARTIAL | capability rows carry verification state and evidence date; no real version difference has been registered | V1_CAPABILITY_MATRIX.md §5a |
| `API-T23` | HISTORICAL_MAKER_CLAIM | supply coverage is UNDETERMINED without evidence, never zero risk | ProtectionVectorTest#TC-LC-P01 |
| `API-T24` | HISTORICAL_MAKER_PARTIAL | feedback themes are de-duplicated per period and theme; no listing-specific AI projection is added | V0075 mart.lc_feedback_theme |
| `API-T25` | HISTORICAL_MAKER_CLAIM | no secret material in fixtures, code or documents; a 200 answer is never a permission proof (capability_subject_status is a registry fact) | ListingConversionFixture; validate_production_readiness.py PASS |
| `API-T26` | HISTORICAL_MAKER_CLAIM | fact intake and Console reads are separately authorized (INTERNAL_FACT_INTAKE vs LISTING_CONVERSION_VIEW) | ListingFactIntakeService; V0074 role matrix |
| `API-T27` | HISTORICAL_MAKER_CLAIM | documentary evidence only raises the capability row; production_write_enabled stays false; engineering completion and real coverage are separate facts | ListingDescriptionWriteGateIT#TC-LC-GATE-006; CURRENT_STATE.md |

## 4. What no status here claims

- No real Ozon or Wildberries description write, readback, status or restore has been made or observed.
- No calibration package, allowance, Pilot allowlist entry or gate authority exists outside test fixtures; each is Owner-published.
- No Level 2 environment, remote publication, Gate EV, Gate E or Pilot authority was used or is implied.
- The Controller review of this exact local checkpoint is pending; every HISTORICAL_MAKER_CLAIM row is subject to it.
