-- How stored Raw bytes become canonical facts, declared as evidence rather than
-- written into code, and the record of every field a source sent that the
-- declaration does not name.
--
-- A payload's shape is the marketplace's fact. Encoding it in a parser would put
-- a guess in a release and would make a published change a code change; encoding
-- it here means somebody records what the current documentation and a real
-- response actually contain, with a last-verified date, and the normalizer
-- refuses to read anything that has not been recorded that way.
--
-- The declaration also gives schema drift a definition. A field the source sends
-- that no declaration names is not silently dropped: it is recorded as drift,
-- with the pointer that produced it, so a platform change surfaces as an
-- operator queue rather than as a metric that quietly stops moving.
--
-- Canonical field names are seeded reference data. A declaration cannot name a
-- field the normalizer does not know how to write, so a mapping mistake is a
-- foreign-key violation at registration time rather than a null in a profit
-- calculation later.

-- ---------------------------------------------------------------------------
-- Canonical field vocabulary
-- ---------------------------------------------------------------------------

CREATE TABLE staging.canonical_field (
    dataset_kind   text    NOT NULL,
    field_name     text    NOT NULL,
    value_kind     text    NOT NULL,
    requirement    text    NOT NULL,
    description    text    NOT NULL,
    ordinal        integer NOT NULL,
    CONSTRAINT canonical_field_pk PRIMARY KEY (dataset_kind, field_name),
    CONSTRAINT canonical_field_dataset_ck
        CHECK (dataset_kind IN (
            'LISTING', 'LISTING_HEALTH', 'PRICE', 'STOCK', 'TRAFFIC',
            'SALES', 'RETURNS', 'FINANCE', 'ADVERTISING')),
    CONSTRAINT canonical_field_name_ck
        CHECK (field_name ~ '^[a-z][a-zA-Z0-9]{0,63}$'),
    CONSTRAINT canonical_field_value_kind_ck
        CHECK (value_kind IN ('TEXT', 'INTEGER', 'DECIMAL', 'INSTANT', 'BOOLEAN')),
    CONSTRAINT canonical_field_requirement_ck
        CHECK (requirement IN ('REQUIRED', 'OPTIONAL')),
    CONSTRAINT canonical_field_ordinal_uq UNIQUE (dataset_kind, ordinal)
);

INSERT INTO staging.canonical_field
    (dataset_kind, field_name, value_kind, requirement, description, ordinal)
SELECT dataset_kind, field_name, value_kind, requirement, description, ordinal
  FROM (VALUES
    ('LISTING', 'nativeListingKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing.', 1),
    ('LISTING', 'nativeVariantKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing variant.', 2),
    ('LISTING', 'nativeProductKey', 'TEXT', 'OPTIONAL',
        'The marketplace product identifier above the listing.', 3),
    ('LISTING', 'title', 'TEXT', 'OPTIONAL', 'The published title.', 4),
    ('LISTING', 'nativeSkuKey', 'TEXT', 'OPTIONAL',
        'The seller stock-keeping unit the marketplace holds.', 5),
    ('LISTING', 'nativeBarcode', 'TEXT', 'OPTIONAL',
        'The barcode the marketplace holds.', 6),
    ('LISTING', 'nativeColorLabel', 'TEXT', 'OPTIONAL',
        'Colour as the marketplace states it.', 7),
    ('LISTING', 'nativeSizeLabel', 'TEXT', 'OPTIONAL',
        'Size as the marketplace states it.', 8),
    ('LISTING', 'nativeStatus', 'TEXT', 'OPTIONAL',
        'The marketplace status word for the listing.', 9),

    ('LISTING_HEALTH', 'nativeListingKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing.', 1),
    ('LISTING_HEALTH', 'nativeVariantKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing variant.', 2),
    ('LISTING_HEALTH', 'observedAt', 'INSTANT', 'REQUIRED',
        'When the marketplace considered this state true.', 3),
    ('LISTING_HEALTH', 'nativeStatus', 'TEXT', 'OPTIONAL',
        'The marketplace status word.', 4),
    ('LISTING_HEALTH', 'sellable', 'BOOLEAN', 'OPTIONAL',
        'Whether the marketplace reports the variant as sellable.', 5),
    ('LISTING_HEALTH', 'blockedReasonNative', 'TEXT', 'OPTIONAL',
        'The marketplace reason for a blocked listing.', 6),

    ('PRICE', 'nativeListingKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing.', 1),
    ('PRICE', 'nativeVariantKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing variant.', 2),
    ('PRICE', 'observedAt', 'INSTANT', 'REQUIRED',
        'When the marketplace considered this price true.', 3),
    ('PRICE', 'currencyCode', 'TEXT', 'REQUIRED', 'Currency of the amounts.', 4),
    ('PRICE', 'listPrice', 'DECIMAL', 'OPTIONAL', 'Price before any discount.', 5),
    ('PRICE', 'sellingPrice', 'DECIMAL', 'OPTIONAL', 'Price a buyer currently pays.', 6),
    ('PRICE', 'discountPrice', 'DECIMAL', 'OPTIONAL', 'Discounted price when one applies.', 7),
    ('PRICE', 'promotionActive', 'BOOLEAN', 'OPTIONAL',
        'Whether a promotion is running.', 8),
    ('PRICE', 'nativePriceKind', 'TEXT', 'OPTIONAL',
        'The marketplace word for which price this is.', 9),

    ('STOCK', 'nativeListingKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing.', 1),
    ('STOCK', 'nativeVariantKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing variant.', 2),
    ('STOCK', 'observedAt', 'INSTANT', 'REQUIRED',
        'When the marketplace considered this quantity true.', 3),
    ('STOCK', 'fulfillmentModeCode', 'TEXT', 'REQUIRED',
        'Which fulfillment mode the quantity belongs to.', 4),
    ('STOCK', 'availableQuantity', 'INTEGER', 'OPTIONAL', 'Units available to sell.', 5),
    ('STOCK', 'reservedQuantity', 'INTEGER', 'OPTIONAL', 'Units reserved.', 6),
    ('STOCK', 'inboundQuantity', 'INTEGER', 'OPTIONAL', 'Units in transit.', 7),

    ('TRAFFIC', 'nativeListingKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing.', 1),
    ('TRAFFIC', 'nativeVariantKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing variant.', 2),
    ('TRAFFIC', 'periodStart', 'INSTANT', 'REQUIRED', 'Start of the reported period.', 3),
    ('TRAFFIC', 'periodEnd', 'INSTANT', 'REQUIRED', 'End of the reported period.', 4),
    ('TRAFFIC', 'impressions', 'INTEGER', 'OPTIONAL', 'Times the listing was shown.', 5),
    ('TRAFFIC', 'clicks', 'INTEGER', 'OPTIONAL', 'Clicks on the listing.', 6),
    ('TRAFFIC', 'visits', 'INTEGER', 'OPTIONAL', 'Visits to the listing.', 7),
    ('TRAFFIC', 'addToCart', 'INTEGER', 'OPTIONAL', 'Additions to a cart.', 8),
    ('TRAFFIC', 'orderedUnits', 'INTEGER', 'OPTIONAL', 'Units ordered in the period.', 9),

    ('SALES', 'nativeListingKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing.', 1),
    ('SALES', 'nativeVariantKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing variant.', 2),
    ('SALES', 'nativeOrderKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the order.', 3),
    ('SALES', 'occurredAt', 'INSTANT', 'REQUIRED', 'When the sale happened.', 4),
    ('SALES', 'quantity', 'INTEGER', 'REQUIRED', 'Units sold on this line.', 5),
    ('SALES', 'currencyCode', 'TEXT', 'REQUIRED', 'Currency of the amounts.', 6),
    ('SALES', 'grossAmount', 'DECIMAL', 'REQUIRED', 'Gross value of the line.', 7),
    ('SALES', 'netAmount', 'DECIMAL', 'REQUIRED', 'Net value of the line.', 8),
    ('SALES', 'nativeLineKey', 'TEXT', 'OPTIONAL',
        'The marketplace identifier of the order line.', 9),
    ('SALES', 'discountAmount', 'DECIMAL', 'OPTIONAL', 'Discount applied to the line.', 10),
    ('SALES', 'nativeStatus', 'TEXT', 'OPTIONAL', 'The marketplace status word.', 11),

    ('RETURNS', 'nativeListingKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing.', 1),
    ('RETURNS', 'nativeVariantKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing variant.', 2),
    ('RETURNS', 'nativeReturnKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the return.', 3),
    ('RETURNS', 'occurredAt', 'INSTANT', 'REQUIRED', 'When the return happened.', 4),
    ('RETURNS', 'quantity', 'INTEGER', 'REQUIRED', 'Units returned.', 5),
    ('RETURNS', 'currencyCode', 'TEXT', 'REQUIRED', 'Currency of the amounts.', 6),
    ('RETURNS', 'returnKind', 'TEXT', 'REQUIRED',
        'Cancellation, delivery refusal or post-delivery return.', 7),
    ('RETURNS', 'nativeOrderKey', 'TEXT', 'OPTIONAL',
        'The order the return belongs to.', 8),
    ('RETURNS', 'reasonNative', 'TEXT', 'OPTIONAL',
        'The marketplace reason text, kept verbatim.', 9),
    ('RETURNS', 'refundAmount', 'DECIMAL', 'OPTIONAL', 'Amount refunded.', 10),
    ('RETURNS', 'lossAmount', 'DECIMAL', 'OPTIONAL', 'Recorded loss on the return.', 11),

    ('FINANCE', 'nativeListingKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing.', 1),
    ('FINANCE', 'nativeVariantKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing variant.', 2),
    ('FINANCE', 'occurredAt', 'INSTANT', 'REQUIRED', 'When the charge was raised.', 3),
    ('FINANCE', 'currencyCode', 'TEXT', 'REQUIRED', 'Currency of the amount.', 4),
    ('FINANCE', 'amount', 'DECIMAL', 'REQUIRED', 'The charge.', 5),
    ('FINANCE', 'feeCategory', 'TEXT', 'REQUIRED',
        'Which internal fee category the charge belongs to.', 6),
    ('FINANCE', 'settlementState', 'TEXT', 'OPTIONAL',
        'Whether the charge is accrued or settled.', 7),
    ('FINANCE', 'nativeFeeCode', 'TEXT', 'OPTIONAL',
        'The marketplace code for the charge, kept verbatim.', 8),
    ('FINANCE', 'nativeOrderKey', 'TEXT', 'OPTIONAL',
        'The order the charge belongs to.', 9),

    ('ADVERTISING', 'nativeListingKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing.', 1),
    ('ADVERTISING', 'nativeVariantKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the listing variant.', 2),
    ('ADVERTISING', 'nativeCampaignKey', 'TEXT', 'REQUIRED',
        'The marketplace identifier of the campaign.', 3),
    ('ADVERTISING', 'periodStart', 'INSTANT', 'REQUIRED', 'Start of the reported period.', 4),
    ('ADVERTISING', 'periodEnd', 'INSTANT', 'REQUIRED', 'End of the reported period.', 5),
    ('ADVERTISING', 'currencyCode', 'TEXT', 'REQUIRED', 'Currency of the spend.', 6),
    ('ADVERTISING', 'spendAmount', 'DECIMAL', 'REQUIRED', 'Spend in the period.', 7),
    ('ADVERTISING', 'campaignKindNative', 'TEXT', 'OPTIONAL',
        'The marketplace campaign type, kept verbatim.', 8),
    ('ADVERTISING', 'impressions', 'INTEGER', 'OPTIONAL', 'Impressions bought.', 9),
    ('ADVERTISING', 'clicks', 'INTEGER', 'OPTIONAL', 'Clicks bought.', 10),
    ('ADVERTISING', 'attributedOrders', 'INTEGER', 'OPTIONAL',
        'Orders the marketplace attributes to the campaign.', 11),
    ('ADVERTISING', 'attributedRevenue', 'DECIMAL', 'OPTIONAL',
        'Revenue the marketplace attributes to the campaign.', 12)
  ) AS fields(dataset_kind, field_name, value_kind, requirement, description, ordinal);

-- ---------------------------------------------------------------------------
-- Declared mappings
-- ---------------------------------------------------------------------------

-- How one platform's payload for one dataset is read.
--
-- record_pointer names where the repeated records live inside the payload; the
-- field declarations below are read relative to each record. Both are JSON
-- Pointers, which is a published notation rather than an expression language, so
-- a declaration cannot compute or reach outside the document it is applied to.
CREATE TABLE staging.normalization_mapping (
    id                    uuid        NOT NULL,
    platform_code         text        NOT NULL,
    dataset_kind          text        NOT NULL,
    mapping_version       integer     NOT NULL,
    record_pointer        text        NOT NULL,
    verification_state    text        NOT NULL,
    last_verified_at      timestamptz,
    evidence_ref          text,
    verified_source_title text,
    owner_label           text        NOT NULL,
    status                text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT normalization_mapping_pk PRIMARY KEY (id),
    CONSTRAINT normalization_mapping_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT normalization_mapping_uq
        UNIQUE (platform_code, dataset_kind, mapping_version),
    CONSTRAINT normalization_mapping_dataset_ck
        CHECK (dataset_kind IN (
            'LISTING', 'LISTING_HEALTH', 'PRICE', 'STOCK', 'TRAFFIC',
            'SALES', 'RETURNS', 'FINANCE', 'ADVERTISING')),
    CONSTRAINT normalization_mapping_version_ck CHECK (mapping_version > 0),
    -- A JSON Pointer is a sequence of slash-prefixed reference tokens, or the
    -- empty string for the document root.
    CONSTRAINT normalization_mapping_record_pointer_ck
        CHECK (record_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)*$'),
    CONSTRAINT normalization_mapping_verification_ck
        CHECK (verification_state IN ('UNKNOWN', 'UNVERIFIED', 'VERIFIED')),
    CONSTRAINT normalization_mapping_provenance_ck
        CHECK (verification_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    CONSTRAINT normalization_mapping_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT normalization_mapping_active_readiness_ck
        CHECK (status <> 'ACTIVE' OR verification_state = 'VERIFIED')
);

CREATE UNIQUE INDEX normalization_mapping_live_uq
    ON staging.normalization_mapping (platform_code, dataset_kind)
    WHERE status = 'ACTIVE';

CREATE TABLE staging.normalization_field (
    mapping_id     uuid NOT NULL,
    dataset_kind   text NOT NULL,
    field_name     text NOT NULL,
    source_pointer text NOT NULL,
    CONSTRAINT normalization_field_pk PRIMARY KEY (mapping_id, field_name),
    CONSTRAINT normalization_field_mapping_fk
        FOREIGN KEY (mapping_id) REFERENCES staging.normalization_mapping (id),
    -- The canonical field must be one the normalizer knows how to write. A
    -- declaration naming anything else fails here rather than producing a null
    -- in a profit calculation later.
    CONSTRAINT normalization_field_canonical_fk
        FOREIGN KEY (dataset_kind, field_name)
        REFERENCES staging.canonical_field (dataset_kind, field_name),
    CONSTRAINT normalization_field_source_pointer_ck
        CHECK (source_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$')
);

CREATE INDEX normalization_field_mapping_ix
    ON staging.normalization_field (mapping_id, field_name);

-- ---------------------------------------------------------------------------
-- Normalization progress
-- ---------------------------------------------------------------------------

-- How far normalization has read one job's evidence.
--
-- The cursor is over evidence rather than over a source, which is what makes
-- reprocessing safe: moving it back re-reads bytes that are already in custody
-- and produces no marketplace traffic, and re-reading the same bytes writes no
-- duplicate fact because every fact is unique on the source's own key.
CREATE TABLE staging.normalization_checkpoint (
    job_id              uuid        NOT NULL,
    last_ingestion_time timestamptz,
    last_observation_id uuid,
    processed_count     bigint      NOT NULL DEFAULT 0,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT normalization_checkpoint_pk PRIMARY KEY (job_id),
    CONSTRAINT normalization_checkpoint_job_fk
        FOREIGN KEY (job_id) REFERENCES platform.ingestion_job (id),
    CONSTRAINT normalization_checkpoint_observation_fk
        FOREIGN KEY (last_observation_id)
        REFERENCES raw.raw_acquisition_observation (id),
    CONSTRAINT normalization_checkpoint_pairing_ck
        CHECK (num_nonnulls(last_ingestion_time, last_observation_id) <> 1),
    CONSTRAINT normalization_checkpoint_count_ck CHECK (processed_count >= 0)
);

-- ---------------------------------------------------------------------------
-- Schema drift
-- ---------------------------------------------------------------------------

-- A field a source sent that no declaration names.
--
-- Drift is recorded per pointer and counted rather than appended per
-- occurrence, so a platform that added one field produces one queue item
-- instead of one per record. The example observation is kept so somebody can
-- open the exact stored bytes that first showed the change.
CREATE TABLE staging.schema_drift_observation (
    id                   uuid        NOT NULL,
    job_id               uuid        NOT NULL,
    mapping_id           uuid        NOT NULL,
    unmapped_pointer     text        NOT NULL,
    first_observation_id uuid        NOT NULL,
    first_seen_at        timestamptz NOT NULL,
    last_seen_at         timestamptz NOT NULL,
    occurrence_count     bigint      NOT NULL,
    state                text        NOT NULL,
    acknowledged_by_user_id uuid,
    acknowledged_at      timestamptz,
    acknowledgement_note text,
    version              bigint      NOT NULL DEFAULT 0,
    CONSTRAINT schema_drift_observation_pk PRIMARY KEY (id),
    CONSTRAINT schema_drift_observation_job_fk
        FOREIGN KEY (job_id) REFERENCES platform.ingestion_job (id),
    CONSTRAINT schema_drift_observation_mapping_fk
        FOREIGN KEY (mapping_id) REFERENCES staging.normalization_mapping (id),
    CONSTRAINT schema_drift_observation_evidence_fk
        FOREIGN KEY (first_observation_id)
        REFERENCES raw.raw_acquisition_observation (id),
    CONSTRAINT schema_drift_observation_user_fk
        FOREIGN KEY (acknowledged_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT schema_drift_observation_state_ck
        CHECK (state IN ('OPEN', 'ACKNOWLEDGED')),
    CONSTRAINT schema_drift_observation_acknowledgement_ck
        CHECK ((state = 'ACKNOWLEDGED')
            = (acknowledged_by_user_id IS NOT NULL
               AND acknowledged_at IS NOT NULL
               AND acknowledgement_note IS NOT NULL)),
    CONSTRAINT schema_drift_observation_count_ck CHECK (occurrence_count > 0)
);

CREATE UNIQUE INDEX schema_drift_observation_open_uq
    ON staging.schema_drift_observation (job_id, mapping_id, unmapped_pointer)
    WHERE state = 'OPEN';

CREATE INDEX schema_drift_observation_queue_ix
    ON staging.schema_drift_observation (state, last_seen_at DESC);

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Normalization runs long after acquisition has finished and no call authority
-- reads any table below, so none of them routes an epoch. Routing them would
-- cancel a running acquisition every time its own output was processed.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('staging', 'canonical_field', 'NO_ROUTE', NULL,
        'normalization vocabulary; no acquisition authority reads it'),
    ('staging', 'normalization_mapping', 'NO_ROUTE', NULL,
        'payload declaration consumed after acquisition, never during it'),
    ('staging', 'normalization_field', 'NO_ROUTE', NULL,
        'payload declaration consumed after acquisition, never during it'),
    ('staging', 'normalization_checkpoint', 'NO_ROUTE', NULL,
        'progress over stored evidence; no acquisition authority reads it'),
    ('staging', 'schema_drift_observation', 'NO_ROUTE', NULL,
        'operator queue produced by normalization; not a control fact');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- The vocabulary is read-only: a running process cannot teach the normalizer a
-- field it has no code to write. Declarations accept evidence-carrying
-- maintenance, and the progress cursor and drift queue carry state.
GRANT SELECT ON staging.canonical_field TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON staging.normalization_mapping TO marketops_app;
GRANT SELECT, INSERT ON staging.normalization_field TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON staging.normalization_checkpoint TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON staging.schema_drift_observation TO marketops_app;
