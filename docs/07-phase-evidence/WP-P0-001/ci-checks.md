# Pull Request checks

**State: PASS_ON_EVIDENCE_HEAD**

Draft PR [#5](https://github.com/Corwin-Code/marketops-platform/pull/5) ran all
eleven required jobs successfully after the final project-foundation rework. The
reviewed starting Head `87d0540697bd3c90db4747b09c8d82f04e6fe946` was not
reused as final evidence.

- `base_sha`: `3ecc72ae509664ff0550f80ece98d4f50dbb0bc0`
- `implementation_head_sha`: `3a7575ad8f3a75b94210dc394f154bf4780283f2`
- `implementation_tree_sha`: `4c4953632a33834052608ec20086c5afe9b791ab`
- `evidence_head_sha`: `6181292eb5408064c4013d4514d3126277927e41`
- `evidence_tree_sha`: `ecfa77a8e5777bb568cbc50c307ada62862654d6`
- `tested_merge_sha`: `22e2f677720181a9ebfd17f192d6f2fcc30e228a`
- observed PR state: `OPEN`, `DRAFT`, mergeable `clean`, not merged
- observed at: 2026-08-17 Asia/Taipei

| # | Required context | Conclusion | Fixed job URL |
| --- | --- | --- | --- |
| 1 | `governance` | SUCCESS | [job 95210010288](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530136/job/95210010288) |
| 2 | `backend-build` | SUCCESS | [job 95210010003](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530054/job/95210010003) |
| 3 | `architecture-boundary` | SUCCESS | [job 95210009998](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530054/job/95210009998) |
| 4 | `backend-integration` | SUCCESS | [job 95210009973](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530054/job/95210009973) |
| 5 | `frontend-lint` | SUCCESS | [job 95210010260](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530097/job/95210010260) |
| 6 | `frontend-typecheck` | SUCCESS | [job 95210010234](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530097/job/95210010234) |
| 7 | `frontend-test` | SUCCESS | [job 95210010226](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530097/job/95210010226) |
| 8 | `frontend-build` | SUCCESS | [job 95210010249](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530097/job/95210010249) |
| 9 | `dependency-review` | SUCCESS | [job 95210010082](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530077/job/95210010082) |
| 10 | `codeql-java` | SUCCESS | [job 95210010125](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530077/job/95210010125) |
| 11 | `codeql-typescript` | SUCCESS | [job 95210010466](https://github.com/Corwin-Code/marketops-platform/actions/runs/31965530077/job/95210010466) |

GitHub also emitted a separate successful
[`CodeQL` summary](https://github.com/Corwin-Code/marketops-platform/runs/95210121333);
it is not one of the eleven required contexts.

## Review, security and public-log observation

| Surface | Observation |
| --- | --- |
| PR review threads | 2 total, 2 resolved, 0 unresolved; thread-aware GraphQL read |
| CodeQL annotations | 0 summary, 0 Java, 0 TypeScript |
| Code scanning | 0 open repository alerts |
| Dependabot | 0 open repository alerts |
| Secret scanning | 0 open repository alerts |
| Dependency Review | SUCCESS; 10 OpenSSF Scorecard warning annotations, each below configured threshold 3 |

The backend integration log contains parseable ECS records with timestamp,
level/logger, service name/version/environment, message, application,
environment and build version. The deliberately contaminated migration emits a
sanitized structured ERROR and rolls back as designed. Searching all backend
run logs for the injected credential, value, host, port, role, SQL, rule and path
markers produced zero hits.

Preserved upstream/transitive output:

- CycloneDX reports unknown schema keywords `meta:enum` and `deprecated`;
- npm reports deprecated transitive `prebuild-install@7.1.3` and `glob@10.5.0`;
- Dependency Review reports the ten Scorecard annotations above;
- checkout emits Git's default-initial-branch hint.

The former Mockito self-attach, implicit annotation-processing, action-runtime
`punycode` warning and `url.parse` warning are absent. CodeQL's own extractor
inventory contains a `punycode.js` path and deprecated-query metadata; neither is
an application/action deprecation warning and neither produced an annotation.

## Ruleset readback

Active Ruleset `main-governance` (ID `20734984`) was independently read after
the checks completed. It has an empty bypass list; blocks deletion and
non-fast-forward updates; requires Pull Requests and resolved review threads; and
enforces strict branch-up-to-date status checks. Its exact required contexts are:

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

Each context remains bound to GitHub Actions integration ID `15368`. Ruleset
`updated_at` remains `2026-08-17T00:18:40.502+08:00`, proving this rework did not
rewrite it: the Human Owner-authorized target state was already present.

The documentation-only commit that records this observation creates a later PR
Head. That Head's self-referential eleven-job rerun, tested merge, annotations,
threads and Ruleset readback are recorded in the Draft PR body and Controller
handoff rather than recursively rewriting this file.
