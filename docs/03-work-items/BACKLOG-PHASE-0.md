# Phase 0 Work Package Backlog

Phase 0 is a Gate, not merely a date range. Work Packages are deliberately small enough for independent design, implementation and review.

| ID | Title | Status | Dependencies | Core source requirements |
| --- | --- | --- | --- | --- |
| WP-P0-001 | Repository, Governance & CI Foundation | READY_FOR_DESIGN | None | D-03, D-10, HR-06, CI Gate, DoR/DoD |
| WP-P0-002 | Organization, Store, Warehouse & Credential Metadata | DRAFT | WP-P0-001 | IAM-001/004/006/007, INT-002/003, ADM-001/002 |
| WP-P0-003 | Ingestion Job, Cursor, Raw, Hash, Schema Observation & Replay | DRAFT | WP-P0-001/002 | INT-001/004/006–014/019/021 |
| WP-P0-004 | Product Master, Variant, Barcode, Listing & Mapping Queue | DRAFT | WP-P0-002/003 | PIM-001/002/003/008/009 |
| WP-P0-005 | Ozon Product & Listing Read Vertical Slice | DRAFT | WP-P0-003/004 + Capability evidence | INT + PIM Phase 0 |
| WP-P0-006 | Wildberries Read Adapter Skeleton | DRAFT | WP-P0-003/004 + Capability evidence | INT + PIM Read |
| WP-P0-007 | Historical Order, Inventory, Return & Finance Truth | DRAFT | WP-P0-003/004/005/006 | ORD-001–004, INV-001–003, RET-001, FIN-001–005 |
| WP-P0-008 | Data Quality & Daily Business Report v1 | DRAFT | WP-P0-003–007 | ADM-003/004, ANL-001/002/010/015 |

## Preferred first vertical slice

```text
Approved Fixture or Ozon Product Read
→ Raw metadata/payload/hash
→ Schema validation
→ Staging
→ Platform Listing / Listing Variant
→ SKU Mapping Queue
→ Internal API
→ Console Product Table
→ Freshness / Confidence / Evidence link
```

This slice must prove traceability, idempotency, schema-drift handling, mapping, API/UI integration and evidence drill-through before expanding dashboard volume.
