-- V0034: P9 — stock and availability risk on the pilot store's real data (Owner decisions 2026-10-02).
--
-- The store has no completed-sale facts yet, so a demand window counting completed sales can never
-- become evidence and every in-stock listing would wait in review forever. A demand-observation policy
-- version now names which unit its windows count:
--
--   COMPLETED_SALES  completed sales from the ledger, the Slice 002 default;
--   ORDERED_UNITS    the units the marketplace reports ordered per UTC day. Windows end with the
--                    newest day the store's order facts cover, a covered day without a record for the
--                    listing counts as zero, and a window observable for enough of its length with
--                    zero orders is evidence of zero demand (nothing sells, so nothing runs out).
--
-- Switching back is publishing a new version once completed sales exist. Each stored window says which
-- unit it counted, so a rate carried forward is never taken from the other basis.

ALTER TABLE core.demand_observation_policy
    ADD COLUMN demand_source text NOT NULL DEFAULT 'COMPLETED_SALES';

ALTER TABLE core.demand_observation_policy
    ADD CONSTRAINT demand_observation_policy_source_ck
        CHECK ((demand_source = ANY (ARRAY['COMPLETED_SALES'::text, 'ORDERED_UNITS'::text])));

ALTER TABLE mart.demand_window_observation
    ADD COLUMN unit_basis text NOT NULL DEFAULT 'COMPLETED_SALES';

ALTER TABLE mart.demand_window_observation
    ADD CONSTRAINT demand_window_observation_basis_ck
        CHECK ((unit_basis = ANY (ARRAY['COMPLETED_SALES'::text, 'ORDERED_UNITS'::text])));

COMMENT ON COLUMN core.demand_observation_policy.demand_source IS
    'Which unit the demand windows count: COMPLETED_SALES (ledger) or ORDERED_UNITS (daily marketplace order facts; an observable zero window is zero demand).';
COMMENT ON COLUMN mart.demand_window_observation.unit_basis IS
    'The unit completed_units counted for this window; carry-forward only reuses a rate of the same basis.';
