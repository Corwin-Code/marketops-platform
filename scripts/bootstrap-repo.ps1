$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

if (-not (Test-Path "README.md") -or -not (Test-Path "docs/00-governance/CURRENT_STATE.md")) {
    throw "Run this script from the MarketOps bootstrap repository."
}

python scripts/validate_governance.py

if (-not (Test-Path ".git")) {
    git init -b main
} else {
    git branch -M main
}

git add .
$staged = git diff --cached --name-only
if ([string]::IsNullOrWhiteSpace(($staged -join ""))) {
    Write-Host "No staged changes. Repository may already be initialized."
} else {
    git commit -m "chore: bootstrap MarketOps governance foundation"
}

Write-Host "Local repository initialized."
Write-Host "Next: git remote add origin <YOUR_PRIVATE_REPOSITORY_REMOTE>"
Write-Host "Then: git push -u origin main"
Write-Host "Configure the Ruleset described in docs/00-governance/GITHUB_SETUP.md."
