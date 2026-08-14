# WP-P0-001 traceability

Each requirement, the commit that implements it, and the test that would catch
its removal. A row whose verification column names a document rather than a test
is a row that is not enforced, and there are none here by design.

| Requirement | Where it lives | What would catch its removal |
| --- | --- | --- |
| Local configuration is generated, never committed | `scripts/init_local_env.py`, `.gitignore` | `tests/test_init_local_env.py`; the generator refuses a target Git does not ignore |
| Generated passwords are never printed | `scripts/init_local_env.py` | `tests/test_init_local_env.py` asserts no code path emits a value |
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
| Health reports no component detail | `application.yaml` | `ApplicationConfigurationTest` |
| Readiness includes the datasource, liveness does not | `application.yaml` | `ApplicationConfigurationTest` |
| Migration runs as the owning role, the application as its own | `application.yaml` | `ApplicationConfigurationTest`, `ApplicationSmokeIT` |
| Schema destruction is disabled | `application.yaml` | `ApplicationConfigurationTest`, `FlywayMigrationIT` TC-DB-114 |
| A correlation identifier exists for every request | `CorrelationIdFilter` | `CorrelationIdTest`, `MetaStatusControllerTest` |
| A hostile identifier is replaced, never echoed | `CorrelationId` | `CorrelationIdTest`, `MetaStatusControllerTest` |
| The logging context does not leak between requests | `CorrelationIdFilter` | `MetaStatusControllerTest`, including the failure path |
| A failure response carries no internal detail | `GlobalExceptionHandler` | `GlobalExceptionHandlerTest`, `MetaStatusControllerTest` |
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
| Module internals are encapsulated | `ArchitectureRules` | TC-ARCH-001 and fixture F-1 |
| Modules are free of cycles | same | TC-ARCH-002 and fixture F-2 |
| A web resource does not reach the database | same | TC-ARCH-003 and fixture F-3 |
| Dependencies arrive through the constructor | same | TC-ARCH-004 and fixture F-4 |
| Time comes from the injected clock | same | TC-ARCH-005 and fixture F-5 |
| Diagnostics go to the logger | same | TC-ARCH-006 and fixture F-6 |
| The shared module depends on no other | same | TC-ARCH-007 and fixture F-7 |
| Every rule can still pass | `RuleSensitivityArchitectureTest` | Fixture F-8, all seven rules |
| The module structure verifies from its own model | `ModulithArchitectureTest` | TC-ARCH-008 |
| The console refuses missing or blank runtime configuration | `config.ts`, `App.tsx` | `config.test.ts`, `App.test.tsx`; no request is made and no platform value is rendered |
| The console reports seven states | `healthState.ts` | `healthState.test.ts` |
| A partial payload is not rendered | `metaStatus.ts` | `metaStatus.test.ts`, `HealthShell.test.tsx` |
| No stale platform value survives an outage | `HealthShell.tsx` | `HealthShell.test.tsx` |
| Only prefixed variables reach the bundle | `vite.config.ts` | `viteConfig.test.ts`, `verify-bundle-isolation.mjs` |
| Exactly two identifiers are replaced at build time | `vite.constants.ts` | `viteConfig.test.ts` |
| Backend coverage is at least 80% lines and 70% branches | `pom.xml` JaCoCo rules | `verify` fails below either threshold; `verify_coverage_thresholds.sh backend` proves the failure path |
| Frontend coverage is at least 80% lines, branches, functions and statements | `vite.config.ts` | `test:ci` fails below a threshold; `verify_coverage_thresholds.sh frontend` proves the failure path |
| Eleven checks report under stable names | `.github/workflows/` | The names are the job names; a rename is visible in the diff |
| Workflow actions and runners are immutable | `.github/workflows/` | TC-GLOBAL-001 and its negative tests reject mutable action refs, absent version comments and floating runners |
| Build metadata records the authored commit | `backend.yml` | The workflow passes the head commit, not the generated merge commit |
| Both ecosystems emit validated CycloneDX inventories | `pom.xml`, `package.json`, `collect_supply_chain.py` | `make supply-chain`; collector tests reject absent or malformed SBOMs and incomplete installed-package licences |
| A real browser proves the ready and database-outage paths | `playwright.config.ts`, `health-shell.spec.ts` | The scenario verifies correlation/CORS, stops PostgreSQL, observes `DOWN`/`degraded`, then restores it |
| A clone verifies with no local state or path restriction | `scripts/fresh_clone_check.sh` | The full check clones into a whitespace-and-apostrophe path, runs every Gate and rejects tracked-file mutation |
