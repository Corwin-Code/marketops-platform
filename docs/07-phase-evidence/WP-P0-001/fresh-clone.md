# Full Fresh Clone acceptance

**Result: PASS**

The acceptance script certified clean implementation Head
`4001a8d2717739967bf48a71c6a4f82bd2e5c50f` on 2026-08-16. It cloned into a
directory named `MarketOps clone's verification`, containing both whitespace
and an apostrophe, and inherited no ignored configuration or build state.

The passing run proved:

- governance, all three global rules and 104 validator tests;
- ignored environment generation and prerequisite checks;
- an isolated healthy PostgreSQL stack;
- full backend verification: 100 unit/configuration/architecture tests and 22
  integration tests;
- `npm ci`, full dependency resolution, lint, formatting, type-check, 45 tests,
  production build and bundle isolation;
- controlled negative coverage checks for backend and frontend;
- validated backend/frontend CycloneDX 1.6 and licence inventories;
- root-configuration verification;
- one real Chromium built-console scenario with automatic Ready → Degraded →
  Ready recovery and correlation verification after recovery;
- no tracked-file mutation;
- trap-based container, network, volume, browser-cache and temporary-clone cleanup.

The first infrastructure attempt encountered the original workspace database on
host port 5432 and stopped before executing tests. Its trap left zero scoped
containers, networks or volumes. After `make down` released the port without
deleting the original data volume, the same committed Head passed in full. This
environmental retry is recorded rather than omitted.

The passing run's browser assertion took 11.5 seconds and the Playwright command
took 23.0 seconds. Backend and frontend SBOMs contain 76 and 341 components;
licence inventories cover 130 Maven dependencies and 366 installed npm packages.
No frontend licence is undeclared. A post-run Docker query found no resource for
Compose project `marketops-fresh-4001a8d27177`.
