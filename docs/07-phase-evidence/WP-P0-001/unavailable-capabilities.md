# Capability availability resolution

**State: RESOLVED_LOCALLY**

The imported artifact documented 28 capabilities its authoring environment
could not execute. Codex ran the technical capabilities rather than carrying
those limitations into the publication candidate.

| Boundary | Disposition |
| --- | --- |
| Maven resolution, wrapper, compile and tests | RESOLVED — official Wrapper and all backend Gates pass |
| npm registry, lockfile and frontend Gates | RESOLVED — exact lock install, dependency tree and all frontend Gates pass |
| Container runtime and database privilege proofs | RESOLVED — PostgreSQL 18.4 and all 21 integration tests pass |
| Browser | RESOLVED — real Chromium ready/correlation/CORS/outage test passes |
| Supply chain | RESOLVED — two CycloneDX 1.6 SBOMs and installed-package licence inventories generated |
| Special-character Fresh Clone | RESOLVED — clean Head passed the complete whitespace-and-apostrophe path and trap cleanup |
| GitHub Actions and repository alerts | PR_GATE_OPEN — observed results require the Draft PR created by this task |

There is no remaining local capability gap. GitHub results are not guessed: the
Draft PR run URLs, conclusions, security-alert counts and Ruleset state are
recorded after publication. Ruleset mutation remains outside current authority
and requires explicit Human Owner direction after all eleven names are stable
and green.
