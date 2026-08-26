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

## Change rule

An `ACCEPTED` decision changes only through a Decision Request that records
reason, affected contracts/modules, migration and compatibility impact, security,
tests, rollback and authority. Normal engineering choices inside an approved
Slice Contract do not require an Owner Decision or ADR unless they trigger the
Conditional Design Gate.
