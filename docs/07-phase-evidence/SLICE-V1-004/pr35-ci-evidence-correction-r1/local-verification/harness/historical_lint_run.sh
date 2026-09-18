#!/usr/bin/env bash
set -u -o pipefail
E=$1; D=$2
export npm_config_fund=false npm_config_audit=false TZ=UTC LANG=C.UTF-8 CI=true
NODE_BIN=$(dirname "$(fnm exec --using=24.19.0 which node)")
export PATH="$NODE_BIN:$PATH"
cd "$D/frontend/marketops-console"
{ echo "node=$(node --version) npm=$(npm --version) head=$(git rev-parse HEAD) tree=$(git rev-parse 'HEAD^{tree}')"; git status --porcelain --untracked-files=no | wc -l; } > "$E/run-context.txt"
npm ci > "$E/npm-ci.log" 2>&1; echo "exit=$?" >> "$E/npm-ci.log"
npm run lint > "$E/lint.log" 2>&1; echo "exit=$?" >> "$E/lint.log"
npm run format:check > "$E/format-check.log" 2>&1; echo "exit=$?" >> "$E/format-check.log"
npm run typecheck > "$E/typecheck.log" 2>&1; echo "exit=$?" >> "$E/typecheck.log"
