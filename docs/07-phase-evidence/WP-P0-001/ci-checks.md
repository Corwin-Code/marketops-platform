# Pull Request checks

**State: OWNER_RULESET_ACTION_REQUIRED**

The workflows define exactly eleven stable job names. Every external action is
pinned to a 40-character commit SHA with a version comment, every runner is
`ubuntu-24.04`, and the refreshed action runtimes use Node 24.

Draft PR [#5](https://github.com/Corwin-Code/marketops-platform/pull/5) ran
every expected job successfully against the certified implementation Head.
GitHub also emitted a separate successful `CodeQL` summary check; it is not one
of the eleven stable job names.

- `source_head_sha`: `4001a8d2717739967bf48a71c6a4f82bd2e5c50f`
- `base_sha`: `3ecc72ae509664ff0550f80ece98d4f50dbb0bc0`
- `tested_merge_sha`: `05e5cdd957603ee4a946dd4085c666dae23b00c6`
- observed PR state: `OPEN`, `DRAFT`
- observed at: 2026-08-16 Asia/Taipei

| # | Check | Workflow | Conclusion | Run |
| --- | --- | --- | --- | --- |
| 1 | `governance` | `governance.yml` | SUCCESS | [job 95189101608](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026229/job/95189101608) |
| 2 | `backend-build` | `backend.yml` | SUCCESS | [job 95189101920](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026226/job/95189101920) |
| 3 | `architecture-boundary` | `backend.yml` | SUCCESS | [job 95189101996](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026226/job/95189101996) |
| 4 | `backend-integration` | `backend.yml` | SUCCESS | [job 95189101897](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026226/job/95189101897) |
| 5 | `frontend-lint` | `frontend.yml` | SUCCESS | [job 95189101632](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026227/job/95189101632) |
| 6 | `frontend-typecheck` | `frontend.yml` | SUCCESS | [job 95189101688](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026227/job/95189101688) |
| 7 | `frontend-test` | `frontend.yml` | SUCCESS | [job 95189101612](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026227/job/95189101612) |
| 8 | `frontend-build` | `frontend.yml` | SUCCESS | [job 95189101747](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026227/job/95189101747) |
| 9 | `dependency-review` | `security.yml` | SUCCESS | [job 95189101993](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026283/job/95189101993) |
| 10 | `codeql-java` | `security.yml` | SUCCESS | [job 95189102073](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026283/job/95189102073) |
| 11 | `codeql-typescript` | `security.yml` | SUCCESS | [job 95189101990](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957026283/job/95189101990) |

## Review and security observation

The repository and PR APIs were queried separately after CodeQL completed. Job
success was not treated as proof of zero alerts.

| Surface | Observation |
| --- | --- |
| PR review threads | 2 total, 2 resolved, 0 unresolved; both original Advanced Security findings closed after the new CodeQL run |
| CodeQL PR annotations | 0 summary, 0 Java and 0 TypeScript annotations on this Head |
| Code scanning | 0 open repository alerts |
| Dependabot | 0 open repository alerts |
| Secret scanning | 0 open repository alerts |
| Dependency Review | SUCCESS; 10 displayed warning annotations, all OpenSSF Scorecard notices below the configured informational threshold |

Public logs contain none of the injected credential/host/port/role/SQL markers.
The prior Node-20 action runtime, action `punycode`/`url.parse`, Mockito
self-attach and implicit annotation-processing warnings are absent. Upstream
output still reports two CycloneDX schema meta-keyword warnings, two transitive
npm deprecation notices and non-blocking OpenSSF scorecard notices; these are
recorded rather than misreported as zero warnings.

## Ruleset observation

Ruleset `main-governance` (ID `20734984`) is active on the default branch. It
blocks deletion and non-fast-forward updates, requires a pull request with
resolved review threads, and keeps strict branch-up-to-date enforcement. Its
required-status-check set is currently only:

```text
governance
```

The required final set is the eleven job names in the table. Controller has
authorized only the Human Owner to add the remaining ten; Codex did not mutate
the Ruleset. Therefore repository-gate completion remains
`OWNER_RULESET_ACTION_REQUIRED` even though all eleven jobs are green.

The evidence commit that records this observation is documentation-only and
creates a later PR Head. That Head's rerun is reported in the Controller handoff
and PR body without recursively rewriting this file.
