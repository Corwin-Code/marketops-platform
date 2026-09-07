#!/bin/bash
set -euo pipefail
if [ "${1:-}" != --clean-child ]; then
  exec /usr/bin/env -i HOME=/Users/chzhengx USER=chzhengx LOGNAME=chzhengx \
    PATH='/Users/chzhengx/.sdkman/candidates/java/21.0.10-zulu/bin:/Users/chzhengx/Library/Application Support/fnm/node-versions/v24.19.0/installation/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin' JAVA_HOME=/Users/chzhengx/.sdkman/candidates/java/21.0.10-zulu LANG=C.UTF-8 TZ=UTC CI=true PYTHONDONTWRITEBYTECODE=1 MAVEN_SKIP_RC=true \
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_SYSTEM=/dev/null GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0 \
    /bin/bash --noprofile --norc "$0" --clean-child "$@"
fi
shift
umask 077
export SLICE3_REPO=/Users/chzhengx/Code/personal/marketops-platform SLICE3_SOURCE_HEAD=e278b1e3d8541aeb806e41d6cbef4deac8d16d06 SLICE3_SOURCE_TREE=178132dd32a92e59e320eb100774b5bb9f6fb248
export SLICE3_RUNS=/private/tmp/slice3-final-execution-e278b1e-r6 SLICE3_PUBLIC=/Users/chzhengx/Code/personal/marketops-platform/build/final-gate-r6-e278b1e SLICE3_COLLECT=/Users/chzhengx/Code/personal/marketops-platform/docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/collect_execution.py
export SLICE3_DRIVER_CONFIG=/private/tmp/slice3-final-drivers-e278b1e-r6/CONFIG.json SLICE3_DRIVER_SUPPORT=/private/tmp/slice3-final-drivers-e278b1e-r6/driver_support.py SLICE3_MAVEN_SETTINGS=/private/tmp/slice3-final-drivers-e278b1e-r6/empty-maven-settings.xml
export npm_config_cache=/tmp/slice3-finalgate-npm-cache
cd "$SLICE3_REPO"
python3 "$SLICE3_DRIVER_SUPPORT" --config "$SLICE3_DRIVER_CONFIG" preflight backend_full
finish() { s=$?; trap - EXIT; set +e; python3 "$SLICE3_DRIVER_SUPPORT" --config "$SLICE3_DRIVER_CONFIG" finish backend_full "$s"; f=$?; if [ "$s" -ne 0 ]; then exit "$s"; fi; exit "$f"; }
trap finish EXIT
mkdir -p "$SLICE3_PUBLIC"
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
case "$DOCKER_HOST" in unix://*) ;; *) exit 2;; esac
case "$SLICE3_MAVEN_SETTINGS" in ""|*[!A-Za-z0-9_./-]*) echo "Unsafe Maven settings path refused" >&2; exit 2;; esac
export MAVEN_CONFIG="-s $SLICE3_MAVEN_SETTINGS -gs $SLICE3_MAVEN_SETTINGS"
mkdir -p "$SLICE3_PUBLIC"
python3 "$SLICE3_COLLECT" --layer backend_full --run-id final-backend-r6 \
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
  --capture 'build/final-gate-r6-e278b1e/backend/*' \
  -- bash --noprofile --norc -euo pipefail -c '
    mkdir -p "$SLICE3_PUBLIC/backend"
    cp "$1" "$SLICE3_PUBLIC/backend/empty-maven-settings.xml"
    python3 scripts/validation/collect_slice3_runtime_resources.py --output "$SLICE3_PUBLIC/backend/runtime-resources.json"
    export SLICE3_RUNTIME_RESOURCE_RECEIPT="$SLICE3_PUBLIC/backend/runtime-resources.json"
    export MARKETOPS_EVIDENCE_SOURCE_HEAD_SHA="$SLICE3_SOURCE_HEAD"
    cd backend/marketops-server
    ./mvnw -B -ntp -s "$1" -gs "$1" clean -Dmarketops.build.gitCommit="$SLICE3_SOURCE_HEAD" verify
    cd "$SLICE3_REPO"
    bash scripts/verify_coverage_thresholds.sh backend > "$SLICE3_PUBLIC/backend/coverage-negative.log" 2>&1
  ' slice3-backend-driver "$SLICE3_MAVEN_SETTINGS"
