-- V0014: keep the marketplace's content rating of a listing as its own observation.
--
-- Ozon rates each product card's content (POST /v1/product/rating-by-sku, official OpenAPI checked
-- 2026-09-29): a rating from 0 to 100 per SKU, with the groups and conditions that make it up. It
-- explains a listing that does not sell and names what to improve, so it is a diagnostic input.
--
-- It is not a listing health observation. Availability risk, advertising protection and listing
-- health read the latest core.listing_health_observation row of a variant as its sellability; a
-- content observation carries no sellability, and writing it there as UNKNOWN would erase what
-- the status source said. A dataset of its own (LISTING_CONTENT) keeps the two apart:
--   * core.listing_content_observation: append-only, one row per variant and observation time;
--     content_rating on the 0..100 scale the marketplace states, with up to four decimals
--     (normalization refuses a value it cannot store exactly rather than rounding it);
--   * canonical fields: the variant keys (or the item key standing in for them), observedAt and
--     contentRating (required: a content record without a rating says nothing).
-- The conditions behind the rating stay in Raw for now.

ALTER TABLE platform.ingestion_job
    DROP CONSTRAINT ingestion_job_dataset_kind_ck;

ALTER TABLE platform.ingestion_job
    ADD CONSTRAINT ingestion_job_dataset_kind_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'UNKNOWN'::text, 'LISTING_CONTENT'::text])));

ALTER TABLE staging.canonical_field
    DROP CONSTRAINT canonical_field_dataset_ck;

ALTER TABLE staging.canonical_field
    ADD CONSTRAINT canonical_field_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text])));

ALTER TABLE staging.normalization_mapping
    DROP CONSTRAINT normalization_mapping_dataset_ck;

ALTER TABLE staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text])));

CREATE TABLE core.listing_content_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    content_rating numeric(7,4) NOT NULL,
    CONSTRAINT listing_content_observation_rating_ck CHECK (((content_rating >= (0)::numeric) AND (content_rating <= (100)::numeric)))
);

ALTER TABLE ONLY core.listing_content_observation
    ADD CONSTRAINT listing_content_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_content_observation
    ADD CONSTRAINT listing_content_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.listing_content_observation
    ADD CONSTRAINT listing_content_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_content_observation
    ADD CONSTRAINT listing_content_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE INDEX listing_content_observation_variant_ix ON core.listing_content_observation USING btree (platform_listing_variant_id, observed_at DESC);

GRANT SELECT,INSERT ON TABLE core.listing_content_observation TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'listing_content_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it');

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal) VALUES
    ('LISTING_CONTENT', 'nativeListingKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing.', 1),
    ('LISTING_CONTENT', 'nativeVariantKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing variant.', 2),
    ('LISTING_CONTENT', 'observedAt', 'INSTANT', 'REQUIRED', 'When the marketplace considered this rating true.', 3),
    ('LISTING_CONTENT', 'contentRating', 'DECIMAL', 'REQUIRED', 'The marketplace rating of the listing content, 0 to 100.', 4),
    ('LISTING_CONTENT', 'nativeItemKey', 'TEXT', 'OPTIONAL', 'The marketplace item identifier, when the source names the variant by it instead of the listing and variant keys.', 5);
