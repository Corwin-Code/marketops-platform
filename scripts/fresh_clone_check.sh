#!/usr/bin/env bash
# Verify the current commit from an isolated clone whose path exercises shell quoting.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-full}"

log() { printf 'fresh-clone: %s\n' "$1"; }
fail() { printf 'fresh-clone: FAIL (step %s) %s\n' "$1" "$2" >&2; exit 1; }

case "${MODE}" in
  full|--offline) ;;
  *) fail 0 "usage: scripts/fresh_clone_check.sh [--offline]" ;;
esac

command -v git >/dev/null 2>&1 || fail 0 "git is required"

# A clone cannot contain uncommitted input, so certification begins only from a
# source tree whose commit completely describes it.
if [ -n "$(git -C "${REPO_ROOT}" status --porcelain)" ]; then
  fail 1 "the working copy has uncommitted changes; commit them before certification"
fi
COMMIT="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
log "certifying commit ${COMMIT}"

WORKSPACE="$(mktemp -d "${TMPDIR:-/tmp}/marketops-fresh.XXXXXX")"
CLONE="${WORKSPACE}/MarketOps clone's verification"
COMPOSE_PROJECT_NAME="marketops-fresh-${COMMIT:0:12}"
export COMPOSE_PROJECT_NAME
STACK_STARTED=false

cleanup() {
  if [ "${STACK_STARTED}" = true ] && [ -f "${CLONE}/.env.local" ]; then
    docker compose \
      --project-name "${COMPOSE_PROJECT_NAME}" \
      --env-file "${CLONE}/.env.local" \
      -f "${CLONE}/infra/compose/docker-compose.yml" \
      down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  if [ -n "${WORKSPACE}" ] && [ "${WORKSPACE}" != "/" ]; then
    rm -rf -- "${WORKSPACE}"
  fi
}
trap cleanup EXIT

# Local transport is disabled so the clone receives independent Git objects,
# and the target deliberately contains both whitespace and a single quote.
git clone --quiet --no-local --no-tags "${REPO_ROOT}" "${CLONE}"
[ "$(git -C "${CLONE}" rev-parse HEAD)" = "${COMMIT}" ] \
  || fail 2 "the clone did not check out the certified commit"
log "cloned into the special-character acceptance path"

for forbidden in \
  .env.local \
  frontend/marketops-console/.env.local \
  frontend/marketops-console/node_modules \
  backend/marketops-server/target \
  frontend/marketops-console/dist; do
  if [ -e "${CLONE}/${forbidden}" ]; then
    fail 3 "the clone carries ignored path ${forbidden}"
  fi
done
log "no ignored build or configuration state crossed the clone boundary"

(
  cd "${CLONE}"
  python3 scripts/validate_governance.py
  python3 scripts/validate_production_readiness.py
  python3 -m unittest discover -s tests -p 'test_*.py' --quiet
)
log "governance, readiness and validator tests pass in the clone"

if [ "${MODE}" = "--offline" ]; then
  log "PASS (offline) for commit ${COMMIT}"
  exit 0
fi

for command in java node npm docker make python3; do
  command -v "${command}" >/dev/null 2>&1 || fail 5 "${command} is required"
done

(
  cd "${CLONE}"
  make env-init
  python3 scripts/dev_doctor.py
)
log "the clone generated ignored configuration and satisfied its prerequisites"

STACK_STARTED=true
(
  cd "${CLONE}"
  make COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME}" up
)
log "the isolated PostgreSQL stack is ready"

(
  cd "${CLONE}/backend/marketops-server"
  ./mvnw -B -ntp verify
)
log "the full backend and database verification passes"

(
  cd "${CLONE}/frontend/marketops-console"
  npm ci
  npm ls --all
  npm run lint
  npm run format:check
  npm run typecheck
  npm run test:ci
  npm run build
  npm run verify:bundle
)
log "the lockfile install and full frontend verification pass"

(
  cd "${CLONE}"
  bash scripts/verify_coverage_thresholds.sh all
)
log "both coverage gates reject deliberately unmet thresholds"

(
  cd "${CLONE}"
  python3 scripts/collect_supply_chain.py
  bash scripts/verify_local_config.sh
)
log "CycloneDX inventories and root-configuration verification pass"

export PLAYWRIGHT_BROWSERS_PATH="${WORKSPACE}/playwright-browsers"
(
  cd "${CLONE}/frontend/marketops-console"
  npx playwright install chromium
  npm run test:browser
)
log "the real browser rendered metadata from the backend"

if [ -n "$(git -C "${CLONE}" status --porcelain)" ]; then
  fail 10 "verification left tracked changes in the clone"
fi

log "PASS for commit ${COMMIT}"
