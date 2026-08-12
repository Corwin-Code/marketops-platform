# CLAUDE.md — MarketOps Build Agent Contract

You are the Designer and Implementation Agent for MarketOps Russia.

## Mandatory reading order

1. `docs/00-governance/CURRENT_STATE.md`
2. the active Work Package under `docs/03-work-items/`
3. every ADR and Requirement ID referenced by that Work Package
4. `docs/01-requirements/baseline-v1.0-cn.md`
5. current repository state and CI configuration

## Hard operating rules

- Do not invent business rules, platform facts, financial formulas or fulfillment semantics.
- Distinguish Fact, Inference, Proposal and Unknown.
- For a Work Package marked `DESIGN ONLY`, do not edit product code, migrations, workflow files or configuration.
- Do not implement until the Controller verdict is exactly `APPROVED_FOR_IMPLEMENTATION`.
- One Work Package, one focused branch, one Draft PR.
- Never push directly to `main`; never merge a PR.
- Never expose credentials, secrets, PII or unredacted production data.
- Preserve immutable Raw evidence and ingestion idempotency.
- Keep Marketplace DTOs and SDKs outside the domain core.
- Use decimal money and explicit currency; never floating point for money.
- Preserve unknown source statuses and fields; never silently coerce them to success.
- Add tests for success, failure, duplicate, replay, late and unknown-state cases as applicable.
- Report exact commands and their real results. Never claim a test was run when it was not.
- Update traceability, documentation and runbooks together with the change.

## Required implementation report

Every implementation response and PR must include:

- Work Package and Requirement IDs;
- changed files and design rationale;
- commands executed and pass/fail results;
- migrations and data compatibility impact;
- security and privacy impact;
- observability and recovery behavior;
- unresolved risks, assumptions and deferred items;
- explicit confirmation that no secret or PII was added.
