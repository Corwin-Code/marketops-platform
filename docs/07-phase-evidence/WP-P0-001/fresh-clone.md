# Full Fresh Clone acceptance

**Result: PASS**

The acceptance script certified implementation Head
`3a7575ad8f3a75b94210dc394f154bf4780283f2` with tree
`4c4953632a33834052608ec20086c5afe9b791ab` on 2026-08-17. It cloned into
`MarketOps clone's verification`, containing whitespace and an apostrophe, and
inherited no ignored configuration, dependency or build state.

The passing run proved:

- governance, all three global rules and 122 Python tests over 200 source files;
- ignored owner-only environment generation and prerequisite checks;
- an isolated healthy PostgreSQL 18.4 stack;
- 109 backend unit/configuration/architecture tests and 22 integration tests;
- exact lock install, dependency tree, lint, formatting, type-check, 46 frontend
  tests, production build and bundle isolation;
- backend/frontend negative coverage-threshold enforcement;
- validated CycloneDX 1.6 and licence inventories for both ecosystems;
- root-configuration import and readiness;
- a newly installed real Chromium built-console scenario that rendered package
  version/full source Head and recovered Ready → Degraded → Ready;
- no tracked-file mutation and trap cleanup of container, network, volume,
  browser cache and temporary clone.

The first infrastructure attempt encountered the workspace database on host port
5432 and stopped before build execution. Its trap removed the scoped container,
network and volume. `make down` then released the port without deleting the
workspace data volume, and the same committed Head passed in full. A post-run
Docker query found no container, network or volume for Compose project
`marketops-fresh-3a7575ad8f3a`; the preserved workspace volume remains.

The passing browser assertion took 9.9 seconds and its Playwright command took
20.9 seconds. SBOMs contain 76 backend and 341 frontend components; licence
inventories cover 130 Maven dependencies and 366 installed npm packages, with no
undeclared frontend licence.
