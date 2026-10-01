-- V0026: price suggestions (P8).
--
-- Deterministic findings now lead to PRICE_CHANGE recommendations that enter the existing price
-- review: PRICE_GAP_REDUCIBLE, PRICE_GAP_PARTIAL and the new PRICE_HEADROOM. Writes stay off: the
-- guardrail chain cannot pass on the pilot yet (no commercial policy, no economics projection
-- profile, no freshness watermarks, realized-profit completeness on a store without orders, no
-- fulfilment declaration — the Owner, 2026-10-01, moved these to a phase before writes are enabled),
-- so a person who agrees changes the price in the marketplace's back office and records it here.
--
-- PRICE_HEADROOM (Owner decision 2026-10-01): buyers look for an in-stock listing and nobody orders
-- it, and its buyer price can fall by at least the configured least room before the estimated unit
-- margin reaches the minimum. A buyer price above a comparable competitor price is the price-gap
-- rules' case. Informational; the suggestion never goes below the target margin price.
INSERT INTO mart.diagnosis_rule (rule_code, rule_version, ordinal, display_name, statement, default_severity, blocks_execution, status) VALUES
    ('PRICE_HEADROOM', 1, 18, 'Price headroom', 'Search users are at or above the demand floor, platform stock is available, no unit was ordered in the window, the estimated unit margin is above the configured minimum and the buyer price can fall by at least the configured least room before reaching the target margin price; a buyer price above a comparable lowest competitor price is left to the price-gap rules. Informational: a person decides on the price suggestion it leads to.', 'INFO', false, 'ACTIVE');

INSERT INTO mart.diagnosis_rule_input (rule_code, rule_version, metric_code, requirement) VALUES
    ('PRICE_HEADROOM', 1, 'SEARCH_USERS', 'REQUIRED'),
    ('PRICE_HEADROOM', 1, 'ORDERED_UNITS', 'REQUIRED'),
    ('PRICE_HEADROOM', 1, 'PLATFORM_AVAILABLE_UNITS', 'REQUIRED'),
    ('PRICE_HEADROOM', 1, 'OBSERVED_SELLING_PRICE', 'REQUIRED'),
    ('PRICE_HEADROOM', 1, 'PROJECTED_UNIT_MARGIN', 'REQUIRED'),
    ('PRICE_HEADROOM', 1, 'TARGET_MARGIN_PRICE', 'REQUIRED'),
    ('PRICE_HEADROOM', 1, 'PLATFORM_COMPETITOR_MIN_PRICE', 'OPTIONAL');

-- What a person did with a price suggestion: changed the price by hand in the marketplace's back
-- office (with the buyer price they set), or decided not to. Append-only, beside the recommendation
-- it answers, so the effect review of P10 can compare what was decided with what happened.
CREATE TABLE ops.price_decision (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    recommendation_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    decision text NOT NULL,
    applied_price numeric(18,4),
    currency_code text,
    note text,
    decided_by_user_id uuid NOT NULL,
    decided_at timestamp with time zone NOT NULL,
    CONSTRAINT price_decision_decision_ck CHECK ((decision = ANY (ARRAY['APPLIED_IN_SELLER_OFFICE'::text, 'NOT_APPLIED'::text]))),
    CONSTRAINT price_decision_price_ck CHECK ((((applied_price IS NULL) AND (currency_code IS NULL)) OR ((applied_price > (0)::numeric) AND (currency_code ~ '^[A-Z]{3}$'::text)))),
    CONSTRAINT price_decision_applied_ck CHECK (((decision = 'APPLIED_IN_SELLER_OFFICE'::text) = (applied_price IS NOT NULL))),
    CONSTRAINT price_decision_note_ck CHECK (((note IS NULL) OR ((length(btrim(note)) >= 1) AND (length(note) <= 500))))
);

ALTER TABLE ONLY ops.price_decision
    ADD CONSTRAINT price_decision_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.price_decision
    ADD CONSTRAINT price_decision_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.price_decision
    ADD CONSTRAINT price_decision_recommendation_fk FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY ops.price_decision
    ADD CONSTRAINT price_decision_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE UNIQUE INDEX price_decision_recommendation_uq ON ops.price_decision USING btree (recommendation_id);

CREATE INDEX price_decision_latest_ix ON ops.price_decision USING btree (store_id, platform_listing_variant_id, decided_at DESC);

GRANT SELECT,INSERT ON TABLE ops.price_decision TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'price_decision', 'NO_ROUTE', NULL, 'append-only record of price decisions taken by hand in the marketplace back office; nothing is sent to a marketplace from it');
