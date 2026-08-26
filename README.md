# MarketOps Russia

**Russia Marketplace Operations & Decision Platform**

## Current authority

`docs/00-governance/CURRENT_STATE.md` is the only live execution-state source.
The active product contract is `docs/01-requirements/V1_PRODUCT_CONTRACT.md`; the
active delivery plan is `docs/03-work-items/V1_DELIVERY_SLICES.md`.
DR-0004 plus `EXECUTION_ENVELOPE_POLICY.md` and
`CLOSURE_SNAPSHOT_STANDARD.md` govern engineering execution and Slice closure
without changing the V1 Product or active Slice Contract.

The 2026-08-06 Baseline remains immutable source provenance and a Requirement/NFR
catalog. DR-0003 and the V1 contracts supersede only the rollout, version and
collaboration decisions explicitly listed there; they do not silently erase its
hard rules or accepted engineering evidence.

## Product mission

MarketOps Russia is a production-grade internal operations and decision platform
for one Russian operating entity and its Ozon/Wildberries Stores, Warehouses and
Users. It combines official Marketplace data with internal COGS, physical stock
and finance facts to:

```text
Find operating problems
→ explain likely causes and uncertainty
→ recommend concrete action
→ apply deterministic Policy / Guardrails
→ approve or use bounded Owner Policy
→ execute selected official-platform commands
→ verify Readback
→ follow sales and Contribution Profit outcomes
```

V1 uses all-domain decision support and selective controlled execution. Ozon and
Wildberries must each have at least one real guarded write path; `PRICE_CHANGE` is
the first target. Production writes remain disabled until their independent
Capability Gate and Human Owner enablement.

## Active delivery model

```text
V1 Product Contract
→ immutable Production Delivery Slice Contract + additive Amendments
→ Claude local Detailed Design + Full Implementation
→ exact local checkpoint
→ Codex exact remote publication to Draft PR
→ one GPT Controller Deep Review + Frozen Finding Set
→ one Codex Root-Cause Rework / Fix / Verify cycle
→ GPT Controller Final closure verification
→ protected merge / Gate EV / Gate E as applicable
→ Controller Slice Closure → Owner Formal Closure
→ mandatory Closure Snapshot → next Slice
```

The default active Slice after DR-0003 is:

```text
SLICE-V1-001 — SKU Growth & Profit Diagnostic Loop
```

Work Packages/implementation tranches remain engineering and PR-sized units. They
are not independent product stages and do not create a mandatory Design Gate.
A separate Pre-Implementation Design Gate is conditional on material Owner,
irreversible, authority, security, provider or data-loss risk.

## Preserved technical baseline

- Java 21, Spring Boot, PostgreSQL, Flyway, React and TypeScript;
- one deployable Modular Monolith with explicit module/architecture tests;
- PostgreSQL Task/Outbox workers;
- exact immutable Raw evidence in S3-compatible Object Storage;
- immutable Inventory and Financial Ledgers when those facts are implemented;
- versioned Metric, Mapping, Policy and Calculation definitions;
- official Marketplace APIs/reports only;
- deterministic facts and Guardrails; AI analyzes and recommends but is not the
  fact, approval, command or credential authority;
- forward-only database evolution; V0001–V0010 are immutable history.

## Repository reality at the reset baseline

The repository contains strong foundation, account/credential/capability metadata,
ingestion authority, Raw custody contracts, CI and architecture/database evidence.
It does not yet contain the complete V1 business modules or operating console.
The existing React application health surface remains useful but is not the V1
product UI.

## Local development

Use `docs/06-runbooks/local-development.md` and the Make targets already committed.
The common verification entry points remain:

```bash
make governance
make backend-test
make backend-verify
make frontend-check
make verify
```

Run the subset appropriate to the change and report exact results. Passing checks
are evidence, not proof of product correctness.

## Repository and data safety

- no Token, Secret, password, Cookie, private key or signed object URL in Git,
  chat, PR, Issue, logs or frontend;
- no Buyer name, phone, full address, payment data or unredacted production
  payload in this public pre-production repository;
- only synthetic or formally redacted fixtures;
- no direct push to protected `main`;
- no platform write merely because code was merged;
- repository must become Private before production go-live or earlier before
  confidential business material is committed, under D-15;
- production enablement, credential provisioning and final business authority
  remain Human Owner actions.

## Main repository map

```text
backend/marketops-server/          Spring Boot modular monolith
frontend/marketops-console/        React operations console
infra/                             local and future controlled deployment assets
docs/00-governance/                decisions, current state, agent/release control
docs/01-requirements/              immutable source Baseline + active V1 Contract
docs/02-architecture/              ADRs and Shared-Spine/AI boundaries
docs/03-work-items/                active Delivery Slices + historical WPs
docs/04-api/                       current Capability evidence register
docs/05-testing/                   test and Production Assurance contracts
docs/07-phase-evidence/            immutable/referenced Gate evidence
docs/08-handoffs/                  standalone Controller and next-action packets
```
