# Full Fresh Clone acceptance

**Result: PASS**

The acceptance script certified clean scratch Head
`c3bb71e3b481274976b813108f8912796e529e0c` on 2026-08-15. The publication
branch is rebuilt from the same corrected tree and must repeat this Gate before
push.

The run cloned into a directory named `MarketOps clone's verification` and
proved all of the following without inherited ignored state:

- governance, all three global rules, and validator unit tests;
- ignored environment generation and prerequisite checks;
- an isolated healthy PostgreSQL stack;
- full backend verification, including migrations and privilege tests;
- clean `npm ci`, full dependency resolution, lint, formatting, type-check,
  coverage tests, build and bundle-isolation canary;
- controlled negative coverage checks for backend and frontend;
- backend/frontend CycloneDX and licence inventories;
- root-configuration verification and real-browser ready/outage acceptance;
- trap-based container, volume and temporary-clone cleanup;
- confirmation that verification did not modify a tracked file.

Observed aggregate results were 86 governance/validator tests, 87 backend unit,
configuration and architecture tests, 21 backend integration tests, 39 frontend
tests, and one real-browser scenario. Clean `npm ci` installed 375 lockfile
packages without an uncovered lifecycle script. Both negative coverage checks
failed their deliberately impossible threshold, and both CycloneDX 1.6
inventories validated.

The command exited zero. A post-run Docker query found no container or volume
for Compose project `marketops-fresh-c3bb71e3b481`, confirming trap cleanup.
