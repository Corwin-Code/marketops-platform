# S1-F010 — managed PostgreSQL bootstrap compatibility decision

```yaml
document_type: decision_request
status: PROPOSED_PENDING_OWNER_CONTROLLER_DECISION
identified_at: 2026-08-28
slice: SLICE-V1-001
existing_finding: S1-F010
new_frozen_finding: NONE
implementation_authority_for_workaround: NOT_GRANTED
accepted_amendment: NONE
deployment: NOT_AUTHORIZED
```

## Decision needed

Confirm a supported Yandex Managed PostgreSQL bootstrap path that preserves the
immutable V0001–V0010 files and their required execution/validation semantics.
No such path has been established by the current evidence. If a different
bootstrap/history or database compatibility policy is required, the Owner must
accept an exact additive Amendment, independently reviewed by the Controller,
before Codex implements it. This document is a request, not that Amendment.

The next action for this constraint is Owner/Controller disposition. Independent
authorized rework on tests, coverage and CI can continue without changing it.
It is not Final Closure Verification: the rework is incomplete, and all thirteen
Frozen Findings remain open.

## Conflicting constraints

1. ADR-0007 fixes Yandex Cloud `ru-central1` and managed PostgreSQL. The current
   database module selects PostgreSQL 18; local verification uses PostgreSQL
   18.4. This agent cannot silently replace the provider or managed service.
2. The rework authority makes V0001–V0010 byte-immutable and requires the clean
   migration chain. V0002 strictly executes
   `CREATE EXTENSION btree_gist WITH SCHEMA public;`. Its comments explicitly
   reject an extension installed outside migration history.
3. Yandex's official documentation says managed PostgreSQL does not support
   extension management through SQL. Its supported-extension table also shows
   `-` for `btree_gist` under PostgreSQL 18, so support for that selected version
   cannot be assumed. See the [official service documentation](https://yandex.cloud/ru/docs/managed-postgresql/operations/extensions/cluster-extensions)
   (updated 2026-08-21) and its [exact English source revision](https://github.com/yandex-cloud/docs/blob/6837ef084e1686f2c202fa716956943ffae703b7/en/managed-postgresql/operations/extensions/cluster-extensions.md).

The public service contract and repository requirements therefore do not
currently establish a supported clean deployment path. This is an inference
from those constraints, not a claim that a live Yandex request was attempted.
No Yandex account, provider credential or production database was used.

## Reproduction and evidence boundary

`FlywayMigrationIT.preinstalledExtensionDoesNotBecomeAnAppliedMigration`
(`TC-DB-115`) starts an isolated PostgreSQL 18.4 container, executes V0001 through
Flyway, installs `btree_gist` as the separate administrator, then invokes the
unchanged migration chain. The test requires SQLSTATE `42710`, history containing
only V0001, the preinstalled extension remaining present, and V0003's audit table
remaining absent. It passes. A valid V0001 history does not make preinstallation
a solution to strict V0002.

The same run proves clean local migration through V0027, Flyway validation and
idempotent rerun. `ManagedMigrationRunnerIT` also rejects the application
identity and drift in role inheritance, owning-role membership, database CREATE
or TEMP privileges, and `public` access. These are local PostgreSQL proofs, not
managed-service proofs. The new runner is a candidate deployment tool; it does
not resolve this compatibility request.

Exact command (from `backend/marketops-server`):

```bash
./mvnw -B -Dtest=ManagedMigrationRunnerTest,ApplicationConfigurationTest -Dit.test=ManagedMigrationRunnerIT,FlywayMigrationIT integration-test failsafe:verify
```

Result: **26 unit tests and 13 integration tests passed; BUILD SUCCESS**.
This is a focused package/integration run, not a full `verify` or a coverage pass.
The packaged server still names `MarketOpsServerApplication` as its entrypoint;
the separate migration CLI refuses missing explicit arguments with exit 1 and
only `MIGRATION_FAILED` output. Machine-readable identities and report hashes
are in [migration-compatibility-evidence.json](migration-compatibility-evidence.json).

Terraform 1.14.9 / Yandex provider 0.220.0 `fmt`, `init -backend=false`, `validate`
and mock-only plans previously passed for bootstrap, staging and production.
Those results validate provider schema and synthetic wiring, not managed
database migration semantics. They cannot override this blocker or prove a
deployable environment. No `apply` or real state inspection was performed.

## Why ordinary in-scope fixes cannot resolve this yet

- Preinstalling the extension through the provider does not change strict
  V0002's duplicate-extension behavior, and PostgreSQL 18 support is unproven.
- V0027+ cannot repair a chain that stops at V0002.
- Editing V0002, filtering/replacing its SQL, using a custom resolver to skip it,
  setting a baseline or manually writing migration history would change the
  protected execution guarantee. None is authorized or implemented.
- A self-hosted database, alternate provider, downgrade or relaxed privilege
  model is not an implicit implementation choice for this rework.
- A green mock plan or a successful vanilla PostgreSQL test is not evidence of
  managed-service compatibility.

This request is a transitive constraint within existing S1-F010; it neither
adds a fourteenth Frozen Finding nor changes the supplied review artifacts.

## Required resolution and verification

The Owner/Controller disposition must establish the exact supported service and
PostgreSQL/extension versions, extension ownership and privileges, how V0001–
V0010 bytes and history remain verifiable, and the permitted clean-install and
upgrade procedure. If a provider-supported path preserves the current rules,
attach current primary evidence identifying that path. Any normative exception
instead needs a separately accepted additive Amendment; a general instruction
to continue or apply Terraform is insufficient.

Subsequent implementation must retain exclusion constraints, the application
versus migration role boundary, exact checksums, clean/upgrade validation and
restore evidence. Do not grant deployment, real credentials, Gate EV, Gate E or
production enablement as a side effect of resolving this engineering decision.

## Preserved state and remaining work

The branch and PR #20 remain at the original reviewed commit
`30d16e5d7db2d2190635a06fececd5883093a876`, tree
`13b1b789cd4cff292d0d6ab24daca976afbba6da`, with thirteen original commits above
base `89fc29be45327b592a9bcbeffbfec54c96fb66ed`. Rework changes remain local and
uncommitted. PR #20 was rechecked OPEN / DRAFT / UNMERGED on 2026-08-28.
The original Contract, V0001–V0010 and Frozen Finding Set are unchanged.

See [progress-checkpoint.md](progress-checkpoint.md) for the incomplete coverage,
infrastructure, performance/export/restore, canonical-document and CI work.
No finding, criterion, Slice or release is declared closed by this request.
