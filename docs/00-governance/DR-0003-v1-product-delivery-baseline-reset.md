# DR-0003 — MarketOps V1 Product & Delivery Baseline Reset

```yaml
decision_request: DR-0003
status: CONTROLLER_APPROVED_PENDING_REPOSITORY_EFFECT
decision_class: JOINT_OWNER_CONTROLLER
trigger: OWNER_CONFIRMED_PRODUCT_INTENT_REBASE
owner_direction: EXPLICIT
owner_instruction_date: 2026-08-26
controller_verdict: APPROVE_RESET_PACKAGE_FOR_CODEX_GOVERNANCE_EXECUTION
reviewed_repository_base: 52a657f7f6358f43246e03457ba2d48ef658986a
effective_condition: PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION
migration_effect: NONE
production_write_effect: NONE
```

## 1. Problem and trigger

MarketOps was intended to use Vibe Coding to reach a production-grade operating
product quickly. The active repository plan instead serializes horizontal
foundation Work Packages through independent Design, approval, implementation,
rework and closure Gates. At the reviewed Base, WP-P0-003 has already produced
merged production code, database migrations and extensive executable evidence,
but the project remains in `DESIGN_ONLY / DESIGN_FINALIZATION` and has not
produced a user-facing business loop.

During the 2026-08-26 Controller discovery session, the Human Owner explicitly
confirmed that the immutable target is the product outcome—not the existing
Phase/WP/Gate arrangement—and authorized a full planning reset where necessary.
The confirmed V1 outcome materially changes the old rollout decisions.

## 2. Current authority being changed

The pre-reset authority includes:

- D-01: Ozon end-to-end first, Wildberries read in parallel;
- D-02: no unattended platform write in the first version;
- D-10: no automation expansion before the relevant Phase Gate;
- Phase 0–3 as the active delivery roadmap;
- independently designed and closed horizontal Work Packages;
- a mandatory pre-implementation Design Gate for nearly every substantive task;
- WP-P0-003 as the active project stage.

These rules were coherent with the 2026-08-06 rollout assumptions but no longer
match the confirmed V1 product boundary.

## 3. Decision

### 3.1 Product baseline

V1 is a production-grade internal operations and decision platform for one
Russian operating entity and its multiple Ozon/Wildberries Stores, Warehouses and
Users. Its primary outcome is to help operators improve sales and Contribution
Profit through one auditable loop:

```text
Trusted Marketplace + Internal Facts
→ Deterministic Metrics and Data Quality
→ AI-assisted Cross-domain Diagnosis
→ Recommendation and Task
→ Commercial Policy / Guardrail
→ Approval or Pre-authorized Policy
→ Selected Official Marketplace Write
→ Readback and Audit
→ Outcome Follow-up
```

V1 covers all major decision domains—Product/Listing, Price, Promotion, Inventory,
Advertising, Order/Fulfillment, Return and Finance—but exposes real platform
writes selectively. Both Ozon and Wildberries must have at least one controlled
`Write → Readback` capability in V1. `PRICE_CHANGE` is the first targeted
capability, subject to real Capability evidence and a bounded Pilot Cohort.

### 3.2 AI and truth

Core facts and official operating metrics are deterministic, versioned,
reproducible and evidence-linked. AI performs broad analysis, inference,
prioritization and concrete recommendation; it is not a second fact source and
cannot authorize or directly execute a platform action.

Approved, field-allowlisted and PII/Secret-redacted operating data may be sent to
eligible external cloud model providers through a provider-neutral AI Gateway.
Buyer name, phone and full address are excluded from the AI path and general
Analytics/Mart by default.

### 3.3 Internal data

V1 includes COGS, local physical inventory and necessary finance/accounting facts.
Until system APIs are known, the supported product paths are controlled manual
entry and audited Excel/CSV import. Contribution Profit is the primary operating
profit measure. Canonical and estimated profit are separated; high-risk writes
require stricter data-completeness and Confidence Gates.

### 3.4 Infrastructure and identity

Yandex Cloud `ru-central1` is the V1 primary production environment. V1 does not
build multi-cloud, but external infrastructure remains behind replaceable
Port/Adapter boundaries. Exact topology and service configuration require
primary-source verification and production evidence.

Human users authenticate through a production-grade external OIDC Identity
Provider with mandatory MFA; Yandex Identity Hub is the default provider selected
for V1 subject to account provisioning and primary-source verification. MarketOps
owns business RBAC, Store/Platform/Warehouse/Data Scope, Approval and Policy
authority. MarketOps does not implement password or MFA protocols itself.

### 3.5 Delivery model

`Production Delivery Slice` becomes the primary Vibe Coding and business delivery
unit. A Slice is a complete user-visible operating capability. A Work Package, if
used, is only an implementation tranche or context boundary inside an approved
Slice and is not an independent product phase.

The default workflow is:

```text
GPT Controller: Product/Slice Acceptance Contract
→ Claude: Detailed Design + Initial Full Implementation continuously
→ GPT: Source-first Design + Implementation Deep Review
→ Codex: Full in-scope Production Rework / Fix / Verify
→ GPT: Final PR Gate
→ Human Owner / delegated Git executor: protected merge
→ Capability-specific Production Enablement Gate
→ Controlled Production Release
```

A pre-implementation Design Gate is conditional, not mandatory. It is triggered
only by a genuine Owner decision, irreversible migration, authority/source-of-
truth change, new security/privacy/legal boundary, new external provider or
unbounded financial/data-loss risk. Normal engineering design remains Claude and
Codex implementation freedom inside the accepted contract.

### 3.6 Release model

A production-grade Slice may enter bounded real use before every V1 Slice is
complete. `Slice Production Released` and `V1 Product Complete` are separate
states. Pilot scope limits enablement, not implementation quality. V1 completion
is based on production capability, not proof of a predetermined sales/profit
uplift; business outcome measurement continues after release.

## 4. Supersession matrix

| Prior authority | New disposition |
| --- | --- |
| D-01 Ozon-first / WB read-parallel | SUPERSEDED by D-19: dual-platform decision and selected write on both platforms |
| D-02 no unattended first-version write | SUPERSEDED by D-19: low-risk Policy-authorized semi-automation; high risk remains approval-bound |
| D-10 Phase Gate before automation | SUPERSEDED by D-22/D-24: Slice and Capability enablement Gates |
| ADR-0003 rollout sequencing | SUPERSEDED IN PART by ADR-0008; its complete controlled-write chain remains binding |
| ADR-0004 mandatory interpretation | REFINED by ADR-0006; Maker–Checker independence remains binding |
| DR-0002 active allocation | SUPERSEDED AS ACTIVE DELIVERY ALLOCATION; its file-intake trust-boundary analysis remains binding |
| `BACKLOG-PHASE-0.md` | HISTORICAL PROVENANCE, not active execution authority |
| WP-P0-003 active stage | SUPERSEDED AS ACTIVE DELIVERY UNIT; bounded merged assets remain verified Shared Spine provenance |
| Phase 0–3 roadmap | HISTORICAL PRODUCT PLANNING PROVENANCE; replaced by V1 Delivery Slices |

No previously verified code, migration or evidence is declared incorrect merely
because its old delivery allocation is superseded.

## 5. Preserved authority

The following remain binding unless a later formal decision changes them:

- D-03 Modular Monolith + PostgreSQL Worker;
- D-04 immutable Raw, Inventory Ledger and Financial Ledger;
- D-05 Variant/Color/Size/Purchase Batch granularity;
- D-06 third-party competitor data is trend-only;
- D-07 AI has no Marketplace Credential or direct execution authority;
- D-08 official APIs are the only programmatic Marketplace path;
- D-09 Metrics and Mapping are versioned;
- D-15 Public pre-production repository and mandatory Private conversion before
  confidential production material;
- D-16 Owner Git Workflow Guidance;
- D-17 bounded Codex mechanical Ready/merge delegation;
- HR-01 through HR-10 except where sequencing is explicitly refined here.

## 6. Repository and asset effect

- Keep protected Git/PR/CI/security controls.
- Keep the current technology stack and Modular Monolith.
- Freeze V0001–V0010 byte-for-byte; future schema changes start at V0011.
- Keep and extend Organization/Account/Store/Warehouse, Service Account/Scope,
  Credential metadata, Capability/Endpoint Registry, Feature Flags and audit.
- Adapt the bounded WP-P0-003 acquisition/Raw authority into the V1 Shared Spine.
- Preserve all prior Design and Evidence files as historical provenance.
- Keep HealthShell as a system-health surface; it is not the product home page.

The exact classification is recorded in
`docs/00-governance/ASSET_DISPOSITION_LEDGER.md`.

## 7. Active post-merge state

After this reset PR is merged, the repository moves to:

```text
lifecycle_state: EXECUTING_V1
active_delivery_slice: SLICE-V1-001
active_gate: SLICE_CONTRACT_APPROVED
authorization: FULL_SCOPE_IMPLEMENTATION
next_actor: CLAUDE_FABLE_5
next_action: DETAILED_DESIGN_AND_INITIAL_FULL_IMPLEMENTATION
production_write: DISABLED_PENDING_CAPABILITY_ENABLEMENT_GATE
```

The active Slice Contract is
`docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md`.

## 8. Data migration and compatibility

This reset introduces no database migration and no product runtime behavior.
V0001–V0010, backend/frontend product source, infrastructure runtime files and
existing evidence must remain byte-identical. The new governance model must be
implemented by forward-compatible validator/test changes only.

## 9. Security, privacy and production impact

- No Secret, Credential, production payload or Buyer PII is introduced.
- No external Marketplace, Yandex or AI provider is called.
- No human authentication runtime is enabled by this governance PR.
- No production platform write is enabled.
- The Public repository must remain free of confidential business data and must
  become Private before such data is committed or real production go-live occurs.

## 10. Testing and evidence

The reset PR must prove:

1. exact required Base identity;
2. byte-preservation of Baseline v1.0, Naming Baseline, V0001–V0010 and existing
   WP evidence;
3. one canonical current-state source with the new Slice model;
4. supersession of old active decisions without deleting provenance;
5. validator rejection of old/parallel active states, mandatory-every-task Design
   Gate, missing Slice Contract or enabled production write;
6. governance, production-readiness and validator-unit-test success;
7. no product-code, migration, Secret/PII or provider-call change.

## 11. Rollback

Before merge, close the Draft PR and delete its branch. After merge, revert the
single reset squash commit through the protected PR Gate. Because no runtime or
migration change occurs, rollback restores only governance authority. Any product
implementation started from the new contract must stop before such a rollback.

## 12. Cost and operational impact

The reset itself has no infrastructure or Marketplace cost. It reduces repeated
Design/Review handoffs, concentrates evidence at real risk Gates and allows each
Vibe Coding cycle to deliver a production vertical slice. Production provider and
model costs remain subject to later verified configuration and budget controls.

## 13. Owner authority and ratification

The Human Owner explicitly directed the Controller to reset any existing plan that
conflicts with the product goal, accepted the major V1 boundaries recorded in
`OWNER_DECISIONS_V1.md`, and requested this Controller package. Controller-resolved
implementation choices listed separately in that file become Owner-ratified when
the Human Owner authorizes the exact DR-0003 PR merge.

## 14. Controller recommendation and final status

```text
APPROVE_RESET_PACKAGE_FOR_CODEX_GOVERNANCE_EXECUTION
```

The Decision becomes repository-effective only after an independent Controller
reviews the actual Draft PR, all required checks pass, and the Human Owner
separately authorizes the protected merge.
