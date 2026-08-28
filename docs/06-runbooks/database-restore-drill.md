# Database restore drill

```yaml
document_type: recovery_drill_procedure_and_local_evidence
local_ephemeral_restore: EXECUTED_PG17_SYNTHETIC
provider_pitr_drill: NOT_EXECUTED
production_restore: NOT_AUTHORIZED
```

## Status

**Local ephemeral restore passed; provider PITR has not run.**
[Checkpoint 114](../07-phase-evidence/SLICE-V1-001/rework-r1/async-export-114/summary.json)
records standard PG17 backup/restore of the synthetic 5,000-SKU, 360,000-order
profile and an immutable 488,000-record export. `pg_dump -Fc` and
`pg_restore --exit-on-error` ran only inside the disposable test cluster, using
its generated administrator identity. Restore into a separate database preserved
row counts, the exact export manifest, application write denials and Flyway
history; the migration runner validated and applied zero migrations. All 44
export custody objects were verified in a separate backup directory. Losing one
primary object caused the actual download service to refuse it; restoring its
exact bytes restored access. This backup/restore and object exercise took
61,530 ms locally. It is not a provider PITR, live VM failover or production RTO
claim. The managed-profile fixture separately verifies restored extension
ownership, provider-DDL denial, history and schema equivalence.

`S1-AC-006` still requires real environment evidence against its accepted target.
Controls are described in `infra/yandex/modules/database`; real restore remains
open in the acceptance and assurance records.

What follows is the procedure to execute once an environment exists. Executing
it is an Owner-authorized act against a real account.

## What the drill proves

Not that a backup exists — the console says that. It proves three things a
configuration screen cannot:

1. that somebody in this organisation can perform a restore under pressure,
   from these instructions, without improvising;
2. that the restored database is actually usable by the application, rather than
   merely present;
3. how long it takes, measured rather than estimated.

The third is the one that changes plans. A recovery objective nobody has timed
is a number in a document.

## Preconditions

- A staging environment built from `infra/yandex/environments/staging`.
- The `KILL_SWITCH_OPERATE` grant, because step 1 uses it.
- Two people. One performs, one records the times. The recorder is not optional:
  the performer will be busy and will misremember.

## Procedure

### 1. Stop writes

```
POST /api/v1/console/commands/kill-switch/disable
{ "scopeKind": "GLOBAL", "reason": "restore drill" }
```

Even in staging. The drill should rehearse the real sequence, and in the real
sequence this step comes first.

### 2. Record the target instant

Choose a point in time a few minutes in the past and write it down, along with
one fact you can verify afterwards — the identifier of the most recent
`ops.price_command` created before that instant, for example. Restoring without
a checkable fact proves only that a database came back.

### 3. Start the restore

```
yc managed-postgresql cluster restore \
  --backup-id <backup> \
  --time <target instant, RFC 3339> \
  --name marketops-restore-drill \
  --environment PRESTABLE \
  --network-name staging-marketops \
  ...
```

Start the clock here.

### 4. Point an application at it

Do not repoint the staging application. Start a separate instance against the
restored cluster, so the drill cannot damage the environment it is rehearsing
in.

### 5. Verify

Three checks, in this order:

- **The schema is complete.** `SELECT max(version) FROM flyway_schema_history`
  matches the migration the application expects. A restore to a point before a
  migration produces a database the current application refuses to start
  against, which is correct and worth seeing once.
- **The fact you chose is present**, and nothing created after the target
  instant is.
- **The application reaches readiness** against the restored cluster.

Stop the clock at the third.

### 6. Record and destroy

Write down the elapsed time, the target instant, the restore point actually
achieved, and anything in this procedure that was wrong. Then delete the
restored cluster; a forgotten one is a second copy of the data nobody is
watching.

### 7. Restart writes

Follow `kill-switch.md`, including the parts about somebody else agreeing. The
drill is a good occasion to rehearse that too.

## What to do with the result

The elapsed time is the recovery objective until a later drill produces a better
one. If it is longer than the business can accept, that is a finding about the
architecture, not about the person who ran the drill, and it belongs in the
Assurance Matrix rather than in a retrospective.
