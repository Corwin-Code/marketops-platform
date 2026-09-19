#!/usr/bin/env bash
# Local reproduction of the four frontend CI jobs at one exact commit, one fresh clone per job.
set -u -o pipefail
SRC=/Users/chzhengx/Code/personal/marketops-platform
SHA=${1:?commit}
TREE=$(git -C "$SRC" rev-parse "$SHA^{tree}")
STAMP=$(date -u +%Y%m%dt%H%M%Sz)
BASE=$SRC/build/pr35-local-verification
EVID=$BASE/frontend-${SHA:0:12}-$STAMP
CLONES=$BASE/clones-${SHA:0:12}-$STAMP
mkdir -p "$EVID" "$CLONES"
export TZ=UTC LANG=C.UTF-8 CI=true npm_config_fund=false npm_config_audit=false
unset JAVA_TOOL_OPTIONS LC_ALL LC_CTYPE LANGUAGE GITHUB_ACTIONS
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.10-zulu/zulu-21.jdk/Contents/Home"
NODE_BIN=$(dirname "$(fnm exec --using=24.19.0 which node)")
export PATH="$JAVA_HOME/bin:$NODE_BIN:$PATH"
export MARKETOPS_SOURCE_HEAD_SHA=$SHA
export COMPOSE_PROJECT_NAME="marketops-browser-local-$STAMP"
printf 'job\tstep\texit\tstarted\tfinished\tworkdir\n' > "$EVID/steps.tsv"
new_clone() {
  local d=$CLONES/$1
  git clone --quiet --no-tags "$SRC" "$d" && git -C "$d" checkout --quiet --detach "$SHA" \
    && [ "$(git -C "$d" rev-parse 'HEAD^{tree}')" = "$TREE" ] && printf '%s\n' "$d"
}
step() {  # step <job> <id> <repo> <workdir> <block>
  local job=$1 id=$2 repo=$3 dir=$4 block=$5 t0 rc
  t0=$(date -u +%FT%TZ)
  ( cd "$repo/$dir" && bash -e -c "$block" ) > "$EVID/$job.$id.log" 2>&1
  rc=$?
  printf 'exit=%s\n' "$rc" >> "$EVID/$job.$id.log"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$job" "$id" "$rc" "$t0" "$(date -u +%FT%TZ)" "$dir" >> "$EVID/steps.tsv"
  return "$rc"
}
skip() { printf '%s\t%s\tSKIPPED\t-\t-\t-\n' "$1" "$2" >> "$EVID/steps.tsv"; }
{
  echo "source_head=$SHA source_tree=$TREE stamp=$STAMP compose_project=$COMPOSE_PROJECT_NAME"
  echo "node=$(node --version) npm=$(npm --version) java=$(java -version 2>&1 | head -1)"
  echo "docker=$(docker version --format '{{.Server.Version}}') compose=$(docker compose version --short)"
  echo "uname=$(uname -mrs)"
  shasum -a 256 "$SRC/frontend/marketops-console/package-lock.json" "$SRC/frontend/marketops-console/.node-version" | sed "s#$SRC/##"
  echo "ports_in_use_before:"; for p in 5432 8080 8082 4173 5173; do lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1 && echo "  $p IN_USE" || echo "  $p free"; done
} > "$EVID/fingerprint-before.txt" 2>&1
FE=frontend/marketops-console

run_job() {  # run_job <job> <repo> "<id>|<workdir>|<block>"...  CI semantics: after a failure, later steps are skipped
  local job=$1 repo=$2 ok=1 s id rest dir block; shift 2
  for s in "$@"; do
    id=${s%%|*}; rest=${s#*|}; dir=${rest%%|*}; block=${rest#*|}
    if [ $ok = 1 ]; then step "$job" "$id" "$repo" "$dir" "$block" || ok=0; else skip "$job" "$id"; fi
  done
  [ $ok = 1 ]
}

L=$(new_clone lint) || exit 90
run_job frontend-lint "$L" "04-npm-ci|$FE|npm ci" "05-lint|$FE|npm run lint" "06-format|$FE|npm run format:check" || true

T=$(new_clone typecheck) || exit 91
run_job frontend-typecheck "$T" "04-npm-ci|$FE|npm ci" "05-typecheck|$FE|npm run typecheck" || true

X=$(new_clone test) || exit 92
if run_job frontend-test "$X" "05-npm-ci|$FE|npm ci" "06-test-ci|$FE|npm run test:ci" \
     "07-coverage-proof|.|bash scripts/verify_coverage_thresholds.sh frontend" \
     "08-playwright|$FE|npx playwright install chromium" \
     "09-business-browser|.|bash scripts/validation/business_browser_isolated.sh"; then business_ok=1; else business_ok=0; fi
step frontend-test 10-backstop-cleanup "$X" . 'if [ -f .env.local ]; then docker compose --project-name "${COMPOSE_PROJECT_NAME:?}" --env-file .env.local -f infra/compose/docker-compose.yml down --volumes --remove-orphans; fi' || true
if [ $business_ok = 1 ]; then step frontend-test 11-advertising-browser "$X" . 'bash scripts/validation/advertising_browser_isolated.sh' || true; else skip frontend-test 11-advertising-browser; fi
mkdir -p "$EVID/frontend-test-artifacts"
cp "$X/$FE/coverage/coverage-summary.json" "$EVID/frontend-test-artifacts/" 2>/dev/null || true
( cd "$X/$FE" && find test-results playwright-report -type f 2>/dev/null | head -400 > "$EVID/frontend-test-artifacts/browser-artifact-list.txt" )

B=$(new_clone build) || exit 93
run_job frontend-build "$B" "04-npm-ci|$FE|npm ci" "05-verify-bundle|$FE|npm run verify:bundle" \
  "06-build|$FE|export MARKETOPS_BUILD_COMMIT=$SHA VITE_MARKETOPS_API_BASE_URL=http://127.0.0.1:8080 VITE_MARKETOPS_ENVIRONMENT=ci; npm run build" \
  "07-inventories|$FE|npm run sbom; npm ls --all --json > npm-dependency-inventory.json" \
  "08-artifacts-present|.|test -d frontend/marketops-console/dist && test -s frontend/marketops-console/npm-dependency-inventory.json && test -s build/supply-chain/frontend-sbom.json" || true

{
  echo "leftover_compose_resources_for_project:"; docker ps -a --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" --format '{{.Names}}'; docker volume ls -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME"
  echo "leftover_ad_browser_containers:"; docker ps -a --filter "name=marketops-ad-browser" --format '{{.Names}}'
  for c in lint typecheck test build; do echo "$c head=$(git -C "$CLONES/$c" rev-parse HEAD) tree=$(git -C "$CLONES/$c" rev-parse 'HEAD^{tree}') tracked_changes=$(git -C "$CLONES/$c" status --porcelain --untracked-files=no | wc -l | tr -d ' ')"; done
} > "$EVID/fingerprint-after.txt" 2>&1
echo "EVIDENCE=$EVID"
cat "$EVID/steps.tsv"
