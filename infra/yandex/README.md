# Yandex Cloud topology for MarketOps V1

```yaml
document_type: infrastructure_as_code
cloud: YANDEX_CLOUD
region: ru-central1
applied: NEVER
apply_authority: OWNER_ONLY
```

## What this is

The complete `ru-central1` topology MarketOps V1 runs on, written so that the
environment can be rebuilt from this directory rather than from somebody's
memory of what they clicked.

## What this is not

**Nothing here has ever been applied.** No Yandex Cloud account has been
contacted, no state file exists, and no credential for one is present in this
repository or in any environment this work was produced in. Applying it is a
separate act that only the Owner can authorize, and it is not implied by this
code existing, by the branch merging, or by any check passing.

The configuration is therefore *reviewed* rather than *proven*. Its acceptance
criterion (`S1-AC-005`) asks for a reproducible topology from reviewed
infrastructure code; the second half of that criterion — that a real
environment was built from it — remains open and is recorded as open in the
Production Assurance Matrix.

## Shape

| Module | What it owns | Why it is separate |
| --- | --- | --- |
| `network` | VPC, subnets per availability zone, security groups | The blast radius of a network change is different from that of a database change, and separating them means one can be reviewed without re-reading the other. |
| `database` | Managed PostgreSQL cluster, backup window, retention, point-in-time recovery | Recovery settings belong beside the thing they recover, not in a global variables file where they are edited without a second thought. |
| `object-storage` | Evidence bucket, versioning, object lock, lifecycle | Raw evidence is immutable by product rule; the storage that holds it has to enforce that independently of the application. |
| `workload-identity` | Service accounts and role bindings for each workload | Least privilege is a property of these bindings. Keeping them in one place makes the total set of what each workload may do readable in one sitting. |
| `observability` | Log groups, metric alerts, notification channel | An alert nobody defined is an outage nobody notices. |

## Environments

`environments/production` and `environments/staging` are the only places a
concrete value appears. Both are declarative compositions of the modules above;
neither carries a secret, and every credential is referenced by the Lockbox
name it lives under rather than by value.

## Applying this

Not from here, and not by an agent. The sequence an Owner follows is recorded
in [`docs/06-runbooks/yandex-environment-bootstrap.md`](../../docs/06-runbooks/yandex-environment-bootstrap.md).
