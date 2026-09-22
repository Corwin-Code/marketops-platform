# SLICE-V1-004 — Current-evidence qualification for S4-DR-R1-022 and S4-DR-R1-027

Append-only record. Source: Controller review `SLICE-V1-004-PR35-CORRECTION-REVIEW-45474B60-R1`
(`01_DECISION_RECORD.json` SHA-256 `fc937ca7e9f17bb1c910b7059b86e7cdc8d6ab713fae5168bb832f84291b817c`,
`historical_closure` and `controller_coverage_correction`; `00_CONTROLLER_DECISION.md`; `03_HEADER_RETENTION_RESIDUAL.md` §4).

## What is unchanged

- The Frozen Finding Set, the Controller R2 FINAL record and the Owner Formal Closure of
  `f71d4c8c2bdf5dc6497d7951d1cd5b12b0122f10` / `b8a9e7d0450d24f2b58b95d6b370daf9cd5e5e47` keep their bytes.
- 27 of 27 findings remain recorded as closed; 54 / 12 / 3 remains the acceptance layering.
- The other 25 findings are not reopened. There is no 028th Frozen Finding.
- F-M01, F-M02, F-S01, F-W01, F-W02 and E-04, and every predecessor release obligation, remain open.

## What is qualified

| Finding | Recorded closure | Current reuse |
|---|---|---|
| S4-DR-R1-022 (wait headers and asynchronous results; the real wait must not be shortened and response evidence must be kept) | unchanged | Qualified. The shared transport dropped the native wait headers and out-of-bound wait values before they reached the adapter, so the closed wait chain did not hold for them. This is the same-root residual `PR35-NOTE-KEYPATH-01`, repaired by supplement B1. |
| S4-DR-R1-027 | unchanged | Qualified correspondingly: the evidence accepted for the wait chain did not pass through the production response filter. |

The rows of the original 022 scope (from the Controller's `evidence/ORIGINAL_022_SCOPE.json`) are
C04-AC2, API-T05, API-T08, API-T11, API-T15 and API-T18. Their recorded statuses and the counts are not
changed; `S4-AC-STATUS.json` carries an append-only overlay that points here.

## Controller coverage correction

`CRCF-PR35-04` — the Controller had accepted a test that bypassed the production transport as proof of the
complete wait chain, without checking the shared allow-list against its actual consumers. It is registered in
[`CONTROLLER_COVERAGE_CORRECTIONS.md`](CONTROLLER_COVERAGE_CORRECTIONS.md) of this directory, append-only and
separate from the earlier register in `../pr35-ci-evidence-correction-r1/`.

Whether B1 closes the residual is for the Controller's limited residual verification to decide; this record
does not claim it.
