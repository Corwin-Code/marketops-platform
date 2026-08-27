# Yandex Cloud topology for MarketOps V1

```yaml
document_type: infrastructure_as_code
cloud: YANDEX_CLOUD
region: ru-central1
status: CANDIDATE_REWORK_INCOMPLETE
deployment_compatibility: AMENDMENT_001_ACCEPTED_IMPLEMENTATION_IN_PROGRESS
applied: NEVER
apply_authority: OWNER_ONLY
```

## What this is

The candidate `ru-central1` topology for MarketOps V1. Exact Amendment-001 is
accepted and pins the managed database to PostgreSQL 17 with provider-managed
`btree_gist` and `pgcrypto`. The environment is not deployed or accepted as
operational. Terraform schema validation and synthetic plans do not prove a
real managed database.

**Deployment remains unauthorized.** Read the accepted
[Amendment-001](../../docs/03-work-items/SLICE-V1-001-AMENDMENT-001-YANDEX-MANAGED-PG-BOOTSTRAP.md)
and current [implementation evidence](../../docs/07-phase-evidence/SLICE-V1-001/rework-r1/managed-pg-bootstrap-progress.md).
Do not apply this candidate or bypass migration history.

## What this is not

**This rework has not applied this infrastructure or called a Yandex account.**
Mock plans and local fixtures do not establish real provider state or secret
absence in a deployed state artifact. Applying this infrastructure is a
separate act that only the Owner can authorize, and it is not implied by this
code existing, by the branch merging, or by any check passing.

Independent closure review is pending. `S1-AC-005` remains
`IMPLEMENTATION_DEFECT` in the candidate acceptance matrix; it is not merely
waiting for production apply. Foundation/runtime sequencing, artifact guards
and complete public input examples now have local checks. Final runtime/alert
verification, exact-commit CI and real staging evidence remain incomplete.

## Shape

| Module | What it owns | Why it is separate |
| --- | --- | --- |
| `network` | VPC, subnets per availability zone, security groups | The blast radius of a network change is different from that of a database change, and separating them means one can be reviewed without re-reading the other. |
| `database` | Managed PostgreSQL 17 cluster, provider extensions, backup window, retention, point-in-time recovery | Amendment-001 makes the control plane the extension lifecycle authority. |
| `object-storage` | Evidence bucket, versioning, object lock, lifecycle | Raw evidence is immutable by product rule; the storage that holds it has to enforce that independently of the application. |
| `workload-identity` | Service accounts and role bindings for each workload | Least privilege is a property of these bindings. Keeping them in one place makes the total set of what each workload may do readable in one sitting. |
| `observability` | Log group, dashboard, audit trail and six alert requirements | Workload timers submit private aggregate signals with scoped Monitoring roles. Actual alert creation, metric receipt and notification delivery remain unverified; see `docs/06-runbooks/operational-monitoring.md`. |
| `workload` | Private instance groups, digest-pinned containers, readiness, HTTPS ALB/router and DNS | Disabled in the foundation stage; a hash-pinned successful managed migration result is required for runtime. Real environment verification remains pending. |
| `bootstrap` | Versioned KMS-encrypted state storage, YDB lock database and audit | Backend use and lock-table creation require separate authorized environment verification. |

## Environments

`environments/production` and `environments/staging` are the only places a
concrete value appears. Both are declarative compositions of the modules above;
neither carries a secret, and every credential is referenced by the Lockbox
name it lives under rather than by value.

Database credential inputs are ephemeral and use provider write-only attributes.
The synthetic plan check proves their absence from that artifact, not from a
real deployed state file. Public tfvars examples cover the current input schema
and default to foundation-only. They deliberately omit both ephemeral password
inputs and contain placeholders requiring independent environment review.

## Local verification only

With exactly Terraform 1.14.9, run from the repository root:

```bash
python3 scripts/verify_terraform.py --terraform /path/to/terraform --output build/terraform-evidence
python3 -m unittest tests.test_validate_terraform_plan tests.test_yandex_runtime
```

The verifier initializes without a backend and uses only mock-provider plans
with locked Yandex provider 0.220.0. No Yandex account or real state is inspected.

All three `.terraform.lock.hcl` files are committed build inputs. They include
the unpacked package hashes for both `darwin_arm64` development and
`linux_amd64` CI. When intentionally maintaining the pinned provider lock,
generate these hashes from the origin registry before review:

```bash
terraform -chdir=infra/yandex/bootstrap providers lock -platform=darwin_arm64 -platform=linux_amd64
terraform -chdir=infra/yandex/environments/staging providers lock -platform=darwin_arm64 -platform=linux_amd64
terraform -chdir=infra/yandex/environments/production providers lock -platform=darwin_arm64 -platform=linux_amd64
```

Review the signing-key output and exact lockfile diff, then run the verifier.
This downloads public provider software, not account data. CI retains
`init -backend=false -lockfile=readonly`; it must not silently update missing
platform hashes. The [Terraform lock command](https://developer.hashicorp.com/terraform/cli/commands/providers/lock)
documents why supported platforms should be declared explicitly.

## Applying this

Not from here, and not by an agent. The sequence an Owner follows is recorded
in [`docs/06-runbooks/yandex-environment-bootstrap.md`](../../docs/06-runbooks/yandex-environment-bootstrap.md).
