# DR-0003 Governance Reset File Matrix

```yaml
document_type: governance_file_disposition
reviewed_base: 52a657f7f6358f43246e03457ba2d48ef658986a
reset_scope: GOVERNANCE_ONLY
product_source_change: NONE
migration_change: NONE
```

This matrix records the disposition of every live governance entry point reviewed
for DR-0003. `PRESERVE` means Codex must not edit it in the reset PR. `REPLACE`
means use the canonical overlay. `PATCH` means follow the exact execution spec and
preserve all unmentioned content. `ADD` is new active authority/evidence.

## `docs/00-governance`

| File | Disposition | Rationale |
| --- | --- | --- |
| `AI_OPERATING_MODEL.md` | REPLACE | Contract-governed Slice lifecycle replaces mandatory per-WP Design state machine. |
| `CHANGE_CONTROL.md` | REPLACE | Distinguish material Decision Request from implementation freedom. |
| `CHATGPT_PROJECT_INSTRUCTIONS.md` | REPLACE | Controller governs contracts and major Gates, not every micro-design transition. |
| `CLAUDE_PROJECT_INSTRUCTIONS.md` | REPLACE | Claude performs Detailed Design + Initial Full Implementation continuously. |
| `CONTROLLER_REVIEW_STANDARD.md` | REPLACE | Risk-driven assurance and major-Gate artifact contract. |
| `CONTROL_SESSION_0001.md` | PRESERVE | Immutable bootstrap-session provenance. |
| `CURRENT_STATE.md` | REPLACE | Activate V1 / SLICE-V1-001 and close old WP-P0-003 live Gate. |
| `DECISION_LOG.md` | REPLACE | Record supersession and D-18–D-24. |
| `DR-0001-temporary-codex-git-execution-delegation.md` | PRESERVE | D-17 mechanical delegation remains valid. |
| `DR-0002-split-controlled-file-import-from-wp-p0-003.md` | PRESERVE | Historical planning decision; active allocation superseded by DR-0003. |
| `GITHUB_SETUP.md` | PRESERVE | Protected PR/CI setup remains; no product sequencing authority. |
| `HANDOFF_PROTOCOL.md` | REPLACE | Slice Contract → combined implementation → Deep Review → Rework → Final Gate. |
| `OPEN_QUESTIONS.md` | REPLACE | Reclassify resolved Owner decisions and precise external evidence Gates. |
| `OWNER_GIT_WORKFLOW_GUIDE.md` | REPLACE | Preserve D-16/D-17 while changing WP/Design terminology and separating merge from enablement. |
| `PROJECT_CHARTER.md` | REPLACE | V1 mission, boundaries and delivery model. |
| `QUALITY_GATES.md` | REPLACE | Contract/Deep/Final/Enablement/Slice/V1 Gates. |
| `DR-0003-v1-product-delivery-baseline-reset.md` | ADD | Formal reset decision. |
| `OWNER_DECISIONS_V1.md` | ADD | Canonical Owner and Controller-resolved decisions. |
| `ASSET_DISPOSITION_LEDGER.md` | ADD | Keep/adapt/freeze/supersede record. |
| `GOVERNANCE_RESET_FILE_MATRIX.md` | ADD | This complete disposition map. |

## Root and GitHub contracts

| File | Disposition |
| --- | --- |
| `README.md` | REPLACE |
| `START_HERE.md` | REPLACE |
| `CONTRIBUTING.md` | REPLACE |
| `CLAUDE.md` | REPLACE |
| `AGENTS.md` | REPLACE |
| `.github/pull_request_template.md` | REPLACE |
| `.github/ISSUE_TEMPLATE/work_package.yml` | REPLACE |
| `.github/ISSUE_TEMPLATE/decision_request.yml` | REPLACE |
| `.github/ISSUE_TEMPLATE/delivery_slice.yml` | ADD |
| `.github/workflows/*.yml` | PRESERVE unless validator path inventory alone requires a mechanical change; no check weakening. |

## Requirements, architecture, work and evidence

| File / area | Disposition |
| --- | --- |
| `docs/01-requirements/baseline-v1.0-cn.md` | PRESERVE BYTE-EXACT |
| `docs/01-requirements/naming-baseline-cn.md` | PRESERVE BYTE-EXACT |
| `docs/01-requirements/SHA256SUMS.txt` | PRESERVE BYTE-EXACT |
| `docs/01-requirements/SOURCE_MANIFEST.md` | REPLACE to define active V1 overlay order without altering source hashes |
| `docs/01-requirements/traceability.csv` | PRESERVE as historic source/WP mapping |
| `docs/01-requirements/v1-traceability.csv` | ADD as active V1/Slice mapping |
| `docs/01-requirements/V1_PRODUCT_CONTRACT.md` | ADD |
| `docs/02-architecture/README.md` | REPLACE |
| `ADR-0001`, `ADR-0002` | PRESERVE |
| `ADR-0003` | PATCH status/consequence notice only; controlled-write chain remains authoritative |
| `ADR-0004` | PATCH refinement notice only; Maker–Checker independence remains authoritative |
| `ADR-0005`–`ADR-0008` | ADD |
| existing WP-P0-001/002 designs | PRESERVE BYTE-EXACT where pinned/approved |
| WP-P0-003 addendum/design evidence | PRESERVE as historical Shared-Spine provenance |
| `V1_SHARED_SPINE.md`, `V1_AI_DATA_AND_EXECUTION_BOUNDARY.md` | ADD |
| `docs/03-work-items/BACKLOG-PHASE-0.md` | PATCH supersession banner; preserve original body |
| existing WP-P0-001/002/003 records | PRESERVE; add no live authorization to them |
| `V1_DELIVERY_SLICES.md`, `SLICE-V1-001...md` | ADD |
| `docs/04-api/V1_CAPABILITY_MATRIX.md` | ADD |
| `docs/05-testing/TEST_STRATEGY.md` | PATCH V1 active-assurance notice; preserve historical test IDs |
| `docs/05-testing/V1_PRODUCTION_ASSURANCE_MATRIX.md` | ADD |
| existing `docs/07-phase-evidence/WP-P0-*` | PRESERVE BYTE-EXACT |
| `docs/07-phase-evidence/README.md` | REPLACE index/layout guidance only |
| `docs/07-phase-evidence/V1/Baseline-Reset/README.md` | ADD |
| `docs/08-handoffs/CONTROLLER-DR-0003-V1-BASELINE-RESET-REVIEW.md` | ADD standalone Controller review |
| `docs/08-handoffs/CODEX-DR-0003-GOVERNANCE-EXECUTION-PROMPT.md` | ADD standalone next-action prompt |
| `docs/08-handoffs/DR-0003-CONTROLLER-ARTIFACT-HASHES.md` | ADD exact SHA-256 binding |

## Executable governance

| File | Disposition | Constraint |
| --- | --- | --- |
| `scripts/validate_governance.py` | REWORK | Replace old active-WP/lifecycle invariants with V1/Slice contract while preserving secret/source/history checks. |
| `tests/test_validate_governance.py` | REWORK | Mutation-sensitive tests for new state, supersession, authority and preserved provenance. |
| `scripts/validate_production_readiness.py` | TARGETED REWORK | Keep production quality/security/migration pins; remove only stale active-state assumptions and register new canonical docs as appropriate. |
| `tests/test_validate_production_readiness.py` | TARGETED REWORK | Prove no weakening and V1 state compatibility. |
| `bootstrap-manifest.json` | PRESERVE | Historical bootstrap manifest, not current live-state authority. |

## Runtime/product source

All of the following are `PRESERVE / OUT OF RESET SCOPE`:

```text
backend/marketops-server/src/main/**
backend/marketops-server/src/test/**
backend/marketops-server/src/main/resources/db/migration/V0001..V0010
frontend/marketops-console/**
infra/**
fixtures/**
existing runbooks and API docs unless explicitly listed above
```

Any unexpected modification is a scope violation.
