-- V0033: P10 part two — the weekly review (Owner decisions 2026-10-01).
--
-- Every Monday (UTC) the store's week is kept as a snapshot: the newest seven days of daily order
-- facts across the store, how many followed actions took effect and how many readings came in during
-- the week, how many actions still wait for their verdict and how the final verdicts stand, and the
-- actions as they were reviewed. A model then writes what worked and what to adjust from that
-- snapshot (WEEKLY_REVIEW): only the before/after counts, ratios and verdicts the platform computed
-- leave, never a cost or profit amount; a fact cites only the listing values of the newest calculation.
--
-- One snapshot per store and week. A snapshot taken while its week is still running (asked for from
-- the console) is provisional: it is taken again, and the model asked again, until one is taken after
-- the week ended; the Monday run takes that final one. compiled_at tells which it is.

CREATE TABLE ops.weekly_review (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    week_start date NOT NULL,
    week_end date NOT NULL,
    orders_from date,
    orders_to date,
    orders_days_covered integer NOT NULL,
    ordered_units bigint,
    listings_with_orders integer,
    actions_acted integer NOT NULL,
    readings_recorded integer NOT NULL,
    actions_observing integer NOT NULL,
    improved_count integer NOT NULL,
    unchanged_count integer NOT NULL,
    regressed_count integer NOT NULL,
    indeterminate_count integer NOT NULL,
    actions jsonb NOT NULL,
    ai_invocation_id uuid,
    compiled_at timestamp with time zone NOT NULL,
    CONSTRAINT weekly_review_week_ck CHECK (((week_end = (week_start + 6)) AND (EXTRACT(isodow FROM week_start) = (1)::numeric))),
    CONSTRAINT weekly_review_orders_window_ck CHECK ((((orders_from IS NULL) AND (orders_to IS NULL)) OR (orders_to = (orders_from + 6)))),
    CONSTRAINT weekly_review_counts_ck CHECK (((orders_days_covered >= 0) AND (orders_days_covered <= 7) AND ((ordered_units IS NULL) OR (ordered_units >= 0)) AND ((listings_with_orders IS NULL) OR (listings_with_orders >= 0)) AND (actions_acted >= 0) AND (readings_recorded >= 0) AND (actions_observing >= 0) AND (improved_count >= 0) AND (unchanged_count >= 0) AND (regressed_count >= 0) AND (indeterminate_count >= 0))),
    CONSTRAINT weekly_review_actions_ck CHECK (((jsonb_typeof(actions) = 'array'::text) AND (jsonb_array_length(actions) <= 50)))
);

ALTER TABLE ONLY ops.weekly_review
    ADD CONSTRAINT weekly_review_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.weekly_review
    ADD CONSTRAINT weekly_review_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.weekly_review
    ADD CONSTRAINT weekly_review_invocation_fk FOREIGN KEY (ai_invocation_id) REFERENCES ops.ai_invocation(id);

CREATE UNIQUE INDEX weekly_review_week_uq ON ops.weekly_review USING btree (store_id, week_start);

GRANT SELECT,INSERT ON TABLE ops.weekly_review TO marketops_app;

GRANT UPDATE (orders_from, orders_to, orders_days_covered, ordered_units, listings_with_orders, actions_acted, readings_recorded, actions_observing, improved_count, unchanged_count, regressed_count, indeterminate_count, actions, ai_invocation_id, compiled_at) ON TABLE ops.weekly_review TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'weekly_review', 'NO_ROUTE', NULL, 'weekly snapshot of followed actions and their verdicts with the model''s review of it (P10); nothing is sent to a marketplace from it');

-- The weekly review: what came of the store's followed actions, from the platform's before/after
-- counts, ratios and verdicts and the products' demand over the newest calculation window. No cost,
-- profit or break-even amount leaves. A fact cites only the listing values.
INSERT INTO ops.ai_projection_definition (projection_code, projection_version, purpose, retention_policy, owner_label, status) VALUES
    ('WEEKLY_REVIEW', 1, 'What came of a store''s followed actions (price changes, promotions joined or left): their before/after order counts, search and price index changes and verdicts as the platform computed them, the store''s newest seven days of orders, and the products'' search demand and orders; no cost, profit or break-even amounts.', 'NO_PROVIDER_RETENTION', 'aicopilot', 'ACTIVE');

INSERT INTO ops.ai_projection_field (projection_code, projection_version, field_path, data_classification)
SELECT 'WEEKLY_REVIEW', 1, field.path, field.classification
  FROM (VALUES
    ('review.weekStart', 'OPERATING_ATTRIBUTE'),
    ('review.weekEnd', 'OPERATING_ATTRIBUTE'),
    ('review.weekComplete', 'OPERATING_ATTRIBUTE'),
    ('store.storeRef', 'OPAQUE_IDENTIFIER'),
    ('store.platformCode', 'OPERATING_ATTRIBUTE'),
    ('store.ordersFrom', 'OPERATING_ATTRIBUTE'),
    ('store.ordersTo', 'OPERATING_ATTRIBUTE'),
    ('store.ordersDaysCovered', 'DERIVED_VALUE'),
    ('store.orderedUnits', 'DERIVED_VALUE'),
    ('store.listingsWithOrders', 'DERIVED_VALUE'),
    ('store.actionsActed', 'DERIVED_VALUE'),
    ('store.readingsRecorded', 'DERIVED_VALUE'),
    ('store.actionsObserving', 'DERIVED_VALUE'),
    ('store.improvedCount', 'DERIVED_VALUE'),
    ('store.unchangedCount', 'DERIVED_VALUE'),
    ('store.regressedCount', 'DERIVED_VALUE'),
    ('store.indeterminateCount', 'DERIVED_VALUE'),
    ('window.windowCode', 'OPERATING_ATTRIBUTE'),
    ('window.periodStart', 'OPERATING_ATTRIBUTE'),
    ('window.periodEnd', 'OPERATING_ATTRIBUTE'),
    ('actions.actionRef', 'OPAQUE_IDENTIFIER'),
    ('actions.listingRef', 'OPAQUE_IDENTIFIER'),
    ('actions.title', 'MARKETPLACE_TEXT'),
    ('actions.size', 'MARKETPLACE_TEXT'),
    ('actions.color', 'MARKETPLACE_TEXT'),
    ('actions.actionKind', 'OPERATING_ATTRIBUTE'),
    ('actions.source', 'OPERATING_ATTRIBUTE'),
    ('actions.actedOn', 'OPERATING_ATTRIBUTE'),
    ('actions.priceChange', 'DERIVED_VALUE'),
    ('actions.stage', 'DERIVED_VALUE'),
    ('actions.verdict', 'DERIVED_VALUE'),
    ('actions.leadingSignal', 'DERIVED_VALUE'),
    ('actions.reason', 'DERIVED_VALUE'),
    ('actions.ordersBefore', 'DERIVED_VALUE'),
    ('actions.ordersAfter', 'DERIVED_VALUE'),
    ('actions.daysBefore', 'DERIVED_VALUE'),
    ('actions.daysAfter', 'DERIVED_VALUE'),
    ('actions.searchChange', 'DERIVED_VALUE'),
    ('actions.priceIndexBefore', 'OPERATING_ATTRIBUTE'),
    ('actions.priceIndexAfter', 'OPERATING_ATTRIBUTE'),
    ('actions.preliminaryDueOn', 'OPERATING_ATTRIBUTE'),
    ('actions.finalDueOn', 'OPERATING_ATTRIBUTE'),
    ('actions.metricCode', 'CANONICAL_METRIC'),
    ('actions.displayValue', 'CANONICAL_METRIC'),
    ('actions.valueRef', 'OPAQUE_IDENTIFIER')) AS field (path, classification);
