# Pull Request checks

**State: PASS_ON_CERTIFIED_IMPLEMENTATION_HEAD**

The workflows define exactly eleven stable job names. Every external action is
pinned to a verified 40-character commit SHA with a version comment, and every
runner is `ubuntu-24.04`.

Draft PR [#5](https://github.com/Corwin-Code/marketops-platform/pull/5)
ran every expected job successfully against the certified implementation and
acceptance-evidence Head. GitHub also emitted a separate successful `CodeQL`
summary check; it is not one of the eleven stable job names.

- `source_head_sha`:
  `b0c8fb218861a36f5f55004c34e9790d8d584166`
- `base_sha`: `3ecc72ae509664ff0550f80ece98d4f50dbb0bc0`
- `tested_merge_sha`: `8169189ee3810ea99d679e22c32a05e27c042c66`
- observed PR state: `OPEN`, `DRAFT`
- observed at: 2026-08-15 Asia/Taipei

| # | Check | Workflow | Conclusion | Run |
| --- | --- | --- | --- | --- |
| 1 | `governance` | `governance.yml` | SUCCESS | [job 94871244250](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504482/job/94871244250) |
| 2 | `backend-build` | `backend.yml` | SUCCESS | [job 94871244365](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504560/job/94871244365) |
| 3 | `architecture-boundary` | `backend.yml` | SUCCESS | [job 94871244370](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504560/job/94871244370) |
| 4 | `backend-integration` | `backend.yml` | SUCCESS | [job 94871244433](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504560/job/94871244433) |
| 5 | `frontend-lint` | `frontend.yml` | SUCCESS | [job 94871244469](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504539/job/94871244469) |
| 6 | `frontend-typecheck` | `frontend.yml` | SUCCESS | [job 94871244512](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504539/job/94871244512) |
| 7 | `frontend-test` | `frontend.yml` | SUCCESS | [job 94871244570](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504539/job/94871244570) |
| 8 | `frontend-build` | `frontend.yml` | SUCCESS | [job 94871244492](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504539/job/94871244492) |
| 9 | `dependency-review` | `security.yml` | SUCCESS | [job 94871244614](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504546/job/94871244614) |
| 10 | `codeql-java` | `security.yml` | SUCCESS | [job 94871244626](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504546/job/94871244626) |
| 11 | `codeql-typescript` | `security.yml` | SUCCESS | [job 94871244648](https://github.com/Corwin-Code/marketops-platform/actions/runs/31832504546/job/94871244648) |

## Security-alert observation

The repository APIs were queried separately after CodeQL completed. A
successful CodeQL job was not treated as proof of zero alerts.

| Surface | Open alerts | High/Critical open alerts |
| --- | --- | --- |
| Code scanning | 0 | 0 |
| Dependabot | 0 | 0 |
| Secret scanning | 0 | Not severity-classified; 0 total |

## Ruleset observation

Ruleset `main-governance` (ID `20734984`) is active on the default branch. It
blocks deletion and non-fast-forward updates, requires a pull request with
resolved review threads, requires the branch to be current, and requires only
the `governance` status check. The remaining ten stable WP-P0-001 job names are
not yet required by that Ruleset. This evidence is observation only: the rework
task did not mutate repository rules. The Human Owner must decide whether and
when to add those checks after their names have proven stable.

The evidence commit that adds this observed record is documentation-only and
therefore creates a new PR Head. Its final-head rerun is reported in the
Controller handoff without recursively changing this file again.
