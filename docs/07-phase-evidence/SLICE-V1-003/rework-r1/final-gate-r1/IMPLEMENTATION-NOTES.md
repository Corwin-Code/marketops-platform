# Existing R1 residual rework: implementation and verification boundary

This is additive engineering documentation for the same accepted Contract and
Frozen Finding Set. The supplied Controller verdict applies to W10 Head
`3ff042df66d5d6924b587cac96fc652b93bf5e7a`. It remains
`NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED`; this document does not substitute
for independent review of a new candidate. The current execution manifest and
central matrices carry the actual verification state.

## CV-A: each consumed input keeps its own authority

The Planner freezes the scoped Profile version and bounds for each evidence
kind and Outcome purpose. Observation consumes that frozen map, checks its
canonical digest and current validity, and records each qualification. A newer
favorable Profile cannot replace a revoked, expired, altered or differently
scoped version. Source age and acceptance age are separate checks. Null or
future consumed timestamps fail qualification even when an age bound is absent.
Aggregate minimum timestamps establish age; they do not hide a future component.

Company coverage consumes only the stage's required source fields from the
canonical return/quality report. Mature historical cohorts remain valid when
the exact report and still-effective cost evidence meet their frozen Profiles.
Canonical Metric selection requires the cost period to cover all consumed
linked-sale cohorts before choosing the latest value. A newly computed Metric
for an unrelated period cannot silently replace the applicable historical cost.
No alternative Metric writer or second financial ledger is introduced.

Physical and price context cover the whole original observation window. An
identical retrospective report may refresh the same effective state, using its
actual source and acceptance times. Conflicting reports at the same effective
time remain unresolved. The affected scope includes the frozen company mapping,
mapping conflicts, all relevant configuration history, and the original
semantic Profile and object lineage generation. A new favorable mapping or
object generation cannot prove clearance of the old action's cause.

## CV-B: exact Policy qualification of proved economic danger

The shared Java dependency policy and SQL decision guards consume the same
persisted purpose evidence. Explicit Policy permission can qualify proved
negative canonical economic harm with the necessary fresh, complete inputs
when conversion evidence is unknown. Unknown economics are never zero or
proved loss. Full sales, inventory, scope, exception, role and approval controls
remain required. Planner, Preview, selection, command sealing and transmit
guards use the same cause-qualified evidence authority.

The integration path uses actual application services and isolated PostgreSQL,
including inherited Availability refresh and fixture APPLY/readback. It does
not use a real Provider, provision a real credential, or enable production
write. Positive and adverse cases belong to the execution manifest; source
method inventories alone are not passing evidence.

## CV-C: cause-specific conclusions and preserved history

Physical risk clearance is bound to the frozen canonical original cause
(`PROMOTED_VARIANT_NOT_SELLABLE` or `PROMOTED_VARIANT_UNAVAILABLE`) and complete,
qualified physical evidence over its action window. It does not require or
claim profit, company-sales, listing or inventory repair. Exposure stopped
requires a complete closed source report covering the entire window, verified
configuration and exact scope, with zero new spend. Ordinary Outcome coverage
thresholds do not lower this complete-source requirement.

Input fingerprints trigger bounded revisions for accepted source corrections,
scope/control changes, source admissibility, Profile invalidation and expiry.
An unchanged qualified input does not generate a new revision every clock tick.
Earlier receipts stay immutable. Invalidated claims and confirmed recurring
danger return to the same action/responsibility and canonical quarantine
mechanism; missing new-stage financial attribution remains unknown and does
not invent a financial regression or a Finance Review conclusion.

## CV-D and CV-E: separate workloads and measured identities

The original 1,000-object capacity evidence remains historical. The additive
mixed workload includes 200 critical objects, mature Outcomes, corrected
Outcomes, held/released reservations and expired/invalidated authority. It
measures actual targeted and hourly orchestration with the original five-minute
critical P95, fifteen-minute hard bound and thirty-minute sweep bound. Historical
fixture commands are declared inputs, not measured Provider throughput.

Each local or CI measurement identifies its own dataset, source inputs, command,
runtime limits and raw reports. Host, JVM, Docker VM and individual container
limits are separate observations. W8 local, W8 CI and the two W10 CI artifacts
remain separate measurements. JaCoCo report-root counters govern the report
gate; CSV class sums are a distinct aggregation. New closure requires executed
evidence joined to actual source digests and the published candidate; no old
PASS is rebound to newly changed files.

`production_write_enabled=false` throughout. The designated branch is append
only; PR 30 remains Draft. Codex has no authority here to mark Ready, merge,
force-push, access a real Provider or shared/production environment, modify the
accepted Contract/Frozen Set, or replace the independent Controller verdict.

### Canonical historical Metric re-evaluation proof (verification pending)

The actual day60 path exposed a transitive cost freshness defect: a historical D30
calculation that deduplicated to the same Metric value had no proof connecting the
new computation to that old value. `runForWindow` now accepts an exact, aligned,
non-future named window through the existing `AnalyticsCalculationService` and
`MetricEngine`; there is no second Metric writer or new HTTP/permission surface.
V0070 appends `(metric_value_id, calculation_run_id, evaluated_at)` only through
`recordValue`, including when the immutable value is reused. The table rejects
mismatched organization/store/period, closed-run insertion and history mutation.
Only a matching successful run completed by the reading instant qualifies its
evaluation; the original value, input digest and `computed_at` remain unchanged.
`MetricQuery` exposes the separate verification time/run identity, selecting the
latest evaluated value without skipping a newer unavailable result. Advertising
cost purpose freshness and Outcome use this same proof; Outcome stores the run
reference and revision detection consumes the identical proof/selection. Merely
recording a completed run via the shared run ledger cannot refresh an old value.

This is an implementation correction under existing Contract §6.8. It does not
relax a frozen Profile, change a prior baseline, or upgrade a stale stored value.
Actual positive/adverse tests and full regression remain required before closure.
