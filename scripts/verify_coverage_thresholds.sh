#!/usr/bin/env bash
# Prove each coverage command fails when its configured minimum exceeds the result.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-all}"
LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/marketops-coverage.XXXXXX")"
trap 'rm -rf -- "${LOG_DIR}"' EXIT

verify_backend() {
  local output="${LOG_DIR}/backend.log"
  if (
    cd "${ROOT}/backend/marketops-server"
    ./mvnw -B -ntp -DskipITs \
      -Djacoco.line.coverage=1.00 \
      -Djacoco.branch.coverage=1.00 \
      verify
  ) >"${output}" 2>&1; then
    printf 'coverage-negative: backend unexpectedly passed at 100%%\n' >&2
    return 1
  fi
  grep -Eiq 'coverage checks have not been met|rule violated' "${output}" \
    || { printf 'coverage-negative: backend failed for the wrong reason\n' >&2; return 1; }
  printf 'coverage-negative: backend threshold enforcement PASS\n'
}

verify_frontend() {
  local output="${LOG_DIR}/frontend.log"
  if (
    cd "${ROOT}/frontend/marketops-console"
    npm exec -- vitest run --coverage \
      --coverage.thresholds.lines=100 \
      --coverage.thresholds.branches=100 \
      --coverage.thresholds.functions=100 \
      --coverage.thresholds.statements=100
  ) >"${output}" 2>&1; then
    printf 'coverage-negative: frontend unexpectedly passed at 100%%\n' >&2
    return 1
  fi
  grep -Eiq 'coverage for .* does not meet|coverage threshold' "${output}" \
    || { printf 'coverage-negative: frontend failed for the wrong reason\n' >&2; return 1; }
  printf 'coverage-negative: frontend threshold enforcement PASS\n'
}

case "${MODE}" in
  backend) verify_backend ;;
  frontend) verify_frontend ;;
  all) verify_backend; verify_frontend ;;
  *) printf 'usage: scripts/verify_coverage_thresholds.sh [backend|frontend|all]\n' >&2; exit 2 ;;
esac
