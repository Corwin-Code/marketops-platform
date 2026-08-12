#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f "README.md" || ! -f "docs/00-governance/CURRENT_STATE.md" ]]; then
  echo "ERROR: run this script from the MarketOps bootstrap repository." >&2
  exit 1
fi

python3 scripts/validate_governance.py

if [[ ! -d .git ]]; then
  git init -b main
else
  git branch -M main
fi

git add .

if git diff --cached --quiet; then
  echo "No staged changes. Repository may already be initialized."
else
  git commit -m "chore: bootstrap MarketOps governance foundation"
fi

cat <<'EOF'
Local repository initialized.
Next:
  git remote add origin <YOUR_PRIVATE_REPOSITORY_REMOTE>
  git push -u origin main
Then configure the GitHub Ruleset in docs/00-governance/GITHUB_SETUP.md.
EOF
