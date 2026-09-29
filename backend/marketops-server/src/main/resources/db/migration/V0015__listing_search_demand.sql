-- V0015: keep how many buyers searched for a listing, and with which search terms.
--
-- Ozon answers both per SKU for a period (official OpenAPI checked 2026-09-29):
--   * POST /v1/analytics/product-queries: unique_search_users ("buyers who searched for your
--     product on Ozon") and gmv ("sales from searches") with its currency, up to 1000 SKUs per
--     request; position, unique_view_users and view_conversion need a Premium subscription;
--   * POST /v1/analytics/product-queries/details: the same measures per search term (query) plus
--     order_count ("orders from the term"), up to 15 terms per SKU, 100 rows per page, pages
--     counted from 0, and every SKU of the store in one request.
-- Only the last month is available, never the current day. A listing that buyers search for and
-- do not buy is the core question the store diagnosis asks, so both become facts:
--   * core.listing_search_observation (LISTING_SEARCH): append-only, one row per variant and
--     period; search_revenue is money and is kept only together with its currency;
--   * core.listing_search_term_observation (LISTING_SEARCH_TERM): append-only, one row per
--     variant, period and search term as the marketplace wrote it.
-- Search analytics is evidence grade C (platform analytics): it explains and ranks a diagnosis
-- and never drives an automatic price change.
--
-- The details request pages by a zero-based page number over all of the store's item keys at
-- once, so two placeholders join the closed read set:
--   * pageIndex: the page position counted from 0 (page counts from 1);
--   * itemKeysAll: every recorded item key of the store as one JSON array, refused rather than
--     truncated above the most one request carries.

ALTER TABLE platform.ingestion_job
    DROP CONSTRAINT ingestion_job_dataset_kind_ck;

ALTER TABLE platform.ingestion_job
    ADD CONSTRAINT ingestion_job_dataset_kind_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'UNKNOWN'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text])));

ALTER TABLE staging.canonical_field
    DROP CONSTRAINT canonical_field_dataset_ck;

ALTER TABLE staging.canonical_field
    ADD CONSTRAINT canonical_field_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text])));

ALTER TABLE staging.normalization_mapping
    DROP CONSTRAINT normalization_mapping_dataset_ck;

ALTER TABLE staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text])));

CREATE TABLE core.listing_search_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    period_start timestamp with time zone NOT NULL,
    period_end timestamp with time zone NOT NULL,
    search_users bigint NOT NULL,
    currency_code text,
    search_revenue numeric(18,4),
    CONSTRAINT listing_search_observation_period_ck CHECK ((period_end > period_start)),
    CONSTRAINT listing_search_observation_users_ck CHECK ((search_users >= 0)),
    CONSTRAINT listing_search_observation_revenue_ck CHECK ((((search_revenue IS NULL) AND (currency_code IS NULL)) OR ((search_revenue >= (0)::numeric) AND (currency_code ~ '^[A-Z]{3}$'::text))))
);

ALTER TABLE ONLY core.listing_search_observation
    ADD CONSTRAINT listing_search_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_search_observation
    ADD CONSTRAINT listing_search_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.listing_search_observation
    ADD CONSTRAINT listing_search_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_search_observation
    ADD CONSTRAINT listing_search_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE INDEX listing_search_observation_variant_ix ON core.listing_search_observation USING btree (platform_listing_variant_id, period_end DESC);

CREATE TABLE core.listing_search_term_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    period_start timestamp with time zone NOT NULL,
    period_end timestamp with time zone NOT NULL,
    search_term text NOT NULL,
    search_users bigint NOT NULL,
    ordered_count bigint,
    currency_code text,
    search_revenue numeric(18,4),
    CONSTRAINT listing_search_term_observation_period_ck CHECK ((period_end > period_start)),
    CONSTRAINT listing_search_term_observation_term_ck CHECK (((char_length(search_term) >= 1) AND (char_length(search_term) <= 512))),
    CONSTRAINT listing_search_term_observation_users_ck CHECK ((search_users >= 0)),
    CONSTRAINT listing_search_term_observation_orders_ck CHECK (((ordered_count IS NULL) OR (ordered_count >= 0))),
    CONSTRAINT listing_search_term_observation_revenue_ck CHECK ((((search_revenue IS NULL) AND (currency_code IS NULL)) OR ((search_revenue >= (0)::numeric) AND (currency_code ~ '^[A-Z]{3}$'::text))))
);

ALTER TABLE ONLY core.listing_search_term_observation
    ADD CONSTRAINT listing_search_term_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_search_term_observation
    ADD CONSTRAINT listing_search_term_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.listing_search_term_observation
    ADD CONSTRAINT listing_search_term_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_search_term_observation
    ADD CONSTRAINT listing_search_term_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE INDEX listing_search_term_observation_variant_ix ON core.listing_search_term_observation USING btree (platform_listing_variant_id, period_end DESC);

GRANT SELECT,INSERT ON TABLE core.listing_search_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.listing_search_term_observation TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'listing_search_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it'),
    ('core', 'listing_search_term_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it');

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal) VALUES
    ('LISTING_SEARCH', 'nativeListingKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing.', 1),
    ('LISTING_SEARCH', 'nativeVariantKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing variant.', 2),
    ('LISTING_SEARCH', 'periodStart', 'INSTANT', 'REQUIRED', 'Start of the period the searches were counted over.', 3),
    ('LISTING_SEARCH', 'periodEnd', 'INSTANT', 'REQUIRED', 'End of the period the searches were counted over.', 4),
    ('LISTING_SEARCH', 'searchUsers', 'INTEGER', 'REQUIRED', 'Buyers who searched for the listing in the period.', 5),
    ('LISTING_SEARCH', 'searchRevenue', 'DECIMAL', 'OPTIONAL', 'Sales the marketplace attributes to those searches.', 6),
    ('LISTING_SEARCH', 'currencyCode', 'TEXT', 'OPTIONAL', 'ISO 4217 currency of searchRevenue.', 7),
    ('LISTING_SEARCH', 'nativeItemKey', 'TEXT', 'OPTIONAL', 'The marketplace item identifier, when the source names the variant by it instead of the listing and variant keys.', 8),
    ('LISTING_SEARCH_TERM', 'nativeListingKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing.', 1),
    ('LISTING_SEARCH_TERM', 'nativeVariantKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing variant.', 2),
    ('LISTING_SEARCH_TERM', 'periodStart', 'INSTANT', 'REQUIRED', 'Start of the period the searches were counted over.', 3),
    ('LISTING_SEARCH_TERM', 'periodEnd', 'INSTANT', 'REQUIRED', 'End of the period the searches were counted over.', 4),
    ('LISTING_SEARCH_TERM', 'searchTerm', 'TEXT', 'REQUIRED', 'The search term, as the marketplace wrote it.', 5),
    ('LISTING_SEARCH_TERM', 'searchUsers', 'INTEGER', 'REQUIRED', 'Buyers who searched for the listing with this term in the period.', 6),
    ('LISTING_SEARCH_TERM', 'orderedCount', 'INTEGER', 'OPTIONAL', 'Orders the marketplace attributes to this term.', 7),
    ('LISTING_SEARCH_TERM', 'searchRevenue', 'DECIMAL', 'OPTIONAL', 'Sales the marketplace attributes to this term.', 8),
    ('LISTING_SEARCH_TERM', 'currencyCode', 'TEXT', 'OPTIONAL', 'ISO 4217 currency of searchRevenue.', 9),
    ('LISTING_SEARCH_TERM', 'nativeItemKey', 'TEXT', 'OPTIONAL', 'The marketplace item identifier, when the source names the variant by it instead of the listing and variant keys.', 10);

-- The closed placeholder set of V0013 grows by pageIndex and itemKeysAll.
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
            'listingKeyBatch', 'itemKeyBatch', 'itemKeysAll'] END;
    FOR token IN SELECT regexp_matches(p_template, '\{([a-zA-Z][a-zA-Z0-9]{0,31})\}', 'g') LOOP
        IF NOT token[1] = ANY(allowed) THEN RETURN false; END IF;
        rendered := replace(rendered, '{' || token[1] || '}',
            CASE WHEN token[1] IN ('targetPrice', 'targetBid', 'limit', 'cursor', 'offset', 'page', 'pageIndex')
                 THEN '1' WHEN token[1] IN ('listingKeyBatch', 'itemKeyBatch', 'itemKeysAll') THEN '[]'
                 ELSE 'fixture' END);
    END LOOP;
    IF p_is_body THEN RETURN rendered IS JSON OBJECT WITH UNIQUE KEYS; END IF;
    RETURN rendered !~ '[{}[:cntrl:]]';
END;
$$;
