# Erratum — historical frontend lint and format statements for the final SLICE-V1-004 source

Append-only correction under Controller arbitration
`SLICE-V1-004-PR35-REMOTE-DELIVERY-ARBITRATION-57C5EFC4-R1` (decision Q3,
`03_HISTORICAL_EVIDENCE_CORRECTION.md`). No earlier record is edited: the
statements below keep their bytes, and this file limits how they may be used
from now on. It draws no conclusion that a command did not run, that output was
fabricated, or that any other historical suite is invalid.

## Statements corrected

| Record | Git blob at `57c5efc4` | SHA-256 | Statement (quoted under 15 words) |
| --- | --- | --- | --- |
| `docs/07-phase-evidence/SLICE-V1-004/executable-evidence.md` line 44 | `61f6c47cb459d80494e58d2b2fc92ef2e2bb7515` | `7a2719b8dcf016eb3a7f7f8da8f9d280b00df106264d459aad273a47db3ddd38` | "lint, format check, typecheck, `test:ci`, build and bundle verification all exit 0" |
| `docs/07-phase-evidence/SLICE-V1-004/rework-r1/executable-evidence.md` line 50 | `0f55e7759f27a4eb4dd523dfc4cd5a3fa5546224` | `a92f6871e4354bd7acf571226b0c501fc90c9a70b34d9860bf9ec457bfae1936` | "lint, format check, typecheck, `test:ci`, build and bundle checks all exit 0" |
| `docs/07-phase-evidence/SLICE-V1-004/rework-r1/FINAL_LEVEL1_LOCAL_VERIFICATION.json` lines 151–153, 181–183 | `cce4acb69e0eb74cb2da2c6b1d3e59b81c153fc3` | `4784cb9a3495a1132a114e4b85a1a34eee2573a9a62b9a897204e85a1f6a2798` | `"lint": "PASS"`, `"format_check": "PASS"`, `"typecheck": "PASS"` with log hashes |

Downstream carriers of the same statements, also unchanged:
`rework-r1/finding-progress.json` lines 60–62, the `CURRENT_STATE.md` prose
that reports "Backend and frontend terminal matrices passed" for Head
`16eda4bf…`, and the Controller R2 FINAL record
(`formal-closure-f71d4c8c-r2/controller-r2-final/01_CLOSURE_RECORD.json`
`inherited_frontend` / `historical_full_backend_frontend_browser`, record
SHA-256 `3c4841ec5d2f32c01d4b8fda126a266da9a4d0ef422ff569be3973540c0751b0`).

## Facts

1. **The original lint log exists.** `frontend-lint.log`, 61 bytes, SHA-256
   `593a58dc506c6936a45297a0670cc1e2a7bca4483499f3f0fe6eafb7dc6bf2c1`, in the
   original `slice-v1-004-final-evidence` archive and pinned at
   `FINAL_LEVEL1_LOCAL_VERIFICATION.json` line 181. It holds only the npm
   script banner and `eslint . --max-warnings 0`; it records no exit code, Node
   version or source identity. The same bytes appear in many other receipts, so
   the hash identifies the output text, not one execution.
2. **The source changed between the lint run and the final matrix.** The three
   original source manifests are in
   `controller-arbitration-57c5efc4-r1/evidence/historical-frontend/`. `before`
   (`c5fa7f9d…`) differs from `final-before-matrix` and `after-matrix` (both
   `ddf0d894…`) in exactly one path,
   `frontend/marketops-console/src/__tests__/ListingConversion.test.tsx`:
   `a171da22…` → `5e916d70…`. The final hash equals the committed file at
   `16eda4bf…`, `f71d4c8c…` and `57c5efc4…` (blob `77527b47…`). The lint,
   format-check and typecheck logs carry file times inside the `before` →
   `final` interval; no lint, format or typecheck log exists after the change.
   File times are supporting information only, not an execution clock.
3. **The committed final file fails lint deterministically.**
   - PR #35 CI, run `35199185673`, job `frontend-lint` (`105129566253`),
     2026-09-17, Node 24.19.0 with `npm ci`: `473:25
     @typescript-eslint/no-confusing-void-expression`, exit 1.
   - Observed again on 2026-09-18 on a fresh clone of `16eda4bf…` (tree
     `98b9ff7d…`) with Node 24.19.0, npm 11.17.0 and `npm ci`: lint exit 1
     with the same single error; `format:check` exit 0; `typecheck` exit 0.
     These are today's observations of that source, not historical results.
   - Line 473 entered with the `a171da22…` → `5e916d70…` change (`3626e776`).
     ESLint configuration, `package.json`, `package-lock.json` and
     `tsconfig.json` are unchanged from `3626e776` to `57c5efc4`.

## Corrected statement

For the final SLICE-V1-004 frontend source (`16eda4bf…` through `57c5efc4…`):

| Command | Status for the final source |
| --- | --- |
| lint | **Historical PASS withdrawn.** The final source fails lint deterministically. File times suggest the historical log predates the final file; which working-tree state that invocation used, and whether its error output was captured, is not established. |
| format check | **Not proven for the final source by the historical run**, whose file time falls before the final file was written. A pass on the same source was observed on 2026-09-18; it is not backdated. |
| typecheck | **Supported with limit**, indirectly through the final build and bundle runs (`tsc --noEmit && vite build`); by file time the standalone log predates the final file. |
| `test:ci` 418/418, build, bundle | **Supported with limit.** They ran between two identical final manifests; no exit code or Node version was captured. |

See `HISTORICAL_FRONTEND_COMMAND_AUDIT.json` for the per-command record.

## What replaces the withdrawn lint evidence

The lint defect is corrected in commit `48298206794a81b0640b4a14363198465dbd86c0`
(block-bodied `waitFor` callback, same assertion, unchanged lint rules). The
passing lint and format runs belong to that commit and to the PR #35 CI of the
published correction Head. They are not backdated to `16eda4bf…` or to the
formally closed Head `f71d4c8c…`, whose closure remains a historical fact.
