#!/usr/bin/env bash
# Prove that the backend loads the repository-root local configuration file.
#
# Readiness includes the datasource, and the datasource password exists only in
# the ignored root file. A ready backend is therefore proof that the absolute
# import path resolved correctly — established without printing any value.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_LOCAL="${REPO_ROOT}/.env.local"
BACKEND_DIR="${REPO_ROOT}/backend/marketops-server"
READINESS_URL="http://127.0.0.1:8080/actuator/health/readiness"
TIMEOUT_SECONDS=180

log() { printf 'verify-local-config: %s\n' "$1"; }
fail() { printf 'verify-local-config: FAIL (step %s) %s\n' "$1" "$2" >&2; exit 1; }

# Step 1 — the root file exists with owner-only permissions.
[ -f "${ENV_LOCAL}" ] || fail 1 "missing ${ENV_LOCAL}; run 'make env-init'"
if [ "$(uname -s)" != "Darwin" ]; then
  mode="$(stat -c '%a' "${ENV_LOCAL}")"
else
  mode="$(stat -f '%Lp' "${ENV_LOCAL}")"
fi
[ "${mode}" = "600" ] || fail 1 "expected mode 600 on the local file, found ${mode}"
log "root local configuration present with owner-only permissions"

# Step 2 — no copy of the secret file exists beside the backend.
[ ! -e "${BACKEND_DIR}/.env.local" ] || fail 2 "a copy of the local file exists in the backend directory"
log "no secret file copy beside the backend"

# Step 3 — start the backend with the absolute import location.
cd "${BACKEND_DIR}"
SPRING_CONFIG_IMPORT="file:${ENV_LOCAL}[.properties]" \
  ./mvnw -B -ntp spring-boot:run -Dspring-boot.run.profiles=local \
  > /tmp/marketops-verify-local-config.log 2>&1 &
backend_pid=$!
cleanup() {
  if kill -0 "${backend_pid}" 2>/dev/null; then
    kill "${backend_pid}" 2>/dev/null || true
    wait "${backend_pid}" 2>/dev/null || true
  fi
}
trap cleanup EXIT
log "backend started with an absolute configuration import"

# Step 4 — poll readiness.
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
status=""
while [ "$(date +%s)" -lt "${deadline}" ]; do
  if ! kill -0 "${backend_pid}" 2>/dev/null; then
    fail 4 "backend exited before becoming ready; see the run log"
  fi
  status="$(curl -fsS "${READINESS_URL}" 2>/dev/null || true)"
  case "${status}" in
    *'"status":"UP"'*) break ;;
  esac
  sleep 3
done

# Step 5 — assert readiness.
case "${status}" in
  *'"status":"UP"'*) ;;
  *) fail 5 "readiness did not report UP within ${TIMEOUT_SECONDS}s" ;;
esac

log "readiness reported UP, which requires the generated database password"
log "PASS"
