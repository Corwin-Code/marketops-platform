# Decision Log

| ID | Date | Status | Decision | Source / Rationale |
| --- | --- | --- | --- | --- |
| D-01 | 2026-08-06 | ACCEPTED | Dual-platform architecture; Ozon end-to-end first; WB Read Integration in parallel. | Baseline v1.0 |
| D-02 | 2026-08-06 | ACCEPTED | No unattended platform write in the first version. | Baseline v1.0 |
| D-03 | 2026-08-06 | ACCEPTED | Modular Monolith + PostgreSQL Worker. | Baseline v1.0 |
| D-04 | 2026-08-06 | ACCEPTED | Raw, Inventory Ledger and Financial Ledger are immutable. | Baseline v1.0 |
| D-05 | 2026-08-06 | ACCEPTED | Variant / Color / Size / Purchase Batch are required granularity. | Baseline v1.0 |
| D-06 | 2026-08-06 | ACCEPTED | Third-party competitor data is trend-only. | Baseline v1.0 |
| D-07 | 2026-08-06 | ACCEPTED | AI recommends only and holds no platform write credentials. | Baseline v1.0 |
| D-08 | 2026-08-06 | ACCEPTED | Official APIs are the only permitted programmatic integration. | Baseline v1.0 |
| D-09 | 2026-08-06 | ACCEPTED | Metrics and Mapping are versioned. | Baseline v1.0 |
| D-10 | 2026-08-06 | ACCEPTED | Automation cannot expand before the relevant Phase Gate passes. | Baseline v1.0 |
| D-11 | 2026-08-07 | SUPERSEDED | Use a private monorepo named `marketops-platform`. | Superseded before acceptance by D-15 |
| D-12 | 2026-08-07 | ACCEPTED | GPT is Controller; Claude is Designer/Maker; CI is evidence; Human Owner performs final merge. | Owner request and collaboration plan |
| D-13 | 2026-08-07 | PROPOSED | Use GitHub Issues/PRs/Rulesets as the execution ledger for Work Packages and code changes. | Needed for auditable Maker–Checker workflow |
| D-14 | 2026-08-07 | PROPOSED | No long-lived `develop` branch during initial individual development; use short-lived WP branches into protected `main`. | Reduce drift and merge complexity |
| D-15 | 2026-08-12 | ACCEPTED | Use `Corwin-Code/marketops-platform` as a Public repository during pre-production. When real production go-live is reached, or earlier before committing confidential business material, upgrade the GitHub plan as needed, change the repository to Private and revalidate all repository rules and security gates. | Human Owner instruction; temporary cost/capability tradeoff that enables Public-repository Rulesets and security controls before GitHub Pro is adopted |
| D-16 | 2026-08-12 | ACCEPTED | Enable Owner Git Workflow Guidance Mode by default at the start of every task. Each agent must explain the complete branch/commit/push/PR/CI/Controller/Owner-merge lifecycle, current position and next action. The mode remains required until the Human Owner explicitly confirms familiarity and asks to disable it. | Human Owner instruction to make the protected Git workflow understandable and repeatable without adding another GitHub approval requirement |
| D-17 | 2026-08-12 | ACCEPTED | Temporarily delegate mechanical PR Ready/merge execution to Codex while Current State marks the delegation `ACTIVE`. Codex may act only after all Ruleset/project gates and an independent Controller verdict pass; it cannot approve its own changes, bypass controls, push directly to `main`, provision credentials or authorize production/business decisions. Human Owner retains authorization and explicit revocation authority. | Explicit Human Owner instruction; bounded by DR-0001 and independently verified through the PR gate before becoming effective on `main` |

## Change rule

An `ACCEPTED` decision may be changed only by a Decision Request that records reason, affected requirements/modules, migration and compatibility impact, tests, rollback and Owner/Controller approval. No agent may silently replace it.
