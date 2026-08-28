# SLICE-V1-001-AMENDMENT-001 — Yandex Managed PostgreSQL Extension Bootstrap Compatibility

```yaml
document_type: additive_slice_contract_amendment
amendment_id: SLICE-V1-001-AMENDMENT-001
title: Yandex Managed PostgreSQL Extension Bootstrap Compatibility
slice: SLICE-V1-001
original_contract_path: docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md
original_contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
existing_finding: S1-F010
frozen_finding_set_sha256: 8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8
status: PROPOSED_PENDING_EXACT_HUMAN_OWNER_ACCEPTANCE
product_scope_change: NONE
business_behavior_change: NONE
production_provider: YANDEX_MANAGED_POSTGRESQL
production_region: ru-central1
production_postgresql_major: "17"
deployment: NOT_AUTHORIZED
credentials: NOT_AUTHORIZED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
production_write_enabled: false
```

## 1. Contract defect and purpose

The immutable migration `V0002__enable_btree_gist_extension.sql` strictly executes:

```sql
CREATE EXTENSION btree_gist WITH SCHEMA public;
```

and deliberately fails when the extension already exists. Yandex Managed Service
for PostgreSQL requires extensions to be managed through the service control
plane rather than SQL. The current Yandex extension matrix supports
`btree_gist` and `pgcrypto` for PostgreSQL 17 but does not establish them for
PostgreSQL 18.

Therefore no supported clean-install path can simultaneously preserve all of
these unamended statements:

1. Yandex Managed PostgreSQL is the V1 database service;
2. production uses PostgreSQL 18;
3. the provider preinstalls the extension;
4. unchanged V0002 executes successfully;
5. preinstallation is treated as invalid drift.

This Amendment preserves the product and database-integrity outcome while
creating one explicit managed-service compatibility profile. It does not edit
V0001–V0010.

## 2. Binding Owner decision

### A-001 — Preserve the managed service

V1 staging and production remain on Yandex Managed Service for PostgreSQL in
`ru-central1`. The project does not switch to self-hosted PostgreSQL, another
cloud provider or another database service to solve this issue.

### A-002 — Pin the managed major version to PostgreSQL 17

Yandex staging and production are pinned to PostgreSQL major version `17`.

A PostgreSQL 18 upgrade is prohibited until:

- current official Yandex evidence explicitly supports every required extension;
- the managed bootstrap profile passes clean-install, upgrade, restore and
  schema-equivalence tests for PostgreSQL 18;
- a separate reviewed change authorizes the major-version upgrade.

The local compatibility matrix may continue testing PostgreSQL 18, but it is not
the V1 managed-production target.

### A-003 — Provider-managed extension lifecycle

For the Yandex managed profile, Terraform/Yandex control-plane configuration is
the sole extension lifecycle authority.

At minimum, the database resource declares:

```text
btree_gist
pgcrypto (while the current IaC/application contract requires it)
```

The migration role and application role must not create, alter or drop managed
extensions through SQL. The application role must not own an extension.

### A-004 — V0001–V0010 remain byte-immutable

The original V0001–V0010 files, paths, Git blobs and SHA/checksum inventory remain
unchanged.

The standard PostgreSQL profile continues to execute V0002's exact SQL and
retains its strict duplicate-extension refusal.

### A-005 — Managed profile uses an externally satisfied V0002 executor

The Yandex managed migration runner may replace **execution**, but not identity,
of V0002 only in the explicit `YANDEX_MANAGED` profile.

The implementation must use Flyway's supported extension API
(`MigrationResolver` / `MigrationExecutor`, or an equivalent independently
reviewed public Flyway API) so that:

- the built-in SQL resolver does not simultaneously expose V0002 in the managed
  profile;
- the resolved migration version, description and Flyway checksum equal the
  canonical V0002 identity;
- Flyway itself records V0002 in `flyway_schema_history`;
- the executor performs no extension DDL;
- the executor fails closed unless every precondition below passes.

Direct SQL insertion into `flyway_schema_history`, `baselineOnMigrate`, an
environment-wide baseline, `repair`, migration filtering without attestation,
editing V0002, `IF NOT EXISTS`, or silent checksum substitution are prohibited.

`skipExecutingMigrations` / `cherryPick` from a paid Flyway edition is not
authorized by this Amendment. Introducing a paid Flyway edition requires a
separate Owner cost/licensing decision.

### A-006 — Required managed V0002 attestation

Before Flyway may record V0002 as successful, the managed executor must verify:

```text
service profile == YANDEX_MANAGED
server major == 17
database identity matches the intended environment
btree_gist exists
btree_gist version matches the current Yandex-supported PG17 version
btree_gist objects are in the expected secure schema
pgcrypto exists when required by the current environment contract
extension owner is not the application role
application role cannot create/drop/alter extensions
migration role cannot use SQL to manage provider-controlled extensions
canonical V0002 path/blob/SHA-256/Flyway checksum match the pinned manifest
provider/IaC evidence declares the same extension set
no existing schema-history row conflicts with V0002
```

Any missing, ambiguous or mismatched fact aborts before V0003.

### A-007 — Durable redacted bootstrap evidence

Each managed migration attempt produces a machine-readable, Secret-free evidence
artifact containing at least:

```text
repository commit/tree
environment identifier as an opaque reference
service profile and PostgreSQL major
canonical V0002 SHA-256 and Flyway checksum
resolved migration identity and executor mode
extension names/versions/schemas/owners
role and privilege assertions
Terraform/provider plan or applied-resource evidence identity
Flyway history before/after
migration result
timestamp and correlation identifier
```

The evidence artifact is hash-pinned in the release evidence set. It is not a
substitute for a real Yandex staging verification.

### A-008 — Clean-install procedure

The permitted Yandex clean-install sequence is:

```text
reviewed Terraform plan
→ provision Yandex PG17 database and provider-managed extensions
→ verify extension/control-plane state
→ run the dedicated managed migration runner
→ execute canonical V0001
→ attest and record canonical V0002 without extension DDL
→ execute canonical V0003 through the current latest migration
→ Flyway validate
→ schema/constraint/privilege equivalence check
→ application smoke with production writes disabled
→ capture redacted bootstrap evidence
```

No production apply is authorized by accepting this Amendment.

### A-009 — Upgrade procedure

For an existing database:

- a standard-profile V0002 history row remains valid only when it came from exact
  SQL execution;
- a managed-profile V0002 history row remains valid only when its canonical
  checksum and matching managed bootstrap evidence exist;
- missing/mismatched evidence, checksum, extension, major version or role
  boundary is a hard stop;
- V0003+ upgrades remain normal forward Flyway migrations;
- no tool may rewrite historical success to hide drift.

### A-010 — Required equivalence and negative tests

Before Final Closure Verification, executable tests must prove:

1. standard PostgreSQL 17 clean migration executes exact V0001–latest;
2. managed-profile PostgreSQL 17 emulation preinstalls provider extensions,
   denies SQL extension management and completes through latest;
3. standard and managed profiles have equivalent application schemas,
   constraints, indexes, functions, privileges and route inventory;
4. V0002 version/description/checksum match across histories;
5. absent/wrong-version/wrong-schema/wrong-owner extension fails before V0003;
6. PostgreSQL 18 in the managed profile fails closed;
7. application or migration role privilege drift fails closed;
8. missing/mismatched bootstrap evidence fails closed on rerun/upgrade;
9. accidentally exposing both canonical SQL V0002 and managed V0002 fails tests;
10. restore and upgrade retain migration and bootstrap evidence integrity.

A real Yandex staging run remains required before `S1-AC-005`/`S1-AC-006` can be
declared fully verified.

## 3. Preserved invariants

This Amendment does not change:

- MarketOps V1 product behavior;
- SLICE-V1-001 business outcome or Acceptance IDs;
- exclusion constraints or the need for `btree_gist`;
- immutable V0001–V0010 bytes;
- migration-role versus application-role separation;
- exact checksums and Flyway validation;
- provider fail-closed behavior;
- deployment, Credential, Gate EV, Gate E or production-write authority.

## 4. Rejected alternatives

```text
Assume PostgreSQL 18 extension support
Preinstall and still execute strict V0002
Edit V0002 or add IF NOT EXISTS
Add V0027+ to repair a failure at V0002
Manually insert/repair Flyway history
Use a generic baseline or silently skip V0002
Self-host PostgreSQL 18
Switch cloud/database provider
Relax application/migration privileges
```

Each either lacks current provider support, breaks before V0003, weakens
immutability/auditability, or creates a larger operational and product-contract
change than the managed compatibility profile.
