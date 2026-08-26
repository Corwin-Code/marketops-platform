# Source Manifest and V1 Precedence

| Repository file | Origin | Active role |
| --- | --- | --- |
| `baseline-v1.0-cn.md` | Original Development Baseline v1.0 | Foundational PRD/SRS, unchanged Requirement IDs, NFRs and hard rules; historical Phase 0–3 rollout and superseded decisions are not active authority after DR-0003 |
| `naming-baseline-cn.md` | MarketOps naming baseline | Product/repository/package naming source |
| `../00-governance/DR-0003-v1-product-delivery-baseline-reset.md` | Human Owner direction + Controller reset | Highest authority for V1 supersession and delivery model |
| `../00-governance/OWNER_DECISIONS_V1.md` | 2026-08-26 Owner discovery | Explicit and delegated V1 product decisions |
| `V1_PRODUCT_CONTRACT.md` | DR-0003 Controller contract | Active V1 product scope, non-goals and Product Complete conditions |

Checksums for the two imported source baselines remain in `SHA256SUMS.txt` and
must stay byte-identical unless a future Decision Request intentionally replaces
them.

## Conflict order

For V1 product and delivery conflicts:

1. effective DR-0003 and `OWNER_DECISIONS_V1.md`;
2. `V1_PRODUCT_CONTRACT.md`;
3. accepted newer ADRs and active Delivery Slice Contract;
4. unchanged Baseline v1.0 requirements and hard rules;
5. live source/migration/test/provider evidence;
6. Current State, Decision Log, Open Questions and Traceability.

A superseded Phase/WP allocation remains historical provenance; it does not regain
authority merely because it appears in Baseline v1.0 or an old Work Package.

The byte-preserved Baseline's historical `Critical / High` release wording does
not create a second active finding taxonomy. New Controller findings and all
current merge/release decisions use only `BLOCKER / MAJOR / MINOR /
INFORMATIONAL`; the historical wording is not assigned to new findings.
