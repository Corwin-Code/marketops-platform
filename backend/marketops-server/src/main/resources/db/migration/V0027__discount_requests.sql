-- V0027: buyers' discount requests (P8, part two).
--
-- POST /v2/actions/discounts-task/list (official OpenAPI info.version 2.1, checked 2026-10-01) lists
-- the requests buyers made to buy a product at a lower price, in every state (NEW, APPROVED,
-- DECLINED). The answer carries no position: the next page asks after the last request's id
-- ("last_id: the identifier of the last value on the page; leave it empty on the first request"),
-- and the list ends with an empty page. Pilot (2026-10-01): 331 requests from 2026-04-16 to
-- 2026-09-27 on six full pages of 50 and one of 31, ids descending and never repeated, the request
-- after the last page empty; 329 approved, 2 declined, none new; 64 SKUs, 8 of them in the current
-- catalogue (11 requests).
--
-- A new pagination model, LAST_RECORD_KEY: the next request carries the key of the previous page's
-- last record, read at continuation_pointer inside that record (here /id), through the placeholder
-- {lastRecordKey}. It renders as a bare JSON number, or null on the first page, which proto3 JSON
-- reads as an absent field. Such a source ends a listing with an empty page, so the model needs
-- the EMPTY_RECORDS end rule and a records pointer. The page size stays in the recorded template:
-- this method allows 5 to 50, below the 100 that {limit} renders, and an empty page ends the
-- listing whatever the size. The evidence approval of V0013 already asks a paged endpoint for a
-- continuation pointer, which this model has.
--
-- Dataset DISCOUNT_REQUEST: one record per request and answer, recorded against the store (most
-- requested SKUs are no longer in the catalogue; the read side joins the others to their listings
-- by SKU). The answer states no currency; its amounts are in the store's currency (the pilot store
-- and all 41 of its listing prices are RUB). Not read: the seller's employee who handled a request
-- (email, first name, last name, patronymic); approved_discount, which the documentation calls
-- roubles and the pilot's answers state as percent of the original price (329 of 329; the approved
-- price says the same); edited_till (equal to the moderation time on every pilot request); and
-- min_auto_price, reduction_factor and the auto-moderation settings, which the diagnosis does not use.

ALTER TABLE platform.platform_endpoint
    DROP CONSTRAINT platform_endpoint_pagination_ck;

ALTER TABLE platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_pagination_ck CHECK ((pagination_model = ANY (ARRAY['CURSOR'::text, 'OFFSET'::text, 'PAGE'::text, 'DATE_WINDOW'::text, 'LAST_RECORD_KEY'::text, 'NONE'::text, 'UNKNOWN'::text])));

-- A template asks after the last record's key only under that model, which then says where a
-- page's records and the key inside the last of them live, and ends on an empty page.
ALTER TABLE platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_last_record_key_ck CHECK (((strpos((coalesce(body_template, ''::text) || coalesce(query_template, ''::text)), '{lastRecordKey}'::text) = 0) OR ((pagination_model = 'LAST_RECORD_KEY'::text) AND (continuation_end_rule = 'EMPTY_RECORDS'::text) AND (continuation_pointer IS NOT NULL) AND (records_pointer IS NOT NULL))));

ALTER TABLE ops.ingestion_checkpoint
    DROP CONSTRAINT ingestion_checkpoint_strategy_ck;

ALTER TABLE ops.ingestion_checkpoint
    ADD CONSTRAINT ingestion_checkpoint_strategy_ck CHECK ((strategy = ANY (ARRAY['CURSOR'::text, 'OFFSET'::text, 'PAGE'::text, 'DATE_WINDOW'::text, 'LAST_RECORD_KEY'::text, 'NONE'::text, 'UNKNOWN'::text])));

-- The closed placeholder set of V0023 grows by lastRecordKey, a whole number.
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
            'listingKeyBatch', 'itemKeyBatch', 'itemKeysAll', 'promotionKey', 'lastRecordKey'] END;
    FOR token IN SELECT regexp_matches(p_template, '\{([a-zA-Z][a-zA-Z0-9]{0,31})\}', 'g') LOOP
        IF NOT token[1] = ANY(allowed) THEN RETURN false; END IF;
        rendered := replace(rendered, '{' || token[1] || '}',
            CASE WHEN token[1] IN ('targetPrice', 'targetBid', 'limit', 'cursor', 'offset', 'page', 'pageIndex', 'promotionKey', 'lastRecordKey')
                 THEN '1' WHEN token[1] IN ('listingKeyBatch', 'itemKeyBatch', 'itemKeysAll') THEN '[]'
                 ELSE 'fixture' END);
    END LOOP;
    IF p_is_body THEN RETURN rendered IS JSON OBJECT WITH UNIQUE KEYS; END IF;
    RETURN rendered !~ '[{}[:cntrl:]]';
END;
$$;

ALTER TABLE platform.ingestion_job
    DROP CONSTRAINT ingestion_job_dataset_kind_ck;

ALTER TABLE platform.ingestion_job
    ADD CONSTRAINT ingestion_job_dataset_kind_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'UNKNOWN'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text, 'SELLER_RATING'::text, 'FBS_WAREHOUSE'::text, 'DISCOUNT_REQUEST'::text])));

ALTER TABLE staging.canonical_field
    DROP CONSTRAINT canonical_field_dataset_ck;

ALTER TABLE staging.canonical_field
    ADD CONSTRAINT canonical_field_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'LISTING_ATTRIBUTE'::text, 'LISTING_CONTENT_GROUP'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text, 'SELLER_RATING'::text, 'SELLER_RATING_ITEM'::text, 'FBS_WAREHOUSE'::text, 'DISCOUNT_REQUEST'::text])));

ALTER TABLE staging.normalization_mapping
    DROP CONSTRAINT normalization_mapping_dataset_ck,
    DROP CONSTRAINT normalization_mapping_source_dataset_ck;

ALTER TABLE staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'LISTING_ATTRIBUTE'::text, 'LISTING_CONTENT_GROUP'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text, 'SELLER_RATING'::text, 'SELLER_RATING_ITEM'::text, 'FBS_WAREHOUSE'::text, 'DISCOUNT_REQUEST'::text]))),
    ADD CONSTRAINT normalization_mapping_source_dataset_ck CHECK (((source_dataset_kind IS NULL) OR ((source_dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text, 'SELLER_RATING'::text, 'FBS_WAREHOUSE'::text, 'DISCOUNT_REQUEST'::text])) AND (source_dataset_kind <> dataset_kind))));

-- One buyer's discount request as one answer stated it. Amounts are kept only with a currency.
CREATE TABLE core.discount_request_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    store_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    native_request_key text NOT NULL,
    native_item_key text,
    product_name text,
    status text,
    requested_at timestamp with time zone,
    moderated_at timestamp with time zone,
    expires_at timestamp with time zone,
    currency_code text,
    original_price numeric(18,4),
    requested_price numeric(18,4),
    requested_discount_percent numeric(9,4),
    requested_quantity integer,
    approved_price numeric(18,4),
    approved_quantity integer,
    auto_moderated boolean,
    CONSTRAINT discount_request_observation_key_ck CHECK (((length(native_request_key) >= 1) AND (length(native_request_key) <= 64))),
    CONSTRAINT discount_request_observation_texts_ck CHECK ((((native_item_key IS NULL) OR ((length(native_item_key) >= 1) AND (length(native_item_key) <= 64))) AND ((product_name IS NULL) OR (length(product_name) <= 512)) AND ((status IS NULL) OR (length(status) <= 64)))),
    CONSTRAINT discount_request_observation_money_ck CHECK (((currency_code IS NULL) = ((original_price IS NULL) AND (requested_price IS NULL) AND (approved_price IS NULL))) AND ((currency_code IS NULL) OR (currency_code ~ '^[A-Z]{3}$'::text)) AND ((original_price IS NULL) OR (original_price > (0)::numeric)) AND ((requested_price IS NULL) OR (requested_price > (0)::numeric)) AND ((approved_price IS NULL) OR (approved_price > (0)::numeric))),
    CONSTRAINT discount_request_observation_percent_ck CHECK (((requested_discount_percent IS NULL) OR ((requested_discount_percent >= (0)::numeric) AND (requested_discount_percent <= (100)::numeric)))),
    CONSTRAINT discount_request_observation_quantity_ck CHECK ((((requested_quantity IS NULL) OR (requested_quantity >= 0)) AND ((approved_quantity IS NULL) OR (approved_quantity >= 0))))
);

ALTER TABLE ONLY core.discount_request_observation
    ADD CONSTRAINT discount_request_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.discount_request_observation
    ADD CONSTRAINT discount_request_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.discount_request_observation
    ADD CONSTRAINT discount_request_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.discount_request_observation
    ADD CONSTRAINT discount_request_observation_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

-- The newest state of each request, and the store's newest answer.
CREATE INDEX discount_request_observation_request_ix ON core.discount_request_observation USING btree (store_id, native_request_key, observed_at DESC);

CREATE INDEX discount_request_observation_store_ix ON core.discount_request_observation USING btree (store_id, observed_at DESC);

GRANT SELECT,INSERT ON TABLE core.discount_request_observation TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'discount_request_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it');

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal, repeated) VALUES
    ('DISCOUNT_REQUEST', 'nativeRequestKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the request.', 1, false),
    ('DISCOUNT_REQUEST', 'observedAt', 'INSTANT', 'REQUIRED', 'When the marketplace considered this description true.', 2, false),
    ('DISCOUNT_REQUEST', 'nativeItemKey', 'TEXT', 'OPTIONAL', 'The marketplace item identifier (SKU) the buyer asked about.', 3, false),
    ('DISCOUNT_REQUEST', 'productName', 'TEXT', 'OPTIONAL', 'The product''s name as the request states it.', 4, false),
    ('DISCOUNT_REQUEST', 'status', 'TEXT', 'OPTIONAL', 'NEW, APPROVED or DECLINED.', 5, false),
    ('DISCOUNT_REQUEST', 'requestedAt', 'INSTANT', 'OPTIONAL', 'When the buyer made the request.', 6, false),
    ('DISCOUNT_REQUEST', 'moderatedAt', 'INSTANT', 'OPTIONAL', 'When the request was looked at, approved or declined.', 7, false),
    ('DISCOUNT_REQUEST', 'expiresAt', 'INSTANT', 'OPTIONAL', 'When the request ends; for a new request, the time left to decide.', 8, false),
    ('DISCOUNT_REQUEST', 'originalPrice', 'DECIMAL', 'OPTIONAL', 'The product''s price before every discount when the buyer asked.', 9, false),
    ('DISCOUNT_REQUEST', 'requestedPrice', 'DECIMAL', 'OPTIONAL', 'The price the buyer asked for.', 10, false),
    ('DISCOUNT_REQUEST', 'requestedDiscountPercent', 'DECIMAL', 'OPTIONAL', 'The discount the buyer asked for, in percent of the original price.', 11, false),
    ('DISCOUNT_REQUEST', 'requestedQuantity', 'INTEGER', 'OPTIONAL', 'How many units the buyer asked for at most.', 12, false),
    ('DISCOUNT_REQUEST', 'approvedPrice', 'DECIMAL', 'OPTIONAL', 'The price the seller approved; 0 when the request was not approved.', 13, false),
    ('DISCOUNT_REQUEST', 'approvedQuantity', 'INTEGER', 'OPTIONAL', 'How many units the approval covers at most.', 14, false),
    ('DISCOUNT_REQUEST', 'autoModerated', 'BOOLEAN', 'OPTIONAL', 'Whether the request was decided automatically.', 15, false);
