-- Internal access metadata: the permission taxonomy, Service Account identity
-- and lifecycle, declared network sources, and explicit scoped permission
-- grants.
--
-- Everything here is policy metadata. No table in this schema authenticates a
-- caller or enforces an access decision at runtime; consumers evaluate it and
-- must treat every non-ACTIVE or unknown state as a denial.

-- The five internal permission kinds are independent of one another: no kind
-- implies another, and no aggregate "all permissions" value exists. Rows are
-- deterministic migration seeds; the application role reads and never writes.
CREATE TABLE iam.permission_kind (
    code         text NOT NULL,
    display_name text NOT NULL,
    CONSTRAINT permission_kind_pk PRIMARY KEY (code),
    CONSTRAINT permission_kind_code_ck CHECK (code ~ '^[A-Z][A-Z0-9_]{1,62}$')
);

INSERT INTO iam.permission_kind (code, display_name) VALUES
    ('READ', 'Read'),
    ('WRITE', 'Write'),
    ('FINANCE', 'Finance'),
    ('ADS', 'Advertising'),
    ('CREDENTIAL_ADMIN', 'Credential administration');

-- A Service Account is a non-human subject with a single stated purpose, a
-- named responsible owner and a mandatory expiry. Expiry is evaluated, not
-- stored back: an expired account keeps status ACTIVE and is refused by every
-- evaluation, so the operator's recorded intent is never rewritten by a clock.
CREATE TABLE iam.service_account (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    code            text        NOT NULL,
    display_name    text        NOT NULL,
    purpose         text        NOT NULL,
    owner_label     text        NOT NULL,
    expires_at      timestamptz NOT NULL,
    status          text        NOT NULL,
    disabled_reason text,
    last_used_at    timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT service_account_pk PRIMARY KEY (id),
    CONSTRAINT service_account_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT service_account_code_uq UNIQUE (organization_id, code),
    CONSTRAINT service_account_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT service_account_code_ck
        CHECK (code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT service_account_status_ck
        CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED')),
    -- A deactivation without a recorded reason is not auditable.
    CONSTRAINT service_account_reason_ck
        CHECK (status = 'ACTIVE' OR disabled_reason IS NOT NULL)
);

CREATE INDEX service_account_organization_ix
    ON iam.service_account (organization_id, status);

-- Declared allowed network sources. The consumption contract is fail-closed:
-- zero ACTIVE rows means no source is declared and therefore nothing is
-- allowed.
CREATE TABLE iam.service_account_allowed_source (
    id                 uuid        NOT NULL,
    service_account_id uuid        NOT NULL,
    cidr               text        NOT NULL,
    note               text,
    status             text        NOT NULL,
    reason             text,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT service_account_allowed_source_pk PRIMARY KEY (id),
    CONSTRAINT service_account_allowed_source_account_fk
        FOREIGN KEY (service_account_id) REFERENCES iam.service_account (id),
    CONSTRAINT service_account_allowed_source_status_ck
        CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT service_account_allowed_source_reason_ck
        CHECK (status = 'ACTIVE' OR reason IS NOT NULL)
);

-- One live declaration per source; a withdrawn source may be declared again as
-- a new row, so the withdrawal and the re-declaration are both retained.
CREATE UNIQUE INDEX service_account_allowed_source_active_uq
    ON iam.service_account_allowed_source (service_account_id, cidr)
    WHERE status = 'ACTIVE';

CREATE INDEX service_account_allowed_source_account_ix
    ON iam.service_account_allowed_source (service_account_id, status);

-- An explicit positive grant of one permission kind on exactly one resource.
-- Denial is the default: no wildcard resource, no wildcard permission, and no
-- derivation from other grants exists.
--
-- Exactly one resource reference column is set. Each reference is a composite
-- foreign key carrying the grant's organization_id, and an organization-scope
-- grant must name the grant's own organization, so a grant can never point at
-- a resource outside the Service Account's organization.
CREATE TABLE iam.service_account_scope_grant (
    id                         uuid        NOT NULL,
    organization_id            uuid        NOT NULL,
    service_account_id         uuid        NOT NULL,
    permission_code            text        NOT NULL,
    organization_ref_id        uuid,
    legal_entity_ref_id        uuid,
    marketplace_account_ref_id uuid,
    store_ref_id               uuid,
    warehouse_ref_id           uuid,
    effective_from             timestamptz NOT NULL,
    effective_to               timestamptz,
    status                     text        NOT NULL,
    reason                     text,
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    version                    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT service_account_scope_grant_pk PRIMARY KEY (id),
    CONSTRAINT service_account_scope_grant_account_fk
        FOREIGN KEY (service_account_id, organization_id)
        REFERENCES iam.service_account (id, organization_id),
    CONSTRAINT service_account_scope_grant_permission_fk
        FOREIGN KEY (permission_code) REFERENCES iam.permission_kind (code),
    CONSTRAINT service_account_scope_grant_one_resource_ck
        CHECK (num_nonnulls(
            organization_ref_id, legal_entity_ref_id, marketplace_account_ref_id,
            store_ref_id, warehouse_ref_id) = 1),
    CONSTRAINT service_account_scope_grant_own_org_ck
        CHECK (organization_ref_id IS NULL OR organization_ref_id = organization_id),
    CONSTRAINT service_account_scope_grant_org_ref_fk
        FOREIGN KEY (organization_ref_id) REFERENCES core.organization (id),
    CONSTRAINT service_account_scope_grant_legal_entity_ref_fk
        FOREIGN KEY (legal_entity_ref_id, organization_id)
        REFERENCES core.legal_entity (id, organization_id),
    CONSTRAINT service_account_scope_grant_account_ref_fk
        FOREIGN KEY (marketplace_account_ref_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT service_account_scope_grant_store_ref_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT service_account_scope_grant_warehouse_ref_fk
        FOREIGN KEY (warehouse_ref_id, organization_id)
        REFERENCES core.warehouse (id, organization_id),
    CONSTRAINT service_account_scope_grant_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT service_account_scope_grant_status_ck
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT service_account_scope_grant_reason_ck
        CHECK (status = 'ACTIVE' OR reason IS NOT NULL)
);

-- One live grant per subject, permission and resource. NULLS NOT DISTINCT makes
-- the unset resource columns compare equal, so the same scope cannot be granted
-- twice while active; a revoked grant stays as history.
CREATE UNIQUE INDEX service_account_scope_grant_active_uq
    ON iam.service_account_scope_grant (
        service_account_id, permission_code, organization_ref_id,
        legal_entity_ref_id, marketplace_account_ref_id, store_ref_id,
        warehouse_ref_id)
    NULLS NOT DISTINCT
    WHERE status = 'ACTIVE';

CREATE INDEX service_account_scope_grant_subject_ix
    ON iam.service_account_scope_grant (service_account_id, status);
CREATE INDEX service_account_scope_grant_org_ix
    ON iam.service_account_scope_grant (organization_id, status);

-- The permission taxonomy is read-only reference data; subject and grant tables
-- accept inserts and versioned updates. No DELETE is granted anywhere:
-- withdrawal and revocation are recorded state transitions.
GRANT SELECT ON iam.permission_kind TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON iam.service_account TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON iam.service_account_allowed_source TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON iam.service_account_scope_grant TO marketops_app;
