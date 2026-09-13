# SLICE-V1-004 evidence index

Status: **LEVEL 1 LOCAL ENGINEERING VERIFIED — CONTROLLER VERIFICATION PENDING**.

This directory contains the current canonical Level 1 evidence for SLICE-V1-004.
The original Maker artifacts are preserved separately at
[`rework-r1/historical-maker/`](rework-r1/historical-maker/); they are historical
inputs and do not state the current rework result.

## Canonical Controller entry points

- [Controller handoff](controller-handoff.md) — bound identities, scope,
  verification matrix, authority boundary and requested independent action.
- [Acceptance status](acceptance-status.md) and
  [machine-readable status](S4-AC-STATUS.json) — current evidence-grounded status
  for 36 functional groups, 6 inherited non-functional groups and 27 official
  API scenarios.
- [Executable evidence](executable-evidence.md) — canonical command/result
  summary; exact rework receipts are in
  [rework-r1/executable-evidence.md](rework-r1/executable-evidence.md).
- [Migration inventory](MIGRATION-INVENTORY.json) — reviewed predecessor chain
  and exact V0080–V0123 bytes, line counts and SHA-256 values.
- [Finding progress](rework-r1/finding-progress.json) — all 27 frozen roots with
  their correction, same-class/transitive scan, evidence and Controller-pending
  disposition.
- [Final Level 1 verification receipt](rework-r1/FINAL_LEVEL1_LOCAL_VERIFICATION.json)
  — exact local commands, counts, artifact hashes and authority boundary.
- [Rework design](../../02-architecture/designs/SLICE-V1-004-rework-r1-design.md)
  and [API document cross-check](rework-r1/API_CROSSCHECK.md).

## Immutable inputs

| Input | SHA-256 |
| --- | --- |
| Original Contract | `5a1761ad614426ad3cba9594f481e293b584d69e96c6d893cf502a5062cfc983` |
| Bound acceptance annex | `c77089fc78183d6289ed0023d4d0dbee915f61e8d917a7f49ba8564b0dc2a48d` |
| R1 Frozen Finding Set | `204f9f6f914ec415694f5a1693f86d2a08e6d92283fdbf9d8dfa4755da7a8843` |

The Frozen Finding Set is bound to reviewed Head
`f91d107c53a0cf3964ae43c0e8353e0c244a2b59` and Tree
`b04fc98b9a3e156cc66e00fe878569306972c638`.

## Current boundary

The verified implementation source is Head
`16eda4bf7e5f561b60d10c19a9a157bd62d21d6e`, Tree
`98b9ff7d692eb869fb1f7bf704980259426e09f1`. The clean backend and complete
frontend layers passed. The browser evidence comprises one complete 24/26 run
and a 2/2 targeted closure of exactly those deterministic failures; all 26
unique scenarios have a passing receipt, while no single all-green full
invocation is claimed. All 27 findings are
`ENGINEERING_VERIFIED_CONTROLLER_PENDING`; no Controller closure is claimed.

Production writes remain disabled. No real Provider/account call, remote Git
write, Level 2 work, shared or production deployment/migration, Gate EV, Gate E
or real business side effect is represented by this evidence.
