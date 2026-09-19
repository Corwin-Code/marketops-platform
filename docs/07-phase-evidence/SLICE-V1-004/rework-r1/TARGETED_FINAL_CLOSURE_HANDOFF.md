# SLICE-V1-004 targeted Final Closure Verification handoff

Status: **003 and 027 rework complete; independent Final Closure Verification pending**.

This is a bounded continuation of the existing Owner Level 1 local authorization.
It starts exactly at Head `ec0e73b9b9451f63f0cef385aed623d63521596a`,
tree `c6f28fe4fb084d9b1fd6e3fdfdd744edf59fc8b2`. The production repair is the
single-child commit `6ccaa6c070cb5a786a91d447b474b44926f8837c`, tree
`1da0d52ffdb6d658cddfa9c6699b0c0818dbf9d3`, sole parent `ec0e73b…`.

The original Contract (`5a1761ad…`), annex (`c77089fc…`) and Frozen Finding Set
(`204f9f6f…`) remain byte-identical. The Controller closure record (`b4b06412…`)
and residual conditions (`b03e8954…`) are inputs, not new authority, and were not
written into the Frozen JSON. No accepted Amendment exists.

## Residual closure

`S4-DR-R1-003` now has an actual OFFICIAL_SUMMARY method bridge. The existing
normal-role fact-intake HTTP path accepts exact method version, advertising and
organic strata, plus optional critical-group strata. The database retains those
inputs. Measurement validates exact sums, group bounds, profile enablement and
profile chronology, then records separate total/source/group qualification in
lineage. The formal fixed-traffic comparison and Outcome consume those qualified
facts without requiring the literal `DETAIL` path.

The positive counterexample uses signed HTTP and a real isolated Testcontainers
PostgreSQL database, has no visit-detail facts, and produces the same frozen
formal `0.634` outcome as DETAIL. Missing group evidence leaves that protection
`UNDETERMINED`; inconsistent source sums preserve the independently proven total
but cannot borrow formal qualification; retired profiles yield `NOT_AVAILABLE`;
malformed structures return HTTP 400 without creating an observation.

`S4-DR-R1-027` is reconciled rather than self-closed: the bound Controller record
closed 25 findings at ec0, while 003 and 027 are now
`REWORK_COMPLETE_PENDING_INDEPENDENT_FINAL_CLOSURE_VERIFICATION`. This checkpoint
claims zero new Controller closures. The next review remains Final Closure
Verification and is not a new Deep Review.

## Exact implementation delta and impact

The implementation commit relative to ec0 changes 15 files: 539 insertions and
44 deletions. The affected production surface is summary profile/observation schema, existing fact intake,
measurement and lineage qualification, formal fixed-traffic comparison/Outcome,
and late-fact recalculation. Test changes cover those paths plus frontend payload
propagation. Exact name/status, stat, old/new source manifests and their SHA-256
digests are recorded in
[TARGETED_FINAL_CLOSURE_CHECKPOINT.json](TARGETED_FINAL_CLOSURE_CHECKPOINT.json)
and retained locally under
`build/slice-v1-004-final-closure-ec0e73b9-r1/`.
The subsequent evidence checkpoint adds canonical documents and one exact V0124
approved-migration registry entry; its commit/tree and complete ec0 diff are
reported out of band to avoid self-reference.

V0124 is one additive forward migration: 39 lines, 2,706 bytes, SHA-256
`f3fe79e8237dc088a54a4e00bff29afb0e6278f7b4c9055f5bad6d4114bb3802`.
It defaults new profile qualification flags to false and does not upgrade any
existing profile. A local synthetic row or document can therefore demonstrate
the method, but it cannot qualify a real platform or enable production.

## Executed evidence

| Scope | Exact result |
| --- | --- |
| Summary → Metric/comparison/Outcome | Maven `verify`, exit 0: 21 unit + 9 signed-HTTP/isolated-DB integration tests, zero failures/errors/skips |
| Migration/schema | Maven `verify`, exit 0: 3 unit + 21 integration tests, V0001–V0124 clean/upgrade/rollback/schema coverage |
| Recalculation propagation | Maven `verify`, exit 0: 3 unit + 1 isolated-DB integration test; exact result published once with source clock preserved |
| Frontend summary request | Vitest, exit 0: 26/26; JSON reporter retained |
| Frontend type/format | Typecheck and final Prettier check, both exit 0 |

The machine-readable checkpoint contains every exact command, exit code, raw
XML/JSON/log path and SHA-256. It also records three superseded diagnostics: one
Maven invocation that exited 0 while explicitly skipping tests (not evidence),
one failed test assertion caused by JdbcClient interpreting PostgreSQL `?` as a
placeholder (fixed to an equivalent JSONB assertion before the authoritative
pass), and the initial Prettier failure followed by formatting and green reruns.

## Preserved regression and accurate status

The historical terminal receipt remains evidence for its own verified source:
backend 1,895 Surefire + 1,363 Failsafe, frontend 418/418, and the bounded browser
combination 24/26 plus the exact 2/2 closure rerun. Those logs are preserved. They
were not rerun at `6ccaa6c…`, and this handoff does not mislabel them as a new
Head-wide complete-suite or browser pass.

The other 25 findings remain Controller-closed on the bound ec0 record. Their
non-regression basis is the exact bounded diff, old/new manifests, additive
default-false migration, and passing changed/transitive tests for DETAIL formal
Outcome, revisions, caller-number refusal, lineage, independent totals,
contradiction, profile chronology, schema/privileges and frontend transport. No
provider/write-gate/approval/allowance/command/compensation/credential/AI/
financial/task/notification authority changed. This is a scoped regression
argument, not a claim that the historical 31-hour suite was repeated.

The 69-row acceptance register remains: 54
`ENGINEERING_VERIFIED_CONTROLLER_PENDING`, 12 `EXTERNAL_EVIDENCE_PENDING`, and
3 `NOT_APPLICABLE_AT_LEVEL_1`. `F-M01`, `F-M02`, `F-S01`, `F-W01`, `F-W02` and
`E-04` remain open. SUMMARY engineering consumers are now wired, but no proxy
metric replaces the official source and no external obligation is promoted.

## Authority boundary

`production_write_enabled=false`. No push, PR, merge, deployment, production
migration, real account, Provider call, Level 2, Gate EV, Gate E, or production
side effect occurred. Every new platform write remains default-OFF. Codex does
not issue Controller final closure; the requested next action is independent
Final Closure Verification of 003, 027 and their transitive regressions at the
exact checkpoint.
