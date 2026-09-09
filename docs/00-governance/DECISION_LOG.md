# Decision Log

| ID | Date | Status | Decision | Source / Rationale |
| --- | --- | --- | --- | --- |
| D-01 | 2026-08-06 | SUPERSEDED | Dual-platform architecture; Ozon end-to-end first; WB Read Integration in parallel. | Superseded in rollout sequencing by D-19 / DR-0003; dual-platform architecture remains. |
| D-02 | 2026-08-06 | SUPERSEDED | No unattended platform write in the first version. | Superseded by D-19: low-risk Policy-authorized execution is allowed; high-risk actions remain Approval-bound. |
| D-03 | 2026-08-06 | ACCEPTED | Modular Monolith + PostgreSQL Worker. | Baseline v1.0; retained by DR-0003. |
| D-04 | 2026-08-06 | ACCEPTED | Raw, Inventory Ledger and Financial Ledger are immutable. | Baseline v1.0; retained by DR-0003. |
| D-05 | 2026-08-06 | ACCEPTED | Variant / Color / Size / Purchase Batch are required granularity. | Baseline v1.0; retained by DR-0003. |
| D-06 | 2026-08-06 | ACCEPTED | Third-party competitor data is trend-only. | Baseline v1.0; retained by DR-0003. |
| D-07 | 2026-08-06 | ACCEPTED | AI recommends only and holds no platform write credentials. | Refined by D-20: AI is core analysis/recommendation but never authorization authority. |
| D-08 | 2026-08-06 | ACCEPTED | Official APIs are the only permitted programmatic Marketplace integration. | Baseline v1.0; retained by DR-0003. |
| D-09 | 2026-08-06 | ACCEPTED | Metrics and Mapping are versioned. | Baseline v1.0; retained by DR-0003. |
| D-10 | 2026-08-06 | SUPERSEDED | Automation cannot expand before the relevant Phase Gate passes. | Superseded by D-22/D-24: Slice and Capability-specific Gates. |
| D-11 | 2026-08-07 | SUPERSEDED | Use a private monorepo named `marketops-platform`. | Superseded before acceptance by D-15. |
| D-12 | 2026-08-07 | ACCEPTED | GPT is Controller; Claude is Designer/Maker; CI is evidence; Human Owner performs final merge. | Retained and refined by D-22; Design and implementation are continuous by default. |
| D-13 | 2026-08-07 | PROPOSED | Use GitHub Issues/PRs/Rulesets as the execution ledger for Work Packages and code changes. | Refined to Delivery Slice / implementation tranche terminology by D-22. |
| D-14 | 2026-08-07 | PROPOSED | No long-lived `develop` branch during initial individual development; use short-lived branches into protected `main`. | Still preferred; active Slice may use one focused Draft PR unless its Contract defines bounded tranches. |
| D-15 | 2026-08-12 | ACCEPTED | Public repository during pre-production; convert to Private before real production go-live or confidential business material and revalidate controls. | Human Owner instruction. |
| D-16 | 2026-08-12 | ACCEPTED | Owner Git Workflow Guidance Mode is required until explicit Human Owner disablement. | Human Owner instruction. |
| D-17 | 2026-08-12 | ACCEPTED | Codex may mechanically execute gated PR Ready/merge while delegation is active; no self-approval or production/business authority. | DR-0001; retained. |
| D-18 | 2026-08-26 | ACCEPTED | Reset the V1 product and delivery baseline; product outcome outranks the existing Phase/WP/Gate arrangement. | DR-0003 and explicit Human Owner direction. |
| D-19 | 2026-08-26 | ACCEPTED | V1 uses all-domain decision support plus selective official-platform execution on both Ozon and Wildberries; `PRICE_CHANGE` is the first target; low-risk Policy-authorized execution is allowed. | Owner decisions OD-V1-002/003/004 and Controller choices CD-V1-002/003. |
| D-20 | 2026-08-26 | ACCEPTED | Deterministic Truth, AI Intelligence: AI deeply analyzes and recommends using approved external models, but official facts and execution authority remain deterministic. | OD-V1-007/008/016 and CD-V1-001. |
| D-21 | 2026-08-26 | ACCEPTED | V1 is a single-entity internal platform covering Ozon/WB FBO/FBS and internal COGS, stock and finance via manual plus Excel/CSV intake; Contribution Profit is the primary operating measure. | OD-V1-005/006/017/018/019/020. |
| D-22 | 2026-08-26 | ACCEPTED | Production Delivery Slice + Shared Spine is the primary delivery model; default workflow is Contract-governed Claude Design+Implementation followed by GPT Deep Review, Codex full rework and GPT Final Gate. | OD-V1-022 and CD-V1-010/011; ADR-0005/0006. |
| D-23 | 2026-08-26 | ACCEPTED | Yandex Cloud `ru-central1` is the V1 primary infrastructure; human auth uses external OIDC/MFA with Yandex Identity Hub as default; provider boundaries remain replaceable; Buyer PII stays out of AI/general Mart. | OD-V1-011/014/015/016 and CD-V1-007; ADR-0007. |
| D-24 | 2026-08-26 | ACCEPTED | A production-grade Slice may enter bounded production before V1 is complete; write Capability enablement uses a Pilot Cohort and its own Gate; V1 completion is capability-based, not uplift-proof-based. | OD-V1-010/023 and CD-V1-004/006. |
| D-25 | 2026-08-26 | ACCEPTED | DR-0004 plus exact Owner-accepted DR-0004-AMENDMENT-001 is the engineering execution and closure protocol; frozen proposal-status fields are provenance only and repository effect requires the accepted result on protected main. No V1 Product scope change and no SLICE-V1-001 scope change. | DR-0004; DR-0004-AMENDMENT-001; durable Human Owner acceptance evidence. |
| D-26 | 2026-09-04 | ACCEPTED | SLICE-V1-003 Advertising & Traffic Efficiency is the active Slice. Its single controlled-write Capability is `AD_BID_CHANGE`; Budget, Campaign pause/resume, bidding strategy/mode, structure, creative and portfolio actions stay manual-Shadow or future scope. The initial Ordinary nonzero envelope is zero, so every nonzero bid Command is Material and needs Human Owner final per-command approval, and Standing Policy automation is disabled. Both platforms receive the full governed Manual Shadow; every unverified Provider write path stays structurally unreachable. | Exact accepted SLICE-V1-003 Contract SHA-256 `1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c`; OD-S3-001..047; durable Human Owner acceptance evidence. |
| D-27 | 2026-09-09 | ACCEPTED | SLICE-V1-004 Promotion & Listing Conversion is the active Slice. Its single new controlled-write Capability is `LISTING_DESCRIPTION_CHANGE`, limited to the exact Russian Description attribute; promotion, price, media, title, attribute and every other content write stay governed-manual or out of scope. The 85 Owner decisions, three local substitutions (DELTA-01/02/03 not required) and Q085-B are fixed. Owner acceptance grants Contract §15 Level 1 full local Detailed Design, Full-Scope Implementation, Tests, canonical docs and local Git checkpoint authority to Claude; no Level 2 environment, remote push/PR/merge, production deployment/migration, real Provider/account call, Gate EV, Gate E or real business side effect is authorized, and every new platform write is disabled by default. | Exact accepted SLICE-V1-004 Contract SHA-256 `5a1761ad614426ad3cba9594f481e293b584d69e96c6d893cf502a5062cfc983`; bound annex SHA-256 `c77089fc78183d6289ed0023d4d0dbee915f61e8d917a7f49ba8564b0dc2a48d`; [Owner acceptance receipt](../08-handoffs/OWNER-SLICE-V1-004-CONTRACT-ACCEPTANCE-EVIDENCE.md) and [statement](../08-handoffs/OWNER-SLICE-V1-004-CONTRACT-ACCEPTANCE-STATEMENT.txt). |

## Change rule

An `ACCEPTED` decision changes only through a Decision Request that records
reason, affected contracts/modules, migration and compatibility impact, security,
tests, rollback and authority. Normal engineering choices inside an approved
Slice Contract do not require an Owner Decision or ADR unless they trigger the
Conditional Design Gate.

## Execution evidence — 2026-09-05 Codex R1

This records execution authority without changing any product decision: Human
Owner's `OWNER_CODEX_SLICE_V1_003_ROOT_CAUSE_REWORK_R1` authorizes one continuous
root-cause cycle for the immutable accepted SLICE-V1-003 Contract and the 22-item
Frozen Finding Set. [Exact authorization evidence](../08-handoffs/OWNER-SLICE-V1-003-CODEX-REWORK-AUTHORIZATION-EVIDENCE.md)
and [takeover receipt](../07-phase-evidence/SLICE-V1-003/rework-r1/TAKEOVER_RECEIPT.md)
retain the original Base, Head, tree and SHA identities. Append-only transport on
the named branch, one Draft PR and CI are authorized. Ready, merge, force-push,
real Provider or shared/production access and enablement remain unauthorized.
This entry makes no engineering-closure or release claim.

## SLICE-V1-003 R1 engineering closure record — no new Owner Decision

Measured `32b307c` engineering rework and complete relevant verification are
recorded in the [current handoff](../07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/CURRENT-ENGINEERING-HANDOFF.md).
All 22 original Findings and CV-A..E have current engineering evidence; independent
Controller review of the final containing Head remains pending. Accepted Contract,
47 Owner Decisions, Frozen Finding Set and 24 deferred release obligations are
unchanged. This record grants no Ready, merge, Gate EV or production authority.

## Execution evidence — 2026-09-09 Claude SLICE-V1-004 Level 1 local checkpoint

This records execution authority and its consumption without adding a product
decision beyond D-27: the Human Owner accepted the exact SLICE-V1-004 Contract
and bound annex on the accepted source base `0f26d0ed387fd0e20c2137b11760ae0bb0f3e5bd`
(tree `9d65c590b4c6a5e08ea2692d5d8f7a3b9645f400`) and granted Contract §15
Level 1 local authority only. Claude, as Maker, produced the detailed design,
backend, forward migrations V0074–V0079, Console, tests, canonical documents and
one local Git checkpoint on branch `claude/slice-v1-004-local-implementation-pmrr80`,
recorded in the [Level 1 handoff](../07-phase-evidence/SLICE-V1-004/controller-handoff.md).
No remote publication, Draft PR, Level 2 environment, real Provider call, Gate EV,
Gate E or production enablement was used or is implied. Independent Controller
Deep Review of the exact local checkpoint is the next step; this entry grants no
Ready, merge, Pilot or production authority and `production_write_enabled`
remains `false`.
