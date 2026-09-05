# SLICE-V1-003 detailed design

```yaml
document_type: evolvable_slice_detailed_design
slice: SLICE-V1-003
slice_title: Advertising & Traffic Efficiency
contract: docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md
contract_sha256: 1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c
contract_git_blob_sha1: 669c38dc4d9429249e663da0e684dabf570c4a4a
contract_bytes: 129400
contract_lines: 2687
owner_acceptance_evidence: docs/08-handoffs/OWNER-SLICE-V1-003-CONTRACT-ACCEPTANCE-EVIDENCE.md
owner_acceptance_evidence_sha256: d0532ff25806c5cbc96411aad81db8524671fba8b987a57a41843bff78bcce7d
source_protected_main: 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430
source_protected_main_tree: 0ca229112bcf351ab5c572dd8d375c647bab61c0
predecessor_slice: SLICE-V1-002
predecessor_snapshot_sha256: f4847d4fdca8bede97decc02a12f99b2358b196d3d5b31a3aac60362ae41799f
design_state: R1_IMPLEMENTED_PENDING_EXACT_HEAD_FULL_VERIFICATION
controlled_write_target: AD_BID_CHANGE
controlled_write_provider_paths: STRUCTURALLY_UNREACHABLE
production_write_enabled: false
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
```


This is the evolvable implementation design for Owner-authorized R1. The accepted
Contract and additive Owner decisions remain normative. R1 is a continuous repair
of Frozen Finding Set 001 against reviewed Head
`a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb`. Targeted results and outstanding
verification are recorded in `docs/07-phase-evidence/SLICE-V1-003/rework-r1/`.
This document does not issue the independent Controller's verdict or authorize a
Release, Gate EV, Gate E, Pilot or production write.

## 1. Canonical identity and facts

An Advertising Case identifies organization, platform, account/store, native
object and lineage, bidding mode, semantic version, complete affected variant
set and independent cause. Children retain their diagnostic role. An allocation
or a subset never becomes an independently executable object. Incomplete or
conflicting mapping is visible and cannot be treated as a complete affected set.

`AdvertisingEvidenceRepository` and `AdvertisingEvidenceGatherer` consume actual
canonical sources, preserve source and accepted timestamps, and carry lineage
into calculation. Provider spend/traffic/attribution, company sales and company
profit remain separate facts. Corrected official reports use their canonical
replacement chain; they do not accumulate with superseded reports.

Advertising-linked sales resolve the exact listing variant first, with an
explicit product-variant fallback only where linkage supports it. Contribution
profit weights each linked line by its attributed quantity. Platform fees use
the existing company cost-component vocabulary, including promotion, instead of
subtracting promotion twice. Currency, mapping, quantity or cost uncertainty
remains explicit; company-wide sales divided by advertising clicks is not a
conversion definition. Actual `MetricValue` records and their definition,
calculation and evidence lineage support the advertising metrics.

The two efficiency axes are absolute Advertising Contribution Profit and profit
per official advertising RUB. Neither compensates for deterioration in the
other. A negative result remains a loss even if its magnitude decreases.

## 2. Purpose-specific decisions and ranking

`AdvertisingPurposeFreshness` evaluates each required kind against the exact
purpose, scope and profile, using source time, acceptance time, complete period,
publication lag and correction rules. A new ingestion time cannot make an old
report current. Mature Outcome cohorts use their applicable maturity policy.
Late effective cost metadata must reconfirm the consumed source evidence.

A `MAX_CPC_BOUNDED` action requires its six purpose inputs. The narrow
`CAUSE_BOUND_PROTECTION_STEP` route requires fresh native configuration,
affected-set mapping, official spend and the exact known not-sellable or
unavailable danger. Only the closed financial-uncertainty allowlist in
`AdActionDependencyPolicy` and its SQL counterpart may be excluded from this
route's action blockers. Missing, duplicated, expired or adverse critical-sales
proof, policy uncertainty and native/scope uncertainty still block. Raw financial
uncertainty remains visible in the Preview and does not imply profitability.

Optimization qualification counts distinct complete canonical periods. Refresh
count is not sustained evidence. Policy resolution refuses conflicting applicable
versions and does not fall back to a broad version when a narrower one conflicts.

`AdPriorityPolicy` uses a versioned lexicographic vector: lane, Protection
sub-tier and fixed intra-tier factors. It never blends ranks into a compensating
weighted score. Priority inputs include actual Task SLO, complete repair
uncertainty, independent absolute-profit and per-RUB gaps, and critical-unit
signals. Outcome-derived factors require the current matching case, calculation,
policy, affected set and eligible 30-day Retained lineage. Reading a projection
written by the same refresh must not change the next result for the same as-of.

## 3. Accountable work and Accepted Exception

`AdvertisingResponsibilityService` creates one independent responsibility Task
per actionable non-Watch Case before candidate eligibility. A blocked or
candidate-free case therefore remains accountable. `ADVERTISING_REVIEW`
recommendation uniqueness is by organization and Case. Finite bid proposals use
organization and candidate identity; other domains retain the original live
recommendation uniqueness rule.

The Task binds the actual accountable role, Owner-published SLO profile and
staffed reporting calendar. A missing profile/calendar gives an explicit unknown
response clock and escalation, never a default deadline. If authority later
becomes available, the same Task receives the missing binding and keeps the
original unresolved snapshot, resolution time, first-raised time and journal.
Historical SLO reads must use only authority recorded by their as-of time.

The console presents event completion, deadline evaluation and the staffed
Action clock separately. A false breach flag establishes `NOT_BREACHED` only
with known coverage and that stage's valid deadline. Missing profiles, malformed
or partial timing records, and unknown pause flags remain `UNRESOLVED`; no
legacy deadline supplies missing live SLO authority. A recorded acknowledgement
does not complete the Action stage. A known breach remains visible alongside a
reported pause, and a completed Action is not displayed as an active clock.
Wall-clock age preserves a known zero and rejects negative or invalid values.

View, acknowledgement, attributable action and observed Outcome are separate
journal events. Task event appends lock the Task before allocating a sequence.
Assignment checks the proposed assignee's live role and complete scope.
`DATA_OR_MAPPING_REPAIR` requires a real canonical evidence record; a free UUID
or arbitrary text cannot satisfy the Action stage. Manual issuance/proof and
controlled command creation record their actual attributable events.

`ACTION_REQUIRED`, `ACTION_IN_PROGRESS` and `ACCEPTED_EXCEPTION_ACTIVE` are
exclusive Case dispositions. A Maker selection, endorsed/issued Manual intent,
live command/reservation or in-progress Task prevents new risk acceptance.
An active Accepted Exception blocks new action intent, preview and a manual Task
reopen that would reset its epoch.

The exception freezes scoped risk, evidence, people, versions, review/expiry and
the shortest live authority deadline. Distinct Ops and Owner approval is
required. Only the Action clock pauses; acknowledgement, wall-clock exposure
and visible continuing harm do not. Expiry, missed review, new unknown or
regressed critical units, worsening profit/spend, changed Case/Bundle/policy or
lost IAM authority invalidate it and reopen the same Task. Private append-only
boundary records make revoke-and-restore permanently invalidate old authority.
Reading the workflow first refreshes invalidation, then reads the resulting Task.

## 4. Candidate, Preview and human decision

`BidCandidateSet` produces at most the exact policy count, bounded by the allowed
finite maximum, removes duplicate normalized targets and validates direction,
relative/absolute step and native grid. Intermediate Protection targets require
explicit policy permission. `AdBidUnitConversion` converts native minor units
and RUB economics before comparing ceilings and exposure; unknown denomination
cannot form a usable grid. Runtime adapters do not choose or round a target.
Both proposed expected effect and the actual Preview compare an intermediate
Protection target against the native-unit ceiling after the exact policy
headroom. A target above that conservative ceiling remains
`RECOVERY_IN_PROGRESS_NOT_HEALTHY`, even when below raw Max CPC. Cause-bound
Protection retains absent numerical Max CPC and the exposure-only interpretation.

Generating choices takes no reservation and creates no additional responsibility
Task. The public human route permits only selection or rejection of a generated
candidate. Generic recommendation state transitions and generic price Impact
Preview refuse the advertising action.

`AdvertisingHumanDecisionService` binds Maker selection, a frozen Outcome
baseline, distinct Ops endorsement and exact final approval. Initial nonzero
changes are Material and require a third person acting as Owner. A later
Ordinary route still requires the exact scoped promotion evidence and per-command
Maker/Checker authority; it cannot become standing authorization.

`ops.ad_materiality_assessment` exposes native-normalized absolute and relative
change, official Spend, affected-set size, critical sales, cumulative absolute
change, lifecycle and current regression/unknown containment. Each hard trigger
acts independently; unknown inputs remain unknown. The same result drives
Preview, final seal, creator and the live execution gate. The current route must
equal the sealed route. Ordinary promotion never overrides a fixed hard trigger.

`AdvertisingImpactEvidenceService` captures the native current/target/grid,
major/native economic ceiling, alternative choices, policy/Metric lineage,
uncertainty, full company and critical-unit baseline, purpose proof and all
exposure axes. `GuardrailService` stores this evidence together with its
identity digest in the immutable evaluation used for approval. Both candidate
selection and endorsement freeze the combined bid and Bundle authority snapshot.

## 5. Disclosure and live identity

`AdvertisingDisclosurePolicy` is the published workflow port implemented by the
advertising disclosure service; it avoids an internal module dependency cycle.
Queue, Case, Preview, Outcome, Brief attachments, exports, notification and AI
inputs use the same disclosure decision. Financial roles also require live
`ADVERTISING_DECISION_EVIDENCE_VIEW` scope covering every affected store and
variant; company-wide totals require organization coverage. A partially scoped
reviewer cannot approve using masked evidence. Maker selection displays the
permitted native intent without granting financial approval authority. Each
historical candidate is checked against its own frozen complete affected set;
current Case scope alone cannot expose an older recommendation. Financial basis
is masked independently. Manual Bid/Budget/Status options require the current
set to be complete and native view scope over its store and every product.

`AuthenticatedInvocationIssuer` uses the actual authenticated SecurityContext
and a separately configured private database login. The application login
cannot create/read these invocation proofs, assume the issuer role or provision
roles. Absent configuration fails closed. Historical credential metadata remains
unverified; a separately controlled exact credential attestation participates in
the seal. No real credential is provided by this Slice.

## 6. Command, observation and exact Compensation

The existing `marketplaceintegration` execution authority owns the advertising
command capability. `AdBidCommandService` accepts recommendation identity,
expected version and reservation reference, not caller-authored approval fields.
`AdBidApprovalAuthority` seals live IAM, exact evidence, materiality, Bundle,
Gate, credential and policy authority. `ops.create_ad_bid_command` derives the
command from that sealed record. Caller-written approval rows or JSON are not an
execution grant.

Reservation acquisition and the transmission boundary independently recheck the
seal, evidence expiry, snapshot equality, exact scope, kill/quarantine, lease and
aggregate exposure. Expiration appends an invalidation and expires unexecuted
recommendations; it does not rewrite historical approval or release exposure.

Provider acceptance remains pending until exact readback. Native pending status
uses STATUS/READBACK phases and cannot return to APPLY. An unknown submission
without verified native idempotency permits no blind retry. The exceptional
NOT_APPLIED route requires documented official proof, unchanged current bid,
exact key and retry budget. A third value or unknown readback preserves
uncertainty and requires containment; it is never overwritten with the target.

Exact Compensation is a new human decision. It requires the same object and
original configuration, an action-bound human Stop or actual latest regression,
a distinct published compensation Bundle with no ordinary candidate set,
Maker/Ops/Owner separation, fresh lease and live exact authority. Exposure
includes its RESTORE delta. Old compensation approval remains invalid after a
credential/policy/IAM/Bundle/Gate/containment revoke-and-restore. It authorizes
only the captured prior native bid, never a newly optimized target.

## 7. Reservations, exposure, containment and Bundle

`ops.ad_exposure_snapshot` supplies the same six axes to Preview and the final
write gate. It uses current official-spend leaves, complete affected sets and
actual active controlled/manual/compensation interventions. Currency or mapping
uncertainty is unknown; missing spend cannot be zero. Exact scoped recovery
headroom and cumulative deltas apply independently on every axis.

A reservation covers the entire affected set. Configuration proof plus complete
early company and every frozen critical-unit Completed-Sales guard may release
it. Elapsed time, a caller boolean or one favorable child cannot. A later
regression reopens the reservation or quarantines overlap with a new action.

Kill, quarantine, credential revocation and reenablement operate on exact
organization/platform/account/store/capability/direction/object scope. Changes
permanently invalidate old unexecuted decision assets. Clearing containment
requires a fresh decision chain. Bundle publication uses DRAFT, distinct Ops
endorsement and Owner activation with the exact proposed Gate envelope frozen
into endorsement; changing that envelope invalidates approval.

## 8. Governed Manual and one Outcome engine

Both Ozon and Wildberries retain `UNVERIFIED` API status while their governed
Manual workflow can be exercised with fictional isolated fixtures. A versioned
Owner Manual policy produces a proposal; Maker selection, Ops endorsement and
Owner approval produce the packet. Proposal, issued packet, executor start,
self-report and configuration proof are distinct states and endpoints.

The packet binds exact native field/target, current configuration, affected set,
versions, people, expiry, shared baseline and reservation. Official evidence must
exist in Raw custody for the same account/object/native field and round-trip to
the cited observation. Independent verification requires a different authorized
person. Self-report never proves configuration; conflict, wrong field, stale or
superseded proof produces uncertainty and retains the reservation. No packet
creates an API command, outbox entry or transport permission.

Controlled and Manual actions enter `AdvertisingOutcomePlanning` and the same
frozen baseline, plan and observation engine. The selected baseline ID remains
identical through selection, sealed approval, command/packet and observations.
Owner Outcome fields explicitly specify non-worsening bands, material deltas,
minimum advertising-spend denominator, scale, rounding, inclusive/exclusive
boundary, sales tolerance and the negative-profit terminal rule. Incomplete
policy cannot use hidden numeric defaults.

Baseline persistence requires a one-use attestation from the independently
controlled identity issuer over the full planner payload, organization and the
application backend transaction. The application role cannot directly insert a
baseline or stage, mint a planning grant, modify the payload or replay a consumed
grant. The security-definer freeze function atomically consumes the proof and
writes all three stages and exact frozen critical members. A current canonical
validator checks the attestation, payload, typed Owner policy, windows, scope and
purpose freshness again at selection, endorsement, approval and execution.
Missing issuer configuration leaves planning unavailable while preserving the
Case and responsibility Task.

The implementation has three observations within the Contract's Operational and
Settled business stages:

| Observation | Purpose and permitted conclusion |
| --- | --- |
| `OPERATIONAL` | Early Completed-Sales safety; actual company and every frozen critical unit. This is not the primary efficiency result. |
| `RETAINED` | Fixed 30-day Retained-Sales Operational business result, visibly not settled; both profit axes and sales preservation apply. |
| `SETTLED` | Mature financial confirmation against the frozen stage-consistent baseline; can contradict the earlier result. |

`businessOutcome`, profit-axis verdict and safety verdict are separate fields.
Actual zero official spend with landed configuration may prove exposure stopped;
canonical eligible nonnegative profit can clear an original loss cause. Neither
alone proves overall efficiency or inventory repair. Negative profit cannot close
Protection. Missing company or critical-unit coverage is unknown. A current
incident/hold is a confounder. Late financial corrections append a revision,
retain prior versions and reopen/escalate/quarantine as required.

A mature Settled contradiction of the current successful Retained result also
creates a linked Finance review responsibility through the Shared Task service.
V0065 binds it to the exact baseline, action and original Task. The original Task
and first-raised age remain intact, while the Finance Task has its own authorized
owner and append-only observation history. Replaying the same observation is
idempotent; an early or unknown financial observation cannot fabricate a Finance
contradiction. Finance task reads and transitions require the complete frozen
affected scope.

## 9. Orchestration, console and evidence

V0063 connects real canonical changes to the targeted queue: facts and raw
configuration, mapping, cost, inventory, sales/settlement, policy, Bundle,
exception, hold, readback, Manual proof and Outcome. Exact future expiry and
stage maturity are queued explicitly. Leased work coalesces a new trigger
without losing it; failed work can retry and expired workers cannot overwrite a
new lease. Full reconciliation calls the same calculation and the real authority,
exception and SLO maintenance port, reporting actual counts.
Maintenance limits a caller's observation time to the current database clock
before authority expiry. The database independently rejects future expiry
requests, so application/VM clock skew cannot expire current human approval.

The queue records accepted-to-start/finish timing and incidents. P95 derives from
actual samples. Capacity evidence must declare object count, organization/store
shape, safety-path proportion, resources and tested failure/recovery scenarios;
a small or homogeneous fixture cannot establish undeclared production capacity.

The console exposes responsibility, governed bid/manual choices, exact Preview,
exception, configuration proof, independent Outcome states, exposure,
containment, Compensation and orchestration status with server-derived allowed
actions. Brief and Review remain immutable revisioned projections with canonical
links and late-correction deltas. Browser evidence uses actual isolated backend
and database role flows; component mocks alone do not demonstrate the chain.

## 10. Migration and verification boundary

V0001–V0035 are byte-preserved against protected main and the reviewed Head.
Candidate V0036–V0056 are reworked under the R1 authorization. New migrations are:

| Version | Implemented authority |
| --- | --- |
| V0057 | Case responsibility, human selection/endorsement and finite generation |
| V0058 | Private invocation, sealed action authority, exposure, control, compensation |
| V0059 | Frozen Outcome baseline and critical units |
| V0060 | Governed Manual policy/proposal/packet and configuration proof |
| V0061 | Exception risk, permanent authority changes and captured Preview evidence |
| V0062 | Shared frozen Outcome for governed Manual |
| V0063 | Canonical triggers, exact expiry/maturity, queue recovery and SLO |
| V0064 | Expired authority reconciliation and advertising recommendation uniqueness |
| V0065 | Exact Settled contradiction to linked Finance responsibility and observation journal |

Browser history scenarios use explicitly time-travelled synthetic read oracles
for Unknown, mismatch, expiry and revision display. These retain the frozen
stage/window and append-only constraints; they do not prove financial
calculation. Canonical financial integration tests and the facts-to-action
vertical path provide the separate behavioral evidence.

`collect_slice3_rework_identity.py` binds the actual commit/tree, protected bytes,
migration inventory and runtime/build/test/CI input digest. It makes no test
claim. Full clean backend, architecture, PostgreSQL migration/privilege/runtime,
frontend quality/coverage/build/browser, governance and all repository CI gates
must separately pass on identified inputs. A later evidence-only commit is
reported as a distinct Head and compared by the input digest, never called the
same commit. Exact remote Head, tested merge parents/tree and run/job/artifact
identities belong in the final handoff. R1 publication is append-only to the
named feature branch and one Draft PR; Ready, merge and production enablement
remain outside this execution.
