# Controller Review — DR-0003 MarketOps V1 Baseline Reset

```yaml
document_type: controller_reset_review
controller: GPT-5.6 Sol Pro
repository: Corwin-Code/marketops-platform
reviewed_base: 52a657f7f6358f43246e03457ba2d48ef658986a
review_date: 2026-08-26
scope: PRODUCT_AND_DELIVERY_BASELINE_RESET
verdict: APPROVE_RESET_PACKAGE_FOR_CODEX_GOVERNANCE_EXECUTION
merge_verdict: NOT_ISSUED
production_enablement: NOT_AUTHORIZED
```

## 1. Review question

Whether the current MarketOps Baseline, Phase 0–3 plan, Work Package state
machine and mandatory Design Gate still provide the fastest production-grade path
to the Human Owner's now-confirmed V1 product outcome, and, if not, what exact
repository reset is required before product implementation continues.

## 2. Sources reviewed

The review is bound to `main` at
`52a657f7f6358f43246e03457ba2d48ef658986a` and cross-checked:

- all live governance records and agent contracts;
- `baseline-v1.0-cn.md`, its Requirement IDs, hard rules and roadmap;
- ADR-0001 through ADR-0004;
- V0001 through V0010 without changing their bytes;
- backend module tree, marketplace-integration ports and authority code;
- frontend product surface, which remains a HealthShell only;
- architecture, database and governance tests;
- WP-P0-001/002/003 evidence, including PR #16 post-merge verification;
- explicit Human Owner answers from the 2026-08-26 Controller discovery session.

No Marketplace Credential, Secret, Buyer PII, production payload or external
platform call was requested or used.

## 3. Verified repository facts

1. Live state remains `WP-P0-003 / DESIGN_ONLY /
   CONTROLLER_WP_P0_003_DESIGN_FINALIZATION` even though the repository contains
   merged production code, V0007–V0010 and extensive real-PostgreSQL evidence for
   the bounded ingestion-authority tranche.
2. The Phase 0 backlog deliberately makes each WP independently designed,
   implemented and reviewed, while the same backlog also calls for a preferred
   end-to-end vertical slice.
3. Claude's current contract requires a separate Design stage and exact
   `APPROVED_FOR_IMPLEMENTATION` verdict for nearly every substantive MarketOps
   change: architecture, migration, Raw/Ledger, Marketplace Adapter, IAM,
   finance, worker/outbox, platform write and cross-module work.
4. The current product source contains strong foundation modules but no complete
   Product, Inventory, Order, Return, Finance, Ads, Workflow, AI or operational
   console business loop. The React application renders only the HealthShell.
5. The current Baseline's product value chain already points toward
   `Fact → Diagnosis → Recommendation → Task → Approval → Controlled Execution
   → Readback → Outcome`, but its rollout decisions postpone dual-platform write
   capability and split that chain across serial horizontal WPs and phases.

## 4. Findings

### BLOCKER — the active execution authority conflicts with confirmed V1 intent

The Human Owner has explicitly redefined V1 as a production-grade internal
operations platform that uses real Ozon and Wildberries data plus internal COGS,
warehouse and finance facts; performs broad deterministic and AI-assisted
analysis; and exposes selected guarded writes on both platforms. The live
Baseline still makes Ozon-first/WB-read-parallel, no unattended first-version
write and Phase Gate sequencing authoritative. Product implementation cannot
safely start while both sets of rules remain live.

### MAJOR — Work Package and Gate granularity has become the delivery bottleneck

The repository treats horizontal infrastructure WPs as independent product
stages. WP-P0-003 therefore carries substantial correctness scope but, by its own
non-goals, cannot deliver a user-visible operating capability. Its implementation
was classified as design evidence and returned to Design finalization after
merge. This is a structural planning problem, not merely an unfinished design.

### MAJOR — no single active V1 Product Contract exists

The repository lacks one authoritative contract covering the current V1 user
outcome, data domains, AI authority, internal data intake, dual-platform guarded
write, identity, infrastructure, delivery slices and V1 completion conditions.
Without it, each later WP is forced to reinterpret the product locally.

### MAJOR — the current review protocol applies project-grade ceremony too often

The 11+1 review and artifact contract are useful at genuine Contract, Deep Review,
Final and Production Enablement Gates. Applying the full package to every small
planning/design/rework transition creates context reconstruction, duplicate
artifacts and serial waiting without increasing executable evidence.

### INFORMATIONAL — existing engineering assets remain valuable

The repository, CI, modular-monolith boundaries, identity/account metadata,
Capability Registry, no-secret controls, ingestion authority, Raw evidence model,
architecture tests and V0001–V0010 should be preserved and adapted. A repository
restart is not justified.

## 5. Controller decision

A formal Development Baseline Reset is required.

- Preserve Git history, `main`, existing evidence and V0001–V0010.
- Supersede the old Phase 0–3 and WP backlog only as the active execution plan.
- Reclassify WP-P0-003 as bounded Shared Spine provenance, not as the current
  product stage and not as fully completed.
- Establish `V1_PRODUCT_CONTRACT.md`, `OWNER_DECISIONS_V1.md`, new ADRs, a Shared
  Spine and Production Delivery Slice model.
- Make `SLICE-V1-001 — SKU Growth & Profit Diagnostic Loop` the active delivery
  slice and authorize Claude to perform Detailed Design plus Initial Full
  Implementation continuously after the reset PR is merged.
- Replace mandatory per-WP Design Approval with a Conditional Design Gate for
  genuine Owner, irreversible, authority, security, provider or data-loss risks.
- Retain strong independent GPT review, Codex production rework/fix/verify, CI,
  final merge review and controlled Capability enablement.

## 6. Asset disposition

| Asset class | Decision |
| --- | --- |
| Repository, protected PR flow, CI/security workflows | KEEP |
| Java/Spring/PostgreSQL/Flyway/React modular monolith | KEEP |
| V0001–V0010 | FREEZE; forward-only V0011+ |
| Organization/Account/Store/Warehouse metadata | KEEP AND EXTEND |
| Service Account/Scope/Credential metadata | KEEP AND EXTEND |
| Capability/Endpoint/Feature Flag registry | KEEP AND EXTEND |
| Acquisition/Raw authority and ports | ADAPT INTO SHARED SPINE |
| Existing architecture/database tests | KEEP; extend by Slice |
| HealthShell | KEEP as operational health surface, not product home |
| Phase 0 backlog and WP-P0-003 active gate | SUPERSEDE AS ACTIVE PLAN |
| WP designs and phase evidence | HISTORICAL PROVENANCE; DO NOT DELETE |
| Mandatory Design Gate for every substantive WP | RETIRE |
| Old Ozon-first/WB-read-parallel and no-first-version-unattended-write rollout | SUPERSEDE |

## 7. Reset PR boundary

The authorized next PR is governance-only. It may change governance documents,
agent contracts, templates, traceability and the governance validators/tests. It
must not change:

- backend or frontend product source;
- V0001–V0010 or any database migration;
- infrastructure runtime configuration;
- existing WP-P0-001/002/003 evidence bytes;
- source Baseline v1.0 or Naming Baseline bytes;
- any Secret, Credential, production payload or Buyer PII;
- production-write runtime state.

## 8. Verdict

```text
APPROVE_RESET_PACKAGE_FOR_CODEX_GOVERNANCE_EXECUTION
```

This verdict authorizes Codex to create a short-lived governance branch, import
the byte-preserved canonical overlay, implement only the bounded validator and
traceability patches, run the specified checks and open a Draft PR.

It is **not** an `APPROVE_FOR_HUMAN_MERGE` verdict. The resulting PR requires a
fresh independent Controller review against its actual Head, tree, diff and CI.

```text
NEXT_AUTHORIZED_ACTOR: CODEX
NEXT_ACTION: CONTENT_PRESERVING_DR_0003_GOVERNANCE_GIT_EXECUTION
```
