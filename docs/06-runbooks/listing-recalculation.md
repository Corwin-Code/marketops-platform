# Listing recalculation — local rework operating boundary

The existing Listing Conversion scheduler is disabled unless
`marketops.listing-conversion.worker-enabled=true`. This local rework does not
enable that switch in a shared environment. Queue processing has no Provider
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

This checkpoint proves the Health consumer's transactional publication. It does
not establish full 5/15/60 fulfillment: whole-scope periodic scheduling and the
canonical conversion/protection/authorization consumer chain remain open under
S4-DR-R1-023. In particular, a FULL_REVIEW trigger label is not evidence that
those missing consumers ran. Operational review must inspect the named result
scope and the current finding status, not infer full review from FINISHED alone.

Verification is bounded to affected source and observable behavior. Retain the
exact command, final status, source hash and diagnostics. Stop the sequence on
failure, diagnose before re-running, and stop passing checks when no relevant
source or evidence has changed. Do not poll or retry failed business work to
make a dashboard green.
