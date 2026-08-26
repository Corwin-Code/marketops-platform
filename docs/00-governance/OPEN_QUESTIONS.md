# Open Questions and External Evidence Register — V1

This register separates product decisions from facts/configuration that must be
verified. No item below blocks DR-0003 or the start of SLICE-V1-001 unless its
`Blocks` column says `IMPLEMENTATION_START`.

| ID | Question / required evidence | Current disposition | Blocks | Owner |
| --- | --- | --- | --- | --- |
| OQ-005 | Human-user authentication solution | RESOLVED BY DR-0003: external OIDC IdP + mandatory MFA; Yandex Identity Hub default; MarketOps owns business authorization | OIDC runtime acceptance, not implementation start | Owner + Security + Controller |
| OQ-006 | Secret Manager and Object Storage | RESOLVED BY OWNER: Yandex Cloud `ru-central1`; provider ports retained. Exact Lockbox/KMS/Object Storage/backup/retention configuration requires evidence | Raw provider acceptance and production readiness | Security + Platform |
| OQ-101 | Actual Account/Store/Warehouse and fulfillment inventory | PARTIALLY RESOLVED: at least one controlled Ozon and WB Seller Account; V1 must model both FBO/FBS. Exact inventory still required | Onboarding and Pilot enablement | Owner + Ops |
| OQ-102 | Actual API roles, subscriptions, quotas and write/readback semantics | EXTERNAL EVIDENCE REQUIRED for each account/capability | Corresponding real Adapter; Gate EV for the first real write; Gate E for Pilot enablement | Marketplace Integration |
| OQ-103 | Cost/profit model | RESOLVED AT MODEL LEVEL: Contribution Profit primary; Canonical/Estimated separated. Actual cost values and policy thresholds remain configuration | Profit-backed write enablement | Owner + Finance + Ops |
| OQ-104 | Completed/refusal/return window | RESOLVED BY MODEL: Completed, Retained and Settled are separate; Retained is versioned 7d/14d/30d, 30d default | Metric acceptance | Product + Finance + Data |
| OQ-105 | SKU/Barcode duplicate policy | RESOLVED BY PROCESS: mapping candidates, conflict queue, manual confirmation, effective-time versioning. Actual data profile remains required | Mapping acceptance | Product Ops + Data |
| OQ-106 | ERP/WMS/accounting integration | RESOLVED FOR V1 ENTRY PATH: controlled manual entry + Excel/CSV. Future source APIs remain discovery | Source-specific importer acceptance | Owner + Finance + Warehouse |
| OQ-107 | Russia hosting, backup, personal-data and cross-border legal confirmation | EXTERNAL LEGAL EVIDENCE REQUIRED | Production release | Owner + Legal + Security |
| OQ-108 | Hero SKU and first experiment/Pilot cohort | OPERATIONAL CONFIGURATION REQUIRED; Gate EV needs an exact verification SKU allowlist and Gate E separately needs the Pilot Cohort | First real-write verification and Price Capability production enablement | Owner + Ops |
| OQ-109 | External AI Provider eligibility, contract and data-processing terms for this Russian business | EXTERNAL EVIDENCE REQUIRED; provider-neutral Gateway is binding | Real external AI production call | Owner + Legal + Security |
| OQ-110 | Actual COGS/stock/finance Excel/CSV samples and schema ownership | DATA INPUT REQUIRED with approved redacted samples | Importer contract test and user acceptance | Finance + Warehouse + Data |
| OQ-111 | Initial Commercial Policy thresholds: minimum margin/profit, max delta, cooldown, Confidence and exceptions | OPERATIONAL CONFIGURATION REQUIRED and versioned in product; Gate EV separately fixes maximum verification delta and cumulative exposure | Gate EV real-write envelope and Price Pilot enablement; not implementation start | Owner + Ops + Finance |
| OQ-112 | Exact Yandex Identity Hub tenant/application setup and recovery administrators | CONFIGURATION/EVIDENCE REQUIRED | Public-login production acceptance | Owner + Security |
| OQ-113 | Exact bounded real-write verification envelope: Platform, opaque Account/Store, Capability, SKU allowlist, window, price delta/exposure, operator and abort owner | NOT AUTHORIZED; must be explicitly approved at Gate EV and defaults to `NONE` | First real write used for evidence only; not implementation or general Pilot | Human Owner + Controller |

## Gate rule

An external evidence item becomes a blocker only at the boundary that consumes
it. Claude must continue independent in-scope work and use fail-closed
`UNVERIFIED` Capability/configuration states. It may stop the whole Slice only
when evidence proves the Slice Contract infeasible or a Conditional Design Gate
trigger affects the Slice architecture as a whole.

No Secret, real Token, Buyer PII or unredacted production payload belongs in this
register, GitHub, chat or the public repository.
