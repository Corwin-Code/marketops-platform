# Advertising data that is stale, incomplete or still being corrected

An unresolved advertising measure is not zero. Inspect the exact case calculation,
its evidence purpose and the contributing canonical facts before retrying work.
A current queue row is not proof that a recommendation, bid write or outcome has
current evidence.

## Read the purpose and reason

`mart.ad_case_purpose_evidence` records separate assessments for queue observation,
Task activation, Protection and Optimization recommendations, and Protection and
Optimization bid writes. Each required kind retains its selected Owner profile,
actual source and acceptance timestamps, expiry, eligibility and reason codes.
Outcome plans separately freeze the selected early, retained and settled freshness
profiles with their before-action snapshots.

```sql
SELECT e.decision_purpose,e.evidence_kind,e.freshness_profile_id,e.source_time,
       e.accepted_at,e.expires_at,e.eligible,e.reason_codes
  FROM mart.ad_case c
  JOIN mart.ad_case_purpose_evidence e
    ON e.case_id=c.id AND e.calculation_id=c.calculation_id
 WHERE c.organization_id=:organizationId AND c.id=:caseId
 ORDER BY e.decision_purpose,e.evidence_kind;
```

- **Stale** means a required source or acceptance deadline failed, or the selected
  profile is no longer effective. A newly accepted old official spend report does
  not become current merely because its ingestion timestamp is new.
- **Incomplete** includes a missing interval, overlapping aggregate windows,
  unresolved linkage, mixed currency, or absent values. One complete child row
  does not establish complete coverage of the requested window.
- **Provisional** includes an open correction window when the purpose requires
  closure. The published lag and correction rules are evaluated independently.
- **Estimated** remains visible but cannot supply canonical bid-write economics.
  Missing or overlapping effective policy authorities remain unresolved; the
  resolver does not fall back to a broader policy to escape an ambiguous narrow
  scope.

Effective-dated canonical cost and fee metrics use their actual applicability
calculation time for freshness. The age of a still-applicable cost transaction by
itself does not invalidate that metric. Its input lineage and confidence remain
part of the evidence; recomputing cannot turn estimated or missing costs into
confirmed costs. A mature retained-sales cohort is evaluated under its published
purpose profile, with its actual coverage and provenance retained. Historical
replay selects canonical Metric versions whose computed time is at or before the
captured decision instant. A later Metric does not replace that earlier input or
make it disappear; corrected facts and later calculations remain separate history.

## Check the acquisition and calculation

```sql
SELECT f.id,f.period_start,f.period_end,f.spend_amount,f.clicks,
       f.report_window_complete,f.correction_window_open,f.source_time,f.recorded_at
  FROM ledger.ad_object_fact f
 WHERE f.organization_id=:organizationId AND f.ad_native_object_id=:objectId
   AND f.recorded_at<=:asOf
   AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                    WHERE later.supersedes_fact_id=f.id AND later.recorded_at<=:asOf)
 ORDER BY f.period_start DESC,f.id LIMIT 20;
```

Check the acquisition backlog, missing source windows, mapping conflicts and
provider incident evidence. Follow `acquisition-backlog.md`,
`advertising-mapping-or-linkage-gap.md` or `advertising-provider-incident.md` for
the actual failed dependency. Repair the source and let the targeted refresh
recalculate the affected objects. Scheduled expiry checks and reconciliation
also use the canonical advertising calculation path.

A Protection responsibility Task can remain actionable when complete write-grade
MaxCPC economics are unavailable. A cause-bound bid candidate is restricted to a
published `KNOWN_NOT_SELLABLE_WITH_FRESH_SPEND` or
`KNOWN_UNAVAILABLE_WITH_FRESH_SPEND` cause, with fresh spend, exact current native
configuration, complete affected set and the matching sellability or availability
proof. Missing conversion and economic facts do not suppress that one-sided harm,
but unresolved critical-sales safety or another independent control still blocks
writing. A separate accountable DataRepair case retains the missing evidence.
Task activation and write qualification use their own published closed-window
thresholds; a visible Task is not authorization to write.

## Operator boundary

Do not enter an advertising number to fill a missing official fact, change a
confidence flag, extend a deadline, mark a partial set complete, or widen a policy
to make one case pass. Policy changes require the governed Owner version and
scope. A changed authority starts a new review chain; a previously selected
baseline cannot be silently replaced. Production enablement remains independent
of successful calculation, manual work, Gate evidence and CI.
