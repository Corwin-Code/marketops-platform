-- V0022: keep the content of a listing card as facts: its attributes and description, how many
-- images it shows, and the groups and conditions behind the marketplace's content rating (P6).
--
-- Both answers are already collected every day and kept in Raw (official OpenAPI checked
-- 2026-09-29):
--   * POST /v4/product/info/attributes (the catalog job, dataset LISTING): per product its
--     description category and product type, its images, and attributes[] of {id, complex_id,
--     values[{dictionary_value_id, value}]} (attributes of complex groups, such as a video, come
--     separately and are not read); Ozon writes the description (Аннотация) as attribute 4191 and
--     the rich content as attribute 11254;
--   * POST /v1/product/rating-by-sku (the content job, dataset LISTING_CONTENT): per SKU its
--     groups[] of {key, name, rating, weight, conditions[{key, description, fulfilled, cost}],
--     improve_attributes[{id, name}], improve_at_least}; the attributes a group names are the
--     ones to fill to raise the rating.
-- They answer "what exactly is missing from this card", which the content optimization in the
-- store diagnosis drawer and its Russian drafts start from.
--
-- A declaration so far produced one kind of fact per job. Both answers carry a second, nested
-- kind, so a declaration may now be a companion: it reads the evidence of a job of another
-- dataset (source_dataset_kind) beside that dataset's own declaration, with its own records, in
-- the same pass and under the same cursor. Its records are child records (attributes[] of a
-- product, groups[] of a SKU); the pointers its children cover no longer count as drift of the
-- main declaration.
--
-- Two field sources are added, both still addressing the document and never computing from it:
--   * EACH_POINTER reads a list: source_pointer names an array inside the record, element_pointer
--     the value inside each element (every value of an attribute; every condition key, text,
--     fulfilment and cost of a group). Only a canonical field declared repeated takes a list, and
--     only a list goes into a repeated field. Lists read from the same array keep their positions
--     (an element without the value is a null), so parallel lists can be zipped;
--   * ARRAY_LENGTH reads how many elements an array holds (the number of images), for INTEGER
--     fields only.
--
-- The content facts change rarely while the snapshots arrive daily, so attributes and groups are
-- written only when they differ from the newest row of the same listing and key (a revert to an
-- earlier value is a change again). The per-listing catalog observation is written with every
-- snapshot and lists which attribute keys the card carried, so an attribute the seller removed is
-- not taken for a current one. The application role keeps SELECT and INSERT only: facts are
-- appended, never edited.
--
-- The management-side listing-conversion observations (core.lc_description_observation,
-- lc_display_observation) are not written here: their single writer is the listing-conversion
-- module, the drawer and the model read operating facts, and their consumer (the description write
-- chain) stays off. Feeding them from these facts belongs to the description write capability (W2).

-- Companion declarations.
ALTER TABLE staging.normalization_mapping
    ADD COLUMN source_dataset_kind text;

ALTER TABLE staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_source_dataset_ck CHECK (((source_dataset_kind IS NULL) OR ((source_dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text])) AND (source_dataset_kind <> dataset_kind))));

ALTER TABLE staging.normalization_mapping
    DROP CONSTRAINT normalization_mapping_dataset_ck;

ALTER TABLE staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'LISTING_ATTRIBUTE'::text, 'LISTING_CONTENT_GROUP'::text])));

-- A companion always reads child records of the source dataset's records.
ALTER TABLE staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_companion_child_ck CHECK (((source_dataset_kind IS NULL) OR (child_pointer IS NOT NULL)));

-- Lists and array lengths.
ALTER TABLE staging.normalization_field
    ADD COLUMN element_pointer text;

ALTER TABLE staging.normalization_field
    DROP CONSTRAINT normalization_field_source_kind_ck,
    DROP CONSTRAINT normalization_field_source_shape_ck;

ALTER TABLE staging.normalization_field
    ADD CONSTRAINT normalization_field_source_kind_ck CHECK ((source_kind = ANY (ARRAY['POINTER'::text, 'PARENT_POINTER'::text, 'OBSERVATION_TIME'::text, 'CONSTANT'::text, 'WINDOW_START'::text, 'WINDOW_END'::text, 'EACH_POINTER'::text, 'ARRAY_LENGTH'::text]))),
    ADD CONSTRAINT normalization_field_source_shape_ck CHECK ((((source_kind = ANY (ARRAY['POINTER'::text, 'PARENT_POINTER'::text])) AND (source_pointer IS NOT NULL) AND (constant_value IS NULL) AND (element_pointer IS NULL))
        OR ((source_kind = ANY (ARRAY['OBSERVATION_TIME'::text, 'WINDOW_START'::text, 'WINDOW_END'::text])) AND (source_pointer IS NULL) AND (constant_value IS NULL) AND (value_map IS NULL) AND (element_pointer IS NULL))
        OR ((source_kind = 'CONSTANT'::text) AND (source_pointer IS NULL) AND (constant_value IS NOT NULL) AND (length(constant_value) >= 1) AND (length(constant_value) <= 256) AND (value_map IS NULL) AND (element_pointer IS NULL))
        OR ((source_kind = 'EACH_POINTER'::text) AND (source_pointer IS NOT NULL) AND (element_pointer IS NOT NULL) AND (constant_value IS NULL) AND (value_map IS NULL))
        OR ((source_kind = 'ARRAY_LENGTH'::text) AND (source_pointer IS NOT NULL) AND (constant_value IS NULL) AND (value_map IS NULL) AND (element_pointer IS NULL)))),
    ADD CONSTRAINT normalization_field_element_pointer_ck CHECK (((element_pointer IS NULL) OR (element_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$'::text)));

ALTER TABLE staging.canonical_field
    ADD COLUMN repeated boolean DEFAULT false NOT NULL;

ALTER TABLE staging.canonical_field
    DROP CONSTRAINT canonical_field_dataset_ck;

ALTER TABLE staging.canonical_field
    ADD CONSTRAINT canonical_field_dataset_ck CHECK ((dataset_kind = ANY (ARRAY['LISTING'::text, 'LISTING_HEALTH'::text, 'PRICE'::text, 'STOCK'::text, 'TRAFFIC'::text, 'SALES'::text, 'RETURNS'::text, 'FINANCE'::text, 'ADVERTISING'::text, 'LISTING_CONTENT'::text, 'LISTING_SEARCH'::text, 'LISTING_SEARCH_TERM'::text, 'LISTING_ATTRIBUTE'::text, 'LISTING_CONTENT_GROUP'::text])));

-- Per listing and catalog snapshot: category, product type, image count and the attribute keys
-- the card carried.
CREATE TABLE core.listing_catalog_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    description_category_key text,
    type_key text,
    image_count integer,
    attribute_keys text[] DEFAULT '{}'::text[] NOT NULL,
    CONSTRAINT listing_catalog_observation_category_ck CHECK (((description_category_key IS NULL) OR ((length(description_category_key) >= 1) AND (length(description_category_key) <= 64)))),
    CONSTRAINT listing_catalog_observation_type_ck CHECK (((type_key IS NULL) OR ((length(type_key) >= 1) AND (length(type_key) <= 64)))),
    CONSTRAINT listing_catalog_observation_images_ck CHECK (((image_count IS NULL) OR (image_count >= 0))),
    CONSTRAINT listing_catalog_observation_keys_ck CHECK ((cardinality(attribute_keys) <= 1000))
);

ALTER TABLE ONLY core.listing_catalog_observation
    ADD CONSTRAINT listing_catalog_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_catalog_observation
    ADD CONSTRAINT listing_catalog_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.listing_catalog_observation
    ADD CONSTRAINT listing_catalog_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_catalog_observation
    ADD CONSTRAINT listing_catalog_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE INDEX listing_catalog_observation_variant_ix ON core.listing_catalog_observation USING btree (platform_listing_variant_id, observed_at DESC);

-- One attribute of a listing, written when its values change. value_texts is empty when the
-- values are too long to keep (values_length still says how long they were).
CREATE TABLE core.listing_attribute_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    attribute_key text NOT NULL,
    content_role text,
    value_texts text[] NOT NULL,
    value_count integer NOT NULL,
    values_length integer NOT NULL,
    values_digest text NOT NULL,
    CONSTRAINT listing_attribute_observation_key_ck CHECK (((length(attribute_key) >= 1) AND (length(attribute_key) <= 64))),
    CONSTRAINT listing_attribute_observation_role_ck CHECK (((content_role IS NULL) OR (content_role = ANY (ARRAY['DESCRIPTION'::text, 'RICH_CONTENT'::text])))),
    CONSTRAINT listing_attribute_observation_values_ck CHECK (((value_count >= 0) AND (value_count <= 1000) AND (values_length >= 0) AND (cardinality(value_texts) <= value_count))),
    CONSTRAINT listing_attribute_observation_digest_ck CHECK ((values_digest ~ '^[0-9a-f]{64}$'::text))
);

ALTER TABLE ONLY core.listing_attribute_observation
    ADD CONSTRAINT listing_attribute_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_attribute_observation
    ADD CONSTRAINT listing_attribute_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.listing_attribute_observation
    ADD CONSTRAINT listing_attribute_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_attribute_observation
    ADD CONSTRAINT listing_attribute_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE INDEX listing_attribute_observation_variant_ix ON core.listing_attribute_observation USING btree (platform_listing_variant_id, attribute_key, observed_at DESC);

-- One group of a listing's content rating, written when it changes.
CREATE TABLE core.listing_content_group_observation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provenance_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    source_fact_key text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    group_key text NOT NULL,
    group_name text,
    group_rating numeric(7,4),
    group_weight numeric(7,4),
    improve_at_least integer,
    condition_keys text[] NOT NULL,
    condition_texts text[] NOT NULL,
    condition_met boolean[] NOT NULL,
    condition_points numeric(9,4)[] NOT NULL,
    improve_attribute_keys text[] NOT NULL,
    improve_attribute_names text[] NOT NULL,
    content_digest text NOT NULL,
    CONSTRAINT listing_content_group_observation_key_ck CHECK (((length(group_key) >= 1) AND (length(group_key) <= 64))),
    CONSTRAINT listing_content_group_observation_name_ck CHECK (((group_name IS NULL) OR (length(group_name) <= 256))),
    CONSTRAINT listing_content_group_observation_rating_ck CHECK (((group_rating IS NULL) OR ((group_rating >= (0)::numeric) AND (group_rating <= (100)::numeric)))),
    CONSTRAINT listing_content_group_observation_weight_ck CHECK (((group_weight IS NULL) OR ((group_weight >= (0)::numeric) AND (group_weight <= (100)::numeric)))),
    CONSTRAINT listing_content_group_observation_improve_ck CHECK (((improve_at_least IS NULL) OR (improve_at_least >= 0))),
    CONSTRAINT listing_content_group_observation_conditions_ck CHECK (((cardinality(condition_keys) <= 100) AND (cardinality(condition_texts) = cardinality(condition_keys)) AND (cardinality(condition_met) = cardinality(condition_keys)) AND (cardinality(condition_points) = cardinality(condition_keys)))),
    CONSTRAINT listing_content_group_observation_attributes_ck CHECK (((cardinality(improve_attribute_keys) <= 200) AND (cardinality(improve_attribute_names) = cardinality(improve_attribute_keys)))),
    CONSTRAINT listing_content_group_observation_digest_ck CHECK ((content_digest ~ '^[0-9a-f]{64}$'::text))
);

ALTER TABLE ONLY core.listing_content_group_observation
    ADD CONSTRAINT listing_content_group_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_content_group_observation
    ADD CONSTRAINT listing_content_group_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.listing_content_group_observation
    ADD CONSTRAINT listing_content_group_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_content_group_observation
    ADD CONSTRAINT listing_content_group_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

CREATE INDEX listing_content_group_observation_variant_ix ON core.listing_content_group_observation USING btree (platform_listing_variant_id, group_key, observed_at DESC);

GRANT SELECT,INSERT ON TABLE core.listing_catalog_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.listing_attribute_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.listing_content_group_observation TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'listing_catalog_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it'),
    ('core', 'listing_attribute_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it'),
    ('core', 'listing_content_group_observation', 'NO_ROUTE', NULL, 'append-only observation produced by acquisition, never read by it');

-- The catalog snapshot fields a listing record may also carry (optional: a declaration without
-- observedAt records identity only, as before).
INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal, repeated) VALUES
    ('LISTING', 'observedAt', 'INSTANT', 'OPTIONAL', 'When the catalog answer was true; with it, the listing''s content snapshot is recorded.', 20, false),
    ('LISTING', 'descriptionCategoryKey', 'TEXT', 'OPTIONAL', 'The marketplace identifier of the listing''s description category.', 21, false),
    ('LISTING', 'typeKey', 'TEXT', 'OPTIONAL', 'The marketplace identifier of the listing''s product type.', 22, false),
    ('LISTING', 'imageCount', 'INTEGER', 'OPTIONAL', 'How many images the listing card shows.', 23, false),
    ('LISTING', 'attributeKeys', 'TEXT', 'OPTIONAL', 'The identifiers of the attributes the listing card carries.', 24, true),
    ('LISTING_ATTRIBUTE', 'nativeListingKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing.', 1, false),
    ('LISTING_ATTRIBUTE', 'nativeVariantKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing variant.', 2, false),
    ('LISTING_ATTRIBUTE', 'observedAt', 'INSTANT', 'REQUIRED', 'When the catalog answer was true.', 3, false),
    ('LISTING_ATTRIBUTE', 'attributeKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the attribute.', 4, false),
    ('LISTING_ATTRIBUTE', 'attributeValues', 'TEXT', 'OPTIONAL', 'Every value of the attribute, as the seller entered or chose it.', 5, true),
    ('LISTING_ATTRIBUTE', 'contentRole', 'TEXT', 'OPTIONAL', 'DESCRIPTION or RICH_CONTENT when the attribute holds the listing''s description or rich content.', 6, false),
    ('LISTING_CONTENT_GROUP', 'nativeListingKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing.', 1, false),
    ('LISTING_CONTENT_GROUP', 'nativeVariantKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the listing variant.', 2, false),
    ('LISTING_CONTENT_GROUP', 'observedAt', 'INSTANT', 'REQUIRED', 'When the marketplace considered this rating true.', 3, false),
    ('LISTING_CONTENT_GROUP', 'groupKey', 'TEXT', 'REQUIRED', 'The marketplace identifier of the rating group.', 4, false),
    ('LISTING_CONTENT_GROUP', 'groupName', 'TEXT', 'OPTIONAL', 'The marketplace name of the rating group.', 5, false),
    ('LISTING_CONTENT_GROUP', 'groupRating', 'DECIMAL', 'OPTIONAL', 'The group''s rating, 0 to 100.', 6, false),
    ('LISTING_CONTENT_GROUP', 'groupWeight', 'DECIMAL', 'OPTIONAL', 'The group''s share of the content rating, in percent.', 7, false),
    ('LISTING_CONTENT_GROUP', 'improveAtLeast', 'INTEGER', 'OPTIONAL', 'How many of the named attributes to fill at least to raise the group.', 8, false),
    ('LISTING_CONTENT_GROUP', 'conditionKeys', 'TEXT', 'OPTIONAL', 'The marketplace identifier of every condition of the group.', 9, true),
    ('LISTING_CONTENT_GROUP', 'conditionTexts', 'TEXT', 'OPTIONAL', 'The marketplace''s description of every condition.', 10, true),
    ('LISTING_CONTENT_GROUP', 'conditionMet', 'BOOLEAN', 'OPTIONAL', 'Whether every condition is fulfilled.', 11, true),
    ('LISTING_CONTENT_GROUP', 'conditionPoints', 'DECIMAL', 'OPTIONAL', 'What every condition contributes to the group.', 12, true),
    ('LISTING_CONTENT_GROUP', 'improveAttributeKeys', 'TEXT', 'OPTIONAL', 'The marketplace identifiers of the attributes to fill to raise the group.', 13, true),
    ('LISTING_CONTENT_GROUP', 'improveAttributeNames', 'TEXT', 'OPTIONAL', 'The marketplace names of those attributes.', 14, true),
    ('LISTING_CONTENT_GROUP', 'nativeItemKey', 'TEXT', 'OPTIONAL', 'The marketplace item identifier, when the source names the variant by it instead of the listing and variant keys.', 15, false);

-- The content drafts of the store diagnosis drawer: what the card says and what the rating finds
-- missing, the listing's content and search values and findings, and its top search terms.
INSERT INTO ops.ai_projection_definition (projection_code, projection_version, purpose, retention_policy, owner_label, status) VALUES
    ('LISTING_CONTENT_DRAFT', 1, 'Russian title, description and attribute drafts for human review of one listing variant, from its card content, content rating groups, content and search values and findings, and top search terms; no cost or profit data.', 'NO_PROVIDER_RETENTION', 'aicopilot', 'ACTIVE');

INSERT INTO ops.ai_projection_field (projection_code, projection_version, field_path, data_classification) VALUES
    ('LISTING_CONTENT_DRAFT', 1, 'subject.subjectRef', 'OPAQUE_IDENTIFIER'),
    ('LISTING_CONTENT_DRAFT', 1, 'subject.storeRef', 'OPAQUE_IDENTIFIER'),
    ('LISTING_CONTENT_DRAFT', 1, 'subject.platformCode', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'subject.title', 'MARKETPLACE_TEXT'),
    ('LISTING_CONTENT_DRAFT', 1, 'subject.size', 'MARKETPLACE_TEXT'),
    ('LISTING_CONTENT_DRAFT', 1, 'subject.color', 'MARKETPLACE_TEXT'),
    ('LISTING_CONTENT_DRAFT', 1, 'window.windowCode', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'window.periodStart', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'window.periodEnd', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'content.titleLength', 'DERIVED_VALUE'),
    ('LISTING_CONTENT_DRAFT', 1, 'content.descriptionText', 'MARKETPLACE_TEXT'),
    ('LISTING_CONTENT_DRAFT', 1, 'content.descriptionLength', 'DERIVED_VALUE'),
    ('LISTING_CONTENT_DRAFT', 1, 'content.richContent', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'content.imageCount', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'content.attributeCount', 'DERIVED_VALUE'),
    ('LISTING_CONTENT_DRAFT', 1, 'rating.groupKey', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'rating.groupRating', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'rating.groupWeight', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'rating.conditionKey', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'rating.conditionText', 'MARKETPLACE_TEXT'),
    ('LISTING_CONTENT_DRAFT', 1, 'rating.conditionMet', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'rating.conditionPoints', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'rating.improveAtLeast', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'rating.improveAttributeName', 'MARKETPLACE_TEXT'),
    ('LISTING_CONTENT_DRAFT', 1, 'metrics.metricCode', 'CANONICAL_METRIC'),
    ('LISTING_CONTENT_DRAFT', 1, 'metrics.valueRef', 'OPAQUE_IDENTIFIER'),
    ('LISTING_CONTENT_DRAFT', 1, 'metrics.displayValue', 'CANONICAL_METRIC'),
    ('LISTING_CONTENT_DRAFT', 1, 'findings.findingRef', 'OPAQUE_IDENTIFIER'),
    ('LISTING_CONTENT_DRAFT', 1, 'findings.ruleCode', 'DETERMINISTIC_FINDING'),
    ('LISTING_CONTENT_DRAFT', 1, 'findings.detailKey', 'DETERMINISTIC_FINDING'),
    ('LISTING_CONTENT_DRAFT', 1, 'findings.detailValue', 'DETERMINISTIC_FINDING'),
    ('LISTING_CONTENT_DRAFT', 1, 'search.periodStart', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'search.periodEnd', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'search.lastDay', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'searchTerms.term', 'MARKETPLACE_TEXT'),
    ('LISTING_CONTENT_DRAFT', 1, 'searchTerms.searchUsers', 'OPERATING_ATTRIBUTE'),
    ('LISTING_CONTENT_DRAFT', 1, 'searchTerms.orderedUnits', 'OPERATING_ATTRIBUTE');

-- Two ways a content draft is refused: it fills an attribute the rating did not name, or it
-- recommends something other than a content review.
ALTER TABLE ops.ai_output_claim DROP CONSTRAINT ai_output_claim_rejection_values_ck;
ALTER TABLE ops.ai_output_claim ADD CONSTRAINT ai_output_claim_rejection_values_ck
    CHECK (((rejection_code IS NULL) OR (rejection_code = ANY (ARRAY['SCHEMA_INVALID'::text, 'UNKNOWN_FIELD'::text, 'EVIDENCE_REFERENCE_UNRESOLVED'::text, 'EVIDENCE_REFERENCE_MISSING'::text, 'METRIC_NOT_RECOGNISED'::text, 'DERIVED_CALCULATION_NOT_PRODUCTIZED'::text, 'CAPABILITY_NOT_RECOGNISED'::text, 'STATEMENT_TOO_LONG'::text, 'INSTRUCTION_LIKE_CONTENT'::text, 'SECRET_LIKE_CONTENT'::text, 'LISTING_ASSISTANCE_ACTION_OUT_OF_SCOPE'::text, 'DRAFT_ATTRIBUTE_NOT_NAMED'::text, 'CONTENT_DRAFT_ACTION_OUT_OF_SCOPE'::text]))));
