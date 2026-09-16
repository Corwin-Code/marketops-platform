# SLICE-V1-004 Final Closure continuation handoff (39d55e30 R1 residual)

Status: **003 complement-feasibility residual and 027 evidence delivery complete; independent Final Closure Verification R2 pending**.

This is a bounded continuation of the existing Owner Level 1 local authorization.
It starts exactly at the Controller's Final Closure Verification R1 object, Head
`39d55e303eae5e046d0be2fd66ac2256f0c95e78`, tree `06ea3278543960368a6b891a990c167af874a901`, whose working
tree was verified clean before any change. The production repair is the
single-child commit `d65c9185adc89955d6bab3b20ac7bd9f639b5335`, tree
`e01e510d5b8bce57f7556d3e5d6996a01f8d2d74`, sole parent `39d55e30…`.

The Controller continuation prompt was addressed to Codex. The Human Owner
redirected it in chat on 2026-09-16 to Claude, the established Maker, because
Codex had no remaining token budget. That redirection carries no new authority:
the same Level 1 local envelope applies, and nothing below is a Controller
closure, a remote publication, a Level 2 action, a Provider call, Gate EV, Gate E
or a production write.

The original Contract (`5a1761ad…`), annex (`c77089fc…`) and Frozen Finding Set
(`204f9f6f…`) remain byte-identical. The Controller package
`SLICE-V1-004-Final-Closure-Verification-39d55e30-R1` verified 24/24 against its
manifest; its closure record (`e7a7fb99…`), residual conditions (`135f9610…`)
and continuation prompt (`6b8bebdc…`) are inputs, not new authority. No accepted
Amendment exists; the 85 Owner decisions, Q085-B and the three scope deltas that
are not required are untouched.

## Residual closure

`S4-DR-R1-003`. The Controller showed that `OfficialSummaryMethodEvidence.within()`
checked only `n <= N` and `k <= K`, so a critical group covering every cohort
visit with fewer successes than the cohort still received method qualification.
The predicate now admits a group only as a feasible subset of its own source
stratum: `n <= N`, `k <= K` and `K - k <= N - n`, because the complement must be
able to carry the remaining successes. The refusal uses the existing same-cohort
reason `SUMMARY_CRITICAL_GROUP_OUTSIDE_TOTAL`; no metric, group vocabulary,
threshold, reason code or approval was added, overlapping groups remain lawful,
and no rule requires groups to sum to the source. The contradictory group is
retained as reported; `pathQualified`, the independent total,
`sourceStratified` and `sourceStrataQualified` are unchanged, so the primary
formal comparison still computes and the affected protection reads
`UNDETERMINED`, never `PASS`.

`S4-DR-R1-027`. All 31 indexed payloads of the ec0 continuation exist locally,
verify 31/31 against their `SHA256SUMS` and are packaged verbatim. The new run
is kept in a separate ignored directory with its own `SHA256SUMS`. Inherited
receipts are named with the identity reason that makes them applicable. The
qualified-SUMMARY late-fact revision is now proven by its own journey rather
than by the null-method totals revision test. Canonical documents keep the 25
Controller closures at ec0, the `6ccaa6c…` targeted source and this
continuation bound to their own identities.

## Executed evidence at `d65c9185…`

| Scope | Exact result |
| --- | --- |
| Domain pre-check and compilation | Maven `test`, exit 0: 24 unit tests; every test source including the ITs compiled |
| Summary → Metric/comparison/Outcome and recalculation | Maven `verify`, exit 0: 24 unit + 13 signed-HTTP/isolated-DB integration tests (12 `ListingReworkAuthorizationIT`, of which 3 new; 1 `ListingRecalculationLeaseIT`), zero failures/errors/skips, 47.1 s |
| Architecture boundaries | Maven `test`, exit 0: 76 architecture tests |
| Whitespace, manifests, identity | `git diff --check` exit 0; backend manifest 1,323 old / 1,323 new with exactly 3 changed entries; frontend 126/126 identical; tested working tree equals the commit tree |
| Controller package and old payloads | `MANIFEST.sha256` 24/24 OK; ec0 `SHA256SUMS` 31/31 OK, 0 gaps |
| Governance and readiness validators | recorded in the machine-readable checkpoint after the canonical documents were written |

New journeys, all normal-role signed HTTP against isolated PostgreSQL:

- `infeasibleCriticalGroupComplementCannotBorrowQualifiedProtectionThroughTheFormalOutcome`
  — a 400/300 group inside a 400/364 advertising stratum: lineage retains the
  group, `sourceStrataQualified=true`, `criticalGroupSourceStrataQualified=false`
  with exactly `SUMMARY_CRITICAL_GROUP_OUTSIDE_TOTAL`; the repository exposes no
  group counts; the formal result is `MET`, protection `UNDETERMINED`, `CORE`
  `UNQUALIFIED` with `CRITICAL_GROUP_TARGET_UNQUALIFIED`, and
  `observedDifference` stays `0.634`.
- `infeasibleCriticalGroupComplementKeepsTheProvenSourceStrataWhileLawfulComplementsQualify`
  — an 80/7 group inside an 80/8 stratum keeps `pathQualified`, `primaryRatio`
  0.1 and `sourceStratified`; on a second window the whole cohort, a zero-slack
  complement and their overlap qualify with an empty reason list and their
  counts reach the comparison.
- `qualifiedSummaryLateFactRevisesTheFormalOutcomeThroughRecalculationOnce`
  — after the equivalent SUMMARY 0.634 outcome, a superseding summary with
  qualified source and critical-group inputs is recorded through the existing
  intake, the worker finishes exactly the two queued events, publishes one
  `LATE_FACT` revision (`observedDifference` 0.49, `RECALCULATION_QUEUE`
  authority, same frozen reference, `CORE` still `PASS`), preserves the original
  result, the frozen plan and both observations, and a delivery retry returns the
  finished request and republishes nothing.

Unit level: both Controller counterexamples (`N=10,K=9` with `n=10,k=8` and
`n=9,k=0`) are refused; whole-cohort, empty, zero-slack, overlapping and
all-success groups qualify; a failure inside an all-success cohort, `n > N`,
`k > K` and an unproven source are refused for their own reasons.

## Inherited and historical evidence

- Migration/schema (3 unit + 21 integration at `6ccaa6c0…`) is inherited: no file
  under `db/migration` and no schema-bearing source changed; V0124 is unchanged.
- Frontend (26/26, typecheck, format at `6ccaa6c0…`) is inherited: the frontend
  manifest is byte-identical.
- The historical complete backend (1,895 + 1,363), frontend (418/418) and browser
  (24/26 plus exact 2/2) receipts remain attributed to their own sources. They
  were not rerun at `6ccaa6c0…` or `d65c9185…`, and this handoff does not
  relabel them.
- The other 25 findings remain Controller-closed at ec0; the bounded diff is one
  domain predicate plus tests, and the nine prior summary/DETAIL/outcome/lineage
  journeys, the recalculation worker journey and the architecture tests passed.

## Evidence transport

Raw logs, JUnit XML, manifests, diffs and identity files for this run are under
the ignored root `build/slice-v1-004-final-closure-39d55e30-r1/`, indexed by
`SHA256SUMS`. The 31 ec0 payloads under
`build/slice-v1-004-final-closure-ec0e73b9-r1/` are delivered verbatim beside
it. The transport bundle's name and SHA-256, the `SHA256SUMS` hash of the new
directory and the evidence-only commit identity are reported out of band after
the canonical documents are committed.

## Acceptance and authority boundary

The 69-row register remains 54 `ENGINEERING_VERIFIED_CONTROLLER_PENDING`, 12
`EXTERNAL_EVIDENCE_PENDING` and 3 `NOT_APPLICABLE_AT_LEVEL_1`. `F-M01`, `F-M02`,
`F-S01`, `F-W01`, `F-W02` and `E-04` remain open.

`production_write_enabled=false`. No push, PR, merge, deployment, production
migration, real account, Provider call, Level 2, Gate EV, Gate E or production
side effect occurred. Claude does not issue Controller final closure; the
requested next action is independent Final Closure Verification R2 of 003, 027
and their transitive regressions at the exact checkpoint.
