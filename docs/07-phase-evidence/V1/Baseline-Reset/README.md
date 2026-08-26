# DR-0003 V1 Baseline Reset Evidence Index

```yaml
document_type: governance_reset_evidence_index
reviewed_repository: Corwin-Code/marketops-platform
reviewed_base: 52a657f7f6358f43246e03457ba2d48ef658986a
review_date: 2026-08-26
controller: GPT-5.6 Sol Pro
change_class: GOVERNANCE_ONLY
product_source_change: NONE
migration_change: NONE
production_enablement: NONE
```

## Evidence reviewed

- live governance and agent contracts;
- immutable source Baseline and Requirement catalog;
- ADR-0001 through ADR-0004;
- V0001–V0010 inventory and production-readiness pins;
- backend/frontend module and product-surface inventory;
- existing unit/architecture/database/governance tests;
- WP-P0-001/002/003 evidence, especially PR #16 post-merge verification;
- explicit Owner decisions recorded in `OWNER_DECISIONS_V1.md`.

## Reset artifacts

- `docs/00-governance/DR-0003-v1-product-delivery-baseline-reset.md`;
- `docs/00-governance/OWNER_DECISIONS_V1.md`;
- `docs/00-governance/ASSET_DISPOSITION_LEDGER.md`;
- `docs/00-governance/GOVERNANCE_RESET_FILE_MATRIX.md`;
- `docs/01-requirements/V1_PRODUCT_CONTRACT.md`;
- ADR-0005 through ADR-0008;
- Shared-Spine and AI boundaries;
- V1 Delivery Slices and SLICE-V1-001 Contract;
- Capability and Production Assurance matrices;
- standalone Controller review, Codex next-action prompt and
  `docs/08-handoffs/DR-0003-CONTROLLER-ARTIFACT-HASHES.md`.

## Preserved immutable provenance

- source Baseline/Naming Baseline and their checksums;
- Git history and existing `main`;
- V0001–V0010;
- WP-P0-001/002/003 records, designs and evidence;
- prior accepted DR-0001/DR-0002 as historical decisions;
- existing product source at the reset Base.

## Required execution evidence

Codex records in the reset PR:

1. required Base SHA and resulting branch/commit/PR identity;
2. package checksum verification;
3. changed-file allowlist and protected-path equality checks;
4. governance validator/test results;
5. production-readiness validator/test results;
6. `git diff --check` and source Baseline/migration/evidence byte comparison;
7. CI result on the exact PR Head/tested merge;
8. no-secret/PII/product-runtime/migration change confirmation.

This index is not an implementation or merge approval. It becomes effective only
through the protected governance PR and a fresh Controller verdict on the actual
PR Head.
