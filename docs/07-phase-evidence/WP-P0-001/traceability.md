# WP-P0-001 traceability

**Verification state: PASS on implementation Head
`a971717a658e9db315c5e6c3e03e5b5899e48f65` (2026-08-17), tree
`2f227c35b515a21b8e412a0adea59838dbfc5af8`.** The clean-head run comprised
133 governance/readiness tests, 110 backend unit tests, 22 backend integration
tests, 31 architecture observations within the unit total, 53 frontend tests and
one real-browser recovery scenario.

Each row names the implementation and the test or hard rule that would catch its
removal. A document-only assertion is not treated as verification.

| Requirement | Where it lives | What would catch its removal |
| --- | --- | --- |
| Local configuration is generated, never committed | `scripts/init_local_env.py`, `.gitignore` | `tests/test_init_local_env.py`; the generator refuses a target Git does not ignore |
| Generated passwords are never printed | `scripts/init_local_env.py` | `tests/test_init_local_env.py` asserts no code path emits a value |
| No active WP means planning only; completed WP-P0-001 is closed and verified | `CURRENT_STATE.md`, WP-P0-001 metadata, `validate_governance.py` | Authorization/completion mutation tests reject open, ambiguous, candidate or unfinished combinations |
| The canonical backlog independently closes WP-P0-001 without activating WP-P0-002 | `BACKLOG-PHASE-0.md`, `validate_governance.py` | Structural parsing and mutations reject missing, duplicated, stale, unknown-state or malformed canonical rows |
| D-03 does not falsely claim an unimplemented Worker | `traceability.csv`, `BACKLOG-PHASE-0.md` | Governance mutations require `ACTIVE_CONTROL`, WP-P0-003 and explicit Task/Outbox Worker disposition |
| Local files are owner-only | `scripts/init_local_env.py` | `scripts/verify_local_config.sh` step 1 |
| The backend reads the repository-root local file | `Makefile`, `scripts/verify_local_config.sh` | Readiness reports up only if the generated password was read |
| No superseded construct in a production path | `scripts/validate_production_readiness.py` | TC-GLOBAL-001 |
| No comment narrates history | same | TC-GLOBAL-002 |
| Production names are the agreed names | same | TC-GLOBAL-003 |
| The build refuses any Java but 21 | `pom.xml` enforcer | The enforcer fails the build; `BuildConstraintsTest` asserts the running release |
| Object-relational mapping is absent | `pom.xml` enforcer, TC-GLOBAL-001 | `BuildConstraintsTest` asserts the types are unreachable on the classpath |
| The Maven distribution is pinned and verified | `mvnw`, `maven-wrapper.properties` | The wrapper refuses to run without a digest and deletes a mismatched archive |
| CORS is disabled in the base profile and finite in local/CI | `CorsProperties`, `WebConfig`, profile YAML | `CorsContractTest`, including startup rejection of an unknown configured origin |
| CORS permits only loopback console origins and read requests | `CorsProperties`, `WebConfig` | `CorsContractTest` proves origin, method, request-header, exposed-header and credential policy |
| The server binds to loopback | `application.yaml` | `ApplicationConfigurationTest` |
| Only health and info are reachable | `application.yaml` | `ApplicationConfigurationTest` |
| Health names components/status but reports no detail | `application.yaml` | `ApplicationConfigurationTest`, `ApplicationSmokeIT.healthResponseNamesComponentsButWithholdsDetails` |
| Readiness includes the datasource, liveness does not | `application.yaml` | `ApplicationConfigurationTest` |
| Migration runs as the owning role, the application as its own | `application.yaml` | `ApplicationConfigurationTest`, `ApplicationSmokeIT` |
| An unprofiled start cannot invent an environment | profile YAML and no base `marketops.environment` | `ApplicationEnvironmentFailClosedTest`; TC-GLOBAL-001 rejects a base fallback |
| Schema destruction is disabled | `application.yaml` | `ApplicationConfigurationTest`, `FlywayMigrationIT` TC-DB-114 |
| A correlation identifier exists for every request | `CorrelationIdFilter` | `CorrelationIdTest`, `MetaStatusControllerTest` |
| A hostile identifier is replaced, never echoed | `CorrelationId` | `CorrelationIdTest`, `MetaStatusControllerTest` |
| The logging context does not leak between requests | `CorrelationIdFilter` | `MetaStatusControllerTest`, including the failure path |
| Non-local logs are ECS JSON with exactly one root correlation ID and local logs are one readable line | profile YAML, `EcsCorrelationIdJsonMembersCustomizer` | `LoggingContractTest` invokes the real Spring Boot encoder, proves MDC value/`none` fallback, exact single occurrence, retained safe event fields and excluded context/tags/error/stack data |
| Safe events use deliberate severity | `GlobalExceptionHandler` | `GlobalExceptionHandlerTest` requires validation WARN, missing resource INFO and unexpected failure ERROR |
| A failure response or public-boundary log carries no internal detail | `GlobalExceptionHandler`, `MetaStatusAssembler` | Captured-appender tests prove no throwable proxy, message, credential, host, port, role or SQL marker; response tests prove the allowlist |
| Repeated dependency degradation does not flood logs | `MetaStatusAssembler` transition flags | `repeatedProbeFailureIsBoundedAndRecoveryRearmsIt` proves one WARN per degraded interval and rearming after recovery |
| The metadata field set is an allowlist | `MetaStatusResponse` | `MetaStatusControllerTest` |
| A degraded source degrades one field, not the request | `MetaStatusAssembler` | `MetaStatusAssemblerTest` |
| An unexpected commit value is not published | `MetaStatusAssembler` | `MetaStatusAssemblerTest` |
| Eight foundation schemas exist, owned by the migrating role | `V0001` | TC-DB-101, TC-DB-102 |
| A contaminated database fails and leaves nothing behind | `V0001` | TC-DB-103, twelve ordered observations |
| The application role can enter but not create | `01-roles.sql`, `V0001` | TC-DB-105, TC-DB-105b, TC-DB-106, TC-DB-107 |
| The application role cannot touch the migration history | `01-roles.sql` | TC-DB-108 |
| `PUBLIC` holds nothing on the public schema | `01-roles.sql` | TC-DB-109 |
| The application role resolves names without `public` | `01-roles.sql` | TC-DB-115 |
| Neither role holds cluster authority | `01-roles.sql` | TC-DB-116, TC-DB-117 |
| The foundation creates no application table | `V0001` | TC-DB-110 |
| Exactly one migration exists | `db/migration` | TC-DB-113, TC-GLOBAL-001 |
| Only an exact `internal` package segment is closed | `ArchitectureRules` | TC-ARCH-001, ordinary/prefix-collision violations and the conforming `internalization` fixture |
| Modules are free of cycles | same | TC-ARCH-002 and F-ARCH-002 |
| Shared depends on no business module | same | TC-ARCH-003 and F-ARCH-003 |
| Domain does not depend on adapter, infrastructure or SDK | same | TC-ARCH-004 and F-ARCH-004 |
| Application and port do not depend on concrete implementations or SDK | same | TC-ARCH-005; F-ARCH-005a and F-ARCH-005b independently exercise the composite halves |
| Vendor SDK types stay under `marketplaceintegration.adapter.<platform>` | same | TC-ARCH-006 and F-ARCH-006 |
| Vendor SDK types never enter domain, direct API or NamedInterface signatures | same | TC-ARCH-007/F-ARCH-007 inspect public/protected fields, constructor, return, parameter, generic, superclass and interface shapes; a platform-owned NamedInterface passes |
| Valid adapter/infrastructure dependencies point inward | `RuleSensitivityArchitectureTest` | F-ARCH-PASS passes all seven approved factories using only a local SDK stand-in |
| REST/database, constructor injection, clock and logger quality safeguards remain separate | `CodeQualityArchitectureRules` | TC-QUALITY-ARCH-001–004, F-QUALITY-001–004 and F-QUALITY-PASS; none count as an approved boundary |
| The module structure verifies from its own model | `ModulithArchitectureTest` | TC-ARCH-008 |
| The console refuses missing or blank runtime configuration | `config.ts`, `App.tsx` | `config.test.ts`, `App.test.tsx`; no request is made and no platform value is rendered |
| The console reports seven states | `healthState.ts` | `healthState.test.ts` |
| A partial payload is not rendered | `metaStatus.ts` | `metaStatus.test.ts`, `HealthShell.test.tsx` |
| Automatic polling is non-overlapping and bounded | `HealthShell.tsx` | Fake-timer tests prove normal interval, three-stage backoff, overlap prevention, manual refresh and StrictMode singleton scheduling |
| No timer or request survives unmount | `HealthShell.tsx`, `metaStatus.ts` | Component/API cancellation tests assert abort and zero remaining timers/requests |
| No stale platform value survives an outage | `HealthShell.tsx` | `HealthShell.test.tsx` |
| Only prefixed variables reach the bundle | `vite.config.ts` | `viteConfig.test.ts`, `verify-bundle-isolation.mjs` |
| Frontend version comes only from valid package metadata | `package.json`, `vite.config.ts`, `vite.constants.ts` | `viteConfig.test.ts` rejects missing/invalid versions and proves a transient ref is ignored |
| Frontend commit is the full authored source Head, not the checkout merge | workflow, `sourceIdentity.ts`, `playwright.config.ts` and browser scenario | unit/mutation rejection covers missing/invalid/uppercase/short identity, CI fallback, checkout-Head substitution and build/assertion divergence; the real browser renders the shared resolved value |
| Backend coverage is at least 80% lines and 70% branches | `pom.xml` JaCoCo rules | `verify` fails below either threshold; `verify_coverage_thresholds.sh backend` proves the failure path |
| Frontend coverage is at least 80% lines, branches, functions and statements | `vite.config.ts` | `test:ci` fails below a threshold; `verify_coverage_thresholds.sh frontend` proves the failure path |
| Eleven checks report under stable names | `.github/workflows/` | The names are the job names; a rename is visible in the diff |
| Workflow actions and runners are immutable | `.github/workflows/` | TC-GLOBAL-001 and its negative tests reject mutable action refs, absent version comments and floating runners |
| Build metadata records the authored commit | `backend.yml` | The workflow passes the head commit, not the generated merge commit |
| Both ecosystems emit validated CycloneDX inventories | `pom.xml`, `package.json`, `collect_supply_chain.py` | `make supply-chain`; collector tests reject absent or malformed SBOMs and incomplete installed-package licences |
| A real browser proves build identity, outage and recovery | `playwright.config.ts`, `health-shell.spec.ts` | Production preview verifies package version/full Head, semantic `contentinfo`, correlation/CORS, Ready → `DOWN`/Degraded → healthy restart/Ready and recovered correlation |
| A clone verifies with no local state or path restriction | `scripts/fresh_clone_check.sh` | The full check clones into a whitespace-and-apostrophe path, runs every Gate and rejects tracked-file mutation |
