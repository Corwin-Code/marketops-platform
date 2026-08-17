# Pull Request checks

**State: FINAL GITHUB EVIDENCE IS NON-RECURSIVE PR HANDOFF DATA**

Draft PR [#5](https://github.com/Corwin-Code/marketops-platform/pull/5)
started this micro-closure at reviewed source Head
`fa2a0614c3ba4e3e6110c738b5d6687c10320442`, tree
`e08501ead84a4852732bb9f1c412a0bf306620df`, with temporary tested merge
`7d97fdad4921025caf50dfd78406dd2842092c28`. Its eleven green jobs are
superseded and are not final evidence for F-13 through F-16.

- `base_sha`: `3ecc72ae509664ff0550f80ece98d4f50dbb0bc0`
- `implementation_head_sha`: `a971717a658e9db315c5e6c3e03e5b5899e48f65`
- `implementation_tree_sha`: `2f227c35b515a21b8e412a0adea59838dbfc5af8`
- final evidence Head and tree: Draft PR handoff
- final temporary tested merge SHA: Draft PR handoff
- final job URLs, annotations, alerts and review-thread audit: Draft PR handoff

The evidence commit necessarily creates a Head later than the implementation
Head certified by Fresh Clone. Recording that final Head inside itself would
recurse forever, so the immutable GitHub run and its audit are recorded in the
Draft PR body and Controller handoff only. No older green run is promoted.

## Final required contexts

The final evidence Head must pass these exact eleven contexts:

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

For `frontend-test`, the workflow supplies
`github.event.pull_request.head.sha || github.sha` as the explicit authored
source identity while the default checkout remains GitHub's temporary merge.
The shared resolver is called by both Playwright configuration and the browser
assertion. Final handoff therefore records three distinct facts:

- `source_head_sha` equals the final branch Head;
- `tested_merge_sha` equals GitHub's temporary merge commit and is not published;
- the asserted footer is `Console 0.1.0 (<source_head_sha>)`.

For `backend-integration`, actual Spring Boot ECS output must retain application,
environment and build identity, contain exactly one root `correlationId`, use the
request MDC value for application/request events, and use deterministic `none`
for events without MDC. The local profile remains one readable line.

## Security and review audit boundary

After all final required jobs settle, the handoff records thread-aware unresolved
review count, CodeQL annotations for the summary/Java/TypeScript checks, open
Code Scanning, Dependabot and Secret Scanning alerts, and Dependency Review
annotations. Public CI logs are searched only for synthetic sensitive markers;
raw local logs, user paths, generated credentials and Testcontainers endpoints
are never committed.

## Ruleset readback

Ruleset `main-governance` (ID `20734984`) already contained the exact eleven
contexts above at task start, each bound to GitHub Actions integration ID `15368`.
It also had an empty bypass list, deletion and non-fast-forward protections,
required Pull Requests, required conversation resolution and strict up-to-date
checks. Its `updated_at` was `2026-08-17T00:18:40.502+08:00`.

The Human Owner authorized preserving the Ruleset and adding the contexts. Since
the authorized target state was already active, this task performs no Ruleset
write. Final post-CI readback and the unchanged timestamp belong in the PR
handoff; the feature branch remains Draft and is not merged.
