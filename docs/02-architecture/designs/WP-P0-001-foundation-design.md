# MarketOps Russia — Repository, Governance & CI Foundation Design

```yaml
document_type: module foundation design
status: APPROVED_FOR_IMPLEMENTATION
work_package: WP-P0-001
product: MarketOps Russia
repository: marketops-platform
```

This is the canonical design for the repository, build, database, quality and CI
foundation of MarketOps Russia. It describes the current intended state of the
foundation. It is not an ADR and not a Decision Request.

---

## 1. Purpose

Provide the complete production-grade WP-P0-001 monorepo foundation in which a
fresh clone can validate the local toolchain, generate ignored local configuration
without exposing secrets, build and test the backend, enforce module boundaries,
start PostgreSQL and apply a strict foundation migration, prove database ownership
and least-privilege invariants, build and test the frontend health console, and
produce deterministic CI evidence — all without Marketplace credentials or
production data.

The foundation carries no business behaviour. It exists so that later work
packages inherit enforced boundaries, a least-privilege database, deterministic
builds, and evidence-producing CI from their first commit.

```text
DEFERRED_ITEMS_IN_WP_SCOPE: NONE
COMPROMISE_IMPLEMENTATION_ALLOWED: NO
```

---

## 2. Architecture

### 2.1 Shape

One deployable Modular Monolith backend, one single-page operations console, one
PostgreSQL database, orchestrated locally by a Docker-compatible CLI with a
Compose Specification file.

```text
marketops-console  ──HTTP──▶  marketops-server  ──JDBC──▶  marketops (PostgreSQL)
   (React SPA)                (Spring Boot)                 8 foundation schemas
```

### 2.2 Backend module model

The backend is a single Maven module. Boundaries are expressed as Java packages
under the root package `com.mimococo.marketops` and enforced by automated tests,
not by build-tool separation.

An application module is a direct sub-package of the root package. Modules are
closed: each module's `internal` sub-package is private to that module. A module
exposes API only through its own base package, or through additional packages
explicitly marked as named interfaces.

Two modules exist in the foundation:

| Module | Responsibility | Exposed API |
| --- | --- | --- |
| `shared` | Cross-cutting technical types: correlation identity, safe error codes | Base package only; `shared.internal` is private |
| `adminobservability` | Application metadata endpoint | Base package only; `adminobservability.internal` is private |

`shared` is declared a shared module so it is always present when a module is
bootstrapped in isolation for integration tests. Being shared does not open it:
its `internal` package remains inaccessible from other modules and it remains
subject to cycle detection.

The layering convention reserved for business modules is
`application / domain / port / infrastructure / adapter`. Marketplace adapters
belong under `marketplaceintegration.adapter.<platform>`; vendor SDK types must
not cross into domain code or appear in module API signatures. These directions
are enforced from the first commit even though no business module exists yet.

### 2.3 Frontend shape

A single private package, `marketops-console`, built with Vite. Its only runtime
responsibility in the foundation is a health shell: it polls the backend metadata
endpoint and renders a seven-state view of backend and data health. It holds no
credentials and performs no business function.

---

## 3. Technology lines and refresh policy

### 3.1 Lines

| Area | Line |
| --- | --- |
| Language / runtime | Java 21 (LTS) |
| Application framework | Spring Boot 4.1 |
| Module verification | Spring Modulith 2.1 |
| Boundary rules | ArchUnit 1.5 (`archunit-junit6`) |
| Build | Maven 3.9 via the committed Maven Wrapper |
| Database | PostgreSQL 18 |
| Migrations | Flyway, version managed by the Spring Boot BOM |
| Integration tests | Testcontainers, version managed by the Spring Boot BOM |
| Frontend runtime | Node.js 24 (Active LTS) |
| Package manager | npm with a committed `package-lock.json` |
| UI | React 19 + TypeScript 6.0 |
| Bundler / tests | Vite 8 + Vitest |
| Lint / format | ESLint flat config + Prettier |
| Local orchestration | Docker-compatible CLI + Compose Specification |
| CI | GitHub Actions on `ubuntu-24.04` |

### 3.2 Pinning rules

- Backend dependencies carry no explicit version when the Spring Boot BOM manages
  them. Build plugins outside the BOM are pinned to an exact version with a
  comment recording the official source and the date it was verified.
- The Maven Wrapper pins both the distribution URL and its SHA-256, and the
  wrapper JAR SHA-256. Apache publishes SHA-512, so the SHA-256 value is derived
  locally only after verifying the published SHA-512 and GPG signature.
- The frontend pins an exact Node patch in `.node-version`; `package.json`
  constrains `engines.node` to the Node 24 line; CI reads the same
  `.node-version` file. `package-lock.json` is authoritative for resolved
  versions and CI installs with `npm ci` only.
- GitHub Actions are referenced by full-length commit SHA with a version comment.
- The PostgreSQL image is pinned to a `major.minor` tag.

### 3.3 Refresh policy

Exact patch versions are volatile. Before build files are created or changed, if
the recorded verification date is more than seven calendar days old, the volatile
version snapshot is refreshed from official release indexes and registries, and a
difference table is recorded in phase evidence. Registry reads use the
authoritative `dist-tags` endpoint with cache-busting and repeated reads, because
single reads of a package's `latest` endpoint can return stale cached data. Any
selection that changes as a result is submitted for review.

A pinned version older than the current release is permitted only with a recorded
compatibility reason, and is never described as the latest release.

---

## 4. Repository structure

```text
marketops-platform/
├── .env.example                    variable names and blank assignments only
├── Makefile                        documented developer targets
├── .github/
│   ├── dependabot.yml
│   └── workflows/                  governance, backend, frontend, security
├── backend/marketops-server/       single Maven module, root package com.mimococo.marketops
│   ├── .mvn/wrapper/               distribution and wrapper checksums
│   └── src/
│       ├── main/java/…/            MarketOpsServerApplication, shared, adminobservability
│       ├── main/resources/         application.yaml, profile files, db/migration
│       └── test/java/…/            unit, architecture, fixtures, integration
├── frontend/marketops-console/     private package, health shell
├── infra/compose/                  Compose file and PostgreSQL role bootstrap
├── fixtures/                       synthetic data policy
├── scripts/                        local environment generator, doctor, validators
├── tests/                          validator unit tests
└── docs/                           governance, requirements, architecture, runbooks, evidence
```

Business modules and frontend feature areas are reserved by convention, not by
empty directories. Nothing is created before it has behaviour.

---

## 5. Configuration contract

### 5.1 Separation

| Class of value | Where it lives | Committed |
| --- | --- | --- |
| Non-secret constants (application name, ports, database name, role names) | YAML, Compose, SQL | Yes |
| Variable documentation | `.env.example` — comments, names, blank assignments | Yes |
| Local secret values | `.env.local` at the repository root | No |
| Frontend public settings | `frontend/marketops-console/.env.local` | No |
| CI database credentials | Generated at runtime by Testcontainers | Not applicable |

Common weak passwords and password-like placeholder values are prohibited
anywhere in the repository. The governance secret scan covers documentation,
README, runbooks, evidence, configuration, source, tests and workflow files with
no directory, documentation-block, or marker-based exclusion.

### 5.2 Local value generation

`make env-init` generates the ignored local files. The generator uses only the
Python 3 standard library and:

- refuses to overwrite an existing file unless forced, and refuses to force in a
  non-interactive shell;
- refuses to write to a path that is not ignored by Git;
- creates the file with owner-only permissions where the platform supports it;
- draws passwords from an alphanumeric alphabet so values need no escaping in a
  shell, a properties file, YAML, or a `psql` literal;
- prints variable names and a success or failure result, never a value.

Database role names are non-secret constants and are never generated. The
generator emits the superuser password, the migration role password, the
application role password, and the host port for the database container.

### 5.3 Loading

| File | Consumer | Mechanism | If missing |
| --- | --- | --- | --- |
| root `.env.local` | Compose | explicit `--env-file` with an absolute path | Compose fails; the Make target reports the initialization command first |
| root `.env.local` | Spring Boot | `SPRING_CONFIG_IMPORT` set to an absolute `file:` location, not optional | Startup fails before the context is created, naming the exact path |
| `frontend/marketops-console/.env.local` | Vite | Vite's own project-root loading, `VITE_` prefix only | The console renders a configuration-error state listing missing variable names |

The secret file is never copied into the backend directory, never symlinked,
never sourced by a shell, and never evaluated. Spring reads it with the
properties parser, so it cannot execute anything.

Make recipes, scripts, Compose invocations and configuration imports quote every
filesystem path. Make does not split or transform the repository path with Make
word functions; Python or shell helpers derive the root inside the helper and
pass each path as one quoted argument. Whitespace and single quotes in the clone
path are supported inputs, not exceptional cases.

### 5.4 Profiles

The base configuration sets the default profile to a name for which no profile
document exists, so starting without an explicit profile activates no
environment-specific configuration and fails fast on the unresolved environment
property. The local profile is always activated explicitly by the command that
starts the application. No profile silently behaves as production.

Local HTTP services bind to loopback.

---

## 6. Database and permission contract

### 6.1 Container

PostgreSQL 18 with the named data volume mounted at `/var/lib/postgresql`. In
this major version the data directory is version-specific beneath that mount
point; mounting the pre-18 path leaves the named volume empty and silently
directs the cluster to an anonymous volume that does not survive recreation.

Non-secret values — database name, role names — are literals in the committed
Compose file. Only passwords and the host port come from the ignored local file,
and Compose fails when they are absent.

### 6.2 Roles

Two non-superuser roles, named by committed constants:

| Role | Purpose |
| --- | --- |
| `marketops_migration` | Executes migrations; owns the foundation schemas |
| `marketops_app` | Application runtime datasource |

Role creation runs once, during first initialization of an empty data directory,
from an executable shell script. The script stops on the first SQL error, passes
passwords as `psql` variables interpolated with the quoted-literal form, and
never echoes a password — including on failure, where captured error output is
withheld because a failing statement can repeat its own text.

### 6.3 Privileges

| Privilege | `marketops_migration` | `marketops_app` |
| --- | --- | --- |
| `LOGIN`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE` | yes | yes |
| `CONNECT` on the database | yes | yes |
| `CREATE` on the database | yes | no |
| `USAGE` on schema `public` | yes | no |
| `CREATE` on schema `public` | yes | no |
| Owner of the eight foundation schemas | yes | — |
| `USAGE` on the eight foundation schemas | yes | yes |
| `CREATE` on any schema | own schemas only | no |
| Table-level data privileges | — | none in this foundation |

`CREATE` and `USAGE` on schema `public` are both revoked from `PUBLIC`.
PostgreSQL grants `USAGE` on `public` to `PUBLIC` by default, so revoking
`CREATE` alone would leave the application role able to reach the migration
history table. The application role's search path excludes `public`.

No blanket data privileges and no default privileges are granted. Object-level
privileges are granted by the work package that introduces the corresponding
objects, according to that object's actual invariant.

### 6.4 Foundation migration

One versioned migration creates exactly eight schemas — `iam`, `platform`, `raw`,
`staging`, `core`, `ledger`, `mart`, `ops` — each with an explicit authorization
clause naming the migration role, and grants schema usage to the application
role.

Schema creation is strict: it does not tolerate an existing schema. A database
that already contains one of these names is either contaminated or partially
initialized, and either way the owner cannot be assumed correct. Failing is the
required behaviour; silently adopting a foreign-owned schema would defer the
fault to a later work package where it would present as an unexplained privilege
error.

PostgreSQL applies migrations transactionally. A failed migration is rolled back
in full and leaves no migration-history row, so recovery is to discard and
recreate the local volume. History repair is not part of this contract.

Migration files are never renumbered or edited after they reach `main`. Errors
are corrected by a new forward migration. There are no application or domain
tables in this foundation; the migration history table is the only table.

---

## 7. Health, metadata and logging contract

### 7.1 Exposure

Only the health and info endpoints are exposed over HTTP. Environment,
configuration properties, beans, mappings, loggers and migration endpoints are
not exposed.

Health detail is never rendered; component names and their status are. Readiness
includes the datasource. Liveness does not: a process whose database is
unavailable is degraded, not dead, and reporting otherwise causes an orchestrator
to restart a healthy process.

### 7.2 Application metadata

A single metadata resource serves the console. Its response is an explicit
allowlist: product, application, environment, build version, source commit,
server time, database status, migration version, and correlation identifier.

It never emits a connection string, a database user, a password, a search path, a
filesystem path, a full migration description, a dependency inventory, an
environment dump, or a stack trace.

The resource answers successfully even when the database is unavailable, marking
the database status accordingly, so the console can distinguish an unreachable
backend from a reachable backend with a degraded data layer.

### 7.3 Build metadata

Build version comes from the build system. The source commit is supplied by CI
and falls back to a literal unknown value locally; an unknown commit in local
development is a normal state, not a failure.

CI distinguishes the commit that a contributor actually authored from the
temporary merge commit that the pull-request check tested. Build artifacts carry
the authored commit, because the merge commit ceases to exist once the pull
request is merged. Evidence records the authored commit, the tested merge commit,
and the merge base.

Build time is deliberately absent from the contract. A wall-clock timestamp makes
two builds of one source tree differ, which contradicts the reproducibility this
foundation is meant to provide, and the source commit already identifies the
build input.

### 7.4 Correlation identity

An inbound correlation header is accepted only when it is at most sixty-four
characters and contains only ASCII letters, digits, dot, underscore, colon and
hyphen. Control characters and non-ASCII input are rejected. A missing or
rejected value is replaced by a generated identifier. A rejected value is never
placed into the logging context or a response header, and is never echoed; only
the rejection reason category is recorded.

### 7.5 Errors and logs

Failures are returned as problem details carrying a safe message and the
correlation identifier. Stack traces, SQL, and configuration never reach a
response body.

Local logs are single-line and human readable; non-local profiles emit structured
records. Every record carries the correlation identifier, application name,
environment and build version. Tokens, secrets, connection strings, personal data
and large payloads are never logged.

---

## 8. Continuous integration contract

### 8.1 Checks

Eleven stable check names:

```text
governance             backend-build           architecture-boundary
backend-integration    frontend-lint           frontend-typecheck
frontend-test          frontend-build          dependency-review
codeql-java            codeql-typescript
```

The C0 design approval Pull Request uses only the existing `governance` check,
because it contains no product implementation or new workflow jobs. The
implementation Pull Request creates and stabilizes all eleven named jobs. After
those exact names have run successfully on the current implementation Pull
Request, the Human Owner adds all eleven names to the Ruleset before that Pull
Request merges. The implementation Pull Request must not merge until every one of
the eleven checks is both required and green.

Security results are not advisory: an unresolved high or critical finding blocks
a merge irrespective of a job's own conclusion.

### 8.2 Common rules

Explicit `ubuntu-24.04` runners; explicit timeouts; least-privilege workflow
token permissions; actions referenced by immutable full-length commit SHA with a
version comment; no path filters on required checks; no workflow trigger that
grants write permissions to untrusted code; no Marketplace credentials; no
self-hosted runners. Uploaded artifacts and printed logs carry no environment
values or credentials.

The `governance` check keeps its name and triggers. Its validator gains rules as
the repository grows; that is the check doing its job, not a change of identity.

### 8.3 Backend jobs

Java 21 is set up explicitly and the committed Maven Wrapper is the only build
entry point. Compilation, unit tests and packaging run separately from
integration tests; architecture rules run as their own check; integration tests
run the full verification including containerized database tests. Jobs that
produce or start a backend artifact inject the authored source commit.

The architecture check proves that exactly the three expected architecture test
classes ran, by comparing the set of test report file names to the expected set —
not by counting test methods, which changes whenever a case is added.

### 8.4 Frontend jobs

Node is installed from the committed version file. Dependencies install with
`npm ci` only. Lint, format check, type check, unit and component tests,
browser-level smoke, and the production build run as separate checks. The build
job injects the authored source commit.

The build output is checked deterministically rather than by scanning for
suspicious English words, which produces false positives from dependency
messages. Three assertions apply: the output contains none of the backend
configuration name prefixes; a unique non-sensitive value present in the build
environment but absent from the bundler's compile-time allowlist does not appear
in the output; and the output contains no environment file, dump or configuration
snapshot. A validator asserts that the compile-time allowlist contains exactly
the two approved keys.

### 8.5 Security jobs

Java analysis uses Java 21 and a manual build driven by the project's own Maven
Wrapper, so it obeys the same toolchain contract as the ordinary backend build.
TypeScript analysis uses the no-build mode, the only supported mode for
interpreted languages.

Dependency review blocks additions carrying high or critical known
vulnerabilities. License enforcement is not asserted by this job. A dependency
license inventory is produced for both backend and frontend; permissive,
clearly-identified licenses proceed, while an unknown license or a strong or
network copyleft license stops the change for an explicit Owner decision.

Software bills of materials are produced for backend and frontend.

Security evidence records five separate facts: the workflow conclusion, whether
the analysis database was uploaded, the count of open critical findings, the
count of open high findings, and the reviewer's disposition. A successful
analysis job does not by itself demonstrate the absence of findings.

Dependency updates are automated for the actions, Maven and npm ecosystems.

---

## 9. Tests and evidence

### 9.1 Backend

Unit tests for the metadata allowlist and the correlation contract; context smoke
tests; error-response tests; migration tests covering clean application,
validation, second-run idempotency, and the transactional rollback of a failed
migration against a contaminated database; schema ownership assertions; negative
privilege assertions proving the application role cannot create schemas or
tables, cannot alter or delete migration history, and holds nothing on `public`;
module verification; boundary rules; dependency resolution assertions; and
packaged artifact checks. Coverage is measured and enforced with narrow,
documented exclusions.

The rollback test asserts the database error code by searching the exception
cause chain, not by matching an exception class or a localized message, and it
discards its container rather than repairing or reusing it.

### 9.2 Architecture rules

Eight dependency prohibitions are realised as seven executable rule factories:
the application and port prohibitions are the two independently exercised halves
of one composite rule. A shared conformance scenario proves the permitted inward
adapter/infrastructure dependencies, and an independent Modulith rule verifies
the framework-derived module model.

Rules are defined once in a shared factory and used unchanged by both the
production check and the sensitivity check. The production check runs the seven
rules against production classes. The sensitivity check runs the same rule
objects against ten deliberately invalid observations: ordinary and
prefix-collision internal access, a cycle, shared reaching outward, domain
reaching outward, application and port reaching outward independently, an SDK
outside a platform adapter, and SDK signatures in both domain and a module API.
One conforming fixture must pass every prohibition.

Only the domain rule and the application and port halves of the composite rule
permit an empty subject, because the foundation legitimately contains no
business layers yet. Once a matching layer exists the corresponding rule is
fully enforced. The global setting that would disable empty-subject protection
is never used; every rule over the existing application tree keeps the default.

The conformance scenario expresses a permission — that inward dependencies from
adapters and infrastructure are legitimate. A permission has no assertion of its
own; it is verified by showing that the prohibitions do not fire on a valid
sample. It is not counted as an eighth rule.

Fixtures live under test sources only and never reach a production artifact. They
reference locally-declared stand-in types, never a real Marketplace SDK.

### 9.3 Frontend

Lint, format check, type check, unit and component tests including all seven
health-shell states, retry and backoff behaviour, configuration-error handling,
and build metadata; coverage thresholds; a browser-level smoke test that
exercises the rendered shell against backend metadata rather than merely loading
a page; the production build; and the build-output assertions in §8.4.

### 9.4 Fresh clone

From a clean clone, the documented sequence installs nothing on the host,
generates every required ignored local file, starts the database, and runs the
full backend and frontend verification without an undocumented manual step.

The acceptance suite repeats the fresh-clone sequence from a temporary clone
whose path contains both a space and an apostrophe. Environment generation, Make
targets, Compose commands, backend configuration imports and frontend commands
must all pass from that path without renaming or relocating the clone.

### 9.5 Evidence

Evidence is sanitized before it is committed. Command transcripts, CI output,
container output, health responses, environment diagnostics and screenshots are
reviewed for secrets, passwords, tokens, personal data, user names and email
addresses, private paths, internal host names, unrelated project names and
confidential business information. Raw environment dumps are never committed;
evidence is an excerpt selected for review, not a screen capture.

### 9.6 Global foundation hard rules

Three repository-wide checks express functional acceptance intent and run against
the implementation Pull Request:

- **Compromise Retirement Check** fails when an in-scope capability is skipped,
  disabled, replaced with simulated behaviour, left as a placeholder, or made to
  pass through a weakened assertion. It detects unresolved `TODO`, `FIXME` and
  `HACK` markers, unused dependencies, disabled workflows, parallel legacy/new
  configuration, transitional scripts and path restrictions that avoid correct
  quoting. It also verifies the implementation evidence declares no deferred
  in-scope item.
- **Functional JavaDoc Rewrite Check** verifies JavaDoc, TSDoc, shell and YAML
  comments, and knowledge documentation explain only current purpose, inputs,
  outputs, security boundaries, invariants and failure behaviour. Boilerplate,
  generated restatements, version or stage history, Controller-finding narrative,
  and instructions to remove something later do not satisfy the check.
- **Production Naming Check** rejects placeholder, scaffold, temporary, demo and
  example terminology in production package names, artifacts, runtime
  configuration and user-facing identifiers. Explicit test-only fixture names are
  permitted only under test source roots and cannot enter production artifacts.

---

## 10. Operational constraints

- The repository is public during pre-production. It must return to private
  before production go-live, or earlier if confidential material would be
  committed, and repository and security controls must be revalidated at that
  point.
- Changes reach `main` only through a pull request with the required checks
  green. Nothing is pushed directly to `main`.
- The application must not be deployed to any publicly reachable environment
  before identity and access control exist.
- Local development requires a Docker-compatible CLI with Compose support;
  no runtime vendor is assumed.
- The primary development platform is macOS. All supported commands also accept a
  repository path containing whitespace and single quotes.
- Marketplace credentials and production data are never introduced by this
  foundation.

---

## 11. Non-goals

The following are outside this foundation and are absent entirely — not stubbed,
not partially wired, and not represented by placeholder code, packages, tables,
configuration or workflows:

```text
Ozon or Wildberries API clients        Marketplace credentials
authentication and authorization       platform write operations
business or domain tables              production deployment artifacts
Kafka                                  Kubernetes
microservice separation                a business dashboard
```

---

## 12. Naming

| Element | Name |
| --- | --- |
| Product display name | MarketOps Russia |
| Repository | `marketops-platform` |
| Backend artifact and application | `marketops-server` |
| Frontend package | `marketops-console` (private, unscoped) |
| Java root package and Maven group | `com.mimococo.marketops` |
| Database | `marketops` |
| Database roles | `marketops_migration`, `marketops_app` |
| Backend environment prefix | `MARKETOPS_` |
| Frontend public environment prefix | `VITE_MARKETOPS_` |

Production identifiers never contain placeholder or scaffold terms. Test fixtures
may use explicit test-only names under test source roots, and never appear in
production source, artifact names, runtime configuration, user-facing text or
canonical documentation.
