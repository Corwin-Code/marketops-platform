# V1 Shared Spine

```yaml
architecture_role: CROSS_SLICE_SINGLE_AUTHORITIES
product_contract: MARKETOPS-V1-PRODUCT
active_first_consumer: SLICE-V1-001
technology: JAVA_SPRING_POSTGRES_REACT_MODULAR_MONOLITH
```

## 1. Purpose

The Shared Spine contains cross-cutting capabilities that must have one authority
and be reused by every Delivery Slice. It is not a separately completed product
stage. Each Slice extends it only to the production depth needed for a complete
user outcome.

## 2. Shared authorities

| Authority | Sole owning boundary | Consumers |
| --- | --- | --- |
| Human identity token validation | `identityaccess` OIDC adapter/application boundary | all web/API surfaces |
| Business roles/scopes/approval authority | `identityaccess` + `operationsworkflow` public contracts | all business modules |
| Organization/Account/Store/Warehouse topology | `organizationaccount` | all scoped modules |
| Marketplace capability, credential reference and adapter invocation | `marketplaceintegration` | product/order/inventory/finance/ads slices |
| Ingestion job, lease/fence, cursor and Raw intake | `marketplaceintegration` | all source normalizers |
| Immutable Raw custody/provenance | `marketplaceintegration` + `raw` schema/ObjectStorage adapter | replay, normalization, evidence UI |
| Canonical Metric definitions/calculation versions | `analyticsdecision` metric service | UI, AI, policy, outcome tracking |
| AI provider invocation and structured output validation | `aicopilot` gateway | analytics and workflow only |
| Recommendation/Task/Approval/Policy | `operationsworkflow` | all decision domains |
| Platform Command/Outbox/Readback | `marketplaceintegration` execution boundary, initiated by `operationsworkflow` authority | controlled capabilities |
| Audit/operability | `adminobservability` append-only audit and operational views | all modules |

A module may query another module through an explicit public Application/Query
contract. It must not access another module's Repository or create a second writer.

## 3. Existing assets to reuse

- Organization/Account/Store/Warehouse references and associations;
- Service Account/Scope metadata and permission taxonomy;
- Credential reference, Capability/Endpoint Registry and Feature Flags;
- no-secret field/return/logging contracts;
- V0007–V0010 ingestion authority, fencing, checkpoint and Raw-reference model;
- `AcquisitionPort`, `AcquisitionRequest`, `AcquisitionResult` and
  `ObjectStoragePort` after adapter-gate review;
- audit, Correlation ID, errors and least-privilege database roles;
- architecture/real-database mutation tests;
- React configuration and HealthShell as an operations surface.

## 4. Required V1 additions

### Identity and authorization

- OIDC resource-server/client integration and token validation;
- internal User Profile linked to external subject;
- Role + Store/Platform/Warehouse/Data/Action Scope;
- MFA claim/policy acceptance and sensitive-action step-up;
- user disable/revocation/session/audit behavior.

### Integration and Raw

- real Ozon/WB adapters only behind the exact marketplace-integration root;
- Yandex Lockbox/Secret reference resolution;
- Yandex Object Storage immutable Raw adapter;
- Scheduler/worker/retry/rate limit/circuit/backpressure/replay/reconciliation;
- exact Provider Capability evidence and fail-closed `UNVERIFIED` state;
- controlled manual and file source intake using the same Raw custody contract.

### Product and identity

- Product Master, Variant, Listing and Listing Variant;
- native IDs/Barcode and Mapping Candidate/Conflict Queue;
- effective-time, reviewer and reprocessing behavior.

### Facts and metrics

- thin canonical projections for all Slice-1 diagnostic domains;
- deterministic versioned metrics, Freshness and Confidence;
- Canonical/Estimated Contribution Profit separation;
- late data, adjustment and recalculation partitions.

### Decision and execution

- Recommendation, Task, Approval and Owner Policy;
- deterministic Guardrail with explicit reason codes;
- idempotent Command/Outbox and attempt state;
- provider-native asynchronous/unknown state;
- Readback, mismatch, manual resolution and restore/compensation;
- Feature Flag/allowlist/Kill Switch by platform/account/store/capability/entity.

### AI

- approved Data Projection and evidence references;
- provider-neutral model gateway;
- structured Fact/Inference/Recommendation/Unknown output;
- prompt/model/version/audit/outcome record;
- deterministic validation before workflow creation.

## 5. Binding invariants

1. No external call begins without an authorized, scoped, auditable application
   boundary.
2. Exact Raw bytes are durable and hash-verified before source progress is
   acknowledged.
3. Replay performs zero Marketplace download unless explicitly started as a new
   acquisition job.
4. A stale worker/fence cannot write cursor, Raw completion, command or success.
5. Canonical facts/metrics are deterministic; AI output cannot mutate them.
6. Recommendation does not imply authorization; authorization does not imply
   provider success; provider acceptance does not imply Readback match.
7. Unknown external result is first-class and cannot be blindly retried.
8. Every platform write is idempotent, scoped, kill-switchable and fully audited.
9. Buyer PII and Secret material cannot enter AI prompts, general Mart, logs or
   client bundles.
10. V0001–V0010 are immutable and every new migration is forward-only.
11. No Delivery Slice creates a parallel ingestion, Metric, Policy, Command or
    audit stack.
12. Platform DTO/SDK types never leak into domain/core/public product contracts.

## 6. Provider boundaries

```text
MarketOps business modules
→ stable internal contracts
→ marketplace / storage / secret / identity / AI Ports
→ Yandex, Ozon, WB or model-provider Adapters
```

Provider-specific retries, quotas, state polling, errors and DTOs remain inside
adapters. Common business semantics are not reduced to a lowest-common-denominator
API shape.

## 7. Database and migration strategy

- retain the existing schema set unless a reviewed Slice migration proves a new
  schema necessary;
- start future migrations at V0011;
- never edit applied migrations;
- isolate immutable Raw/Ledger from mutable operational workflow;
- use append-only events/versions for late facts and policy/metric changes;
- use outbox and stable idempotency keys for external writes;
- provide clean install, upgrade-from-V0010 and rollback/forward-fix evidence.

## 8. Evolution rule

A Shared Spine change is accepted only when required by an active Slice or a
production defect. It must name the consuming user outcome and cannot become an
open-ended foundation program.
