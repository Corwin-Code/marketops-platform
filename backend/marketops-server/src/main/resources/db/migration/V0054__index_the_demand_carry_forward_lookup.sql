-- The carry-forward lookup reads one row and scans a table to find it.
--
-- `mart.demand_window_observation` is append-only: every availability
-- calculation adds one row per child per demand window, so the table grows for
-- as long as the product runs. The carry-forward read asks it, twice per
-- variant, for the newest eligible observation belonging to one child.
--
-- Its two indexes are the primary key and `(calculation_id, window_code)`.
-- Neither leads with `child_id`, so the only plan available drives from the
-- table and filters, and the cost of one read grows with everything written
-- before it. Over a portfolio pass that is quadratic: the last variant of a
-- five-thousand-variant sweep pays for the first four thousand nine hundred and
-- ninety-nine, and the tail of the pass is exactly where a percentile service
-- level is measured.
--
-- The index below turns that read into a one-row index lookup. It is partial on
-- the two predicates the query always carries, so it stores only the rows a
-- carry-forward could ever select — an eligible observation with a rate — and
-- the descending order matches the ORDER BY exactly, so no sort remains.
--
-- Forward-only: an index is added, no row is touched, no result changes. A
-- partial index is used only where its predicate is implied by the query's own,
-- so a future query that asks a wider question simply will not use it.

CREATE INDEX demand_window_observation_carry_forward_ix
    ON mart.demand_window_observation (child_id, period_end DESC, window_code DESC)
    INCLUDE (daily_rate)
    WHERE eligibility = 'ELIGIBLE' AND daily_rate IS NOT NULL;
