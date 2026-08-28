-- Product and listing identity: the internal product master and its variants,
-- the platform listings observed on each store, and the reviewed mapping that
-- joins the two without either side losing its own identity.
--
-- The structural decision is that platform identity is never overwritten by
-- internal identity. A platform listing variant keeps the marketplace's own
-- keys verbatim, an internal variant keeps the company's own code, and the
-- relationship between them is a separate, effective-dated, reviewable fact.
-- Anything else makes a mapping mistake indistinguishable from a data change.
--
-- One internal variant may serve many platform listing variants. The reverse is
-- forbidden while a mapping is active: a platform listing variant that pointed
-- at two internal variants would make cost, and therefore profit, ambiguous at
-- exactly the moment a price decision needs it.

-- ---------------------------------------------------------------------------
-- Internal product master
-- ---------------------------------------------------------------------------

CREATE TABLE core.product (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    code            text        NOT NULL,
    display_name    text        NOT NULL,
    brand_label     text,
    category_label  text,
    status          text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT product_pk PRIMARY KEY (id),
    CONSTRAINT product_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT product_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT product_code_uq UNIQUE (organization_id, code),
    CONSTRAINT product_code_ck CHECK (code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    CONSTRAINT product_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE INDEX product_organization_ix ON core.product (organization_id, status);

-- The sellable internal unit. Colour and size are recorded labels rather than
-- coded dimensions: the pilot cohort's vocabulary is the company's own, and
-- coercing it into a fixed taxonomy would lose the distinction the operator
-- actually uses when reading a diagnosis.
CREATE TABLE core.product_variant (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    product_id      uuid        NOT NULL,
    sku_code        text        NOT NULL,
    display_name    text        NOT NULL,
    color_label     text,
    size_label      text,
    status          text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT product_variant_pk PRIMARY KEY (id),
    CONSTRAINT product_variant_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT product_variant_product_fk
        FOREIGN KEY (product_id, organization_id)
        REFERENCES core.product (id, organization_id),
    CONSTRAINT product_variant_sku_uq UNIQUE (organization_id, sku_code),
    CONSTRAINT product_variant_sku_ck
        CHECK (sku_code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    CONSTRAINT product_variant_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE INDEX product_variant_product_ix ON core.product_variant (product_id, status);
CREATE INDEX product_variant_organization_ix ON core.product_variant (organization_id, status);

-- Barcodes attached to an internal variant. A barcode is a strong mapping
-- signal, so a live duplicate across two variants is refused here: allowing it
-- would silently make automatic mapping pick one of two products.
CREATE TABLE core.product_barcode (
    id                 uuid        NOT NULL,
    organization_id    uuid        NOT NULL,
    product_variant_id uuid        NOT NULL,
    barcode_type       text        NOT NULL,
    barcode_value      text        NOT NULL,
    status             text        NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT product_barcode_pk PRIMARY KEY (id),
    CONSTRAINT product_barcode_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT product_barcode_type_ck
        CHECK (barcode_type IN ('EAN13', 'EAN8', 'UPC', 'ITF14', 'INTERNAL', 'UNKNOWN')),
    CONSTRAINT product_barcode_value_ck
        CHECK (barcode_value ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT product_barcode_status_ck CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX product_barcode_live_uq
    ON core.product_barcode (organization_id, barcode_value)
    WHERE status = 'ACTIVE';

CREATE INDEX product_barcode_variant_ix ON core.product_barcode (product_variant_id, status);

-- ---------------------------------------------------------------------------
-- Platform listing identity
-- ---------------------------------------------------------------------------

-- What one marketplace publishes on one store. The account and platform are
-- carried on the row and pinned by composite foreign keys, so a listing can
-- never disagree with the store it belongs to about which platform it is on.
CREATE TABLE core.platform_listing (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    store_id               uuid        NOT NULL,
    marketplace_account_id uuid        NOT NULL,
    platform_code          text        NOT NULL,
    native_listing_key     text        NOT NULL,
    native_product_key     text,
    title                  text,
    native_status          text,
    first_seen_at          timestamptz NOT NULL,
    last_seen_at           timestamptz NOT NULL,
    status                 text        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT platform_listing_pk PRIMARY KEY (id),
    CONSTRAINT platform_listing_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT platform_listing_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT platform_listing_store_account_fk
        FOREIGN KEY (store_id, marketplace_account_id)
        REFERENCES core.store (id, marketplace_account_id),
    CONSTRAINT platform_listing_account_platform_fk
        FOREIGN KEY (marketplace_account_id, platform_code)
        REFERENCES core.marketplace_account (id, platform_code),
    CONSTRAINT platform_listing_native_key_uq UNIQUE (store_id, native_listing_key),
    -- Marketplace keys are opaque. The only rule applied to them is that they
    -- are bounded and printable, because coercing them further would be a guess
    -- about a vocabulary this system does not own.
    CONSTRAINT platform_listing_native_key_ck
        CHECK (length(btrim(native_listing_key)) BETWEEN 1 AND 128),
    CONSTRAINT platform_listing_native_product_key_ck
        CHECK (native_product_key IS NULL
            OR length(btrim(native_product_key)) BETWEEN 1 AND 128),
    CONSTRAINT platform_listing_seen_ck CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT platform_listing_status_ck
        CHECK (status IN ('OBSERVED', 'ARCHIVED'))
);

CREATE INDEX platform_listing_store_ix ON core.platform_listing (store_id, status);
CREATE INDEX platform_listing_account_ix ON core.platform_listing (marketplace_account_id);

-- The variant level of a platform listing, which is what a price and a stock
-- figure actually attach to. native_status keeps the marketplace's own word for
-- the state; an unrecognised value stays unrecognised.
CREATE TABLE core.platform_listing_variant (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    platform_listing_id uuid        NOT NULL,
    native_variant_key  text        NOT NULL,
    native_sku_key      text,
    native_barcode      text,
    native_color_label  text,
    native_size_label   text,
    native_status       text,
    first_seen_at       timestamptz NOT NULL,
    last_seen_at        timestamptz NOT NULL,
    status              text        NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT platform_listing_variant_pk PRIMARY KEY (id),
    CONSTRAINT platform_listing_variant_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT platform_listing_variant_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT platform_listing_variant_native_key_uq
        UNIQUE (platform_listing_id, native_variant_key),
    CONSTRAINT platform_listing_variant_native_key_ck
        CHECK (length(btrim(native_variant_key)) BETWEEN 1 AND 128),
    CONSTRAINT platform_listing_variant_native_sku_ck
        CHECK (native_sku_key IS NULL OR length(btrim(native_sku_key)) BETWEEN 1 AND 128),
    CONSTRAINT platform_listing_variant_barcode_ck
        CHECK (native_barcode IS NULL OR length(btrim(native_barcode)) BETWEEN 1 AND 64),
    CONSTRAINT platform_listing_variant_seen_ck CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT platform_listing_variant_status_ck CHECK (status IN ('OBSERVED', 'ARCHIVED'))
);

CREATE INDEX platform_listing_variant_listing_ix
    ON core.platform_listing_variant (platform_listing_id, status);
CREATE INDEX platform_listing_variant_barcode_ix
    ON core.platform_listing_variant (organization_id, native_barcode)
    WHERE native_barcode IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Mapping candidates, confirmed mapping and conflicts
-- ---------------------------------------------------------------------------

-- A proposal that one platform listing variant is one internal variant, with
-- the method that produced it and the confidence that method carries. A
-- proposal never takes effect on its own; confirmation writes a mapping row.
CREATE TABLE core.listing_mapping_candidate (
    id                         uuid          NOT NULL,
    organization_id            uuid          NOT NULL,
    platform_listing_variant_id uuid         NOT NULL,
    product_variant_id         uuid          NOT NULL,
    match_method               text          NOT NULL,
    confidence                 numeric(5, 4) NOT NULL,
    evidence_note              text,
    state                      text          NOT NULL,
    decided_by_user_id         uuid,
    decided_at                 timestamptz,
    decision_reason            text,
    created_at                 timestamptz   NOT NULL,
    updated_at                 timestamptz   NOT NULL,
    version                    bigint        NOT NULL DEFAULT 0,
    CONSTRAINT listing_mapping_candidate_pk PRIMARY KEY (id),
    CONSTRAINT listing_mapping_candidate_listing_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT listing_mapping_candidate_product_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT listing_mapping_candidate_user_fk
        FOREIGN KEY (decided_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT listing_mapping_candidate_method_ck
        CHECK (match_method IN
            ('BARCODE', 'NATIVE_SKU_KEY', 'NORMALIZED_TITLE', 'MANUAL', 'IMPORTED')),
    CONSTRAINT listing_mapping_candidate_confidence_ck
        CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT listing_mapping_candidate_state_ck
        CHECK (state IN ('PROPOSED', 'CONFIRMED', 'REJECTED', 'SUPERSEDED')),
    -- A decision is a person, a time and a reason together. Half a decision
    -- describes an unattributable change to a mapping that gates money.
    CONSTRAINT listing_mapping_candidate_decision_ck
        CHECK (state = 'PROPOSED'
            OR (decided_by_user_id IS NOT NULL
                AND decided_at IS NOT NULL
                AND decision_reason IS NOT NULL))
);

-- One live proposal per listing variant and internal variant. Re-proposing the
-- same pair after a rejection is a new row, and the rejection stays readable.
CREATE UNIQUE INDEX listing_mapping_candidate_open_uq
    ON core.listing_mapping_candidate (platform_listing_variant_id, product_variant_id)
    WHERE state = 'PROPOSED';

CREATE INDEX listing_mapping_candidate_queue_ix
    ON core.listing_mapping_candidate (organization_id, state, confidence DESC);
CREATE INDEX listing_mapping_candidate_product_ix
    ON core.listing_mapping_candidate (product_variant_id, state);

-- The confirmed relationship over a half-open interval. The exclusion
-- constraint is the invariant that keeps profit unambiguous: at any instant a
-- platform listing variant resolves to at most one internal variant.
CREATE TABLE core.listing_mapping (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    platform_listing_variant_id uuid        NOT NULL,
    product_variant_id          uuid        NOT NULL,
    source_candidate_id         uuid,
    effective_from              timestamptz NOT NULL,
    effective_to                timestamptz,
    status                      text        NOT NULL,
    confirmed_by_user_id        uuid        NOT NULL,
    reason                      text        NOT NULL,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    version                     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT listing_mapping_pk PRIMARY KEY (id),
    CONSTRAINT listing_mapping_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT listing_mapping_listing_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT listing_mapping_product_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT listing_mapping_candidate_fk
        FOREIGN KEY (source_candidate_id) REFERENCES core.listing_mapping_candidate (id),
    CONSTRAINT listing_mapping_user_fk
        FOREIGN KEY (confirmed_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT listing_mapping_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT listing_mapping_status_ck CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    CONSTRAINT listing_mapping_no_overlap
        EXCLUDE USING gist (
            platform_listing_variant_id WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

CREATE INDEX listing_mapping_product_ix
    ON core.listing_mapping (product_variant_id, status, effective_from);
CREATE INDEX listing_mapping_listing_ix
    ON core.listing_mapping (platform_listing_variant_id, status);

-- Work that a person must resolve before the affected listing variant can carry
-- precise cost, precise profit or any platform write. The queue is a table
-- rather than a derived query so an operator's decision, and the moment they
-- made it, are recorded facts.
CREATE TABLE core.mapping_conflict (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    platform_listing_variant_id uuid        NOT NULL,
    conflict_kind               text        NOT NULL,
    detail                      text        NOT NULL,
    state                       text        NOT NULL,
    detected_at                 timestamptz NOT NULL,
    resolved_by_user_id         uuid,
    resolved_at                 timestamptz,
    resolution_reason           text,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    version                     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT mapping_conflict_pk PRIMARY KEY (id),
    CONSTRAINT mapping_conflict_listing_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT mapping_conflict_user_fk
        FOREIGN KEY (resolved_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT mapping_conflict_kind_ck
        CHECK (conflict_kind IN (
            'NO_CANDIDATE', 'MULTIPLE_CANDIDATES', 'DUPLICATE_BARCODE',
            'CONFLICTING_CONFIRMATION', 'ARCHIVED_INTERNAL_VARIANT')),
    CONSTRAINT mapping_conflict_state_ck CHECK (state IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT mapping_conflict_resolution_ck
        CHECK (state = 'OPEN'
            OR (resolved_by_user_id IS NOT NULL
                AND resolved_at IS NOT NULL
                AND resolution_reason IS NOT NULL))
);

-- One open conflict of a kind per listing variant. Repeated detection updates
-- the existing row rather than growing an unbounded queue of the same problem.
CREATE UNIQUE INDEX mapping_conflict_open_uq
    ON core.mapping_conflict (platform_listing_variant_id, conflict_kind)
    WHERE state = 'OPEN';

CREATE INDEX mapping_conflict_queue_ix
    ON core.mapping_conflict (organization_id, state, detected_at DESC);

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Product and listing identity is consumed by diagnosis and by the write gate,
-- neither of which is the acquisition call authority. No evaluation inside
-- platform.grant_call_authority reads any table below, so routing them would
-- invalidate in-flight acquisitions for changes that cannot affect one.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'product', 'NO_ROUTE', NULL,
        'internal product master; no acquisition authority reads it'),
    ('core', 'product_variant', 'NO_ROUTE', NULL,
        'internal variant identity; no acquisition authority reads it'),
    ('core', 'product_barcode', 'NO_ROUTE', NULL,
        'mapping signal; no acquisition authority reads it'),
    ('core', 'platform_listing', 'NO_ROUTE', NULL,
        'observed platform identity; a normalization result, not a control fact'),
    ('core', 'platform_listing_variant', 'NO_ROUTE', NULL,
        'observed platform identity; a normalization result, not a control fact'),
    ('core', 'listing_mapping_candidate', 'NO_ROUTE', NULL,
        'mapping proposal reviewed by a person; no acquisition authority reads it'),
    ('core', 'listing_mapping', 'NO_ROUTE', NULL,
        'confirmed mapping; consumed by the write gate, not by call authority'),
    ('core', 'mapping_conflict', 'NO_ROUTE', NULL,
        'operator work queue; consumed by the write gate, not by call authority');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Every table accepts inserts and versioned updates and nothing accepts DELETE.
-- Retirement, rejection, ending an interval and dismissing a conflict are all
-- recorded transitions, which is what makes the mapping history readable after
-- a decision turns out to have been wrong.
GRANT SELECT, INSERT, UPDATE ON core.product TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.product_variant TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.product_barcode TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.platform_listing TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.platform_listing_variant TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.listing_mapping_candidate TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.listing_mapping TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.mapping_conflict TO marketops_app;
