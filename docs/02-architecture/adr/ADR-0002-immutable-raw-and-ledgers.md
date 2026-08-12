# ADR-0002 — Immutable Raw Evidence and Ledgers

- Status: ACCEPTED
- Date: 2026-08-06
- Source: D-04; HR-01, HR-02, HR-03, HR-04

## Decision

- Every meaningful platform response, report, push event and manual import is retained as immutable Raw evidence with metadata, hash, schema version and source/ingestion time.
- Core entities are not the only copy of source truth.
- Inventory and financial changes are represented by append-only transactions.
- Corrections use reversal, adjustment or new calculation versions; history is not silently overwritten.
- All ingestion and replay paths are idempotent.

## Consequences

Storage and processing are more deliberate, but every metric and normalized fact can be traced, replayed and reconciled. Snapshot and Mart tables are rebuildable and may not be treated as the audit truth.
