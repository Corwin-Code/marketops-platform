# Final execution plan — exact pending source checkpoint

This is a command plan, not an execution receipt or a PASS assessment. It was
prepared by reading the five current workflows, Makefile, current verification
scripts and W6/W9/W10 command receipts. Root selects the final checkpoint after
V0070 and its diagnostics. No heavy command was executed to prepare this file.

Use one exclusive Maven/Docker/browser slot. Preserve backend reports, JAR and
SBOM immediately after full verification, before any subsequent command can
write `target`. All layer candidates bind the same checkpoint HEAD/tree and
the same complete source inventory. Generated `build`, `target`, browser
reports, central matrices, manifest and CURRENT_STATE are outputs. Wrapper,
observer and copied adapter bytes are additional execution inputs and receive
their own exact hashes; they do not replace application/test source identity.

The current checked-in collector emits candidates only. Root must review scope,
named results, original failures and resource/dataset bounds before admitting
the final manifest. Non-JUnit commands do not acquire invented zero test counts.

## Publication and evidence order

1. Commit the complete pending source, including validators that support both
   phases, then append-only push the named branch to the existing Draft PR 30.
   This starts the checkpoint's actual CI without requiring COMPLETE first.
2. Execute the local layers below on that exact clean checkpoint. Preserve the
   original command/run/HEAD/tree and complete source inventory for every run.
3. Read the checkpoint's Security jobs and independent aggregate CodeQL result.
   Bind their original source HEAD and tested merge. Compare the runtime,
   tests, validators, infrastructure, workflows and tool configuration bytes at
   the source HEAD and tested merge with the local executed inventory; retain
   the comparison and exact Git identities. A tested merge is not renamed to
   the source HEAD. Source differences invalidate carry-forward.
4. Only after all required evidence passes, populate COMPLETE and regenerate
   the three central views. Update CURRENT_STATE using the admitted complete
   tuple. Commit/push these evidence/document outputs append-only.
5. Collect a separate exact final-commit CI readback: all twelve required
   contexts, aggregate CodeQL, source HEAD, tested merge and its parents/tree,
   workflow run/attempt, job and artifact identities. The manifest's assessed
   source remains the source checkpoint. The final containing commit has its
   own identity; equality of the complete executed-source inventory connects
   them. Never rewrite a checkpoint measurement to claim it ran on that later
   commit. Independent Controller approval remains pending.

## Common invocation

Run from `/Users/chzhengx/Code/personal/marketops-platform` after checkpointing.
The resource collector is current source. The installed Terraform binary was
read-only verified against `/tmp/slice3-finalgate-tools/terraform-installation-receipt.json`:
version 1.14.9, Darwin arm64 binary SHA-256
`e6c2079a84ab7b336a9f8466d58d20fa1adf8a9c9a3bd826917a0c177e85cfaa`;
archive SHA-256 `5bc0b11b7a63c8984a41d82523356df46f7833c2e9651a39a7f8919422de5cde`.
Node is pinned by `.node-version` to 24.19.0; use the root-prepared Node/npm
PATH and `npm_config_cache=/tmp/slice3-finalgate-npm-cache`.

```bash
export SLICE3_REPO="$PWD"
export SLICE3_SOURCE_HEAD="$(git rev-parse HEAD)"
export SLICE3_SOURCE_TREE="$(git rev-parse 'HEAD^{tree}')"
test "$(git branch --show-current)" = feat/SLICE-V1-003-advertising-traffic-efficiency
test -z "$(git status --porcelain=v1 --untracked-files=all)"
export SLICE3_RUNS="$(mktemp -d /tmp/slice3-final-execution.XXXXXX)"
export SLICE3_PUBLIC="$SLICE3_REPO/build/final-gate-r1"
export SLICE3_COLLECT="$SLICE3_REPO/docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/collect_execution.py"
export npm_config_cache=/tmp/slice3-finalgate-npm-cache
export TZ=UTC
export LANG=C.UTF-8
mkdir -p "$SLICE3_PUBLIC"
```

Keep `SLICE3_RUNS` outside the repository so repeated `--require-clean` checks
do not mistake new evidence for source edits. The collector archives selected
repository-relative report globs into this owned output. If an isolated clone
produces a report, copy only the selected public report into `SLICE3_PUBLIC`
before the outer collector completes. Preserve the original receipt and add a
relocation index when later archiving into the repository. Do not overwrite a
candidate directory or reuse old reports after a failed command.

| Layer | Actual scope | Required original evidence |
| --- | --- | --- |
| `backend_full` | Unfiltered `clean verify`, both coverage thresholds and negative gate | All Surefire/Failsafe XML plus actual node reconciliation; JaCoCo root XML/CSV/HTML/exec; full log; JAR/build-info hash; SBOM/licenses; managed/diagnostic/performance outputs; old and mixed capacity JSON; resource receipt |
| `frontend_quality` | Clean npm lock install, lint, format, types, complete Vitest+coverage, negative coverage, bundle canary, final authored-source build | Command log, JSON named test results, original positive coverage, negative-gate log, final dist inventory and build identity |
| `browser` | All six legacy spec files in an owned disposable clone/Compose project, then entire advertising suite in its fresh random database | Separate raw JSON/JUnit reports, screenshots/traces/HTML, source and adapter hashes, exact clone HEAD/tree, container/project/port ownership and cleanup, build identity |
| `governance` | Exact `make governance` including all `test_*.py`, plus finalizer check | Full make log, actual named unittest observer result with source hashes/subtests/counts, observer hashes, finalizer derivation result |
| `infrastructure` | Terraform 1.14.9 format/init/validate and all mock plans for bootstrap/staging/production, Terraform and runtime/telemetry tests | Original init/validate/JSONL logs, summary, lockfile/source hashes, tool installation receipt, mirror hashes, exact clean environment names |
| `migration` | Packaged resolver and isolated images from the exact verified JAR | Script logs, summary/migration inventory, JAR hash equality, image IDs/labels, wrong-hash and missing-envelope refusals |
| `security` | Current npm audit plus exact checkpoint Dependency Review, Java/TS security-and-quality CodeQL and aggregate CodeQL | Audit JSON/exit; run/jobs/checks; both exact analyses and SARIFs; dependency diff; current alert delta/triage; source/merge byte comparison |
| `supply_chain` | Full-run backend SBOM/licenses plus current validated frontend SBOM and complete dependency/license trees | Original SBOM/license files, inventory logs, exact dependency manifests/lock hash and full-run JAR binding |
| `mixed_capacity` | Reuse the actual mixed-capacity test executed inside the successful full backend run | Exact mixed JUnit node, all three mixed JSON files, workload/stage/state/refusal lists, resource receipt, source/tested-merge/run/job/artifact/dataset identities |

## Backend full and mixed capacity

No `-Dtest`, `-Dit.test`, skip flags or test exclusions. Local measurements have
no GitHub job/run identity; leave those fields absent rather than assigning a
future CI run. Root should retain its existing full-run testcase reconciliation
and exact JAR/build-info reader alongside the new collector output.

```bash
python3 "$SLICE3_COLLECT" --layer backend_full --run-id final-backend-r1 \
  --out "$SLICE3_RUNS/backend-full" --expect-head "$SLICE3_SOURCE_HEAD" --require-clean \
  --capture 'backend/marketops-server/target/surefire-reports/*' \
  --capture 'backend/marketops-server/target/failsafe-reports/*' \
  --capture 'backend/marketops-server/target/site/jacoco/**/*' \
  --capture 'backend/marketops-server/target/jacoco.exec' \
  --capture 'backend/marketops-server/target/marketops-server-*.jar' \
  --capture 'backend/marketops-server/target/classes/META-INF/build-info.properties' \
  --capture 'backend/marketops-server/target/*-sbom.json' \
  --capture 'backend/marketops-server/target/licenses/*' \
  --capture 'backend/marketops-server/target/managed-profile-evidence/**/*' \
  --capture 'backend/marketops-server/target/diagnostic-export-evidence/**/*' \
  --capture 'backend/marketops-server/target/performance/**/*' \
  --capture 'backend/marketops-server/target/advertising-capacity-*.json' \
  --capture 'backend/marketops-server/target/advertising-mixed-capacity-*.json' \
  --capture 'build/final-gate-r1/backend/*' \
  -- bash --noprofile --norc -euo pipefail -c '
    mkdir -p "$SLICE3_PUBLIC/backend"
    python3 scripts/validation/collect_slice3_runtime_resources.py --output "$SLICE3_PUBLIC/backend/runtime-resources.json"
    export SLICE3_RUNTIME_RESOURCE_RECEIPT="$SLICE3_PUBLIC/backend/runtime-resources.json"
    export MARKETOPS_EVIDENCE_SOURCE_HEAD_SHA="$SLICE3_SOURCE_HEAD"
    cd backend/marketops-server
    ./mvnw -B -ntp clean -Dmarketops.build.gitCommit="$SLICE3_SOURCE_HEAD" verify
    cd "$SLICE3_REPO"
    bash scripts/verify_coverage_thresholds.sh backend > "$SLICE3_PUBLIC/backend/coverage-negative.log" 2>&1
  '
```

`mixed_capacity` is a second assessed scope of this same actual command/run,
not a fabricated second run. Point its layer evidence at the preserved mixed
JUnit XML and the three `advertising-mixed-capacity-*` files from backend-full.
Retain the original run identity in both layers. Check actual named test result,
capacity assertions, mixed state/stage counts, current plus historical gate
refusal lists and outer resource hash. The failed r6 diagnostic cannot supply
this passing layer. Keep legacy capacity and mixed capacity datasets distinct.
Root JaCoCo `<report>/<counter>` values govern the 80% line / 70% branch gate;
class/CSV aggregation is reported separately, including any shared-line delta.

## Frontend quality

This matches workflow ordering: the canary precedes the final official build.
Preserve the positive coverage before the negative run overwrites its directory.
JSON report output stays in ignored `build`, not a new frontend source file.

```bash
python3 "$SLICE3_COLLECT" --layer frontend_quality --run-id final-frontend-r1 \
  --out "$SLICE3_RUNS/frontend-quality" --expect-head "$SLICE3_SOURCE_HEAD" --require-clean \
  --capture 'build/final-gate-r1/frontend/**/*' \
  --capture 'frontend/marketops-console/dist/**/*' \
  -- bash --noprofile --norc -euo pipefail -c '
    mkdir -p "$SLICE3_PUBLIC/frontend"
    cd frontend/marketops-console
    node --version
    npm --version
    npm ci --include=dev --include=optional
    npm run lint
    npm run format:check
    npm run typecheck
    npm run test:ci -- --reporter=default --reporter=json --outputFile="$SLICE3_PUBLIC/frontend/tests.json"
    cp -R coverage "$SLICE3_PUBLIC/frontend/positive-coverage"
    cd "$SLICE3_REPO"
    bash scripts/verify_coverage_thresholds.sh frontend > "$SLICE3_PUBLIC/frontend/coverage-negative.log" 2>&1
    cd frontend/marketops-console
    npm run verify:bundle
    MARKETOPS_BUILD_COMMIT="$SLICE3_SOURCE_HEAD" VITE_MARKETOPS_API_BASE_URL=http://127.0.0.1:8080 VITE_MARKETOPS_ENVIRONMENT=ci npm run build
  '
```

## Complete browser scope with disposable ownership

The ordinary `playwright.config.ts` discovers all six `tests/browser/*.spec.ts`
files. Its backend command reads `.env.local`; `health-shell.spec.ts` executes
real Compose stop/up against `COMPOSE_PROJECT_NAME` (default `marketops-local`).
Therefore do not invoke this default suite in the shared working checkout.
The existing advertising shell script covers only `tests/advertising-browser`.
Both full suites are required, with no grep/test-name selection.

Use the W6 preserved `legacy-isolation-adapter.ts` unchanged in the disposable
clone. It imports the current original Playwright configuration and only changes
server launch/environment and report locations; it adds no test filters,
assertion changes or fixture patches. It passes the exact source stamp to Maven
and disables inherited Vite environment files. Record its SHA as an additional
execution input. The current six test files, fixture classes and application
sources all come from the checkpoint, including its accepted rework.

Save the following reviewed block as an owned `/tmp` driver, hash it before
execution, and invoke it through the collector with `--layer browser` and
`--capture 'build/final-gate-r1/browser/**/*'`. Include the driver/adapter hashes
in the final execution input record. The block uses only the local file Git
transport and an exact unique Compose project. Run it with the root-prepared
Java 21/Node 24 PATH and no other Maven/browser worker active.

```bash
#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$SLICE3_REPO"
mkdir -p "$SLICE3_PUBLIC/browser"
browser_work="$(mktemp -d /tmp/slice3-final-browser.XXXXXX)"
browser_source="$browser_work/source"
browser_project="slice3-fg-legacy-$(basename "$browser_work" | tr '[:upper:].' '[:lower:]-')"
browser_endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
case "$browser_endpoint" in unix://*) ;; *) exit 2 ;; esac
export DOCKER_HOST="$browser_endpoint"
git clone --no-local --depth 1 --single-branch \
  --branch feat/SLICE-V1-003-advertising-traffic-efficiency \
  "file://$SLICE3_REPO" "$browser_source"
test "$(git -C "$browser_source" rev-parse HEAD)" = "$SLICE3_SOURCE_HEAD"
test "$(git -C "$browser_source" rev-parse 'HEAD^{tree}')" = "$SLICE3_SOURCE_TREE"
compose=(docker compose --project-name "$browser_project" --env-file "$browser_source/.env.local" -f "$browser_source/infra/compose/docker-compose.yml")
cleanup_browser() {
  browser_status=$?
  trap - EXIT INT TERM
  set +e
  "${compose[@]}" down --volumes --remove-orphans > "$SLICE3_PUBLIC/browser/legacy-cleanup.log" 2>&1
  cleanup_status=$?
  docker ps -aq --filter "label=com.docker.compose.project=$browser_project" > "$SLICE3_PUBLIC/browser/legacy-remaining-containers.txt"
  docker volume ls -q --filter "label=com.docker.compose.project=$browser_project" > "$SLICE3_PUBLIC/browser/legacy-remaining-volumes.txt"
  git -C "$browser_source" diff --exit-code > "$SLICE3_PUBLIC/browser/clone-tracked-source-diff.txt"
  source_status=$?
  if [ -d "$browser_source/frontend/marketops-console/test-results" ]; then cp -R "$browser_source/frontend/marketops-console/test-results" "$SLICE3_PUBLIC/browser/test-results"; fi
  if [ -d "$browser_source/frontend/marketops-console/playwright-report" ]; then cp -R "$browser_source/frontend/marketops-console/playwright-report" "$SLICE3_PUBLIC/browser/playwright-report"; fi
  rm -rf -- "$browser_work"
  if [ "$browser_status" -ne 0 ]; then exit "$browser_status"; fi
  if [ "$cleanup_status" -eq 0 ] && [ "$source_status" -eq 0 ] && [ ! -s "$SLICE3_PUBLIC/browser/legacy-remaining-containers.txt" ] && [ ! -s "$SLICE3_PUBLIC/browser/legacy-remaining-volumes.txt" ]; then exit 0; fi
  exit 2
}
trap cleanup_browser EXIT INT TERM
cd "$browser_source"
python3 scripts/init_local_env.py --target all
python3 - <<'PY'
import socket
from pathlib import Path
for port in (8080, 8082, 4173):
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', port))
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
assert port not in (5432, 55436)
path = Path('.env.local')
path.write_text(path.read_text().replace('MARKETOPS_DB_PORT=5432', 'MARKETOPS_DB_PORT='+str(port)))
PY
"${compose[@]}" up -d --wait postgres
"${compose[@]}" ps --format json > "$SLICE3_PUBLIC/browser/legacy-owned-services.json"
export COMPOSE_PROJECT_NAME="$browser_project"
export MARKETOPS_SOURCE_HEAD_SHA="$SLICE3_SOURCE_HEAD"
adapter=docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/browser-w6/legacy-browser-r2/legacy-isolation-adapter.ts
cp "$adapter" frontend/marketops-console/playwright.legacy-isolated.config.ts
shasum -a 256 "$adapter" frontend/marketops-console/playwright.legacy-isolated.config.ts > "$SLICE3_PUBLIC/browser/adapter-sha256.txt"
cd frontend/marketops-console
npm ci --include=dev --include=optional
npx playwright install chromium
PLAYWRIGHT_JSON_OUTPUT_FILE="$SLICE3_PUBLIC/browser/legacy.json" \
PLAYWRIGHT_JUNIT_OUTPUT_FILE="$SLICE3_PUBLIC/browser/legacy.xml" \
npx playwright test --config=playwright.legacy-isolated.config.ts --reporter=list,json,junit,html
cd "$browser_source"
"${compose[@]}" down --volumes --remove-orphans
PLAYWRIGHT_JSON_OUTPUT_FILE="$SLICE3_PUBLIC/browser/advertising.json" \
PLAYWRIGHT_JUNIT_OUTPUT_FILE="$SLICE3_PUBLIC/browser/advertising.xml" \
bash scripts/validation/advertising_browser_isolated.sh --reporter=list,json,junit,html
```

Before invoking this block, preserve a safe local Docker container/network
inventory and reserve the free loopback application ports; do not stop an
existing listener. Afterward compare only the owned/new namespace and verify
the advertising script removed its random container/network. Record the clone's
HEAD/tree and per-file byte equality with the collector's source-before inventory
before execution; recheck tracked source after execution. Generated env files
remain private in the disposable clone and are destroyed, never archived.
The fixture HTTP identity response contains a token: do not archive it. Retain
only safe synthetic identity metadata when needed. Browser rendering/HTTP proof
does not stand in for canonical provider or calculation proof.

## Governance full

`make governance` is exactly the current workflow's two validators plus complete
`unittest discover -s tests -p 'test_*.py'`. The historical observational hook
preserves the original discovery/assertions and records actual methods/subtests.
The old hardcoded `/tmp` hook is gone; create a new owned copy of its two files.

```bash
export SLICE3_GOV_HOOK="$(mktemp -d /tmp/slice3-final-governance-hook.XXXXXX)"
cp docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/pre-w4-governance/sitecustomize.py "$SLICE3_GOV_HOOK/"
cp docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/pre-w4-governance/slice3_unittest_capture.py "$SLICE3_GOV_HOOK/"
python3 "$SLICE3_COLLECT" --layer governance --run-id final-governance-r1 \
  --out "$SLICE3_RUNS/governance" --expect-head "$SLICE3_SOURCE_HEAD" --require-clean \
  --capture 'build/final-gate-r1/governance/*' \
  -- bash --noprofile --norc -euo pipefail -c '
    mkdir -p "$SLICE3_PUBLIC/governance"
    shasum -a 256 "$SLICE3_GOV_HOOK/"*.py > "$SLICE3_PUBLIC/governance/observer-sha256.txt"
    PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$SLICE3_GOV_HOOK:$SLICE3_REPO" SLICE3_NAMED_REPOSITORY="$SLICE3_REPO" SLICE3_NAMED_UNITTEST_OUTPUT="$SLICE3_RUNS/governance-named.json" make governance
    cp "$SLICE3_RUNS/governance-named.json" "$SLICE3_PUBLIC/governance/named-unittest-results.json"
    python3 scripts/validation/finalize_slice3_rework_assessment.py --check > "$SLICE3_PUBLIC/governance/derivation.json"
  '
```

Do not use the old W10 collector's hardcoded `418` expectation. Reconcile actual
discovery/method/framework/subtest counts from this run, zero unexecuted methods,
source stability and every status. COMPLETE later requires only generated-doc
updates; both validators already perform evidence-based phase admission.

## Infrastructure mock plans in a clean environment

Use the existing verified Terraform binary. Copy only the public locked Yandex
provider cache into an owned mirror, never local state, backend configuration,
tokens, CLI config or cloud credentials. `verify_terraform.py` enforces mock
providers, explicit `plan`, `-backend=false` and `-lockfile=readonly`.

```bash
export SLICE3_INFRA_WORK="$(mktemp -d /tmp/slice3-final-infra.XXXXXX)"
mkdir -p "$SLICE3_INFRA_WORK/source" "$SLICE3_INFRA_WORK/tmp" "$SLICE3_PUBLIC/infrastructure"
git archive "$SLICE3_SOURCE_HEAD" infra/yandex scripts tests | tar -xf - -C "$SLICE3_INFRA_WORK/source"
mkdir -p "$SLICE3_INFRA_WORK/mirror/registry.terraform.io/yandex-cloud/yandex/0.220.0"
cp -R infra/yandex/bootstrap/.terraform/providers/registry.terraform.io/yandex-cloud/yandex/0.220.0/darwin_arm64 "$SLICE3_INFRA_WORK/mirror/registry.terraform.io/yandex-cloud/yandex/0.220.0/"
python3 "$SLICE3_COLLECT" --layer infrastructure --run-id final-infrastructure-r1 \
  --out "$SLICE3_RUNS/infrastructure" --expect-head "$SLICE3_SOURCE_HEAD" --require-clean \
  --capture 'build/final-gate-r1/infrastructure/**/*' \
  -- bash --noprofile --norc -euo pipefail -c '
    cp /tmp/slice3-finalgate-tools/terraform-installation-receipt.json "$SLICE3_PUBLIC/infrastructure/"
    cd "$SLICE3_INFRA_WORK/source"
    env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin LANG=C.UTF-8 TMPDIR="$SLICE3_INFRA_WORK/tmp/" PYTHONDONTWRITEBYTECODE=1 \
      /usr/bin/python3 scripts/verify_terraform.py --terraform /tmp/slice3-finalgate-tools/terraform --provider-mirror "$SLICE3_INFRA_WORK/mirror" --output "$SLICE3_PUBLIC/infrastructure/terraform"
    env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin LANG=C.UTF-8 TMPDIR="$SLICE3_INFRA_WORK/tmp/" PYTHONDONTWRITEBYTECODE=1 \
      /usr/bin/python3 -m unittest discover -s tests -p "test_*terraform*.py"
    env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin LANG=C.UTF-8 TMPDIR="$SLICE3_INFRA_WORK/tmp/" PYTHONDONTWRITEBYTECODE=1 \
      /usr/bin/python3 -m unittest discover -s tests -p test_yandex_runtime.py
    env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin LANG=C.UTF-8 TMPDIR="$SLICE3_INFRA_WORK/tmp/" PYTHONDONTWRITEBYTECODE=1 \
      /usr/bin/python3 -m unittest discover -s tests -p test_yandex_telemetry.py
  '
```

Capture tool/mirror hashes and the copied source's equality to the checkpoint
inventory. The installation receipt is an immutable pre-run input; retain that origin
and its hash when copying it during the collected command for archival.
The source archive is temporary and carries no ignored Terraform state. These
tests cover synthetic infrastructure semantics, not a cloud account or apply.

## Packaged migration

Run against the exact full-verified JAR while `target` still holds that artifact.
The script's JAR migration bytes must equal current canonical source, including
every approved forward migration; it also refuses packaged test authority.
It uses a local Unix Docker daemon, empty auth config, minimal image context,
network-disabled build/run and no database connection.

```bash
python3 "$SLICE3_COLLECT" --layer migration --run-id final-migration-r1 \
  --out "$SLICE3_RUNS/migration" --expect-head "$SLICE3_SOURCE_HEAD" --require-clean \
  --capture 'build/final-gate-r1/migration/**/*' \
  -- python3 scripts/verify_migration_artifact.py --output "$SLICE3_PUBLIC/migration"
```

Compare `summary.json.artifactSha256` with the preserved full-run JAR hash,
not a rebuilt or browser-generated artifact. Keep both expected refusal logs
and exact image labels/IDs. No real migration, shared database or deployment is
performed by this layer.

## Supply chain from the verified artifact

The default `collect_supply_chain.py` executes Maven `package -DskipTests`
without the full-run source stamp. Use its frontend-only mode and preserve the
backend SBOM/licenses already produced by full verify. This avoids replacing
the verified JAR and its provenance. Its own INVENTORY says frontend-only;
record the backend's full-run origin in an additive binding, without altering
that original inventory description.

```bash
python3 "$SLICE3_COLLECT" --layer supply_chain --run-id final-supply-chain-r1 \
  --out "$SLICE3_RUNS/supply-chain" --expect-head "$SLICE3_SOURCE_HEAD" --require-clean \
  --capture 'build/final-gate-r1/supply-chain/**/*' \
  -- bash --noprofile --norc -euo pipefail -c '
    mkdir -p "$SLICE3_PUBLIC/supply-chain"
    python3 scripts/collect_supply_chain.py --skip-backend
    cp build/supply-chain/frontend-sbom.json build/supply-chain/frontend-dependencies.json build/supply-chain/frontend-licenses.txt build/supply-chain/INVENTORY.md "$SLICE3_PUBLIC/supply-chain/"
    cp backend/marketops-server/target/marketops-server-sbom.json "$SLICE3_PUBLIC/supply-chain/backend-sbom.json"
    cp backend/marketops-server/target/licenses/backend-license-inventory.txt "$SLICE3_PUBLIC/supply-chain/backend-licenses.txt"
  '
```

Bind backend inventory hashes to the same files archived by backend-full and
that run's JAR/source identity. Bind frontend results to the exact lockfile and
installed tree, preserving schema validation output and all declared licenses.

## Security — local dependency audit plus exact checkpoint CI

Run `npm audit --json` in the prepared frontend directory, recording its actual
exit and JSON. Any vulnerability/network failure remains visible; it cannot
be normalized to zero. The existing Security workflow owns actual compiled
Java security-and-quality CodeQL, TypeScript CodeQL and moderate-or-higher
dependency review. The historical W8/W10 runs are history, not this layer.

The following are read-only retrieval commands after the checkpoint push. Set
the three IDs only from their preceding raw API responses and assert that the
Security run belongs to `SLICE3_SOURCE_HEAD`. The check-run response establishes
the independently completed aggregate CodeQL and required contexts.

```bash
mkdir -p "$SLICE3_PUBLIC/security"
(cd frontend/marketops-console && npm audit --json) > "$SLICE3_PUBLIC/security/npm-audit.json" 2> "$SLICE3_PUBLIC/security/npm-audit.stderr"
gh api "repos/Corwin-Code/marketops-platform/actions/workflows/security.yml/runs?event=pull_request&head_sha=$SLICE3_SOURCE_HEAD&per_page=100" > "$SLICE3_PUBLIC/security/workflow-runs.json"
# Read the response, resolve the exact completed checkpoint run, then set its ID:
test -n "$SLICE3_SECURITY_RUN_ID"
gh api "repos/Corwin-Code/marketops-platform/actions/runs/$SLICE3_SECURITY_RUN_ID" > "$SLICE3_PUBLIC/security/run.json"
gh api --paginate "repos/Corwin-Code/marketops-platform/actions/runs/$SLICE3_SECURITY_RUN_ID/jobs?per_page=100" > "$SLICE3_PUBLIC/security/jobs.json"
gh api "repos/Corwin-Code/marketops-platform/actions/runs/$SLICE3_SECURITY_RUN_ID/logs" > "$SLICE3_PUBLIC/security/job-logs.zip"
test -n "$SLICE3_CHECKPOINT_TESTED_MERGE"
gh api "repos/Corwin-Code/marketops-platform/git/commits/$SLICE3_CHECKPOINT_TESTED_MERGE" > "$SLICE3_PUBLIC/security/tested-merge.json"
gh api --paginate "repos/Corwin-Code/marketops-platform/commits/$SLICE3_CHECKPOINT_TESTED_MERGE/check-runs?per_page=100" > "$SLICE3_PUBLIC/security/check-runs.json"
gh api --paginate "repos/Corwin-Code/marketops-platform/code-scanning/analyses?ref=refs/pull/30/merge&per_page=100" > "$SLICE3_PUBLIC/security/analyses.json"
# Select each analysis by exact commit_oid and language/category, not newest time alone.
test -n "$SLICE3_JAVA_ANALYSIS_ID"
test -n "$SLICE3_TS_ANALYSIS_ID"
gh api -H 'Accept: application/sarif+json' "repos/Corwin-Code/marketops-platform/code-scanning/analyses/$SLICE3_JAVA_ANALYSIS_ID" > "$SLICE3_PUBLIC/security/java.sarif.json"
gh api -H 'Accept: application/sarif+json' "repos/Corwin-Code/marketops-platform/code-scanning/analyses/$SLICE3_TS_ANALYSIS_ID" > "$SLICE3_PUBLIC/security/typescript.sarif.json"
gh api "repos/Corwin-Code/marketops-platform/dependency-graph/compare/08ad7da7d9e75b4ddd1c387a22ac0affba9e1430...$SLICE3_SOURCE_HEAD" > "$SLICE3_PUBLIC/security/dependency-diff.json"
```

Run these actual commands through the root-owned security layer collector after
resolving IDs, retaining each command/exit/time. API pagination may produce
separate JSON documents; preserve raw bytes and parse them as pages, or use
`--slurp` explicitly. Never substitute an invented run/job/artifact ID. Preserve
both exact SARIFs, current code-scanning alert responses and an itemized delta
against prior findings with current source locations/hashes; existing approved
false-positive dismissal metadata is reported separately from open findings.
Do not dismiss alerts or call a neutral/incomplete aggregate successful.
This workflow does not upload an Actions artifact for SARIF; direct API response
bytes are the evidence origin. Archive original bytes after publication scanning
and retain any narrow synthetic-help false-positive triage separately.

## Final admission checks

Every completed candidate must have unchanged executed source and command
exit/results appropriate to its scope. Check captured files were produced by
the named command or explicitly classified as immutable inputs/reused same-run
outputs, not accidental stale reports. Bind all 22 finding dispositions, all
200 original criteria and CV-A–E to the actual named raw nodes/assertions with
their narrow limits. Archive all raw artifacts before cleanup or later rebuilds.
The root controls actual final manifest assembly, phase transition, Git and CI.
Production writes remain false; Ready, merge, force-push, real Providers and
shared/production environments remain outside this execution.
