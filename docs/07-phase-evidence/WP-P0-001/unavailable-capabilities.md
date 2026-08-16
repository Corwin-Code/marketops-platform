# Capability availability resolution

**State: OWNER_RULESET_ACTION_REQUIRED**

The imported artifact documented 28 capabilities its authoring environment
could not execute. Codex exercised every local and GitHub technical capability;
the only remaining boundary is an authority-gated repository setting.

| Boundary | Disposition |
| --- | --- |
| Maven resolution, wrapper, compile and tests | RESOLVED — official Wrapper and all backend Gates pass |
| npm registry, lockfile and frontend Gates | RESOLVED — exact lock install, dependency tree and all frontend Gates pass |
| Container runtime and database privilege proofs | RESOLVED — PostgreSQL 18.4 and all 22 integration tests pass |
| Browser | RESOLVED — real Chromium built-preview Ready → Degraded → Ready and recovered correlation pass |
| Supply chain | RESOLVED — two CycloneDX 1.6 SBOMs and installed-package licence inventories validate |
| Special-character Fresh Clone | RESOLVED — clean Head passed the complete whitespace-and-apostrophe path and trap cleanup |
| GitHub Actions | RESOLVED — all eleven stable jobs pass on the implementation Head |
| PR review/security evidence | RESOLVED — 0 unresolved threads, 0 CodeQL PR annotations and 0 open Code scanning/Dependabot/Secret Scanning alerts |
| Ruleset required checks | OWNER ACTION REQUIRED — active Ruleset 20734984 requires only `governance`; Controller permits only Human Owner to add the remaining ten |

There is no remaining local capability gap, no inaccessible GitHub evidence and
no in-scope technical deferral. Ruleset mutation is not a capability workaround:
it is deliberately outside Codex authority and must be performed or explicitly
authorized by the Human Owner before the repository Gate can be called complete.
