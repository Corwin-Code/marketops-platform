# Full Fresh Clone acceptance

**Result: PASS**

The acceptance script certified implementation Head
`a971717a658e9db315c5e6c3e03e5b5899e48f65` with tree
`2f227c35b515a21b8e412a0adea59838dbfc5af8` on 2026-08-17. It cloned into
`MarketOps clone's verification`, containing whitespace and an apostrophe, and
inherited no ignored configuration, dependency or build state.

The passing run proved:

- governance, all three global rules and 133 Python tests over 203 source files;
- ignored owner-only environment generation and prerequisite checks;
- an isolated healthy PostgreSQL 18.4 stack;
- 110 backend unit/configuration/architecture tests and 22 integration tests;
- exact lock install, dependency tree, lint, formatting, type-check, 53 frontend
  tests in eight files, production build and bundle isolation;
- backend/frontend negative coverage-threshold enforcement;
- validated CycloneDX 1.6 and licence inventories for both ecosystems;
- root-configuration import and readiness;
- a newly installed real Chromium built-console scenario that rendered package
  version/full source Head and recovered Ready → Degraded → Ready;
- no tracked-file mutation and trap cleanup of container, network, volume,
  browser cache and temporary clone.

The sandbox attempt reached the browser stage and was refused a macOS Mach port.
The exact command was rerun outside that boundary against the same committed
Head and passed in full. A post-run Docker query found no container, network or
volume for Compose project `marketops-fresh-a971717a658e`; workspace data was
not deleted.

The passing browser assertion took 9.8 seconds and its Playwright command took
21.1 seconds. SBOMs contain 76 backend and 341 frontend components; licence
inventories cover 130 Maven dependencies and 366 installed npm packages, with no
undeclared frontend licence.
