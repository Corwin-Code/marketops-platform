-- V0032: P10 effect tracking — what became of a listing after an action (Owner decisions 2026-10-01).
--
-- An executed action is followed for fourteen days and compared with the fourteen days before it:
--   * the actions followed are a price command that succeeded, a price changed by hand in the seller
--     office (ops.price_decision APPLIED_IN_SELLER_OFFICE) and a promotion joined or left
--     (ops.promotion_decision JOINED / LEFT);
--   * the day of the action belongs to neither window; a preliminary reading compares seven days with
--     seven, the final one fourteen with fourteen, each once the store's daily order facts reach the
--     window's last day (they arrive two days late);
--   * units ordered decide the verdict; when neither window has an order, the leading signals stand
--     beside it: search users moving by 20 % or more and Ozon's price index class changing;
--   * a stock-out, a seller promotion, a price that did not hold or another action inside the window
--     make the verdict INDETERMINATE, with the reason recorded.
--
-- Both tables are append-only for the application: an action is registered once, each reading is
-- written once. The windows are calendar days in UTC, inclusive at both ends.

CREATE TABLE ops.action_outcome (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    action_source text NOT NULL,
    source_id uuid NOT NULL,
    recommendation_id uuid,
    platform_listing_variant_id uuid NOT NULL,
    action_kind text NOT NULL,
    acted_at timestamp with time zone NOT NULL,
    acted_on date NOT NULL,
    prior_price numeric(18,4),
    target_price numeric(18,4),
    currency_code text,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT action_outcome_source_ck CHECK ((action_source = ANY (ARRAY['PRICE_COMMAND'::text, 'PRICE_DECISION'::text, 'PROMOTION_DECISION'::text]))),
    CONSTRAINT action_outcome_kind_ck CHECK ((action_kind = ANY (ARRAY['PRICE_CHANGE'::text, 'PROMOTION_JOINED'::text, 'PROMOTION_LEFT'::text]))),
    CONSTRAINT action_outcome_kind_source_ck CHECK (((action_kind = 'PRICE_CHANGE'::text) = (action_source = ANY (ARRAY['PRICE_COMMAND'::text, 'PRICE_DECISION'::text])))),
    CONSTRAINT action_outcome_day_ck CHECK ((acted_on = ((acted_at AT TIME ZONE 'UTC'::text))::date)),
    CONSTRAINT action_outcome_price_ck CHECK ((((target_price IS NULL) AND (prior_price IS NULL) AND (currency_code IS NULL)) OR ((target_price > (0)::numeric) AND ((prior_price IS NULL) OR (prior_price > (0)::numeric)) AND (currency_code ~ '^[A-Z]{3}$'::text)))),
    CONSTRAINT action_outcome_price_change_ck CHECK (((action_kind <> 'PRICE_CHANGE'::text) OR (target_price IS NOT NULL)))
);

ALTER TABLE ONLY ops.action_outcome
    ADD CONSTRAINT action_outcome_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.action_outcome
    ADD CONSTRAINT action_outcome_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.action_outcome
    ADD CONSTRAINT action_outcome_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.action_outcome
    ADD CONSTRAINT action_outcome_recommendation_fk FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY ops.action_outcome
    ADD CONSTRAINT action_outcome_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE UNIQUE INDEX action_outcome_source_uq ON ops.action_outcome USING btree (action_source, source_id);

CREATE INDEX action_outcome_store_ix ON ops.action_outcome USING btree (store_id, acted_at DESC);

CREATE INDEX action_outcome_variant_ix ON ops.action_outcome USING btree (platform_listing_variant_id, acted_on);

CREATE TABLE ops.action_outcome_reading (
    id uuid NOT NULL,
    action_outcome_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    stage text NOT NULL,
    window_days integer NOT NULL,
    baseline_from date NOT NULL,
    baseline_to date NOT NULL,
    observation_from date NOT NULL,
    observation_to date NOT NULL,
    baseline_days_covered integer NOT NULL,
    observation_days_covered integer NOT NULL,
    baseline_ordered_units bigint,
    observation_ordered_units bigint,
    baseline_search_users bigint,
    observation_search_users bigint,
    baseline_price_index text,
    observation_price_index text,
    observation_buyer_price_min numeric(18,4),
    observation_buyer_price_max numeric(18,4),
    stockout_observed boolean NOT NULL,
    promotion_observed boolean NOT NULL,
    price_held boolean,
    other_action_observed boolean NOT NULL,
    verdict text NOT NULL,
    leading_signal text NOT NULL,
    reason_codes text[] DEFAULT '{}'::text[] NOT NULL,
    rule_version integer NOT NULL,
    computed_at timestamp with time zone NOT NULL,
    CONSTRAINT action_outcome_reading_stage_ck CHECK ((((stage = 'PRELIMINARY'::text) AND (window_days = 7)) OR ((stage = 'FINAL'::text) AND (window_days = 14)))),
    CONSTRAINT action_outcome_reading_windows_ck CHECK (((baseline_to - baseline_from + 1 = window_days) AND (observation_to - observation_from + 1 = window_days) AND (baseline_to < observation_from))),
    CONSTRAINT action_outcome_reading_coverage_ck CHECK (((baseline_days_covered >= 0) AND (baseline_days_covered <= window_days) AND (observation_days_covered >= 0) AND (observation_days_covered <= window_days))),
    CONSTRAINT action_outcome_reading_units_ck CHECK ((((baseline_ordered_units IS NULL) OR (baseline_ordered_units >= 0)) AND ((observation_ordered_units IS NULL) OR (observation_ordered_units >= 0)) AND ((baseline_search_users IS NULL) OR (baseline_search_users >= 0)) AND ((observation_search_users IS NULL) OR (observation_search_users >= 0)))),
    CONSTRAINT action_outcome_reading_verdict_ck CHECK ((verdict = ANY (ARRAY['IMPROVED'::text, 'UNCHANGED'::text, 'REGRESSED'::text, 'INDETERMINATE'::text]))),
    CONSTRAINT action_outcome_reading_signal_ck CHECK ((leading_signal = ANY (ARRAY['POSITIVE'::text, 'NEGATIVE'::text, 'MIXED'::text, 'NONE'::text, 'UNAVAILABLE'::text]))),
    CONSTRAINT action_outcome_reading_reasons_ck CHECK (((cardinality(reason_codes) <= 16) AND (array_position(reason_codes, NULL::text) IS NULL))),
    CONSTRAINT action_outcome_reading_rule_ck CHECK ((rule_version > 0))
);

ALTER TABLE ONLY ops.action_outcome_reading
    ADD CONSTRAINT action_outcome_reading_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.action_outcome_reading
    ADD CONSTRAINT action_outcome_reading_outcome_fk FOREIGN KEY (action_outcome_id, organization_id) REFERENCES ops.action_outcome(id, organization_id);

CREATE UNIQUE INDEX action_outcome_reading_stage_uq ON ops.action_outcome_reading USING btree (action_outcome_id, stage);

GRANT SELECT,INSERT ON TABLE ops.action_outcome TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.action_outcome_reading TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'action_outcome', 'NO_ROUTE', NULL, 'append-only register of executed actions followed for their effect (P10); nothing is sent to a marketplace from it'),
    ('ops', 'action_outcome_reading', 'NO_ROUTE', NULL, 'append-only before/after readings of followed actions (P10); nothing is sent to a marketplace from it');
