# SLICE-V1-004 — targeted Final Closure Verification handoff

Status: **25 Controller-closed at ec0; 003/027 rework complete and ready for independent Final Closure Verification**.

This is the current Level 1 local handoff for the two residual findings. The
bound Controller record closed the other 25 at exact Head
`ec0e73b9b9451f63f0cef385aed623d63521596a`, tree
`c6f28fe4fb084d9b1fd6e3fdfdd744edf59fc8b2`. Codex reports 003 and 027 as
`REWORK_COMPLETE_PENDING_INDEPENDENT_FINAL_CLOSURE_VERIFICATION` and claims no
new Controller closure.

## Bound authority and source identity

| Item | Value |
| --- | --- |
| Original reviewed Head / Tree | `f91d107c53a0cf3964ae43c0e8353e0c244a2b59` / `b04fc98b9a3e156cc66e00fe878569306972c638` |
| Required targeted start Head / Tree | `ec0e73b9b9451f63f0cef385aed623d63521596a` / `c6f28fe4fb084d9b1fd6e3fdfdd744edf59fc8b2` |
| Targeted implementation Head / Tree | `6ccaa6c070cb5a786a91d447b474b44926f8837c` / `1da0d52ffdb6d658cddfa9c6699b0c0818dbf9d3` |
| Implementation sole parent | `ec0e73b9b9451f63f0cef385aed623d63521596a` |
| Contract SHA-256 | `5a1761ad614426ad3cba9594f481e293b584d69e96c6d893cf502a5062cfc983` |
| Annex SHA-256 | `c77089fc78183d6289ed0023d4d0dbee915f61e8d917a7f49ba8564b0dc2a48d` |
| Frozen Finding Set SHA-256 | `204f9f6f914ec415694f5a1693f86d2a08e6d92283fdbf9d8dfa4755da7a8843` |
| Controller closure record SHA-256 | `b4b064124f1e6dfec7f25591957b503ba029b04e3e5824b8b30ad5509d9acb4c` |
| Residual conditions SHA-256 | `b03e89548f87cfcc30997b1584df9222bffe2e394838947504cc6af4dd40f489` |
| Owner decisions | 85 preserved; Q085-B preserved |
| Local branch | `codex/slice-v1-004-root-cause-rework-r1` |
| Evidence-only checkpoint | Exact final docs commit/tree is reported out of band; implementation identity above is self-contained |

The original Contract, annex and Frozen Finding Set were not modified. The
Controller material is an input, not remote/environment/Provider authority. The
prior Maker handoff remains byte-preserved at
[historical-maker/controller-handoff.md](rework-r1/historical-maker/controller-handoff.md).

## Residual disposition

- `S4-DR-R1-003`: existing OFFICIAL_SUMMARY intake now retains and validates
  exact method/source/group inputs, then supplies qualified measurement lineage,
  fixed-traffic comparison and formal Outcome consumers. A signed normal-role
  HTTP test against isolated PostgreSQL proves the same formal `0.634` result as
  DETAIL without visit-detail facts; negative group, sum, profile, malformed,
  caller-number and lineage cases remain fail-closed.
- `S4-DR-R1-027`: canonical files now distinguish the 25 prior Controller
  closures from the two residual reworks, preserve accurate historical evidence
  attribution, record exact new commands/artifacts/manifests, and leave the 69
  acceptance rows and external obligations unchanged.

Details: [TARGETED_FINAL_CLOSURE_CHECKPOINT.json](rework-r1/TARGETED_FINAL_CLOSURE_CHECKPOINT.json)
and [TARGETED_FINAL_CLOSURE_HANDOFF.md](rework-r1/TARGETED_FINAL_CLOSURE_HANDOFF.md).

## Migration and targeted verification

- V0001–V0079 remain byte-preserved; V0080–V0124 are 45 forward-only rework
  migrations. V0124 is additive, defaults qualification false and upgrades no
  existing profile.
- Summary → Metric/comparison/Outcome: 21 unit + 9 signed-HTTP/isolated-DB
  integration tests passed, exit 0.
- Migration/schema: 3 unit + 21 integration tests passed through V0124, exit 0.
- Recalculation: 3 unit + 1 isolated-DB integration test passed, exit 0.
- Frontend: 26/26 request tests, typecheck and final format check passed, exit 0.

Raw logs, JUnit XML, frontend JSON, old/new source manifests and exact SHA-256
values are retained under ignored local root
`build/slice-v1-004-final-closure-ec0e73b9-r1/` and inventoried in the checkpoint.

## Historical receipts and non-regression boundary

Earlier terminal backend 1,895 Surefire + 1,363 Failsafe, frontend 418/418, and
browser 24/26 + exact 2/2 receipts remain preserved at their historical sources.
They were not rerun at `6ccaa6c…` and are not a new-Head full pass. The other 25
closures rely on the bounded 15-file application/migration/test delta, exact manifests, additive
default-false migration, and relevant changed/transitive DETAIL, Outcome,
revision, lineage, schema and client regressions. No 31-hour repeat is claimed.

## Acceptance and authority boundary

The 69-row register remains 54 `ENGINEERING_VERIFIED_CONTROLLER_PENDING`, 12
`EXTERNAL_EVIDENCE_PENDING`, and 3 `NOT_APPLICABLE_AT_LEVEL_1`. `F-M01`,
`F-M02`, `F-S01`, `F-W01`, `F-W02` and `E-04` remain open.

`production_write_enabled=false`. No real Provider/account/credential call,
push, PR, merge, sharing, deployment, production migration, Level 2, Gate EV,
Gate E or business side effect occurred. All new platform writes remain
default-OFF.

## Requested Controller action

Perform the next **Final Closure Verification** against 003, 027 and their
transitive regressions at the exact checkpoint. Do not start a new Deep Review.
Codex does not self-approve, authorize transport or issue final closure.
