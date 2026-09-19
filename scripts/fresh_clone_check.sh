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
# Set per run below; nothing inherited names or reaches a stack of this run.
COMPOSE_PROJECT_NAME=""
STACK_STARTED=false

# Lists every container, volume and network labelled as Compose project $1.
project_resources() {
  local label="label=com.docker.compose.project=$1"
  docker ps -aq --filter "${label}" || return 1
  docker volume ls -q --filter "${label}" || return 1
  docker network ls -q --filter "${label}" || return 1
}

# Fails step $2 unless Compose project $1 owns nothing. A listing error is never read as "nothing".
require_no_resources() {
  local found
  found="$(project_resources "$1")" || fail "$2" "Docker could not list the resources of Compose project $1"
  [ -z "${found}" ] || fail "$2" "$3"
}

# Addresses the current project with the root configuration that started it.
compose() {
  docker compose \
    --project-name "${COMPOSE_PROJECT_NAME}" \
    --env-file "${CLONE}/.env.local" \
    -f "${CLONE}/infra/compose/docker-compose.yml" \
    "$@"
}

cleanup() {
  if [ "${STACK_STARTED}" = true ] && [ -f "${CLONE}/.env.local" ]; then
    compose down --volumes --remove-orphans >/dev/null 2>&1 \
      || printf 'fresh-clone: Compose project %s was not removed\n' "${COMPOSE_PROJECT_NAME}" >&2
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

# The generated root file must be the only database source of every stack below.
# An inherited value would win over it in Compose interpolation and in Spring's
# property resolution; the isolated browser entry refuses the same names.
while IFS= read -r variable; do
  case "${variable}" in
    MARKETOPS_DB_*|MARKETOPS_POSTGRES_*|SPRING_DATASOURCE_*|SPRING_FLYWAY_*|SPRING_CONFIG_*|SPRING_APPLICATION_JSON)
      fail 4 "${variable} is set in the environment; unset it before certification" ;;
  esac
done < <(compgen -e)

for command in java node npm docker make python3; do
  command -v "${command}" >/dev/null 2>&1 || fail 5 "${command} is required"
done

# Both Compose projects are named for this run alone. A project that already owns
# resources is refused, never adopted, recreated or removed.
RUN_ID="${COMMIT:0:12}-$(python3 -c 'import secrets; print(secrets.token_hex(6))')"
CONFIG_PROJECT="marketops-fresh-${RUN_ID}"
BROWSER_PROJECT="marketops-browser-fresh-${RUN_ID}"
for project in "${CONFIG_PROJECT}" "${BROWSER_PROJECT}"; do
  require_no_resources "${project}" 6 \
    "Compose project ${project} already owns resources; refusing to reuse or remove them"
done
COMPOSE_PROJECT_NAME="${CONFIG_PROJECT}"
export COMPOSE_PROJECT_NAME

(
  cd "${CLONE}"
  make env-init
  python3 scripts/dev_doctor.py
)
log "the clone generated ignored configuration and satisfied its prerequisites"

# Developers keep the generated default port. This run publishes its own stack on a
# dedicated loopback port instead, so it never reaches or occupies a default
# instance, and every later step reads that port from the same generated file.
DB_PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
if [[ ! "${DB_PORT}" =~ ^[0-9]+$ ]] || [ "${DB_PORT}" = 5432 ]; then
  fail 6 "a dedicated loopback database port was not allocated"
fi
python3 - "${CLONE}/.env.local" "${DB_PORT}" <<'PY'
import sys

path, port = sys.argv[1], sys.argv[2]
with open(path, "r+", encoding="utf-8") as handle:
    lines = handle.read().splitlines(keepends=True)
    hits = [index for index, line in enumerate(lines) if line.startswith("MARKETOPS_DB_PORT=")]
    if len(hits) != 1 or lines[hits[0]] != "MARKETOPS_DB_PORT=5432\n":
        sys.exit("fresh-clone: generated configuration lacks exactly one default port line")
    lines[hits[0]] = f"MARKETOPS_DB_PORT={port}\n"
    handle.seek(0)
    handle.write("".join(lines))
    handle.truncate()
PY

STACK_STARTED=true
(
  cd "${CLONE}"
  make COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME}" up
)
published="$(compose port postgres 5432)"
[ "${published}" = "127.0.0.1:${DB_PORT}" ] \
  || fail 6 "Compose published ${published} instead of 127.0.0.1:${DB_PORT}"
log "the isolated PostgreSQL stack ${COMPOSE_PROJECT_NAME} serves 127.0.0.1:${DB_PORT}"

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

# The browser fixture accepts only a new, empty database on a dedicated loopback
# port. The configuration stack and the files generated for it are removed first,
# so the isolated entry generates, starts, tests and removes one instance of its own.
compose down --volumes --remove-orphans
STACK_STARTED=false
require_no_resources "${COMPOSE_PROJECT_NAME}" 7 "Compose project ${COMPOSE_PROJECT_NAME} still owns resources"
rm -f -- "${CLONE}/.env.local" "${CLONE}/frontend/marketops-console/.env.local"
log "the configuration stack and its generated files were removed"

export PLAYWRIGHT_BROWSERS_PATH="${WORKSPACE}/playwright-browsers"
(
  cd "${CLONE}/frontend/marketops-console"
  npx playwright install chromium
)

# If the isolated entry fails while its stack still exists, it keeps its generated
# file, and the exit trap removes the same project with the same credentials.
COMPOSE_PROJECT_NAME="${BROWSER_PROJECT}"
STACK_STARTED=true
(
  cd "${CLONE}"
  COMPOSE_PROJECT_NAME="${BROWSER_PROJECT}" MARKETOPS_SOURCE_HEAD_SHA="${COMMIT}" \
    bash scripts/validation/business_browser_isolated.sh
)
STACK_STARTED=false
require_no_resources "${BROWSER_PROJECT}" 8 "Compose project ${BROWSER_PROJECT} still owns resources"
for generated in .env.local frontend/marketops-console/.env.local; do
  [ ! -e "${CLONE}/${generated}" ] || fail 8 "the isolated browser entry left ${generated} behind"
done
log "the real browser suite passed on its own loopback database, which was then removed"

if [ -n "$(git -C "${CLONE}" status --porcelain)" ]; then
  fail 10 "verification left tracked changes in the clone"
fi

log "PASS for commit ${COMMIT}"
