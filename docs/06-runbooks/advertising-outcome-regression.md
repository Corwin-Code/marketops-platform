# A bid change that made things worse

## What you will see

An outcome observation with verdict `REGRESSED`, and — if it was a settled
observation with a satisfied guard — an `ACTION_OUTCOME_QUARANTINE` that
appeared on its own, with an accountable role recorded on it.

## Read the stage before anything else

| Stage | What it counts | What it can support |
| --- | --- | --- |
| `OPERATIONAL` | orders placed, spend recorded | a look, not a claim |
| `SETTLED` | sales that survived cancellation and return | a claim about money |
| `SETTLED_REVISED` | the same window after the provider restated it | the current claim |

An operational regression is a number that has not survived returns yet. It
reopens nothing, and it should not be reported as a loss. In this market the gap
between orders placed and sales retained is routine and large.

A **settled** regression with `guard_state = 'SATISFIED'` is a claim about money,
and it is the one that reopens the lineage.

## What the reopen does

`ops.reopen_ad_lineage_after_regression` writes one quarantine scoped to the
affected set the command acted on — not to the object alone, because another
object promoting the same variants is part of the same question. It is
idempotent on the command, so an hourly reconciliation re-reading the same
regression finds the quarantine it already opened.

It deliberately does **not** decide what went wrong. The quarantine is what the
lane resolver reads on the next calculation, and the
`ACTION_OUTCOME_REGRESSION` case that appears is produced by the same authority
as every other case, at P0.

## What to do

1. Read the observation. `baseline_metric_value`, `observed_metric_value` and
   the plan version are all on the row, and the plan was frozen before the
   command existed — so nobody chose the measure after seeing the answer.
2. Decide whether the change caused it. A settled regression on a window that
   also contains a stockout, a price change or a competitor's promotion is not
   evidence about the bid.
3. If the change caused it, compensate. See the compensation path in
   `advertising-unknown-result.md` — a restore needs a current readback proving
   this command still owns the bid, and refuses without one.
4. Lift the quarantine only through `advertising-reenablement.md`.

## A restatement after the fact

Marketplaces restate their own reports. A later view of the same window is a new
revision naming the observation it supersedes, never an edit — the observation
table refuses updates and deletes outright. If a revision reverses an earlier
regression, that is a fact worth reading and does not by itself lift the
quarantine.
