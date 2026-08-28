-- Human identity and business authorization: the approved external identity
-- providers, the internal user profile bound to an external subject, the
-- business role and action-scope taxonomy, the effective-dated grants that
-- carry them, and the append-only journal of authentication and authorization
-- decisions.
--
-- MarketOps authenticates nobody. An external OpenID Connect provider proves
-- who the caller is and whether multi-factor authentication happened; this
-- schema decides what that person may do here. The separation is why no column
-- below can hold a password, a factor secret, a token or a refresh value: there
-- is no such column, so no code path can write one.
--
-- Authorization is a conjunction, never a union. A caller needs a live user, a
-- live role assignment, and a live scope grant naming both the action and the
-- resource. Any missing or unrecognised element is a denial, and the denial is
-- recorded with the same care as the permission.

-- ---------------------------------------------------------------------------
-- Approved identity providers
-- ---------------------------------------------------------------------------

-- The issuers this deployment accepts. The runtime resource server is
-- configured with an issuer independently; a token is accepted only when its
-- issuer also matches an ACTIVE row here, so switching identity provider is a
-- recorded, reviewable change rather than an environment variable edit.
--
-- mfa_claim_name and mfa_claim_value describe how this provider states that a
-- second factor was used. They are recorded per provider because the claim
-- name is provider vocabulary, not a standard every issuer shares. While the
-- values are unrecorded the provider cannot reach ACTIVE, so an unverified
-- provider can never satisfy the mandatory multi-factor requirement.
CREATE TABLE iam.identity_provider (
    id                    uuid        NOT NULL,
    code                  text        NOT NULL,
    display_name          text        NOT NULL,
    issuer                text        NOT NULL,
    mfa_claim_name        text,
    mfa_claim_value       text,
    max_auth_age_seconds  integer     NOT NULL,
    verification_state    text        NOT NULL,
    last_verified_at      timestamptz,
    evidence_ref          text,
    verified_source_title text,
    owner_label           text        NOT NULL,
    status                text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT identity_provider_pk PRIMARY KEY (id),
    CONSTRAINT identity_provider_code_uq UNIQUE (code),
    CONSTRAINT identity_provider_issuer_uq UNIQUE (issuer),
    CONSTRAINT identity_provider_code_ck
        CHECK (code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    -- An issuer identifier is an https origin with an optional path. A plain
    -- http issuer would let a network position replace an identity provider.
    CONSTRAINT identity_provider_issuer_ck
        CHECK (issuer ~ '^https://[a-z0-9][a-z0-9.-]{0,252}(/[A-Za-z0-9._~-]{1,64}){0,6}$'),
    CONSTRAINT identity_provider_mfa_claim_ck
        CHECK (num_nonnulls(mfa_claim_name, mfa_claim_value) <> 1),
    CONSTRAINT identity_provider_auth_age_ck
        CHECK (max_auth_age_seconds BETWEEN 60 AND 86400),
    CONSTRAINT identity_provider_verification_ck
        CHECK (verification_state IN ('UNKNOWN', 'UNVERIFIED', 'VERIFIED')),
    CONSTRAINT identity_provider_provenance_ck
        CHECK (verification_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    CONSTRAINT identity_provider_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    -- An issuer becomes usable only once its verification and its multi-factor
    -- vocabulary are both recorded. Mandatory multi-factor authentication that
    -- nobody can evaluate is not mandatory.
    CONSTRAINT identity_provider_active_readiness_ck
        CHECK (status <> 'ACTIVE'
            OR (verification_state = 'VERIFIED'
                AND mfa_claim_name IS NOT NULL
                AND mfa_claim_value IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- Business role and action taxonomy
-- ---------------------------------------------------------------------------

-- A named business role. Roles are deterministic reference data: the
-- application role reads them and never writes them, so no request path can
-- invent a role that the seeded grant matrix has never been reviewed against.
CREATE TABLE iam.business_role (
    code         text    NOT NULL,
    display_name text    NOT NULL,
    description  text    NOT NULL,
    ordinal      integer NOT NULL,
    CONSTRAINT business_role_pk PRIMARY KEY (code),
    CONSTRAINT business_role_code_ck CHECK (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT business_role_ordinal_uq UNIQUE (ordinal)
);

INSERT INTO iam.business_role (code, display_name, description, ordinal) VALUES
    ('OWNER', 'Owner',
        'Accountable for commercial policy, approvals and enablement decisions.', 1),
    ('OPERATIONS', 'Operations',
        'Runs the daily diagnostic loop, mapping, tasks and price proposals.', 2),
    ('FINANCE', 'Finance',
        'Owns cost, fee and settlement facts and reviews profit evidence.', 3),
    ('READ_ONLY', 'Read only',
        'Reads diagnosis and evidence without changing any operating state.', 4);

-- The closed set of business actions this product authorizes. requires_step_up
-- marks the actions whose consequence is external or financially material, so
-- a session that authenticated hours ago cannot perform them on the strength of
-- that old proof alone.
CREATE TABLE iam.action_scope (
    code            text    NOT NULL,
    display_name    text    NOT NULL,
    description     text    NOT NULL,
    requires_step_up boolean NOT NULL,
    ordinal         integer NOT NULL,
    CONSTRAINT action_scope_pk PRIMARY KEY (code),
    CONSTRAINT action_scope_code_ck CHECK (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT action_scope_ordinal_uq UNIQUE (ordinal)
);

INSERT INTO iam.action_scope (code, display_name, description, requires_step_up, ordinal) VALUES
    ('DIAGNOSTIC_VIEW', 'View diagnosis',
        'Read the priority queue, SKU diagnosis and canonical metrics.', false, 1),
    ('EVIDENCE_VIEW', 'View evidence',
        'Open source-evidence references behind a canonical fact.', false, 2),
    ('MAPPING_RESOLVE', 'Resolve mapping',
        'Confirm, reject or supersede a listing-to-SKU mapping candidate.', false, 3),
    ('INTERNAL_FACT_INTAKE', 'Enter internal facts',
        'Enter or import cost, stock and finance facts.', false, 4),
    ('RECOMMENDATION_MANAGE', 'Manage recommendations',
        'Validate, cancel or expire a recommendation and its tasks.', false, 5),
    ('TASK_ASSIGN', 'Assign tasks',
        'Assign and reassign operating tasks.', false, 6),
    ('PRICE_CHANGE_APPROVE', 'Approve price change',
        'Approve a price command or consume a bounded policy authorization.', true, 7),
    ('COMMERCIAL_POLICY_MANAGE', 'Manage commercial policy',
        'Publish or retire a commercial policy version or scoped override.', true, 8),
    ('COMMAND_RESOLVE', 'Resolve command',
        'Resolve an unknown result, readback mismatch or compensation.', true, 9),
    ('KILL_SWITCH_OPERATE', 'Operate kill switch',
        'Disable or re-enable a write capability scope.', true, 10);

-- The reviewed role-to-action matrix. It is seeded rather than administered so
-- that widening a role is a migration that review can see, not a row an
-- operator can add at run time.
CREATE TABLE iam.business_role_action_scope (
    role_code   text NOT NULL,
    action_code text NOT NULL,
    CONSTRAINT business_role_action_scope_pk PRIMARY KEY (role_code, action_code),
    CONSTRAINT business_role_action_scope_role_fk
        FOREIGN KEY (role_code) REFERENCES iam.business_role (code),
    CONSTRAINT business_role_action_scope_action_fk
        FOREIGN KEY (action_code) REFERENCES iam.action_scope (code)
);

INSERT INTO iam.business_role_action_scope (role_code, action_code)
SELECT role_code, action_code
  FROM (VALUES
    ('OWNER', 'DIAGNOSTIC_VIEW'),
    ('OWNER', 'EVIDENCE_VIEW'),
    ('OWNER', 'MAPPING_RESOLVE'),
    ('OWNER', 'INTERNAL_FACT_INTAKE'),
    ('OWNER', 'RECOMMENDATION_MANAGE'),
    ('OWNER', 'TASK_ASSIGN'),
    ('OWNER', 'PRICE_CHANGE_APPROVE'),
    ('OWNER', 'COMMERCIAL_POLICY_MANAGE'),
    ('OWNER', 'COMMAND_RESOLVE'),
    ('OWNER', 'KILL_SWITCH_OPERATE'),
    ('OPERATIONS', 'DIAGNOSTIC_VIEW'),
    ('OPERATIONS', 'EVIDENCE_VIEW'),
    ('OPERATIONS', 'MAPPING_RESOLVE'),
    ('OPERATIONS', 'INTERNAL_FACT_INTAKE'),
    ('OPERATIONS', 'RECOMMENDATION_MANAGE'),
    ('OPERATIONS', 'TASK_ASSIGN'),
    ('OPERATIONS', 'COMMAND_RESOLVE'),
    ('OPERATIONS', 'KILL_SWITCH_OPERATE'),
    ('FINANCE', 'DIAGNOSTIC_VIEW'),
    ('FINANCE', 'EVIDENCE_VIEW'),
    ('FINANCE', 'INTERNAL_FACT_INTAKE'),
    ('FINANCE', 'RECOMMENDATION_MANAGE'),
    ('READ_ONLY', 'DIAGNOSTIC_VIEW'),
    ('READ_ONLY', 'EVIDENCE_VIEW')
  ) AS matrix(role_code, action_code);

-- ---------------------------------------------------------------------------
-- User profile
-- ---------------------------------------------------------------------------

-- The internal profile of one human, bound to one external subject. The subject
-- is the provider's opaque identifier and is the only value that links the two
-- systems; display name and contact address are operator-maintained business
-- attributes, and neither participates in authentication.
--
-- Disabling is a state, not a deletion. A disabled profile keeps its history,
-- its audit attribution and its grants, and every evaluation refuses it.
--
-- credentials_valid_from is what makes a revocation take effect immediately. A
-- token is rejected when it was issued before this instant, so disabling a
-- person stops their existing tokens now rather than when the provider's own
-- expiry eventually catches up.
CREATE TABLE iam.user_account (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    identity_provider_id uuid        NOT NULL,
    external_subject     text        NOT NULL,
    login_hint           text,
    display_name         text        NOT NULL,
    contact_email        text,
    status               text        NOT NULL,
    disabled_reason      text,
    credentials_valid_from timestamptz NOT NULL,
    last_seen_at         timestamptz,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL DEFAULT 0,
    CONSTRAINT user_account_pk PRIMARY KEY (id),
    CONSTRAINT user_account_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT user_account_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT user_account_provider_fk
        FOREIGN KEY (identity_provider_id) REFERENCES iam.identity_provider (id),
    CONSTRAINT user_account_subject_uq UNIQUE (identity_provider_id, external_subject),
    CONSTRAINT user_account_subject_ck
        CHECK (external_subject ~ '^[A-Za-z0-9][A-Za-z0-9._:@|-]{0,254}$'),
    CONSTRAINT user_account_login_hint_ck
        CHECK (login_hint IS NULL OR length(btrim(login_hint)) BETWEEN 1 AND 254),
    CONSTRAINT user_account_contact_email_ck
        CHECK (contact_email IS NULL
            OR contact_email ~ '^[^@[:space:]]{1,64}@[a-z0-9][a-z0-9.-]{0,252}$'),
    CONSTRAINT user_account_status_ck
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    CONSTRAINT user_account_disabled_reason_ck
        CHECK (status = 'ACTIVE' OR disabled_reason IS NOT NULL)
);

CREATE INDEX user_account_organization_ix ON iam.user_account (organization_id, status);
CREATE INDEX user_account_provider_ix ON iam.user_account (identity_provider_id);

-- ---------------------------------------------------------------------------
-- Role assignment and scope grant
-- ---------------------------------------------------------------------------

-- One role held by one user over one half-open interval. Two active intervals
-- of the same user and role cannot overlap, so "when did this person hold this
-- role" always has one answer.
CREATE TABLE iam.user_role_assignment (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    role_code       text        NOT NULL,
    effective_from  timestamptz NOT NULL,
    effective_to    timestamptz,
    status          text        NOT NULL,
    reason          text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT user_role_assignment_pk PRIMARY KEY (id),
    CONSTRAINT user_role_assignment_user_fk
        FOREIGN KEY (user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT user_role_assignment_role_fk
        FOREIGN KEY (role_code) REFERENCES iam.business_role (code),
    CONSTRAINT user_role_assignment_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT user_role_assignment_status_ck CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT user_role_assignment_reason_ck
        CHECK (status = 'ACTIVE' OR reason IS NOT NULL),
    CONSTRAINT user_role_assignment_no_overlap
        EXCLUDE USING gist (
            user_id WITH =,
            role_code WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

CREATE INDEX user_role_assignment_user_ix
    ON iam.user_role_assignment (user_id, status, effective_from);

-- One action allowed on one resource for one user over one interval. Exactly
-- one resource reference is set and every reference carries the grant's own
-- organization, so a grant cannot reach across organizations regardless of
-- which client wrote it.
CREATE TABLE iam.user_scope_grant (
    id                         uuid        NOT NULL,
    organization_id            uuid        NOT NULL,
    user_id                    uuid        NOT NULL,
    action_code                text        NOT NULL,
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
    CONSTRAINT user_scope_grant_pk PRIMARY KEY (id),
    CONSTRAINT user_scope_grant_user_fk
        FOREIGN KEY (user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT user_scope_grant_action_fk
        FOREIGN KEY (action_code) REFERENCES iam.action_scope (code),
    CONSTRAINT user_scope_grant_one_resource_ck
        CHECK (num_nonnulls(
            organization_ref_id, legal_entity_ref_id, marketplace_account_ref_id,
            store_ref_id, warehouse_ref_id) = 1),
    CONSTRAINT user_scope_grant_own_org_ck
        CHECK (organization_ref_id IS NULL OR organization_ref_id = organization_id),
    CONSTRAINT user_scope_grant_org_ref_fk
        FOREIGN KEY (organization_ref_id) REFERENCES core.organization (id),
    CONSTRAINT user_scope_grant_legal_entity_ref_fk
        FOREIGN KEY (legal_entity_ref_id, organization_id)
        REFERENCES core.legal_entity (id, organization_id),
    CONSTRAINT user_scope_grant_account_ref_fk
        FOREIGN KEY (marketplace_account_ref_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT user_scope_grant_store_ref_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT user_scope_grant_warehouse_ref_fk
        FOREIGN KEY (warehouse_ref_id, organization_id)
        REFERENCES core.warehouse (id, organization_id),
    CONSTRAINT user_scope_grant_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT user_scope_grant_status_ck CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT user_scope_grant_reason_ck
        CHECK (status = 'ACTIVE' OR reason IS NOT NULL)
);

CREATE UNIQUE INDEX user_scope_grant_active_uq
    ON iam.user_scope_grant (
        user_id, action_code, organization_ref_id, legal_entity_ref_id,
        marketplace_account_ref_id, store_ref_id, warehouse_ref_id)
    NULLS NOT DISTINCT
    WHERE status = 'ACTIVE';

CREATE INDEX user_scope_grant_user_ix ON iam.user_scope_grant (user_id, status);
CREATE INDEX user_scope_grant_store_ix
    ON iam.user_scope_grant (store_ref_id, action_code)
    WHERE store_ref_id IS NOT NULL AND status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Authentication and authorization journal
-- ---------------------------------------------------------------------------

-- Append-only record of what the identity boundary decided. It exists next to
-- the metadata audit journal rather than inside it because these rows are
-- produced by request authentication, which has no business entity to attribute
-- a change to and which must stay writable when no business mutation occurs.
--
-- No token, no claim payload and no factor secret is stored. The columns are a
-- decision, its reason, the subject that was presented and the digest of the
-- session identifier, which is enough to correlate a report with a provider log
-- without holding anything replayable.
CREATE TABLE iam.identity_decision_event (
    id                   uuid        NOT NULL,
    occurred_at          timestamptz NOT NULL DEFAULT now(),
    identity_provider_id uuid,
    issuer               text        NOT NULL,
    subject_digest       text        NOT NULL,
    session_digest       text,
    user_id              uuid,
    decision             text        NOT NULL,
    denial_code          text,
    action_code          text,
    resource_type        text,
    resource_id          uuid,
    authenticated_at     timestamptz,
    multi_factor_present boolean     NOT NULL,
    correlation_id       text        NOT NULL,
    CONSTRAINT identity_decision_event_pk PRIMARY KEY (id),
    CONSTRAINT identity_decision_event_provider_fk
        FOREIGN KEY (identity_provider_id) REFERENCES iam.identity_provider (id),
    CONSTRAINT identity_decision_event_user_fk
        FOREIGN KEY (user_id) REFERENCES iam.user_account (id),
    CONSTRAINT identity_decision_event_action_fk
        FOREIGN KEY (action_code) REFERENCES iam.action_scope (code),
    CONSTRAINT identity_decision_event_decision_ck
        CHECK (decision IN ('AUTHENTICATED', 'AUTHORIZED', 'DENIED', 'STEP_UP_REQUIRED')),
    -- Every refusal names why. A refusal without a stable reason is an
    -- unreadable audit trail at the moment somebody needs to read it.
    CONSTRAINT identity_decision_event_denial_ck
        CHECK (decision NOT IN ('DENIED', 'STEP_UP_REQUIRED') OR denial_code IS NOT NULL),
    -- The digests are what makes the row correlatable without being replayable.
    CONSTRAINT identity_decision_event_subject_digest_ck
        CHECK (subject_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_decision_event_session_digest_ck
        CHECK (session_digest IS NULL OR session_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX identity_decision_event_user_ix
    ON iam.identity_decision_event (user_id, occurred_at DESC);
CREATE INDEX identity_decision_event_decision_ix
    ON iam.identity_decision_event (decision, occurred_at DESC);
CREATE INDEX identity_decision_event_subject_ix
    ON iam.identity_decision_event (subject_digest, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- One audit authority
-- ---------------------------------------------------------------------------

-- The metadata audit journal is the single append-only record of attributable
-- change for every module. Widening its closed vocabularies keeps that one
-- authority rather than letting each module grow a journal of its own.
ALTER TABLE ops.metadata_audit_event
    DROP CONSTRAINT metadata_audit_event_source_domain_ck,
    ADD CONSTRAINT metadata_audit_event_source_domain_ck
        CHECK (source_domain IN (
            'organizationaccount', 'identityaccess',
            'marketplaceintegration', 'adminobservability',
            'productlisting', 'operatingfacts',
            'analyticsdecision', 'aicopilot', 'operationsworkflow'));

ALTER TABLE ops.metadata_audit_event
    DROP CONSTRAINT metadata_audit_event_action_ck,
    ADD CONSTRAINT metadata_audit_event_action_ck
        CHECK (action IN (
            'CREATE', 'UPDATE', 'STATUS_CHANGE', 'GRANT', 'REVOKE',
            'VERIFICATION_CHANGE', 'DENIED',
            'IMPORT', 'MAPPING_DECISION', 'APPROVAL_DECISION',
            'POLICY_CHANGE', 'COMMAND_TRANSITION', 'KILL_SWITCH',
            'AI_INVOCATION', 'EXPORT'));

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Human identity decides what a person may do in this product. It is disjoint
-- from the acquisition call authority, whose subject is a Service Account and
-- whose control facts are the Job, credential and registry rows the epoch
-- mechanism already guards. Routing these tables would advance every Job epoch
-- when an operator's role changed, invalidating in-flight acquisitions for a
-- change that cannot affect one.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('iam', 'identity_provider', 'NO_ROUTE', NULL,
        'human authentication boundary; no acquisition authority reads it'),
    ('iam', 'business_role', 'NO_ROUTE', NULL,
        'human role vocabulary; no acquisition authority reads it'),
    ('iam', 'action_scope', 'NO_ROUTE', NULL,
        'human action vocabulary; no acquisition authority reads it'),
    ('iam', 'business_role_action_scope', 'NO_ROUTE', NULL,
        'reviewed human role matrix; no acquisition authority reads it'),
    ('iam', 'user_account', 'NO_ROUTE', NULL,
        'human profile; the acquisition subject is a service account'),
    ('iam', 'user_role_assignment', 'NO_ROUTE', NULL,
        'human role grant; the acquisition subject is a service account'),
    ('iam', 'user_scope_grant', 'NO_ROUTE', NULL,
        'human scope grant; the acquisition subject is a service account'),
    ('iam', 'identity_decision_event', 'NO_ROUTE', NULL,
        'append-only evidence; it records decisions and never decides one');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Reference taxonomies are read-only: the reviewed role matrix cannot be
-- widened by a running process. Profile and grant tables accept inserts and
-- versioned updates; the decision journal accepts inserts and reads. No DELETE
-- is granted anywhere, so revocation stays a recorded transition.
GRANT SELECT ON iam.business_role TO marketops_app;
GRANT SELECT ON iam.action_scope TO marketops_app;
GRANT SELECT ON iam.business_role_action_scope TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON iam.identity_provider TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON iam.user_account TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON iam.user_role_assignment TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON iam.user_scope_grant TO marketops_app;
GRANT SELECT, INSERT ON iam.identity_decision_event TO marketops_app;
