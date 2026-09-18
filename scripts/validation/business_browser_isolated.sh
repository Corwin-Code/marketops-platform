#!/usr/bin/env bash
# Business-flow and dependency-recovery Chromium suite (playwright.config.ts)
# against a fresh Compose PostgreSQL project on a dedicated non-5432 loopback port.
# `make backend-browser-run` and tests/browser/health-shell.spec.ts both read the
# repository-root .env.local, so this script creates that file with the standard
# generator, moves only its published port, and removes only the Compose project
# and the files that this run created. BrowserFixtureApplication keeps enforcing
# the explicit synthetic marker, loopback, non-5432 and empty-database guards.
set -euo pipefail

repository_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repository_root"
root_env="$repository_root/.env.local"
frontend_env="$repository_root/frontend/marketops-console/.env.local"
compose_file="$repository_root/infra/compose/docker-compose.yml"

fail() { printf 'business-browser: %s\n' "$1" >&2; exit 1; }

# The generated file must be the only database source. An inherited value would
# win over it in Compose interpolation and in Spring's property resolution.
while IFS= read -r variable; do
  case "$variable" in
    MARKETOPS_DB_*|MARKETOPS_POSTGRES_*|SPRING_DATASOURCE_*|SPRING_FLYWAY_*|SPRING_CONFIG_*|SPRING_APPLICATION_JSON)
      fail "$variable is set in the environment; unset it before an isolated run" ;;
  esac
done < <(compgen -e)

project=${COMPOSE_PROJECT_NAME:-marketops-browser-$(python3 -c 'import secrets; print(secrets.token_hex(6))')}
[[ "$project" =~ ^marketops-browser-[a-z0-9][a-z0-9-]*$ ]] \
  || fail "COMPOSE_PROJECT_NAME must name a dedicated marketops-browser-* project"
export COMPOSE_PROJECT_NAME="$project"
compose=(docker compose --project-name "$project" --env-file "$root_env" -f "$compose_file")

label="label=com.docker.compose.project=$project"
if [ -n "$(docker ps -aq --filter "$label")$(docker volume ls -q --filter "$label")$(docker network ls -q --filter "$label")" ]; then
  fail "Compose project $project already owns resources; refusing to reuse or remove them"
fi
if [ -e "$root_env" ] || [ -e "$frontend_env" ]; then
  fail "an existing .env.local was found; run from a fresh checkout"
fi
printf 'business-browser: Compose project %s\n' "$project"

created_env=false
stack_started=false
cleanup() {
  local status=$?
  trap '' INT TERM
  if [ "$stack_started" = true ]; then
    if "${compose[@]}" down --volumes --remove-orphans; then
      stack_started=false
    else
      printf 'business-browser: Compose project %s was not removed\n' "$project" >&2
      [ "$status" -ne 0 ] || status=1
    fi
  fi
  # Keep the generated files while the stack still exists, so the workflow's
  # always-run backstop can address the same project with the same credentials.
  if [ "$created_env" = true ] && [ "$stack_started" = false ]; then
    rm -f -- "$root_env" "$frontend_env"
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

created_env=true
make env-init

db_port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
if [[ ! "$db_port" =~ ^[0-9]+$ ]] || [ "$db_port" = 5432 ]; then
  fail "a dedicated loopback database port was not allocated"
fi
python3 - "$root_env" "$db_port" <<'PY'
import sys

path, port = sys.argv[1], sys.argv[2]
with open(path, "r+", encoding="utf-8") as handle:
    lines = handle.read().splitlines(keepends=True)
    hits = [index for index, line in enumerate(lines) if line.startswith("MARKETOPS_DB_PORT=")]
    if len(hits) != 1 or lines[hits[0]] != "MARKETOPS_DB_PORT=5432\n":
        sys.exit("business-browser: generated configuration lacks exactly one default port line")
    lines[hits[0]] = f"MARKETOPS_DB_PORT={port}\n"
    handle.seek(0)
    handle.write("".join(lines))
    handle.truncate()
PY

stack_started=true
make COMPOSE_PROJECT_NAME="$project" up
published=$("${compose[@]}" port postgres 5432)
[ "$published" = "127.0.0.1:$db_port" ] \
  || fail "Compose published $published instead of 127.0.0.1:$db_port"
printf 'business-browser: %s serves 127.0.0.1:%s\n' "$project" "$db_port"

(cd "$repository_root/frontend/marketops-console" && npm run test:browser)
