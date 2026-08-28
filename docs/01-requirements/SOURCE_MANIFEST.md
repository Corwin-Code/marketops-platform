# Source Manifest and V1 Precedence

| Repository file | Origin | Active role |
| --- | --- | --- |
| `baseline-v1.0-cn.md` | Original Development Baseline v1.0 | Foundational PRD/SRS, unchanged Requirement IDs, NFRs and hard rules; historical Phase 0–3 rollout and superseded decisions are not active authority after DR-0003 |
| `naming-baseline-cn.md` | MarketOps naming baseline | Product/repository/package naming source |
| `../00-governance/DR-0003-v1-product-delivery-baseline-reset.md` | Human Owner direction + Controller reset | Highest authority for V1 supersession and delivery model |
| `../00-governance/DR-0004-engineering-execution-closure-protocol-alignment.md` | Exact Human Owner-accepted governance Contract | Engineering execution, review and Slice-closure protocol; no V1 or Slice scope change |
| `../00-governance/DR-0004-AMENDMENT-001-activation-and-owner-acceptance-provenance.md` | Exact Human Owner-accepted additive Amendment | Classifies frozen proposal-status metadata as provenance and defines protected-main activation semantics; no V1 or Slice scope change |
| `../00-governance/EXECUTION_ENVELOPE_POLICY.md` | Exact DR-0004 normative policy | Level-1/2/3 engineering authority and remote-publication boundary |
| `../00-governance/CLOSURE_SNAPSHOT_STANDARD.md` | Exact DR-0004 normative standard | Owner Formal Closure and mandatory cross-window Slice Snapshot |
| `../08-handoffs/OWNER-DR-0004-ACCEPTANCE-EVIDENCE.md` | Immutable Human Owner acceptance provenance | Binds the exact original DR-0004 artifacts and Amendment-001 without creating product, production or merge authority |
| `../08-handoffs/CONTROLLER-PR19-DR0004-DEEP-REVIEW-R1.md` | Independent Controller review evidence | Binds reviewed Base/Head/tree/tested merge and the R1 verdict |
| `../08-handoffs/FROZEN-FINDING-SET-DR0004-PR19-R1.md` | Frozen formal discovery result | Complete F01–F03 Finding Set for the reviewed PR #19 identity |
| `../08-handoffs/CONTROLLER-CODEX-REWORK-AUTHORIZATION-PR19-R1.md` | Bounded Controller rework authority | Authorizes one root-cause rework cycle on the same Draft PR; no merge or production authority |
| `../00-governance/OWNER_DECISIONS_V1.md` | 2026-08-26 Owner discovery | Explicit and delegated V1 product decisions |
| `V1_PRODUCT_CONTRACT.md` | DR-0003 Controller contract | Active V1 product scope, non-goals and Product Complete conditions |
| `../03-work-items/SLICE-V1-001-AMENDMENT-001-YANDEX-MANAGED-PG-BOOTSTRAP.md` | Exact Human Owner-accepted additive Slice Amendment | Yandex PG17 managed extension/bootstrap compatibility; unchanged original Contract and V0001–V0010 |
| `../08-handoffs/OWNER-SLICE-V1-001-AMENDMENT-001-ACCEPTANCE-EVIDENCE.md` | Explicit Human Owner message on 2026-08-28 | Authorizes Amendment implementation in Draft PR #20; no deployment, provider calls, Ready or merge |

Checksums for the two imported source baselines remain in `SHA256SUMS.txt` and
must stay byte-identical unless a future Decision Request intentionally replaces
them.

The frozen original DR-0004 artifacts' `PROPOSED_PENDING_EXACT_OWNER_ACCEPTANCE`
and `PROPOSED_BY_DR_0004` status tokens are proposal-time provenance only. They
are not the live acceptance/effect state. Live DR-0004 repository effect is
determined by the immutable original artifacts together with exact accepted
`DR-0004-AMENDMENT-001`, the durable Owner acceptance evidence and
`CURRENT_STATE.md`, and becomes `ACTIVE_ON_PROTECTED_MAIN` only when that exact
accepted result is on protected `main`. A proposal branch is not active
repository authority.

## DR-0004 effective-source binding

```text
dcc073bb8f6593bd24b4a74a96f06d0c45ece2f1c192615deb7301cbb850da9a  ../00-governance/DR-0004-engineering-execution-closure-protocol-alignment.md
0dd73e8ed3e29a9903c991d5e723f40eb6a42d63841e6e952bf8f1292194f203  ../00-governance/EXECUTION_ENVELOPE_POLICY.md
487379bc00badc37cd81bd82dec31621c25fbad2d56a7acd6f40cf2244d7ece1  ../00-governance/CLOSURE_SNAPSHOT_STANDARD.md
cea88c6b72b480ad7f39a45390e457de316b6be6511dad45a5d0f6c63716779c  ../00-governance/DR-0004-AMENDMENT-001-activation-and-owner-acceptance-provenance.md
f83349ea537fd48575787dccfaa624ec39c5079181ccf0da6c69e996768bda88  ../08-handoffs/OWNER-DR-0004-ACCEPTANCE-EVIDENCE.md
```

## SLICE-V1-001 accepted additive authority

```text
8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d  ../03-work-items/SLICE-V1-001-AMENDMENT-001-YANDEX-MANAGED-PG-BOOTSTRAP.md
e8fc208a4fcd9270b9187b65aa1618ecf6179166a3a44b4a37213bf067a91ee8  ../08-handoffs/OWNER-SLICE-V1-001-AMENDMENT-001-ACCEPTANCE-EVIDENCE.md
```

The Amendment's original proposal-status metadata remains byte-frozen. The
separate acceptance evidence establishes current implementation authority in the
existing rework; it does not claim protected-main activation or Slice closure.

## Dual truth and conflict order

Normative Truth is ordered as:

1. effective Owner Decisions and Decision Requests, including DR-0003 for product
   scope and immutable DR-0004 plus accepted Amendment-001 and durable acceptance
   provenance for engineering execution/closure;
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
