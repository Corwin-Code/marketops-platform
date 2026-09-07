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
for output in "$SLICE3_RUNS/governance-r2" "$SLICE3_RUNS/binder-synthetic-r2" "$SLICE3_RUNS/governance-named-r2.json" "$SLICE3_RUNS/assessment-tools-check-r2" "$SLICE3_PUBLIC/governance-r2"; do
  [ ! -e "$output" ] || { printf '%s\n' "REFUSED: retry output already exists: $output" >&2; exit 2; }
done
python3 "$SLICE3_DRIVER_SUPPORT" --config "$SLICE3_DRIVER_CONFIG" preflight governance-r2
finish() { s=$?; trap - EXIT; set +e; python3 "$SLICE3_DRIVER_SUPPORT" --config "$SLICE3_DRIVER_CONFIG" finish governance-r2 "$s"; f=$?; if [ "$s" -ne 0 ]; then exit "$s"; fi; exit "$f"; }
trap finish EXIT
mkdir -p "$SLICE3_PUBLIC"
export SLICE3_GOV_HOOK="$(mktemp -d /tmp/slice3-final-governance-hook.XXXXXX)"
cp docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/pre-w4-governance/sitecustomize.py "$SLICE3_GOV_HOOK/"
cp docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/pre-w4-governance/slice3_unittest_capture.py "$SLICE3_GOV_HOOK/"
python3 "$SLICE3_COLLECT" --layer governance --run-id final-governance-r6 \
  --out "$SLICE3_RUNS/governance-r2" --expect-head "$SLICE3_SOURCE_HEAD" --require-clean \
  --capture 'build/final-gate-r6-e278b1e/governance-r2/**/*' \
  -- bash --noprofile --norc -euo pipefail -c '
    mkdir -p "$SLICE3_PUBLIC/governance-r2"
    shasum -a 256 "$SLICE3_GOV_HOOK/"*.py > "$SLICE3_PUBLIC/governance-r2/observer-sha256.txt"
    PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$SLICE3_GOV_HOOK:$SLICE3_REPO" SLICE3_NAMED_REPOSITORY="$SLICE3_REPO" SLICE3_NAMED_UNITTEST_OUTPUT="$SLICE3_RUNS/governance-named-r2.json" make governance
    cp "$SLICE3_RUNS/governance-named-r2.json" "$SLICE3_PUBLIC/governance-r2/named-unittest-results.json"
    python3 scripts/validation/finalize_slice3_rework_assessment.py --check > "$SLICE3_PUBLIC/governance-r2/derivation.json"
    python3 docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/reconcile_measurements.py --check > "$SLICE3_PUBLIC/governance-r2/historical-measurement-check.log"
    cp docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/CV-E-MEASUREMENT-RECONCILIATION.json "$SLICE3_PUBLIC/governance-r2/historical-measurement-reconciliation.json"
    python3 docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/assessment_tools/check_structured_adapter.py --out "$SLICE3_RUNS/assessment-tools-check-r2" > "$SLICE3_PUBLIC/governance-r2/assessment-tools-check.log"
    cp "$SLICE3_RUNS/assessment-tools-check-r2/STRUCTURED-ADAPTER-SAFETY-CHECKS.json" "$SLICE3_PUBLIC/governance-r2/assessment-tools-checks.json"
    python3 "$SLICE3_DRIVER_SUPPORT" --config "$SLICE3_DRIVER_CONFIG" binder
    cp -R "$SLICE3_RUNS/binder-synthetic-r2" "$SLICE3_PUBLIC/governance-r2/binder-synthetic-r2"
  '
