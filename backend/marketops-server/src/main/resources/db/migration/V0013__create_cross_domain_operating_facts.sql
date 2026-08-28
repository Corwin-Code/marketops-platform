-- The thin cross-domain operating facts a SKU growth and profit diagnosis
-- needs: listing health, price, stock, funnel, sales, returns, platform fees
-- and advertising, each carried by one shared provenance record.
--
-- Three rules shape every table here.
--
-- A fact is append-only and carries its own provenance. Nothing rewrites a
-- stored observation; a later correction is a new row that names the row it
-- supersedes. That is what lets a late return or a settlement adjustment change
-- a metric without changing the history the metric was first computed from.
--
-- An absent measure is absent. Every measure that a platform may not publish is
-- nullable, and a null means NOT_AVAILABLE. Substituting zero would turn a gap
-- in coverage into a confident business statement, which is exactly the failure
-- this product exists to prevent.
--
-- A duplicate source read produces no duplicate effect. Every fact carries the
-- source's own composed identity and is unique on it, so replaying stored Raw
-- or re-reading a page inserts nothing new.

-- ---------------------------------------------------------------------------
-- Shared provenance
-- ---------------------------------------------------------------------------

-- Where one fact came from, when the source considered it true, and when this
-- system learned it. Freshness is derived from source_time; a fact whose source
-- never stated a time keeps source_time null and is treated as unknown
-- freshness rather than as fresh.
CREATE TABLE core.fact_provenance (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    source_kind         text        NOT NULL,
    raw_observation_id  uuid,
    source_time         timestamptz,
    ingestion_time      timestamptz NOT NULL,
    recorded_by_user_id uuid,
    evidence_note       text,
    CONSTRAINT fact_provenance_pk PRIMARY KEY (id),
    CONSTRAINT fact_provenance_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT fact_provenance_raw_fk
        FOREIGN KEY (raw_observation_id)
        REFERENCES raw.raw_acquisition_observation (id),
    CONSTRAINT fact_provenance_user_fk
        FOREIGN KEY (recorded_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT fact_provenance_source_kind_ck
        CHECK (source_kind IN ('MARKETPLACE_RAW', 'INTERNAL_IMPORT', 'MANUAL_ENTRY')),
    -- A marketplace fact must name the exact stored bytes it was derived from.
    -- Without that link the fact is an assertion, not evidence.
    CONSTRAINT fact_provenance_raw_required_ck
        CHECK (source_kind <> 'MARKETPLACE_RAW' OR raw_observation_id IS NOT NULL),
    -- A manually entered fact must name the person who entered it.
    CONSTRAINT fact_provenance_manual_actor_ck
        CHECK (source_kind <> 'MANUAL_ENTRY' OR recorded_by_user_id IS NOT NULL)
);

CREATE INDEX fact_provenance_raw_ix
    ON core.fact_provenance (raw_observation_id)
    WHERE raw_observation_id IS NOT NULL;
CREATE INDEX fact_provenance_organization_ix
    ON core.fact_provenance (organization_id, ingestion_time DESC);

-- ---------------------------------------------------------------------------
-- Listing health
-- ---------------------------------------------------------------------------

-- The platform's own view of whether a listing variant can currently sell, kept
-- in the platform's own vocabulary. sellable is a three-valued answer because
-- an unrecorded sellability is not a negative one.
CREATE TABLE core.listing_health_observation (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    provenance_id               uuid        NOT NULL,
    platform_listing_variant_id uuid        NOT NULL,
    source_fact_key             text        NOT NULL,
    observed_at                 timestamptz NOT NULL,
    native_status               text,
    sellable                    text        NOT NULL,
    blocked_reason_native       text,
    content_completeness        numeric(5, 4),
    supersedes_fact_id          uuid,
    CONSTRAINT listing_health_observation_pk PRIMARY KEY (id),
    CONSTRAINT listing_health_observation_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT listing_health_observation_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT listing_health_observation_supersedes_fk
        FOREIGN KEY (supersedes_fact_id)
        REFERENCES core.listing_health_observation (id),
    CONSTRAINT listing_health_observation_source_key_uq
        UNIQUE (organization_id, source_fact_key),
    CONSTRAINT listing_health_observation_sellable_ck
        CHECK (sellable IN ('YES', 'NO', 'UNKNOWN')),
    CONSTRAINT listing_health_observation_completeness_ck
        CHECK (content_completeness IS NULL
            OR (content_completeness >= 0 AND content_completeness <= 1))
);

CREATE INDEX listing_health_observation_variant_ix
    ON core.listing_health_observation (platform_listing_variant_id, observed_at DESC);

-- ---------------------------------------------------------------------------
-- Price
-- ---------------------------------------------------------------------------

-- One observed price state. The three amounts are separate because a discount
-- and a promotion are different commercial facts, and a guardrail that compares
-- a proposed price against the wrong one would authorise the wrong change.
CREATE TABLE core.listing_price_observation (
    id                          uuid          NOT NULL,
    organization_id             uuid          NOT NULL,
    provenance_id               uuid          NOT NULL,
    platform_listing_variant_id uuid          NOT NULL,
    source_fact_key             text          NOT NULL,
    observed_at                 timestamptz   NOT NULL,
    currency_code               text          NOT NULL,
    list_price                  numeric(18, 4),
    selling_price               numeric(18, 4),
    discount_price              numeric(18, 4),
    promotion_active            text          NOT NULL,
    native_price_kind           text,
    supersedes_fact_id          uuid,
    CONSTRAINT listing_price_observation_pk PRIMARY KEY (id),
    CONSTRAINT listing_price_observation_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT listing_price_observation_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT listing_price_observation_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES core.listing_price_observation (id),
    CONSTRAINT listing_price_observation_source_key_uq
        UNIQUE (organization_id, source_fact_key),
    CONSTRAINT listing_price_observation_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT listing_price_observation_list_ck
        CHECK (list_price IS NULL OR list_price >= 0),
    CONSTRAINT listing_price_observation_selling_ck
        CHECK (selling_price IS NULL OR selling_price >= 0),
    CONSTRAINT listing_price_observation_discount_ck
        CHECK (discount_price IS NULL OR discount_price >= 0),
    CONSTRAINT listing_price_observation_promotion_ck
        CHECK (promotion_active IN ('YES', 'NO', 'UNKNOWN')),
    -- An observation with no amount at all is not an observation of a price.
    CONSTRAINT listing_price_observation_any_amount_ck
        CHECK (num_nonnulls(list_price, selling_price, discount_price) > 0)
);

CREATE INDEX listing_price_observation_variant_ix
    ON core.listing_price_observation (platform_listing_variant_id, observed_at DESC);

-- ---------------------------------------------------------------------------
-- Stock
-- ---------------------------------------------------------------------------

-- Availability for one fulfillment mode. Marketplace-held and seller-held stock
-- are different operating facts and are never summed into one number here.
CREATE TABLE core.listing_stock_observation (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    provenance_id               uuid        NOT NULL,
    platform_listing_variant_id uuid        NOT NULL,
    fulfillment_mode_code       text        NOT NULL,
    warehouse_id                uuid,
    source_fact_key             text        NOT NULL,
    observed_at                 timestamptz NOT NULL,
    available_quantity          integer,
    reserved_quantity           integer,
    inbound_quantity            integer,
    supersedes_fact_id          uuid,
    CONSTRAINT listing_stock_observation_pk PRIMARY KEY (id),
    CONSTRAINT listing_stock_observation_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT listing_stock_observation_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT listing_stock_observation_mode_fk
        FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode (code),
    CONSTRAINT listing_stock_observation_warehouse_fk
        FOREIGN KEY (warehouse_id, organization_id)
        REFERENCES core.warehouse (id, organization_id),
    CONSTRAINT listing_stock_observation_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES core.listing_stock_observation (id),
    CONSTRAINT listing_stock_observation_source_key_uq
        UNIQUE (organization_id, source_fact_key),
    CONSTRAINT listing_stock_observation_available_ck
        CHECK (available_quantity IS NULL OR available_quantity >= 0),
    CONSTRAINT listing_stock_observation_reserved_ck
        CHECK (reserved_quantity IS NULL OR reserved_quantity >= 0),
    CONSTRAINT listing_stock_observation_inbound_ck
        CHECK (inbound_quantity IS NULL OR inbound_quantity >= 0)
);

CREATE INDEX listing_stock_observation_variant_ix
    ON core.listing_stock_observation (platform_listing_variant_id, observed_at DESC);

-- ---------------------------------------------------------------------------
-- Funnel
-- ---------------------------------------------------------------------------

-- Exposure and engagement over a closed period. Each measure is nullable on its
-- own, because platforms publish different subsets and a diagnosis has to say
-- which part of the funnel it could not see.
CREATE TABLE core.listing_traffic_observation (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    provenance_id               uuid        NOT NULL,
    platform_listing_variant_id uuid        NOT NULL,
    source_fact_key             text        NOT NULL,
    period_start                timestamptz NOT NULL,
    period_end                  timestamptz NOT NULL,
    impressions                 bigint,
    clicks                      bigint,
    visits                      bigint,
    add_to_cart                 bigint,
    ordered_units               bigint,
    supersedes_fact_id          uuid,
    CONSTRAINT listing_traffic_observation_pk PRIMARY KEY (id),
    CONSTRAINT listing_traffic_observation_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT listing_traffic_observation_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT listing_traffic_observation_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES core.listing_traffic_observation (id),
    CONSTRAINT listing_traffic_observation_source_key_uq
        UNIQUE (organization_id, source_fact_key),
    CONSTRAINT listing_traffic_observation_period_ck CHECK (period_start < period_end),
    CONSTRAINT listing_traffic_observation_impressions_ck
        CHECK (impressions IS NULL OR impressions >= 0),
    CONSTRAINT listing_traffic_observation_clicks_ck CHECK (clicks IS NULL OR clicks >= 0),
    CONSTRAINT listing_traffic_observation_visits_ck CHECK (visits IS NULL OR visits >= 0),
    CONSTRAINT listing_traffic_observation_cart_ck
        CHECK (add_to_cart IS NULL OR add_to_cart >= 0),
    CONSTRAINT listing_traffic_observation_ordered_ck
        CHECK (ordered_units IS NULL OR ordered_units >= 0),
    CONSTRAINT listing_traffic_observation_any_measure_ck
        CHECK (num_nonnulls(impressions, clicks, visits, add_to_cart, ordered_units) > 0)
);

CREATE INDEX listing_traffic_observation_variant_ix
    ON core.listing_traffic_observation (platform_listing_variant_id, period_start DESC);

-- ---------------------------------------------------------------------------
-- Sales, returns, fees and advertising
-- ---------------------------------------------------------------------------

-- One sale line at one stage of certainty. The three stages are separate rows,
-- never an updated status, because a completed order, an order that survived
-- the return window, and a settled payout answer three different questions and
-- arrive at three different times.
CREATE TABLE ledger.sales_fact (
    id                          uuid          NOT NULL,
    organization_id             uuid          NOT NULL,
    provenance_id               uuid          NOT NULL,
    platform_listing_variant_id uuid          NOT NULL,
    store_id                    uuid          NOT NULL,
    sale_stage                  text          NOT NULL,
    retention_window_days       integer,
    source_fact_key             text          NOT NULL,
    native_order_key            text          NOT NULL,
    native_line_key             text,
    native_status               text,
    occurred_at                 timestamptz   NOT NULL,
    quantity                    integer       NOT NULL,
    currency_code               text          NOT NULL,
    gross_amount                numeric(18, 4) NOT NULL,
    discount_amount             numeric(18, 4),
    net_amount                  numeric(18, 4) NOT NULL,
    adjustment_kind             text,
    supersedes_fact_id          uuid,
    CONSTRAINT sales_fact_pk PRIMARY KEY (id),
    CONSTRAINT sales_fact_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT sales_fact_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT sales_fact_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT sales_fact_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.sales_fact (id),
    CONSTRAINT sales_fact_source_key_uq UNIQUE (organization_id, source_fact_key),
    CONSTRAINT sales_fact_stage_ck
        CHECK (sale_stage IN ('COMPLETED', 'RETAINED', 'SETTLED')),
    -- A retained sale only means something against a stated window. The product
    -- contract fixes 7, 14 and 30 days as the supported observations.
    CONSTRAINT sales_fact_retention_window_ck
        CHECK ((sale_stage = 'RETAINED') = (retention_window_days IS NOT NULL)),
    CONSTRAINT sales_fact_retention_values_ck
        CHECK (retention_window_days IS NULL OR retention_window_days IN (7, 14, 30)),
    CONSTRAINT sales_fact_quantity_ck CHECK (quantity <> 0),
    CONSTRAINT sales_fact_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT sales_fact_native_order_ck
        CHECK (length(btrim(native_order_key)) BETWEEN 1 AND 128),
    CONSTRAINT sales_fact_adjustment_ck
        CHECK (adjustment_kind IS NULL
            OR adjustment_kind IN ('LATE_ARRIVAL', 'CORRECTION', 'REVERSAL'))
);

CREATE INDEX sales_fact_variant_stage_ix
    ON ledger.sales_fact (platform_listing_variant_id, sale_stage, occurred_at DESC);
CREATE INDEX sales_fact_store_ix ON ledger.sales_fact (store_id, occurred_at DESC);
CREATE INDEX sales_fact_order_ix ON ledger.sales_fact (organization_id, native_order_key);

-- A cancellation, refusal or return. The platform's own reason text is kept
-- next to the internal category so a category the operator disputes can be
-- checked against what the platform actually said.
CREATE TABLE ledger.return_fact (
    id                          uuid          NOT NULL,
    organization_id             uuid          NOT NULL,
    provenance_id               uuid          NOT NULL,
    platform_listing_variant_id uuid          NOT NULL,
    store_id                    uuid          NOT NULL,
    source_fact_key             text          NOT NULL,
    native_return_key           text          NOT NULL,
    native_order_key            text,
    return_kind                 text          NOT NULL,
    reason_category             text          NOT NULL,
    reason_native               text,
    occurred_at                 timestamptz   NOT NULL,
    quantity                    integer       NOT NULL,
    currency_code               text          NOT NULL,
    refund_amount               numeric(18, 4),
    loss_amount                 numeric(18, 4),
    adjustment_kind             text,
    supersedes_fact_id          uuid,
    CONSTRAINT return_fact_pk PRIMARY KEY (id),
    CONSTRAINT return_fact_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT return_fact_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT return_fact_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT return_fact_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.return_fact (id),
    CONSTRAINT return_fact_source_key_uq UNIQUE (organization_id, source_fact_key),
    CONSTRAINT return_fact_kind_ck
        CHECK (return_kind IN ('CANCELLATION', 'DELIVERY_REFUSAL', 'POST_DELIVERY_RETURN')),
    CONSTRAINT return_fact_reason_ck
        CHECK (reason_category IN (
            'QUALITY', 'SIZE_OR_FIT', 'NOT_AS_DESCRIBED', 'DAMAGED_IN_TRANSIT',
            'CUSTOMER_CHANGED_MIND', 'LOGISTICS', 'OTHER', 'UNKNOWN')),
    CONSTRAINT return_fact_quantity_ck CHECK (quantity > 0),
    CONSTRAINT return_fact_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT return_fact_refund_ck CHECK (refund_amount IS NULL OR refund_amount >= 0),
    CONSTRAINT return_fact_loss_ck CHECK (loss_amount IS NULL OR loss_amount >= 0),
    CONSTRAINT return_fact_adjustment_ck
        CHECK (adjustment_kind IS NULL
            OR adjustment_kind IN ('LATE_ARRIVAL', 'CORRECTION', 'REVERSAL'))
);

CREATE INDEX return_fact_variant_ix
    ON ledger.return_fact (platform_listing_variant_id, occurred_at DESC);
CREATE INDEX return_fact_order_ix ON ledger.return_fact (organization_id, native_order_key);

-- One platform charge attributable to a listing variant. The category is the
-- internal taxonomy the profit definition consumes; native_fee_code preserves
-- what the platform called it, including a code this system does not recognise.
CREATE TABLE ledger.finance_fee_fact (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    provenance_id               uuid           NOT NULL,
    platform_listing_variant_id uuid           NOT NULL,
    store_id                    uuid           NOT NULL,
    source_fact_key             text           NOT NULL,
    native_fee_code             text,
    native_order_key            text,
    fee_category                text           NOT NULL,
    settlement_state            text           NOT NULL,
    occurred_at                 timestamptz    NOT NULL,
    currency_code               text           NOT NULL,
    amount                      numeric(18, 4) NOT NULL,
    adjustment_kind             text,
    supersedes_fact_id          uuid,
    CONSTRAINT finance_fee_fact_pk PRIMARY KEY (id),
    CONSTRAINT finance_fee_fact_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT finance_fee_fact_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT finance_fee_fact_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT finance_fee_fact_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.finance_fee_fact (id),
    CONSTRAINT finance_fee_fact_source_key_uq UNIQUE (organization_id, source_fact_key),
    CONSTRAINT finance_fee_fact_category_ck
        CHECK (fee_category IN (
            'COMMISSION', 'FULFILLMENT', 'DELIVERY', 'STORAGE',
            'RETURN_PROCESSING', 'ADVERTISING', 'VARIABLE_TAX',
            'OTHER_VARIABLE', 'UNKNOWN')),
    CONSTRAINT finance_fee_fact_settlement_ck
        CHECK (settlement_state IN ('ACCRUED', 'SETTLED', 'UNKNOWN')),
    CONSTRAINT finance_fee_fact_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT finance_fee_fact_adjustment_ck
        CHECK (adjustment_kind IS NULL
            OR adjustment_kind IN ('LATE_ARRIVAL', 'CORRECTION', 'REVERSAL'))
);

CREATE INDEX finance_fee_fact_variant_ix
    ON ledger.finance_fee_fact (platform_listing_variant_id, occurred_at DESC);
CREATE INDEX finance_fee_fact_category_ix
    ON ledger.finance_fee_fact (organization_id, fee_category, occurred_at DESC);

-- Advertising cost and its measured effect over a period. Attribution is the
-- platform's, not this system's; attributed_revenue is nullable because a
-- platform that publishes spend does not necessarily publish attribution.
CREATE TABLE ledger.ad_spend_fact (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    provenance_id               uuid           NOT NULL,
    platform_listing_variant_id uuid           NOT NULL,
    store_id                    uuid           NOT NULL,
    source_fact_key             text           NOT NULL,
    native_campaign_key         text           NOT NULL,
    campaign_kind_native        text,
    period_start                timestamptz    NOT NULL,
    period_end                  timestamptz    NOT NULL,
    currency_code               text           NOT NULL,
    spend_amount                numeric(18, 4) NOT NULL,
    impressions                 bigint,
    clicks                      bigint,
    attributed_orders           bigint,
    attributed_revenue          numeric(18, 4),
    adjustment_kind             text,
    supersedes_fact_id          uuid,
    CONSTRAINT ad_spend_fact_pk PRIMARY KEY (id),
    CONSTRAINT ad_spend_fact_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT ad_spend_fact_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT ad_spend_fact_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_spend_fact_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.ad_spend_fact (id),
    CONSTRAINT ad_spend_fact_source_key_uq UNIQUE (organization_id, source_fact_key),
    CONSTRAINT ad_spend_fact_period_ck CHECK (period_start < period_end),
    CONSTRAINT ad_spend_fact_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_spend_fact_spend_ck CHECK (spend_amount >= 0),
    CONSTRAINT ad_spend_fact_impressions_ck CHECK (impressions IS NULL OR impressions >= 0),
    CONSTRAINT ad_spend_fact_clicks_ck CHECK (clicks IS NULL OR clicks >= 0),
    CONSTRAINT ad_spend_fact_orders_ck
        CHECK (attributed_orders IS NULL OR attributed_orders >= 0),
    CONSTRAINT ad_spend_fact_revenue_ck
        CHECK (attributed_revenue IS NULL OR attributed_revenue >= 0),
    CONSTRAINT ad_spend_fact_adjustment_ck
        CHECK (adjustment_kind IS NULL
            OR adjustment_kind IN ('LATE_ARRIVAL', 'CORRECTION', 'REVERSAL'))
);

CREATE INDEX ad_spend_fact_variant_ix
    ON ledger.ad_spend_fact (platform_listing_variant_id, period_start DESC);
CREATE INDEX ad_spend_fact_campaign_ix
    ON ledger.ad_spend_fact (organization_id, native_campaign_key, period_start DESC);

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Operating facts are the output of acquisition, never an input to it. Routing
-- them would make every normalized page advance the epoch of the very job that
-- produced it, cancelling the acquisition mid-run.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'fact_provenance', 'NO_ROUTE', NULL,
        'append-only provenance; a normalization result, not a control fact'),
    ('core', 'listing_health_observation', 'NO_ROUTE', NULL,
        'append-only observation produced by acquisition, never read by it'),
    ('core', 'listing_price_observation', 'NO_ROUTE', NULL,
        'append-only observation produced by acquisition, never read by it'),
    ('core', 'listing_stock_observation', 'NO_ROUTE', NULL,
        'append-only observation produced by acquisition, never read by it'),
    ('core', 'listing_traffic_observation', 'NO_ROUTE', NULL,
        'append-only observation produced by acquisition, never read by it'),
    ('ledger', 'sales_fact', 'NO_ROUTE', NULL,
        'append-only operating fact; no acquisition authority reads it'),
    ('ledger', 'return_fact', 'NO_ROUTE', NULL,
        'append-only operating fact; no acquisition authority reads it'),
    ('ledger', 'finance_fee_fact', 'NO_ROUTE', NULL,
        'append-only operating fact; no acquisition authority reads it'),
    ('ledger', 'ad_spend_fact', 'NO_ROUTE', NULL,
        'append-only operating fact; no acquisition authority reads it');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Facts are append-only at the privilege level. With no UPDATE and no DELETE
-- anywhere in this migration, a correction has to be written as a superseding
-- row, and no code path — well-behaved or not — can quietly restate history.
GRANT SELECT, INSERT ON core.fact_provenance TO marketops_app;
GRANT SELECT, INSERT ON core.listing_health_observation TO marketops_app;
GRANT SELECT, INSERT ON core.listing_price_observation TO marketops_app;
GRANT SELECT, INSERT ON core.listing_stock_observation TO marketops_app;
GRANT SELECT, INSERT ON core.listing_traffic_observation TO marketops_app;
GRANT SELECT, INSERT ON ledger.sales_fact TO marketops_app;
GRANT SELECT, INSERT ON ledger.return_fact TO marketops_app;
GRANT SELECT, INSERT ON ledger.finance_fee_fact TO marketops_app;
GRANT SELECT, INSERT ON ledger.ad_spend_fact TO marketops_app;
