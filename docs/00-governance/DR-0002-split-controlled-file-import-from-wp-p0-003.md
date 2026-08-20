# DR-0002 — Split Controlled File Import from WP-P0-003

```yaml
decision_request: DR-0002
status: ACCEPTED
trigger: CONTROLLER_PHASE_0_PLANNING
owner_approval: EXPLICIT
owner_instruction_date: 2026-08-20
controller_recommendation: ACCEPT_BOUNDED_SPLIT
effective_condition: GOVERNANCE_PR_MERGE
```

## Problem and trigger

The Phase 0 backlog currently allocates `INT-019` controlled CSV/Excel/report
import to the same draft package as the ingestion worker, cursor, immutable Raw,
replay and backfill foundation. Independent Controller planning found that file
intake introduces a separate actor, authentication, malware/schema, size and
operator-workflow trust boundary and makes the next package too broad.

## Current rule/design

- `WP-P0-003` is DRAFT under the live pre-change baseline and has no canonical
  Work Package record.
- The backlog allocates `INT-019` to that draft row together with
  `INT-001/004/006–014/021`.
- ADR-0002 requires immutable evidence for manual imports but does not require
  the importer and the generic worker/Raw foundation to share one Work Package.
- No Design, migration or implementation for either capability exists.

## Proposed change

Canonicalize `WP-P0-003 — Durable Ingestion Control Plane & Immutable Raw
Evidence` with the coupled lease/cursor/idempotency/Raw/replay/backfill and generic
resilience boundary. Reallocate `INT-019` and the manual-file portions of
`INT-010/011` to a new inactive backlog item:

```text
WP-P0-003B — Controlled File Import & Source Intake Security
```

`WP-P0-003B` remains `DRAFT`, receives no canonical Work Package record and is
not authorized for Design or implementation by this request.

## Alternatives considered

1. Keep the entire allocation in one package — rejected because worker
   concurrency/object durability and file-intake security create independently
   reviewable failure surfaces and hidden OQ-005/OQ-006 blockers.
2. Split generic retry/rate limiting from the worker — rejected because retry,
   lease and cursor transitions share crash/ordering invariants.
3. Wait for OQ-006 before recording any scope — rejected for reversible
   governance work; provider-neutral Design can begin while concrete provider
   approval remains a hard Gate.

## Affected Requirements, ADRs and modules

- Requirements: `INT-019`, plus manual-file portions of `INT-010/011`.
- Planning allocations updated for `D-03`, `D-04`, `HR-01`, `HR-02`,
  `INT-001/004/006–014/019/021`, `ADM-002` and `ADM-004` without changing
  their source meaning. `ADM-004` is explicitly `PARTIAL / MULTI-WP`:
  `WP-P0-003` owns generic Job Run/Error Queue/Replay/Dead-letter state,
  recovery-command contract, audit linkage and the single runtime authority;
  `WP-P0-008` owns the Data Quality/Admin product view, cross-domain UX and
  final Phase 0 management closure. OQ-005 and a future runtime IAM Work Package
  gate any authenticated/public operator surface.
- ADR-0001, ADR-0002, ADR-0003 and ADR-0004 remain accepted and unchanged.
- No module boundary changes now. Future Design keeps `marketplaceintegration`
  as the sole scheduler/worker, cursor/checkpoint writer, replay/dead-letter
  recovery-command executor and Raw object-store intake coordinator. The
  `adminobservability` module consumes that contract and may request recovery;
  it is not a second executor/writer. A later file-intake path consumes the
  approved Raw intake contract.

## Data migration and compatibility impact

None. This governance transition creates no migration, schema, runtime behavior,
dependency or Design artifact. V0001–V0006 and completed WP-P0-001/WP-P0-002
records remain immutable/closed.

## Security and privacy impact

The split makes the public/file-intake authentication, malware/schema, size and
operator controls explicit rather than silently mocking them in the generic
foundation. No file-upload surface, Secret, Credential, production PII or
Marketplace outbound path is introduced. OQ-005 and OQ-006 remain OPEN.

## Testing and evidence plan

- governance validation binds the active `WP-P0-003` record to
  `READY_FOR_DESIGN / DESIGN_ONLY` and `INT-019` to DRAFT `WP-P0-003B`;
- mutation tests reject `INT-019` FULL closure, missing later owners, a second
  active Work Package, weakened WP-P0-001/WP-P0-002 closure and removal of the
  OQ-006 Gate;
- changed-file, baseline/migration-hash, Secret/PII and production-write checks
  prove this is governance-only.

## Rollback plan

Before Design begins, revert this governance Pull Request and restore the prior
planning state through the protected PR Gate. After a Design artifact is started
or accepted, any allocation reversal requires a new Decision Request and
Controller review; no silent backlog edit is permitted.

## Cost and operational impact

No current infrastructure, provider or operating cost. The later file-intake
package adds another Design/review handoff but reduces mixed trust boundaries and
makes acceptance independently falsifiable.

## Owner decision required

The Human Owner explicitly accepted this bounded planning split by invoking the
exact Controller next-action prompt on 2026-08-20. This scope decision authorizes
the Draft governance PR workflow; it does not authorize that PR's merge, Design
implementation or production behavior. The Human Owner must separately authorize
the exact governance PR merge after independent Controller review and all Gates.

## Controller recommendation

`ACCEPT_BOUNDED_SPLIT`, as recorded by the Phase 0 Planning verdict
`READY_FOR_WP_P0_003_CANONICALIZATION`.

## Final status and effective date

`ACCEPTED`, with repository effective date pending. It becomes effective only
when the separately Owner-authorized governance PR is merged to protected `main`.
Until then, the live `main` allocation and planning-only state remain authoritative.
