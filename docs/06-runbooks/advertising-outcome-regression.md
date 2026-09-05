# An advertising intervention whose outcome regressed

A canonical observation with `verdict='REGRESSED'` activates
`ACTION_OUTCOME_QUARANTINE` over its exact frozen affected set. This applies to
API and governed manual interventions, including early sales regressions and
later corrections. Financial maturity is not a reason to ignore a proven early
sales safety failure. Unknown evidence does not establish either a regression or
a successful guard.

## Read the frozen plan and stage

The Maker selection freezes an exact baseline and published Owner policy before
approval. Ops Lead endorsement and Owner approval bind that same baseline; a new
calculation, policy, affected set or expired plan requires a new review chain.
The observed window starts from actual verified landing plus the frozen offset,
not from command creation or an executor's report.

The canonical planner uses the separately configured trusted issuer to attest the
entire computed baseline, all three stage snapshots and critical-unit membership.
Its short-lived proof is bound to the exact application backend and transaction,
and is consumed once by the database freeze operation. Application SQL has no
INSERT privilege on those frozen tables and cannot mint its own planner proof.
Selection, final sealing and command execution verify the stored payload digest,
typed Owner policy, scope, windows and complete stage/freshness shape. Replacing
an amount or labeling caller JSON `COMPLETE` cannot substitute for this authority.
If the trusted issuer is unavailable, the plan remains unavailable and the
responsibility Task remains visible; do not provision credentials or weaken the
boundary to make a case pass.

| Stored stage | Canonical basis | Permitted conclusion |
| --- | --- | --- |
| `OPERATIONAL`, `OPERATIONAL_REVISED` | Actual Completed Sales over the Owner's early guard window | Early company and per-critical-unit sales safety; not profit success |
| `RETAINED`, `RETAINED_REVISED` | Actual retained cohort with the fixed 30-day maturity and window | Primary Operational contribution-profit and profit-per-ad-RUB comparison |
| `SETTLED`, `SETTLED_REVISED` | Actual settled financial facts matched to retained company and ad-linked cohorts | Settled comparison; incomplete financial attribution or cost confidence remains unresolved |

Both profit axes use the published material thresholds, separate non-worsening
bands, rounding, boundary rule and minimum spend denominator. Absolute profit
and profit per ad RUB remain independent; improvement on one axis does not offset
failure of the other. Negative improved profit remains `IMPROVED_NOT_HEALTHY`.
Zero spend leaves profit per RUB undefined.

Company sales must be preserved across all company channels of the affected
products, and every frozen required product/listing critical unit must pass its
own guard. Strong aggregate sales cannot offset a failed critical unit. A complete
canonical report with zero events can establish zero; missing coverage cannot.
An effective blocking provider incident, stale acceptance or missing required
unit retains an unresolved guard and the reservation.

## Inspect current evidence

```sql
SELECT o.id,o.command_id,o.manual_packet_id,o.outcome_stage,o.revision_no,o.verdict,
       o.guard_state,o.supersedes_observation_id,
       a.dual_axis_verdict,a.sales_preservation_verdict,a.business_outcome
  FROM ops.ad_outcome_observation o
  JOIN ops.ad_outcome_axes a ON a.observation_id=o.id
 WHERE o.organization_id=:organizationId AND o.ad_native_object_id=:objectId
 ORDER BY o.evaluated_at DESC,o.revision_no DESC;
```

Read the axes' before/after values and snapshots, frozen
`ops.ad_outcome_stage_baseline`, and every `ops.ad_outcome_critical_guard` row.
The business outcome distinguishes `OUTCOME_PENDING`, `PROTECTION_IN_PROGRESS`,
`IMPROVED_NOT_HEALTHY`, `OUTCOME_CONFOUNDED`, `VERIFIED_AD_EXPOSURE_STOPPED`,
`VERIFIED_AD_RISK_CLEARED` and `VERIFIED_EFFICIENCY_SUCCESS`. A stopped exposure or
cleared loss is a narrower fact than verified efficiency success and does not
waive sales safety. Configuration verification alone is not a business outcome.

Exposure stopped requires exact action identity and affected scope, verified
configuration and canonical complete closed zero-spend coverage of the whole
window. Physical risk clearance requires the original sellability or availability
cause to be resolved throughout a complete qualified safety window; missing
profit attribution does not invent either financial success or physical harm.
Economic risk clearance requires the original loss to be resolved with canonical
nonnegative profit and preserved sales under the frozen stage rules.

If a same-window correction invalidates a prior verified terminal proof, the
existing responsibility reopens and its authority remains invalidated. Across
stages, a new unknown financial observation alone does not erase an earlier
verified safety window; actual renewed exposure or cause-specific harm does.
Repeated observations reuse the same Task and observation links. These changes
do not fabricate a financial regression or an automatic compensation command.

## Containment and release

The database derives automatic regression containment from the actual immutable
observation, its sealed intervention and exact affected set. It invalidates prior
authority and records an accountable reviewer. Reconciliation reopens the same
responsibility lineage without resetting its original age or SLO history. A late
regression after release quarantines the affected set; it cannot displace a newer
overlapping reservation holder.

A current conclusive Settled regression, or Settled no-material-improvement
that contradicts the same action's prior Retained efficiency success, also
creates a linked `FINANCE_ANALYST` Shared Task. The original advertising Task,
Case, action baseline and original age remain attached. Finance assignment must
cover the entire frozen product/store scope. Replayed observations reuse the
same Finance Task; a later contradictory revision reopens that task rather than
resetting responsibility. Unknown or confounded financial evidence cannot be
labeled a contradiction merely to create or close a review. The Finance review
is distinct from advertising containment: no-improvement does not manufacture
a `REGRESSED` observation or an automatic reverse write.

Inspect `ops.ad_outcome_review_responsibility` and
`ops.ad_outcome_review_observation` for the canonical observation links. Close
the Finance review only through the Shared Task workflow after a current,
conclusive Settled reconciliation; a favorable value does not silently erase
its preceding verdict or lift quarantine.

A reservation can release after current verified configuration and the latest
eligible early observation establish preserved company sales and PASS for every
frozen critical unit, with no current regression or active containment. The API
readback must match the exact target (or proven compensated prior bid); manual
proof must be current, independent or official, and conflict-free. Unknown,
missing, stale or self-reported evidence holds the reservation. Operators cannot
set observation-complete flags to bypass these checks.

Review confounders such as changed price, promotion, sellability or availability.
Use `advertising-unknown-result.md` for a governed compensation preview and human
review. Compensation requires its own exact current authority and proven owned
configuration; it is not an automatic response to every regression. Lift
containment only through `advertising-reenablement.md`.

Later canonical corrections append a revision that names the observation it
supersedes. The original observation and frozen baseline remain immutable. A
corrected healthy result does not automatically clear quarantine or revive a
revoked action chain. Keep all real Provider and production actions inside their
separate explicit authorization boundaries.
