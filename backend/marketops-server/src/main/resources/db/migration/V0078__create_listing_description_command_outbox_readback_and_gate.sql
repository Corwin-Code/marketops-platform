-- SLICE-V1-004: the description write joins the registry-driven execution
-- boundary as the third controlled write.
--
-- One outbox, one reviewed transition set, one attempt record per call with
-- exact response custody, one readback function that derives the match state
-- under the accepted representation-equivalence rule, and one write gate that
-- refuses for every reason the Contract names. A command comes into existence
-- only through ops.create_lc_description_command, from a launched, bound,
-- approved action on the API path. UNKNOWN_REQUIRES_READBACK has no edge back
-- to EXECUTING; a retry needs verified native idempotency or an explicit
-- not-applied proof recorded against the operation, never a timeout, an empty
-- error list, a task identifier or a stale readback.
--
-- No capability, endpoint, operation or gate authority row is created. The
-- production_write_enabled default is false, and no row anywhere sets it.

-- ---------------------------------------------------------------------------
-- Gate authority
-- ---------------------------------------------------------------------------

-- Owner-published, exact-head-bound authority for a real description write.
-- Nothing in this Slice creates one. No Java writes this table.
CREATE TABLE ops.lc_gate_authority (
    id                             uuid        NOT NULL,
    organization_id                uuid        NOT NULL,
    gate_kind                      text        NOT NULL,
    platform_code                  text        NOT NULL,
    store_id                       uuid        NOT NULL,
    capability_code                text        NOT NULL,
    platform_listing_ids           uuid[]      NOT NULL,
    exact_head_sha                 text        NOT NULL,
    exact_tree_sha                 text        NOT NULL,
    owner_user_id                  uuid        NOT NULL,
    approved_at                    timestamptz NOT NULL,
    valid_from                     timestamptz NOT NULL,
    valid_until                    timestamptz NOT NULL,
    max_commands                   integer     NOT NULL,
    evidence_reference             text        NOT NULL,
    controller_verdict_reference   text        NOT NULL,
    security_attestation_reference text        NOT NULL,
    restoration_plan_reference     text        NOT NULL,
    production_write_enabled       boolean     NOT NULL DEFAULT false,
    status                         text        NOT NULL,
    CONSTRAINT lc_gate_authority_pk PRIMARY KEY (id),
    CONSTRAINT lc_gate_authority_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT lc_gate_authority_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT lc_gate_authority_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_gate_authority_owner_fk
        FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_gate_authority_kind_ck CHECK (gate_kind IN ('GATE_EV', 'GATE_E')),
    CONSTRAINT lc_gate_authority_capability_ck CHECK (capability_code = 'listing-description-change'),
    CONSTRAINT lc_gate_authority_listings_ck CHECK (cardinality(platform_listing_ids) > 0),
    CONSTRAINT lc_gate_authority_sha_ck
        CHECK (exact_head_sha ~ '^[0-9a-f]{40}$' AND exact_tree_sha ~ '^[0-9a-f]{40}$'),
    CONSTRAINT lc_gate_authority_window_ck CHECK (valid_until > valid_from),
    CONSTRAINT lc_gate_authority_commands_ck CHECK (max_commands > 0),
    CONSTRAINT lc_gate_authority_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ENDED')),
    CONSTRAINT lc_gate_authority_enabled_ck CHECK (NOT production_write_enabled OR status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- The outbox
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_description_command (
    id                        uuid        NOT NULL,
    organization_id           uuid        NOT NULL,
    recommendation_id         uuid        NOT NULL,
    approval_decision_id      uuid        NOT NULL,
    action_id                 uuid        NOT NULL,
    launch_id                 uuid        NOT NULL,
    binding_id                uuid        NOT NULL,
    store_id                  uuid        NOT NULL,
    platform_listing_id       uuid        NOT NULL,
    platform_code             text        NOT NULL,
    capability_id             uuid        NOT NULL,
    idempotency_key           text        NOT NULL,
    prior_text                text,
    prior_text_digest         text        NOT NULL,
    target_text               text        NOT NULL,
    target_text_digest        text        NOT NULL,
    kiz_marked_declared       boolean     NOT NULL,
    equivalence_rule          text        NOT NULL,
    length_bound_min          integer     NOT NULL,
    length_bound_max          integer     NOT NULL,
    affected_set_digest       text        NOT NULL,
    approval_expires_at       timestamptz NOT NULL,
    requested_operation       text CHECK (requested_operation = 'READBACK'),
    state                     text        NOT NULL,
    attempt_no                integer     NOT NULL DEFAULT 0,
    retry_budget_remaining    integer     NOT NULL,
    fence_token               bigint      NOT NULL DEFAULT 1,
    retry_preflight_fence     bigint,
    lease_owner               text,
    lease_expires_at          timestamptz,
    next_attempt_at           timestamptz,
    failure_code              text,
    terminal_at               timestamptz,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    CONSTRAINT lc_description_command_pk PRIMARY KEY (id),
    CONSTRAINT lc_description_command_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_description_command_recommendation_fk
        FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation (id, organization_id),
    CONSTRAINT lc_description_command_approval_fk
        FOREIGN KEY (approval_decision_id) REFERENCES ops.approval_decision (id),
    CONSTRAINT lc_description_command_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_description_command_action_uq UNIQUE (action_id),
    CONSTRAINT lc_description_command_launch_fk
        FOREIGN KEY (launch_id, organization_id) REFERENCES ops.lc_launch (id, organization_id),
    CONSTRAINT lc_description_command_binding_fk
        FOREIGN KEY (binding_id) REFERENCES ops.lc_action_binding (id),
    CONSTRAINT lc_description_command_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_description_command_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_description_command_capability_fk
        FOREIGN KEY (capability_id, platform_code) REFERENCES platform.platform_capability (id, platform_code),
    CONSTRAINT lc_description_command_idempotency_uq UNIQUE (idempotency_key),
    CONSTRAINT lc_description_command_idempotency_ck
        CHECK (idempotency_key ~ '^[a-z0-9][a-z0-9._-]{15,127}$'),
    -- The prior text is the complete captured text or absent. Absent is never
    -- a space, a placeholder or an empty import; it is RESTORE_UNSUPPORTED.
    CONSTRAINT lc_description_command_prior_ck
        CHECK (prior_text IS NULL OR length(prior_text) >= 1),
    CONSTRAINT lc_description_command_digests_ck
        CHECK (prior_text_digest ~ '^[0-9a-f]{64}$' AND affected_set_digest ~ '^[0-9a-f]{64}$'
            AND target_text_digest = encode(sha256(convert_to(target_text, 'UTF8')), 'hex')),
    CONSTRAINT lc_description_command_change_ck CHECK (target_text_digest <> prior_text_digest),
    CONSTRAINT lc_description_command_rule_ck
        CHECK (equivalence_rule IN ('EXACT', 'WHITESPACE_NORMALIZED')),
    CONSTRAINT lc_description_command_bounds_ck
        CHECK (length_bound_min >= 0 AND length_bound_max >= length_bound_min AND length_bound_max <= 65536),
    CONSTRAINT lc_description_command_state_ck
        CHECK (state IN (
            'PENDING', 'LEASED', 'EXECUTING', 'PLATFORM_PENDING', 'READBACK_PENDING',
            'READBACK_MATCHED', 'RETRY_WAIT', 'UNKNOWN_REQUIRES_READBACK',
            'READBACK_MISMATCH', 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION',
            'MANUAL_RESOLUTION', 'FAILED_FINAL', 'TERMINATED_WITHOUT_PROVIDER_CALL',
            'COMPENSATION_PENDING', 'COMPENSATED', 'COMPENSATION_FAILED')),
    CONSTRAINT lc_description_command_attempt_ck CHECK (attempt_no >= 0),
    CONSTRAINT lc_description_command_retry_budget_ck CHECK (retry_budget_remaining >= 0),
    CONSTRAINT lc_description_command_fence_ck CHECK (fence_token > 0),
    CONSTRAINT lc_description_command_retry_preflight_fence_ck
        CHECK (retry_preflight_fence > 0 AND retry_preflight_fence <= fence_token),
    CONSTRAINT lc_description_command_lease_pairing_ck
        CHECK (num_nonnulls(lease_owner, lease_expires_at) <> 1),
    CONSTRAINT lc_description_command_leased_state_ck
        CHECK (state NOT IN ('LEASED', 'EXECUTING', 'READBACK_PENDING') OR lease_owner IS NOT NULL),
    CONSTRAINT lc_description_command_terminal_ck
        CHECK ((state IN ('READBACK_MATCHED', 'FAILED_FINAL', 'TERMINATED_WITHOUT_PROVIDER_CALL',
                          'COMPENSATED', 'COMPENSATION_FAILED')) = (terminal_at IS NOT NULL)),
    CONSTRAINT lc_description_command_failure_ck
        CHECK (state NOT IN ('FAILED_FINAL', 'COMPENSATION_FAILED', 'TERMINATED_WITHOUT_PROVIDER_CALL')
            OR failure_code IS NOT NULL)
);

CREATE UNIQUE INDEX lc_description_command_live_uq
    ON ops.lc_description_command (platform_listing_id)
    WHERE state NOT IN ('READBACK_MATCHED', 'FAILED_FINAL', 'TERMINATED_WITHOUT_PROVIDER_CALL',
                        'COMPENSATED', 'COMPENSATION_FAILED');
CREATE INDEX lc_description_command_queue_ix
    ON ops.lc_description_command (state, next_attempt_at) WHERE state IN ('PENDING', 'RETRY_WAIT');
CREATE INDEX lc_description_command_store_ix
    ON ops.lc_description_command (store_id, state, created_at DESC);

CREATE TABLE ops.lc_description_command_transition (
    from_state     text    NOT NULL,
    to_state       text    NOT NULL,
    requires_lease boolean NOT NULL,
    releases_lease boolean NOT NULL,
    note           text    NOT NULL,
    CONSTRAINT lc_description_command_transition_pk PRIMARY KEY (from_state, to_state),
    CONSTRAINT lc_description_command_transition_distinct_ck CHECK (from_state <> to_state)
);

INSERT INTO ops.lc_description_command_transition
    (from_state, to_state, requires_lease, releases_lease, note) VALUES
    ('PENDING', 'LEASED', false, false, 'a worker claims the command through the leasing function'),
    ('PENDING', 'TERMINATED_WITHOUT_PROVIDER_CALL', false, false,
        'a containment or kill activated before anything was sent'),
    ('LEASED', 'EXECUTING', true, false, 'the adapter call is about to be made'),
    ('LEASED', 'READBACK_PENDING', true, false,
        'a governed retry must observe the current text at its new lease fence'),
    ('LEASED', 'PENDING', true, true, 'the worker released the claim without calling the platform'),
    ('LEASED', 'UNKNOWN_REQUIRES_READBACK', true, true,
        'an abandoned retry preflight cannot restore permission to submit'),
    ('LEASED', 'TERMINATED_WITHOUT_PROVIDER_CALL', true, true,
        'live pre-transmission revalidation refused the send'),
    ('EXECUTING', 'PLATFORM_PENDING', true, false,
        'the platform accepted the request and reported asynchronous work'),
    ('EXECUTING', 'READBACK_PENDING', true, false,
        'the platform answered synchronously and the text must be read back'),
    ('EXECUTING', 'RETRY_WAIT', true, true, 'a retriable transport or rate-limit condition occurred'),
    ('EXECUTING', 'UNKNOWN_REQUIRES_READBACK', true, true,
        'the call timed out or returned an answer that cannot be classified'),
    ('EXECUTING', 'FAILED_FINAL', true, true, 'the platform rejected the request permanently'),
    ('PLATFORM_PENDING', 'READBACK_PENDING', true, false,
        'the platform reported the asynchronous work as finished'),
    ('PLATFORM_PENDING', 'RETRY_WAIT', true, true, 'the status enquiry is not yet conclusive'),
    ('PLATFORM_PENDING', 'UNKNOWN_REQUIRES_READBACK', true, true,
        'the status enquiry cannot be classified'),
    ('PLATFORM_PENDING', 'FAILED_FINAL', true, true,
        'the platform reported the asynchronous work as rejected'),
    ('READBACK_PENDING', 'READBACK_MATCHED', true, true,
        'a readback observed the exact approved target under the equivalence rule'),
    ('READBACK_PENDING', 'READBACK_MISMATCH', true, true, 'a readback observed the captured prior text'),
    ('READBACK_PENDING', 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION', true, true,
        'a readback observed a third text that nothing in this lineage wrote'),
    ('READBACK_PENDING', 'RETRY_WAIT', true, true, 'the readback is not yet available'),
    ('READBACK_PENDING', 'EXECUTING', true, false,
        'only a retry preflight at this lease fence may authorize governed reentry'),
    ('READBACK_PENDING', 'UNKNOWN_REQUIRES_READBACK', true, true,
        'the readback attempt itself could not be classified'),
    ('RETRY_WAIT', 'LEASED', false, false,
        'the retry delay elapsed and a worker claimed the command again'),
    ('RETRY_WAIT', 'FAILED_FINAL', false, false, 'the retry budget is exhausted'),
    ('RETRY_WAIT', 'MANUAL_RESOLUTION', false, false,
        'an operator took the command out of automatic handling'),
    ('RETRY_WAIT', 'TERMINATED_WITHOUT_PROVIDER_CALL', false, false,
        'a containment or kill activated while the command was waiting'),
    ('UNKNOWN_REQUIRES_READBACK', 'READBACK_PENDING', false, false,
        'a readback attempt is authorised; the write itself is never repeated'),
    ('UNKNOWN_REQUIRES_READBACK', 'MANUAL_RESOLUTION', false, false,
        'an operator took the unresolved command over'),
    ('READBACK_MISMATCH', 'MANUAL_RESOLUTION', false, false, 'an operator took the mismatch over'),
    ('READBACK_MISMATCH', 'COMPENSATION_PENDING', false, false,
        'an operator authorised restoring the captured prior text'),
    ('LATER_CHANGE_OR_MISMATCH_INVESTIGATION', 'MANUAL_RESOLUTION', false, false,
        'an operator took the externally-owned text over; nothing automatic may'),
    ('MANUAL_RESOLUTION', 'COMPENSATION_PENDING', false, false,
        'an operator authorised restoring the captured prior text'),
    ('MANUAL_RESOLUTION', 'READBACK_MATCHED', false, false,
        'an operator confirmed the approved target against a matching readback'),
    ('MANUAL_RESOLUTION', 'FAILED_FINAL', false, false, 'an operator closed the command as failed'),
    ('COMPENSATION_PENDING', 'COMPENSATED', true, true,
        'the captured prior text was restored and read back'),
    ('COMPENSATION_PENDING', 'COMPENSATION_FAILED', true, true, 'the restore could not be completed'),
    ('COMPENSATION_PENDING', 'MANUAL_RESOLUTION', false, true,
        'the restore was withdrawn and returned to an operator');

CREATE TABLE ops.lc_description_command_attempt (
    id                     uuid        NOT NULL,
    command_id             uuid        NOT NULL,
    attempt_no             integer     NOT NULL,
    purpose                text        NOT NULL,
    fence_token            bigint      NOT NULL,
    lease_owner            text        NOT NULL,
    started_at             timestamptz NOT NULL,
    completed_at           timestamptz,
    outcome_class          text        NOT NULL,
    native_status          text,
    native_task_key        text,
    raw_observation_id     uuid,
    error_code             text,
    correlation_id         text        NOT NULL,
    request_digest         text        NOT NULL,
    operation_snapshot     jsonb       NOT NULL,
    expected_version_token text,
    CONSTRAINT lc_description_command_attempt_pk PRIMARY KEY (id),
    CONSTRAINT lc_description_command_attempt_command_fk
        FOREIGN KEY (command_id) REFERENCES ops.lc_description_command (id),
    CONSTRAINT lc_description_command_attempt_no_uq UNIQUE (command_id, attempt_no),
    CONSTRAINT lc_description_command_attempt_no_ck CHECK (attempt_no > 0),
    CONSTRAINT lc_description_command_attempt_purpose_ck
        CHECK (purpose IN ('APPLY', 'STATUS_ENQUIRY', 'READBACK', 'RESTORE')),
    CONSTRAINT lc_description_command_attempt_fence_ck CHECK (fence_token > 0),
    CONSTRAINT lc_description_command_attempt_outcome_ck
        CHECK (outcome_class IN ('IN_FLIGHT', 'ACCEPTED', 'REJECTED', 'RETRIABLE_ERROR',
                                 'TIMEOUT', 'UNKNOWN_STATE')),
    CONSTRAINT lc_description_command_attempt_completion_ck
        CHECK ((outcome_class = 'IN_FLIGHT') = (completed_at IS NULL)),
    CONSTRAINT lc_description_command_attempt_digest_ck CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT lc_description_command_attempt_snapshot_ck
        CHECK (jsonb_typeof(operation_snapshot) = 'object'),
    CONSTRAINT lc_description_command_attempt_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX lc_description_command_attempt_command_ix
    ON ops.lc_description_command_attempt (command_id, started_at DESC);

CREATE TABLE ops.lc_description_command_readback (
    id                   uuid        NOT NULL,
    command_id           uuid        NOT NULL,
    attempt_id           uuid        NOT NULL,
    observed_at          timestamptz NOT NULL,
    observed_text_digest text,
    observed_kiz_marked  boolean,
    match_state          text        NOT NULL,
    raw_observation_id   uuid        NOT NULL,
    correlation_id       text        NOT NULL,
    CONSTRAINT lc_description_command_readback_pk PRIMARY KEY (id),
    CONSTRAINT lc_description_command_readback_command_fk
        FOREIGN KEY (command_id) REFERENCES ops.lc_description_command (id),
    CONSTRAINT lc_description_command_readback_attempt_fk
        FOREIGN KEY (attempt_id) REFERENCES ops.lc_description_command_attempt (id),
    CONSTRAINT lc_description_command_readback_attempt_uq UNIQUE (attempt_id),
    CONSTRAINT lc_description_command_readback_match_ck
        CHECK (match_state IN ('MATCHES_TARGET', 'MATCHES_PRIOR', 'DIFFERENT', 'UNREADABLE')),
    CONSTRAINT lc_description_command_readback_value_ck
        CHECK ((match_state = 'UNREADABLE') = (observed_text_digest IS NULL)),
    CONSTRAINT lc_description_command_readback_digest_ck
        CHECK (observed_text_digest IS NULL OR observed_text_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT lc_description_command_readback_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX lc_description_command_readback_command_ix
    ON ops.lc_description_command_readback (command_id, observed_at DESC);

CREATE TABLE raw.lc_description_response_observation (
    id                  uuid        PRIMARY KEY,
    command_id          uuid        NOT NULL REFERENCES ops.lc_description_command (id),
    attempt_id          uuid        NOT NULL UNIQUE
                                    REFERENCES ops.lc_description_command_attempt (id) ON DELETE CASCADE,
    raw_content_id      uuid        NOT NULL REFERENCES raw.raw_content (id),
    request_digest      text        NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    http_status         integer     NOT NULL CHECK (http_status BETWEEN 100 AND 599),
    response_headers    jsonb       NOT NULL CHECK (jsonb_typeof(response_headers) = 'object'),
    evidence_class      text        NOT NULL
                                    CHECK (evidence_class IN ('PROTOCOL_FIXTURE', 'PROVIDER_RESPONSE')),
    response_complete   boolean     NOT NULL,
    operation_id        uuid,
    operation_version   bigint,
    observed_text_digest text,
    observed_kiz_marked boolean,
    version_token       text,
    observed_at         timestamptz NOT NULL,
    correlation_id      text        NOT NULL
);
ALTER TABLE ops.lc_description_command_attempt ADD CONSTRAINT lc_description_command_attempt_raw_fk
    FOREIGN KEY (raw_observation_id) REFERENCES raw.lc_description_response_observation (id);
ALTER TABLE ops.lc_description_command_readback ADD CONSTRAINT lc_description_command_readback_raw_fk
    FOREIGN KEY (raw_observation_id) REFERENCES raw.lc_description_response_observation (id);

-- ---------------------------------------------------------------------------
-- Representation equivalence
-- ---------------------------------------------------------------------------

-- The accepted rule is applied here and nowhere else. EXACT compares bytes;
-- WHITESPACE_NORMALIZED collapses runs of whitespace and line endings and
-- trims the ends, and nothing more. Neither rule looks inside a word.
CREATE FUNCTION ops.lc_description_digest_under_rule(p_text text, p_rule text)
RETURNS text
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE
        WHEN p_text IS NULL THEN NULL
        WHEN p_rule = 'WHITESPACE_NORMALIZED' THEN
            encode(sha256(convert_to(btrim(regexp_replace(p_text, '\s+', ' ', 'g')), 'UTF8')), 'hex')
        ELSE encode(sha256(convert_to(p_text, 'UTF8')), 'hex')
    END
$$;
REVOKE ALL ON FUNCTION ops.lc_description_digest_under_rule(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_description_digest_under_rule(text, text) TO marketops_app;

-- The frozen operation shape an attempt is opened with and re-derived against
-- before dispatch. Only a verified, active, correctly-functioned operation of
-- the description capability produces one.
CREATE FUNCTION platform.lc_description_operation_snapshot(p_capability uuid, p_operation text)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp
AS $$
    SELECT jsonb_build_object('operation', to_jsonb(op), 'endpoint', to_jsonb(endpoint),
        'writeResultModel', capability.write_result_model, 'capability', to_jsonb(capability),
        'profile', (SELECT to_jsonb(profile) FROM platform.platform_api_profile profile
                     WHERE profile.platform_code = op.platform_code),
        'headers', (SELECT coalesce(jsonb_agg(to_jsonb(header) ORDER BY header.id), '[]'::jsonb)
            FROM platform.platform_auth_header header
            WHERE header.platform_code = op.platform_code
              AND header.credential_purpose = 'CONTENT_WRITE'))
      FROM platform.capability_operation op
      JOIN platform.platform_endpoint endpoint ON endpoint.id = op.endpoint_id
      JOIN platform.platform_capability capability ON capability.id = op.capability_id
     WHERE op.capability_id = p_capability AND op.operation = p_operation
       AND op.status = 'ACTIVE' AND op.verification_state = 'VERIFIED'
       AND endpoint.status = 'ACTIVE' AND endpoint.verification_state = 'VERIFIED'
       AND endpoint.deprecated_at IS NULL
       AND capability.status = 'ACTIVE' AND capability.verification_state = 'VERIFIED'
       AND capability.deprecated_at IS NULL
       AND capability.capability_code = 'listing-description-change' AND capability.read_write_class = 'WRITE'
       AND endpoint.capability_id = op.capability_id
       AND (op.operation <> 'READBACK' OR op.description_observed_text_pointer IS NOT NULL)
       AND (op.operation NOT IN ('APPLY', 'RESTORE') OR op.description_attribute_key IS NOT NULL)
       AND endpoint.operation_function = CASE op.operation
               WHEN 'STATUS_ENQUIRY' THEN 'DESCRIPTION_STATUS' ELSE 'DESCRIPTION_' || op.operation END
       AND ((op.operation IN ('APPLY', 'RESTORE')
             AND endpoint.http_method IN ('POST', 'PUT', 'PATCH')
             AND endpoint.read_write_class = 'WRITE')
         OR (op.operation IN ('STATUS_ENQUIRY', 'READBACK')
             AND endpoint.http_method IN ('GET', 'POST')
             AND endpoint.read_write_class = 'READ'))
$$;
REVOKE ALL ON FUNCTION platform.lc_description_operation_snapshot(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.lc_description_operation_snapshot(uuid, text) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Creating a command from a launched action
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.create_lc_description_command(
    p_action_id uuid, p_actor_id uuid, p_expected_version bigint, p_correlation_id text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, platform, iam, pg_temp
AS $$
DECLARE
    action      ops.lc_action%ROWTYPE;
    binding     ops.lc_action_binding%ROWTYPE;
    launch      ops.lc_launch%ROWTYPE;
    approval    ops.approval_decision%ROWTYPE;
    listing     core.platform_listing%ROWTYPE;
    observation core.lc_description_observation%ROWTYPE;
    capability  uuid;
    existing    uuid;
    command_id  uuid;
    rule        text;
    bounds      jsonb;
    gaps        text[];
BEGIN
    IF p_correlation_id IS NULL OR length(btrim(p_correlation_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'a correlation identifier is required' USING ERRCODE = 'MO092';
    END IF;
    SELECT * INTO action FROM ops.lc_action WHERE id = p_action_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'action does not exist' USING ERRCODE = 'MO090'; END IF;
    IF action.version <> p_expected_version THEN
        RAISE EXCEPTION 'the action changed since it was read' USING ERRCODE = 'MO090';
    END IF;
    SELECT id INTO existing FROM ops.lc_description_command WHERE action_id = p_action_id;
    IF existing IS NOT NULL THEN RETURN existing; END IF;

    IF action.action_kind <> 'LISTING_DESCRIPTION_CHANGE' OR action.execution_path <> 'API'
        OR action.state <> 'LAUNCHED' THEN
        RAISE EXCEPTION 'only a launched description change on the API path becomes a command'
            USING ERRCODE = 'MO092';
    END IF;
    IF NOT ops.lc_actor_holds_action(p_actor_id, action.organization_id, action.store_id, 'LISTING_ACTION_LAUNCH') THEN
        RAISE EXCEPTION 'the actor cannot create a description command here' USING ERRCODE = 'MO092';
    END IF;
    IF action.kiz_marked_declared IS NULL THEN
        RAISE EXCEPTION 'the marking declaration is stated before a description is written'
            USING ERRCODE = 'MO092';
    END IF;
    gaps := ops.lc_binding_gaps(p_action_id);
    IF cardinality(gaps) > 0 THEN
        RAISE EXCEPTION 'the binding no longer applies: %', array_to_string(gaps, ',') USING ERRCODE = 'MO092';
    END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id = p_action_id;
    SELECT * INTO launch FROM ops.lc_launch WHERE action_id = p_action_id;
    SELECT * INTO approval FROM ops.approval_decision WHERE id = binding.approval_decision_id;
    SELECT * INTO listing FROM core.platform_listing WHERE id = action.platform_listing_id;
    SELECT * INTO observation FROM core.lc_description_observation
     WHERE id = action.current_description_observation_id;
    IF observation.text_digest IS DISTINCT FROM binding.current_text_digest THEN
        RAISE EXCEPTION 'the captured prior text is not the bound current text' USING ERRCODE = 'MO092';
    END IF;

    -- The capability must be described. Being verified is the gate's question,
    -- asked at lease and again before any socket; here a command that could
    -- never name a capability is refused rather than created.
    SELECT cap.id INTO capability FROM platform.platform_capability cap
     WHERE cap.platform_code = listing.platform_code AND cap.capability_code = 'listing-description-change';
    IF capability IS NULL THEN
        RAISE EXCEPTION 'no description write capability is described for this platform'
            USING ERRCODE = 'MO092';
    END IF;

    SELECT v.value_text INTO rule FROM core.lc_calibration_value v
     WHERE v.package_id = binding.calibration_package_id AND v.category_code = 'REPRESENTATION_EQUIVALENCE_RULE';
    SELECT v.value_json INTO bounds FROM core.lc_calibration_value v
     WHERE v.package_id = binding.calibration_package_id AND v.category_code = 'DESCRIPTION_LENGTH_RULE';
    IF rule IS NULL OR bounds IS NULL OR (bounds ->> 'min') IS NULL OR (bounds ->> 'max') IS NULL THEN
        RAISE EXCEPTION 'the calibration package does not state the equivalence rule and length bound'
            USING ERRCODE = 'MO092';
    END IF;

    command_id := gen_random_uuid();
    INSERT INTO ops.lc_description_command (
        id, organization_id, recommendation_id, approval_decision_id, action_id, launch_id, binding_id,
        store_id, platform_listing_id, platform_code, capability_id, idempotency_key,
        prior_text, prior_text_digest, target_text, target_text_digest, kiz_marked_declared,
        equivalence_rule, length_bound_min, length_bound_max, affected_set_digest,
        approval_expires_at, state, retry_budget_remaining, created_at, updated_at)
    VALUES (command_id, action.organization_id, action.recommendation_id, approval.id, action.id,
        launch.id, binding.id, action.store_id, action.platform_listing_id, listing.platform_code,
        capability, 'lcd-' || replace(action.id::text, '-', ''),
        nullif(observation.description_text, ''), observation.text_digest,
        action.target_text, action.target_text_digest, action.kiz_marked_declared,
        rule, (bounds ->> 'min')::integer, (bounds ->> 'max')::integer, action.affected_set_digest,
        binding.expires_at, 'PENDING', 3, clock_timestamp(), clock_timestamp());
    RETURN command_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.create_lc_description_command(uuid, uuid, bigint, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.create_lc_description_command(uuid, uuid, bigint, text) TO marketops_app;

-- ---------------------------------------------------------------------------
-- The write gate
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.evaluate_lc_description_write_gate(p_command_id uuid)
RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, platform, iam, pg_temp
AS $$
DECLARE
    command ops.lc_description_command%ROWTYPE;
    reasons text[] := '{}';
    gaps    text[];
    latest  text;
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id;
    IF NOT FOUND THEN RETURN ARRAY['COMMAND_NOT_FOUND']; END IF;

    IF NOT EXISTS (SELECT 1 FROM platform.platform_capability cap
                    WHERE cap.id = command.capability_id
                      AND cap.capability_code = 'listing-description-change'
                      AND cap.read_write_class = 'WRITE'
                      AND cap.verification_state = 'VERIFIED'
                      AND cap.status = 'ACTIVE' AND cap.deprecated_at IS NULL) THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_VERIFIED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.capability_subject_status s
                    WHERE s.capability_id = command.capability_id
                      AND s.store_id = command.store_id AND s.availability = 'AVAILABLE') THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_AVAILABLE_FOR_STORE');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.feature_flag f
                    WHERE f.flag_code = 'listing-description-write'
                      AND f.scope_kind = 'CAPABILITY' AND f.state = 'ENABLED') THEN
        reasons := array_append(reasons, 'CAPABILITY_SWITCH_DISABLED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.feature_flag f
                    WHERE f.flag_code = 'listing-description-write'
                      AND f.scope_kind = 'GLOBAL' AND f.state = 'ENABLED') THEN
        reasons := array_append(reasons, 'GLOBAL_SWITCH_DISABLED');
    END IF;
    IF EXISTS (SELECT 1 FROM platform.feature_flag f
                WHERE f.flag_code = 'listing-description-write'
                  AND f.scope_kind IN ('PLATFORM', 'MARKETPLACE_ACCOUNT', 'STORE')
                  AND f.state = 'DISABLED') THEN
        reasons := array_append(reasons, 'SCOPED_SWITCH_DISABLED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.pilot_allowlist_entry entry
                    WHERE entry.organization_id = command.organization_id
                      AND entry.action_kind = 'LISTING_DESCRIPTION_CHANGE'
                      AND entry.platform_listing_id = command.platform_listing_id
                      AND entry.status = 'ACTIVE'
                      AND entry.valid_from <= statement_timestamp()
                      AND (entry.valid_until IS NULL OR entry.valid_until > statement_timestamp())) THEN
        reasons := array_append(reasons, 'ENTITY_NOT_ALLOWLISTED');
    END IF;
    -- A real write needs an active, exact-head-bound gate authority that names
    -- this listing and has production writes enabled. None exists.
    IF NOT EXISTS (SELECT 1 FROM ops.lc_gate_authority g
                    WHERE g.organization_id = command.organization_id
                      AND g.store_id = command.store_id
                      AND g.status = 'ACTIVE' AND g.production_write_enabled
                      AND command.platform_listing_id = ANY (g.platform_listing_ids)
                      AND g.valid_from <= statement_timestamp()
                      AND g.valid_until > statement_timestamp()) THEN
        reasons := array_append(reasons, 'PRODUCTION_WRITE_DISABLED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.approval_decision a
                    WHERE a.id = command.approval_decision_id
                      AND a.recommendation_id = command.recommendation_id
                      AND a.decision = 'APPROVED'
                      AND a.scope_expires_at > statement_timestamp()) THEN
        reasons := array_append(reasons, 'AUTHORIZATION_INVALID_OR_EXPIRED');
    END IF;
    IF command.approval_expires_at <= statement_timestamp() THEN
        reasons := array_append(reasons, 'APPROVAL_LEASE_EXPIRED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_action a
                    WHERE a.id = command.action_id AND a.state = 'LAUNCHED') THEN
        reasons := array_append(reasons, 'ACTION_NOT_LAUNCHED');
    END IF;
    gaps := ops.lc_binding_gaps(command.action_id);
    IF cardinality(gaps) > 0 THEN
        reasons := reasons || gaps;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_exposure_occupation o
                    WHERE o.action_id = command.action_id AND o.state <> 'RELEASED') THEN
        reasons := array_append(reasons, 'ALLOWANCE_NOT_OCCUPIED');
    END IF;
    IF ops.lc_scope_contained(command.organization_id, command.platform_listing_id) THEN
        reasons := array_append(reasons, 'SCOPE_CONTAINED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.guardrail_evaluation g
                    JOIN ops.lc_action_binding b ON b.action_id = command.action_id
                   WHERE g.recommendation_id = command.recommendation_id
                     AND g.purpose = 'EXECUTION' AND g.outcome = 'PASS'
                     AND g.lc_calibration_package_id = b.calibration_package_id
                     AND g.lc_calibration_version = b.calibration_version) THEN
        reasons := array_append(reasons, 'EXECUTION_PASS_MISSING');
    END IF;
    -- The verified operation names the description attribute it changes, so
    -- the request cannot be a whole-card import. Without it, nothing is sent.
    IF NOT EXISTS (SELECT 1 FROM platform.capability_operation o
                    JOIN platform.platform_endpoint e ON e.id = o.endpoint_id
                   WHERE o.capability_id = command.capability_id AND o.operation = 'APPLY'
                     AND o.status = 'ACTIVE' AND o.verification_state = 'VERIFIED'
                     AND (coalesce(e.path_template, '') || coalesce(e.query_template, '')
                          || o.request_template) LIKE '%{descriptionAttributeKey}%') THEN
        reasons := array_append(reasons, 'NON_TARGET_FIELD_RISK');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_action a
                    WHERE a.id = command.action_id AND a.kiz_marked_declared IS NOT NULL) THEN
        reasons := array_append(reasons, 'KIZ_MARKED_UNDECLARED');
    END IF;
    IF length(command.target_text) NOT BETWEEN command.length_bound_min AND command.length_bound_max THEN
        reasons := array_append(reasons, 'TEXT_LENGTH_OUT_OF_BOUNDS');
    END IF;
    SELECT o.text_digest INTO latest FROM core.lc_description_observation o
     WHERE o.platform_listing_id = command.platform_listing_id
     ORDER BY o.observed_at DESC, o.acquired_at DESC LIMIT 1;
    IF latest IS DISTINCT FROM command.prior_text_digest
        AND command.state IN ('PENDING', 'LEASED', 'RETRY_WAIT') THEN
        reasons := array_append(reasons, 'PRIOR_TEXT_MOVED');
    END IF;
    RETURN reasons;
END;
$$;
REVOKE ALL ON FUNCTION ops.evaluate_lc_description_write_gate(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.evaluate_lc_description_write_gate(uuid) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Retry proof
-- ---------------------------------------------------------------------------

-- A retry of a mutating call is proven only by verified native idempotency
-- on the APPLY endpoint together with a current readback of the prior text at
-- this fence, or by an explicit not-applied answer recorded against the last
-- APPLY. A timeout, an empty error list, a task identifier or a stale readback
-- proves nothing.
CREATE FUNCTION ops.lc_description_retry_is_proven(p_command_id uuid)
RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, platform, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1 FROM ops.lc_description_command c
         WHERE c.id = p_command_id
           AND cardinality(ops.evaluate_lc_description_write_gate(c.id)) = 0
           AND (
             EXISTS (SELECT 1 FROM ops.lc_description_command_attempt a
                      WHERE a.command_id = c.id AND a.purpose = 'APPLY'
                        AND a.attempt_no = (SELECT max(last.attempt_no) FROM ops.lc_description_command_attempt last
                                             WHERE last.command_id = c.id AND last.purpose = 'APPLY')
                        AND a.error_code = 'provider_explicit_not_applied')
             OR (EXISTS (SELECT 1 FROM platform.capability_operation o
                          JOIN platform.platform_endpoint e ON e.id = o.endpoint_id
                         WHERE o.capability_id = c.capability_id AND o.operation = 'APPLY'
                           AND o.status = 'ACTIVE' AND o.verification_state = 'VERIFIED'
                           AND e.idempotency_support = 'YES')
                 AND EXISTS (SELECT 1 FROM ops.lc_description_command_readback rb
                              JOIN ops.lc_description_command_attempt at ON at.id = rb.attempt_id
                             WHERE rb.command_id = c.id AND rb.match_state = 'MATCHES_PRIOR'
                               AND at.fence_token = c.fence_token))))
$$;
REVOKE ALL ON FUNCTION ops.lc_description_retry_is_proven(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_description_retry_is_proven(uuid) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Transitions
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.transition_lc_description_command(
    p_command_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text,
    p_failure_code text, p_retry_delay_seconds integer, p_evidence_id uuid)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE
    command ops.lc_description_command%ROWTYPE;
    edge    ops.lc_description_command_transition%ROWTYPE;
    now_at  timestamptz := clock_timestamp();
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090'; END IF;
    SELECT * INTO edge FROM ops.lc_description_command_transition
     WHERE from_state = command.state AND to_state = p_to_state;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'transition % -> % is not in the reviewed set', command.state, p_to_state
            USING ERRCODE = 'MO091';
    END IF;
    IF edge.requires_lease THEN
        IF command.fence_token <> p_expected_fence
            OR command.lease_owner IS DISTINCT FROM p_expected_lease_owner
            OR command.lease_expires_at IS NULL OR command.lease_expires_at <= now_at THEN
            RAISE EXCEPTION 'the lease that authorised this transition is not current'
                USING ERRCODE = 'MO090';
        END IF;
    END IF;
    IF command.state = 'LEASED' AND p_to_state = 'READBACK_PENDING' THEN
        IF command.retry_preflight_fence IS DISTINCT FROM command.fence_token
            OR NOT EXISTS (SELECT 1 FROM ops.lc_description_command_attempt previous_apply
                            WHERE previous_apply.command_id = p_command_id AND previous_apply.purpose = 'APPLY') THEN
            RAISE EXCEPTION 'a governed retry lease is required for preflight readback' USING ERRCODE = 'MO092';
        END IF;
    END IF;
    IF p_to_state = 'EXECUTING' THEN
        IF (command.state = 'LEASED' AND command.retry_preflight_fence = command.fence_token)
            OR (command.state = 'READBACK_PENDING'
                AND (command.retry_preflight_fence IS DISTINCT FROM command.fence_token
                     OR NOT ops.lc_description_retry_is_proven(p_command_id))) THEN
            RAISE EXCEPTION 'retry requires fresh proof at the current lease fence' USING ERRCODE = 'MO092';
        END IF;
    END IF;
    IF p_to_state = 'READBACK_MATCHED' THEN
        IF NOT EXISTS (SELECT 1 FROM ops.lc_description_command_readback rb
                         JOIN ops.lc_description_command_attempt at ON at.id = rb.attempt_id
                        WHERE rb.id = p_evidence_id AND rb.command_id = p_command_id
                          AND rb.match_state = 'MATCHES_TARGET'
                          AND at.purpose = 'READBACK' AND at.fence_token = command.fence_token
                          AND at.raw_observation_id IS NOT NULL) THEN
            RAISE EXCEPTION 'a matched readback at the current fence is required for success'
                USING ERRCODE = 'MO093';
        END IF;
    END IF;
    IF p_to_state = 'RETRY_WAIT' AND NOT ops.lc_description_retry_is_proven(p_command_id) THEN
        RAISE EXCEPTION 'retry requires readback-first proof and all current authorities' USING ERRCODE = 'MO092';
    END IF;
    IF p_to_state = 'COMPENSATION_PENDING' THEN
        -- Precise restore needs a captured complete prior text, and the text
        -- the platform holds now must be the one this command wrote.
        IF command.prior_text IS NULL THEN
            RAISE EXCEPTION 'RESTORE_UNSUPPORTED: no complete prior text was captured' USING ERRCODE = 'MO094';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM ops.lc_description_command_readback rb
                        WHERE rb.command_id = p_command_id
                          AND rb.observed_at = (SELECT max(latest.observed_at) FROM ops.lc_description_command_readback latest
                                                 WHERE latest.command_id = p_command_id)
                          AND rb.match_state = 'MATCHES_TARGET')
            OR NOT EXISTS (SELECT 1 FROM ops.lc_description_command_attempt at
                            WHERE at.command_id = p_command_id AND at.purpose = 'APPLY'
                              AND at.outcome_class IN ('ACCEPTED', 'UNKNOWN_STATE')) THEN
            RAISE EXCEPTION 'a compensation may not overwrite a text this command did not write'
                USING ERRCODE = 'MO094';
        END IF;
    END IF;
    IF p_to_state IN ('FAILED_FINAL', 'COMPENSATION_FAILED', 'TERMINATED_WITHOUT_PROVIDER_CALL')
        AND p_failure_code IS NULL THEN
        RAISE EXCEPTION 'a terminal failure names its reason' USING ERRCODE = 'MO091';
    END IF;
    UPDATE ops.lc_description_command
       SET state = p_to_state,
           lease_owner = CASE WHEN edge.releases_lease THEN NULL ELSE lease_owner END,
           lease_expires_at = CASE WHEN edge.releases_lease THEN NULL ELSE lease_expires_at END,
           retry_preflight_fence = CASE WHEN edge.releases_lease OR p_to_state = 'EXECUTING'
               THEN NULL ELSE retry_preflight_fence END,
           retry_budget_remaining = CASE WHEN p_to_state = 'RETRY_WAIT'
               THEN greatest(retry_budget_remaining - 1, 0) ELSE retry_budget_remaining END,
           next_attempt_at = CASE WHEN p_to_state = 'RETRY_WAIT'
               THEN now_at + make_interval(secs => coalesce(p_retry_delay_seconds, 60)) ELSE NULL END,
           requested_operation = CASE WHEN p_to_state = 'READBACK_PENDING' THEN NULL ELSE requested_operation END,
           failure_code = coalesce(p_failure_code, failure_code),
           terminal_at = CASE WHEN p_to_state IN ('READBACK_MATCHED', 'FAILED_FINAL',
                                                  'TERMINATED_WITHOUT_PROVIDER_CALL', 'COMPENSATED',
                                                  'COMPENSATION_FAILED') THEN now_at ELSE terminal_at END,
           updated_at = now_at
     WHERE id = p_command_id;
    RETURN p_to_state;
END;
$$;
REVOKE ALL ON FUNCTION ops.transition_lc_description_command(uuid, bigint, text, text, text, integer, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.transition_lc_description_command(uuid, bigint, text, text, text, integer, uuid) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Leasing
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.lease_lc_description_command(p_command_id uuid, p_owner text, p_seconds integer)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE command ops.lc_description_command%ROWTYPE; reasons text[]; fence bigint;
BEGIN
    IF p_owner IS NULL OR length(p_owner) NOT BETWEEN 1 AND 100 OR p_seconds NOT BETWEEN 1 AND 900 THEN
        RAISE EXCEPTION 'lease owner or duration is invalid' USING ERRCODE = 'MO095';
    END IF;
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090'; END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_description_command_transition
                    WHERE from_state = command.state AND to_state = 'LEASED') THEN
        RAISE EXCEPTION 'this command cannot be claimed from %', command.state USING ERRCODE = 'MO091';
    END IF;
    IF command.state = 'RETRY_WAIT' AND (command.next_attempt_at IS NULL
        OR command.next_attempt_at > clock_timestamp() OR command.retry_budget_remaining <= 0) THEN
        RAISE EXCEPTION 'retry delay and remaining budget must permit a new lease' USING ERRCODE = 'MO092';
    END IF;
    reasons := ops.evaluate_lc_description_write_gate(p_command_id);
    IF cardinality(reasons) > 0 THEN
        RAISE EXCEPTION 'the description write gate is closed: %', array_to_string(reasons, ',')
            USING ERRCODE = 'MO092';
    END IF;
    UPDATE ops.lc_description_command
       SET state = 'LEASED', fence_token = fence_token + 1, attempt_no = attempt_no + 1,
           retry_preflight_fence = CASE WHEN command.state = 'RETRY_WAIT' THEN fence_token + 1 ELSE NULL END,
           lease_owner = p_owner, lease_expires_at = clock_timestamp() + make_interval(secs => p_seconds),
           next_attempt_at = NULL, updated_at = clock_timestamp()
     WHERE id = p_command_id
     RETURNING fence_token INTO fence;
    RETURN fence;
END;
$$;
REVOKE ALL ON FUNCTION ops.lease_lc_description_command(uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_lc_description_command(uuid, text, integer) TO marketops_app;

-- A readback-only lease: the only route out of UNKNOWN_REQUIRES_READBACK.
CREATE FUNCTION ops.request_lc_description_readback(p_command_id uuid, p_expected_fence bigint)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    UPDATE ops.lc_description_command
       SET requested_operation = 'READBACK', updated_at = clock_timestamp()
     WHERE id = p_command_id AND fence_token = p_expected_fence
       AND state = 'UNKNOWN_REQUIRES_READBACK';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a readback is requested only on an unknown command at its current fence'
            USING ERRCODE = 'MO091';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION ops.request_lc_description_readback(uuid, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.request_lc_description_readback(uuid, bigint) TO marketops_app;

CREATE FUNCTION ops.lease_lc_description_readback(p_command_id uuid, p_owner text, p_seconds integer)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE command ops.lc_description_command%ROWTYPE; fence bigint;
BEGIN
    IF p_owner IS NULL OR length(p_owner) NOT BETWEEN 1 AND 100 OR p_seconds NOT BETWEEN 1 AND 900 THEN
        RAISE EXCEPTION 'lease owner or duration is invalid' USING ERRCODE = 'MO095';
    END IF;
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090'; END IF;
    IF command.state <> 'UNKNOWN_REQUIRES_READBACK' OR command.requested_operation IS DISTINCT FROM 'READBACK' THEN
        RAISE EXCEPTION 'a readback lease needs a requested readback on an unknown command'
            USING ERRCODE = 'MO091';
    END IF;
    UPDATE ops.lc_description_command
       SET state = 'READBACK_PENDING', fence_token = fence_token + 1, attempt_no = attempt_no + 1,
           lease_owner = p_owner, lease_expires_at = clock_timestamp() + make_interval(secs => p_seconds),
           requested_operation = NULL, updated_at = clock_timestamp()
     WHERE id = p_command_id
     RETURNING fence_token INTO fence;
    RETURN fence;
END;
$$;
REVOKE ALL ON FUNCTION ops.lease_lc_description_readback(uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_lc_description_readback(uuid, text, integer) TO marketops_app;

CREATE FUNCTION ops.lease_lc_description_status(p_command_id uuid, p_owner text, p_seconds integer)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE command ops.lc_description_command%ROWTYPE; fence bigint;
BEGIN
    IF p_owner IS NULL OR length(p_owner) NOT BETWEEN 1 AND 100 OR p_seconds NOT BETWEEN 1 AND 900 THEN
        RAISE EXCEPTION 'lease owner or duration is invalid' USING ERRCODE = 'MO095';
    END IF;
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090'; END IF;
    IF command.state <> 'PLATFORM_PENDING' OR command.lease_owner IS NOT NULL
        OR (command.next_attempt_at IS NOT NULL AND command.next_attempt_at > clock_timestamp()) THEN
        RAISE EXCEPTION 'a status lease needs an unleased pending command whose delay elapsed'
            USING ERRCODE = 'MO091';
    END IF;
    UPDATE ops.lc_description_command
       SET fence_token = fence_token + 1, attempt_no = attempt_no + 1, lease_owner = p_owner,
           lease_expires_at = clock_timestamp() + make_interval(secs => p_seconds),
           next_attempt_at = NULL, updated_at = clock_timestamp()
     WHERE id = p_command_id
     RETURNING fence_token INTO fence;
    RETURN fence;
END;
$$;
REVOKE ALL ON FUNCTION ops.lease_lc_description_status(uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_lc_description_status(uuid, text, integer) TO marketops_app;

-- Release a status or compensation lease without changing state, and defer
-- the next observation. The retry delay is given in seconds after the platform
-- unit was converted by the caller; the database never guesses the unit.
CREATE FUNCTION ops.defer_lc_description_observation(
    p_command_id uuid, p_expected_fence bigint, p_owner text, p_seconds integer)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    UPDATE ops.lc_description_command
       SET lease_owner = NULL, lease_expires_at = NULL,
           next_attempt_at = clock_timestamp() + make_interval(secs => greatest(coalesce(p_seconds, 60), 1)),
           updated_at = clock_timestamp()
     WHERE id = p_command_id AND fence_token = p_expected_fence AND lease_owner = p_owner
       AND state IN ('PLATFORM_PENDING', 'COMPENSATION_PENDING');
    IF NOT FOUND THEN
        RAISE EXCEPTION 'the lease that would be deferred is not current' USING ERRCODE = 'MO090';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION ops.defer_lc_description_observation(uuid, bigint, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.defer_lc_description_observation(uuid, bigint, text, integer) TO marketops_app;

CREATE FUNCTION ops.lease_lc_description_compensation(p_command_id uuid, p_owner text, p_seconds integer)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE command ops.lc_description_command%ROWTYPE; fence bigint;
BEGIN
    IF p_owner IS NULL OR length(p_owner) NOT BETWEEN 1 AND 100 OR p_seconds NOT BETWEEN 1 AND 900 THEN
        RAISE EXCEPTION 'lease owner or duration is invalid' USING ERRCODE = 'MO095';
    END IF;
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090'; END IF;
    IF command.state <> 'COMPENSATION_PENDING' OR command.lease_owner IS NOT NULL THEN
        RAISE EXCEPTION 'a compensation lease needs an unleased pending compensation' USING ERRCODE = 'MO091';
    END IF;
    IF command.prior_text IS NULL THEN
        RAISE EXCEPTION 'RESTORE_UNSUPPORTED: no complete prior text was captured' USING ERRCODE = 'MO094';
    END IF;
    UPDATE ops.lc_description_command
       SET fence_token = fence_token + 1, attempt_no = attempt_no + 1, lease_owner = p_owner,
           lease_expires_at = clock_timestamp() + make_interval(secs => p_seconds),
           next_attempt_at = NULL, updated_at = clock_timestamp()
     WHERE id = p_command_id
     RETURNING fence_token INTO fence;
    RETURN fence;
END;
$$;
REVOKE ALL ON FUNCTION ops.lease_lc_description_compensation(uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_lc_description_compensation(uuid, text, integer) TO marketops_app;

CREATE FUNCTION ops.recover_expired_lc_description_leases()
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE recovered integer := 0;
BEGIN
    WITH expired AS (
        SELECT id, state, retry_preflight_fence FROM ops.lc_description_command
         WHERE lease_expires_at IS NOT NULL AND lease_expires_at <= clock_timestamp()
           AND state IN ('LEASED', 'EXECUTING', 'PLATFORM_PENDING', 'READBACK_PENDING', 'COMPENSATION_PENDING')
         FOR UPDATE SKIP LOCKED)
    UPDATE ops.lc_description_command c
       SET state = CASE expired.state
               WHEN 'LEASED' THEN CASE WHEN expired.retry_preflight_fence IS NOT NULL
                   THEN 'UNKNOWN_REQUIRES_READBACK' ELSE 'PENDING' END
               WHEN 'COMPENSATION_PENDING' THEN 'MANUAL_RESOLUTION'
               ELSE 'UNKNOWN_REQUIRES_READBACK' END,
           lease_owner = NULL, lease_expires_at = NULL, retry_preflight_fence = NULL,
           updated_at = clock_timestamp()
      FROM expired
     WHERE c.id = expired.id;
    GET DIAGNOSTICS recovered = ROW_COUNT;
    RETURN recovered;
END;
$$;
REVOKE ALL ON FUNCTION ops.recover_expired_lc_description_leases() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.recover_expired_lc_description_leases() TO marketops_app;

-- ---------------------------------------------------------------------------
-- Attempts, exact custody and readback
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.open_lc_description_command_attempt(
    p_attempt_id uuid, p_command_id uuid, p_purpose text, p_fence bigint, p_owner text,
    p_request_digest text, p_correlation_id text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, platform, pg_temp
AS $$
DECLARE command ops.lc_description_command%ROWTYPE; shape jsonb; reasons text[]; next_no integer;
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090'; END IF;
    IF command.fence_token <> p_fence OR command.lease_owner IS DISTINCT FROM p_owner
        OR command.lease_expires_at IS NULL OR command.lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'the lease that authorised this attempt is not current' USING ERRCODE = 'MO090';
    END IF;
    IF p_request_digest !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'a request digest is required before a call is made' USING ERRCODE = 'MO090';
    END IF;
    IF p_correlation_id IS NULL OR length(btrim(p_correlation_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'a correlation identifier is required' USING ERRCODE = 'MO090';
    END IF;
    IF NOT ((p_purpose = 'APPLY' AND command.state = 'EXECUTING')
         OR (p_purpose = 'STATUS_ENQUIRY' AND command.state IN ('EXECUTING', 'PLATFORM_PENDING', 'COMPENSATION_PENDING'))
         OR (p_purpose = 'READBACK' AND command.state IN ('READBACK_PENDING', 'COMPENSATION_PENDING'))
         OR (p_purpose = 'RESTORE' AND command.state = 'COMPENSATION_PENDING')) THEN
        RAISE EXCEPTION 'a % attempt cannot be opened from %', p_purpose, command.state USING ERRCODE = 'MO091';
    END IF;
    IF EXISTS (SELECT 1 FROM ops.lc_description_command_attempt a
                WHERE a.command_id = p_command_id AND a.fence_token = p_fence AND a.outcome_class = 'IN_FLIGHT') THEN
        RAISE EXCEPTION 'another attempt is already in flight at this fence' USING ERRCODE = 'MO096';
    END IF;
    -- The gate again, immediately before the call. A mutating attempt also
    -- needs every reason gone; an observation may proceed under some of them.
    reasons := ops.evaluate_lc_description_write_gate(p_command_id);
    IF p_purpose IN ('APPLY', 'RESTORE') AND cardinality(reasons) > 0 THEN
        RAISE EXCEPTION 'the description write gate is closed: %', array_to_string(reasons, ',')
            USING ERRCODE = 'MO092';
    END IF;
    IF p_purpose = 'RESTORE' AND command.prior_text IS NULL THEN
        RAISE EXCEPTION 'RESTORE_UNSUPPORTED: no complete prior text was captured' USING ERRCODE = 'MO094';
    END IF;
    -- The operation shape is frozen into the attempt so completion classifies
    -- against what was called, not against what the registry says later.
    shape := platform.lc_description_operation_snapshot(command.capability_id, p_purpose);
    IF shape IS NULL THEN
        RAISE EXCEPTION 'no verified % operation is recorded for this capability', p_purpose
            USING ERRCODE = 'MO092';
    END IF;
    SELECT coalesce(max(attempt_no), 0) + 1 INTO next_no FROM ops.lc_description_command_attempt
     WHERE command_id = p_command_id;
    INSERT INTO ops.lc_description_command_attempt (id, command_id, attempt_no, purpose, fence_token,
        lease_owner, started_at, outcome_class, correlation_id, request_digest, operation_snapshot)
    VALUES (p_attempt_id, p_command_id, next_no, p_purpose, p_fence, p_owner, clock_timestamp(),
        'IN_FLIGHT', p_correlation_id, p_request_digest, shape);
    RETURN p_attempt_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.open_lc_description_command_attempt(uuid, uuid, text, bigint, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.open_lc_description_command_attempt(uuid, uuid, text, bigint, text, text, text) TO marketops_app;

CREATE FUNCTION ops.complete_lc_description_command_attempt(
    p_id uuid, p_fence bigint, p_owner text, p_outcome text, p_native_status text, p_task text,
    p_error text, p_content uuid, p_body bytea, p_http_status integer, p_headers jsonb,
    p_evidence_class text, p_request_digest text, p_response_complete boolean DEFAULT true)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, platform, raw, pg_temp
AS $$
DECLARE
    attempt   ops.lc_description_command_attempt%ROWTYPE;
    command   ops.lc_description_command%ROWTYPE;
    operation platform.capability_operation%ROWTYPE;
    document  jsonb;
    resolved  text;
    failure   text;
    observation_id uuid;
    observed_text text;
    observed_digest text;
    observed_kiz boolean;
    native_task text;
BEGIN
    SELECT * INTO attempt FROM ops.lc_description_command_attempt WHERE id = p_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'attempt does not exist' USING ERRCODE = 'MO090'; END IF;
    IF attempt.fence_token <> p_fence OR attempt.lease_owner IS DISTINCT FROM p_owner
        OR attempt.request_digest IS DISTINCT FROM p_request_digest THEN
        RAISE EXCEPTION 'this attempt does not belong to the caller' USING ERRCODE = 'MO090';
    END IF;
    SELECT * INTO command FROM ops.lc_description_command c WHERE c.id = attempt.command_id;
    IF command.fence_token <> p_fence OR command.lease_owner IS DISTINCT FROM p_owner
        OR command.lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'stale completion fence' USING ERRCODE = 'MO090';
    END IF;
    IF attempt.outcome_class <> 'IN_FLIGHT' THEN
        RAISE EXCEPTION 'this attempt is already complete' USING ERRCODE = 'MO096';
    END IF;

    IF p_content IS NULL THEN
        IF p_outcome = 'ACCEPTED' OR p_http_status IS NOT NULL OR p_error IS NULL
            OR (p_body IS NOT NULL AND length(p_body) > 0) THEN
            RAISE EXCEPTION 'an answer with no recorded bytes cannot be an acceptance' USING ERRCODE = 'MO093';
        END IF;
        IF attempt.purpose IN ('APPLY', 'RESTORE') AND p_outcome IN ('RETRIABLE_ERROR', 'TIMEOUT') THEN
            resolved := 'UNKNOWN_STATE';
            failure := 'write_dispatch_not_proven_absent';
        ELSE
            resolved := p_outcome;
            failure := p_error;
        END IF;
    ELSE
        SELECT * INTO operation
          FROM jsonb_populate_record(NULL::platform.capability_operation, attempt.operation_snapshot -> 'operation');
        IF NOT EXISTS (SELECT 1 FROM raw.raw_content rc
                        WHERE rc.id = p_content AND rc.hash_value = encode(sha256(p_body), 'hex')) THEN
            RAISE EXCEPTION 'the recorded bytes do not match their custody hash' USING ERRCODE = 'MO093';
        END IF;
        IF p_evidence_class NOT IN ('PROTOCOL_FIXTURE', 'PROVIDER_RESPONSE') THEN
            RAISE EXCEPTION 'an evidence class is required' USING ERRCODE = 'MO093';
        END IF;
        BEGIN
            document := convert_from(p_body, 'UTF8')::jsonb;
        EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire THEN
            document := NULL;
        END;
        IF document IS NULL THEN
            resolved := 'UNKNOWN_STATE'; failure := 'response_semantics_unknown';
        ELSIF NOT p_response_complete OR p_http_status IN (408, 429) OR p_http_status >= 500 THEN
            resolved := CASE WHEN attempt.purpose IN ('APPLY', 'RESTORE') THEN 'UNKNOWN_STATE' ELSE 'RETRIABLE_ERROR' END;
            failure := 'provider_response_inconclusive';
        ELSIF attempt.purpose IN ('APPLY', 'RESTORE', 'STATUS_ENQUIRY')
            AND operation.ad_not_applied_pointer IS NOT NULL
            AND ops.ad_json_value(document, operation.ad_not_applied_pointer) = operation.ad_not_applied_value THEN
            resolved := 'RETRIABLE_ERROR'; failure := 'provider_explicit_not_applied';
        ELSIF p_http_status >= 300 THEN
            resolved := 'REJECTED'; failure := 'platform_rejected';
        ELSIF attempt.purpose IN ('APPLY', 'RESTORE') THEN
            IF ops.ad_json_value(document, operation.accepted_pointer) IS NOT DISTINCT FROM operation.accepted_value THEN
                IF attempt.operation_snapshot ->> 'writeResultModel' = 'ASYNCHRONOUS_TASK' THEN
                    native_task := ops.ad_json_pointer(document, operation.task_key_pointer);
                    IF native_task IS NOT NULL AND length(native_task) BETWEEN 1 AND 256
                        AND native_task !~ '[[:cntrl:]]' THEN
                        resolved := 'ACCEPTED';
                    ELSE
                        resolved := 'UNKNOWN_STATE'; failure := 'asynchronous_accept_without_task_key';
                    END IF;
                ELSE
                    resolved := 'ACCEPTED';
                END IF;
            ELSE
                resolved := 'UNKNOWN_STATE'; failure := 'response_semantics_unknown';
            END IF;
        ELSIF attempt.purpose = 'READBACK' THEN
            observed_text := ops.ad_json_pointer(document, operation.description_observed_text_pointer);
            IF operation.description_kiz_marked_pointer IS NOT NULL THEN
                observed_kiz := CASE ops.ad_json_pointer(document, operation.description_kiz_marked_pointer)
                    WHEN 'true' THEN true WHEN 'false' THEN false ELSE NULL END;
            END IF;
            IF observed_text IS NOT NULL THEN
                observed_digest := ops.lc_description_digest_under_rule(observed_text, command.equivalence_rule);
                resolved := 'ACCEPTED';
            ELSE
                resolved := 'UNKNOWN_STATE'; failure := 'readback_value_unreadable';
            END IF;
        ELSE
            IF ops.ad_json_pointer(document, operation.task_status_pointer) = operation.task_success_value THEN
                resolved := 'ACCEPTED';
            ELSIF ops.ad_json_pointer(document, operation.task_status_pointer) = operation.task_failure_value THEN
                resolved := 'REJECTED'; failure := 'platform_task_rejected';
            ELSIF ops.ad_json_pointer(document, operation.task_status_pointer) = ANY(operation.task_pending_values) THEN
                resolved := 'RETRIABLE_ERROR'; failure := 'platform_task_pending';
            ELSE
                resolved := 'UNKNOWN_STATE'; failure := 'response_semantics_unknown';
            END IF;
        END IF;

        observation_id := gen_random_uuid();
        INSERT INTO raw.lc_description_response_observation (
            id, command_id, attempt_id, raw_content_id, request_digest, http_status, response_headers,
            evidence_class, response_complete, operation_id, operation_version, observed_text_digest,
            observed_kiz_marked, version_token, observed_at, correlation_id)
        VALUES (observation_id, attempt.command_id, attempt.id, p_content, p_request_digest, p_http_status,
            coalesce(p_headers, '{}'::jsonb), p_evidence_class, p_response_complete, operation.id,
            operation.version, observed_digest, observed_kiz,
            p_headers ->> coalesce(operation.version_token_header, 'etag'), clock_timestamp(),
            attempt.correlation_id);
    END IF;

    UPDATE ops.lc_description_command_attempt
       SET completed_at = clock_timestamp(), outcome_class = resolved, native_status = p_native_status,
           native_task_key = native_task, error_code = failure, raw_observation_id = observation_id
     WHERE id = p_id;
    RETURN observation_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.complete_lc_description_command_attempt(
    uuid, bigint, text, text, text, text, text, uuid, bytea, integer, jsonb, text, text, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.complete_lc_description_command_attempt(
    uuid, bigint, text, text, text, text, text, uuid, bytea, integer, jsonb, text, text, boolean) TO marketops_app;

-- The match state is derived and returned, never supplied. The equivalence
-- rule the command was created under is the only rule applied.
CREATE FUNCTION ops.record_lc_description_command_readback(
    p_readback_id uuid, p_command_id uuid, p_attempt_id uuid, p_fence bigint, p_owner text, p_correlation_id text)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, raw, pg_temp
AS $$
DECLARE
    command     ops.lc_description_command%ROWTYPE;
    attempt     ops.lc_description_command_attempt%ROWTYPE;
    observation raw.lc_description_response_observation%ROWTYPE;
    match       text;
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090'; END IF;
    IF command.fence_token <> p_fence OR command.lease_owner IS DISTINCT FROM p_owner
        OR command.lease_expires_at IS NULL OR command.lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'the lease that authorised this readback is not current' USING ERRCODE = 'MO090';
    END IF;
    SELECT * INTO attempt FROM ops.lc_description_command_attempt WHERE id = p_attempt_id;
    IF NOT FOUND OR attempt.command_id <> p_command_id OR attempt.purpose <> 'READBACK'
        OR attempt.fence_token <> p_fence OR attempt.lease_owner IS DISTINCT FROM p_owner
        OR attempt.outcome_class = 'IN_FLIGHT' OR attempt.raw_observation_id IS NULL THEN
        RAISE EXCEPTION 'a readback needs a completed readback attempt at this fence' USING ERRCODE = 'MO093';
    END IF;
    SELECT * INTO observation FROM raw.lc_description_response_observation WHERE id = attempt.raw_observation_id;
    match := CASE
        WHEN observation.observed_text_digest IS NULL THEN 'UNREADABLE'
        WHEN observation.observed_text_digest
             = ops.lc_description_digest_under_rule(command.target_text, command.equivalence_rule)
             THEN 'MATCHES_TARGET'
        WHEN command.prior_text IS NOT NULL AND observation.observed_text_digest
             = ops.lc_description_digest_under_rule(command.prior_text, command.equivalence_rule)
             THEN 'MATCHES_PRIOR'
        ELSE 'DIFFERENT'
    END;
    -- A matched target whose marking declaration was lost is not a match: a
    -- description write that dropped kizMarked changed a non-target field.
    IF match = 'MATCHES_TARGET' AND observation.observed_kiz_marked IS NOT NULL
        AND observation.observed_kiz_marked <> command.kiz_marked_declared THEN
        match := 'DIFFERENT';
    END IF;
    INSERT INTO ops.lc_description_command_readback (id, command_id, attempt_id, observed_at,
        observed_text_digest, observed_kiz_marked, match_state, raw_observation_id, correlation_id)
    VALUES (p_readback_id, p_command_id, p_attempt_id, clock_timestamp(),
        CASE WHEN match = 'UNREADABLE' THEN NULL ELSE observation.observed_text_digest END,
        observation.observed_kiz_marked, match, attempt.raw_observation_id, p_correlation_id);
    RETURN match;
END;
$$;
REVOKE ALL ON FUNCTION ops.record_lc_description_command_readback(uuid, uuid, uuid, bigint, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_lc_description_command_readback(uuid, uuid, uuid, bigint, text, text) TO marketops_app;

-- A compensation is only ever observed, never asserted.
CREATE FUNCTION ops.lc_description_compensation_is_observed()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    IF NEW.state <> 'COMPENSATED' OR OLD.state = 'COMPENSATED' THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT 1 FROM ops.lc_description_command_readback rb
          JOIN ops.lc_description_command_attempt at ON at.id = rb.attempt_id
         WHERE rb.command_id = NEW.id AND rb.match_state = 'MATCHES_PRIOR' AND at.fence_token = NEW.fence_token
           AND EXISTS (SELECT 1 FROM ops.lc_description_command_attempt restore
                        WHERE restore.command_id = NEW.id AND restore.purpose = 'RESTORE'
                          AND restore.outcome_class = 'ACCEPTED' AND restore.raw_observation_id IS NOT NULL
                          AND restore.started_at <= at.started_at)) THEN
        RAISE EXCEPTION 'COMPENSATED needs an accepted restore followed by a prior-matching readback'
            USING ERRCODE = 'MO093';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_description_compensation_is_observed() FROM PUBLIC;
CREATE TRIGGER lc_description_command_compensation_is_observed_bu
    BEFORE UPDATE ON ops.lc_description_command
    FOR EACH ROW EXECUTE FUNCTION ops.lc_description_compensation_is_observed();

-- The six recovery edges must exist.
DO $verify$
DECLARE missing text;
BEGIN
    SELECT string_agg(pair.from_state || '->' || pair.to_state, ', ') INTO missing
      FROM (VALUES ('LEASED', 'PENDING'), ('LEASED', 'UNKNOWN_REQUIRES_READBACK'),
                   ('EXECUTING', 'UNKNOWN_REQUIRES_READBACK'), ('PLATFORM_PENDING', 'UNKNOWN_REQUIRES_READBACK'),
                   ('READBACK_PENDING', 'UNKNOWN_REQUIRES_READBACK'), ('COMPENSATION_PENDING', 'MANUAL_RESOLUTION')
           ) AS pair(from_state, to_state)
     WHERE NOT EXISTS (SELECT 1 FROM ops.lc_description_command_transition t
                        WHERE t.from_state = pair.from_state AND t.to_state = pair.to_state);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'lease recovery needs transitions that do not exist: %', missing;
    END IF;
    IF EXISTS (SELECT 1 FROM ops.lc_description_command_transition
                WHERE from_state = 'UNKNOWN_REQUIRES_READBACK' AND to_state IN ('EXECUTING', 'LEASED', 'READBACK_MATCHED')) THEN
        RAISE EXCEPTION 'an unknown result must never be repeatable as a write';
    END IF;
END
$verify$;

-- ---------------------------------------------------------------------------
-- Route inventory and privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'lc_gate_authority', 'NO_ROUTE', NULL,
        'owner-published exact-head-bound gate authority; production_write_enabled defaults false; no Java writer'),
    ('ops', 'lc_description_command', 'NO_ROUTE', NULL,
        'controlled description write outbox; state moves only through SECURITY DEFINER functions'),
    ('ops', 'lc_description_command_transition', 'NO_ROUTE', NULL,
        'the reviewed transition graph as data; read by the transition function'),
    ('ops', 'lc_description_command_attempt', 'NO_ROUTE', NULL,
        'one row per provider call; opened before and completed after it'),
    ('ops', 'lc_description_command_readback', 'NO_ROUTE', NULL,
        'derived match state under the accepted equivalence rule; never supplied'),
    ('raw', 'lc_description_response_observation', 'NO_ROUTE', NULL,
        'exact response custody for the description write, sharing raw.raw_content');

GRANT SELECT ON ops.lc_gate_authority TO marketops_app;
GRANT SELECT ON ops.lc_description_command TO marketops_app;
GRANT SELECT ON ops.lc_description_command_transition TO marketops_app;
GRANT SELECT ON ops.lc_description_command_attempt TO marketops_app;
GRANT SELECT ON ops.lc_description_command_readback TO marketops_app;
GRANT SELECT ON raw.lc_description_response_observation TO marketops_app;
