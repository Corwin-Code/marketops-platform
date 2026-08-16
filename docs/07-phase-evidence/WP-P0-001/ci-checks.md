# Pull Request checks

**State: PENDING_RECHECK_AFTER_REWORK_PUSH**

Draft PR [#5](https://github.com/Corwin-Code/marketops-platform/pull/5) began this
rework at reviewed Head `87d0540697bd3c90db4747b09c8d82f04e6fe946` with all
eleven required checks successful. Any new push invalidates that review and those
results for final-closure purposes.

- `base_sha`: `3ecc72ae509664ff0550f80ece98d4f50dbb0bc0`
- `implementation_head_sha`: `3a7575ad8f3a75b94210dc394f154bf4780283f2`
- `implementation_tree_sha`: `4c4953632a33834052608ec20086c5afe9b791ab`
- `new_tested_merge_sha`: pending the rework push
- required job URLs/conclusions: pending the rework push
- required PR state: `OPEN`, `DRAFT`, not merged

| # | Required context | Rework conclusion |
| --- | --- | --- |
| 1 | `governance` | PENDING |
| 2 | `backend-build` | PENDING |
| 3 | `architecture-boundary` | PENDING |
| 4 | `backend-integration` | PENDING |
| 5 | `frontend-lint` | PENDING |
| 6 | `frontend-typecheck` | PENDING |
| 7 | `frontend-test` | PENDING |
| 8 | `frontend-build` | PENDING |
| 9 | `dependency-review` | PENDING |
| 10 | `codeql-java` | PENDING |
| 11 | `codeql-typescript` | PENDING |

The final observation must separately query CodeQL summary/annotations, review
threads, Code scanning, Dependabot and Secret Scanning; a successful job alone is
not evidence of zero alerts.

## Ruleset baseline

At task start, active Ruleset `main-governance` (ID `20734984`) already required
all eleven exact contexts above, each bound to GitHub Actions integration ID
`15368`. It also had an empty bypass list, deletion and non-fast-forward
protection, required Pull Requests, required conversation resolution and strict
branch-up-to-date enforcement. The Human Owner's authorization to preserve the
rules and add the required checks therefore required no Ruleset write: the target
state was already present. A post-push readback remains mandatory.

This file will be updated once the first evidence-bearing Head passes. The final
documentation-only Head's rerun will be recorded in the PR body and Controller
handoff to avoid recursively committing its own hash.
