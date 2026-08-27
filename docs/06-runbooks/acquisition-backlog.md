# Marketplace facts are falling behind

This is the runbook for the alert `marketops-acquisition-backlog`.

## What has happened

Acquisition runs are not keeping up. Nothing is broken and nothing is lost — the
platform is behind, and every figure derived from marketplace facts is ageing.

## Why it matters before it looks urgent

The product refuses to support a price write from stale inputs. As the backlog
grows, metrics cross the policy's maximum input age and the guardrail begins
returning `INPUT_TOO_STALE`. Operators then see recommendations they cannot act
on, with no obvious cause, because the cause is in a different part of the
system entirely.

So the first thing to tell the affected operators is that the platform is
behind. They will otherwise spend the morning wondering why approvals stopped
working.

## Step 1 — find out which jobs are behind

```
GET /api/v1/console/ingestion/jobs
```

Read the checkpoint age per job. One job behind is a job problem; every job
behind is a worker or a marketplace problem.

## Step 2 — decide which of four things it is

| Symptom | Cause | What to do |
| --- | --- | --- |
| Runs are claimed and completing, just slowly | Rate limiting. The adapter refuses a call rather than exceeding a recorded limit. | Nothing to fix. Confirm the recorded rate limit still matches the marketplace's published one; if the marketplace tightened it, the recorded fact is stale and must be re-verified. |
| Runs are claimed and failing | The marketplace is refusing or timing out. | Read the run's failure code. A run that exhausts its retry budget fails terminally and is re-enqueued by schedule, not by hand. |
| Runs are not being claimed at all | No worker is running, or the scheduler is switched off in this environment. | Check `marketops.acquisition.scheduler-enabled` for the deployment. A worker that was never started is the commonest cause and the easiest to miss. |
| Runs are claimed and never finish | A worker died holding a lease. | The lease expires and the run becomes claimable again. If runs are cycling through this repeatedly, the worker is crashing; read its logs before restarting it. |

## Step 3 — do not skip the backlog

The temptation is to move the checkpoint forward so the job catches up. Do not.
The cursor cannot outrun committed evidence by design, and moving it by hand
would leave a window of marketplace facts that were never acquired, silently,
for ever. A backlog that takes a day to clear is a day of slow figures; a
skipped window is a permanent hole in the history every profit figure is
computed from.

## Step 4 — tell people what is uncertain

While the backlog persists, the console is already honest: figures show their
age, and stale ones are labelled. What the console cannot say is when it will
be current again. Tell the operators that, in words, with an estimate.

## Recovery is automatic

Nothing here requires a manual replay. Runs are restartable, duplicate
processing creates no duplicate effects, and a replay makes no marketplace call
at all. Once the cause is removed, the backlog drains on its own.
