-- Platform-neutral operating-entity metadata: platform and fulfillment
-- reference data, the ownership chain Organization → Legal Entity →
-- Marketplace Account → Store, Legal Entity-owned Warehouses, and the
-- configurable Store↔Warehouse service associations.
--
-- Ownership integrity is relational. Every child carries organization_id and
-- references its parent through a composite foreign key that includes it, so a
-- row that crosses organizations is unrepresentable regardless of which client
-- wrote it.
--
-- Creation order inside this file is dependency order: reference tables first,
-- then the ownership chain from the top down, then associations.

-- Marketplace platform identity. Platform codes are upstream identity for the
-- whole account chain and for every platform-integration table, which reference
-- this table one-way. Rows are deterministic migration seeds; the application
-- role reads and never writes them.
CREATE TABLE core.marketplace_platform (
    code         text NOT NULL,
    display_name text NOT NULL,
    status       text NOT NULL,
    CONSTRAINT marketplace_platform_pk PRIMARY KEY (code),
    CONSTRAINT marketplace_platform_code_ck CHECK (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT marketplace_platform_status_ck CHECK (status IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO core.marketplace_platform (code, display_name, status) VALUES
    ('OZON', 'Ozon', 'ACTIVE'),
    ('WILDBERRIES', 'Wildberries', 'ACTIVE');

-- Generic fulfillment family. UNKNOWN is a first-class value: an unrecorded
-- mode stays UNKNOWN and is never coerced to a marketplace- or seller-fulfilled
-- claim.
CREATE TABLE core.fulfillment_mode (
    code         text NOT NULL,
    display_name text NOT NULL,
    CONSTRAINT fulfillment_mode_pk PRIMARY KEY (code),
    CONSTRAINT fulfillment_mode_code_ck CHECK (code ~ '^[A-Z][A-Z0-9_]{1,62}$')
);

INSERT INTO core.fulfillment_mode (code, display_name) VALUES
    ('MARKETPLACE_FULFILLED', 'Marketplace fulfilled'),
    ('SELLER_FULFILLED', 'Seller fulfilled'),
    ('UNKNOWN', 'Unknown');

CREATE TABLE core.organization (
    id                    uuid        NOT NULL,
    code                  text        NOT NULL,
    display_name          text        NOT NULL,
    default_timezone      text,
    default_currency_code text,
    status                text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT organization_pk PRIMARY KEY (id),
    CONSTRAINT organization_code_uq UNIQUE (code),
    CONSTRAINT organization_code_ck
        CHECK (code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT organization_currency_ck
        CHECK (default_currency_code IS NULL OR default_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT organization_timezone_ck
        CHECK (default_timezone IS NULL OR default_timezone ~ '^[A-Za-z0-9_+/-]{1,64}$'),
    CONSTRAINT organization_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE TABLE core.legal_entity (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    code            text        NOT NULL,
    display_name    text        NOT NULL,
    registered_name text,
    country_code    text,
    status          text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT legal_entity_pk PRIMARY KEY (id),
    CONSTRAINT legal_entity_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT legal_entity_code_uq UNIQUE (organization_id, code),
    CONSTRAINT legal_entity_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT legal_entity_code_ck
        CHECK (code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT legal_entity_country_ck
        CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT legal_entity_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE INDEX legal_entity_organization_ix ON core.legal_entity (organization_id, status);

CREATE TABLE core.marketplace_account (
    id                 uuid        NOT NULL,
    organization_id    uuid        NOT NULL,
    legal_entity_id    uuid        NOT NULL,
    platform_code      text        NOT NULL,
    code               text        NOT NULL,
    display_name       text        NOT NULL,
    native_account_key text,
    status             text        NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT marketplace_account_pk PRIMARY KEY (id),
    CONSTRAINT marketplace_account_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT marketplace_account_legal_entity_fk
        FOREIGN KEY (legal_entity_id, organization_id)
        REFERENCES core.legal_entity (id, organization_id),
    CONSTRAINT marketplace_account_code_uq UNIQUE (organization_id, code),
    CONSTRAINT marketplace_account_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT marketplace_account_id_platform_uq UNIQUE (id, platform_code),
    CONSTRAINT marketplace_account_code_ck
        CHECK (code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT marketplace_account_status_ck
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE INDEX marketplace_account_organization_ix
    ON core.marketplace_account (organization_id, status);
CREATE INDEX marketplace_account_legal_entity_ix
    ON core.marketplace_account (legal_entity_id);

-- A platform-native account key is opaque and may be absent. While present on a
-- non-retired account it is unique per platform, so one native account cannot
-- be registered twice as two live accounts. Retirement releases the key for a
-- legitimate re-registration without erasing the retired row.
CREATE UNIQUE INDEX marketplace_account_native_key_uq
    ON core.marketplace_account (platform_code, native_account_key)
    WHERE native_account_key IS NOT NULL AND status <> 'RETIRED';

CREATE TABLE core.store (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    marketplace_account_id uuid        NOT NULL,
    code                   text        NOT NULL,
    display_name           text        NOT NULL,
    native_store_key       text,
    timezone               text,
    currency_code          text,
    status                 text        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT store_pk PRIMARY KEY (id),
    CONSTRAINT store_account_fk
        FOREIGN KEY (marketplace_account_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT store_code_uq UNIQUE (organization_id, code),
    CONSTRAINT store_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT store_id_account_uq UNIQUE (id, marketplace_account_id),
    CONSTRAINT store_code_ck
        CHECK (code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT store_timezone_ck
        CHECK (timezone IS NULL OR timezone ~ '^[A-Za-z0-9_+/-]{1,64}$'),
    CONSTRAINT store_currency_ck
        CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT store_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE INDEX store_organization_ix ON core.store (organization_id, status);
CREATE INDEX store_account_ix ON core.store (marketplace_account_id);

CREATE UNIQUE INDEX store_native_key_uq
    ON core.store (marketplace_account_id, native_store_key)
    WHERE native_store_key IS NOT NULL AND status <> 'RETIRED';

-- A Warehouse belongs to a Legal Entity, never to a Store. Store service
-- relationships are expressed only through core.store_warehouse_link.
CREATE TABLE core.warehouse (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    legal_entity_id uuid        NOT NULL,
    code            text        NOT NULL,
    display_name    text        NOT NULL,
    timezone        text,
    status          text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT warehouse_pk PRIMARY KEY (id),
    CONSTRAINT warehouse_legal_entity_fk
        FOREIGN KEY (legal_entity_id, organization_id)
        REFERENCES core.legal_entity (id, organization_id),
    CONSTRAINT warehouse_code_uq UNIQUE (organization_id, code),
    CONSTRAINT warehouse_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT warehouse_code_ck
        CHECK (code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT warehouse_timezone_ck
        CHECK (timezone IS NULL OR timezone ~ '^[A-Za-z0-9_+/-]{1,64}$'),
    CONSTRAINT warehouse_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED'))
);

CREATE INDEX warehouse_organization_ix ON core.warehouse (organization_id, status);
CREATE INDEX warehouse_legal_entity_ix ON core.warehouse (legal_entity_id);

-- A Store↔Warehouse service association for one fulfillment mode over one
-- half-open validity interval. The two composite foreign keys share the row's
-- organization_id, so a cross-organization association is unrepresentable.
CREATE TABLE core.store_warehouse_link (
    id                    uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    warehouse_id          uuid        NOT NULL,
    fulfillment_mode_code text        NOT NULL,
    effective_from        timestamptz NOT NULL,
    effective_to          timestamptz,
    status                text        NOT NULL,
    note                  text,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT store_warehouse_link_pk PRIMARY KEY (id),
    CONSTRAINT store_warehouse_link_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT store_warehouse_link_warehouse_fk
        FOREIGN KEY (warehouse_id, organization_id)
        REFERENCES core.warehouse (id, organization_id),
    CONSTRAINT store_warehouse_link_mode_fk
        FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode (code),
    CONSTRAINT store_warehouse_link_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT store_warehouse_link_status_ck
        CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    -- Two active associations of the same store, warehouse and mode cannot
    -- overlap in time. Ended and cancelled rows stay out of the way so history
    -- is retained without blocking a new interval.
    CONSTRAINT store_warehouse_link_no_overlap
        EXCLUDE USING gist (
            store_id WITH =,
            warehouse_id WITH =,
            fulfillment_mode_code WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

CREATE INDEX store_warehouse_link_store_ix
    ON core.store_warehouse_link (store_id, status, effective_from);
CREATE INDEX store_warehouse_link_warehouse_ix
    ON core.store_warehouse_link (warehouse_id, status);

-- A Store-level fulfillment mode declaration over a validity interval. A store
-- can operate marketplace-fulfilled with no local warehouse association, which
-- is why this declaration exists independently of store_warehouse_link.
CREATE TABLE core.store_fulfillment_declaration (
    id                    uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    fulfillment_mode_code text        NOT NULL,
    effective_from        timestamptz NOT NULL,
    effective_to          timestamptz,
    status                text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT store_fulfillment_declaration_pk PRIMARY KEY (id),
    CONSTRAINT store_fulfillment_declaration_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT store_fulfillment_declaration_mode_fk
        FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode (code),
    CONSTRAINT store_fulfillment_declaration_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT store_fulfillment_declaration_status_ck
        CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    CONSTRAINT store_fulfillment_declaration_no_overlap
        EXCLUDE USING gist (
            store_id WITH =,
            fulfillment_mode_code WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

CREATE INDEX store_fulfillment_declaration_store_ix
    ON core.store_fulfillment_declaration (store_id, status, effective_from);

-- Reference tables are read-only for the application: their rows are
-- deterministic migration seeds. Entity and association tables accept inserts
-- and versioned updates; nothing in this schema grants DELETE, because
-- retirement is a recorded state transition rather than row removal.
GRANT SELECT ON core.marketplace_platform TO marketops_app;
GRANT SELECT ON core.fulfillment_mode TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.organization TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.legal_entity TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.marketplace_account TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.store TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.warehouse TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.store_warehouse_link TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.store_fulfillment_declaration TO marketops_app;
