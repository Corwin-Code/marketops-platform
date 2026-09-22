#!/usr/bin/env bash
# Local reproduction of the backend-build CI job at one exact commit in a fresh clone.
set -u -o pipefail
SRC=/Users/chzhengx/Code/personal/marketops-platform
SHA=${1:?commit}
TREE=$(git -C "$SRC" rev-parse "$SHA^{tree}")
STAMP=$(date -u +%Y%m%dt%H%M%Sz)
BASE=$SRC/build/pr35-local-verification
EVID=$BASE/backend-${SHA:0:12}-$STAMP
CLONE=$BASE/clones-${SHA:0:12}-$STAMP/backend-build
mkdir -p "$EVID" "$(dirname "$CLONE")"
export TZ=UTC LANG=C.UTF-8 MAVEN_OPTS=-Dstyle.color=never CI=true
unset JAVA_TOOL_OPTIONS LC_ALL LC_CTYPE LANGUAGE GITHUB_ACTIONS
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.10-zulu/zulu-21.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
git clone --quiet --no-tags "$SRC" "$CLONE" && git -C "$CLONE" checkout --quiet --detach "$SHA" || exit 90
[ "$(git -C "$CLONE" rev-parse 'HEAD^{tree}')" = "$TREE" ] || exit 91
printf 'job\tstep\texit\tstarted\tfinished\tworkdir\n' > "$EVID/steps.tsv"
step() {
  local id=$1 dir=$2 block=$3 t0 rc
  t0=$(date -u +%FT%TZ)
  ( cd "$CLONE/$dir" && bash -e -c "$block" ) > "$EVID/backend-build.$id.log" 2>&1
  rc=$?
  printf 'exit=%s\n' "$rc" >> "$EVID/backend-build.$id.log"
  printf 'backend-build\t%s\t%s\t%s\t%s\t%s\n' "$id" "$rc" "$t0" "$(date -u +%FT%TZ)" "$dir" >> "$EVID/steps.tsv"
  return "$rc"
}
skip() { printf 'backend-build\t%s\tSKIPPED\t-\t-\t-\n' "$1" >> "$EVID/steps.tsv"; }
{
  echo "source_head=$SHA source_tree=$TREE stamp=$STAMP"
  java -version 2>&1; (cd "$CLONE/backend/marketops-server" && ./mvnw -v 2>&1 | head -3)
  echo "docker=$(docker version --format '{{.Server.Version}}') ncpu_mem=$(docker info --format '{{.NCPU}} {{.MemTotal}}')"
  echo "uname=$(uname -mrs)"
  (cd "$CLONE" && shasum -a 256 backend/marketops-server/pom.xml backend/marketops-server/.mvn/wrapper/maven-wrapper.properties .github/workflows/backend.yml)
} > "$EVID/fingerprint-before.txt" 2>&1
B=backend/marketops-server
ok=1
run() { if [ $ok = 1 ]; then step "$@" || ok=0; else skip "$1"; fi; }
run 03-resources . 'python3 scripts/validation/collect_slice3_runtime_resources.py --output build/slice3-runtime-resources.json'
run 04-clean-verify $B "export SOURCE_HEAD_SHA=$SHA MARKETOPS_EVIDENCE_SOURCE_HEAD_SHA=$SHA MARKETOPS_EVIDENCE_WORKFLOW_JOB=LOCAL-backend-build MARKETOPS_EVIDENCE_ARTIFACT_NAME=LOCAL-backend-test-reports SLICE3_RUNTIME_RESOURCE_RECEIPT=../../build/slice3-runtime-resources.json; ./mvnw -B -ntp clean -Dmarketops.build.gitCommit=\"\${SOURCE_HEAD_SHA}\" verify"
run 05-coverage-rejection-proof . 'bash scripts/verify_coverage_thresholds.sh backend'
run 06-migration-artifact . "python3 scripts/verify_migration_artifact.py --output $EVID/migration-runtime-evidence"
run 07-supply-chain-present $B 'ls target/*-sbom.json && test -s target/licenses/backend-license-inventory.txt'
# Reports are copied whatever happened, as CI uploads them with if: always().
mkdir -p "$EVID/reports"
for d in surefire-reports failsafe-reports site/jacoco; do [ -d "$CLONE/$B/target/$d" ] && cp -R "$CLONE/$B/target/$d" "$EVID/reports/" ; done
cp "$CLONE/$B/target/jacoco.xml" "$EVID/reports/" 2>/dev/null || true
cp "$CLONE/build/slice3-runtime-resources.json" "$EVID/" 2>/dev/null || true
{
  echo "after: head=$(git -C "$CLONE" rev-parse HEAD) tree=$(git -C "$CLONE" rev-parse 'HEAD^{tree}') tracked_changes=$(git -C "$CLONE" status --porcelain --untracked-files=no | wc -l | tr -d ' ')"
} > "$EVID/fingerprint-after.txt"
echo "EVIDENCE=$EVID"; cat "$EVID/steps.tsv"
