# MarketOps V1 Product Contract

```yaml
contract_id: MARKETOPS-V1-PRODUCT
status: APPROVED_BY_DR_0003_PENDING_REPOSITORY_EFFECT
owner: Human Owner
controller: GPT-5.6 Sol Pro
effective_date: 2026-08-26
product: MarketOps Russia
delivery_model: PRODUCTION_VERTICAL_SLICES
```

## 1. Product definition

MarketOps V1 is a production-grade internal operations and decision system for
one Russian operating entity. It unifies official Ozon/Wildberries and internal
business facts, discovers hidden SKU/store problems, explains likely causes,
generates concrete actions, applies deterministic commercial and safety policy,
executes selected official Marketplace operations and verifies the result.

The product is not judged by the number of roles, pages or technical layers. Its
primary V1 acceptance object is this operating loop:

```text
Fact → Diagnosis → Recommendation → Governed Action → Readback → Outcome
```

## 2. User outcome

Authorized Owner, Operations and Finance users can log in remotely and use a
structured console to:

- identify which Stores/SKUs require action and why;
- inspect exposure, CTR, conversion, Listing, price, promotion, inventory,
  returns, advertising, COGS, fees and Contribution Profit together;
- see Freshness, Confidence, missing data and Raw/Evidence references;
- receive AI-generated explanations, competing hypotheses, concrete
  recommendations, expected effect and risk;
- convert recommendations into Tasks, Approval or Owner pre-authorized Policy;
- execute enabled low-risk capabilities in Ozon/Wildberries;
- see provider state, Readback match/mismatch, restore/compensation, Audit and
  subsequent operating metrics.

## 3. V1 scope

### Marketplace and fulfillment

- Ozon and Wildberries;
- both platforms' FBO/FBS semantics;
- official APIs/reports only;
- at least one real controlled write Capability on each platform before V1 Product
  Complete;
- a unified internal Capability model with platform-specific adapter semantics.

### Decision domains

V1 decision coverage includes:

- Product Master, Variant, Barcode, Listing and Mapping;
- price and promotion;
- platform and internal inventory/availability;
- advertising and traffic/funnel;
- orders, fulfillment, cancellation/refusal/return;
- COGS, platform fees, finance/settlement and Contribution Profit;
- Data Quality, Freshness, Confidence and reconciliation;
- Recommendation, Task, Approval, Policy, Command, Readback and Audit.

Not every domain must expose a write in the first Slice. V1 uses all-domain
analysis plus selected Capability execution.

### Internal business facts

- purchase cost/COGS and Cost Version;
- local physical inventory;
- necessary finance/accounting/settlement inputs;
- controlled manual entry as fallback;
- productized Excel/CSV import with hash, schema validation, preview, mapping,
  approval, audit, replay/reconciliation and safe failure.

## 4. Profit and business objective

`Contribution Profit` is the primary SKU/order operating profit measure:

```text
Net Sales
- COGS
- Marketplace Commission
- Fulfillment / Delivery / Storage
- Return and Refurbishment Loss
- Advertising and Promotion Cost
- Variable Tax Estimate
- Other Variable Fees
```

- `Canonical Contribution Profit` uses deterministic confirmed inputs.
- `Estimated Operational Contribution Profit` may use explicit, versioned
  estimates and must show Confidence/assumptions.
- High-risk Price/Ads/Promotion writes require stronger data completeness and
  Confidence than analysis or low-risk recommendation.
- Difficult-to-allocate company overhead does not become SKU decision truth.

The product supports Owner-defined objectives by SKU/lifecycle:

```text
Hero / Growth / Mature / Repair / Exit
```

Default priority is sustainable Contribution Profit. Owner Policy may explicitly
trade margin, growth, inventory exit or repair within deterministic boundaries.

## 5. Deterministic truth and AI intelligence

Canonical business facts and metrics are:

- deterministic and reproducible;
- versioned with effective dates and owner;
- linked to Raw/source evidence;
- time/freshness/confidence aware;
- re-computable under late data and adjustments.

AI may:

- detect and rank anomalies/opportunities;
- connect facts across domains;
- generate competing root-cause hypotheses;
- explain uncertainty;
- propose concrete action, parameters, expected effect and verification plan;
- summarize experiments and outcomes.

AI may not:

- become the canonical calculator of official metrics or profit;
- hold Marketplace Credentials;
- approve or directly execute a Marketplace command;
- bypass Data Quality, Inventory, Profit, Permission or Policy Gates;
- send Buyer name/phone/full address or Secrets to a model;
- present inference as a settled fact.

Approved field-allowlisted/redacted operating data may use eligible external cloud
models through a provider-neutral Gateway. Every output separates `Fact`,
`Inference`, `Recommendation` and `Unknown` and carries Evidence references.

## 6. Controlled execution

Every real platform write follows:

```text
Evidence-linked Recommendation
→ Deterministic Data Quality / Sellability / Inventory / Profit / Permission Gate
→ Impact Preview / Dry Run
→ Approval or bounded Owner Policy Authorization
→ Idempotent Command / Outbox
→ Official Marketplace Adapter
→ Provider State + Readback
→ Success, Mismatch or Unknown Manual Resolution
→ Audit + Restore/Compensate + Outcome Follow-up
```

- all write Capabilities default disabled;
- enablement is Platform + Account/Store + Capability + entity scope;
- each capability has a Kill Switch;
- unknown/timeout never becomes blind retry success;
- Pilot Cohort limits real scope without weakening implementation;
- AI may propose a command but cannot authorize it.

`PRICE_CHANGE` is the first target capability for both platforms. Exact endpoint,
permission, quota and Readback semantics remain `UNVERIFIED` until primary-source
and real-account evidence is recorded.

## 7. Data and event truth

- exact Raw source bytes, request metadata, hash, source/ingestion/processing time
  and provenance are immutable;
- source-native IDs/statuses and unknown values are preserved;
- identical source/replay input cannot create duplicate logical effects;
- Inventory and Financial Ledgers are append-only; snapshots are rebuildable;
- late returns/fees create events/adjustments and new Calculation Versions;
- `Completed Sale`, `Retained Sale` and `Settled Sale` are separate;
- Retained Sale is a versioned observation metric supporting 7d/14d/30d; 30d is
  the V1 primary default, not an irreversible state;
- Freshness differs by domain and is a Recommendation/Write Gate.

## 8. Security, identity and privacy

- human authentication: external production-grade OIDC IdP + mandatory MFA;
- default provider: Yandex Identity Hub, subject to current verification;
- MarketOps stores no user password/MFA secret;
- MarketOps owns business Role, Store/Platform/Warehouse/Data Scope, Approval and
  Policy authority;
- step-up/reauth is required for sensitive operations;
- Credentials use Secret Manager references and least privilege; read/write/
  finance/ads purposes remain separable;
- Buyer PII stays outside AI and general Analytics/Mart by default;
- all sensitive access/export/write/approval events are append-only audited;
- public repository contains only synthetic or formally redacted data.

## 9. Infrastructure

- primary production environment: Yandex Cloud `ru-central1`;
- managed PostgreSQL with verified backup/PITR and least-privilege roles;
- Yandex Object Storage for immutable Raw with verified integrity/retention;
- Lockbox/KMS/workload identity/audit services or verified equivalent inside the
  selected environment;
- Docker-based deployment; no Kubernetes/microservices/multi-cloud in V1 without
  a new ADR;
- provider-specific code stays behind Ports/Adapters;
- external AI models stay behind an AI Provider Gateway;
- exact topology, RPO/RTO, retention and recovery require executable evidence.

## 10. Product interaction

The structured console is the primary interface. AI is embedded in Command
Center, SKU 360, Inventory, Ads, Finance, Tasks and Approval surfaces. Chat may be
added as an auxiliary interface but cannot form a parallel fact or execution
path.

## 11. Delivery and release

- primary unit: Production Delivery Slice;
- each Slice has a Product Acceptance Contract and complete production evidence;
- Claude performs Detailed Design + Initial Full Implementation continuously;
- GPT performs Deep Review;
- Codex performs full in-scope production rework/fix/verify;
- GPT performs Final PR Gate;
- production write enablement is a separate Capability Gate;
- a Slice may release to bounded real users/data before all V1 Slices are complete;
- `V1 Product Complete` is separate from Slice release and from business uplift.

## 12. V1 Delivery Slices

The active roadmap is defined in `docs/03-work-items/V1_DELIVERY_SLICES.md`:

1. SKU Growth & Profit Diagnostic Loop + Price execution;
2. Inventory & Availability Optimization;
3. Advertising & Traffic Efficiency;
4. Listing & Promotion Conversion;
5. Order, Fulfillment & Return Control;
6. Finance & Contribution Profit Reconciliation;
7. Cross-domain Command Center and V1 Product Gate.

Early Slices may implement thin read projections from later domains where needed
for a complete diagnosis. Later Slices deepen domain workflow and write
capabilities; they must reuse the same Shared Spine.

## 13. V1 Product Complete

V1 is complete only when:

- all required Slices are production-grade and integrated;
- both Ozon and WB have at least one enabled controlled write capability;
- all decision domains have trusted facts, Freshness/Confidence and evidence;
- internal COGS/stock/finance intake is operational;
- AI analysis/recommendation is evidence-linked and safely governed;
- Recommendation/Task/Approval/Policy/Command/Readback/Audit works end to end;
- authentication, RBAC/scope, provider security, backup/restore, monitoring and
  runbooks pass;
- no unresolved Critical/High defect or unowned data gap remains;
- production release is legally/security approved.

A predetermined sales or profit uplift is not required to declare capability
complete; outcome measurement continues after release.

## 14. V1 non-goals

- external multi-tenant SaaS;
- full ERP/WMS/statutory accounting/tax replacement;
- browser automation, scraping or unpublished APIs;
- fully autonomous AI operating agent;
- every Marketplace write domain in the first Slice;
- identical Ozon/WB endpoint semantics;
- microservices, Kafka, Kubernetes, large-scale real-time streaming or multi-cloud;
- Buyer PII in AI/general Mart without a future explicit Gate;
- AI-generated Russian content published without authorized review;
- rewriting V0001–V0010 or deleting historical evidence.

## 15. External evidence boundaries

`OPEN_QUESTIONS.md` assigns current Marketplace capability, file-schema, AI
provider, Yandex configuration, legal, policy-threshold and Pilot scope evidence.
They block only their consuming integration or enablement Gate unless they prove
this Contract infeasible.
