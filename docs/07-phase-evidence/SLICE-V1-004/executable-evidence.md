# SLICE-V1-004 — canonical executable evidence

Status: **25 CONTROLLER-CLOSED AT ec0; 003/027 FINAL CLOSURE CONTINUATION COMPLETE — FINAL CLOSURE VERIFICATION R2 PENDING**.

Current continuation: the Controller record bound to `ec0e73b9…` closed 25 of 27
frozen findings; Final Closure Verification R1 at `39d55e30…` found one
residual inside 003 (complement feasibility of critical groups) and left 027 on
evidence delivery. Both are repaired at Head `d65c9185adc89955d6bab3b20ac7bd9f639b5335`, Tree
`e01e510d5b8bce57f7556d3e5d6996a01f8d2d74`. The exact commands, counts, raw artifact
hashes and inherited receipts are in
[FINAL_CLOSURE_CONTINUATION_39D55E30_R1.json](rework-r1/FINAL_CLOSURE_CONTINUATION_39D55E30_R1.json);
the ec0 continuation is preserved in
[TARGETED_FINAL_CLOSURE_CHECKPOINT.json](rework-r1/TARGETED_FINAL_CLOSURE_CHECKPOINT.json).
The sections below are the historical Level 1 receipts for their own sources and
are not relabelled as a full pass at any later Head.

This page is the canonical summary for the Human Owner-authorized Level 1 local
root-cause rework. Exact final commands and results are retained in the rework
[executable evidence](rework-r1/executable-evidence.md). The source review is
bound to Head `f91d107c53a0cf3964ae43c0e8353e0c244a2b59`, Tree
`b04fc98b9a3e156cc66e00fe878569306972c638`, and Frozen Finding Set SHA-256
`204f9f6f914ec415694f5a1693f86d2a08e6d92283fdbf9d8dfa4755da7a8843`.

The verified implementation source is Head
`16eda4bf7e5f561b60d10c19a9a157bd62d21d6e`, Tree
`98b9ff7d692eb869fb1f7bf704980259426e09f1`. The final evidence-only Git
checkpoint is reported out of band after this document is committed; a document
does not claim its own self-referential commit identity.

## Completed convergence regression

| UTC date | Command | Result | Scope |
| --- | --- | --- | --- |
| 2026-09-13 | `./mvnw -B -ntp -Dtest=ListingSimulationInputEvidenceTest,ListingReworkAuthorizationIT test` | **113 tests, 0 failures, 0 errors** | The 104 connected authorization/database scenarios and 9 strict simulation-input scenarios after the concentrated root-cause fixes. |

This finite run verifies the previously failing convergence set. The local
layer-specific receipts below establish the terminal Level 1 result.

## Final consolidated matrix

| Layer | Command/result receipt | Terminal state |
| --- | --- | --- |
| Backend, database, architecture and migration | `./mvnw -B -ntp clean verify`; `2026-09-14T01:04:42+08:00`–`01:54:44+08:00`; exit 0, `BUILD SUCCESS`; Maven declared 1,895 Surefire and 1,363 Failsafe tests, zero failures/errors/skips; JaCoCo line 86.880515%, branch 70.883436% | `PASS` |
| Frontend | Node 24.19.0: lint, format check, typecheck, `test:ci`, build and bundle verification all exit 0; Vitest 29 files, 418/418 tests; coverage statements 84.32%, branches 76.68%, functions 84.42%, lines 85.31% | `PASS` |
| Browser | One complete run passed 24/26. Its two deterministic failures were isolated to a missing Compose project environment value and an uncovered synthetic promotion-enumeration interval. The exact two failed tests then passed together at Head `16eda4bf…`: 2/2, exit 0. Thus all 26 unique scenarios have a passing receipt; no single all-green full invocation is claimed. | `PASS_BY_FULL_RUN_PLUS_DETERMINISTIC_FAILED_TEST_CLOSURE` |
| Source integrity | Backend/frontend manifests were stable across the closing browser run; Contract, annex and Frozen Finding Set hashes match the bound values; V0001–V0079 remain preserved and V0080–V0123 are forward-only. | `PASS` |
| Canonical evidence | `rework-r1/FINAL_LEVEL1_LOCAL_VERIFICATION.json`, `S4-AC-STATUS.json`, `finding-progress.json`, migration inventory and this handoff are synchronized. | `PASS` |

Ignored raw receipts are under `build/slice-v1-004-final-evidence/`. The canonical
hash manifest is [FINAL_LEVEL1_LOCAL_VERIFICATION.json](rework-r1/FINAL_LEVEL1_LOCAL_VERIFICATION.json).
The browser aggregation and both failure causes are recorded without promoting
the earlier 24/26 invocation into a single-run pass.

## Execution boundary

- All exercised data and endpoints are local and synthetic.
- No real Ozon or Wildberries Provider, account or credential was called.
- No remote Git write, Level 2 execution, shared or production deployment,
  migration, Gate EV, Gate E or business side effect was authorized or performed.
- All new platform write paths remain default-OFF and structurally unreachable
  without later verified capability and release gates.
- `F-M01`, `F-M02`, `F-S01`, `F-W01`, `F-W02` and `E-04` remain open at their
  existing consuming gates.

## Status semantics

The terminal Level 1 evidence set permits Codex to report a finding as
`ENGINEERING_VERIFIED_CONTROLLER_PENDING`. Only the independent Controller can
issue final closure verification; no local result is represented as `CLOSED`.
