# Database restore drill

```yaml
document_type: recovery_drill_procedure
executed: NEVER
executed_against: NONE
```

## Status

**This drill has never been executed.** No Yandex Cloud environment exists yet,
so there has been nothing to restore. The acceptance criterion this serves
(`S1-AC-006`) asks for configured controls *and* an actual restore that meets
the accepted target; the first half is in `infra/yandex/modules/database`, the
second half is open and is recorded as open in the Production Assurance Matrix.

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
