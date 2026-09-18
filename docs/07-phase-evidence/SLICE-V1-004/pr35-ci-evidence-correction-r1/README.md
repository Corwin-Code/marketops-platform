# SLICE-V1-004 — PR #35 bounded CI and evidence correction R1

Status: **correction implemented and verified locally at Level 1; the CI and
CodeQL results of the published Head are reported in the PR #35 description
and the external hand-off, not written back here.** PR #35 stays Draft and
unmerged; Ready and merge need separate exact authority.

This is a bounded CI and evidence consistency correction under the Owner's
issue of Controller envelope 05 A–F. It is not a new Deep Review, not a new
Root-Cause Rework, not a 28th frozen finding, not a next-Slice start and not a
production release.

## Identities

| Identity | Head | Tree |
| --- | --- | --- |
| Formally closed engineering object (historical, never replaced) | `f71d4c8c2bdf5dc6497d7951d1cd5b12b0122f10` | `b8a9e7d0450d24f2b58b95d6b370daf9cd5e5e47` |
| Correction start (published by the remote delivery of 2026-09-17) | `57c5efc4b2c77a3b4d257aeb82ff37b325b21f0e` | `7445281cd6bd29c9fa7b2f6f785afadcde764f27` |
| Correction implementation commit (parent `57c5efc4…`) | `48298206794a81b0640b4a14363198465dbd86c0` | `3531f580200e8802f9a27ab24f7c3049e6b5e3b0` |
| Documentation successor that adds this record (parent `48298206…`) | reported in the PR #35 description and the hand-off | — |

`main` observed at `0f26d0ed387fd0e20c2137b11760ae0bb0f3e5bd`. The
documentation successor changes no file under `backend`, `frontend`, `infra`,
`.github` or `scripts`, so its product source equals the implementation commit.

## Authority

- Controller arbitration `SLICE-V1-004-PR35-REMOTE-DELIVERY-ARBITRATION-57C5EFC4-R1`,
  verdict `TRANSPORT_ACCEPTED_CI_BLOCKED_SCOPED_CORRECTION_REQUIRED`, record
  SHA-256 `f62a8484d030392ad770dff26d7e366efd7ea7652a29ad9d273d1ecfd6cf734b`;
  originals in `controller-arbitration-57c5efc4-r1/` (18 files verified against
  the Controller manifest; the 6 `inputs/` files and 6 historical logs are held
  out of the repository and referenced by SHA-256).
- Owner issue of envelope 05 (SHA-256
  `cdb5748df5842e99926a6154e2269c2cdde34d57753078ab5fe0057e0e931194`) and
  executor naming:
  `docs/08-handoffs/OWNER-SLICE-V1-004-PR35-BOUNDED-CORRECTION-AUTHORIZATION-EVIDENCE.md`.
- Unchanged: Contract `5a1761ad…`, annex `c77089fc…`, Frozen Finding Set
  `204f9f6f…`, Controller R2 FINAL `3c4841ec…`, Owner Formal Closure
  `cf162aaa…`; 27/27 historical closures; 54/12/3 layers; `F-M01`, `F-M02`,
  `F-S01`, `F-W01`, `F-W02`, `E-04` and every prior release obligation;
  `production_write_enabled=false`.

## Root causes and corrections (commit `48298206…`)

| ID | Failing check at `57c5efc4` | Correction |
| --- | --- | --- |
| RC-1 | `backend-build`, `backend-integration` | Eight latest-migration expectations derived from each fixture: `AdvertisingProtectedBaseUpgradeIT` 89 and `"0124"`, `ManagedMigrationRunnerIT` 124 and `"0124"`, `ManagedProfileMigrationIT` `"0124"` ×3 and 114. Resume (8, `"0010"`), `"0002"` and every intentional old pin stay; no SQL migration changed. |
| RC-2 | `frontend-lint` | `ListingConversion.test.tsx:473` uses a block-bodied `waitFor` callback with the same assertion; lint rules unchanged. |
| RC-3 | `frontend-test` | The business-flow Chromium step runs `scripts/validation/business_browser_isolated.sh`: generated configuration, a per-run Compose project (`marketops-browser-<run>-<attempt>` in CI), a dedicated non-5432 loopback port checked against what Compose published, the same `npm run test:browser`, and cleanup of that project only. The always-run backstop names the same project. Every `BrowserFixtureApplication` guard is unchanged; the developer default port is unchanged. |
| RC-4 | `CodeQL` result check | The two concatenated test queries are parameterized on the same connection (alerts #233/#234). |
| — | CodeQL warnings | #642 braces only; #641 and #643–#647 kept in production code with source evidence and new negative regressions. See `CODEQL_DISPOSITION.md`. |

## Records in this directory

| File | Content |
| --- | --- |
| `CI_REPAIR_STATUS.json` | Baseline 12/8/4/0 at `57c5efc4`, root-cause map, and where the published-Head CI is reported |
| `ci-baseline-57c5efc4/` | Baseline check runs, Ruleset rules, synthetic merge, the two failed-step logs (`.log.gz`, decompressed SHA-256 `991ce4e9…` and `fccc4b0b…`), the two CodeQL analysis responses, the Java SARIF (`.sarif.gz`), the SARIF alignment of the nine key alerts and the CodeQL query source identity used for #641 |
| `CODEQL_DISPOSITION.md`, `CODEQL_NOTE_KEYPATH_CHECK.json`, `CODEQL_NOTE_REGISTER.json` | Alert-by-alert disposition and scan identity; the key-path check of 36 security-relevant notes, including one related latent defect outside this envelope; the grouped register of the other 507 open alerts |
| `HISTORICAL_LINT_ERRATUM.md`, `HISTORICAL_FRONTEND_COMMAND_AUDIT.json` | Append-only correction of the historical frontend lint and format statements |
| `CONTROLLER_COVERAGE_CORRECTIONS.md` | CRCF-PR35-01 to 03 with closing evidence, and one residual outside the envelope |
| `local-verification/` | Level 1 receipts of the local runs at the implementation commit, and of the documentation checks of this record (see below) |
| `SHA256SUMS` | Hashes of every file in this directory except itself |

## Local verification at the implementation commit

All five local job reproductions passed at `48298206…` in fresh clones, with
real exit codes and before/after fingerprints (details and the unavoidable
differences from CI in `local-verification/README.md`):

- backend-build: Surefire 1907 / 0 failures, Failsafe 1370 / 0 failures,
  coverage gate met, coverage rejection proof PASS, packaged migration and
  runtime artifact verification PASS, supply-chain files present. The three
  migration suites that failed at `57c5efc4` pass.
- frontend-lint, frontend-typecheck, frontend-build: every step exit 0.
- frontend-test: Vitest 418/418, coverage rejection proof PASS, business-flow
  Chromium 26/26 through the new isolated entry point on a non-5432 port,
  advertising browser 12/12, no leftover Compose resources.

`backend-integration` repeats the same `clean verify` and was left to CI.

## Reported, not changed

- `PR35-NOTE-KEYPATH-01`: the production transport drops the platform-native
  retry headers, so the V0084 provider-wait gate records `ABSENT` instead of
  `UNKNOWN` for a wait expressed only in `item-retry-after` or
  `x-ratelimit-retry`. Latent while description writes are disabled; outside
  envelope 05. Details in `CODEQL_DISPOSITION.md`.
- `scripts/fresh_clone_check.sh` keeps the developer default port and meets
  the same 5432 refusal; it is not a CI job and not named in envelope 05.
- `CURRENT_STATE.md` keeps its validator-pinned fields. Envelope 05 B asks
  that a validator vocabulary that blocks the accurate new state is reported as
  a specific difference, not relaxed. These pinned values no longer describe the
  present and are left unchanged; the correction is recorded in the prose of
  `CURRENT_STATE.md` and in this directory:

  | Field (CURRENT_STATE.md line) | Pinned value | Governance validator | Readiness validator | Present fact |
  | --- | --- | --- | --- | --- |
  | `candidate_state_scope` (322) | `SLICE_V1_004_LEVEL_1_ENGINEERING_FORMALLY_CLOSED_LOCAL_DOCUMENTATION_CHECKPOINT_NOT_PUBLISHED` | `validate_governance.py:533-535` | `validate_production_readiness.py:667` | published to PR #35 on 2026-09-17 |
  | `next_action` (332) | `LEVEL_3_PUBLICATION_DECISION_ON_FORMALLY_CLOSED_CHECKPOINT_NO_NEXT_SLICE_AUTHORIZED` | `validate_governance.py:537-539` | — | the publication decision was taken; a bounded correction is under way |
  | `slice_v1_004_execution_authority` (28) | `LOCAL_DOCUMENTATION_SYNC_ONLY_ENGINEERING_CLOSED` | `validate_governance.py:461` | `validate_production_readiness.py:675` | bounded test, CI configuration and evidence authority is active |
  | `slice_v1_004_remote_write_authority` (76) | `NONE` | `validate_governance.py:489` | `validate_production_readiness.py:676` | a non-rewriting push to the named branch and a Draft PR update are authorized |
  | `authorization` (156) | `CLOSED` | `validate_governance.py:454` | `validate_production_readiness.py:539` | correct for the closed engineering object; the bounded correction has no value in the closed vocabulary |
  | `maker_remote_git_authority` / `remote_git_publication_delegate` (248-249) | `DENIED` / `CODEX` | `validate_governance.py:115-116` | — | the Owner authorized this executor to push this correction |
  | `owner_git_execution_delegate` (352) | `CODEX` | vocabulary `{CODEX, NONE}` at `validate_governance.py:997` | — | the named executor is Opus 5 |

  `active_gate`, `next_authorized_actor` and
  `slice_v1_004_publication_or_next_slice_authority` remain accurate. Changing
  any of the rows above needs a separately authorized, exact validator and test
  update.

## What this correction does not do

No Ready transition, merge, auto-merge, direct write to `main`, force-push,
rebase, branch deletion, Ruleset, protection, permission or Secret change,
query-pack, threshold, lint-rule or coverage relaxation, alert dismissal, SQL
migration change, deployment, production migration, Level 2 environment, real
Provider or account call, Gate EV, Gate E, Pilot, production write or
next-Slice work.
