# Open Questions Register

## Bootstrap decisions

| ID | Question | Needed by | Blocking | Owner | Status |
| --- | --- | --- | --- | --- | --- |
| OQ-001 | Which GitHub user/organization owns `marketops-platform`? | Repository creation | Yes | Human Owner | OPEN |
| OQ-002 | What company-controlled domain defines the Java root package? | WP-P0-001 implementation | Yes | Human Owner | OPEN |
| OQ-003 | What is the primary development OS and container runtime? | WP-P0-001 design approval | No | Human Owner | OPEN |
| OQ-004 | Is Codex formally enabled for rework/fix/verify? | First review cycle | No | Human Owner | OPEN |
| OQ-005 | Which authentication solution will be used for application users? | IAM design | No for WP-P0-001 | Owner + Controller | OPEN |
| OQ-006 | Which Secret Manager and S3-compatible object storage are approved for Integration/Staging/Production? | INT-003 / Raw implementation | No for design skeleton | Owner + Security | OPEN |

## Sprint 0 business and platform questions from the Baseline

| ID | Question | Needed by | Blocking | Status |
| --- | --- | --- | --- | --- |
| OQ-101 | Actual Ozon/WB Store and Account count and fulfillment modes | WP-P0-002/005/006 | Yes | OPEN |
| OQ-102 | Available API roles, subscriptions and advertising permissions | Capability Matrix v1 | Yes | OPEN |
| OQ-103 | First-version cost model for purchase, packaging, warehouse labor, tax and overhead | Finance model | Yes | OPEN |
| OQ-104 | Business windows for completed order, refusal and return | Order/return mapping | Yes | OPEN |
| OQ-105 | Internal Barcode/SKU data quality and duplicate policy | Product identity | Yes | OPEN |
| OQ-106 | Existing ERP/WMS/accounting systems and export/API availability | Integration scope | No | OPEN |
| OQ-107 | Russia hosting, backup, personal-data and cross-border access legal confirmation | Staging/Production | Yes before production | OPEN |
| OQ-108 | Hero SKU and first experiment scope | Phase 1 | No for Phase 0 | OPEN |

## Resolution format

Every resolved question must record:

```text
Decision / answer
Evidence or source
Effective date
Affected Work Packages / ADRs / Requirements
Migration or compatibility impact
Approver
```
