# Listing recalculation operating boundary

The existing Listing Conversion scheduler is disabled unless
`marketops.listing-conversion.worker-enabled=true`. This runbook does not
authorize enabling that switch in a shared environment. Queue processing has no Provider
call and supplies no launch, Gate-EV or Gate-E authority.

`ops.lc_recalculation_queue` is the sole queue and lease record. One atomic
statement claims eligible rows using `SKIP LOCKED`; the returned generation
identifies that claim. The database instant used for eligibility, start and
lease expiry is the same. An accepted timestamp still in the future remains
queued and is retained unchanged. Source time and accepted time are separate.

Each result transaction locks its exact current generation, writes a Health
version through the existing consumer, and records its exact `health_result_id`
and `calculation_run_id`. A 120-second technical lease and transaction timeout
bound publication; this duration is not a freshness or responsibility policy.
Expiration rolls back an unpublished result. The next pass can reclaim an
abandoned RUNNING row, including a historical row without a lease. A stale
generation cannot complete or fail the successor. Terminal managed receipts
are immutable. A caught computation failure is FAILED and remains visible;
there is no automatic unbounded retry loop for that exception.

Only FINISHED rows with actual bound results can satisfy the displayed latency
check, using exact duration rather than truncated seconds. Historical missing
result references remain missing. FAILED is never a successful latency sample.
Listing locks serialize Health version allocation across interactive and queued
recomputation. Re-running an already completed queue item produces no new result.

Verification is bounded to affected source and observable behavior. Retain the
exact command, final status, source hash and diagnostics. Stop the sequence on
failure, diagnose before re-running, and stop passing checks when no relevant
source or evidence has changed. Do not poll or retry failed business work to
make a dashboard green.

## Queue coverage

V0121 connects native-scope/mapping, listing observations, sales, returns, fees,
ad spend, internal stock/cost, governed finance and feedback revisions to this
sole queue. Risk and ordinary events retain their accepted/source times and
coalesce against the exact Listing without replacing an active fenced generation.
A periodic full-review row uses the same queue authority.

Completion retains the consumer contract version, measurement result identities,
binding-assessment count and binding-invalidated count beside Health/calculation
identity. The current consumer chain recalculates the relevant measurement/protection
state and invalidates affected prepared bindings; a `FINISHED` label alone still
cannot assert every consumer succeeded. Command terminal and review results return
through existing Task events.

The worker remains default OFF and no Provider call is reachable.
