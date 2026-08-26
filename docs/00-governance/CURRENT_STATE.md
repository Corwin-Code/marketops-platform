# Current State

```yaml
as_of: 2026-08-27
project: MarketOps Russia
repository: Corwin-Code/marketops-platform
reset_effective_base: 52a657f7f6358f43246e03457ba2d48ef658986a
lifecycle_state: EXECUTING_V1
product_version: V1
delivery_model: PRODUCTION_VERTICAL_SLICES
legacy_phase_model: SUPERSEDED_AS_ACTIVE_EXECUTION_PLAN
active_delivery_slice: SLICE-V1-001
active_slice_title: SKU Growth & Profit Diagnostic Loop
active_slice_contract: docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md
active_slice_contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
active_slice_contract_authorization_condition: EXACT_HASH_INDEPENDENTLY_REVIEWED_AND_OWNER_AUTHORIZED_ON_PROTECTED_MAIN
active_gate: SLICE_CONTRACT_APPROVED
authorization: FULL_SCOPE_IMPLEMENTATION
accepted_contract_mutation: PROHIBITED_APPEND_ONLY_AMENDMENT_REQUIRED
execution_envelope: EXECUTION_ENVELOPE_V1
maker_remote_git_authority: DENIED
remote_git_publication_delegate: CODEX
deep_review_mode: ONE_SHOT_DISCOVERY_FROZEN_FINDING_SET
final_gate_mode: CLOSURE_VERIFICATION_ONLY
owner_formal_slice_closure: REQUIRED
closure_snapshot_before_next_slice: REQUIRED
dual_truth_model: NORMATIVE_AND_IMPLEMENTATION_FACT
conditional_design_gate: ENABLED
mandatory_design_gate_for_every_slice: DISABLED
next_authorized_actor: CLAUDE_FABLE_5
next_action: SLICE_V1_001_DETAILED_DESIGN_AND_INITIAL_FULL_IMPLEMENTATION
production_write_enabled: false
controlled_write_enablement: CAPABILITY_SPECIFIC_GATE_REQUIRED
bounded_real_write_verification_authorization: NONE
bounded_real_write_verification_gate: REQUIRED_BEFORE_FIRST_REAL_WRITE
ozon_price_write: DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE
wildberries_price_write: DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE
production_infrastructure: YANDEX_CLOUD_RU_CENTRAL1
human_authentication: EXTERNAL_OIDC_MFA_DEFAULT_YANDEX_IDENTITY_HUB
owner_git_workflow_guidance: REQUIRED
owner_git_workflow_guidance_exit: HUMAN_OWNER_EXPLICIT_CONFIRMATION
owner_git_execution_delegation: ACTIVE
owner_git_execution_delegate: CODEX
owner_git_execution_delegation_scope: PR_READY_AND_MERGE_AFTER_ALL_GATES
owner_git_execution_delegation_exit: HUMAN_OWNER_EXPLICIT_REVOCATION
```

## Active authority

The active product and Slice authority remains DR-0003,
`OWNER_DECISIONS_V1.md`, `V1_PRODUCT_CONTRACT.md`, ADR-0005 through ADR-0008 and
the Slice Contract named above. DR-0004, `EXECUTION_ENVELOPE_POLICY.md` and
`CLOSURE_SNAPSHOT_STANDARD.md` govern engineering execution and closure without
changing that product outcome or the active Slice Contract bytes. Claude is
authorized to perform local Detailed Design and Initial Full Implementation
continuously inside that exact Contract. A separate Design Approval is not
required unless a Conditional Design Gate trigger occurs.

`SLICE_CONTRACT_APPROVED` and `FULL_SCOPE_IMPLEMENTATION` are valid only for the
exact `active_slice_contract` path and `active_slice_contract_sha256` recorded in
the leading metadata. A byte change or identity mismatch is prohibited and makes
that authorization invalid; updating the original hash or re-reviewing edited
original bytes cannot restore it. Restore the accepted bytes. Any normative
change requires a separately accepted additive Amendment.

The accepted original Contract is permanently byte-frozen. A normative change
requires a separately identified, exact, Owner-accepted additive Amendment; a
new hash for edited original bytes is not a valid update. Non-expansive
Controller interpretation may clarify existing text but cannot accumulate into
hidden scope, authority or acceptance expansion.

Claude's ordinary authority ends at an exact local commit/tree and evidence
handoff. It excludes push, remote branch/tag mutation and PR create/update.
Remote publication is a separately authorized Codex/Owner-delegate transport
operation that must preserve the exact checkpoint/tree and stop when exact
transport cannot be proven.

Formal Controller Deep Review is one-shot discovery/falsification across the
complete transitive Slice surface and freezes one SHA-256-bound Finding Set.
Codex then receives the original Contract, accepted Amendments and Frozen Finding
Set as one rework contract. Final Gate is closure verification, not a second
open-ended discovery review; reopening requires materially new, previously
unavailable severe evidence. A miss based on evidence already available to Deep
Review is `CONTROLLER_REVIEW_COVERAGE_FAILURE`.

After Controller Slice Closure, Human Owner Formal Closure verifies identities
and Owner-only conditions rather than repeating engineering review. The exact
Owner-accepted Closure Snapshot is required before the next Slice starts.

Conflicts use the dual truth model. Normative Truth is Owner Decision → original
Contract plus accepted Amendments → ADR/canonical normative docs. Implementation
Fact is runtime/DB/external evidence → migration/schema → exact source/Git →
tests/snapshots. Classify any conflict as `IMPLEMENTATION_DEFECT`,
`CONTRACT_DEFECT` or `DOCUMENTATION_DRIFT`; no layer silently overwrites another.

This implementation authorization does **not** authorize:

- production platform writes or Capability enablement;
- bounded real-write verification without an exact Gate-EV verdict and explicit
  Human Owner authorization;
- real credentials in Git, chat, logs, fixtures or ordinary configuration;
- Buyer PII in AI or general Analytics/Mart;
- destructive migration or changes to V0001–V0010;
- a second ingestion, metric, policy, command or audit authority;
- a product-scope expansion outside SLICE-V1-001;
- merge or production release without later independent Gates.

The Ozon/WB price-write fields remain disabled production/scheduling flags. A
future Gate-EV envelope is represented as a separate, exact, expiring authority;
it cannot be implemented by changing either default flag to `ENABLED`, and it is
consumed without creating recurring execution authority.

## Preserved historical provenance

### WP-P0-001

Repository/CI/modular-monolith foundation remains `VERIFIED` and closed to its
approved scope. Canonical design and evidence remain under:

```text
docs/02-architecture/designs/WP-P0-001-foundation-design.md
docs/07-phase-evidence/WP-P0-001/
```

### WP-P0-002

Organization, Store, Warehouse, Service Account, Credential metadata, Capability
Registry, Feature Flag and audit foundation remains `VERIFIED` and closed to its
approved scope. Canonical design/evidence remain under:

```text
docs/02-architecture/designs/WP-P0-002-organization-store-warehouse-credential-metadata-design.md
docs/07-phase-evidence/WP-P0-002/
```

### WP-P0-003 bounded implementation-backed validation

PR #16 remains verified for its bounded executable-design-validation scope:

```text
authorized_head: 27b457bff4a0ed11308efa080993ee6793cae090
authorized_tree: 52704ed54b2499898609a0bdd4041a5c88892fd3
squash_commit: ce054a0c115788c7e7a174daa978af116b100a83
bounded_result: VERIFIED
full_legacy_wp_completion: NOT_CLAIMED
new_role: SHARED_SPINE_PROVENANCE
```

Exact evidence remains at
`docs/07-phase-evidence/WP-P0-003/post-merge-execution-verification.md`.
The exact-root architecture correction remains mandatory before the first real
Acquisition/Object Storage adapter:

```text
com.mimococo.marketops.marketplaceintegration
```

### Migration history

V0001–V0010 remain immutable and byte-pinned. All new schema work begins at
V0011. DR-0003 itself contains no migration.

## Completed by the reset

- V1 product intent and execution boundaries are recorded in Git rather than
  chat-only state.
- D-01, D-02 and D-10 are superseded where their rollout sequencing conflicts
  with V1.
- Phase 0–3 and the old WP backlog are historical provenance, not active execution
  authority.
- Production Vertical Slices and the Shared Spine are the active delivery model.
- The default GPT → Claude → GPT → Codex → GPT workflow is Contract-governed.
- Owner-level decisions required to start Slice 1 are closed or assigned to a
  precise external evidence/production enablement Gate.

## Not completed and not claimed

- No real Ozon/WB client, credential retrieval, platform call or production write
  is enabled by the reset.
- Human OIDC authentication is not yet implemented.
- Yandex infrastructure is selected but not provisioned or accepted.
- Product/Listing identity, cross-domain operating facts, AI analysis, workflow,
  price command, UI and production deployment remain Slice 1 implementation.
- V1 and SLICE-V1-001 are not production-ready until their future Gates pass.
- Business sales/profit uplift is not claimed.

## Current external evidence and configuration gates

`OPEN_QUESTIONS.md` is the canonical register. No open item blocks Slice 1
implementation start. Platform capability evidence, actual internal file samples,
AI provider eligibility, legal confirmation, commercial thresholds and Pilot
Cohort block only their named integration or production-enablement boundary.

## Next authorized action

```text
CLAUDE_FABLE_5 executes SLICE-V1-001 local Detailed Design and Initial Full
Implementation under the approved Slice Contract and Execution Envelope. Claude may change in-scope
backend, frontend, V0011+ migrations, tests, infrastructure-as-code, docs and
runbooks and create local Git checkpoints. It may not push, mutate a remote
branch/tag or create/update a PR under ordinary authority. It must keep production
writes disabled and stop only on a material Conditional Design Gate trigger,
Execution Envelope expansion or proven external-capability blocker.
```
