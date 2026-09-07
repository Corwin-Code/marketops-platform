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
python3 "$SLICE3_DRIVER_SUPPORT" --config "$SLICE3_DRIVER_CONFIG" preflight infrastructure
finish() { s=$?; trap - EXIT; set +e; python3 "$SLICE3_DRIVER_SUPPORT" --config "$SLICE3_DRIVER_CONFIG" finish infrastructure "$s"; f=$?; if [ "$s" -ne 0 ]; then exit "$s"; fi; exit "$f"; }
trap finish EXIT
mkdir -p "$SLICE3_PUBLIC"
export SLICE3_INFRA_WORK="$(mktemp -d /tmp/slice3-final-infra.XXXXXX)"
mkdir -p "$SLICE3_INFRA_WORK/source" "$SLICE3_INFRA_WORK/tmp" "$SLICE3_PUBLIC/infrastructure"
git archive "$SLICE3_SOURCE_HEAD" infra/yandex scripts tests | tar -xf - -C "$SLICE3_INFRA_WORK/source"
mkdir -p "$SLICE3_INFRA_WORK/mirror/registry.terraform.io/yandex-cloud/yandex/0.220.0"
cp -R infra/yandex/bootstrap/.terraform/providers/registry.terraform.io/yandex-cloud/yandex/0.220.0/darwin_arm64 "$SLICE3_INFRA_WORK/mirror/registry.terraform.io/yandex-cloud/yandex/0.220.0/"
python3 "$SLICE3_COLLECT" --layer infrastructure --run-id final-infrastructure-r6 \
  --out "$SLICE3_RUNS/infrastructure" --expect-head "$SLICE3_SOURCE_HEAD" --require-clean \
  --capture 'build/final-gate-r6-e278b1e/infrastructure/**/*' \
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
