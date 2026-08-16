# Pull Request checks

**State: PASS**

The workflows define exactly eleven stable job names. Every external action is
pinned to a 40-character commit SHA with a version comment, every runner is
`ubuntu-24.04`, and the refreshed action runtimes use Node 24.

Draft PR [#5](https://github.com/Corwin-Code/marketops-platform/pull/5) ran
every expected job successfully against the certified implementation Head.
GitHub also emitted a separate successful `CodeQL` summary check; it is not one
of the eleven stable job names.

- `source_head_sha`: `58dc6e4bc2eabad19f7d150e465749a85266b7ff`
- `base_sha`: `3ecc72ae509664ff0550f80ece98d4f50dbb0bc0`
- `tested_merge_sha`: `bdc66bf89428e8864fd847c90d03799f4eeaafa7`
- observed PR state: `OPEN`, `DRAFT`
- observed at: 2026-08-17 Asia/Taipei

| # | Check | Workflow | Conclusion | Run |
| --- | --- | --- | --- | --- |
| 1 | `governance` | `governance.yml` | SUCCESS | [job 95190124429](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450215/job/95190124429) |
| 2 | `backend-build` | `backend.yml` | SUCCESS | [job 95190124396](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450181/job/95190124396) |
| 3 | `architecture-boundary` | `backend.yml` | SUCCESS | [job 95190124393](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450181/job/95190124393) |
| 4 | `backend-integration` | `backend.yml` | SUCCESS | [job 95190124452](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450181/job/95190124452) |
| 5 | `frontend-lint` | `frontend.yml` | SUCCESS | [job 95190124357](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450174/job/95190124357) |
| 6 | `frontend-typecheck` | `frontend.yml` | SUCCESS | [job 95190124344](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450174/job/95190124344) |
| 7 | `frontend-test` | `frontend.yml` | SUCCESS | [job 95190124354](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450174/job/95190124354) |
| 8 | `frontend-build` | `frontend.yml` | SUCCESS | [job 95190124331](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450174/job/95190124331) |
| 9 | `dependency-review` | `security.yml` | SUCCESS | [job 95190124304](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450165/job/95190124304) |
| 10 | `codeql-java` | `security.yml` | SUCCESS | [job 95190124251](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450165/job/95190124251) |
| 11 | `codeql-typescript` | `security.yml` | SUCCESS | [job 95190124280](https://github.com/Corwin-Code/marketops-platform/actions/runs/31957450165/job/95190124280) |

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

After explicit Human Owner authorization, Ruleset `main-governance` (ID
`20734984`) was updated and independently read back on 2026-08-17. It remains
active on the default branch with an empty bypass list, blocks deletion and
non-fast-forward updates, requires a pull request with resolved review threads,
and keeps strict branch-up-to-date enforcement. Its exact required-status-check
set is:

```text
governance
backend-build
architecture-boundary
backend-integration
frontend-lint
frontend-typecheck
frontend-test
frontend-build
dependency-review
codeql-java
codeql-typescript
```

Each context is bound to GitHub Actions integration ID `15368`. The Ruleset API
reported `updated_at: 2026-08-17T00:18:40.502+08:00`, and
`gh pr checks 5 --required` reported all eleven successful on unchanged Head
`58dc6e4bc2eabad19f7d150e465749a85266b7ff`. The repository Gate is complete
for Controller re-review; the PR remains Draft and unmerged.

The evidence commit that records this observation is documentation-only and
creates a later PR Head. That Head's rerun is reported in the Controller handoff
and PR body without recursively rewriting this file.
