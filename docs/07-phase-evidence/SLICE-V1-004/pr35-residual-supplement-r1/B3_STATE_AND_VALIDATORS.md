# B3 — Exact current state and validator binding

Residual: `PR35-CURRENT-STATE-SCOPE` (Controller record, `residuals`): the validator-pinned fields of
`CURRENT_STATE.md` could not express an active bounded task. Scope: supplement §B3.

## Three layers

| Layer | Fields | Rule |
|---|---|---|
| Historical engineering | `authorization: CLOSED`, formal closure of `f71d4c8c…`, 27/27, 54/12/3 | unchanged; `FULL_SCOPE_IMPLEMENTATION` is not reopened |
| Bounded current task | 16 `slice_v1_004_pr35_supplement_*` fields; `slice_v1_004_execution_authority`, `slice_v1_004_remote_write_authority`, `candidate_state_scope`, `next_authorized_actor`, `next_action` | pinned exactly by both validators; bound to the Owner statement, the Controller record and the issued scope |
| Global delegation (DR-0004) | `owner_git_execution_delegate` / `remote_git_publication_delegate` = `CODEX` | unchanged; the named executor may never appear there |

`next_authorized_actor` is `CONTROLLER` and `next_action` is the limited residual verification, because
the supplement ends handed back in Draft/Unmerged.

## Validator changes (additive; no check removed, no enum widened)

`scripts/validate_governance.py`
- Constants for the statement, the Controller record, the scope, the executor, the branch, the PR and the
  start Head/Tree; the new values and 16 fields in `V1_ACTIVE_STATE`.
- `validate_slice4_pr35_supplement_authority`: SHA-256 of the three authority files; the statement must state
  every pinned identity and the prohibitions; the Controller record must name the start Head/Tree and the
  proposal and must grant nothing itself; any metadata key containing `pr35` must be a registered field; the
  executor may not be a global delegate; while the supplement's remote authority is current, `authorization`,
  `merge_authorization`, `maker_remote_git_authority`, `production_write_enabled`, Level 2 and Gate fields and
  the Draft state must hold their exact values.
- `validate_current_state_metadata_form`: exactly one fenced YAML block, and every line in it a plain
  `key: value`. This closes an older gap found by review: a duplicate key written with a space or quotes before
  the colon, or a second YAML block, was read by YAML but skipped by the exact-value checks.

`scripts/validate_production_readiness.py`
- `COMPLETION_STATE_TOKENS`: four values re-pinned and five supplement tokens added.
- B2 bindings (`FRESH_CLONE_*`, `ISOLATED_BROWSER_*`), described in `B2_FRESH_CLONE_ENTRY.md`.

## Negative tests (Controller 05 C.6)

`tests/test_validate_governance.py::Slice4Pr35SupplementAuthorityTests` (7):

| Case | Mutation | Rejected by |
|---|---|---|
| False pass | Controller record bytes changed to grant `merge` | authority hash; "cannot itself grant authority" |
| Old or wrong Head | record or `start_head` set to `57c5efc4…` | reviewed-Head check; pinned field |
| No authorization | statement missing; statement rewritten to another executor | authority hash; statement tokens |
| Widened executor or permission | delegates set to the executor; executor `ANY_EXECUTOR`; PR state `READY_FOR_REVIEW`; `merge_authorization`; `maker_remote_git_authority`; an unregistered supplement field | pinned fields; delegate rule; registered-field rule |
| Shadowing key forms | spaced, quoted and upper-case duplicates; other-prefix `pr35` fields; a second YAML block | metadata form; registered-field rule |
| Production closure lifted | `production_write_enabled: true`; `authorization: FULL_SCOPE_IMPLEMENTATION`; Gate EV granted | pinned fields |

`tests/test_validate_production_readiness.py::FreshCloneEntryContractTests` (9) covers B2.

## Also recorded

- The Owner statement, its authorization evidence, and byte-exact copies of the Controller's 17 decision,
  scope, evidence and template files. The Controller package's `source/`, `probe/`, `inputs/` and
  `verify_materials.py` are not copied (source files of another tree, a probe, the earlier handoff ZIP and a
  checker); the copied `MANIFEST.sha256` therefore reports those 10 entries as absent and every copied entry OK.
- The CURRENT_STATE prose says only what the validators check, and that the prose itself is not checked.
