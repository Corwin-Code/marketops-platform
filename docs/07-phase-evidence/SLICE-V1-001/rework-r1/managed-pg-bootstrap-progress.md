# Managed PostgreSQL Amendment-001 implementation progress

```yaml
status: IN_PROGRESS_LOCAL_PG17_CHECKPOINT_PASS
amendment_sha256: 8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d
managed_target: YANDEX_MANAGED_POSTGRESQL_17
terraform_apply: NOT_EXECUTED
real_yandex_verification: NOT_PERFORMED
```

## Latest full local checkpoint

Independent `make backend-integration` at 87 passed 836 unit and 355 integration
tests with unchanged coverage gates (LINE 11766/14208, BRANCH 3141/4384).
Its input manifest, reports, artifact hash and synthetic bootstrap/attempt records
are under `managed-profile-87/` and `verification-inputs-managed-87.json`.
The earlier full `make backend-check` at 79 also passed; 87 adds exact JAR and
database URL hashes to migration evidence for the runtime release boundary.

`packaged-runtime-99/summary.json` binds the same JAR, SHA-256
`4ee1bd781a6f5e97120258951c3420175539ce4356e719f4e705611373d2e6c1`,
to the actual packaged resolver inventory and two locally built images. The
probe verifies all 27 migration hashes and that only SQL V0002 is hidden from
the managed resolver. Both image builds enforce the JAR hash; wrong-hash build
and missing-envelope execution fail. The execution container has no network,
database connection or credential. Its build context contains only the JAR and
four named public runtime files, with a separate empty Docker auth config.

`terraform-sequencing-100/summary.json` records passing state-bootstrap and both
environment plans: 65 foundation resources, a rejected missing-migration-proof
runtime, then 73 resources for the full synthetic runtime. These were initialized
from the existing local provider mirror after registry EOF failures; unchanged
lockfile checksums still apply. The report SHA-256 is
`bcb6916dfb0c6435c0388f4070c2fb19eef822efb4d832cb20908ad9fc15ae59`.
No apply or Yandex API call occurred.

Host bootstrap checks the successful result's environment, JDBC URL hash,
extension/role/history facts and artifact before starting runtime. Docker does
not independently restart these containers; systemd supervises both children
and reruns validation and secret delivery, removing only labelled owned
containers. Local runtime/input-example checks at 102 pass 21 Python tests.
This is not a real VM reboot, alert delivery, representative recovery drill,
final exact-commit verification or Controller closure verdict.

## Earlier focused implementation evidence

The exact Amendment and supplied decision package were hash-verified at intake.
The original Slice Contract and V0001–V0010 remain byte-identical to
`origin/main`; standard-profile V0002 still contains and executes its exact
strict SQL.

The candidate database module now pins PostgreSQL 17 and declares
`btree_gist` plus `pgcrypto` on the Yandex database resource. The provider
0.220.0 schema exposes extension names but no version attribute, so versions
must be attested from `pg_extension` by the managed migration executor rather
than invented in Terraform. The plan validator rejects PostgreSQL 18, missing,
extra or renamed extensions, credential persistence and the existing topology
mutations.

Local mock-plan checkpoint 56 used Terraform 1.14.9 and pinned provider 0.220.0:

| Plan | Resources | SHA-256 | Result |
| --- | ---: | --- | --- |
| bootstrap | 11 | `1315672f52aea5853701cac61256eef61b7c8b70c98b99af049ab525b690c08a` | PASS |
| staging | 73 | `8fcf62c61e854e4716aabf8475b06cad937745a0790774da61e5bd0d4ac90c84` | PASS |
| production | 73 | `a170266d7fe6bc9a13f0447c528694df0cf7fa7a1c078537a31ce2c86f929719` | PASS |

These are synthetic mock plans. They make no provider API call and do not prove
that a real Yandex resource or state has these values.

The managed Flyway resolver/executor uses Flyway 12.4.0's public extension seam.
Only its explicit resource provider hides canonical SQL V0002; the custom
executor exposes the same version, description, script, type and Flyway checksum
and performs no extension DDL. The standard runner still executes exact V0002.
The production entry point accepts only `YANDEX_MANAGED`, a private TLS Yandex
database URL, hash-bound applied-resource evidence and a closed manifest before
it reads the mounted migration credential.

Focused checkpoint 77 passed 23 unit tests and three PostgreSQL integration
tests. Its log SHA-256 is
`8e5f7c0fa366fdc64282cf88d6a751239a2a626292c11eb8d6c4f63d7bb4208c`.
Its retained artifacts are in `managed-profile-77/`; repository/provider IDs
inside fixture JSON are synthetic, not actual release identities. Checkpoint 78
also passed 23 unit and three integration tests after adding actual database
extension-version drift and CREATE/ALTER/DROP denials for both runtime roles.
The earlier full checkpoint 72 passed 832 unit and 355 integration tests,
with LINE 11622/14055 and BRANCH 3060/4270. Its source manifest and reports are
in `managed-profile-72/`. That full run predates the evidence/upgrade corrections;
it is not a final-source verification claim.

| Amendment A-010 case | Local result |
| --- | --- |
| standard PG17 clean V0001–V0027 | PASS |
| managed PG17 preinstalled-extension emulation | PASS |
| application schema/constraint/index/function/privilege equivalence | PASS |
| V0002 version, description, type, script and checksum equivalence | PASS |
| absent/wrong-schema/wrong-owner extension, individual member schema drift, actual wrong extension version | REFUSED |
| managed PG18 | REFUSED before V0003 |
| role drift and SQL extension DDL by runtime roles | REFUSED |
| missing or mismatched applied-resource/bootstrap evidence | REFUSED |
| simultaneous standard and managed V0002 resolvers | REFUSED |
| clean replay with exact evidence | PASS; missing replay evidence refused |
| new release commit/tree/provider evidence with original hash-pinned bootstrap | PASS; original bootstrap bytes retained |
| V0003 failure after V0002, resume to V0010, then upgrade to V0027 | PASS; no baseline/repair/history rewriting |
| isolated PG17 dump/restore, canonical history and bootstrap evidence replay | PASS; schema-only fixture, not representative data or Yandex PITR |

Canonical V0002 remains SHA-256
`438f67ccf3c2f640a1e7a4e325e24fb60d1eb4f363ab545e1e69babba202db16`
and Flyway checksum `1291326236` in both histories. The managed executor checks
PG17, database identity, extension version/schema/owner, member schemas and role
boundaries on every run. The production manifest binds applied-resource evidence
to the same opaque environment and exact TLS JDBC URL hash. The closed classpath
inventory discovers all packaged migrations, rejects duplicates/gaps, and hides
only canonical SQL V0002 from Flyway's SQL resolver.

An immutable started record precedes credential access; an attested record
precedes migration. Flyway commits canonical V0001/V0002, then the runner saves
the original bootstrap before V0003+. Each success/refusal has a separate result
record containing the current release identity and observed before/after history.
Expected and observed server/extension facts are separate, including refusals.
Publication uses fsync and atomic non-overwriting hard links. A replay/upgrade
must pin the original bootstrap hash; it may have a new commit/tree/provider
record without rewriting the old bootstrap. Missing evidence after history is
recorded is a hard stop; neither repair nor a baseline path exists. A crash leaves
started/attested evidence and an unknown outcome, requiring reconciliation before
rollout. The deployment evidence mount must be persistent, not credential tmpfs.

The PG17 fixture uses local `pg_dump -Fc` and `pg_restore` as the isolated
cluster administrator to emulate provider restore. It restores the original
Flyway history, extension ownership, role grants and SQL DDL denial trigger;
the managed runner then validates and applies zero migrations. Application
schema, function bodies, constraints, indexes, privileges and route inventory
are compared with the standard profile. No migration/application identity runs
restore DDL.

A representative-data failure/restore drill, container restart and deployment
sequencing, final source verification, remote CI and a real Yandex staging run
remain outstanding. The prior-release test uses an isolated classpath containing
the exact protected V0001–V0010 files; both old and new histories are written only
by Flyway. Mock plans and local emulation are not provider evidence.

Official-source identity remains pinned in `migration-compatibility-evidence.json`.
Yandex documents that managed clusters do not permit SQL extension management,
and its PG17 matrix lists `btree_gist` 1.7 and `pgcrypto` 1.3. Flyway 12.4.0's
public `MigrationResolver` / `MigrationExecutor` API is the implementation seam;
manual history writes, baseline, repair, generic skip and paid-only features are
prohibited.
