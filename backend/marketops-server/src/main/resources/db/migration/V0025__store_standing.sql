-- V0025: the store's own standing on the marketplace (tuning round, data backfill).
--
-- Two read methods (official OpenAPI info.version 2.1, checked 2026-10-01):
--   * POST /v1/rating/summary ("Rating" role): whether the store has Premium or Premium Plus,
--     whether its penalty balance is exceeded, its localization index (empty without sales in
--     the last 14 days), and its ratings by group — each with Ozon's status (OK, WARNING,
--     CRITICAL, UNKNOWN_STATUS), value type, which direction is good, the current and previous
--     value and the change;
--   * POST /v2/warehouse/list ("Warehouse" role, cursor paging): the FBS and rFBS warehouses —
--     status (created = active, new = activating, disabled = archived, blocked,
--     disabled_due_to_limit = paused, error; the mapping is stated in /v1/warehouse/list), type,
--     rFBS, express, large goods, automatic assembly, first mile (PICK_UP or DROP_OFF), working
--     days, the time to hand over and the least assembly time in minutes, the postings limit
--     (-1 without one), the pause and the warehouse's own creation and update times. Names,
--     addresses, coordinates, phones, courier notes, drop-off points and time slots are not read.
--
-- Pilot (2026-10-01): no Premium, no Premium Plus, penalty balance not exceeded, localization
-- computed 2026-09-23; ten ratings in four groups, all at zero with six OK and four without a
-- status, because the store has no orders yet. One FBS warehouse, active, drop-off, seven working
-- days, no postings limit. The delivery-method list (/v2/delivery-method/list) serves rFBS
-- warehouses only, and v1 was switched off 2026-04-07: an FBS store has none, so it is not read.
--
-- Datasets: SELLER_RATING reads the summary itself (a record at the root of the answer) and the
-- companion SELLER_RATING_ITEM one record per rating; FBS_WAREHOUSE one record per warehouse.
-- Each observation is append-only and belongs to the store; the newest observation time of a
-- store is what is current.

ALTER TABLE platform.ingestion_job
    DROP CONSTRAINT ingestion_job_dataset_kind_ck;

ALTER TABLE platform.ingestion_job
    ADD CONSTRAINT ingestion_job_dataset_kind_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'UNKNOWN'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text, 'SELLER_RATING'::text, 'FBS_WAREHOUSE'::text])));

ALTER TABLE staging.canonical_field
    DROP CONSTRAINT canonical_field_dataset_ck;

ALTER TABLE staging.canonical_field
    ADD CONSTRAINT canonical_field_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'LISTING_ATTRIBUTE'::text, 'LISTING_CONTENT_GROUP'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text, 'SELLER_RATING'::text, 'SELLER_RATING_ITEM'::text, 'FBS_WAREHOUSE'::text])));

ALTER TABLE staging.normalization_mapping
    DROP CONSTRAINT normalization_mapping_dataset_ck,
    DROP CONSTRAINT normalization_mapping_source_dataset_ck;

ALTER TABLE staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'LISTING_ATTRIBUTE'::text, 'LISTING_CONTENT_GROUP'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text, 'SELLER_RATING'::text, 'SELLER_RATING_ITEM'::text, 'FBS_WAREHOUSE'::text]))),
    ADD CONSTRAINT normalization_mapping_source_dataset_ck CHECK (((source_dataset_kind IS NULL) OR ((source_dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'PROMOTION'::text, 'PROMOTION_CANDIDATE'::text, 'PROMOTION_PARTICIPANT'::text, 'SELLER_RATING'::text, 'FBS_WAREHOUSE'::text])) AND (source_dataset_kind <> dataset_kind))));

-- The store's rating summary as one answer stated it.
CREATE TABLE core.seller_rating_summary_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    store_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    premium boolean,
    premium_plus boolean,
    penalty_score_exceeded boolean,
    localization_calculated_at timestamp with time zone,
    localization_percentage numeric(9,4),
    CONSTRAINT seller_rating_summary_localization_ck CHECK (((localization_percentage IS NULL) OR ((localization_percentage >= (0)::numeric) AND (localization_percentage <= (100)::numeric))))
);

ALTER TABLE ONLY core.seller_rating_summary_observation
    ADD CONSTRAINT seller_rating_summary_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.seller_rating_summary_observation
    ADD CONSTRAINT seller_rating_summary_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.seller_rating_summary_observation
    ADD CONSTRAINT seller_rating_summary_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.seller_rating_summary_observation
    ADD CONSTRAINT seller_rating_summary_observation_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

CREATE INDEX seller_rating_summary_observation_store_ix ON core.seller_rating_summary_observation USING btree (store_id, observed_at DESC);

-- One rating of the store as one answer stated it.
CREATE TABLE core.seller_rating_item_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    store_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    rating_key text NOT NULL,
    group_name text,
    rating_name text,
    value_type text,
    direction text,
    status text,
    current_value numeric(18,6),
    past_value numeric(18,6),
    change_direction text,
    change_meaning text,
    CONSTRAINT seller_rating_item_key_ck CHECK (((length(rating_key) >= 1) AND (length(rating_key) <= 128))),
    CONSTRAINT seller_rating_item_texts_ck CHECK ((((group_name IS NULL) OR (length(group_name) <= 256)) AND ((rating_name IS NULL) OR (length(rating_name) <= 256)))),
    CONSTRAINT seller_rating_item_codes_ck CHECK ((((value_type IS NULL) OR (length(value_type) <= 64)) AND ((direction IS NULL) OR (length(direction) <= 64)) AND ((status IS NULL) OR (length(status) <= 64)) AND ((change_direction IS NULL) OR (length(change_direction) <= 64)) AND ((change_meaning IS NULL) OR (length(change_meaning) <= 64))))
);

ALTER TABLE ONLY core.seller_rating_item_observation
    ADD CONSTRAINT seller_rating_item_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.seller_rating_item_observation
    ADD CONSTRAINT seller_rating_item_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.seller_rating_item_observation
    ADD CONSTRAINT seller_rating_item_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.seller_rating_item_observation
    ADD CONSTRAINT seller_rating_item_observation_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

CREATE INDEX seller_rating_item_observation_store_ix ON core.seller_rating_item_observation USING btree (store_id, observed_at DESC);

-- One warehouse of the store as one answer stated it.
CREATE TABLE core.warehouse_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    store_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    native_warehouse_key text NOT NULL,
    warehouse_type text,
    status text,
    rfbs boolean,
    express boolean,
    large_goods boolean,
    auto_assembly boolean,
    first_mile_kind text,
    working_day_count integer,
    handover_minutes integer,
    assembly_minutes integer,
    postings_limit integer,
    min_postings_limit integer,
    has_postings_limit boolean,
    paused_at timestamp with time zone,
    source_created_at timestamp with time zone,
    source_updated_at timestamp with time zone,
    time_zone text,
    CONSTRAINT warehouse_observation_key_ck CHECK (((length(native_warehouse_key) >= 1) AND (length(native_warehouse_key) <= 64))),
    CONSTRAINT warehouse_observation_codes_ck CHECK ((((warehouse_type IS NULL) OR (length(warehouse_type) <= 64)) AND ((status IS NULL) OR (length(status) <= 64)) AND ((first_mile_kind IS NULL) OR (length(first_mile_kind) <= 64)) AND ((time_zone IS NULL) OR (length(time_zone) <= 32)))),
    CONSTRAINT warehouse_observation_counts_ck CHECK ((((working_day_count IS NULL) OR ((working_day_count >= 0) AND (working_day_count <= 7))) AND ((handover_minutes IS NULL) OR (handover_minutes >= 0)) AND ((assembly_minutes IS NULL) OR (assembly_minutes >= 0)) AND ((postings_limit IS NULL) OR (postings_limit >= '-1'::integer)) AND ((min_postings_limit IS NULL) OR (min_postings_limit >= 0))))
);

ALTER TABLE ONLY core.warehouse_observation
    ADD CONSTRAINT warehouse_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.warehouse_observation
    ADD CONSTRAINT warehouse_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.warehouse_observation
    ADD CONSTRAINT warehouse_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.warehouse_observation
    ADD CONSTRAINT warehouse_observation_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

CREATE INDEX warehouse_observation_store_ix ON core.warehouse_observation USING btree (store_id, observed_at DESC);

GRANT SELECT,INSERT ON TABLE core.seller_rating_summary_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.seller_rating_item_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.warehouse_observation TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'seller_rating_summary_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it'),
    ('core', 'seller_rating_item_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it'),
    ('core', 'warehouse_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it');

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal, repeated) VALUES
    ('SELLER_RATING', 'observedAt', 'INSTANT', 'REQUIRED', 'When the marketplace considered this summary true.', 1, false),
    ('SELLER_RATING', 'premium', 'BOOLEAN', 'OPTIONAL', 'Whether the store has the Premium subscription.', 2, false),
    ('SELLER_RATING', 'premiumPlus', 'BOOLEAN', 'OPTIONAL', 'Whether the store has the Premium Plus subscription.', 3, false),
    ('SELLER_RATING', 'penaltyScoreExceeded', 'BOOLEAN', 'OPTIONAL', 'Whether the store''s penalty balance is exceeded.', 4, false),
    ('SELLER_RATING', 'localizationCalculatedAt', 'INSTANT', 'OPTIONAL', 'When the localization index was calculated; absent without sales in the last 14 days.', 5, false),
    ('SELLER_RATING', 'localizationPercentage', 'DECIMAL', 'OPTIONAL', 'The localization index in percent.', 6, false),
    ('SELLER_RATING_ITEM', 'ratingKey', 'TEXT', 'REQUIRED', 'The marketplace''s system name of the rating.', 1, false),
    ('SELLER_RATING_ITEM', 'observedAt', 'INSTANT', 'REQUIRED', 'When the marketplace considered this rating true.', 2, false),
    ('SELLER_RATING_ITEM', 'groupName', 'TEXT', 'OPTIONAL', 'The marketplace''s name of the rating group.', 3, false),
    ('SELLER_RATING_ITEM', 'ratingName', 'TEXT', 'OPTIONAL', 'The marketplace''s name of the rating.', 4, false),
    ('SELLER_RATING_ITEM', 'valueType', 'TEXT', 'OPTIONAL', 'INDEX, PERCENT, TIME, RATIO, REVIEW_SCORE or COUNT, as the marketplace states it.', 5, false),
    ('SELLER_RATING_ITEM', 'direction', 'TEXT', 'OPTIONAL', 'HIGHER_IS_BETTER, LOWER_IS_BETTER or NEUTRAL.', 6, false),
    ('SELLER_RATING_ITEM', 'status', 'TEXT', 'OPTIONAL', 'The marketplace''s verdict: OK, WARNING, CRITICAL or UNKNOWN_STATUS.', 7, false),
    ('SELLER_RATING_ITEM', 'currentValue', 'DECIMAL', 'OPTIONAL', 'The rating''s current value, as the marketplace states it.', 8, false),
    ('SELLER_RATING_ITEM', 'pastValue', 'DECIMAL', 'OPTIONAL', 'The rating''s previous value.', 9, false),
    ('SELLER_RATING_ITEM', 'changeDirection', 'TEXT', 'OPTIONAL', 'How the rating moved, as the marketplace states it.', 10, false),
    ('SELLER_RATING_ITEM', 'changeMeaning', 'TEXT', 'OPTIONAL', 'Whether the move is good or bad, as the marketplace states it.', 11, false),
    ('FBS_WAREHOUSE', 'nativeWarehouseKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the warehouse.', 1, false),
    ('FBS_WAREHOUSE', 'observedAt', 'INSTANT', 'REQUIRED', 'When the marketplace considered this description true.', 2, false),
    ('FBS_WAREHOUSE', 'warehouseType', 'TEXT', 'OPTIONAL', 'The marketplace''s word for the kind of warehouse.', 3, false),
    ('FBS_WAREHOUSE', 'status', 'TEXT', 'OPTIONAL', 'created (active), new, disabled, blocked, disabled_due_to_limit or error.', 4, false),
    ('FBS_WAREHOUSE', 'rfbs', 'BOOLEAN', 'OPTIONAL', 'Whether the warehouse works under rFBS.', 5, false),
    ('FBS_WAREHOUSE', 'express', 'BOOLEAN', 'OPTIONAL', 'Whether it delivers express, within 60 minutes.', 6, false),
    ('FBS_WAREHOUSE', 'largeGoods', 'BOOLEAN', 'OPTIONAL', 'Whether it accepts large goods.', 7, false),
    ('FBS_WAREHOUSE', 'autoAssembly', 'BOOLEAN', 'OPTIONAL', 'Whether automatic assembly is on.', 8, false),
    ('FBS_WAREHOUSE', 'firstMileKind', 'TEXT', 'OPTIONAL', 'PICK_UP or DROP_OFF.', 9, false),
    ('FBS_WAREHOUSE', 'workingDayCount', 'INTEGER', 'OPTIONAL', 'How many weekdays the warehouse works.', 10, false),
    ('FBS_WAREHOUSE', 'handoverMinutes', 'INTEGER', 'OPTIONAL', 'The time to hand orders over, in minutes.', 11, false),
    ('FBS_WAREHOUSE', 'assemblyMinutes', 'INTEGER', 'OPTIONAL', 'The least time to assemble an order, in minutes.', 12, false),
    ('FBS_WAREHOUSE', 'postingsLimit', 'INTEGER', 'OPTIONAL', 'The orders limit; -1 without one.', 13, false),
    ('FBS_WAREHOUSE', 'minPostingsLimit', 'INTEGER', 'OPTIONAL', 'The fewest orders one shipment may carry.', 14, false),
    ('FBS_WAREHOUSE', 'hasPostingsLimit', 'BOOLEAN', 'OPTIONAL', 'Whether a least number of orders applies.', 15, false),
    ('FBS_WAREHOUSE', 'pausedAt', 'INSTANT', 'OPTIONAL', 'When the seller paused the warehouse; rFBS only.', 16, false),
    ('FBS_WAREHOUSE', 'sourceCreatedAt', 'INSTANT', 'OPTIONAL', 'When the warehouse was created.', 17, false),
    ('FBS_WAREHOUSE', 'sourceUpdatedAt', 'INSTANT', 'OPTIONAL', 'When the warehouse was last updated.', 18, false),
    ('FBS_WAREHOUSE', 'timeZone', 'TEXT', 'OPTIONAL', 'The warehouse''s UTC offset, such as UTC+03:00.', 19, false);
