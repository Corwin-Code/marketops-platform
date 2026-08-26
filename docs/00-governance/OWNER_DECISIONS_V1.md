# Owner Decisions — MarketOps V1

```yaml
document_type: v1_owner_decisions
source_session: Human Owner + GPT Controller discovery
session_date: 2026-08-26
effective_via: DR-0003
repository_effect: EFFECTIVE_WHEN_PRESENT_ON_PROTECTED_MAIN
effective_condition: PROTECTED_MAIN_MERGE_AFTER_INDEPENDENT_CONTROLLER_REVIEW_AND_OWNER_AUTHORIZATION
```

`EFFECTIVE_WHEN_PRESENT_ON_PROTECTED_MAIN` does not make these decisions active
from a proposal branch. It records durable repository semantics: the decisions
become effective only through the independent Controller and Human Owner merge
authority named by `effective_condition`.

This document distinguishes explicit Human Owner decisions from implementation
choices the Owner delegated to the Controller for best judgment. Both classes are
binding after the Human Owner authorizes the exact DR-0003 merge. External facts
remain evidence requirements rather than guessed decisions.

## A. Explicit Human Owner decisions

| ID | Decision |
| --- | --- |
| OD-V1-001 | Product success is an operating capability that helps real operators improve sales and profit; role count, page count and feature count are not the primary acceptance measure. |
| OD-V1-002 | V1 uses all-domain decision support with selective execution rather than either read-only BI or immediate automation of every action. |
| OD-V1-003 | Ozon and Wildberries must each expose at least one real `Approval/Policy → Write → Readback` capability in V1. |
| OD-V1-004 | Low-risk actions with explicit deterministic Guardrails may execute under an Owner pre-authorized Policy; high-risk actions require per-action Approval. |
| OD-V1-005 | V1 includes internal COGS/purchase cost, local physical inventory and necessary finance/accounting facts. |
| OD-V1-006 | Internal facts support controlled manual entry as fallback and productized Excel/CSV import for batch intake. |
| OD-V1-007 | External cloud LLM APIs may receive field-allowlisted, Secret/PII-redacted operating data to obtain strong model capability. |
| OD-V1-008 | V1 AI must perform deep analysis, problem discovery, explanation and concrete recommendation; execution authority remains deterministic Policy/Guardrail/Approval. Autonomous AI operating authority is deferred. |
| OD-V1-009 | The business objective is multi-objective: default Contribution Profit priority, with Owner-configurable Hero/Growth/Mature/Repair/Exit strategies. |
| OD-V1-010 | V1 is complete when production-grade product capability passes; demonstrated business uplift is measured after release and is not a blocking completion Gate. |
| OD-V1-011 | Yandex Cloud `ru-central1` is the V1 primary production infrastructure. V1 does not build multi-cloud, but provider boundaries remain replaceable. |
| OD-V1-012 | The Owner controls at least one Ozon Seller Account and one Wildberries Seller Account and can create/manage their API Credentials. |
| OD-V1-013 | Development and acceptance may use a strictly allowlisted set of real Store/SKU/Campaign scope for low-risk `Write → Readback → Restore/Compensate` verification; Sandbox is not mandatory. |
| OD-V1-014 | Owner, Operations and Finance users require secure public-network remote login. |
| OD-V1-015 | No existing company-wide identity directory or SSO is available. |
| OD-V1-016 | Buyer name, phone and full address are excluded from AI and general Analytics/Mart by default; a future PII use case requires a separate Owner/Legal/Security Gate. |
| OD-V1-017 | V1 serves one Russian operating entity with multiple Stores, Warehouses and Users; it is not a multi-tenant public SaaS. |
| OD-V1-018 | V1 must model both Ozon and Wildberries FBO/FBS semantics. |
| OD-V1-019 | Contribution Profit is the primary daily operating profit measure; company-wide final net-profit overhead allocation is not the SKU decision truth. |
| OD-V1-020 | Canonical profit uses deterministic confirmed inputs. Estimated Operational Contribution Profit may support analysis and low-risk recommendations, but high-risk writes require stronger data completeness and Confidence. |
| OD-V1-021 | The main UI remains a structured operating system; AI is embedded deeply as an enhancement rather than replacing the product with a chat-only interface. |
| OD-V1-022 | Production-grade end-to-end business capability slices are the preferred Vibe Coding execution and business communication unit. |
| OD-V1-023 | There is no fixed launch date; advance at the fastest reasonable speed consistent with production-grade quality. |
| OD-V1-024 | Existing implementation may be retained, adapted, superseded or replaced solely according to fit with the confirmed product goal; sunk work is not a reason to preserve a wrong route. |

## B. Controller-resolved choices under delegated judgment

| ID | Decision | Rationale / evidence boundary |
| --- | --- | --- |
| CD-V1-001 | Use `Deterministic Truth, AI Intelligence`: official metrics/facts are versioned and reproducible; AI performs inference and recommendation. | Prevents a probabilistic second fact source while allowing deep AI analysis. |
| CD-V1-002 | Use one Unified Business Capability Model and platform-specific Ozon/WB Adapter semantics. Do not require identical endpoint/state behavior. | Preserves product consistency without inventing false platform symmetry. |
| CD-V1-003 | Select `PRICE_CHANGE` as the first controlled dual-platform write capability. | High business relevance, clear deterministic Guardrails, bounded change and explicit Readback/restore path; real platform facts still require verification. |
| CD-V1-004 | Release the first write through a representative allowlisted Pilot Cohort, then expand by evidence. Implementation remains full production quality. | Limits blast radius without creating a temporary implementation. |
| CD-V1-005 | First product Slice is `SKU Growth & Profit Diagnostic Loop`: broad cross-domain diagnosis plus Recommendation/Task/Approval, with Price as the first real write. | Proves both decision quality and the full execution chain without swallowing every write domain. |
| CD-V1-006 | Use Slice-level controlled production release and a separate V1 Product Complete Gate. | Produces real business value early while retaining a complete-version contract. |
| CD-V1-007 | Use an external production-grade OIDC IdP with mandatory MFA; Yandex Identity Hub is the default V1 provider. MarketOps owns business authorization. | Avoids custom password/MFA implementation and aligns with the selected cloud; current provider facts must be reverified before implementation. |
| CD-V1-008 | Model `Completed Sale`, `Retained Sale` and `Settled Sale` separately. Retained Sale is a versioned 7d/14d/30d observation metric, with 30d as the V1 primary default—not an irreversible order state. | Supports late returns and financial adjustments without rewriting source history. |
| CD-V1-009 | Use domain-specific Freshness targets and make Freshness/Confidence an execution Gate, not only a UI label. | Marketplace data sources update at different rates; stale inputs must degrade or block risky actions. |
| CD-V1-010 | Use `Production Vertical Slice + Thin Shared Spine`; shared foundations are built to the production depth required by the current Slice and then extended once. | Avoids both infrastructure-first delay and duplicate temporary foundations. |
| CD-V1-011 | Use Contract-governed development by default. A separate pre-implementation Design Gate is conditional on material risk, not mandatory for every Slice or engineering detail. | Preserves independent review while using the continuous design/implementation strength of Vibe Coding. |

## C. External facts that are not decided here

The following must be verified through primary sources and/or real controlled
accounts. They are not permitted to be invented in Design or code:

- current Ozon/WB endpoints, roles, subscriptions, quotas, error and Readback
  semantics;
- actual account/store/warehouse inventory and Pilot Cohort;
- actual internal Excel/CSV columns, ownership, effective dates and quality;
- Yandex service configuration, region behavior, backup/restore and audit proof;
- external AI Provider service eligibility, contract and data-processing terms;
- Russian hosting, personal-data and cross-border legal confirmation;
- initial Commercial Policy thresholds and exception scopes.

These facts block only the corresponding external-integration or production-
enablement Gate unless evidence shows the Product Contract itself is infeasible.
