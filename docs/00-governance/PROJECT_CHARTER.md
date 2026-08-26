# Project Charter — MarketOps Russia V1

## 1. Identity

| Field | Value |
| --- | --- |
| Formal Chinese name | 俄罗斯 Marketplace 运营与决策平台 |
| Formal English name | Russia Marketplace Operations & Decision Platform |
| Product short name | MarketOps Russia |
| Engineering short name | marketops |
| Repository | Corwin-Code/marketops-platform |
| Foundational source | Development Baseline v1.0, 2026-08-06 |
| Active reset authority | DR-0003 + `OWNER_DECISIONS_V1.md` + `V1_PRODUCT_CONTRACT.md` |
| Product version | V1 |
| Status | EXECUTING_V1 |
| Delivery model | Production Vertical Slices + Shared Spine |

## 2. Mission

Build a production-grade, auditable internal operating system for one Russian
Marketplace business. MarketOps unifies official Ozon/Wildberries facts with
internal procurement, COGS, warehouse and finance facts; exposes trustworthy
SKU-level operating truth; uses AI to discover and explain hidden patterns; and
turns approved recommendations into guarded platform actions whose Readback and
business outcomes are tracked.

The success measure is a real operating loop that helps users improve sales and
Contribution Profit—not the quantity of dashboards, roles, schemas or completed
horizontal components.

## 3. V1 product outcome

V1 must support:

- Ozon and Wildberries, including both platforms' FBO/FBS semantics;
- Product/Listing, Price, Promotion, Inventory, Advertising, Order/Fulfillment,
  Return and Finance decision data;
- internal COGS, local physical stock and necessary finance facts through
  controlled manual entry and Excel/CSV import until system APIs are known;
- deterministic versioned facts, metrics, Freshness and Confidence;
- embedded AI analysis, explanation and concrete Recommendation;
- Recommendation, Task, Commercial Policy, Guardrail and Approval;
- selected official-platform writes on both Ozon and Wildberries;
- Readback, Audit, Kill Switch, Restore/Compensate and outcome follow-up;
- secure public-network use with external OIDC/MFA and MarketOps business scopes;
- Yandex Cloud `ru-central1` as the V1 primary production environment.

## 4. Active delivery strategy

The primary delivery unit is a `Production Delivery Slice`: a complete operating
capability that can be released safely to bounded real users/data. Shared
infrastructure is built to the production depth required by the current Slice and
is reused and extended once.

The active first Slice is:

```text
SLICE-V1-001 — SKU Growth & Profit Diagnostic Loop
```

It combines broad SKU diagnosis with Recommendation/Task/Approval and the first
real dual-platform `PRICE_CHANGE → Readback` capability.

A Work Package is now an optional implementation tranche/context boundary inside
an approved Slice. It is not an independent product phase and does not create a
mandatory Design Approval on its own.

## 5. Fixed V1 boundaries

- one operating entity; not a public multi-tenant SaaS;
- Modular Monolith + PostgreSQL Worker/Outbox;
- official Marketplace APIs only;
- immutable Raw, Inventory Ledger, Financial Ledger and audit evidence;
- Variant/Color/Size/Purchase Batch operating granularity;
- deterministic facts and rules; AI is an intelligence layer, not execution
  authority or financial fact authority;
- Buyer PII excluded from AI and general Analytics/Mart by default;
- low-risk Policy-authorized semi-automation allowed; high-risk actions require
  per-action approval;
- all platform writes default disabled and open only by Capability-specific Gate;
- no Kafka, Kubernetes, microservices or multi-cloud without a demonstrated need
  and a new Decision/ADR;
- production capability completion is a release Gate; business uplift is measured
  after release rather than required to declare V1 capability complete.

## 6. Governance authorities

| Decision type | Authority |
| --- | --- |
| Product intent, commercial risk appetite, legal entity/accounts, production release and irreversible business decisions | Human Owner |
| Product/Slice Contract, architecture hard boundaries, quality/release verdict and decision interpretation | GPT Controller |
| Detailed Design and Initial Full Implementation inside an approved Slice Contract | Claude Fable 5 / Claude Code |
| Full in-scope Production Rework, Fix and Verify after GPT Deep Review | Codex |
| Deterministic build/test/migration/security evidence | CI |
| Protected merge authorization/revocation and production enablement | Human Owner |
| Mechanical Ready/merge execution after all Gates | Human Owner or active D-17 Codex delegate |

## 7. Source-of-truth order

For V1 conflicts, use:

1. DR-0003 and explicit Owner decisions in `OWNER_DECISIONS_V1.md`;
2. `V1_PRODUCT_CONTRACT.md`;
3. accepted newer ADRs and active Slice Contract;
4. unchanged hard rules/Requirement IDs in Baseline v1.0;
5. live code, migrations, tests and provider evidence;
6. `CURRENT_STATE.md`, Decision Log, Open Questions and Traceability;
7. chat history only as non-authoritative work context.

No agent may silently reconcile a conflict. It must cite the higher authority or
raise one material question.

## 8. Initial V1 success condition

`V1 Product Complete` requires all V1-required Delivery Slices to be production-
grade, integrated and operable. An individual Slice may be released earlier to a
bounded production cohort after its own Gate. A Slice release does not imply the
whole V1 is complete.
