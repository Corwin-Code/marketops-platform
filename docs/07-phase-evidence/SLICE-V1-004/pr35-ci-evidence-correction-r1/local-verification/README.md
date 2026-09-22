# Local Level 1 verification of the correction commit

Source: `48298206794a81b0640b4a14363198465dbd86c0`, tree
`3531f580200e8802f9a27ab24f7c3049e6b5e3b0`. Every job ran in its own fresh
`git clone` detached at that commit, with the tree checked before the run and
the clone's tracked files checked unchanged afterwards. Each step's real exit
code is appended to its log and listed in `steps.tsv`; after a failure, later
steps are skipped as in CI. Logs are stored as `.log.gz` because raw tool output
carries trailing whitespace. The harness scripts are in `harness/`.

This is development and regression evidence. It does not replace the CI of the
published Head, which is reported in the PR #35 description.

## Results

| Job (CI name) | Steps | Result |
| --- | --- | --- |
| backend-build | runtime resources → `./mvnw -B -ntp clean -Dmarketops.build.gitCommit=… verify` → backend coverage rejection proof → packaged migration resolver and isolated runtime artifacts → supply-chain files present | all exit 0. Surefire 1907 / 0 failures, Failsafe 1370 / 0 failures, "All coverage checks have been met", BUILD SUCCESS in 55:49; rejection proof "backend threshold enforcement PASS"; migration artifact result PASS bound to the correction commit with `uncommittedWorktree: false` |
| frontend-lint | `npm ci` → `npm run lint` → `npm run format:check` | all exit 0 |
| frontend-typecheck | `npm ci` → `npm run typecheck` | all exit 0 |
| frontend-test | `npm ci` → `npm run test:ci` → frontend coverage rejection proof → `npx playwright install chromium` → `bash scripts/validation/business_browser_isolated.sh` → backstop cleanup → `bash scripts/validation/advertising_browser_isolated.sh` | all exit 0. Vitest 29 files, 418/418; statements 84.35 %, branches 76.7 %, functions 84.42 %, lines 85.31 %; business-flow Chromium 26 passed on Compose project `marketops-browser-local-20260918t091144z`, port 63710; advertising browser 12 passed; no leftover container, volume or network |
| frontend-build | `npm ci` → `npm run verify:bundle` → `npm run build` with the CI build variables → `npm run sbom` and `npm ls --all --json` → artifacts present | all exit 0; "bundle isolation PASS" |

The three migration suites that failed at `57c5efc4` passed:
`AdvertisingProtectedBaseUpgradeIT` 1/1, `ManagedMigrationRunnerIT` 2/2,
`ManagedProfileMigrationIT` 3/3; `ListingReworkAuthorizationIT` passed 111/111.
Per-suite counts are in `backend-48298206794a/test-results-summary.json`.

`historical-lint-16eda4bf/` holds the 2026-09-18 observation on a fresh clone
of `16eda4bf…` used by the lint erratum: lint exit 1, format check and typecheck
exit 0.

## Differences from CI that could not be removed

| Item | CI runner | This machine |
| --- | --- | --- |
| Platform | Ubuntu 24.04 x64 | macOS arm64 |
| JDK | Temurin 21.0.12 | Zulu 21.0.10 (Maven 3.9.16 wrapper) |
| Node / npm | 24.19.0 from `.node-version` | 24.19.0 / 11.17.0 through fnm |
| Docker | Docker 28.0.4, about 16 GB | Docker Desktop 29.7.2, 4 CPUs, about 6.2 GB for the VM |
| Maven artifacts | Maven Central | the same coordinates through the local Maven settings mirror |
| Playwright | `install --with-deps` | `install chromium` (browser already present) |
| GitHub identifiers | tested merge, run id and attempt set | not set; evidence labels say `LOCAL-…` |

`backend-integration` runs the same `clean verify` without the build-commit
flag; it was not repeated locally and is covered by CI. `architecture-boundary`
runs the `*ArchitectureTest` classes, which ran inside the backend `clean
verify` above. `infrastructure-validation` (synthetic Terraform plans) was not
run locally and is covered by CI. Checkout of the synthetic merge, uploads,
`dependency-review`, the CodeQL analyses and the CodeQL result check exist only
on GitHub.

## Documentation checks of this record

`documentation-checks/` holds the governance validator, the production
readiness validator, the 424 validator unit tests (the three commands of the
`governance` CI job) and `git diff --check`. They ran on the working tree that
became the documentation commit, with every file of this record in place
except `documentation-checks/` itself and `../SHA256SUMS`, which were written
afterwards. The same checks on the committed documentation Head are reported in
the hand-off.
