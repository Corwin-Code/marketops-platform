# SLICE-V1-004 detailed design

```yaml
document_type: evolvable_slice_detailed_design
slice: SLICE-V1-004
slice_title: Promotion & Listing Conversion
contract: docs/03-work-items/SLICE-V1-004-promotion-listing-conversion.md
contract_sha256: 5a1761ad614426ad3cba9594f481e293b584d69e96c6d893cf502a5062cfc983
contract_git_blob_sha1: 8e89dec6b67e1e4d1e9f5ea05f17cedbd1985ea9
contract_bytes: 70847
contract_lines: 669
normative_acceptance_annex: docs/03-work-items/SLICE-V1-004-promotion-listing-conversion-acceptance.md
normative_acceptance_annex_sha256: c77089fc78183d6289ed0023d4d0dbee915f61e8d917a7f49ba8564b0dc2a48d
normative_acceptance_annex_git_blob_sha1: 4d9e93c17e26deee5d2222c8fc51d61d619aa9c8
owner_acceptance_evidence: docs/08-handoffs/OWNER-SLICE-V1-004-CONTRACT-ACCEPTANCE-EVIDENCE.md
owner_acceptance_evidence_sha256: 5aa9b84b5c889e3c8dcb82f7d436c6a71a3eb2810d3a7d18855052391df87dcb
owner_acceptance_statement: docs/08-handoffs/OWNER-SLICE-V1-004-CONTRACT-ACCEPTANCE-STATEMENT.txt
owner_acceptance_statement_sha256: 2d49c1bda22fd55e8f7d14af6672c2d545ac530ebac2ae4cd52dd2b162c19ed3
source_base_commit: 0f26d0ed387fd0e20c2137b11760ae0bb0f3e5bd
source_base_tree: 9d65c590b4c6a5e08ea2692d5d8f7a3b9645f400
predecessor_slice: SLICE-V1-003
predecessor_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS
design_state: LEVEL_1_LOCAL_FULL_SCOPE_IMPLEMENTATION
new_module: listingconversion
controlled_write_target: LISTING_DESCRIPTION_CHANGE
controlled_write_provider_paths: STRUCTURALLY_UNREACHABLE_PENDING_VERIFIED_CAPABILITY_AND_GATE
promotion_execution: GOVERNED_MANUAL_ONLY
production_write_enabled: false
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
```

This is the evolvable implementation design for the Owner-accepted SLICE-V1-004
Contract and its bound annex (§1–2 and §4). The accepted bytes are normative;
this document records how the twelve business groups (C01–C12), the six
inherited NFR groups and the twenty-seven official-API scenarios (§17,
annex §4) are realised on the existing Shared Spine. It authorises nothing
beyond Level 1: no remote publication, no Level 2 environment, no real Provider
call, no Gate EV, no Gate E and no production write.

## 1. Placement on the Shared Spine

One new Spring Modulith module, `listingconversion`, owns Listing Health,
retained-visit conversion measurement, candidates, action preparation and
review, evaluation plans, promotion engagement and simulation, batches and
containment. It consumes every other authority through published contracts
and writes no table another module owns; the sole-authority text scan that
protects the advertising module is applied to this module as well.

| Authority | How this Slice consumes it |
| --- | --- |
| `identityaccess` | `BusinessAuthorization` for every read and decision; new `ActionScopeCode`s with step-up on every external or financial consequence. A technical administrator holds no business approval scope. |
| `organizationaccount`, `productlisting` | Store/account/listing identity by reference; `ListingIdentityDirectory` for variant context. Native keys are never guessed. |
| `operationsworkflow` | `ops.recommendation`, `ops.work_task`, `ops.approval_decision` and `ops.guardrail_evaluation` stay the only Recommendation, Task, Approval and Guardrail records. The workflow module gains `ListingActionIntake` (proposal and responsibility Task), a `LISTING_DESCRIPTION_CHANGE` branch in `ApprovalService`, `GuardrailService` and `ExecutionService`, and `ListingActionLaunch`, the single launch and allowance-acquisition authority. |
| `marketplaceintegration` | The description write joins the existing registry-driven execution boundary: capability `listing-description-change`, one outbox, one worker, one data-driven adapter, one kill-switch flag `listing-description-write`, verification state fail-closed. |
| `analyticsdecision` | Canonical profit, return and sales values through `MetricQuery`; every Slice calculation records lineage through `CalculationRunLedger`. Retained-visit conversion evaluations are Slice tables with versioned definitions and explicit evidence-path qualification. |
| `adminobservability` | Every read and mutation journals through `MetadataAuditRecorder` under the new `listingconversion` audit domain. |
| `aicopilot` | Unchanged provider-neutral Gateway; the Slice submits only allow-listed evidence references and never accepts a model figure as a fact. |

Owner-published authority (calibration packages, summary-equivalence
profiles, exposure allowances) has no Java writer. The sole-authority scan
refuses one, exactly as for advertising policy.

## 2. Facts, identity and the complete affected set (C01.3, C02, C11)

`core.lc_affected_set` freezes the complete native impact set of a listing:
platform, native listing key, every listing-variant member, a resolution state
(`COMPLETE`, `INCOMPLETE`, `CONFLICTED`) and a digest. A preview, review,
approval, launch, verification and evaluation all bind the digest; a UI
selection of one variant cannot narrow it, and an incomplete set blocks only
the purposes that need completeness while the facts and the gap stay visible.

Description facts are three separate observations. `core.lc_description_observation`
is the management-side text read with its digest, language and provenance;
`core.lc_display_observation` is customer-side display evidence with its
evidence grade (`OFFICIAL_EVIDENCE` or `INDEPENDENT_HUMAN`) and observer;
neither is inferred from the other. Submission, platform acceptance,
management match, display and evaluation are distinct times and states.

Visit facts are `core.lc_visit_fact` (one row per qualified visit, with its
sellability at visit time as `YES`, `NO` or `UNKNOWN`, source channel and
key-group codes) and `core.lc_visit_purchase_link` (visit to sales fact,
with link basis). `core.lc_official_summary_observation` carries an official
aggregate for a period with the counts the platform reported, keyed to a
`core.lc_summary_equivalence_profile` that says whether the Owner has accepted
proof that the aggregate is semantically equivalent to the detail definition.
A profile that is not `PROVEN` leaves the aggregate readable but unqualified.

## 3. Primary metric and evidence paths (C02.1–C02.7, §17.2–17.3, API-T01–T06)

`VisitConversion` in the domain package computes `|S|/|V|` with visit
de-duplication: a visit with several retained purchases counts once, the
numerator is a subset of the denominator by construction, a zero denominator is
`UNDEFINED`, and missing retention maturity or missing eligibility is
`NOT_AVAILABLE` rather than zero. Orders, completed sales, units, buyouts,
add-to-cart conversions and platform "conversion rate" labels cannot become
the numerator; the type system has no constructor for that.

`EvidencePathQualification` decides per platform and store whether the
detail path or the official-summary path qualifies. The summary path requires
a `PROVEN` equivalence profile covering numerator, denominator, time
attribution, maturity and revision. Official labels alone never qualify.
Missing source stratification (advertising versus organic) blocks the
standardised comparison and leaves the raw total visible; nothing is prorated.

Window selection follows C02.5: a day in which two descriptions were both
displayed is excluded from the version-attributed window without prorating,
and its cost and protection responsibilities remain in the whole-window
protection view. Sellability splits are auxiliary results shown beside the
primary value and never replace it; a critical group named before launch has
its own non-worsening verdict that the total cannot compensate.

The dual-platform representative real-measurement requirement (C02.7) is
tracked as an unmet external obligation in the evidence register; the code
distinguishes "measured", "not measurable" and "not yet measured" and never
presents synthetic fixtures as platform qualification.

## 4. Listing Health (C11.1–C11.3)

`mart.lc_listing_health` records, per listing and version, three separate
layers: necessary conditions (hard problems that no other item offsets),
evidence eligibility per purpose (measurement, protection, evaluation) and
improvement opportunities. There is no total score. Unknown is neither
healthy nor unhealthy; an opportunity is not an error; a clean health row is
not a licence to act. Cross-domain collaboration reuses `ops.work_task`
through `ListingActionIntake`; the Slice records handover, evidence return
and dependency re-evaluation in `ops.lc_collaboration_link` and never writes
inventory or finance facts.

Unassociated changes after adoption (C11.3) are `ops.lc_late_association`
rows: a lawful late report links to the exact approved action without a new
approval and keeps both the operation time and the report time; an
unauthorised deviation keeps the real platform state, the authority gap and
the forward disposition; an unresolved change stays under verification and
is never closed by resubmission or by matching text.

## 5. Candidates, materiality, review and approval (C01, C04.1–C04.3, C07.1)

`ops.lc_candidate` holds comparable candidates of kind `CONTENT_DESCRIPTION`,
`OFFICIAL_PROMOTION_PARTICIPATION` or `SELLER_DIRECT_DISCOUNT`, each with its
evidence references and expected effect. Only one primary change per round is
selected into `ops.lc_action`, which binds the native object, affected-set
digest, execution path (`MANUAL` or `API`, chosen before approval), the exact
current text digest and the exact target Russian full text.

`MaterialityClassifier` evaluates two independent axes, content meaning and
commercial exposure, against the active calibration package; either axis
crossing its material trigger routes final approval to the Owner
(`LISTING_ACTION_APPROVE_MATERIAL`), otherwise to the Operations Lead
(`LISTING_ACTION_APPROVE_ORDINARY`). Professional review
(`ops.lc_action_review`) must be by a person other than the author, attests
the full Russian text digest and the facts, and may be combined with final
approval only when the same person holds both grants; the author can never
approve.

Approval is recorded by `ApprovalService` in `ops.approval_decision`. The
`LISTING_DESCRIPTION_CHANGE` branch asks `ListingActionDecisionAuthority`
for the decision scope, records a verdict in `ops.guardrail_evaluation`
naming the calibration package that authorised it (third authority column,
exactly one of three on a PASS), and freezes `ops.lc_action_binding`: target
digest, current digest, affected-set digest, path, evidence versions, rule
versions and the earliest expiry of every bound authority. Any change to
those inputs makes the binding inapplicable; queueing, preparation, launch,
notification and allowance acquisition never extend it.

## 6. Launch and the cumulative allowance (C04.4, C05.1–C05.2, API-T17)

Approval never sends. `ListingActionLaunch` (workflow module) is the one
launch route. It requires `LISTING_ACTION_LAUNCH` with step-up, re-checks the
binding, the health necessary conditions, containment and the approval
expiry, then calls `ops.acquire_lc_launch_allowance`, a `SECURITY DEFINER`
function that consumes a one-use invocation proof, serialises on the
allowance scope with a transaction advisory lock and checks every axis of
`ops.lc_exposure_allowance` independently: occupied plus requested must not
exceed the limit minus the disposal reserve on each axis; surplus on one axis
never offsets another. The occupation row (`ops.lc_exposure_occupation`) is
`ACQUIRED` at launch, becomes `ACTUAL` or `UNKNOWN` from execution facts and
is released only through `ops.release_lc_occupation` with a basis of
`STOP_EVIDENCE`, `OBLIGATION_CLEARED` or `NOT_APPLIED_PROVEN`; there is no
timer release. Manual and API actions, and different batches, consume the
same rows. Technical retries of one action open no second occupation.

Insufficient allowance leaves an approved action `APPROVED_NOT_LAUNCHABLE`
rather than rewriting the approval, and nothing external happens first.

## 7. Governed manual path (C04.4–C04.6, C07.2, C12.4)

`ops.lc_manual_packet` is issued only from a launched action: exact material
(full target text, native object, affected set, path, expiry), executor and
one launch. `ops.lc_manual_report` is the executor's own report, which never
verifies anything. `ops.lc_manual_verification` is by a distinct person or
official evidence and records management match and display evidence
separately. A packet creates no command, outbox row or transport permission;
an action whose path is `MANUAL` cannot acquire a command and vice versa, and
an undecided action is never switched to the other path.

Both simple promotion types run only on this path (`ops.lc_promotion_engagement`
with terms, freeze and auto-participation flags from official evidence,
residual obligations per axis, exit authorisation bound to a pre-approved
reason, and two separate releases: new transactions stopped and obligations
cleared). Existing engagements are adopted with their real obligations and
never receive fabricated history.

## 8. Description API path (C04.1, C04.5–C04.7, §17.4–17.6, API-T07–T18)

The write registry gains capability `listing-description-change` with its own
placeholder vocabulary (`nativeListingKey`, `nativeVariantKey`,
`descriptionText`, `descriptionAttributeKey`, `nativeTaskKey`,
`idempotencyKey`), endpoint functions `DESCRIPTION_APPLY`, `DESCRIPTION_STATUS`,
`DESCRIPTION_READBACK`, `DESCRIPTION_RESTORE`, credential purpose
`CONTENT_WRITE` and a readback observed-text pointer. No profile, endpoint,
auth header, operation or capability row is created; the registry can describe
the write and cannot perform it until an Owner-authorised session records
verified rows.

`ops.lc_description_command` mirrors the advertising outbox: created only by
`ops.create_lc_description_command` from a launched, bound, approved action;
state moves only through `ops.transition_lc_description_command` with lease
and fence; `ops.evaluate_lc_description_write_gate` refuses on capability
verification, subject availability, flags at every scope, allowlist,
approval expiry, launch presence, allowance occupation, containment,
non-target-field guard, `kizMarked` preservation and category length rule.
Platform acceptance is `PLATFORM_ACCEPTED`, management readback is
`READBACK_MATCHED` under the accepted `RepresentationEquivalence` rule, and
display remains a separate observation. `UNKNOWN_REQUIRES_READBACK` has no
edge back to `EXECUTING`; a retry needs verified native idempotency or an
explicit not-applied proof recorded against the operation, never a timeout,
an empty error list, a task identifier or a stale readback. Retry-after is
converted per platform unit (`RetryAfterUnits`: Ozon minutes, Wildberries
seconds) and never extends approval.

`DescriptionChangeGuard` refuses any request carrying a non-target attribute,
a missing or forged `kizMarked` declaration, a text below or above the
verified category bound, and any full-card overwrite whose non-target fields
are not proven preserved against the latest external read. Precise restore
requires a captured complete non-empty prior text, no later lawful version and
current authority; an empty or missing prior value is `RESTORE_UNSUPPORTED`
and never becomes a space, a placeholder or a full import.

## 9. Batches, containment and result isolation (C05.3–C05.4, C12.3)

`ops.lc_batch` and `ops.lc_batch_member` give each member its own action,
approval, launch, current check and result; membership history is
append-only, a new member or a changed target inherits nothing, and a partial
platform effect is reported per member without an implied whole-batch
cancel or rollback.

`ops.lc_containment` records technical or business stops at exact scope with
cause class (`LOCAL_COST`, `SHARED_VERSION`, `PATH_INTEGRITY`,
`SAFETY_FAILURE`, `PLATFORM_INCIDENT`); reenablement needs the cause owner's
repair attestation and the business owner's consent as separate rows, never
one person, one recalculation or one flag. `IsolationScope` widens an
isolation from the failing object and its complete affected set only along
proven dependencies; unknown links are not treated as independent and a
merely unmet primary target is not treated as a failure.

## 10. Evaluation plan, nodes, outcome and stop (C02.4–C02.6, C03, C08.4, C10)

`ops.lc_evaluation_plan` is frozen before launch with version coverage,
transition handling, latest boundary, formal nodes (a small fixed list with
their maturity and method requirements), the effect-shortfall stop rule,
critical groups, comparison basis and the cross-period profit window.
`ops.lc_node_result` holds, per node, the primary ratio, its conservative
bound, the accepted threshold and a verdict `MET`, `NOT_MET` or
`UNDETERMINED`, together with the protection vector: direct contribution
profit, linked-scope profit, overall return rate, critical-variant return,
supply coverage and each critical group, each `PASS`, `FAIL` or `UNDETERMINED`.
No protection compensates another, an unmet primary target never becomes a
failed protection, and "no harm proven" is not a pass. Operational and
Settled stages are separate rows; late facts append `ops.lc_outcome_revision`
under the original plan and the original conclusion stays readable.

`PromotionSimulator` computes forward scenarios and the inverse minimum
quantity for a reference profit line from the same profit inputs, keeps step
fees stepwise, refuses to deduct a seller discount already inside net revenue,
leaves missing fees missing, and reports `NO_SOLUTION` or `UNDETERMINED`
rather than a fabricated number. Demand gating (C03.4) requires every
necessary conservative scenario to pass on its own.

## 11. Freshness, responsibility clocks and recalculation (C06, C08.2)

`ops.lc_recalculation_queue` receives canonical change triggers classed as
`RISK` (5 minute internal target), `ORDINARY` (15 minutes) or `FULL_REVIEW`
(60 minutes); the worker, disabled by default, records accepted-to-finished
latency per class. Source time, acquisition time and processing time are
separate columns everywhere; a new acquisition never refreshes an old source
time. Responsibility Tasks reuse the workflow journal: acknowledgement,
substantive disposition and outcome maturity are separate clocks; reassignment
and recalculation do not reset the first-raised time; only a qualified
dependency hold pauses the action clock, and coverage outside staffed hours is
visible, never assumed. Console surfaces carry risks, deadlines, escalations,
clocks and entry points (DELTA-02); no new e-mail integration exists.

## 12. Calibration packages and activation (C08.3, C08.5)

`core.lc_calibration_package` is an Owner-published, versioned, scoped
package whose validation function requires every consumed category with a
value, unit, scope, window and evidence reference: material improvement bound,
non-worsening bounds, critical-group rules, demand scenarios, freshness rules,
responsibility SLO and coverage, ordinary/material triggers, approval validity,
representation-equivalence rule, allowance axes and reserve, formal nodes and
stop rule, cross-period window. Consumption resolves the one active package for
the scope and purpose; a missing or conflicting package blocks only its
consumers and produces `CALIBRATION_UNRESOLVED`, never a code default. A newer
package does not rewrite a frozen plan or revive an expired, revoked or
contained authority.

## 13. Bilingual console and API (C12.5, DELTA-01, DELTA-03)

The console gains `src/listing/` views for the health queue, candidate
comparison, action preparation, review and approval, launch with allowance
visibility, manual packet, report and verification, command timeline,
evaluation nodes and protections, batches and containment. Every fixed system
statement, blocker, error, confirmation and result stage is presented from one
catalogue in Chinese and Russian; raw evidence and free text are shown as
recorded and never machine-translated, and the Russian full text is reviewed
verbatim. Both languages read the same facts, permissions, times and amounts.
A catalogue completeness test enumerates every reason and state code the
backend can return.

## 14. Migrations and verification boundary

V0001–V0073 are byte-preserved. New forward-only migrations:

| Version | Authority |
| --- | --- |
| V0074 | Shared-Spine widening: audit domain, action scopes and role matrix, workflow vocabularies, credential purpose `CONTENT_WRITE`, description endpoint functions and operation pointers, write registry vocabulary and shape, readback shape admitting an observed-text pointer, control invocation purposes, `ops.lc_actor_holds_action` |
| V0075 | Listing conversion facts, affected sets and digest, description/display/visit observations, purchase links, owner-published summary equivalence profiles, official summaries, conversion measurement, three-layer Listing Health projection, feedback themes, collaboration links |
| V0076 | Owner-published calibration packages, categories and values with complete-combination activation, `core.lc_resolve_calibration`, exposure allowances, guardrail evaluation third authority (`lc_calibration_package_id`/`version`, exactly one of three) |
| V0077 | Candidates, actions and lawful transitions, review, binding and gaps, evaluation plan, batches, containment and attestation, isolation dependencies, launch and occupation with the allowance function, manual packet/report/verification, promotion engagement and exit, `ops.lc_authority_snapshot` and the listing branch of the authority-binding trigger |
| V0078 | Description command, transitions, attempts, readbacks, raw response custody, write gate, lease/status/readback/compensation/recovery functions |
| V0079 | Node results and protection verdicts, outcome revisions, simulations, late association, recalculation queue |

Verification in this Level 1 session uses isolated fixtures only: unit and
architecture tests, PostgreSQL integration tests against an isolated server
where a container runtime is available, and a local isolated PostgreSQL
migration and function check where it is not. No Provider, credential, shared
environment or production database is touched, and `production_write_enabled`
stays `false`.
