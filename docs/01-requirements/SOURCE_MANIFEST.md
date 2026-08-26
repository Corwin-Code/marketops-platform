# Source Manifest and V1 Precedence

| Repository file | Origin | Active role |
| --- | --- | --- |
| `baseline-v1.0-cn.md` | Original Development Baseline v1.0 | Foundational PRD/SRS, unchanged Requirement IDs, NFRs and hard rules; historical Phase 0–3 rollout and superseded decisions are not active authority after DR-0003 |
| `naming-baseline-cn.md` | MarketOps naming baseline | Product/repository/package naming source |
| `../00-governance/DR-0003-v1-product-delivery-baseline-reset.md` | Human Owner direction + Controller reset | Highest authority for V1 supersession and delivery model |
| `../00-governance/DR-0004-engineering-execution-closure-protocol-alignment.md` | Exact Human Owner-accepted governance Contract | Engineering execution, review and Slice-closure protocol; no V1 or Slice scope change |
| `../00-governance/EXECUTION_ENVELOPE_POLICY.md` | Exact DR-0004 normative policy | Level-1/2/3 engineering authority and remote-publication boundary |
| `../00-governance/CLOSURE_SNAPSHOT_STANDARD.md` | Exact DR-0004 normative standard | Owner Formal Closure and mandatory cross-window Slice Snapshot |
| `../00-governance/OWNER_DECISIONS_V1.md` | 2026-08-26 Owner discovery | Explicit and delegated V1 product decisions |
| `V1_PRODUCT_CONTRACT.md` | DR-0003 Controller contract | Active V1 product scope, non-goals and Product Complete conditions |

Checksums for the two imported source baselines remain in `SHA256SUMS.txt` and
must stay byte-identical unless a future Decision Request intentionally replaces
them.

## Dual truth and conflict order

Normative Truth is ordered as:

1. effective Owner Decisions and Decision Requests, including DR-0003 for product
   scope and DR-0004 for engineering execution/closure;
2. the immutable original Product/Slice Contract plus separately identified,
   exact, Owner-accepted additive Amendments;
3. accepted ADRs and canonical normative governance documents;
4. unchanged Baseline v1.0 requirements and hard rules.

Implementation Fact is ordered as:

1. runtime, database and current external evidence;
2. migration and schema identity;
3. exact source/Git identity;
4. executable tests and immutable snapshots.

Current State, Decision Log, Open Questions and Traceability index those truths;
they do not silently replace them. A conflict is classified explicitly as
`IMPLEMENTATION_DEFECT`, `CONTRACT_DEFECT` or `DOCUMENTATION_DRIFT` and resolved
at the responsible layer. A non-expansive Controller interpretation cannot
accumulate into hidden normative expansion; changed normative meaning requires
an accepted Amendment.

A superseded Phase/WP allocation remains historical provenance; it does not regain
authority merely because it appears in Baseline v1.0 or an old Work Package.

The byte-preserved Baseline's historical `Critical / High` release wording does
not create a second active finding taxonomy. New Controller findings and all
current merge/release decisions use only `BLOCKER / MAJOR / MINOR /
INFORMATIONAL`; the historical wording is not assigned to new findings.
