-- Commercial policy, the deterministic guardrails derived from it, the bounded
-- authorizations an Owner may pre-grant, and the allowlist that limits which
-- entities a real write may ever touch.
--
-- Policy is versioned and effective-dated, never edited. A guardrail evaluation
-- names the exact policy version it applied, so the question "under which rules
-- was this authorised" has one answer months later.
--
-- Every guardrail defaults to deny. A limit that is not configured is not an
-- absent restriction; it is a missing decision, and a missing decision blocks
-- execution. That is why an unconfigured limit produces a reason code rather
-- than a pass.
--
-- A bounded authorization can be spent, and spending it is a database
-- operation, not an application intention. The counter is protected by column
-- privilege and advanced by one function, so two concurrent approvals cannot
-- both consume the last remaining use.

-- ---------------------------------------------------------------------------
-- Limit taxonomy
-- ---------------------------------------------------------------------------

CREATE TABLE ops.policy_limit_kind (
    code            text    NOT NULL,
    display_name    text    NOT NULL,
    value_kind      text    NOT NULL,
    guardrail_code  text    NOT NULL,
    required_for_price_write boolean NOT NULL,
    ordinal         integer NOT NULL,
    CONSTRAINT policy_limit_kind_pk PRIMARY KEY (code),
    CONSTRAINT policy_limit_kind_code_ck CHECK (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT policy_limit_kind_value_ck
        CHECK (value_kind IN ('RATE', 'AMOUNT', 'COUNT', 'DURATION_SECONDS')),
    CONSTRAINT policy_limit_kind_guardrail_ck
        CHECK (guardrail_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT policy_limit_kind_ordinal_uq UNIQUE (ordinal)
);

INSERT INTO ops.policy_limit_kind
    (code, display_name, value_kind, guardrail_code, required_for_price_write, ordinal) VALUES
    ('MIN_DATA_COMPLETENESS', 'Minimum data completeness', 'RATE',
        'DATA_COMPLETENESS_BELOW_MINIMUM', true, 1),
    ('MAX_INPUT_AGE_SECONDS', 'Maximum input age', 'DURATION_SECONDS',
        'INPUT_TOO_STALE', true, 2),
    ('MIN_CONTRIBUTION_MARGIN', 'Minimum contribution margin', 'RATE',
        'MARGIN_BELOW_MINIMUM', true, 3),
    ('MIN_UNIT_CONTRIBUTION_PROFIT', 'Minimum unit contribution profit', 'AMOUNT',
        'UNIT_PROFIT_BELOW_MINIMUM', true, 4),
    ('MAX_SINGLE_CHANGE_RATE', 'Maximum single change', 'RATE',
        'SINGLE_CHANGE_TOO_LARGE', true, 5),
    ('MAX_DAILY_CHANGE_RATE', 'Maximum cumulative daily change', 'RATE',
        'DAILY_CHANGE_EXCEEDED', true, 6),
    ('COOLDOWN_SECONDS', 'Cooldown between changes', 'DURATION_SECONDS',
        'COOLDOWN_ACTIVE', true, 7),
    ('MIN_AVAILABLE_UNITS', 'Minimum available units', 'COUNT',
        'INVENTORY_BELOW_MINIMUM', true, 8),
    ('MAX_POLICY_AUTHORIZED_CHANGE_RATE', 'Maximum policy-authorized change', 'RATE',
        'CHANGE_EXCEEDS_POLICY_AUTHORIZATION', false, 9);

-- ---------------------------------------------------------------------------
-- Commercial policy
-- ---------------------------------------------------------------------------

-- One published policy version at one scope. The lifecycle objective is part of
-- the policy rather than of the product, because the same product can be a
-- growth item in one store and an exit item in another.
CREATE TABLE ops.commercial_policy (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    policy_code            text        NOT NULL,
    policy_version         integer     NOT NULL,
    scope_kind             text        NOT NULL,
    platform_code          text,
    store_ref_id           uuid,
    product_variant_ref_id uuid,
    lifecycle_objective    text        NOT NULL,
    currency_code          text        NOT NULL,
    effective_from         timestamptz NOT NULL,
    effective_to           timestamptz,
    status                 text        NOT NULL,
    published_by_user_id   uuid        NOT NULL,
    reason                 text        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT commercial_policy_pk PRIMARY KEY (id),
    CONSTRAINT commercial_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT commercial_policy_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT commercial_policy_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT commercial_policy_variant_fk
        FOREIGN KEY (product_variant_ref_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT commercial_policy_user_fk
        FOREIGN KEY (published_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT commercial_policy_code_uq
        UNIQUE (organization_id, policy_code, policy_version),
    CONSTRAINT commercial_policy_code_ck
        CHECK (policy_code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    CONSTRAINT commercial_policy_version_ck CHECK (policy_version > 0),
    CONSTRAINT commercial_policy_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE', 'PRODUCT_VARIANT')),
    CONSTRAINT commercial_policy_scope_matrix_ck CHECK (
        (scope_kind = 'ORGANIZATION'
            AND num_nonnulls(platform_code, store_ref_id, product_variant_ref_id) = 0)
        OR (scope_kind = 'PLATFORM' AND platform_code IS NOT NULL
            AND num_nonnulls(store_ref_id, product_variant_ref_id) = 0)
        OR (scope_kind = 'STORE' AND store_ref_id IS NOT NULL
            AND num_nonnulls(platform_code, product_variant_ref_id) = 0)
        OR (scope_kind = 'PRODUCT_VARIANT' AND product_variant_ref_id IS NOT NULL
            AND num_nonnulls(platform_code, store_ref_id) = 0)),
    CONSTRAINT commercial_policy_objective_ck
        CHECK (lifecycle_objective IN ('HERO', 'GROWTH', 'MATURE', 'REPAIR', 'EXIT')),
    CONSTRAINT commercial_policy_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT commercial_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT commercial_policy_status_ck
        CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    -- Two active policies at the same scope over the same instant would make
    -- the applicable rule set a coin toss.
    CONSTRAINT commercial_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, product_variant_ref_id,
                     '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

CREATE INDEX commercial_policy_scope_ix
    ON ops.commercial_policy (organization_id, scope_kind, status, effective_from DESC);

CREATE TABLE ops.commercial_policy_limit (
    id            uuid           NOT NULL,
    policy_id     uuid           NOT NULL,
    limit_code    text           NOT NULL,
    rate_value    numeric(9, 6),
    amount_value  numeric(18, 4),
    count_value   integer,
    duration_seconds bigint,
    CONSTRAINT commercial_policy_limit_pk PRIMARY KEY (id),
    CONSTRAINT commercial_policy_limit_policy_fk
        FOREIGN KEY (policy_id) REFERENCES ops.commercial_policy (id),
    CONSTRAINT commercial_policy_limit_kind_fk
        FOREIGN KEY (limit_code) REFERENCES ops.policy_limit_kind (code),
    CONSTRAINT commercial_policy_limit_uq UNIQUE (policy_id, limit_code),
    -- Exactly one typed value is set. A limit stored in the wrong column would
    -- read as unconfigured, and an unconfigured limit denies rather than passes,
    -- so the failure would be silent refusals nobody can explain.
    CONSTRAINT commercial_policy_limit_one_value_ck
        CHECK (num_nonnulls(rate_value, amount_value, count_value, duration_seconds) = 1),
    CONSTRAINT commercial_policy_limit_rate_ck
        CHECK (rate_value IS NULL OR (rate_value >= 0 AND rate_value <= 1)),
    CONSTRAINT commercial_policy_limit_amount_ck
        CHECK (amount_value IS NULL OR amount_value >= 0),
    CONSTRAINT commercial_policy_limit_count_ck
        CHECK (count_value IS NULL OR count_value >= 0),
    CONSTRAINT commercial_policy_limit_duration_ck
        CHECK (duration_seconds IS NULL OR duration_seconds >= 0)
);

CREATE INDEX commercial_policy_limit_policy_ix
    ON ops.commercial_policy_limit (policy_id, limit_code);

-- ---------------------------------------------------------------------------
-- Bounded Owner authorization
-- ---------------------------------------------------------------------------

-- A standing permission to execute a low-risk action without a per-action
-- approval, bounded in scope, magnitude, time and number of uses. It is the
-- only mechanism by which a command can proceed without a person deciding it,
-- and each of its bounds is enforced by the consuming function below.
CREATE TABLE ops.policy_authorization (
    id                   uuid           NOT NULL,
    organization_id      uuid           NOT NULL,
    policy_id            uuid           NOT NULL,
    action_kind          text           NOT NULL,
    scope_kind           text           NOT NULL,
    store_ref_id         uuid,
    product_variant_ref_id uuid,
    max_change_rate      numeric(9, 6)  NOT NULL,
    max_uses             integer        NOT NULL,
    used_count           integer        NOT NULL DEFAULT 0,
    valid_from           timestamptz    NOT NULL,
    valid_until          timestamptz    NOT NULL,
    status               text           NOT NULL,
    granted_by_user_id   uuid           NOT NULL,
    reason               text           NOT NULL,
    revoked_reason       text,
    created_at           timestamptz    NOT NULL,
    updated_at           timestamptz    NOT NULL,
    version              bigint         NOT NULL DEFAULT 0,
    CONSTRAINT policy_authorization_pk PRIMARY KEY (id),
    CONSTRAINT policy_authorization_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT policy_authorization_policy_fk
        FOREIGN KEY (policy_id) REFERENCES ops.commercial_policy (id),
    CONSTRAINT policy_authorization_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT policy_authorization_variant_fk
        FOREIGN KEY (product_variant_ref_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT policy_authorization_user_fk
        FOREIGN KEY (granted_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT policy_authorization_action_ck CHECK (action_kind = 'PRICE_CHANGE'),
    CONSTRAINT policy_authorization_scope_ck
        CHECK (scope_kind IN ('STORE', 'PRODUCT_VARIANT')),
    CONSTRAINT policy_authorization_scope_matrix_ck CHECK (
        (scope_kind = 'STORE' AND store_ref_id IS NOT NULL
            AND product_variant_ref_id IS NULL)
        OR (scope_kind = 'PRODUCT_VARIANT' AND product_variant_ref_id IS NOT NULL
            AND store_ref_id IS NULL)),
    CONSTRAINT policy_authorization_rate_ck
        CHECK (max_change_rate > 0 AND max_change_rate <= 1),
    CONSTRAINT policy_authorization_uses_ck
        CHECK (max_uses > 0 AND used_count >= 0 AND used_count <= max_uses),
    CONSTRAINT policy_authorization_validity_ck CHECK (valid_from < valid_until),
    CONSTRAINT policy_authorization_status_ck
        CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT policy_authorization_revoked_ck
        CHECK (status <> 'REVOKED' OR revoked_reason IS NOT NULL)
);

CREATE INDEX policy_authorization_scope_ix
    ON ops.policy_authorization (organization_id, action_kind, status, valid_until);

ALTER TABLE ops.approval_decision
    ADD CONSTRAINT approval_decision_policy_authorization_fk
        FOREIGN KEY (policy_authorization_id) REFERENCES ops.policy_authorization (id);

-- Spend one use of a bounded authorization, or refuse.
--
-- The row is locked first and every bound is rechecked against the database's
-- own clock and the row's committed counter, so two approvals racing for the
-- last remaining use cannot both win. The counter has no UPDATE privilege
-- outside this function, which is what makes that guarantee structural rather
-- than a property of well-written callers.
CREATE FUNCTION ops.consume_policy_authorization(
    p_authorization_id uuid,
    p_change_rate      numeric,
    p_store_id         uuid,
    p_variant_id       uuid)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    authorization_row record;
    remaining_uses    integer;
BEGIN
    SELECT grant_row.id, grant_row.status, grant_row.max_change_rate,
           grant_row.max_uses, grant_row.used_count, grant_row.valid_from,
           grant_row.valid_until, grant_row.scope_kind, grant_row.store_ref_id,
           grant_row.product_variant_ref_id
      INTO authorization_row
      FROM ops.policy_authorization AS grant_row
     WHERE grant_row.id = p_authorization_id
       FOR UPDATE OF grant_row;

    IF authorization_row.id IS NULL THEN
        RAISE EXCEPTION 'policy authorization % does not exist', p_authorization_id
            USING ERRCODE = 'MO020';
    END IF;

    IF authorization_row.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'policy authorization % is %',
            p_authorization_id, authorization_row.status
            USING ERRCODE = 'MO021';
    END IF;

    IF clock_timestamp() < authorization_row.valid_from
        OR clock_timestamp() >= authorization_row.valid_until THEN
        RAISE EXCEPTION 'policy authorization % is outside its validity window',
            p_authorization_id
            USING ERRCODE = 'MO021';
    END IF;

    IF p_change_rate IS NULL OR p_change_rate > authorization_row.max_change_rate THEN
        RAISE EXCEPTION
            'requested change exceeds the bound of policy authorization %',
            p_authorization_id
            USING ERRCODE = 'MO022';
    END IF;

    IF authorization_row.scope_kind = 'STORE'
        AND authorization_row.store_ref_id IS DISTINCT FROM p_store_id THEN
        RAISE EXCEPTION 'policy authorization % does not cover this store',
            p_authorization_id
            USING ERRCODE = 'MO023';
    END IF;

    IF authorization_row.scope_kind = 'PRODUCT_VARIANT'
        AND authorization_row.product_variant_ref_id IS DISTINCT FROM p_variant_id THEN
        RAISE EXCEPTION 'policy authorization % does not cover this variant',
            p_authorization_id
            USING ERRCODE = 'MO023';
    END IF;

    IF authorization_row.used_count >= authorization_row.max_uses THEN
        RAISE EXCEPTION 'policy authorization % has no remaining uses',
            p_authorization_id
            USING ERRCODE = 'MO024';
    END IF;

    UPDATE ops.policy_authorization AS grant_row
       SET used_count = grant_row.used_count + 1,
           status = CASE
                        WHEN grant_row.used_count + 1 >= grant_row.max_uses
                            THEN 'EXHAUSTED'
                        ELSE grant_row.status
                    END,
           updated_at = clock_timestamp(),
           version = grant_row.version + 1
     WHERE grant_row.id = p_authorization_id
       AND grant_row.used_count = authorization_row.used_count
    RETURNING grant_row.max_uses - grant_row.used_count INTO remaining_uses;

    IF remaining_uses IS NULL THEN
        RAISE EXCEPTION 'policy authorization % changed while being consumed',
            p_authorization_id
            USING ERRCODE = 'MO024';
    END IF;

    RETURN remaining_uses;
END;
$$;

REVOKE ALL ON FUNCTION ops.consume_policy_authorization(uuid, numeric, uuid, uuid)
    FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Guardrail evaluation
-- ---------------------------------------------------------------------------

-- The deterministic verdict for one proposed action, recorded whether it passed
-- or blocked. Blocking reasons are stored as a set of codes rather than a
-- message so the operating surface, the tests and the runbooks all name the
-- same conditions.
CREATE TABLE ops.guardrail_evaluation (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    recommendation_id    uuid        NOT NULL,
    policy_id            uuid,
    policy_version       integer,
    purpose              text        NOT NULL,
    outcome              text        NOT NULL,
    reason_codes         text[]      NOT NULL,
    detail               jsonb       NOT NULL,
    input_digest         text        NOT NULL,
    evaluated_at         timestamptz NOT NULL,
    correlation_id       text        NOT NULL,
    CONSTRAINT guardrail_evaluation_pk PRIMARY KEY (id),
    CONSTRAINT guardrail_evaluation_recommendation_fk
        FOREIGN KEY (recommendation_id) REFERENCES ops.recommendation (id),
    CONSTRAINT guardrail_evaluation_policy_fk
        FOREIGN KEY (policy_id) REFERENCES ops.commercial_policy (id),
    CONSTRAINT guardrail_evaluation_purpose_ck
        CHECK (purpose IN ('IMPACT_PREVIEW', 'APPROVAL', 'EXECUTION')),
    CONSTRAINT guardrail_evaluation_outcome_ck CHECK (outcome IN ('PASS', 'BLOCK')),
    -- A pass names no reason and a block always does. An empty block would be
    -- an unexplained refusal at the moment somebody needs the explanation.
    CONSTRAINT guardrail_evaluation_reasons_ck
        CHECK ((outcome = 'PASS') = (cardinality(reason_codes) = 0)),
    CONSTRAINT guardrail_evaluation_detail_ck CHECK (jsonb_typeof(detail) = 'object'),
    CONSTRAINT guardrail_evaluation_digest_ck CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    -- A pass must name the policy version it passed under. A block can occur
    -- because no policy applies at all, which is itself a recorded reason.
    CONSTRAINT guardrail_evaluation_policy_presence_ck
        CHECK (outcome = 'BLOCK'
            OR (policy_id IS NOT NULL AND policy_version IS NOT NULL))
);

CREATE INDEX guardrail_evaluation_recommendation_ix
    ON ops.guardrail_evaluation (recommendation_id, evaluated_at DESC);
CREATE INDEX guardrail_evaluation_outcome_ix
    ON ops.guardrail_evaluation (organization_id, outcome, evaluated_at DESC);

-- ---------------------------------------------------------------------------
-- Pilot allowlist
-- ---------------------------------------------------------------------------

-- The exact entities a real platform write may touch. The allowlist is a
-- positive list: an entity that is not on it is not eligible, so widening real
-- exposure is always an explicit, attributed act with a stated window.
CREATE TABLE ops.pilot_allowlist_entry (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    capability_code             text        NOT NULL,
    platform_code               text        NOT NULL,
    store_id                    uuid        NOT NULL,
    platform_listing_variant_id uuid,
    valid_from                  timestamptz NOT NULL,
    valid_until                 timestamptz NOT NULL,
    status                      text        NOT NULL,
    granted_by_user_id          uuid        NOT NULL,
    reason                      text        NOT NULL,
    revoked_reason              text,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    version                     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pilot_allowlist_entry_pk PRIMARY KEY (id),
    CONSTRAINT pilot_allowlist_entry_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT pilot_allowlist_entry_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT pilot_allowlist_entry_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT pilot_allowlist_entry_user_fk
        FOREIGN KEY (granted_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT pilot_allowlist_entry_capability_ck CHECK (capability_code = 'PRICE_CHANGE'),
    CONSTRAINT pilot_allowlist_entry_validity_ck CHECK (valid_from < valid_until),
    CONSTRAINT pilot_allowlist_entry_status_ck
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    CONSTRAINT pilot_allowlist_entry_revoked_ck
        CHECK (status <> 'REVOKED' OR revoked_reason IS NOT NULL)
);

CREATE UNIQUE INDEX pilot_allowlist_entry_live_uq
    ON ops.pilot_allowlist_entry (
        capability_code, store_id,
        coalesce(platform_listing_variant_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'ACTIVE';

CREATE INDEX pilot_allowlist_entry_lookup_ix
    ON ops.pilot_allowlist_entry (organization_id, capability_code, status, valid_until);

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Policy governs the write path. It is evaluated by the price write gate at
-- the moment a command is leased, which is a separate authority from the
-- acquisition call authority the epoch mechanism protects.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'policy_limit_kind', 'NO_ROUTE', NULL,
        'guardrail vocabulary; no acquisition authority reads it'),
    ('ops', 'commercial_policy', 'NO_ROUTE', NULL,
        'write-path policy; evaluated by the price write gate, not call authority'),
    ('ops', 'commercial_policy_limit', 'NO_ROUTE', NULL,
        'write-path limits; evaluated by the price write gate, not call authority'),
    ('ops', 'policy_authorization', 'NO_ROUTE', NULL,
        'bounded write authorization; consumed by its own locking function'),
    ('ops', 'guardrail_evaluation', 'NO_ROUTE', NULL,
        'append-only verdict; no acquisition authority reads it'),
    ('ops', 'pilot_allowlist_entry', 'NO_ROUTE', NULL,
        'write-path allowlist; evaluated by the price write gate at lease time');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- The limit taxonomy is read-only. Policies, authorizations and allowlist
-- entries accept evidence-carrying maintenance; guardrail evaluations are
-- append-only.
--
-- used_count is deliberately outside the column list the application may
-- update. Spending an authorization is possible only through the function
-- above, so the bound holds for an arbitrary SQL client as well as for this
-- application.
GRANT SELECT ON ops.policy_limit_kind TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.commercial_policy TO marketops_app;
GRANT SELECT, INSERT ON ops.commercial_policy_limit TO marketops_app;
GRANT SELECT, INSERT ON ops.policy_authorization TO marketops_app;
GRANT UPDATE (status, revoked_reason, updated_at, version)
    ON ops.policy_authorization TO marketops_app;
GRANT SELECT, INSERT ON ops.guardrail_evaluation TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.pilot_allowlist_entry TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.consume_policy_authorization(uuid, numeric, uuid, uuid)
    TO marketops_app;
