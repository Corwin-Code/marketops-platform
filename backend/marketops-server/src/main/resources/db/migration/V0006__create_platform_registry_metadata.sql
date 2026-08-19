-- Marketplace-integration metadata: credential purposes and opaque credential
-- references with an explicit scope contract, the platform-neutral capability
-- and endpoint registry with verification provenance, per-subject capability
-- status, external platform permission-requirement evidence, and feature-flag
-- metadata.
--
-- Every table here references core identity one-way. Nothing in this schema
-- references iam: the registry records platform truth and holds no mapping to
-- internal permissions.
--
-- Platform namespace integrity is relational: registry relationships carry the
-- row's platform_code inside composite foreign keys, so a link between objects
-- of two different platforms is unrepresentable even for direct SQL.

-- Credential purposes mirror the platform-account governance separation of
-- credentials by use. This is an internal governance taxonomy, not a platform
-- API fact, and it is disjoint from iam.permission_kind: administering
-- credential metadata is an internal permission, never a credential purpose.
CREATE TABLE platform.credential_purpose (
    code         text NOT NULL,
    display_name text NOT NULL,
    CONSTRAINT credential_purpose_pk PRIMARY KEY (code),
    CONSTRAINT credential_purpose_code_ck CHECK (code ~ '^[A-Z][A-Z0-9_]{1,62}$')
);

INSERT INTO platform.credential_purpose (code, display_name) VALUES
    ('READ', 'Read'),
    ('FINANCE', 'Finance'),
    ('INVENTORY_WRITE', 'Inventory write'),
    ('PRICE_WRITE', 'Price write'),
    ('ADS_WRITE', 'Advertising write');

-- Non-secret credential metadata. The secret_reference column names a secret
-- by an opaque reference and never holds secret material; the format check
-- rejects anything that is not a well-formed reference.
--
-- Scope is declared, never inferred: scope_mode ACCOUNT covers the whole
-- account, scope_mode STORE_SET covers exactly the ACTIVE rows in
-- credential_store_scope, and a STORE_SET credential with no active scope row
-- is unusable rather than account-wide.
--
-- The validity window is [effective_from, expires_at) intersected with status
-- ACTIVE. There is deliberately no non-overlap constraint on this table:
-- rotation requires an old and a new credential of the same account and
-- purpose to be simultaneously active, with the succession recorded through
-- replaces_credential_id.
CREATE TABLE platform.credential_metadata (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    marketplace_account_id uuid        NOT NULL,
    code                   text        NOT NULL,
    display_name           text        NOT NULL,
    purpose_code           text        NOT NULL,
    scope_mode             text        NOT NULL,
    secret_reference       text        NOT NULL,
    effective_from         timestamptz NOT NULL,
    expires_at             timestamptz NOT NULL,
    replaces_credential_id uuid,
    status                 text        NOT NULL,
    custodian_label        text        NOT NULL,
    last_used_at           timestamptz,
    verification_state     text        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT credential_metadata_pk PRIMARY KEY (id),
    CONSTRAINT credential_metadata_account_fk
        FOREIGN KEY (marketplace_account_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT credential_metadata_purpose_fk
        FOREIGN KEY (purpose_code) REFERENCES platform.credential_purpose (code),
    CONSTRAINT credential_metadata_replaces_fk
        FOREIGN KEY (replaces_credential_id) REFERENCES platform.credential_metadata (id),
    CONSTRAINT credential_metadata_code_uq UNIQUE (organization_id, code),
    CONSTRAINT credential_metadata_id_account_uq UNIQUE (id, marketplace_account_id),
    CONSTRAINT credential_metadata_code_ck
        CHECK (code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT credential_metadata_scope_mode_ck
        CHECK (scope_mode IN ('ACCOUNT', 'STORE_SET')),
    CONSTRAINT credential_metadata_secret_reference_ck
        CHECK (secret_reference ~
            '^secret-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,4}$'),
    CONSTRAINT credential_metadata_window_ck CHECK (effective_from < expires_at),
    CONSTRAINT credential_metadata_status_ck
        CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED')),
    -- This metadata surface has no credential-evidence verification transition,
    -- so every stored credential remains UNVERIFIED.
    CONSTRAINT credential_metadata_verification_ck
        CHECK (verification_state = 'UNVERIFIED')
);

CREATE INDEX credential_metadata_account_ix
    ON platform.credential_metadata (marketplace_account_id, status);
CREATE INDEX credential_metadata_org_ix
    ON platform.credential_metadata (organization_id, status);
CREATE INDEX credential_metadata_replaces_ix
    ON platform.credential_metadata (replaces_credential_id);

-- One live credential per secret reference. Revocation releases the reference,
-- so a re-created credential never aliases a live one.
CREATE UNIQUE INDEX credential_metadata_secret_reference_uq
    ON platform.credential_metadata (secret_reference)
    WHERE status <> 'REVOKED';

-- Store scope rows for STORE_SET credentials. The row carries the account key,
-- and both composite foreign keys pin it: the credential side proves the row
-- belongs to the credential's account, the store side proves the store belongs
-- to that same account. A cross-account scope row is unrepresentable.
CREATE TABLE platform.credential_store_scope (
    id                     uuid        NOT NULL,
    credential_id          uuid        NOT NULL,
    marketplace_account_id uuid        NOT NULL,
    store_id               uuid        NOT NULL,
    status                 text        NOT NULL,
    reason                 text,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT credential_store_scope_pk PRIMARY KEY (id),
    CONSTRAINT credential_store_scope_credential_fk
        FOREIGN KEY (credential_id, marketplace_account_id)
        REFERENCES platform.credential_metadata (id, marketplace_account_id),
    CONSTRAINT credential_store_scope_store_fk
        FOREIGN KEY (store_id, marketplace_account_id)
        REFERENCES core.store (id, marketplace_account_id),
    CONSTRAINT credential_store_scope_status_ck
        CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT credential_store_scope_reason_ck
        CHECK (status = 'ACTIVE' OR reason IS NOT NULL)
);

CREATE UNIQUE INDEX credential_store_scope_active_uq
    ON platform.credential_store_scope (credential_id, store_id)
    WHERE status = 'ACTIVE';

CREATE INDEX credential_store_scope_credential_ix
    ON platform.credential_store_scope (credential_id, status);
CREATE INDEX credential_store_scope_store_ix
    ON platform.credential_store_scope (store_id);

-- A platform-neutral logical capability. Verification is fail-closed metadata:
-- UNKNOWN and UNVERIFIED rows can never enable behaviour, and VERIFIED requires
-- complete provenance. capability_code is an internal registry name, never a
-- platform API name.
CREATE TABLE platform.platform_capability (
    id                        uuid        NOT NULL,
    platform_code             text        NOT NULL,
    capability_code           text        NOT NULL,
    display_name              text        NOT NULL,
    description               text,
    applies_to                text        NOT NULL,
    read_write_class          text        NOT NULL,
    subscription_required     text        NOT NULL,
    verification_state        text        NOT NULL,
    last_verified_at          timestamptz,
    evidence_ref              text,
    verified_source_title     text,
    owner_label               text        NOT NULL,
    contract_test_status      text        NOT NULL,
    deprecated_at             timestamptz,
    replacement_capability_id uuid,
    status                    text        NOT NULL,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    version                   bigint      NOT NULL DEFAULT 0,
    CONSTRAINT platform_capability_pk PRIMARY KEY (id),
    CONSTRAINT platform_capability_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT platform_capability_code_uq UNIQUE (platform_code, capability_code),
    -- Composite key target keeping every registry relationship inside one
    -- platform namespace.
    CONSTRAINT platform_capability_id_platform_uq UNIQUE (id, platform_code),
    CONSTRAINT platform_capability_replacement_fk
        FOREIGN KEY (replacement_capability_id, platform_code)
        REFERENCES platform.platform_capability (id, platform_code),
    CONSTRAINT platform_capability_capability_code_ck
        CHECK (capability_code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    CONSTRAINT platform_capability_applies_to_ck
        CHECK (applies_to IN ('MARKETPLACE_ACCOUNT', 'STORE', 'UNKNOWN')),
    CONSTRAINT platform_capability_read_write_ck
        CHECK (read_write_class IN ('READ', 'WRITE')),
    CONSTRAINT platform_capability_subscription_ck
        CHECK (subscription_required IN ('YES', 'NO', 'UNKNOWN')),
    CONSTRAINT platform_capability_verification_ck
        CHECK (verification_state IN ('UNKNOWN', 'UNVERIFIED', 'VERIFIED')),
    CONSTRAINT platform_capability_provenance_ck
        CHECK (verification_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    CONSTRAINT platform_capability_contract_test_ck
        CHECK (contract_test_status IN ('NOT_IMPLEMENTED', 'FAILING', 'PASSING')),
    CONSTRAINT platform_capability_status_ck CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE INDEX platform_capability_platform_ix
    ON platform.platform_capability (platform_code, status);

-- A physical endpoint or endpoint version. Every unrecorded operational fact
-- (pagination, rate limits, idempotency, late data) is stored as UNKNOWN or
-- NULL; no seed assumes an external platform fact.
CREATE TABLE platform.platform_endpoint (
    id                      uuid        NOT NULL,
    platform_code           text        NOT NULL,
    endpoint_code           text        NOT NULL,
    api_version             text        NOT NULL,
    http_method             text,
    path_template           text,
    capability_id           uuid,
    read_write_class        text        NOT NULL,
    pagination_model        text        NOT NULL,
    rate_limit_per_minute   integer,
    rate_limit_note         text,
    quota_note              text,
    idempotency_support     text        NOT NULL,
    late_data_behavior      text,
    freshness_expectation   text,
    business_key_note       text,
    schema_version          text,
    deprecated_at           timestamptz,
    replacement_endpoint_id uuid,
    verification_state      text        NOT NULL,
    last_verified_at        timestamptz,
    evidence_ref            text,
    verified_source_title   text,
    owner_label             text        NOT NULL,
    contract_test_status    text        NOT NULL,
    status                  text        NOT NULL,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    version                 bigint      NOT NULL DEFAULT 0,
    CONSTRAINT platform_endpoint_pk PRIMARY KEY (id),
    CONSTRAINT platform_endpoint_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT platform_endpoint_code_uq UNIQUE (platform_code, endpoint_code, api_version),
    CONSTRAINT platform_endpoint_id_platform_uq UNIQUE (id, platform_code),
    CONSTRAINT platform_endpoint_capability_fk
        FOREIGN KEY (capability_id, platform_code)
        REFERENCES platform.platform_capability (id, platform_code),
    CONSTRAINT platform_endpoint_replacement_fk
        FOREIGN KEY (replacement_endpoint_id, platform_code)
        REFERENCES platform.platform_endpoint (id, platform_code),
    CONSTRAINT platform_endpoint_endpoint_code_ck
        CHECK (endpoint_code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    CONSTRAINT platform_endpoint_http_method_ck
        CHECK (http_method IS NULL
            OR http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    CONSTRAINT platform_endpoint_read_write_ck
        CHECK (read_write_class IN ('READ', 'WRITE')),
    CONSTRAINT platform_endpoint_pagination_ck
        CHECK (pagination_model IN
            ('CURSOR', 'OFFSET', 'PAGE', 'DATE_WINDOW', 'NONE', 'UNKNOWN')),
    CONSTRAINT platform_endpoint_rate_limit_ck
        CHECK (rate_limit_per_minute IS NULL OR rate_limit_per_minute > 0),
    CONSTRAINT platform_endpoint_idempotency_ck
        CHECK (idempotency_support IN ('YES', 'NO', 'UNKNOWN')),
    CONSTRAINT platform_endpoint_verification_ck
        CHECK (verification_state IN ('UNKNOWN', 'UNVERIFIED', 'VERIFIED')),
    CONSTRAINT platform_endpoint_provenance_ck
        CHECK (verification_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    CONSTRAINT platform_endpoint_contract_test_ck
        CHECK (contract_test_status IN ('NOT_IMPLEMENTED', 'FAILING', 'PASSING')),
    CONSTRAINT platform_endpoint_status_ck CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE INDEX platform_endpoint_platform_ix
    ON platform.platform_endpoint (platform_code, status);
CREATE INDEX platform_endpoint_capability_ix
    ON platform.platform_endpoint (capability_id);

-- Availability of one capability for one subject: exactly one of the account
-- and store columns is set. The account subject is pinned to the capability's
-- platform relationally; the store subject's platform consistency is a
-- service-level rule because it crosses two tables.
CREATE TABLE platform.capability_subject_status (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    platform_code          text        NOT NULL,
    capability_id          uuid        NOT NULL,
    marketplace_account_id uuid,
    store_id               uuid,
    availability           text        NOT NULL,
    last_verified_at       timestamptz,
    evidence_ref           text,
    verified_source_title  text,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT capability_subject_status_pk PRIMARY KEY (id),
    CONSTRAINT capability_subject_status_capability_fk
        FOREIGN KEY (capability_id, platform_code)
        REFERENCES platform.platform_capability (id, platform_code),
    CONSTRAINT capability_subject_status_one_subject_ck
        CHECK (num_nonnulls(marketplace_account_id, store_id) = 1),
    CONSTRAINT capability_subject_status_account_platform_fk
        FOREIGN KEY (marketplace_account_id, platform_code)
        REFERENCES core.marketplace_account (id, platform_code),
    CONSTRAINT capability_subject_status_account_org_fk
        FOREIGN KEY (marketplace_account_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT capability_subject_status_store_org_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT capability_subject_status_availability_ck
        CHECK (availability IN ('UNKNOWN', 'UNAVAILABLE', 'AVAILABLE')),
    CONSTRAINT capability_subject_status_provenance_ck
        CHECK (availability = 'UNKNOWN'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL))
);

CREATE UNIQUE INDEX capability_subject_status_account_uq
    ON platform.capability_subject_status (capability_id, marketplace_account_id)
    WHERE marketplace_account_id IS NOT NULL;
CREATE UNIQUE INDEX capability_subject_status_store_uq
    ON platform.capability_subject_status (capability_id, store_id)
    WHERE store_id IS NOT NULL;

CREATE INDEX capability_subject_status_org_ix
    ON platform.capability_subject_status (organization_id);

-- The platform's own permission language: an API role, OAuth scope,
-- subscription or plan the platform requires for a capability or endpoint.
-- external_code is the platform's identifier, stored opaquely. This table is
-- disjoint from the internal permission taxonomy and holds no seeded facts;
-- populated rows contain values supplied through evidence-aware maintenance.
CREATE TABLE platform.platform_permission_requirement (
    id                    uuid        NOT NULL,
    platform_code         text        NOT NULL,
    capability_id         uuid,
    endpoint_id           uuid,
    requirement_kind      text        NOT NULL,
    external_code         text        NOT NULL,
    description           text,
    verification_state    text        NOT NULL,
    last_verified_at      timestamptz,
    evidence_ref          text,
    verified_source_title text,
    status                text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT platform_permission_requirement_pk PRIMARY KEY (id),
    CONSTRAINT platform_permission_requirement_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT platform_permission_requirement_one_target_ck
        CHECK (num_nonnulls(capability_id, endpoint_id) = 1),
    CONSTRAINT platform_permission_requirement_capability_fk
        FOREIGN KEY (capability_id, platform_code)
        REFERENCES platform.platform_capability (id, platform_code),
    CONSTRAINT platform_permission_requirement_endpoint_fk
        FOREIGN KEY (endpoint_id, platform_code)
        REFERENCES platform.platform_endpoint (id, platform_code),
    CONSTRAINT platform_permission_requirement_kind_ck
        CHECK (requirement_kind IN
            ('API_ROLE', 'OAUTH_SCOPE', 'SUBSCRIPTION', 'PLAN', 'OTHER', 'UNKNOWN')),
    CONSTRAINT platform_permission_requirement_verification_ck
        CHECK (verification_state IN ('UNKNOWN', 'UNVERIFIED', 'VERIFIED')),
    CONSTRAINT platform_permission_requirement_provenance_ck
        CHECK (verification_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    CONSTRAINT platform_permission_requirement_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX platform_permission_requirement_capability_uq
    ON platform.platform_permission_requirement
        (platform_code, requirement_kind, external_code, capability_id)
    WHERE capability_id IS NOT NULL;
CREATE UNIQUE INDEX platform_permission_requirement_endpoint_uq
    ON platform.platform_permission_requirement
        (platform_code, requirement_kind, external_code, endpoint_id)
    WHERE endpoint_id IS NOT NULL;

-- Append-only journal of every verification and availability transition, with
-- its evidence. Exactly one target column is set. The application inserts and
-- reads; verification history is never rewritten.
CREATE TABLE platform.capability_verification_event (
    id                                 uuid        NOT NULL,
    capability_id                      uuid,
    endpoint_id                        uuid,
    capability_subject_status_id       uuid,
    platform_permission_requirement_id uuid,
    from_state                         text        NOT NULL,
    to_state                           text        NOT NULL,
    evidence_ref                       text,
    source_title                       text,
    verified_at                        timestamptz,
    actor                              text        NOT NULL,
    reason                             text,
    occurred_at                        timestamptz NOT NULL DEFAULT now(),
    correlation_id                     text        NOT NULL,
    CONSTRAINT capability_verification_event_pk PRIMARY KEY (id),
    CONSTRAINT capability_verification_event_one_target_ck
        CHECK (num_nonnulls(
            capability_id, endpoint_id, capability_subject_status_id,
            platform_permission_requirement_id) = 1),
    CONSTRAINT capability_verification_event_capability_fk
        FOREIGN KEY (capability_id) REFERENCES platform.platform_capability (id),
    CONSTRAINT capability_verification_event_endpoint_fk
        FOREIGN KEY (endpoint_id) REFERENCES platform.platform_endpoint (id),
    CONSTRAINT capability_verification_event_subject_fk
        FOREIGN KEY (capability_subject_status_id)
        REFERENCES platform.capability_subject_status (id),
    CONSTRAINT capability_verification_event_requirement_fk
        FOREIGN KEY (platform_permission_requirement_id)
        REFERENCES platform.platform_permission_requirement (id)
);

CREATE INDEX capability_verification_event_capability_ix
    ON platform.capability_verification_event (capability_id, occurred_at DESC);
CREATE INDEX capability_verification_event_endpoint_ix
    ON platform.capability_verification_event (endpoint_id, occurred_at DESC);

-- Feature-flag metadata. State defaults to DISABLED and the kill direction
-- (ENABLED → DISABLED) is never gated. A WRITE_CAPABILITY flag cannot reach
-- ENABLED while production writes are globally disabled; that rule lives in the
-- application service because the global gate is runtime configuration, and it
-- is covered by dedicated denial tests.
CREATE TABLE platform.feature_flag (
    id                     uuid        NOT NULL,
    flag_code              text        NOT NULL,
    flag_kind              text        NOT NULL,
    scope_kind             text        NOT NULL,
    platform_code          text,
    marketplace_account_id uuid,
    store_id               uuid,
    capability_id          uuid,
    scope_key              text GENERATED ALWAYS AS (
        scope_kind || ':'
            || coalesce(platform_code, '')
            || ':' || coalesce(CAST(marketplace_account_id AS text), '')
            || ':' || coalesce(CAST(store_id AS text), '')
            || ':' || coalesce(CAST(capability_id AS text), '')) STORED,
    state                  text        NOT NULL,
    description            text,
    reason                 text,
    status                 text        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT feature_flag_pk PRIMARY KEY (id),
    CONSTRAINT feature_flag_code_ck
        CHECK (flag_code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    CONSTRAINT feature_flag_kind_ck
        CHECK (flag_kind IN ('OPERATIONAL', 'WRITE_CAPABILITY')),
    CONSTRAINT feature_flag_scope_kind_ck
        CHECK (scope_kind IN
            ('GLOBAL', 'PLATFORM', 'MARKETPLACE_ACCOUNT', 'STORE', 'CAPABILITY')),
    -- The scope kind decides exactly which reference is set.
    CONSTRAINT feature_flag_scope_matrix_ck CHECK (
        (scope_kind = 'GLOBAL' AND num_nonnulls(
            platform_code, marketplace_account_id, store_id, capability_id) = 0)
        OR (scope_kind = 'PLATFORM' AND platform_code IS NOT NULL AND num_nonnulls(
            marketplace_account_id, store_id, capability_id) = 0)
        OR (scope_kind = 'MARKETPLACE_ACCOUNT' AND marketplace_account_id IS NOT NULL
            AND platform_code IS NULL AND num_nonnulls(store_id, capability_id) = 0)
        OR (scope_kind = 'STORE' AND store_id IS NOT NULL AND platform_code IS NULL
            AND num_nonnulls(marketplace_account_id, capability_id) = 0)
        OR (scope_kind = 'CAPABILITY' AND capability_id IS NOT NULL
            AND platform_code IS NULL
            AND num_nonnulls(marketplace_account_id, store_id) = 0)),
    CONSTRAINT feature_flag_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT feature_flag_account_fk
        FOREIGN KEY (marketplace_account_id) REFERENCES core.marketplace_account (id),
    CONSTRAINT feature_flag_store_fk
        FOREIGN KEY (store_id) REFERENCES core.store (id),
    CONSTRAINT feature_flag_capability_fk
        FOREIGN KEY (capability_id) REFERENCES platform.platform_capability (id),
    CONSTRAINT feature_flag_state_ck CHECK (state IN ('DISABLED', 'ENABLED')),
    CONSTRAINT feature_flag_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    -- A retired flag cannot stay switched on.
    CONSTRAINT feature_flag_retired_disabled_ck
        CHECK (status = 'ACTIVE' OR state = 'DISABLED')
);

CREATE UNIQUE INDEX feature_flag_scope_uq
    ON platform.feature_flag (flag_code, scope_key)
    WHERE status = 'ACTIVE';

CREATE INDEX feature_flag_state_ix ON platform.feature_flag (state, status);

-- The purpose taxonomy is read-only reference data. The verification journal is
-- append-only: insert and read, no update. Everything else accepts inserts and
-- versioned updates. No DELETE is granted anywhere in this schema.
GRANT SELECT ON platform.credential_purpose TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON platform.credential_metadata TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON platform.credential_store_scope TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON platform.platform_capability TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON platform.platform_endpoint TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON platform.capability_subject_status TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON platform.platform_permission_requirement TO marketops_app;
GRANT SELECT, INSERT ON platform.capability_verification_event TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON platform.feature_flag TO marketops_app;
