# Pull Request checks

**State: PR_GATE_OPEN**

The workflows define exactly eleven stable job names. Every external action is
pinned to a verified 40-character commit SHA with a version comment, and every
runner is `ubuntu-24.04`.

The Draft PR does not exist at the time of this pre-publication evidence commit,
so inventing run identifiers or conclusions would be false evidence. This table
is replaced with the observed run URL and conclusion for every job after the
branch is pushed and the Draft PR is open.

| # | Check | Workflow | Pre-publication state |
| --- | --- | --- | --- |
| 1 | `governance` | `governance.yml` | DEFINED |
| 2 | `backend-build` | `backend.yml` | DEFINED |
| 3 | `architecture-boundary` | `backend.yml` | DEFINED |
| 4 | `backend-integration` | `backend.yml` | DEFINED |
| 5 | `frontend-lint` | `frontend.yml` | DEFINED |
| 6 | `frontend-typecheck` | `frontend.yml` | DEFINED |
| 7 | `frontend-test` | `frontend.yml` | DEFINED |
| 8 | `frontend-build` | `frontend.yml` | DEFINED |
| 9 | `dependency-review` | `security.yml` | DEFINED |
| 10 | `codeql-java` | `security.yml` | DEFINED |
| 11 | `codeql-typescript` | `security.yml` | DEFINED |

The final record separates `source_head_sha`, `tested_merge_sha`, and
`base_sha`. High/Critical security alerts and the Ruleset state are recorded
separately; a successful CodeQL job is not interpreted as zero alerts.
