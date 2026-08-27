# Building a MarketOps environment on Yandex Cloud

```yaml
document_type: environment_bootstrap_procedure
status: AMENDMENT_ACCEPTED_IMPLEMENTATION_IN_PROGRESS
executed: NEVER_BY_CODEX_REWORK
apply_authority: NOT_GRANTED
```

## Stop before deployment

The candidate is not ready to apply. The Human Owner accepted exact
[Amendment-001](../03-work-items/SLICE-V1-001-AMENDMENT-001-YANDEX-MANAGED-PG-BOOTSTRAP.md),
selecting Yandex Managed PostgreSQL 17, provider-managed `btree_gist`/`pgcrypto`
and an attested V0002 executor. The earlier [S1-F010 Decision Request](../07-phase-evidence/SLICE-V1-001/rework-r1/DECISION-REQUEST-S1-F010-MANAGED-PG-BOOTSTRAP.md)
is resolved as a direction, but implementation and evidence are incomplete.

The original bootstrap procedure at reviewed commit
`30d16e5d7db2d2190635a06fececd5883093a876` is historical implementation material,
not an executable current deployment procedure. In particular, its direct
Flyway Maven command and assertion that the production application validates
migrations at startup must not be relied on. The candidate production profile
disables application Flyway access and keeps the migration credential separate.

No deployment, real-account verification, provider call or credential retrieval
was performed by this rework. No Gate EV, Gate E or production enablement is
authorized.

## Checks currently executable without an account

From the repository root, with Terraform 1.14.9:

```bash
python3 scripts/verify_terraform.py --terraform /path/to/terraform --output build/terraform-evidence
python3 -m unittest tests.test_validate_terraform_plan tests.test_yandex_runtime
```

If the registry is unavailable, an already populated filesystem mirror may be
supplied with `--provider-mirror /path/to/mirror`. Terraform still enforces the
unchanged lockfile checksums; this does not substitute a new provider or contact
the Yandex service. CI normally initializes from the public registry.

From `backend/marketops-server`, with Java 21 and local Docker:

```bash
./mvnw -B -Dtest=ManagedMigrationRunnerTest,ApplicationConfigurationTest -Dit.test=ManagedMigrationRunnerIT,ManagedProfileMigrationIT,FlywayMigrationIT integration-test failsafe:verify
```

After full backend verification produces the JAR, run from the repository root:

```bash
python3 scripts/verify_migration_artifact.py
```

This checks the actual Spring Boot packaged resolver, builds local backend and
migration images from an explicit context containing only the verified JAR and
public runtime files, and tests incorrect artifact/missing-envelope refusals.
It uses the local Docker daemon with empty registry authentication, never pushes
an image, and starts the refusal container with networking disabled. Both image
builds require `ARTIFACT_SHA256`; the Dockerfile verifies it against the copied
JAR before publishing its artifact label. The report separates the checkout's
commit/tree from an uncommitted worktree. A local build is not deployment approval.

The Terraform checks use locked provider 0.220.0, backend-disabled initialization,
schema validation and mock-only plans. Standard compatibility retains PostgreSQL
18 tests; the accepted managed target uses a digest-pinned PostgreSQL 17.6
container with synthetic values. Neither establishes a working
Yandex database, real state secrecy, real notifications or restoration.

## Required sequence — local controls implemented, environment proof pending

| Stage | Required evidence before advancing |
| --- | --- |
| Exact authorization | Accepted Amendment-001, exact artifact and environment; separate explicit deployment authority. |
| State bootstrap | KMS-encrypted, versioned, private state; scoped identity; Document API lock table; lock contention and audit proof. Do not put payloads in tfvars or backend configuration. |
| Foundation | Default `runtime_enabled=false` plans database/network/storage/identities without workload, ALB or application DNS. Confirm the actual applied PG17 resource has provider-managed `btree_gist` 1.7 and `pgcrypto` 1.3. |
| Migration | Exact artifact, applied-resource evidence and separate migration identity. The runner must emit managed bootstrap evidence after Flyway records canonical V0002. Never baseline, repair, edit SQL or write history directly. |
| Runtime | Separate reviewed plan sets `runtime_enabled=true` and supplies `migration_evidence={document,sha256}` for the exact successful result. Host validation binds the environment, private JDBC URL, canonical V0002, privileges, completed history and JAR. Pinned images, tmpfs secrets, DB TLS, readiness and HTTPS routing remain required. |
| Operability | Six real alerts, notifications and log delivery; PITR/object custody checks; local then separately approved environment recovery drills. |
| Business onboarding | Approved OIDC/MFA and account evidence; scoped users, stores and provider registry. Provider/capability states remain UNVERIFIED until the audited verification process succeeds. |

Terraform tests now cover foundation, expected refusal without migration proof,
and complete runtime plans for both staging and production. No real environment
sequence has run. The tfvars examples, host image contents, real alert delivery
and network reachability still require authorized environment verification.
Files named `migration-result.fixture.json` under environment tests are synthetic
valid-shape fixtures; their `YANDEX_MANAGED` field is not real Yandex evidence.

## Secret and execution boundaries

### Managed migration evidence custody

The migration manifest and applied-resource evidence examples are in
`infra/yandex/runtime/`. The manifest binds the exact JAR, repository commit/tree,
opaque environment, provider-document hash and applied-resource evidence hash.
Applied-resource evidence must bind the same opaque environment and the SHA-256
of the exact TLS JDBC URL. A plan-only record is not accepted as applied evidence.

Mount `/run/marketops-migration/evidence` from a persistent private evidence
volume, owned by the migration runner UID, separate from the credential tmpfs.
Despite its container path under `/run`, this directory must not be ephemeral.
Do not mount it into application or acquisition containers. The manifest and
provider evidence are read-only mounts; the credential is a separate bounded
UTF-8 file. Running this container still requires separate deployment authority.

For a new database only, set `expectedBootstrapSha256=ABSENT`. The runner writes
an immutable `attempt-<correlation-sha256>.started.json` before credential access.
It then checks the role, server, extension members and existing history and
writes an attested record before migration. Flyway executes canonical V0001,
attests/records canonical V0002, and commits that stage. The runner publishes
`managed-bootstrap.json` before executing V0003 through the current artifact's
latest version. Each normal success or refusal writes a separate result record;
raw exception messages, JDBC URLs, credentials and business data are excluded.

Every replay and upgrade must provide the exact original bootstrap hash in the
reviewed release manifest. Its original commit/tree and provider-evidence hash
are retained, while the new attempt records the current release identities.
Extension, environment, canonical V0002 and privilege assertions must still
match. Never regenerate the bootstrap from an existing history row to bypass
missing evidence. A V0003+ failure can resume after its cause is fixed, using the
intact bootstrap and a new unique correlation. Reusing a correlation is refused.

Preserve and hash-pin the bootstrap, started/attested/result records and provider
record in the release evidence set and in database recovery custody. Publication
uses an atomic non-overwriting hard link and fsync; the volume must support both.
A started/attested record without a result means an interrupted attempt with an
unknown outcome. Stop rollout and inspect the history and evidence together.
If V0002 committed but its bootstrap was lost, restore the paired pre-bootstrap
database/evidence state; do not insert history, baseline, repair or fabricate a
replacement attestation. Storage failure must block rollout even if SQL completed.

Local evidence under `target/managed-profile-evidence` is explicitly synthetic.
It includes expected and observed facts separately; a rejected PG18 server is
recorded as observed PG18, not as the required PG17. These artifacts are uploaded
by backend CI but cannot satisfy real Yandex staging acceptance.

### Host restart and artifact boundary

The host checks migration evidence before reading instance identity or Lockbox.
After pulling the digest-pinned image, it verifies the build's JAR label against
the migrated artifact. Docker containers have `--restart=no`; systemd supervises
both API and console and performs the complete validation/secret-delivery cycle
after an exit. This prevents Docker daemon restart from auto-starting a stale
container against an empty tmpfs. Cleanup stops/removes only the exact expected
containers carrying `org.marketops.managed=true`; unrelated containers are not
force-removed. Tests cover either child exiting, absent containers, owned cleanup,
artifact mismatch and missing/mismatched migration proof. Real VM restart and
host-image verification remain environment evidence, not a completed local drill.

Runtime processes receive only their approved Lockbox references; payloads are
resolved on the private host into tmpfs. Terraform database passwords use
ephemeral inputs and write-only provider fields. A synthetic plan check cannot
prove what an actual backend contains; that evidence is still required.

Application and acquisition identities must never receive the migration secret.
The migration identity must never become the application identity. A valid
artifact hash or an approval-reference string does not itself grant deployment
authority.

Acquisition scheduling and marketplace write workers remain disabled. Enabling
read acquisition needs separate authorized provider/account evidence. Enabling
a write additionally requires the exact capability, account, target, approval,
guardrail and Gate-EV/production authorities; none follows from infrastructure,
migration, branch merge or this procedure.

Recovery procedures remain in the [runbook index](README.md). Their existence
does not mean a drill has been executed; outstanding execution evidence is
listed in the [rework checkpoint](../07-phase-evidence/SLICE-V1-001/rework-r1/progress-checkpoint.md).
