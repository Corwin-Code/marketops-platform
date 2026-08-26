# Asset Disposition Ledger — V1 Baseline Reset

```yaml
reviewed_base: 52a657f7f6358f43246e03457ba2d48ef658986a
decision_request: DR-0003
principle: PRESERVE_EVIDENCE_ADAPT_VALUE_RETIRE_WRONG_AUTHORITY
migration_policy: V0001_TO_V0010_BYTE_FROZEN
```

The reset changes active authority, not historical truth. No existing source,
Design, Migration or Evidence is deleted by DR-0003.

## Disposition vocabulary

- `KEEP`: continue as active foundation with no product-direction change.
- `KEEP_AND_EXTEND`: valid current asset that needs V1 additions.
- `ADAPT`: retain the verified core but change allocation or integrate it into a
  Slice/Shared Spine.
- `FREEZE`: immutable historical or migration asset; no in-place edit.
- `HISTORICAL_PROVENANCE`: retained for evidence but not current execution
  authority.
- `SUPERSEDE_AS_ACTIVE_PLAN`: no longer drives work; not declared technically
  wrong.
- `REPLACE_IF_REVIEW_PROVES_NECESSARY`: no sunk-cost protection.

## Repository and governance

| Asset | Disposition | Binding treatment |
| --- | --- | --- |
| Git history and protected `main` | KEEP | No repository restart. |
| PR/CI/security workflows | KEEP_AND_EXTEND | Continue deterministic evidence; update governance model only. |
| D-15/D-16/D-17 controls | KEEP | Public pre-production boundary, Owner guidance and delegated mechanical merge remain. |
| `CURRENT_STATE.md` | REPLACE | One new Slice-based live state; old state summarized as provenance. |
| Phase 0–3 roadmap | SUPERSEDE_AS_ACTIVE_PLAN | Retain in Baseline v1.0 as historical planning source. |
| `BACKLOG-PHASE-0.md` | HISTORICAL_PROVENANCE | Add an explicit supersession banner; do not delete rows. |
| WP-P0-001/002/003 records and designs | HISTORICAL_PROVENANCE | Preserve exact evidence and accepted scope claims. |
| Mandatory per-WP Design Gate | SUPERSEDE_AS_ACTIVE_PLAN | Conditional Design Gate replaces it. |
| Controller 11+1 on every micro-transition | ADAPT | Apply risk-driven review; full artifact contract at real Gates. |
| `bootstrap-manifest.json` | HISTORICAL_PROVENANCE | It is a Session-0 snapshot, never live state. |

## Architecture and runtime foundation

| Asset | Disposition | Binding treatment |
| --- | --- | --- |
| Java 21 / Spring Boot / PostgreSQL / Flyway / React | KEEP | Still matches production-speed objective. |
| Modular Monolith | KEEP | No microservice or Kubernetes expansion in V1. |
| PostgreSQL Worker/Outbox direction | KEEP_AND_EXTEND | One worker/control authority; add execution outbox by Slice. |
| V0001–V0010 | FREEZE | Future change begins at V0011; checksums preserved. |
| `organizationaccount` | KEEP_AND_EXTEND | Reuse Legal Entity, Account, Store, Warehouse and FBO/FBS associations. |
| `identityaccess` metadata | KEEP_AND_EXTEND | Add human OIDC identity linkage, business roles/scopes and step-up policy; current service-account metadata remains. |
| `marketplaceintegration` Registry/Credential metadata | KEEP_AND_EXTEND | Populate only with verified real Capability evidence. |
| `AcquisitionPort` / `AcquisitionRequest` / `AcquisitionResult` | ADAPT | Preserve no-secret/verbatim/unknown-state contracts; bind real Ozon/WB adapters after exact package-root hardening. |
| `ObjectStoragePort` | ADAPT | Implement Yandex Object Storage adapter; review current content-address/readback contract against provider semantics. |
| Call-authority SQL/JDBC path | ADAPT | Preserve verified exclusivity/fencing; integrate into real Slice worker rather than extending as a separate product stage. |
| Architecture and real-DB tests | KEEP_AND_EXTEND | Retain mutation sensitivity and add Slice/Provider/Command paths. |
| Secret guards / least-privilege DB roles | KEEP_AND_EXTEND | Integrate Lockbox/KMS/workload identity and human auth. |
| `ProductionWritePolicy` defaults | KEEP_AND_EXTEND | Global default remains disabled; add Capability/Store/SKU Policy and Kill Switch. |

## Product surface

| Asset | Disposition | Binding treatment |
| --- | --- | --- |
| React application shell | KEEP_AND_EXTEND | Becomes structured operating console. |
| `HealthShell` | KEEP | Retain as System Health/Admin surface, not product home. |
| Metadata maintenance endpoints/UI | KEEP_AND_EXTEND | Place behind real OIDC/RBAC and admin scope. |
| Product, inventory, order, return, finance, ads, workflow and AI modules | NEW | Build through Delivery Slices; do not create parallel foundations. |

## WP-P0-003 specific conclusion

PR #16 and V0007–V0010 remain verified for their bounded scope. DR-0003 does not
claim the old WP is fully implemented or complete. It changes its role from
`active project stage` to `Shared Spine provenance` and absorbs its remaining
valid obligations into Slice contracts.

Before the first real Acquisition/Object Storage adapter, the recorded exact-root
architecture correction remains mandatory:

```text
com.mimococo.marketops.marketplaceintegration
```

## No deletion or rewrite authority

DR-0003 authorizes no deletion of legacy designs/evidence, no migration rewrite,
no product-source refactor and no removal of tests. Any later replacement must be
justified by the active Slice Contract, preserve data compatibility and pass an
independent Controller review.
