-- V0023: read the marketplace's promotions (Ozon actions) and what joining one would mean (P7).
--
-- Three read methods, all in the key's "Actions read-only" role (official OpenAPI checked
-- 2026-09-29):
--   * GET /v1/actions: the actions the store can take part in, with type, dates, the freeze date
--     after which prices can only go down, how many products can join and have joined, whether
--     the store takes part, and the discount;
--   * POST /v2/actions/candidates and POST /v2/actions/products (v1 is switched off
--     2026-10-13): per action, the products that can join and the ones taking part, each with
--     its price, action price, the maximum action price, the recommended action price and
--     whether the price is above it (the product may be removed), and the boost range with the
--     prices that earn its minimum and maximum. Both take one action_id per request and page by
--     last_id, 100 products at most.
--
-- One request per action. A read template may name the one promotion it asks about
-- ({promotionKey}); the keys are the promotions the store's newest promotion snapshot named
-- that have not ended, asked about one at a time in text order under the KEYS_EXHAUSTED end
-- rule. The answer does not repeat the action id, so the key travels with the answer: a Raw
-- observation records the key its request asked about (request_key), and a normalization field
-- may take it (REQUEST_KEY). Paging inside one action is not built: a full page stops the run
-- for a person instead of silently truncating, and a request the source refuses as bad or
-- unknown (the action ended after the list was read) is kept and the next action is asked.
--
-- Promotion identity (core.platform_promotion) belongs to the listing module, beside listing
-- identity: the promotion snapshot records it and acquisition reads its keys from there. The
-- facts belong to operating facts: one promotion observation per snapshot and one item
-- observation per snapshot, product and membership (CANDIDATE or PARTICIPANT); the newest
-- observation of a promotion and membership is what is current, so a product that left the
-- list is not taken for a current one.

-- Raw observations remember the one key a request asked about.
ALTER TABLE raw.raw_acquisition_observation
    ADD COLUMN request_key text;

ALTER TABLE raw.raw_acquisition_observation
    ADD CONSTRAINT raw_acquisition_observation_request_key_ck CHECK (((request_key IS NULL) OR ((length(request_key) >= 1) AND (length(request_key) <= 128))));

-- A normalization field may take the key the request asked about.
ALTER TABLE staging.normalization_field
    DROP CONSTRAINT normalization_field_source_kind_ck,
    DROP CONSTRAINT normalization_field_source_shape_ck;

ALTER TABLE staging.normalization_field
    ADD CONSTRAINT normalization_field_source_kind_ck CHECK ((source_kind = ANY (ARRAY['POINTER'::text, 'PARENT_POINTER'::text, 'OBSERVATION_TIME'::text, 'CONSTANT'::text, 'WINDOW_START'::text, 'WINDOW_END'::text, 'EACH_POINTER'::text, 'ARRAY_LENGTH'::text, 'REQUEST_KEY'::text]))),
    ADD CONSTRAINT normalization_field_source_shape_ck CHECK ((((source_kind = ANY (ARRAY['POINTER'::text, 'PARENT_POINTER'::text])) AND (source_pointer IS NOT NULL) AND (constant_value IS NULL) AND (element_pointer IS NULL))
        OR ((source_kind = ANY (ARRAY['OBSERVATION_TIME'::text, 'WINDOW_START'::text, 'WINDOW_END'::text, 'REQUEST_KEY'::text])) AND (source_pointer IS NULL) AND (constant_value IS NULL) AND (value_map IS NULL) AND (element_pointer IS NULL))
        OR ((source_kind = 'CONSTANT'::text) AND (source_pointer IS NULL) AND (constant_value IS NOT NULL) AND (length(constant_value) >= 1) AND (length(constant_value) <= 256) AND (value_map IS NULL) AND (element_pointer IS NULL))
        OR ((source_kind = 'EACH_POINTER'::text) AND (source_pointer IS NOT NULL) AND (element_pointer IS NOT NULL) AND (constant_value IS NULL) AND (value_map IS NULL))
        OR ((source_kind = 'ARRAY_LENGTH'::text) AND (source_pointer IS NOT NULL) AND (constant_value IS NULL) AND (value_map IS NULL) AND (element_pointer IS NULL))));

-- Three datasets.
ALTER TABLE platform.ingestion_job
    DROP CONSTRAINT ingestion_job_dataset_kind_ck;

ALTER TABLE platform.ingestion_job
    ADD CONSTRAINT ingestion_job_dataset_kind_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'UNKNOWN'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text])));

ALTER TABLE staging.canonical_field
    DROP CONSTRAINT canonical_field_dataset_ck;

ALTER TABLE staging.canonical_field
    ADD CONSTRAINT canonical_field_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'LISTING_ATTRIBUTE'::text, 'LISTING_CONTENT_GROUP'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text])));

ALTER TABLE staging.normalization_mapping
    DROP CONSTRAINT normalization_mapping_dataset_ck,
    DROP CONSTRAINT normalization_mapping_source_dataset_ck;

ALTER TABLE staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'LISTING_ATTRIBUTE'::text, 'LISTING_CONTENT_GROUP'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text]))),
    ADD CONSTRAINT normalization_mapping_source_dataset_ck CHECK (((source_dataset_kind IS NULL) OR ((source_dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text])) AND (source_dataset_kind <> dataset_kind))));

-- A key-exhausted endpoint may name one promotion instead of a batch of products.
ALTER TABLE platform.platform_endpoint
    DROP CONSTRAINT platform_endpoint_keys_exhausted_ck;

ALTER TABLE platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_keys_exhausted_ck CHECK (((continuation_end_rule <> 'KEYS_EXHAUSTED'::text) OR ((pagination_model = 'OFFSET'::text) AND ((strpos(coalesce(body_template, ''::text), '{listingKeyBatch}'::text) > 0) OR (strpos(coalesce(body_template, ''::text), '{itemKeyBatch}'::text) > 0) OR (strpos(coalesce(body_template, ''::text), '{promotionKey}'::text) > 0)))));

-- The closed placeholder set of V0015 grows by promotionKey, a whole number.
CREATE OR REPLACE FUNCTION platform.request_template_is_well_formed(p_template text, p_is_body boolean, p_is_write boolean) RETURNS boolean
    LANGUAGE plpgsql IMMUTABLE
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE rendered text := p_template; token text[]; allowed text[];
BEGIN
    IF p_template IS NULL THEN RETURN true; END IF;
    IF length(p_template) > 4096 THEN RETURN false; END IF;
    allowed := CASE WHEN p_is_write THEN ARRAY[
            'nativeListingKey', 'nativeVariantKey', 'targetPrice', 'currencyCode',
            'idempotencyKey', 'nativeTaskKey',
            'nativeCampaignKey', 'nativeObjectKey', 'targetBid', 'bidUnitCode',
            'descriptionText', 'descriptionAttributeKey']
        ELSE ARRAY['cursor', 'limit', 'accountKey', 'endpointCode', 'offset', 'page', 'pageIndex',
            'windowFrom', 'windowTo', 'windowStartUtcDate', 'windowEndUtcDate',
            'listingKeyBatch', 'itemKeyBatch', 'itemKeysAll', 'promotionKey'] END;
    FOR token IN SELECT regexp_matches(p_template, '\{([a-zA-Z][a-zA-Z0-9]{0,31})\}', 'g') LOOP
        IF NOT token[1] = ANY(allowed) THEN RETURN false; END IF;
        rendered := replace(rendered, '{' || token[1] || '}',
            CASE WHEN token[1] IN ('targetPrice', 'targetBid', 'limit', 'cursor', 'offset', 'page', 'pageIndex', 'promotionKey')
                 THEN '1' WHEN token[1] IN ('listingKeyBatch', 'itemKeyBatch', 'itemKeysAll') THEN '[]'
                 ELSE 'fixture' END);
    END LOOP;
    IF p_is_body THEN RETURN rendered IS JSON OBJECT WITH UNIQUE KEYS; END IF;
    RETURN rendered !~ '[{}[:cntrl:]]';
END;
$$;

-- Promotion identity, written by the listing module.
CREATE TABLE core.platform_promotion (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    native_promotion_key text NOT NULL,
    ends_at timestamp with time zone,
    first_observed_at timestamp with time zone NOT NULL,
    last_observed_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT platform_promotion_key_ck CHECK (((length(btrim(native_promotion_key)) >= 1) AND (length(native_promotion_key) <= 64))),
    CONSTRAINT platform_promotion_seen_ck CHECK ((last_observed_at >= first_observed_at))
);

ALTER TABLE ONLY core.platform_promotion
    ADD CONSTRAINT platform_promotion_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.platform_promotion
    ADD CONSTRAINT platform_promotion_key_uq UNIQUE (store_id, native_promotion_key);

ALTER TABLE ONLY core.platform_promotion
    ADD CONSTRAINT platform_promotion_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.platform_promotion
    ADD CONSTRAINT platform_promotion_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

CREATE INDEX platform_promotion_current_ix ON core.platform_promotion USING btree (store_id, last_observed_at DESC);

-- One promotion as one snapshot described it.
CREATE TABLE core.promotion_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    platform_promotion_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    title text,
    promotion_kind text,
    description text,
    starts_at timestamp with time zone,
    ends_at timestamp with time zone,
    freezes_at timestamp with time zone,
    candidate_count integer,
    participant_count integer,
    banned_count integer,
    participating boolean,
    voucher boolean,
    targeted boolean,
    discount_kind text,
    discount_value numeric(18,4),
    order_amount numeric(18,4),
    CONSTRAINT promotion_observation_title_ck CHECK (((title IS NULL) OR (length(title) <= 512))),
    CONSTRAINT promotion_observation_kind_ck CHECK (((promotion_kind IS NULL) OR (length(promotion_kind) <= 128))),
    CONSTRAINT promotion_observation_description_ck CHECK (((description IS NULL) OR (length(description) <= 4000))),
    CONSTRAINT promotion_observation_counts_ck CHECK ((((candidate_count IS NULL) OR (candidate_count >= 0)) AND ((participant_count IS NULL) OR (participant_count >= 0)) AND ((banned_count IS NULL) OR (banned_count >= 0)))),
    CONSTRAINT promotion_observation_discount_kind_ck CHECK (((discount_kind IS NULL) OR (length(discount_kind) <= 64)))
);

ALTER TABLE ONLY core.promotion_observation
    ADD CONSTRAINT promotion_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.promotion_observation
    ADD CONSTRAINT promotion_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.promotion_observation
    ADD CONSTRAINT promotion_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.promotion_observation
    ADD CONSTRAINT promotion_observation_promotion_fk FOREIGN KEY (platform_promotion_id, organization_id) REFERENCES core.platform_promotion(id, organization_id);

CREATE INDEX promotion_observation_promotion_ix ON core.promotion_observation USING btree (platform_promotion_id, observed_at DESC);

-- One product of one promotion as one answer described it: a candidate or a participant.
CREATE TABLE core.promotion_item_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    platform_promotion_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    membership text NOT NULL,
    currency_code text,
    price numeric(18,4),
    action_price numeric(18,4),
    max_action_price numeric(18,4),
    recommended_action_price numeric(18,4),
    above_recommended boolean,
    current_boost numeric(9,4),
    min_boost numeric(9,4),
    max_boost numeric(9,4),
    price_for_min_boost numeric(18,4),
    price_for_max_boost numeric(18,4),
    min_stock integer,
    recommended_stock integer,
    stock integer,
    add_mode text,
    quarantined boolean,
    CONSTRAINT promotion_item_observation_membership_ck CHECK ((membership = ANY (ARRAY['CANDIDATE'::text, 'PARTICIPANT'::text]))),
    CONSTRAINT promotion_item_observation_currency_ck CHECK (((currency_code IS NULL) OR (currency_code ~ '^[A-Z]{3}$'::text))),
    CONSTRAINT promotion_item_observation_amounts_ck CHECK ((((price IS NULL) OR (price >= (0)::numeric)) AND ((action_price IS NULL) OR (action_price >= (0)::numeric)) AND ((max_action_price IS NULL) OR (max_action_price >= (0)::numeric)) AND ((recommended_action_price IS NULL) OR (recommended_action_price >= (0)::numeric)) AND ((price_for_min_boost IS NULL) OR (price_for_min_boost >= (0)::numeric)) AND ((price_for_max_boost IS NULL) OR (price_for_max_boost >= (0)::numeric)))),
    CONSTRAINT promotion_item_observation_money_currency_ck CHECK (((currency_code IS NOT NULL) OR ((price IS NULL) AND (action_price IS NULL) AND (max_action_price IS NULL) AND (recommended_action_price IS NULL) AND (price_for_min_boost IS NULL) AND (price_for_max_boost IS NULL)))),
    CONSTRAINT promotion_item_observation_stock_ck CHECK ((((min_stock IS NULL) OR (min_stock >= 0)) AND ((recommended_stock IS NULL) OR (recommended_stock >= 0)) AND ((stock IS NULL) OR (stock >= 0)))),
    CONSTRAINT promotion_item_observation_add_mode_ck CHECK (((add_mode IS NULL) OR (add_mode = ANY (ARRAY['AUTOMATIC'::text, 'SELLER'::text]))))
);

ALTER TABLE ONLY core.promotion_item_observation
    ADD CONSTRAINT promotion_item_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.promotion_item_observation
    ADD CONSTRAINT promotion_item_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.promotion_item_observation
    ADD CONSTRAINT promotion_item_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.promotion_item_observation
    ADD CONSTRAINT promotion_item_observation_promotion_fk FOREIGN KEY (platform_promotion_id, organization_id) REFERENCES core.platform_promotion(id, organization_id);

ALTER TABLE ONLY core.promotion_item_observation
    ADD CONSTRAINT promotion_item_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE INDEX promotion_item_observation_promotion_ix ON core.promotion_item_observation USING btree (platform_promotion_id, membership, observed_at DESC);

CREATE INDEX promotion_item_observation_variant_ix ON core.promotion_item_observation USING btree (platform_listing_variant_id, observed_at DESC);

GRANT SELECT,INSERT,UPDATE ON TABLE core.platform_promotion TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.promotion_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.promotion_item_observation TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'platform_promotion', 'NO_ROUTE', NULL, 'observed promotion identity; a normalization result, not a control fact'),
    ('core', 'promotion_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it'),
    ('core', 'promotion_item_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it');

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal, repeated) VALUES
    ('PROMOTION', 'nativePromotionKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the promotion.', 1, false),
    ('PROMOTION', 'observedAt', 'INSTANT', 'REQUIRED', 'When the marketplace considered this description true.', 2, false),
    ('PROMOTION', 'title', 'TEXT', 'OPTIONAL', 'The promotion''s title.', 3, false),
    ('PROMOTION', 'promotionKind', 'TEXT', 'OPTIONAL', 'The marketplace''s word for the kind of promotion.', 4, false),
    ('PROMOTION', 'description', 'TEXT', 'OPTIONAL', 'The marketplace''s description of the promotion.', 5, false),
    ('PROMOTION', 'startsAt', 'INSTANT', 'OPTIONAL', 'When the promotion starts.', 6, false),
    ('PROMOTION', 'endsAt', 'INSTANT', 'OPTIONAL', 'When the promotion ends.', 7, false),
    ('PROMOTION', 'freezesAt', 'INSTANT', 'OPTIONAL', 'From when prices can only go down and products can no longer leave.', 8, false),
    ('PROMOTION', 'candidateCount', 'INTEGER', 'OPTIONAL', 'How many products can join.', 9, false),
    ('PROMOTION', 'participantCount', 'INTEGER', 'OPTIONAL', 'How many products take part.', 10, false),
    ('PROMOTION', 'bannedCount', 'INTEGER', 'OPTIONAL', 'How many products are blocked from it.', 11, false),
    ('PROMOTION', 'participating', 'BOOLEAN', 'OPTIONAL', 'Whether the store takes part.', 12, false),
    ('PROMOTION', 'voucher', 'BOOLEAN', 'OPTIONAL', 'Whether buyers need a promo code.', 13, false),
    ('PROMOTION', 'targeted', 'BOOLEAN', 'OPTIONAL', 'Whether it addresses a target audience only.', 14, false),
    ('PROMOTION', 'discountKind', 'TEXT', 'OPTIONAL', 'The marketplace''s word for how the discount is expressed.', 15, false),
    ('PROMOTION', 'discountValue', 'DECIMAL', 'OPTIONAL', 'The size of the discount.', 16, false),
    ('PROMOTION', 'orderAmount', 'DECIMAL', 'OPTIONAL', 'The order amount the promotion states.', 17, false),
    ('PROMOTION_CANDIDATE', 'nativeListingKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing.', 1, false),
    ('PROMOTION_CANDIDATE', 'nativeVariantKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing variant.', 2, false),
    ('PROMOTION_CANDIDATE', 'observedAt', 'INSTANT', 'REQUIRED', 'When the marketplace considered this answer true.', 3, false),
    ('PROMOTION_CANDIDATE', 'nativePromotionKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the promotion the request asked about.', 4, false),
    ('PROMOTION_CANDIDATE', 'currencyCode', 'TEXT', 'OPTIONAL', 'ISO 4217 currency of every amount.', 5, false),
    ('PROMOTION_CANDIDATE', 'price', 'DECIMAL', 'OPTIONAL', 'The product''s price without discount.', 6, false),
    ('PROMOTION_CANDIDATE', 'actionPrice', 'DECIMAL', 'OPTIONAL', 'The product''s price in the promotion.', 7, false),
    ('PROMOTION_CANDIDATE', 'maxActionPrice', 'DECIMAL', 'OPTIONAL', 'The highest price the product may have in the promotion.', 8, false),
    ('PROMOTION_CANDIDATE', 'recommendedActionPrice', 'DECIMAL', 'OPTIONAL', 'The price the marketplace recommends in the promotion.', 9, false),
    ('PROMOTION_CANDIDATE', 'aboveRecommended', 'BOOLEAN', 'OPTIONAL', 'Whether the price is above the recommended one; the product may be removed.', 10, false),
    ('PROMOTION_CANDIDATE', 'currentBoost', 'DECIMAL', 'OPTIONAL', 'The product''s current boost.', 11, false),
    ('PROMOTION_CANDIDATE', 'minBoost', 'DECIMAL', 'OPTIONAL', 'The smallest boost, in percent.', 12, false),
    ('PROMOTION_CANDIDATE', 'maxBoost', 'DECIMAL', 'OPTIONAL', 'The largest boost, in percent.', 13, false),
    ('PROMOTION_CANDIDATE', 'priceForMinBoost', 'DECIMAL', 'OPTIONAL', 'The price that earns the smallest boost.', 14, false),
    ('PROMOTION_CANDIDATE', 'priceForMaxBoost', 'DECIMAL', 'OPTIONAL', 'The price that earns the largest boost.', 15, false),
    ('PROMOTION_CANDIDATE', 'minStock', 'INTEGER', 'OPTIONAL', 'The fewest units a stock-discount promotion needs.', 16, false),
    ('PROMOTION_CANDIDATE', 'recommendedStock', 'INTEGER', 'OPTIONAL', 'The units the marketplace recommends for the promotion.', 17, false),
    ('PROMOTION_CANDIDATE', 'quarantined', 'BOOLEAN', 'OPTIONAL', 'Whether the product is in quarantine.', 18, false),
    ('PROMOTION_PARTICIPANT', 'nativeListingKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing.', 1, false),
    ('PROMOTION_PARTICIPANT', 'nativeVariantKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing variant.', 2, false),
    ('PROMOTION_PARTICIPANT', 'observedAt', 'INSTANT', 'REQUIRED', 'When the marketplace considered this answer true.', 3, false),
    ('PROMOTION_PARTICIPANT', 'nativePromotionKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the promotion the request asked about.', 4, false),
    ('PROMOTION_PARTICIPANT', 'currencyCode', 'TEXT', 'OPTIONAL', 'ISO 4217 currency of every amount.', 5, false),
    ('PROMOTION_PARTICIPANT', 'price', 'DECIMAL', 'OPTIONAL', 'The product''s price without discount.', 6, false),
    ('PROMOTION_PARTICIPANT', 'actionPrice', 'DECIMAL', 'OPTIONAL', 'The product''s price in the promotion.', 7, false),
    ('PROMOTION_PARTICIPANT', 'maxActionPrice', 'DECIMAL', 'OPTIONAL', 'The highest price the product may have in the promotion.', 8, false),
    ('PROMOTION_PARTICIPANT', 'recommendedActionPrice', 'DECIMAL', 'OPTIONAL', 'The price the marketplace recommends in the promotion.', 9, false),
    ('PROMOTION_PARTICIPANT', 'aboveRecommended', 'BOOLEAN', 'OPTIONAL', 'Whether the price is above the recommended one; the product may be removed.', 10, false),
    ('PROMOTION_PARTICIPANT', 'currentBoost', 'DECIMAL', 'OPTIONAL', 'The product''s current boost.', 11, false),
    ('PROMOTION_PARTICIPANT', 'minBoost', 'DECIMAL', 'OPTIONAL', 'The smallest boost, in percent.', 12, false),
    ('PROMOTION_PARTICIPANT', 'maxBoost', 'DECIMAL', 'OPTIONAL', 'The largest boost, in percent.', 13, false),
    ('PROMOTION_PARTICIPANT', 'priceForMinBoost', 'DECIMAL', 'OPTIONAL', 'The price that earns the smallest boost.', 14, false),
    ('PROMOTION_PARTICIPANT', 'priceForMaxBoost', 'DECIMAL', 'OPTIONAL', 'The price that earns the largest boost.', 15, false),
    ('PROMOTION_PARTICIPANT', 'minStock', 'INTEGER', 'OPTIONAL', 'The fewest units a stock-discount promotion needs.', 16, false),
    ('PROMOTION_PARTICIPANT', 'recommendedStock', 'INTEGER', 'OPTIONAL', 'The units the marketplace recommends for the promotion.', 17, false),
    ('PROMOTION_PARTICIPANT', 'stock', 'INTEGER', 'OPTIONAL', 'The units in a stock-discount promotion.', 18, false),
    ('PROMOTION_PARTICIPANT', 'addMode', 'TEXT', 'OPTIONAL', 'AUTOMATIC when the marketplace added the product, SELLER when the seller did.', 19, false),
    ('PROMOTION_PARTICIPANT', 'quarantined', 'BOOLEAN', 'OPTIONAL', 'Whether the product is in quarantine.', 20, false);

-- What a person did with a promotion in the marketplace's own back office: joined, skipped or
-- left it, for one product, with the action price they set and why. The platform joins nothing
-- itself (automatic joining is outside 1.0); this is the record of a decision taken by hand, so
-- a later review can compare what was decided with what happened.
CREATE TABLE ops.promotion_decision (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    platform_promotion_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    decision text NOT NULL,
    action_price numeric(18,4),
    currency_code text,
    note text,
    decided_by_user_id uuid NOT NULL,
    decided_at timestamp with time zone NOT NULL,
    CONSTRAINT promotion_decision_decision_ck CHECK ((decision = ANY (ARRAY['JOINED'::text, 'SKIPPED'::text, 'LEFT'::text]))),
    CONSTRAINT promotion_decision_price_ck CHECK ((((action_price IS NULL) AND (currency_code IS NULL)) OR ((action_price > (0)::numeric) AND (currency_code ~ '^[A-Z]{3}$'::text)))),
    CONSTRAINT promotion_decision_price_joined_ck CHECK (((action_price IS NULL) OR (decision = 'JOINED'::text))),
    CONSTRAINT promotion_decision_note_ck CHECK (((note IS NULL) OR ((length(btrim(note)) >= 1) AND (length(note) <= 500))))
);

ALTER TABLE ONLY ops.promotion_decision
    ADD CONSTRAINT promotion_decision_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.promotion_decision
    ADD CONSTRAINT promotion_decision_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.promotion_decision
    ADD CONSTRAINT promotion_decision_promotion_fk FOREIGN KEY (platform_promotion_id, organization_id) REFERENCES core.platform_promotion(id, organization_id);

ALTER TABLE ONLY ops.promotion_decision
    ADD CONSTRAINT promotion_decision_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE INDEX promotion_decision_latest_ix ON ops.promotion_decision USING btree (store_id, platform_promotion_id, platform_listing_variant_id, decided_at DESC);

GRANT SELECT,INSERT ON TABLE ops.promotion_decision TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'promotion_decision', 'NO_ROUTE', NULL, 'append-only record of promotion decisions taken by hand in the marketplace back office; nothing is sent to a marketplace from it');

-- Recording a decision changes nothing on the marketplace, so no recent authentication is asked.
INSERT INTO iam.action_scope (code, display_name, description, requires_step_up, ordinal)
    SELECT 'PROMOTION_DECISION_RECORD', 'Record a promotion decision',
           'Record that a promotion was joined, skipped or left by hand in the marketplace back office.', false,
           max(ordinal) + 1
      FROM iam.action_scope;

INSERT INTO iam.business_role_action_scope (role_code, action_code) VALUES
    ('OWNER', 'PROMOTION_DECISION_RECORD'),
    ('OPS_LEAD', 'PROMOTION_DECISION_RECORD');

-- The promotion review: which products to join, keep, skip or leave in the store's current
-- promotions, from the promotions' terms, the estimated margins at their prices as ratios, and the
-- products' demand over the newest calculation window. The prices are the marketplace's own; no
-- cost, profit or break-even amount leaves. A fact cites only the listing values.
INSERT INTO ops.ai_projection_definition (projection_code, projection_version, purpose, retention_policy, owner_label, status) VALUES
    ('PROMOTION_REVIEW', 1, 'Which products to join, keep, skip or leave in a store''s current marketplace promotions, from the promotions'' terms, the estimated unit margins at their prices as ratios, and the products'' search demand and orders; no cost, profit or break-even amounts.', 'NO_PROVIDER_RETENTION', 'aicopilot', 'ACTIVE');

INSERT INTO ops.ai_projection_field (projection_code, projection_version, field_path, data_classification)
SELECT 'PROMOTION_REVIEW', 1, field.path, field.classification
  FROM (VALUES
    ('store.storeRef', 'OPAQUE_IDENTIFIER'),
    ('store.platformCode', 'OPERATING_ATTRIBUTE'),
    ('store.currencyCode', 'OPERATING_ATTRIBUTE'),
    ('store.minimumMargin', 'OPERATING_ATTRIBUTE'),
    ('store.promotionCount', 'DERIVED_VALUE'),
    ('window.windowCode', 'OPERATING_ATTRIBUTE'),
    ('window.periodStart', 'OPERATING_ATTRIBUTE'),
    ('window.periodEnd', 'OPERATING_ATTRIBUTE'),
    ('promotions.promotionRef', 'OPAQUE_IDENTIFIER'),
    ('promotions.title', 'MARKETPLACE_TEXT'),
    ('promotions.kind', 'OPERATING_ATTRIBUTE'),
    ('promotions.startsOn', 'OPERATING_ATTRIBUTE'),
    ('promotions.endsOn', 'OPERATING_ATTRIBUTE'),
    ('promotions.freezesOn', 'OPERATING_ATTRIBUTE'),
    ('promotions.participating', 'OPERATING_ATTRIBUTE'),
    ('promotions.discount', 'OPERATING_ATTRIBUTE'),
    ('promotions.productCount', 'DERIVED_VALUE'),
    ('promotions.keepsFloorCount', 'DERIVED_VALUE'),
    ('promotions.belowFloorCount', 'DERIVED_VALUE'),
    ('promotions.losesCount', 'DERIVED_VALUE'),
    ('promotions.unknownCount', 'DERIVED_VALUE'),
    ('items.listingRef', 'OPAQUE_IDENTIFIER'),
    ('items.promotionRef', 'OPAQUE_IDENTIFIER'),
    ('items.title', 'MARKETPLACE_TEXT'),
    ('items.size', 'MARKETPLACE_TEXT'),
    ('items.color', 'MARKETPLACE_TEXT'),
    ('items.membership', 'OPERATING_ATTRIBUTE'),
    ('items.addMode', 'OPERATING_ATTRIBUTE'),
    ('items.priceNow', 'OPERATING_ATTRIBUTE'),
    ('items.marginNow', 'DERIVED_VALUE'),
    ('items.actionPrice', 'OPERATING_ATTRIBUTE'),
    ('items.marginAtActionPrice', 'DERIVED_VALUE'),
    ('items.maxActionPrice', 'OPERATING_ATTRIBUTE'),
    ('items.marginAtMaxActionPrice', 'DERIVED_VALUE'),
    ('items.recommendedActionPrice', 'OPERATING_ATTRIBUTE'),
    ('items.marginAtRecommendedPrice', 'DERIVED_VALUE'),
    ('items.verdict', 'DERIVED_VALUE'),
    ('items.missingInput', 'OPERATING_ATTRIBUTE'),
    ('items.promotionPriceVsCompetitor', 'DERIVED_VALUE'),
    ('items.metricCode', 'CANONICAL_METRIC'),
    ('items.displayValue', 'CANONICAL_METRIC'),
    ('items.valueRef', 'OPAQUE_IDENTIFIER')) AS field (path, classification);

-- A promotion review recommends reviewing a promotion, or the costs its estimates lack; any other
-- action it proposes is kept as rejected.
ALTER TABLE ops.ai_output_claim DROP CONSTRAINT ai_output_claim_rejection_values_ck;
ALTER TABLE ops.ai_output_claim ADD CONSTRAINT ai_output_claim_rejection_values_ck
    CHECK (((rejection_code IS NULL) OR (rejection_code = ANY (ARRAY['SCHEMA_INVALID'::text, 'UNKNOWN_FIELD'::text, 'EVIDENCE_REFERENCE_UNRESOLVED'::text, 'EVIDENCE_REFERENCE_MISSING'::text, 'METRIC_NOT_RECOGNISED'::text, 'DERIVED_CALCULATION_NOT_PRODUCTIZED'::text, 'CAPABILITY_NOT_RECOGNISED'::text, 'STATEMENT_TOO_LONG'::text, 'INSTRUCTION_LIKE_CONTENT'::text, 'SECRET_LIKE_CONTENT'::text, 'LISTING_ASSISTANCE_ACTION_OUT_OF_SCOPE'::text, 'DRAFT_ATTRIBUTE_NOT_NAMED'::text, 'CONTENT_DRAFT_ACTION_OUT_OF_SCOPE'::text, 'PROMOTION_REVIEW_ACTION_OUT_OF_SCOPE'::text]))));

-- A promotion the listing can join that keeps the minimum unit margin at its highest price: a
-- metric for the best such margin and an informational rule on it. Joining stays a person's act
-- in the seller back office.
INSERT INTO mart.metric_definition (metric_code, definition_version, display_name, unit_kind, formula_statement, domain, owner_label, status) VALUES
    ('PROMOTION_BEST_MARGIN', 2, 'Best promotion margin', 'RATIO', 'The highest estimated unit margin at the maximum action price among the current marketplace promotions the listing can join, under the same commission, highest stated logistics tariffs at today''s price, acquiring, VAT and UNIT_COST as PROJECTED_UNIT_MARGIN; an estimate that errs low because a lower price may reduce some logistics tariffs.', 'PROFIT', 'analyticsdecision', 'ACTIVE');

INSERT INTO mart.diagnosis_rule (rule_code, rule_version, ordinal, display_name, statement, default_severity, blocks_execution, status) VALUES
    ('PROMOTION_OPPORTUNITY', 1, 17, 'Promotion opportunity', 'The listing can join a current marketplace promotion and the estimated unit margin at its maximum action price is at or above the configured minimum unit margin. Informational; a person joins in the seller back office and records the decision.', 'INFO', false, 'ACTIVE');

INSERT INTO mart.diagnosis_rule_input (rule_code, rule_version, metric_code, requirement) VALUES
    ('PROMOTION_OPPORTUNITY', 1, 'PROMOTION_BEST_MARGIN', 'REQUIRED');

-- A checkpoint may also move past a complete "not found" answer, which is stored and hashed like
-- any other. Two continuations rest on one: a promotion that ended after the list was read is
-- answered 404 and the next promotion is asked, and a source that ends a cursor listing with 404
-- (SHORT_PAGE_OR_NOT_FOUND, V0009) ends it there. Until now the function accepted only success
-- bytes, so both raised MO009 and the run was retried against the same position. Any other
-- refusal still cannot move a checkpoint.
CREATE OR REPLACE FUNCTION ops.acknowledge_checkpoint(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_observation_id uuid, p_expected_version bigint, p_position_value text) RETURNS bigint
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
    target_job  uuid;
    new_version bigint;
BEGIN
    -- Lock the run and hold the lock through the checkpoint write.
    SELECT run.job_id INTO target_job
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       AND run.fence_token = p_expected_fence
       AND run.lease_owner = p_expected_lease_owner
       AND run.state = 'RUNNING'
       AND run.lease_expires_at > clock_timestamp()
       FOR UPDATE OF run;

    IF target_job IS NULL THEN
        RAISE EXCEPTION
            'run % is not held by % at fence % with a live lease',
            p_run_id, p_expected_lease_owner, p_expected_fence
            USING ERRCODE = 'MO008';
    END IF;

    -- The evidence must exist, belong to this run, and be durable content.
    PERFORM 1
      FROM raw.raw_acquisition_observation AS observation
      JOIN raw.raw_logical_unit AS unit ON unit.id = observation.logical_unit_id
      JOIN raw.raw_content AS content ON content.id = observation.content_id
     WHERE observation.id = p_observation_id
       AND observation.run_id = p_run_id
       AND observation.response_complete
       AND (observation.outcome_class='SUCCESS_BYTES'
            OR (observation.outcome_class='BUSINESS_FAILURE_BYTES' AND observation.native_status='HTTP 404'))
       AND observation.pagination_outcome IN ('END','NEXT')
       AND unit.job_id = target_job;
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'checkpoint for run % has no committed observation %', p_run_id, p_observation_id
            USING ERRCODE = 'MO009',
                  HINT = 'store and hash the returned bytes before acknowledging a cursor';
    END IF;

    -- Lock the exact checkpoint row after the run. A concurrent holder may
    -- delay this acquisition past the lease deadline; after the row is obtained
    -- the final UPDATE below therefore rechecks wall-clock lease truth.
    PERFORM 1
      FROM ops.ingestion_checkpoint AS checkpoint
     WHERE checkpoint.job_id = target_job
       AND checkpoint.checkpoint_version = p_expected_version
       FOR UPDATE OF checkpoint;
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'checkpoint for job % is not at expected version %', target_job, p_expected_version
            USING ERRCODE = 'MO008';
    END IF;

    UPDATE ops.ingestion_checkpoint AS checkpoint
       SET position_value     = p_position_value,
           checkpoint_version = checkpoint.checkpoint_version + 1,
           updated_at         = clock_timestamp()
      FROM ops.ingestion_run AS run
     WHERE checkpoint.job_id = target_job
       AND checkpoint.checkpoint_version = p_expected_version
       AND run.id = p_run_id
       AND run.job_id = checkpoint.job_id
       AND run.state = 'RUNNING'
       AND run.fence_token = p_expected_fence
       AND run.lease_owner = p_expected_lease_owner
       AND run.lease_expires_at > clock_timestamp()
       AND EXISTS (
           SELECT 1
             FROM raw.raw_acquisition_observation AS observation
             JOIN raw.raw_logical_unit AS unit
               ON unit.id = observation.logical_unit_id
             JOIN raw.raw_content AS content
               ON content.id = observation.content_id
            WHERE observation.id = p_observation_id
              AND observation.run_id = run.id
              AND observation.response_complete
       AND (observation.outcome_class='SUCCESS_BYTES'
            OR (observation.outcome_class='BUSINESS_FAILURE_BYTES' AND observation.native_status='HTTP 404'))
              AND observation.pagination_outcome IN ('END','NEXT')
              AND unit.job_id = run.job_id)
    RETURNING checkpoint.checkpoint_version INTO new_version;

    IF new_version IS NULL THEN
        RAISE EXCEPTION
            'checkpoint authority for run % was lost before version % could advance',
            p_run_id, p_expected_version
            USING ERRCODE = 'MO008';
    END IF;

    RETURN new_version;
END;
$$;
