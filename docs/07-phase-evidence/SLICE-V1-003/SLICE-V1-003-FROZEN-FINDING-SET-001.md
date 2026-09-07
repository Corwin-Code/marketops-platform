# SLICE-V1-003 — Frozen Finding Set 001

```yaml
document_type: controller_frozen_finding_set
finding_set_id: SLICE-V1-003-FROZEN-FINDING-SET-001
controller_review_id: CONTROLLER_SLICE_V1_003_ONE_SHOT_DEEP_REVIEW_A0711F1_R1
date: 2026-09-05
repository: Corwin-Code/marketops-platform

accepted_contract:
  path: docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md
  sha256: 1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c
  git_blob_sha1: 669c38dc4d9429249e663da0e684dabf570c4a4a
  bytes: 129400
  lines: 2687

reviewed_git:
  base: 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430
  head: a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb
  tree: fb4d242d62febd87191da9dce353bdef99f5a77d
  measured_commit: ae099e1913af2c99c2a0909dfca0842d5d15b1bd
  measured_tree: 18bca0325a8fb19fe32d1ef3c413577a872d911e
  branch: feat/SLICE-V1-003-advertising-traffic-efficiency
  remote_branch_observed: true
  pull_request_observed: false
  github_actions_runs_for_branch: 0
  final_head_signature: UNSIGNED

deep_review_verdict: READY_FOR_CODEX_REWORK
engineering_closure: REJECTED_CURRENT_HEAD
release_or_production_authority: NONE
production_write_enabled: false

finding_count: 22
blocker_count: 17
major_count: 5
owner_decision_required: false
contract_amendment_required: false
external_provider_action_required_for_rework: false
```

## 1. Review boundary and method

This is the one-shot source-first Controller Deep Review over the exact remote
Base/Head/tree above. The review inspected the accepted Contract, the remote
branch identity and compare, candidate migrations, the advertising calculation,
policy, workflow, command, readback, outcome, containment, console and evidence
surfaces, plus the candidate's own criterion-by-criterion audit.

The Controller did **not** independently execute the local Maven, npm, browser or
capacity commands. There is no remote CI run for this branch. The reported green
test totals are therefore evidence supplied by the Maker, not an independent
exact-Head CI receipt. Source findings below do not depend on those tests being
false; many are expressly admitted by the candidate's own `S3-AC-STATUS.json`.

The finding set is frozen. Codex receives the accepted original Contract plus
this entire set once and must perform one continuous root-cause rework/fix/verify
cycle. Ordinary findings supported by evidence available to this review must not
be drip-fed later.

## 2. Positive observations retained

- Exact Contract and acceptance identities are present.
- Protected Base remains the accepted SLICE-V1-002 closure Main.
- V0001–V0035 are reported unchanged and the candidate adds forward-only
  V0036–V0056 migrations.
- The internal `AD_BID_CHANGE` command/outbox/attempt/readback skeleton and a
  pre-transmission recheck exist.
- Fixture-only execution, task-event history, Daily/Weekly projections and a
  broad test corpus exist.
- Real Ozon/Wildberries writes, Credentials, Gate EV, Gate E, Pilot and
  production write remain disabled.
- These positives do not overcome the root-cause findings below or the
  repository's own 55/200 acceptance status.

## 3. Frozen findings

### S3-DR-001 — MAJOR — The handoff and acceptance index do not bind the actual remote candidate identity

**Affected Acceptance criteria:** `S3-AC-199`, `S3-AC-200`

**Observed at the reviewed Head**

- The reviewed remote branch exists at a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb / fb4d242d62febd87191da9dce353bdef99f5a77d, while r3-implementation-handoff.md states push NOT_EXECUTED, no remote branch exists, and uses a placeholder local_checkpoint_commit.
- S3-AC-STATUS.json still declares head 77faa37, not the reviewed Head, and records only 55 VERIFIED / 132 PARTIAL / 13 NOT_YET with closure_claim_made=false.
- No pull request and no GitHub Actions run exist for the reviewed branch; the reported local test outputs are therefore not independently bound to the final remote Head.

**Risk**

The review, future rework, CI and closure could refer to different trees, and the repository evidence currently contradicts the actual Git state.

**Required root-cause rework**

- Replace placeholders and false remote-state claims with the exact reviewed/reworked Base, Head, tree, branch, publication actor/time and transport receipt.
- Regenerate every machine-readable acceptance/evidence index against the final rework Head; do not claim engineering closure before all engineering criteria are verified.
- Bind full local and remote CI artefacts to the exact final rework Head and preserve the measured-commit distinction where a documentation-only tip is used.

**Required closure evidence**

- Exact-head identity validator.
- GitHub branch/PR/CI readback.
- Hash-verified evidence manifest with no stale Head or placeholder.

### S3-DR-002 — BLOCKER — The canonical facts-to-action path is not executable; the advertised vertical test bypasses the calculator

**Affected Acceptance criteria:** `S3-AC-032`, `S3-AC-051`, `S3-AC-061`, `S3-AC-090`, `S3-AC-169`, `S3-AC-195`

**Observed at the reviewed Head**

- AdvertisingCaseCalculationService.profitOf supplies promotion cost as unconditionally unavailable, so the wired calculator always emits a blocker and cannot create an actionable Recommendation.
- AdvertisingVerticalPathIT first proves that blocked DATA_REPAIR result, then seeds a separate protection Case through AdvertisingWriteEnabledFixture for the Recommendation-to-Outcome stages.
- The resulting test is a component-chain exercise, not one canonical accepted-facts → calculated Case → governed action → Outcome path.

**Risk**

The product may appear end-to-end complete while real calculated evidence can never reach its governed operating loop.

**Required root-cause rework**

- Make the canonical calculator consume an explicit confirmed/zero/unavailable promotion-cost fact and all other required profit components without hidden defaults.
- Provide one true fixture-only vertical path starting from accepted source facts and using the production calculation/projection/task/recommendation services without seeding a prequalified Case.
- Keep a separate negative path proving missing required economics creates Data Repair and no command.

**Required closure evidence**

- PostgreSQL integration path from accepted facts through calculation, Task, Approval, command, readback, early guard, Operational and Settled outcomes.
- Test must fail if any production stage is replaced by direct table seeding.

### S3-DR-003 — BLOCKER — Multi-variant advertising economics and Allowable CPA are calculated with non-canonical aggregation

**Affected Acceptance criteria:** `S3-AC-032`, `S3-AC-033`, `S3-AC-040`, `S3-AC-041`, `S3-AC-090`, `S3-AC-151`, `S3-AC-157`

**Observed at the reviewed Head**

- AdvertisingEvidenceGatherer.sumAcross computes an unweighted mean of per-variant economics and AdvertisingContributionProfit multiplies that mean by the total linked unit count.
- Allowable CPA is resolved using only the first Product Variant in the complete affected set.
- Fulfilment is folded into another component, metric-definition versions are not bound to the calculated values, and the production path is blocked by the missing promotion component.

**Risk**

A heterogeneous affected set can receive materially wrong Contribution Profit, Allowable CPA and Max CPC, leading to unsafe bid candidates and false outcomes.

**Required root-cause rework**

- Aggregate each cost/revenue component by the exact deterministically linked sale-event/Variant quantities, preserving currency, sale stage, cost version and evidence lineage.
- Resolve Allowable CPA for every required Variant/sale stage and combine only under an explicitly accepted canonical rule; never select the first Variant as authority.
- Bind both profit axes and Max CPC to exact Metric/Calculation versions and reconcile aggregate totals to line-level inputs.

**Required closure evidence**

- Generated and PostgreSQL tests with unequal Variant volumes and materially different COGS/fees/returns.
- Permutation invariance and line-to-total reconciliation properties.
- Tests that fail the former simple-mean and first-Variant implementations.

### S3-DR-004 — BLOCKER — Freshness, conversion, qualification, sustainment and critical-sales authorities are not wired into production decisions

**Affected Acceptance criteria:** `S3-AC-037`, `S3-AC-038`, `S3-AC-039`, `S3-AC-040`, `S3-AC-042`, `S3-AC-043`, `S3-AC-044`, `S3-AC-045`, `S3-AC-046`, `S3-AC-047`, `S3-AC-048`, `S3-AC-049`, `S3-AC-050`, `S3-AC-052`, `S3-AC-054`, `S3-AC-057`, `S3-AC-058`, `S3-AC-090`

**Observed at the reviewed Head**

- The production gatherer resolves only a hard-coded Completed-Sale conversion stage and uses a hard-coded 30-day fallback.
- Linkage coverage is fabricated as 1.0 or 0.5 from window completeness rather than calculated from eligible traffic and deterministically linked sale events.
- AdvertisingPolicyRepository.resolveFreshness has no production caller; qualification sustainment/minimum periods are not applied to lane resolution.
- Compensation responsibility is passed as false and critical-sales danger as none in the production calculation path; variantShares are gathered but unused.

**Risk**

Stale, unsustained, incomplete or economically mismatched evidence can be promoted, while valid one-sided danger and critical-sales obligations can be ignored.

**Required root-cause rework**

- Resolve and apply versioned purpose/platform/scope Freshness and Qualification Profiles for Watch, Task, Recommendation, write and Outcome.
- Calculate actual linkage/mapping/coverage denominators and preserve Provider-attribution observation separately.
- Resolve the exact sale stage from the active Bundle, enforce Allowable-CPA stage consistency, wire sustainment, critical-sales danger, compensation, availability and sellability inputs.
- Fail closed only for the affected purpose while preserving independent Fresh danger.

**Required closure evidence**

- Purpose-separated stale/fresh/conflicted/missing-profile tests.
- Sustainment and materiality tests that distinguish Watch from Optimization.
- Critical-sales/availability/compensation P0/P1 tests through the production calculation service.

### S3-DR-005 — BLOCKER — Canonical Priority violates the accepted non-compensating lexicographic policy

**Affected Acceptance criteria:** `S3-AC-056`, `S3-AC-057`, `S3-AC-058`, `S3-AC-059`, `S3-AC-060`

**Observed at the reviewed Head**

- AdPriorityPolicy.rank computes a weighted sum inside each hard band instead of comparing the accepted ordered factors lexicographically.
- Missing numeric measures are converted to zero, silently treating unknown exposure as the lowest value.
- The same factor structure is reused across lanes rather than the accepted lane-specific orders.

**Risk**

A lower-order commercial factor can reverse a higher-order responsibility, and missing evidence can push a dangerous Case down the queue.

**Required root-cause rework**

- Represent Canonical Rank as Lane → hard sub-tier → ordered factor tuple → stable Case identity.
- Use lane-specific factor sequences and explicit UNRESOLVED priority evidence; never coerce missing values to zero.
- Keep any weighted score as a clearly non-canonical analysis field only.

**Required closure evidence**

- Properties proving no later factor can reverse an earlier decisive factor.
- Missing-factor tests producing PRIORITY_POLICY_UNRESOLVED rather than a low score.
- Separate Protection, Data Repair, Optimization and Watch order tests.

### S3-DR-006 — BLOCKER — Accountable Task, human SLO and Accepted Exception are not complete operating authorities

**Affected Acceptance criteria:** `S3-AC-061`, `S3-AC-062`, `S3-AC-063`, `S3-AC-064`, `S3-AC-066`, `S3-AC-068`, `S3-AC-069`, `S3-AC-070`, `S3-AC-072`, `S3-AC-073`, `S3-AC-074`

**Observed at the reviewed Head**

- AdvertisingProposalService suppresses every blocked Case before Task creation, so material Data Repair and non-bid Protection responsibilities can remain taskless.
- The acknowledgement deadline and staffed/out-of-coverage logic are stored but not evaluated; a missing human-SLO Profile silently falls back to 15 minutes.
- No advertising Accepted Exception entity, service or endpoint exists.

**Risk**

Proven harm or data blockers can lack accountable work, SLO reporting can claim an unpublished service level, and risk can be suppressed without the accepted exclusive, expiring exception control.

**Required root-cause rework**

- Create/update cause-routed Tasks independently of whether a bid candidate exists, using the existing operationsworkflow sole authority.
- Implement acknowledgement/action clocks, staffed calendar, OUT_OF_COVERAGE_ACTIVE_HARM, escalation, reassignment history and no hidden fallback.
- Implement advertising Accepted Exception with exact cause/scope/evidence/consequence, Operations endorsement, Owner approval, Bundle, expiry, exclusive action-intent rule and same-Case reopen.

**Required closure evidence**

- Concurrent/replay Task dedupe tests.
- Clock/coverage/expiry/escalation tests.
- Exception lifecycle and no-auto-renew tests.

### S3-DR-007 — BLOCKER — Governed Manual Shadow is neither dual-platform nor evidence-safe

**Affected Acceptance criteria:** `S3-AC-075`, `S3-AC-076`, `S3-AC-078`, `S3-AC-079`, `S3-AC-080`, `S3-AC-081`, `S3-AC-082`, `S3-AC-083`, `S3-AC-084`, `S3-AC-088`, `S3-AC-170`

**Observed at the reviewed Head**

- No complete Wildberries Manual Shadow path is present; the candidate's own audit records only an Ozon-side example.
- Packet Bundle/guardrail/approval fields are nullable or not authority-bound, and there is no production issue service that refuses unresolved permission/evidence.
- The evidence grade is caller-controlled: a manual observation can be labelled OFFICIAL_API_READBACK without a corresponding command/attempt; verifier role/scope and observation completeness are not enforced.
- Confirmed manual interventions do not take the affected-set reservation or consume aggregate exposure.

**Risk**

An unverified or self-reported manual action can be presented as official configuration proof, bypass scope/approval controls and collide with controlled interventions.

**Required root-cause rework**

- Implement Ozon and Wildberries governed issue/report/verify/read paths using UNVERIFIED/Shadow-only platform profiles.
- Bind every packet to exact object, affected set, Bundle, guardrail, approval route, expiry and verification plan; refuse unresolved authority.
- Derive evidence grade from trusted source lineage, require authorized independent verifier and completeness, preserve observed-at limitations, and make confirmed manual actions reserve/consume exposure.

**Required closure evidence**

- Both-platform integration and browser paths.
- Evidence-grade forgery, self-report, partial screenshot, unauthorized verifier and API/export hierarchy tests.
- Manual/controlled overlap and exposure tests.

### S3-DR-008 — BLOCKER — The mandatory per-command Maker → Operations endorsement → Owner approval chain is absent

**Affected Acceptance criteria:** `S3-AC-018`, `S3-AC-103`, `S3-AC-104`, `S3-AC-105`, `S3-AC-106`

**Observed at the reviewed Head**

- The implementation records one approval decision and no command-bound Operations endorsement.
- Recommendation/approval records do not preserve a complete Maker/endorser/final-approver chain, and approver evidence-scope eligibility is not enforced.
- The handoff asserts that Bundle-level endorsement substitutes for per-command endorsement, which directly contradicts the accepted initial all-Material route.

**Risk**

A material nonzero bid change can be authorized without the accepted independent operational check or with a blind/out-of-scope approver.

**Required root-cause rework**

- Record immutable Maker, distinct Operations endorsement and Owner final approval for each initial nonzero AD_BID_CHANGE.
- Enforce person separation, action scope and full decision-evidence scope at each transition.
- Keep Ordinary route disabled until a separately modelled, evidence-gated promotion exists; Bundle activation endorsement is not a command endorsement.

**Required closure evidence**

- Positive full chain and negative self-endorse/self-approve/blind-approver tests through API, service and SQL boundary.
- Audit/preview must display all actors and evidence versions.

### S3-DR-009 — BLOCKER — Approval Lease is re-anchored at command preparation and does not use the earliest authority bound

**Affected Acceptance criteria:** `S3-AC-107`, `S3-AC-108`, `S3-AC-136`, `S3-AC-149`

**Observed at the reviewed Head**

- AdvertisingDecisionService derives expiry from clock.instant() plus lease seconds because the repository does not load final-approved-at.
- Only the lease policy and a generic approval scope are folded into expiry; evidence, Recommendation/Preview, Metric/Profile, credential/capability and Gate bounds are absent.
- The command creator accepts caller-supplied approval_expires_at, and reenablement does not permanently invalidate old decision assets.

**Risk**

Waiting or retry can effectively renew a commercial authorization, and a stale approval can execute after underlying evidence or containment changes.

**Required root-cause rework**

- Anchor an immutable expires_at at final approval as the minimum of every bound authority and any Owner-shortened limit.
- Persist authoritative timestamps/versions; reject caller-supplied expiry unless exactly derived and verified.
- Recheck at approval, command creation, worker preparation, transmission and allowed retry; expired/killed/quarantined/superseded assets never revive.

**Required closure evidence**

- Time-travel tests for every minimum bound and no renewal.
- Kill/quarantine/Bundle switch/reenablement invalidation tests.

### S3-DR-010 — BLOCKER — The SECURITY DEFINER command creator permits actor and authority spoofing at the database boundary

**Affected Acceptance criteria:** `S3-AC-006`, `S3-AC-016`, `S3-AC-104`, `S3-AC-106`, `S3-AC-111`, `S3-AC-112`, `S3-AC-198`

**Observed at the reviewed Head**

- ops.create_ad_bid_command is callable by the application role and accepts p_actor_id, p_bundle_id and p_approval_expires_at as caller parameters.
- The function checks the supplied actor's stored scope but does not bind that UUID to the authenticated session/caller, allowing an arbitrary SQL client with app-role credentials to nominate another authorized user.
- The caller can also nominate authority values rather than the function deriving exact immutable authority from the approved recommendation.

**Risk**

A compromised or erroneous application path can impersonate an approver and fabricate the authority snapshot for an external write.

**Required root-cause rework**

- Make the database command-creation boundary derive actor and authority from a trusted authenticated/session-bound context and authoritative rows, not caller assertions.
- Constrain EXECUTE privileges and sole legal caller; bind one approval/recommendation/candidate/Bundle/expiry identity atomically.
- Add app-role adversarial tests that attempt actor, Bundle, expiry, target, candidate and object substitution.

**Required closure evidence**

- Direct SQL impersonation must fail even when the nominated UUID has valid scopes.
- Application service positive path and DB privilege matrix tests.

### S3-DR-011 — BLOCKER — Exact target, Materiality and Preview do not implement the accepted deterministic multi-axis contract

**Affected Acceptance criteria:** `S3-AC-092`, `S3-AC-093`, `S3-AC-094`, `S3-AC-095`, `S3-AC-096`, `S3-AC-098`, `S3-AC-099`, `S3-AC-100`, `S3-AC-102`, `S3-AC-105`

**Observed at the reviewed Head**

- Target policy declares a candidate count, but the production proposal records only candidate ordinal 1 and exposes no governed select/reject surface.
- Cause-bound exposure-only semantics and RECOVERY_IN_PROGRESS_NOT_HEALTHY are not represented in the running path.
- Preview omits required critical-sales, Spend, profit, inventory/sellability details, and absent current Bid is mapped to numeric zero in one decision path.
- Command materiality is not derived from every accepted hard axis.

**Risk**

Users can approve an incomplete or falsely precise impact view, and hard material risk may be misclassified or candidate freedom may be hidden.

**Required root-cause rework**

- Generate and persist the complete finite Provider-valid candidate set, expose exact selection/rejection, and reject free-typed/substituted targets.
- Represent basis, unavailable Max CPC, exposure-only claim and recovery-not-healthy state.
- Populate Preview and Materiality from all non-compensating axes with missing/unresolved states, never zero substitution.

**Required closure evidence**

- Finite-set determinism and operator selection browser tests.
- One-factor-at-a-time Materiality tests and complete Preview snapshot tests.
- Runtime-rounding/substitution/readback mismatch negatives.

### S3-DR-012 — BLOCKER — Idempotency, NOT_APPLIED retry and exact native Readback semantics are incomplete

**Affected Acceptance criteria:** `S3-AC-111`, `S3-AC-112`, `S3-AC-113`, `S3-AC-114`, `S3-AC-115`, `S3-AC-116`, `S3-AC-117`, `S3-AC-118`, `S3-AC-119`, `S3-AC-120`, `S3-AC-121`

**Observed at the reviewed Head**

- The capability profile's idempotency semantics are stored but not used to select runtime behavior.
- No verified NOT_APPLIED representation/consumption path exists for the allowed no-native-idempotency same-command retry.
- Readback amount/currency equality is enforced, but the observed native unit is not compared; the command unit is reused, so a major/minor-unit mismatch can be accepted.
- Several core command-creation agreement checks are not executed by the cited tests because fixtures seed command graphs directly.

**Risk**

A timeout may be retried under the wrong authority or an incorrectly represented Provider value may be declared matched.

**Required root-cause rework**

- Drive retry behavior from the active evidence-backed capability profile and preserve one logical command/provider identity.
- Model signed/verified NOT_APPLIED evidence, unchanged prior Bid and live authority; otherwise one submission only.
- Compare actual observed native representation, unit, currency and exact target; reject tolerance, rounding and third values.

**Required closure evidence**

- Native-idempotent, no-idempotency, timeout, NOT_APPLIED, stale-approval and unit-mismatch matrices through the real command creator and worker.

### S3-DR-013 — BLOCKER — Same-object reentry and exact Compensation are not an executable governed path

**Affected Acceptance criteria:** `S3-AC-122`, `S3-AC-123`, `S3-AC-124`, `S3-AC-125`, `S3-AC-126`, `S3-AC-150`

**Observed at the reviewed Head**

- No reentry/cooldown calibration authority prevents another ordinary action after reservation release.
- SQL primitives exist for prior-Bid restoration, but no complete service/API/test performs new Preview, Operations endorsement, Owner approval, current-state ownership and exact Gate-scope verification.
- Compensation Readback and business Outcome are not demonstrated as separate completed lineages.

**Risk**

A second action can occur without accepted calibration, while a nominal restoration can bypass the human and current-state controls intended to prevent overwriting a later legitimate change.

**Required root-cause rework**

- Keep generic same-object reentry fail-closed until a separate accepted profile exists.
- Implement exact prior-Bid Compensation in the original lineage/reservation with full current evidence, new preview/endorsement/approval and exact inactive Gate authority model.
- Never auto-compensate; distinguish compensation readback from business outcome.

**Required closure evidence**

- Positive fixture compensation and negatives for later external change, stale authority, mismatched current Bid, missing Gate scope and automatic rollback.

### S3-DR-014 — BLOCKER — Reservation and aggregate Exposure enforce only a subset of the accepted real-intervention envelope

**Affected Acceptance criteria:** `S3-AC-127`, `S3-AC-128`, `S3-AC-129`, `S3-AC-130`, `S3-AC-131`, `S3-AC-132`, `S3-AC-133`, `S3-AC-134`, `S3-AC-135`, `S3-AC-136`, `S3-AC-137`

**Observed at the reviewed Head**

- Confirmed manual actions do not acquire the governed reservation or consume aggregate exposure.
- The gate enforces active count, unresolved writes and cumulative change but does not enforce maximum deduplicated retained-sales exposure or associated official Spend.
- Missing/conflicted envelope detection is incomplete and some tests only exercise a mirrored query path.
- Overlap/quarantine logic uses affected-set digest equality rather than actual Variant-set intersection.

**Risk**

Several individually valid actions can exceed the Pilot's real sales/Spend blast radius, manual actions can be invisible to capacity, and overlapping harm can proceed under different digests.

**Required root-cause rework**

- Atomically admit every controlled or confirmed manual intervention across all six independent axes and reserve recovery headroom.
- Calculate deduplicated Variant sales and official Spend from canonical evidence; unresolved aggregation fails closed.
- Detect missing/conflicted envelopes and real set intersection; release only after accepted configuration/early-observation conditions.

**Required closure evidence**

- One-axis-at-a-time exhaustion, concurrent admission, manual/API parity, Unknown capacity and compensation-headroom tests.

### S3-DR-015 — BLOCKER — The running Outcome path does not implement frozen sales protection, dual-axis Operational/Settled truth or cause-specific terminal states

**Affected Acceptance criteria:** `S3-AC-028`, `S3-AC-029`, `S3-AC-030`, `S3-AC-031`, `S3-AC-033`, `S3-AC-034`, `S3-AC-035`, `S3-AC-036`, `S3-AC-151`, `S3-AC-152`, `S3-AC-153`, `S3-AC-154`, `S3-AC-155`, `S3-AC-156`, `S3-AC-157`, `S3-AC-158`, `S3-AC-159`, `S3-AC-160`, `S3-AC-161`, `S3-AC-162`, `S3-AC-163`, `S3-AC-164`, `S3-AC-165`, `S3-AC-166`, `S3-AC-167`, `S3-AC-168`

**Observed at the reviewed Head**

- The production Outcome service uses Order-stage counts for the Operational stage and elapsed time/ratio proxies rather than fresh attributable Completed Sales and final 30-day Retained Sales protection.
- SalesPreservation and DualAxisVerdict are correct isolated rules but have no production caller; critical sales units are not frozen in the action plan.
- The Settled path does not prove settlement/fee/return/spend maturity and the observation lacks required Freshness/Confidence semantics.
- Confounder, associative-versus-causal marker, VERIFIED_AD_RISK_CLEARED and VERIFIED_AD_EXPOSURE_STOPPED do not exist in the running model.

**Risk**

The system can report an Outcome that does not prove the accepted sales or profit objective, miss key-unit harm, or collapse Operational estimates into Settled finality.

**Required root-cause rework**

- Freeze the complete evaluation plan before external execution, including company total, critical units, 30-day retained window, both profit axes, evidence/sample/coverage/confounder policy and exact versions.
- Wire early Completed-Sales Guard, 30-day Operational result, stage-consistent Settled confirmation and late-data version transitions through SalesPreservation and DualAxisVerdict.
- Implement cause-specific protection terminal states, remaining-harm responsibility, confounded/inconclusive states, association marker and same-lineage reopen.

**Required closure evidence**

- Full scenario matrix: critical-unit failure despite total pass; partial loss reduction; risk cleared; exposure stopped; Operational success→Settled regression; reverse upgrade; attribution unresolved; confounder; late data.

### S3-DR-016 — BLOCKER — Quarantine, Kill and reenablement do not preserve cause-proportional scope or non-resurrection

**Affected Acceptance criteria:** `S3-AC-138`, `S3-AC-139`, `S3-AC-140`, `S3-AC-141`, `S3-AC-142`, `S3-AC-143`, `S3-AC-144`, `S3-AC-145`, `S3-AC-146`, `S3-AC-147`, `S3-AC-148`, `S3-AC-149`, `S3-AC-150`

**Observed at the reviewed Head**

- Regression quarantine matches digest equality rather than overlapping Variants, lacks object-scope propagation and does not invalidate unexecuted recommendations/candidates/approvals/commands.
- Authority-version quarantine records a reference but no gate consumes it.
- Kill activation is not tied to actor role/max scope or a mandatory review owner; manual packets are not revoked by containment.
- reenable() clears containment without permanently invalidating old decision assets, so a surviving pending command can become eligible again.

**Risk**

Known bad authority can keep serving other consumers, a stale command can transmit after restart, and a user can stop/restart a wider scope than authorized.

**Required root-cause rework**

- Implement object + intersecting affected-set quarantine, authority-version consumer invalidation and exact capability kill.
- Persist append-only invalidation epochs on every unexecuted asset and require fresh recomputation after reenablement.
- Enforce actor-specific maximum stop scope, review owner, multi-party cause-specific reenablement, manual packet revocation/uncertain verification and transmission-boundary fencing.

**Required closure evidence**

- Race tests for pending/leased/just-before-send commands.
- Authority-version and overlapping-set propagation tests.
- Old-asset non-resurrection and actor-scope adversarial tests.

### S3-DR-017 — BLOCKER — Gate EV, Gate E, Ordinary promotion and complete Policy-Bundle activation authorities are absent or incomplete

**Affected Acceptance criteria:** `S3-AC-006`, `S3-AC-109`, `S3-AC-110`, `S3-AC-169`, `S3-AC-171`, `S3-AC-172`, `S3-AC-173`, `S3-AC-174`, `S3-AC-175`, `S3-AC-176`, `S3-AC-177`, `S3-AC-178`, `S3-AC-179`, `S3-AC-180`

**Observed at the reviewed Head**

- Gate EV is only a free-text reference and there is no structured exact authorization object; Gate E has no evidence-bundle/consumption model.
- Ordinary route is simply refused, with no scoped all-Material evidence-gated promotion record.
- The application role can INSERT/UPDATE the decision Bundle and no sole-writer architecture rule covers it.
- Command authority snapshots do not bind every required Profile/evidence version, and activation/non-resurrection validation is incomplete.

**Risk**

Future enablement could rely on unstructured text or mutable app-written Bundles, expand beyond demonstrated evidence, or silently combine unreviewed policy versions.

**Required root-cause rework**

- Implement inactive, fail-closed structured Gate EV and Gate E authority/evidence models that cannot authorize adjacent scope; do not activate them.
- Implement inactive Ordinary promotion model with exact scope/evidence prerequisites and automatic fallback to Material.
- Enforce a sole Bundle writer, immutable exact-version references, whole-combination validation, atomic activation, old-asset invalidation and new-version rollback.

**Required closure evidence**

- Scope-monotonic negative tests for adjacent Platform/Store/object/direction/basis/value/window/exposure.
- Bundle sole-writer/atomic switch/conflict/non-resurrection tests.
- All real platform paths remain UNVERIFIED and disabled.

### S3-DR-018 — BLOCKER — Role-minimal decision disclosure is not enforced across the advertising surface

**Affected Acceptance criteria:** `S3-AC-017`, `S3-AC-019`, `S3-AC-020`, `S3-AC-081`, `S3-AC-082`, `S3-AC-083`, `S3-AC-187`, `S3-AC-188`, `S3-AC-190`, `S3-AC-198`

**Observed at the reviewed Head**

- AdvertisingQueueConsoleController authorizes generic ADVERTISING_VIEW and returns AdvertisingCaseView containing Contribution Profit, profit-per-ad-RUB, Spend, conversion, Max CPC and cross-scope evidence fields.
- No role/evidence-field projection distinguishes Maker-minimal view from Operations/Owner/Finance decision evidence.
- Export, notification, attachment and AI advertising disclosure paths are absent or unproved; manual evidence grade/verifier scope is forgeable as described in S3-DR-007.

**Risk**

A Store-scoped operator can receive financial or other-Store decision evidence outside the accepted minimum disclosure, and alternate channels can bypass UI restrictions.

**Required root-cause rework**

- Introduce server-side derived-field disclosure policies and role-specific DTO/projections while preserving one canonical evaluation result.
- Enforce approver evidence-scope eligibility and explicit masked-versus-absent states.
- Apply the same allowlist to API, UI, export, notification, attachment, audit drill-through and AI projection.

**Required closure evidence**

- Horizontal/vertical and field-level authorization tests for Maker, Operations Lead, Owner, Finance and unauthorized users.
- Cross-channel leakage tests and browser checks.

### S3-DR-019 — MAJOR — Ozon and Wildberries platform-native semantics are not demonstrated as distinct complete Shadow-only capabilities

**Affected Acceptance criteria:** `S3-AC-012`, `S3-AC-013`, `S3-AC-015`, `S3-AC-075`, `S3-AC-087`, `S3-AC-088`, `S3-AC-170`

**Observed at the reviewed Head**

- The schema can represent per-platform profiles and object relationships, but no query exercises relationships and no Ozon/Wildberries semantic-profile content proves their differences.
- The fixture platform correctly supports local writes, but it cannot substitute for both real-platform business semantics.
- Wildberries governed Manual Shadow is absent and the unverified path does not surface a complete Shadow state.

**Risk**

A lowest-common-denominator model or fixture-only path can masquerade as dual-platform product coverage.

**Required root-cause rework**

- Add synthetic, explicitly UNVERIFIED Ozon and Wildberries semantic/profile fixtures reflecting distinct identity/control/readback vocabularies without inventing endpoints or granting writes.
- Expose/query object relationships and both-platform read/diagnosis/Task/Manual Shadow/Outcome paths.
- Keep every real-platform capability, credential, active Bundle and write path unreachable.

**Required closure evidence**

- Separate Ozon/WB contract tests and browser scenarios.
- Negative assertion that neither platform becomes VERIFIED or writable.

### S3-DR-020 — MAJOR — Advertising-specific recalculation, SLO and declared-capacity evidence are incomplete

**Affected Acceptance criteria:** `S3-AC-023`, `S3-AC-068`, `S3-AC-069`, `S3-AC-070`, `S3-AC-071`, `S3-AC-194`, `S3-AC-197`

**Observed at the reviewed Head**

- Late/corrected Spend is stored but does not enqueue a recalculation request.
- The targeted worker records hard-bound observations, but no production path computes the required P95 or evaluates the critical distribution target.
- The passing RepresentativePerformanceIT result measures the availability portfolio; the candidate audit explicitly states no advertising load was applied to ops.ad_slo_observation.
- Staffed coverage and out-of-coverage active harm remain unused.

**Risk**

Advertising Cases can remain stale after corrections, and the Slice can claim capacity/SLO from an unrelated workload.

**Required root-cause rework**

- Wire every accepted/corrected/expired/matured advertising fact and authority change to targeted recalculation plus hourly full sweep repair.
- Measure P95/hard bounds from advertising observations and preserve violations through reconciliation.
- Run declared advertising capacity covering calculation, projection, Task, brief/outcome and write-gate state on a documented isolated runtime.

**Required closure evidence**

- Dropped-trigger, late-correction, restart/replay and policy-change tests.
- Advertising-specific capacity receipt with exact dataset/resources, P95 and hourly margin.

### S3-DR-021 — MAJOR — The console is largely read-only and does not expose the complete governed action workflow

**Affected Acceptance criteria:** `S3-AC-095`, `S3-AC-105`, `S3-AC-181`, `S3-AC-187`, `S3-AC-188`, `S3-AC-189`, `S3-AC-195`

**Observed at the reviewed Head**

- Queue, operations and brief readers exist, but the candidate audit records no production operator surface for selecting/rejecting an exact candidate and no complete Manual Packet issue path.
- Required Preview/approval/lease/command/unknown/mismatch/exception/kill/reenablement transitions are not all reachable through backend APIs and browser scenarios.
- State distinctions exist in fragments but are not proven across the full role-scoped console.

**Risk**

The implementation may pass component/browser tests without delivering the primary Action-first operating console.

**Required root-cause rework**

- Complete backend APIs and frontend flows for candidate selection/rejection, Task, Exception, Manual Shadow, Preview, endorsement/approval, command/readback, Outcome, containment and reenablement.
- Keep authorization server-side and render confirmed/estimated/stale/blocked, Provider accepted/readback/manual verified, Operational/Settled as distinct states.
- Verify keyboard, UTF-8/Russian, UTC/Store-local time, pagination and safe errors.

**Required closure evidence**

- Role-specific Playwright paths for Protection, Data Repair, Optimization, Watch, manual action, material approval, expiry, Unknown/Mismatch, regression, exception, kill and late Settled transition.

### S3-DR-022 — MAJOR — Test, evidence and traceability closure is not bound to the exact candidate and remains explicitly incomplete

**Affected Acceptance criteria:** `S3-AC-193`, `S3-AC-194`, `S3-AC-195`, `S3-AC-196`, `S3-AC-197`, `S3-AC-198`, `S3-AC-199`, `S3-AC-200`

**Observed at the reviewed Head**

- The repository's own acceptance index records 145 of 200 criteria as PARTIAL or NOT_YET and states that 83 are pure engineering work.
- No mutation-testing tool/score exists, no remote CI run exists for the branch, and no PR has been created.
- The measured test commit differs from the branch tip, and evidence files use stale/placeholder identities.
- Passing aggregate coverage and component tests do not close the named missing behavior, security, capacity and full-path evidence.

**Risk**

A green aggregate build can be mistaken for Production Acceptance while known acceptance gaps remain untested or unimplemented.

**Required root-cause rework**

- Close every engineering PARTIAL/NOT_YET criterion with executable behavior; retain only genuine S3-REL external obligations as deferred.
- Add mutation or equivalent systematic fault-seeding evidence for hard Gates, plus complete concurrency/restart/replay/security suites.
- Regenerate exact-head traceability, migration inventory, runbooks, evidence manifest and acceptance status; then run all protected remote checks on the exact rework Head.

**Required closure evidence**

- S3-AC-001..199 all VERIFIED by named source/test/evidence and S3-AC-200 prerequisites green.
- Independent Controller final review remains the authority for no unresolved BLOCKER/MAJOR.

## 4. Cross-cutting rework rules

1. Preserve the exact accepted Contract bytes. Normative change requires an exact
   additive Human Owner-accepted Amendment; none is currently required.
2. Preserve V0001–V0035 byte-for-byte. V0036–V0056 remain candidate migrations
   and may be coherently corrected before protected merge; recreate disposable
   databases and regenerate checksums/evidence afterward.
3. Rework the whole affected surface, not the smallest textual patch. Update
   backend, frontend, migrations, tests, runbooks, traceability and evidence
   together.
4. Do not satisfy engineering criteria with real Marketplace facts. Ozon/WB
   credentials, endpoints, Gate EV, Gate E, Pilot and production evidence remain
   deferred and fail-closed.
5. Do not weaken thresholds, validators, security rules, datasets or assertions.
6. Preserve all Provider/command uncertainty as first-class truth. Do not add
   automatic rollback, blind retry, standing automation, Budget/Status writes,
   inventory writes or a second authority.
7. After rework, every engineering acceptance criterion must be backed by named
   executable evidence. External S3-REL obligations remain explicitly
   production-blocking.
8. No merge, deployment, shared-environment mutation, real Credential or real
   Provider call is authorized by this finding set.

## 5. Rework completion return

Codex must return:

```yaml
base: 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430
starting_head: a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb
new_head: <exact>
new_tree: <exact>
worktree: CLEAN

frozen_finding_set:
  id: SLICE-V1-003-FROZEN-FINDING-SET-001
  sha256: <bind from manifest>
  findings_closed: 22_OF_22

contract:
  sha256: 1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c
  byte_identical: true

S3_AC_001_through_199:
  EXECUTABLY_VERIFIED

S3_AC_200_candidate_prerequisites:
  ALL_GREEN

S3_REL_001_through_024:
  EXPLICITLY_PRODUCTION_BLOCKING_DEFERRED

real_credentials: NONE
real_provider_calls: NONE
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
deployment: NOT_AUTHORIZED
production_write_enabled: false
```

The independent Controller Final Gate will decide whether every finding is
actually closed and whether `S3-AC-200` has no unresolved BLOCKER/MAJOR item.
