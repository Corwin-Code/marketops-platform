-- Internal operating facts and the controlled path they arrive by: registered
-- file schema profiles, immutable import batches and their row-level outcomes,
-- versioned purchase cost, internal physical stock and the finance inputs the
-- profit definition needs but no marketplace publishes.
--
-- Two properties make this an intake product rather than a loading script.
--
-- The file is evidence. Its exact bytes go into the same content-addressed Raw
-- custody the acquisition path uses, and the batch names that content row. A
-- rejected import is therefore still fully reconstructable, and a dispute about
-- what was uploaded is answered from stored bytes rather than from memory.
--
-- Nothing is overwritten. Cost and finance inputs are effective-dated versions,
-- a re-upload of the same bytes is refused as a duplicate, and a superseding
-- batch ends the previous version's interval instead of editing it.

-- ---------------------------------------------------------------------------
-- Registered schema profiles
-- ---------------------------------------------------------------------------

-- The column contract one operator-owned file family is expected to satisfy.
-- Nothing is seeded: the real column names, owners and effective-date semantics
-- of the company's own files are recorded evidence, and inventing them here
-- would make an unvalidated guess look like an agreed contract. With no profile
-- registered, every import of that dataset is refused.
CREATE TABLE staging.import_schema_profile (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    dataset_kind     text        NOT NULL,
    profile_code     text        NOT NULL,
    profile_version  integer     NOT NULL,
    display_name     text        NOT NULL,
    column_contract  jsonb       NOT NULL,
    owner_label      text        NOT NULL,
    status           text        NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT import_schema_profile_pk PRIMARY KEY (id),
    CONSTRAINT import_schema_profile_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT import_schema_profile_code_uq
        UNIQUE (organization_id, dataset_kind, profile_code, profile_version),
    CONSTRAINT import_schema_profile_dataset_ck
        CHECK (dataset_kind IN ('PURCHASE_COST', 'INTERNAL_STOCK', 'FINANCE_INPUT')),
    CONSTRAINT import_schema_profile_code_ck
        CHECK (profile_code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    CONSTRAINT import_schema_profile_version_ck CHECK (profile_version > 0),
    -- The contract is a list of column declarations. An object or a scalar
    -- would not describe a file, and accepting one would defer the failure to
    -- the first upload rather than to the registration that caused it.
    CONSTRAINT import_schema_profile_contract_ck
        CHECK (jsonb_typeof(column_contract) = 'array' AND jsonb_array_length(column_contract) > 0),
    CONSTRAINT import_schema_profile_status_ck CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX import_schema_profile_live_uq
    ON staging.import_schema_profile (organization_id, dataset_kind, profile_code)
    WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Import batch and rows
-- ---------------------------------------------------------------------------

-- One submitted file and everything decided about it. content_id is the exact
-- bytes in Raw custody, which is what makes preview, replay and reconciliation
-- possible without keeping a second copy of the upload.
CREATE TABLE staging.import_batch (
    id                    uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    dataset_kind          text        NOT NULL,
    schema_profile_id     uuid        NOT NULL,
    content_id            uuid        NOT NULL,
    declared_file_name    text        NOT NULL,
    declared_media_type   text        NOT NULL,
    state                 text        NOT NULL,
    rejection_code        text,
    total_row_count       integer,
    accepted_row_count    integer,
    rejected_row_count    integer,
    source_time           timestamptz,
    effective_from        timestamptz,
    submitted_by_user_id  uuid        NOT NULL,
    submitted_at          timestamptz NOT NULL,
    approved_by_user_id   uuid,
    approved_at           timestamptz,
    applied_at            timestamptz,
    supersedes_batch_id   uuid,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT import_batch_pk PRIMARY KEY (id),
    CONSTRAINT import_batch_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT import_batch_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT import_batch_profile_fk
        FOREIGN KEY (schema_profile_id) REFERENCES staging.import_schema_profile (id),
    CONSTRAINT import_batch_content_fk
        FOREIGN KEY (content_id) REFERENCES raw.raw_content (id),
    CONSTRAINT import_batch_submitter_fk
        FOREIGN KEY (submitted_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT import_batch_approver_fk
        FOREIGN KEY (approved_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT import_batch_supersedes_fk
        FOREIGN KEY (supersedes_batch_id) REFERENCES staging.import_batch (id),
    CONSTRAINT import_batch_dataset_ck
        CHECK (dataset_kind IN ('PURCHASE_COST', 'INTERNAL_STOCK', 'FINANCE_INPUT')),
    CONSTRAINT import_batch_state_ck
        CHECK (state IN ('RECEIVED', 'VALIDATED', 'REJECTED', 'APPROVED',
                         'APPLIED', 'SUPERSEDED')),
    CONSTRAINT import_batch_rejection_ck
        CHECK ((state = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT import_batch_media_type_ck
        CHECK (declared_media_type IN (
            'text/csv',
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')),
    CONSTRAINT import_batch_file_name_ck
        CHECK (declared_file_name ~ '^[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}\.(csv|xlsx)$'),
    CONSTRAINT import_batch_counts_ck
        CHECK (total_row_count IS NULL
            OR (total_row_count >= 0
                AND accepted_row_count IS NOT NULL AND accepted_row_count >= 0
                AND rejected_row_count IS NOT NULL AND rejected_row_count >= 0
                AND accepted_row_count + rejected_row_count = total_row_count)),
    -- Applying an import is a separate, attributed act. A batch cannot reach
    -- APPLIED without the approval that authorised it.
    CONSTRAINT import_batch_approval_ck
        CHECK (state NOT IN ('APPROVED', 'APPLIED')
            OR (approved_by_user_id IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT import_batch_applied_ck
        CHECK ((state = 'APPLIED') = (applied_at IS NOT NULL)),
    CONSTRAINT import_batch_effective_ck
        CHECK (state NOT IN ('APPROVED', 'APPLIED') OR effective_from IS NOT NULL)
);

-- The same bytes cannot be imported twice into the same dataset while an
-- earlier attempt still stands. A rejected attempt releases the content so a
-- corrected resubmission of an identical file is possible after the rejection
-- has been recorded.
CREATE UNIQUE INDEX import_batch_content_live_uq
    ON staging.import_batch (organization_id, dataset_kind, content_id)
    WHERE state <> 'REJECTED';

CREATE INDEX import_batch_queue_ix
    ON staging.import_batch (organization_id, dataset_kind, state, submitted_at DESC);
CREATE INDEX import_batch_content_ix ON staging.import_batch (content_id);

-- One parsed row and what validation decided about it. Accepted and rejected
-- rows live together so the rejection report is the same object as the preview
-- and cannot drift from it.
CREATE TABLE staging.import_row (
    id               uuid    NOT NULL,
    batch_id         uuid    NOT NULL,
    row_number       integer NOT NULL,
    parsed_values    jsonb   NOT NULL,
    validation_state text    NOT NULL,
    rejection_code   text,
    rejection_detail text,
    target_key       text,
    CONSTRAINT import_row_pk PRIMARY KEY (id),
    CONSTRAINT import_row_batch_fk
        FOREIGN KEY (batch_id) REFERENCES staging.import_batch (id),
    CONSTRAINT import_row_number_uq UNIQUE (batch_id, row_number),
    CONSTRAINT import_row_number_ck CHECK (row_number > 0),
    CONSTRAINT import_row_state_ck CHECK (validation_state IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT import_row_rejection_ck
        CHECK ((validation_state = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT import_row_values_ck CHECK (jsonb_typeof(parsed_values) = 'object')
);

CREATE INDEX import_row_batch_state_ix
    ON staging.import_row (batch_id, validation_state, row_number);

-- ---------------------------------------------------------------------------
-- Provenance from an import
-- ---------------------------------------------------------------------------

-- An internally sourced fact names the batch it came from, exactly as a
-- marketplace fact names the stored bytes it came from. One provenance
-- authority serves both, so evidence drill-through has one shape.
ALTER TABLE core.fact_provenance
    ADD COLUMN import_batch_id uuid,
    ADD CONSTRAINT fact_provenance_import_batch_fk
        FOREIGN KEY (import_batch_id) REFERENCES staging.import_batch (id);

ALTER TABLE core.fact_provenance
    DROP CONSTRAINT fact_provenance_raw_required_ck,
    ADD CONSTRAINT fact_provenance_source_reference_ck
        CHECK (CASE source_kind
                   WHEN 'MARKETPLACE_RAW' THEN
                       raw_observation_id IS NOT NULL AND import_batch_id IS NULL
                   WHEN 'INTERNAL_IMPORT' THEN
                       import_batch_id IS NOT NULL AND raw_observation_id IS NULL
                   ELSE
                       raw_observation_id IS NULL AND import_batch_id IS NULL
               END);

CREATE INDEX fact_provenance_import_batch_ix
    ON core.fact_provenance (import_batch_id)
    WHERE import_batch_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Purchase cost
-- ---------------------------------------------------------------------------

-- The cost of one internal variant over one half-open interval. Two active
-- versions of the same variant and cost kind cannot overlap, so "what did this
-- cost on that day" has exactly one answer and a profit figure can be
-- reproduced against the version that was in force.
CREATE TABLE core.cost_version (
    id                  uuid           NOT NULL,
    organization_id     uuid           NOT NULL,
    product_variant_id  uuid           NOT NULL,
    cost_kind           text           NOT NULL,
    currency_code       text           NOT NULL,
    unit_cost           numeric(18, 4) NOT NULL,
    provenance_id       uuid           NOT NULL,
    effective_from      timestamptz    NOT NULL,
    effective_to        timestamptz,
    status              text           NOT NULL,
    reason              text,
    created_at          timestamptz    NOT NULL,
    updated_at          timestamptz    NOT NULL,
    version             bigint         NOT NULL DEFAULT 0,
    CONSTRAINT cost_version_pk PRIMARY KEY (id),
    CONSTRAINT cost_version_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT cost_version_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT cost_version_kind_ck CHECK (cost_kind IN ('PURCHASE', 'LANDED')),
    CONSTRAINT cost_version_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT cost_version_amount_ck CHECK (unit_cost >= 0),
    CONSTRAINT cost_version_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT cost_version_status_ck CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    CONSTRAINT cost_version_reason_ck CHECK (status = 'ACTIVE' OR reason IS NOT NULL),
    CONSTRAINT cost_version_no_overlap
        EXCLUDE USING gist (
            product_variant_id WITH =,
            cost_kind WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

CREATE INDEX cost_version_variant_ix
    ON core.cost_version (product_variant_id, cost_kind, effective_from DESC);

-- ---------------------------------------------------------------------------
-- Internal physical stock
-- ---------------------------------------------------------------------------

-- What the company itself holds, per warehouse and internal variant. This is a
-- separate fact from platform stock and is never merged with it: a stockout
-- diagnosis has to distinguish "the marketplace has none" from "we have none".
CREATE TABLE core.internal_stock_snapshot (
    id                 uuid        NOT NULL,
    organization_id    uuid        NOT NULL,
    provenance_id      uuid        NOT NULL,
    warehouse_id       uuid        NOT NULL,
    product_variant_id uuid        NOT NULL,
    source_fact_key    text        NOT NULL,
    observed_at        timestamptz NOT NULL,
    quantity_on_hand   integer     NOT NULL,
    quantity_reserved  integer,
    CONSTRAINT internal_stock_snapshot_pk PRIMARY KEY (id),
    CONSTRAINT internal_stock_snapshot_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT internal_stock_snapshot_warehouse_fk
        FOREIGN KEY (warehouse_id, organization_id)
        REFERENCES core.warehouse (id, organization_id),
    CONSTRAINT internal_stock_snapshot_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT internal_stock_snapshot_source_key_uq
        UNIQUE (organization_id, source_fact_key),
    CONSTRAINT internal_stock_snapshot_on_hand_ck CHECK (quantity_on_hand >= 0),
    CONSTRAINT internal_stock_snapshot_reserved_ck
        CHECK (quantity_reserved IS NULL OR quantity_reserved >= 0)
);

CREATE INDEX internal_stock_snapshot_variant_ix
    ON core.internal_stock_snapshot (product_variant_id, observed_at DESC);
CREATE INDEX internal_stock_snapshot_warehouse_ix
    ON core.internal_stock_snapshot (warehouse_id, observed_at DESC);

-- ---------------------------------------------------------------------------
-- Finance inputs
-- ---------------------------------------------------------------------------

-- Company-owned inputs to the profit definition that no marketplace publishes,
-- such as a variable tax estimate. Each is an effective-dated version at one
-- scope, so a profit figure can name the exact input version behind it.
CREATE TABLE core.finance_input_version (
    id                  uuid           NOT NULL,
    organization_id     uuid           NOT NULL,
    input_code          text           NOT NULL,
    scope_kind          text           NOT NULL,
    store_ref_id        uuid,
    product_variant_ref_id uuid,
    value_kind          text           NOT NULL,
    rate_value          numeric(9, 6),
    amount_value        numeric(18, 4),
    currency_code       text,
    provenance_id       uuid           NOT NULL,
    effective_from      timestamptz    NOT NULL,
    effective_to        timestamptz,
    status              text           NOT NULL,
    reason              text,
    created_at          timestamptz    NOT NULL,
    updated_at          timestamptz    NOT NULL,
    version             bigint         NOT NULL DEFAULT 0,
    CONSTRAINT finance_input_version_pk PRIMARY KEY (id),
    CONSTRAINT finance_input_version_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT finance_input_version_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT finance_input_version_variant_fk
        FOREIGN KEY (product_variant_ref_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT finance_input_version_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT finance_input_version_code_ck
        CHECK (input_code IN (
            'VARIABLE_TAX_RATE', 'PAYMENT_PROCESSING_RATE',
            'RETURN_HANDLING_UNIT_COST', 'INBOUND_LOGISTICS_UNIT_COST')),
    CONSTRAINT finance_input_version_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'STORE', 'PRODUCT_VARIANT')),
    CONSTRAINT finance_input_version_scope_matrix_ck CHECK (
        (scope_kind = 'ORGANIZATION'
            AND num_nonnulls(store_ref_id, product_variant_ref_id) = 0)
        OR (scope_kind = 'STORE'
            AND store_ref_id IS NOT NULL AND product_variant_ref_id IS NULL)
        OR (scope_kind = 'PRODUCT_VARIANT'
            AND product_variant_ref_id IS NOT NULL AND store_ref_id IS NULL)),
    CONSTRAINT finance_input_version_value_kind_ck CHECK (value_kind IN ('RATE', 'AMOUNT')),
    CONSTRAINT finance_input_version_value_matrix_ck CHECK (
        (value_kind = 'RATE'
            AND rate_value IS NOT NULL AND rate_value >= 0 AND rate_value <= 1
            AND amount_value IS NULL AND currency_code IS NULL)
        OR (value_kind = 'AMOUNT'
            AND amount_value IS NOT NULL AND amount_value >= 0
            AND currency_code IS NOT NULL AND rate_value IS NULL)),
    CONSTRAINT finance_input_version_currency_ck
        CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT finance_input_version_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT finance_input_version_status_ck
        CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    CONSTRAINT finance_input_version_reason_ck
        CHECK (status = 'ACTIVE' OR reason IS NOT NULL),
    CONSTRAINT finance_input_version_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            input_code WITH =,
            scope_kind WITH =,
            coalesce(store_ref_id, product_variant_ref_id,
                     '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

CREATE INDEX finance_input_version_lookup_ix
    ON core.finance_input_version (organization_id, input_code, effective_from DESC);

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Internal intake never reaches a marketplace. A file arrives through a person,
-- is stored in Raw custody and produces internal facts; no path from any table
-- below is read while an outbound call authority is evaluated.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('staging', 'import_schema_profile', 'NO_ROUTE', NULL,
        'internal file contract; no acquisition authority reads it'),
    ('staging', 'import_batch', 'NO_ROUTE', NULL,
        'internal intake state; no outbound call is authorised from it'),
    ('staging', 'import_row', 'NO_ROUTE', NULL,
        'append-only validation outcome; no acquisition authority reads it'),
    ('core', 'cost_version', 'NO_ROUTE', NULL,
        'internal cost fact; consumed by profit, not by call authority'),
    ('core', 'internal_stock_snapshot', 'NO_ROUTE', NULL,
        'append-only internal inventory fact; no acquisition authority reads it'),
    ('core', 'finance_input_version', 'NO_ROUTE', NULL,
        'internal finance input; consumed by profit, not by call authority');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Batch and version tables accept inserts and versioned updates; parsed rows
-- are append-only, because a rejection report that could be edited after the
-- fact is not a report. No DELETE is granted anywhere.
GRANT SELECT, INSERT, UPDATE ON staging.import_schema_profile TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON staging.import_batch TO marketops_app;
GRANT SELECT, INSERT ON staging.import_row TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.cost_version TO marketops_app;
GRANT SELECT, INSERT ON core.internal_stock_snapshot TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.finance_input_version TO marketops_app;
