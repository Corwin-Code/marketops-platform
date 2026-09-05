# Current 12-gate execution review

Read-only review of the current workflows and their invoked scripts. No job was run by this review, and no remote check is claimed for the final R1 Head. `ci-gates-review.json` records the inspected file hashes and exact commands.

## Required contexts

| Context | Required execution |
| --- | --- |
| `governance` | `python3 scripts/validate_governance.py`; `python3 scripts/validate_production_readiness.py`; `python3 -m unittest discover -s tests -p 'test_*.py'` |
| `backend-build` | `python3 scripts/validation/collect_slice3_runtime_resources.py --output build/slice3-runtime-resources.json`; `./mvnw -B -ntp clean -Dmarketops.build.gitCommit="${SOURCE_HEAD_SHA}" verify`; `bash scripts/verify_coverage_thresholds.sh backend`; `python3 scripts/verify_migration_artifact.py` |
| `architecture-boundary` | `./mvnw -B -ntp -Dtest='*ArchitectureTest' -DfailIfNoTests=true test` |
| `backend-integration` | `python3 scripts/validation/collect_slice3_runtime_resources.py --output build/slice3-runtime-resources.json`; `./mvnw -B -ntp clean verify` |
| `frontend-lint` | `npm ci`; `npm run lint`; `npm run format:check` |
| `frontend-typecheck` | `npm ci`; `npm run typecheck` |
| `frontend-test` | `npm ci`; `npm run test:ci`; `bash scripts/verify_coverage_thresholds.sh frontend`; `npx playwright install --with-deps chromium`; `make env-init`; `make up`; `npm run test:browser`; `docker compose --project-name marketops-local --env-file .env.local -f infra/compose/docker-compose.yml down --volumes --remove-orphans`; `bash scripts/validation/advertising_browser_isolated.sh` |
| `frontend-build` | `npm ci`; `npm run build`; `npm run verify:bundle`; `npm run sbom`; `npm ls --all --json > npm-dependency-inventory.json` |
| `dependency-review` | `actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294` |
| `codeql-java` | `CodeQL init java-kotlin, manual build, security-and-quality`; `./mvnw -B -ntp -DskipTests clean package`; `CodeQL analyze category /language:java-kotlin` |
| `codeql-typescript` | `CodeQL init javascript-typescript, build-mode none, security-and-quality`; `CodeQL analyze category /language:javascript-typescript` |
| `infrastructure-validation` | `Install checksum-pinned Terraform 1.14.9 linux_amd64`; `python3 scripts/verify_terraform.py`; `python3 -m unittest discover -s tests -p 'test_*terraform*.py'`; `python3 -m unittest discover -s tests -p 'test_yandex_runtime.py'` |

Use Java 21 and the pinned Node 24/npm 11 toolchain. A complete backend `verify` includes unit tests, integration tests, coverage, SBOM and license inventory; targeted `failsafe:verify` is not a substitute. Preserve reports before another `clean`. The coverage threshold scripts also demonstrate the threshold failure path.

## Publication and local boundaries

Feature-branch push alone does not start these jobs. The authorized Draft PR starts `pull_request` runs; all required results must refer to its exact final Head. PR jobs normally test a merge checkout, so record both the PR source SHA and tested merge SHA. Concurrent updates cancel older runs; cancelled, skipped and stale results cannot count as passing gates.

The workflows upload test/build artifacts and CodeQL security events. Dependency review is configured with `comment-summary-in-pr: on-failure`, although its PR permission is read-only; a comment attempt may therefore be refused. No workflow changes Git, marks Ready, merges, deploys a site, publishes an npm/Maven package, or applies infrastructure.

**Do not copy the generic frontend compose commands onto this workstation.** The workflow uses `marketops-local` and `down --volumes --remove-orphans` on its fresh CI runner. On this workstation that name belongs to an existing forbidden-to-touch environment. Local advertising verification uses `scripts/validation/advertising_browser_isolated.sh`, which generates a unique synthetic database/network and cleans only that owned namespace. It does not read repository `.env.local`.

The migration-artifact verifier builds uniquely SHA-tagged local images from the application JAR and a restricted context, then performs network-disabled refusal checks. It does not connect to a database. The Terraform verifier drops inherited cloud credential variables, initializes with `-backend=false`, enforces immutable lockfiles and permits only mock-provider `command = plan` tests. It never applies or reads real cloud state.

## Exact final evidence to collect

- exact final local commit and tree.
- exact branch remote head and PR URL/number; draft=true.
- source PR head SHA and workflow revision.
- actual tested merge SHA for PR jobs, distinct from source head.
- all 12 check contexts with run ID, attempt, job ID/URL, conclusion and timestamps.
- artifact IDs/names and downloaded SHA-256 including reports and runtime evidence.
- cancelled/skipped/stale results listed separately and never counted as PASS.
- production_write_enabled=false and external Gate/Provider obligations remain deferred.
- downloaded advertising-capacity-receipt.json from each executing backend job; verify source/tested SHA, run/attempt/job/artifact and actual workload/timing assertions.
- all three capacity JSONs plus runtime-resource JSON: verify dataset/source/resource SHA-256 links and distinguish host/JVM resources from Docker VM limits.

Both backend jobs collect safe host/Docker resource fields before Maven and upload three capacity JSONs (receipt, actual synthetic dataset, source-input hashes) plus `build/slice3-runtime-resources.json`, under `always()` with 14-day retention. The resource collector asks Docker only for CPU count, memory and server version; it does not inspect container environments, credentials or shared databases. The dataset contains only the test-generated synthetic organization. Inspect every linked SHA-256 and actual timing/workload assertion. Host/JVM resource values and Docker VM limits are recorded separately. Missing upload files only warn, so artifact existence alone does not prove capacity ran or passed. The workflow injects source/tested/run/job/artifact variables, while the current helper records its actual GITHUB metadata, measured checkout and file hashes; final source Head/job/artifact binding must also use the authoritative workflow/run API.

No independent Controller verdict, Ready, merge or production enablement is produced by these engineering checks.
