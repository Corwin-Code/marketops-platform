-- The controlled write path: one PRICE_CHANGE command, its outbox state
-- machine, the attempts made against a platform, the readbacks that decide
-- whether the platform actually holds the intended value, and the gate that
-- must pass before any of it can leave the system.
--
-- The application role can read every table here and change none of them. Every
-- transition runs through one of the two functions below, exactly as the
-- acquisition run and cursor do, so the invariants are properties of the
-- database rather than of the code that happens to call it.
--
-- Four invariants shape the design.
--
-- Platform acceptance is not success. A command reaches SUCCEEDED only after a
-- readback observes the intended value; the transition to SUCCEEDED requires a
-- matching readback row in the same transaction and refuses without one.
--
-- An unknown result is never retried blindly. UNKNOWN_REQUIRES_READBACK has no
-- transition back to EXECUTING; the only way out is a readback or a person.
--
-- A stale worker gains nothing. A lease and a fence token are checked on every
-- transition, so a worker whose lease was taken over writes no row.
--
-- Restore cannot overwrite a later legitimate change. Compensation requires the
-- latest readback to still observe the value this command wrote, which is the
-- database's way of asking whether anything else has changed the price since.
--
-- Error conditions raised here:
--
--   MO030  PRICE_COMMAND_AUTHORITY_LOST
--   MO031  PRICE_COMMAND_TRANSITION_NOT_ALLOWED
--   MO032  PRICE_COMMAND_WRITE_GATE_CLOSED
--   MO033  PRICE_COMMAND_SUCCESS_WITHOUT_READBACK
--   MO034  PRICE_COMMAND_COMPENSATION_UNSAFE
--   MO035  PRICE_COMMAND_LEASE_INVALID

-- ---------------------------------------------------------------------------
-- Command
-- ---------------------------------------------------------------------------

CREATE TABLE ops.price_command (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    recommendation_id           uuid           NOT NULL,
    approval_decision_id        uuid           NOT NULL,
    store_id                    uuid           NOT NULL,
    platform_listing_variant_id uuid           NOT NULL,
    platform_code               text           NOT NULL,
    capability_id               uuid           NOT NULL,
    idempotency_key             text           NOT NULL,
    currency_code               text           NOT NULL,
    prior_price                 numeric(18, 4) NOT NULL,
    target_price                numeric(18, 4) NOT NULL,
    prior_price_observation_id  uuid           NOT NULL,
    entity_version_digest       text           NOT NULL,
    state                       text           NOT NULL,
    attempt_no                  integer        NOT NULL DEFAULT 0,
    retry_budget_remaining      integer        NOT NULL,
    fence_token                 bigint         NOT NULL DEFAULT 1,
    lease_owner                 text,
    lease_expires_at            timestamptz,
    next_attempt_at             timestamptz,
    failure_code                text,
    terminal_at                 timestamptz,
    created_at                  timestamptz    NOT NULL,
    updated_at                  timestamptz    NOT NULL,
    CONSTRAINT price_command_pk PRIMARY KEY (id),
    CONSTRAINT price_command_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT price_command_recommendation_fk
        FOREIGN KEY (recommendation_id, organization_id)
        REFERENCES ops.recommendation (id, organization_id),
    CONSTRAINT price_command_approval_fk
        FOREIGN KEY (approval_decision_id) REFERENCES ops.approval_decision (id),
    CONSTRAINT price_command_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT price_command_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT price_command_capability_fk
        FOREIGN KEY (capability_id, platform_code)
        REFERENCES platform.platform_capability (id, platform_code),
    CONSTRAINT price_command_prior_observation_fk
        FOREIGN KEY (prior_price_observation_id)
        REFERENCES core.listing_price_observation (id),
    -- The idempotency key is the identity a platform retry must not duplicate.
    -- It is unique across the whole system, not per store, because the same key
    -- reaching two commands would defeat its purpose entirely.
    CONSTRAINT price_command_idempotency_uq UNIQUE (idempotency_key),
    CONSTRAINT price_command_idempotency_ck
        CHECK (idempotency_key ~ '^[a-z0-9][a-z0-9._-]{15,127}$'),
    CONSTRAINT price_command_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT price_command_prior_price_ck CHECK (prior_price > 0),
    CONSTRAINT price_command_target_price_ck CHECK (target_price > 0),
    CONSTRAINT price_command_change_ck CHECK (target_price <> prior_price),
    CONSTRAINT price_command_digest_ck CHECK (entity_version_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT price_command_state_ck
        CHECK (state IN (
            'PENDING', 'LEASED', 'EXECUTING', 'PLATFORM_PENDING', 'READBACK_PENDING',
            'SUCCEEDED', 'RETRY_WAIT', 'UNKNOWN_REQUIRES_READBACK', 'READBACK_MISMATCH',
            'MANUAL_RESOLUTION', 'FAILED_FINAL', 'COMPENSATION_PENDING',
            'COMPENSATED', 'COMPENSATION_FAILED')),
    CONSTRAINT price_command_attempt_ck CHECK (attempt_no >= 0),
    CONSTRAINT price_command_retry_budget_ck CHECK (retry_budget_remaining >= 0),
    CONSTRAINT price_command_fence_ck CHECK (fence_token > 0),
    -- A lease is an owner and a deadline together, and only a state that is
    -- actively held may carry one.
    CONSTRAINT price_command_lease_pairing_ck
        CHECK (num_nonnulls(lease_owner, lease_expires_at) <> 1),
    CONSTRAINT price_command_leased_state_ck
        CHECK (state NOT IN ('LEASED', 'EXECUTING', 'PLATFORM_PENDING', 'READBACK_PENDING')
            OR lease_owner IS NOT NULL),
    CONSTRAINT price_command_terminal_ck
        CHECK ((state IN ('SUCCEEDED', 'FAILED_FINAL', 'COMPENSATED', 'COMPENSATION_FAILED'))
            = (terminal_at IS NOT NULL)),
    CONSTRAINT price_command_failure_ck
        CHECK (state NOT IN ('FAILED_FINAL', 'COMPENSATION_FAILED')
            OR failure_code IS NOT NULL)
);

-- One live command per listing variant. A second in-flight change to the same
-- price would race with the first and make the readback ambiguous.
CREATE UNIQUE INDEX price_command_live_uq
    ON ops.price_command (platform_listing_variant_id)
    WHERE state NOT IN ('SUCCEEDED', 'FAILED_FINAL', 'COMPENSATED', 'COMPENSATION_FAILED');

CREATE INDEX price_command_queue_ix
    ON ops.price_command (state, next_attempt_at)
    WHERE state IN ('PENDING', 'RETRY_WAIT');
CREATE INDEX price_command_recommendation_ix ON ops.price_command (recommendation_id);
CREATE INDEX price_command_store_ix
    ON ops.price_command (store_id, state, created_at DESC);

-- The complete allowed transition set, as data.
--
-- Keeping the state machine in a table rather than in code means a transition
-- that was never reviewed cannot be reached by an application defect, and the
-- absence of a transition — such as UNKNOWN_REQUIRES_READBACK back to
-- EXECUTING — is asserted directly by reading this table.
CREATE TABLE ops.price_command_transition (
    from_state     text    NOT NULL,
    to_state       text    NOT NULL,
    requires_lease boolean NOT NULL,
    releases_lease boolean NOT NULL,
    note           text    NOT NULL,
    CONSTRAINT price_command_transition_pk PRIMARY KEY (from_state, to_state),
    CONSTRAINT price_command_transition_distinct_ck CHECK (from_state <> to_state)
);

INSERT INTO ops.price_command_transition
    (from_state, to_state, requires_lease, releases_lease, note) VALUES
    ('PENDING', 'LEASED', false, false,
        'a worker claims the command through the leasing function'),
    ('LEASED', 'EXECUTING', true, false,
        'the adapter call is about to be made'),
    ('LEASED', 'PENDING', true, true,
        'the worker released the claim without calling the platform'),
    ('EXECUTING', 'PLATFORM_PENDING', true, false,
        'the platform accepted the request and reported asynchronous work'),
    ('EXECUTING', 'READBACK_PENDING', true, false,
        'the platform answered synchronously and the value must be read back'),
    ('EXECUTING', 'RETRY_WAIT', true, true,
        'a retriable transport or rate-limit condition occurred'),
    ('EXECUTING', 'UNKNOWN_REQUIRES_READBACK', true, true,
        'the call timed out or returned an answer that cannot be classified'),
    ('EXECUTING', 'FAILED_FINAL', true, true,
        'the platform rejected the request permanently'),
    ('PLATFORM_PENDING', 'READBACK_PENDING', true, false,
        'the platform reported the asynchronous work as finished'),
    ('PLATFORM_PENDING', 'RETRY_WAIT', true, true,
        'the status enquiry is not yet conclusive'),
    ('PLATFORM_PENDING', 'UNKNOWN_REQUIRES_READBACK', true, true,
        'the status enquiry cannot be classified'),
    ('PLATFORM_PENDING', 'FAILED_FINAL', true, true,
        'the platform reported the asynchronous work as rejected'),
    ('READBACK_PENDING', 'SUCCEEDED', true, true,
        'a readback observed the intended value'),
    ('READBACK_PENDING', 'READBACK_MISMATCH', true, true,
        'a readback observed a different value'),
    ('READBACK_PENDING', 'RETRY_WAIT', true, true,
        'the readback is not yet available'),
    ('READBACK_PENDING', 'UNKNOWN_REQUIRES_READBACK', true, true,
        'the readback attempt itself could not be classified'),
    ('RETRY_WAIT', 'LEASED', false, false,
        'the retry delay elapsed and a worker claimed the command again'),
    ('RETRY_WAIT', 'FAILED_FINAL', false, false,
        'the retry budget is exhausted'),
    ('RETRY_WAIT', 'MANUAL_RESOLUTION', false, false,
        'an operator took the command out of automatic handling'),
    ('UNKNOWN_REQUIRES_READBACK', 'READBACK_PENDING', false, false,
        'a readback attempt is authorised; the write itself is never repeated'),
    ('UNKNOWN_REQUIRES_READBACK', 'MANUAL_RESOLUTION', false, false,
        'an operator took the unresolved command over'),
    ('READBACK_MISMATCH', 'MANUAL_RESOLUTION', false, false,
        'an operator took the mismatch over'),
    ('READBACK_MISMATCH', 'COMPENSATION_PENDING', false, false,
        'an operator authorised restoring the previous value'),
    ('MANUAL_RESOLUTION', 'COMPENSATION_PENDING', false, false,
        'an operator authorised restoring the previous value'),
    ('MANUAL_RESOLUTION', 'SUCCEEDED', false, false,
        'an operator confirmed the intended value against a matching readback'),
    ('MANUAL_RESOLUTION', 'FAILED_FINAL', false, false,
        'an operator closed the command as failed'),
    ('COMPENSATION_PENDING', 'COMPENSATED', true, true,
        'the previous value was restored and read back'),
    ('COMPENSATION_PENDING', 'COMPENSATION_FAILED', true, true,
        'the restore could not be completed'),
    ('COMPENSATION_PENDING', 'MANUAL_RESOLUTION', false, false,
        'the restore was withdrawn and returned to an operator');

-- ---------------------------------------------------------------------------
-- Attempts and readbacks
-- ---------------------------------------------------------------------------

-- One call made against a platform on behalf of one command. Append-only, and
-- keyed by attempt number so a retry cannot overwrite the record of the attempt
-- that preceded it. native_task_key holds the platform's own handle for
-- asynchronous work, which is what a status enquiry needs.
CREATE TABLE ops.price_command_attempt (
    id                 uuid        NOT NULL,
    command_id         uuid        NOT NULL,
    attempt_no         integer     NOT NULL,
    purpose            text        NOT NULL,
    fence_token        bigint      NOT NULL,
    lease_owner        text        NOT NULL,
    started_at         timestamptz NOT NULL,
    completed_at       timestamptz,
    outcome_class      text        NOT NULL,
    native_status      text,
    native_task_key    text,
    raw_observation_id uuid,
    error_code         text,
    correlation_id     text        NOT NULL,
    CONSTRAINT price_command_attempt_pk PRIMARY KEY (id),
    CONSTRAINT price_command_attempt_command_fk
        FOREIGN KEY (command_id) REFERENCES ops.price_command (id),
    CONSTRAINT price_command_attempt_raw_fk
        FOREIGN KEY (raw_observation_id)
        REFERENCES raw.raw_acquisition_observation (id),
    CONSTRAINT price_command_attempt_no_uq UNIQUE (command_id, attempt_no, purpose),
    CONSTRAINT price_command_attempt_no_ck CHECK (attempt_no > 0),
    CONSTRAINT price_command_attempt_purpose_ck
        CHECK (purpose IN ('APPLY', 'STATUS_ENQUIRY', 'READBACK', 'RESTORE')),
    CONSTRAINT price_command_attempt_fence_ck CHECK (fence_token > 0),
    CONSTRAINT price_command_attempt_outcome_ck
        CHECK (outcome_class IN (
            'IN_FLIGHT', 'ACCEPTED', 'REJECTED', 'RETRIABLE_ERROR',
            'TIMEOUT', 'UNKNOWN_STATE')),
    CONSTRAINT price_command_attempt_completion_ck
        CHECK ((outcome_class = 'IN_FLIGHT') = (completed_at IS NULL))
);

CREATE INDEX price_command_attempt_command_ix
    ON ops.price_command_attempt (command_id, started_at DESC);

-- What a later read of the platform actually observed. This is the evidence a
-- success claim rests on, and the evidence a restore decision is checked
-- against. Append-only: a readback that could be rewritten proves nothing.
CREATE TABLE ops.price_command_readback (
    id                 uuid           NOT NULL,
    command_id         uuid           NOT NULL,
    attempt_id         uuid           NOT NULL,
    observed_at        timestamptz    NOT NULL,
    observed_price     numeric(18, 4),
    currency_code      text,
    match_state        text           NOT NULL,
    raw_observation_id uuid,
    correlation_id     text           NOT NULL,
    CONSTRAINT price_command_readback_pk PRIMARY KEY (id),
    CONSTRAINT price_command_readback_command_fk
        FOREIGN KEY (command_id) REFERENCES ops.price_command (id),
    CONSTRAINT price_command_readback_attempt_fk
        FOREIGN KEY (attempt_id) REFERENCES ops.price_command_attempt (id),
    CONSTRAINT price_command_readback_raw_fk
        FOREIGN KEY (raw_observation_id)
        REFERENCES raw.raw_acquisition_observation (id),
    CONSTRAINT price_command_readback_match_ck
        CHECK (match_state IN ('MATCHES_TARGET', 'MATCHES_PRIOR', 'DIFFERENT', 'UNREADABLE')),
    -- An unreadable readback carries no price. Any other outcome must carry the
    -- value it compared, or the comparison cannot be re-checked.
    CONSTRAINT price_command_readback_value_ck
        CHECK ((match_state = 'UNREADABLE')
            = (observed_price IS NULL AND currency_code IS NULL)),
    CONSTRAINT price_command_readback_currency_ck
        CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT price_command_readback_price_ck
        CHECK (observed_price IS NULL OR observed_price > 0)
);

CREATE INDEX price_command_readback_command_ix
    ON ops.price_command_readback (command_id, observed_at DESC);

-- Every operator action on a write capability switch. The switch state itself
-- lives in the platform feature-flag registry; this journal records who moved
-- it, when and why, so a kill and a re-enable are equally attributable.
CREATE TABLE ops.kill_switch_event (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    capability_code     text        NOT NULL,
    scope_kind          text        NOT NULL,
    scope_reference     text,
    action              text        NOT NULL,
    actor_user_id       uuid        NOT NULL,
    reason              text        NOT NULL,
    in_flight_command_count integer NOT NULL,
    occurred_at         timestamptz NOT NULL,
    correlation_id      text        NOT NULL,
    CONSTRAINT kill_switch_event_pk PRIMARY KEY (id),
    CONSTRAINT kill_switch_event_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT kill_switch_event_actor_fk
        FOREIGN KEY (actor_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT kill_switch_event_scope_ck
        CHECK (scope_kind IN
            ('GLOBAL', 'PLATFORM', 'MARKETPLACE_ACCOUNT', 'STORE', 'CAPABILITY')),
    CONSTRAINT kill_switch_event_scope_reference_ck
        CHECK ((scope_kind = 'GLOBAL') = (scope_reference IS NULL)),
    CONSTRAINT kill_switch_event_action_ck CHECK (action IN ('DISABLE', 'ENABLE')),
    CONSTRAINT kill_switch_event_in_flight_ck CHECK (in_flight_command_count >= 0)
);

CREATE INDEX kill_switch_event_occurred_ix
    ON ops.kill_switch_event (organization_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Write gate
-- ---------------------------------------------------------------------------

-- Every condition that must hold before a command may leave the system, as a
-- set of blocking reason codes. An empty array is the only permission; every
-- other result names exactly what is closed, which is what an operator reads on
-- the command timeline and what the runbooks are written against.
--
-- The function reads state and grants nothing. It is STABLE, takes no lock and
-- issues no authority, so it cannot become a second execution path.
CREATE FUNCTION ops.evaluate_price_write_gate(p_command_id uuid)
RETURNS text[]
LANGUAGE plpgsql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    command_row   record;
    reasons       text[] := ARRAY[]::text[];
    now_instant   timestamptz := clock_timestamp();
BEGIN
    SELECT command.id, command.organization_id, command.store_id,
           command.platform_code, command.capability_id,
           command.platform_listing_variant_id, command.recommendation_id,
           command.approval_decision_id, command.target_price, command.prior_price,
           store.marketplace_account_id
      INTO command_row
      FROM ops.price_command AS command
      JOIN core.store AS store ON store.id = command.store_id
     WHERE command.id = p_command_id;

    IF command_row.id IS NULL THEN
        RETURN ARRAY['COMMAND_NOT_FOUND'];
    END IF;

    -- The capability itself must be verified against recorded evidence and
    -- available for this exact store.
    PERFORM 1
      FROM platform.platform_capability AS capability
     WHERE capability.id = command_row.capability_id
       AND capability.status = 'ACTIVE'
       AND capability.deprecated_at IS NULL
       AND capability.verification_state = 'VERIFIED';
    IF NOT FOUND
    THEN
        reasons := reasons || 'CAPABILITY_NOT_VERIFIED';
    END IF;

    PERFORM 1
      FROM platform.capability_subject_status AS subject
     WHERE subject.capability_id = command_row.capability_id
       AND subject.store_id = command_row.store_id
       AND subject.availability = 'AVAILABLE';
    IF NOT FOUND
    THEN
        reasons := reasons || 'CAPABILITY_NOT_AVAILABLE_FOR_STORE';
    END IF;

    -- The capability switch must be explicitly on, and no wider scope may be
    -- switched off. A missing flag is off, so an unconfigured scope blocks.
    PERFORM 1
      FROM platform.feature_flag AS flag
     WHERE flag.flag_code = 'price-change-write'
       AND flag.scope_kind = 'CAPABILITY'
       AND flag.capability_id = command_row.capability_id
       AND flag.status = 'ACTIVE'
       AND flag.state = 'ENABLED';
    IF NOT FOUND
    THEN
        reasons := reasons || 'CAPABILITY_SWITCH_DISABLED';
    END IF;

    PERFORM 1
      FROM platform.feature_flag AS flag
     WHERE flag.flag_code = 'price-change-write'
       AND flag.scope_kind = 'GLOBAL'
       AND flag.status = 'ACTIVE'
       AND flag.state = 'ENABLED';
    IF NOT FOUND
    THEN
        reasons := reasons || 'GLOBAL_SWITCH_DISABLED';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM platform.feature_flag AS flag
         WHERE flag.flag_code = 'price-change-write'
           AND flag.status = 'ACTIVE'
           AND flag.state = 'DISABLED'
           AND ((flag.scope_kind = 'PLATFORM'
                    AND flag.platform_code = command_row.platform_code)
                OR (flag.scope_kind = 'MARKETPLACE_ACCOUNT'
                    AND flag.marketplace_account_id = command_row.marketplace_account_id)
                OR (flag.scope_kind = 'STORE'
                    AND flag.store_id = command_row.store_id)))
    THEN
        reasons := reasons || 'SCOPED_SWITCH_DISABLED';
    END IF;

    -- The entity must be on the positive allowlist at this instant.
    PERFORM 1
      FROM ops.pilot_allowlist_entry AS entry
     WHERE entry.capability_code = 'PRICE_CHANGE'
       AND entry.status = 'ACTIVE'
       AND entry.store_id = command_row.store_id
       AND (entry.platform_listing_variant_id IS NULL
            OR entry.platform_listing_variant_id
                = command_row.platform_listing_variant_id)
       AND entry.valid_from <= now_instant
       AND entry.valid_until > now_instant;
    IF NOT FOUND
    THEN
        reasons := reasons || 'ENTITY_NOT_ALLOWLISTED';
    END IF;

    -- The authorization must still stand and still cover this exact proposal.
    PERFORM 1
      FROM ops.approval_decision AS decision
      JOIN ops.recommendation AS proposal ON proposal.id = decision.recommendation_id
     WHERE decision.id = command_row.approval_decision_id
       AND decision.recommendation_id = command_row.recommendation_id
       AND decision.decision IN ('APPROVED', 'POLICY_AUTHORIZED')
       AND decision.scope_expires_at > now_instant
       AND decision.entity_version_digest = proposal.entity_version_digest;
    IF NOT FOUND
    THEN
        reasons := reasons || 'AUTHORIZATION_INVALID_OR_EXPIRED';
    END IF;

    PERFORM 1
      FROM ops.recommendation AS proposal
     WHERE proposal.id = command_row.recommendation_id
       AND proposal.state IN ('APPROVED', 'POLICY_AUTHORIZED',
                              'COMMAND_CREATED', 'EXECUTION_TRACKING')
       AND proposal.valid_until > now_instant;
    IF NOT FOUND
    THEN
        reasons := reasons || 'RECOMMENDATION_STALE';
    END IF;

    -- The mapping the profit case rests on must still resolve.
    PERFORM 1
      FROM core.listing_mapping AS mapping
     WHERE mapping.platform_listing_variant_id
               = command_row.platform_listing_variant_id
       AND mapping.status = 'ACTIVE'
       AND mapping.effective_from <= now_instant
       AND (mapping.effective_to IS NULL OR mapping.effective_to > now_instant);
    IF NOT FOUND
    THEN
        reasons := reasons || 'MAPPING_UNRESOLVED';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM core.mapping_conflict AS conflict
         WHERE conflict.platform_listing_variant_id
                   = command_row.platform_listing_variant_id
           AND conflict.state = 'OPEN')
    THEN
        reasons := reasons || 'MAPPING_CONFLICT_OPEN';
    END IF;

    -- The deterministic guardrail must have passed for execution specifically.
    PERFORM 1
      FROM ops.guardrail_evaluation AS evaluation
     WHERE evaluation.recommendation_id = command_row.recommendation_id
       AND evaluation.purpose = 'EXECUTION'
       AND evaluation.outcome = 'PASS';
    IF NOT FOUND
    THEN
        reasons := reasons || 'GUARDRAIL_NOT_PASSED';
    END IF;

    RETURN reasons;
END;
$$;

REVOKE ALL ON FUNCTION ops.evaluate_price_write_gate(uuid) FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Leasing
-- ---------------------------------------------------------------------------

-- Claim a command for one worker, or refuse.
--
-- The gate is evaluated inside the same transaction that takes the claim, so a
-- switch that is thrown while a worker is deciding cannot be missed: either the
-- claim happens before the switch commits, or the gate reads the switch.
CREATE FUNCTION ops.lease_price_command(
    p_command_id    uuid,
    p_lease_owner   text,
    p_lease_seconds integer)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    current_state text;
    gate_reasons  text[];
    new_fence     bigint;
BEGIN
    IF p_lease_owner IS NULL OR length(btrim(p_lease_owner)) = 0 THEN
        RAISE EXCEPTION 'a lease owner is required'
            USING ERRCODE = 'MO035';
    END IF;
    IF p_lease_seconds IS NULL OR p_lease_seconds <= 0 OR p_lease_seconds > 900 THEN
        RAISE EXCEPTION 'lease duration must be between 1 and 900 seconds'
            USING ERRCODE = 'MO035';
    END IF;

    SELECT command.state INTO current_state
      FROM ops.price_command AS command
     WHERE command.id = p_command_id
       FOR UPDATE OF command;

    IF current_state IS NULL THEN
        RAISE EXCEPTION 'price command % does not exist', p_command_id
            USING ERRCODE = 'MO030';
    END IF;

    PERFORM 1 FROM ops.price_command_transition AS allowed
     WHERE allowed.from_state = current_state AND allowed.to_state = 'LEASED';
    IF NOT FOUND
    THEN
        RAISE EXCEPTION 'price command % cannot be leased from state %',
            p_command_id, current_state
            USING ERRCODE = 'MO031';
    END IF;

    gate_reasons := ops.evaluate_price_write_gate(p_command_id);
    IF cardinality(gate_reasons) > 0 THEN
        RAISE EXCEPTION 'price write gate closed for command %: %',
            p_command_id, array_to_string(gate_reasons, ',')
            USING ERRCODE = 'MO032';
    END IF;

    UPDATE ops.price_command AS command
       SET state = 'LEASED',
           fence_token = command.fence_token + 1,
           attempt_no = command.attempt_no + 1,
           lease_owner = p_lease_owner,
           lease_expires_at = clock_timestamp() + make_interval(secs => p_lease_seconds),
           next_attempt_at = NULL,
           updated_at = clock_timestamp()
     WHERE command.id = p_command_id
       AND command.state = current_state
    RETURNING command.fence_token INTO new_fence;

    IF new_fence IS NULL THEN
        RAISE EXCEPTION 'price command % changed while being leased', p_command_id
            USING ERRCODE = 'MO030';
    END IF;

    RETURN new_fence;
END;
$$;

REVOKE ALL ON FUNCTION ops.lease_price_command(uuid, text, integer) FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Transition
-- ---------------------------------------------------------------------------

-- Move a command between states, or refuse.
--
-- Four checks stand between a caller and a state change: the transition must be
-- in the reviewed set, the caller must still hold the lease when the transition
-- requires one, a success must be backed by a matching readback committed in
-- this transaction, and a compensation must be backed by a latest readback that
-- still observes the value this command wrote.
--
-- The last of those is what stops a restore from overwriting a later legitimate
-- change: if anything else has moved the price since, the newest readback no
-- longer matches the target and the restore is refused.
CREATE FUNCTION ops.transition_price_command(
    p_command_id           uuid,
    p_expected_fence       bigint,
    p_expected_lease_owner text,
    p_to_state             text,
    p_failure_code         text,
    p_retry_delay_seconds  integer,
    p_evidence_id          uuid)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    command_row   record;
    rule_row      record;
    latest_match  text;
    updated_state text;
BEGIN
    SELECT command.id, command.state, command.fence_token, command.lease_owner,
           command.lease_expires_at, command.retry_budget_remaining,
           command.target_price, command.currency_code
      INTO command_row
      FROM ops.price_command AS command
     WHERE command.id = p_command_id
       FOR UPDATE OF command;

    IF command_row.id IS NULL THEN
        RAISE EXCEPTION 'price command % does not exist', p_command_id
            USING ERRCODE = 'MO030';
    END IF;

    SELECT allowed.requires_lease, allowed.releases_lease
      INTO rule_row
      FROM ops.price_command_transition AS allowed
     WHERE allowed.from_state = command_row.state
       AND allowed.to_state = p_to_state;

    IF rule_row.requires_lease IS NULL THEN
        RAISE EXCEPTION 'price command % may not move from % to %',
            p_command_id, command_row.state, p_to_state
            USING ERRCODE = 'MO031';
    END IF;

    IF rule_row.requires_lease THEN
        IF command_row.fence_token <> p_expected_fence
            OR command_row.lease_owner IS DISTINCT FROM p_expected_lease_owner
            OR command_row.lease_expires_at IS NULL
            OR command_row.lease_expires_at <= clock_timestamp()
        THEN
            RAISE EXCEPTION
                'price command % is not held by % at fence % with a live lease',
                p_command_id, p_expected_lease_owner, p_expected_fence
                USING ERRCODE = 'MO030';
        END IF;
    END IF;

    -- Platform acceptance is not success. The evidence named here must be a
    -- readback of this command that observed the intended value.
    IF p_to_state = 'SUCCEEDED' THEN
        PERFORM 1
          FROM ops.price_command_readback AS readback
         WHERE readback.id = p_evidence_id
           AND readback.command_id = p_command_id
           AND readback.match_state = 'MATCHES_TARGET'
           AND readback.observed_price = command_row.target_price
           AND readback.currency_code = command_row.currency_code;
        IF NOT FOUND
        THEN
            RAISE EXCEPTION
                'price command % cannot succeed without a matching readback',
                p_command_id
                USING ERRCODE = 'MO033',
                      HINT = 'record the readback observation before claiming success';
        END IF;
    END IF;

    -- A restore may only proceed while the platform still holds what this
    -- command put there. Anything else means somebody or something changed the
    -- price afterwards, and overwriting that is not compensation.
    IF p_to_state = 'COMPENSATION_PENDING' THEN
        SELECT readback.match_state INTO latest_match
          FROM ops.price_command_readback AS readback
         WHERE readback.command_id = p_command_id
         ORDER BY readback.observed_at DESC, readback.id DESC
         LIMIT 1;

        IF latest_match IS DISTINCT FROM 'MATCHES_TARGET' THEN
            RAISE EXCEPTION
                'price command % cannot be compensated: the latest readback is %',
                p_command_id, coalesce(latest_match, 'absent')
                USING ERRCODE = 'MO034',
                      HINT = 'read the current platform value before restoring';
        END IF;
    END IF;

    IF p_to_state IN ('FAILED_FINAL', 'COMPENSATION_FAILED')
        AND (p_failure_code IS NULL OR length(btrim(p_failure_code)) = 0)
    THEN
        RAISE EXCEPTION 'a terminal failure of price command % must name a reason',
            p_command_id
            USING ERRCODE = 'MO031';
    END IF;

    UPDATE ops.price_command AS command
       SET state = p_to_state,
           lease_owner = CASE WHEN rule_row.releases_lease THEN NULL
                              ELSE command.lease_owner END,
           lease_expires_at = CASE WHEN rule_row.releases_lease THEN NULL
                                   ELSE command.lease_expires_at END,
           retry_budget_remaining = CASE WHEN p_to_state = 'RETRY_WAIT'
                                             THEN greatest(command.retry_budget_remaining - 1, 0)
                                         ELSE command.retry_budget_remaining END,
           next_attempt_at = CASE WHEN p_to_state = 'RETRY_WAIT'
                                      THEN clock_timestamp()
                                           + make_interval(
                                               secs => coalesce(p_retry_delay_seconds, 60))
                                  ELSE NULL END,
           failure_code = CASE WHEN p_to_state IN ('FAILED_FINAL', 'COMPENSATION_FAILED')
                                   THEN p_failure_code
                               ELSE NULL END,
           terminal_at = CASE WHEN p_to_state IN ('SUCCEEDED', 'FAILED_FINAL',
                                                  'COMPENSATED', 'COMPENSATION_FAILED')
                                  THEN clock_timestamp()
                              ELSE NULL END,
           updated_at = clock_timestamp()
     WHERE command.id = p_command_id
       AND command.state = command_row.state
       AND command.fence_token = command_row.fence_token
    RETURNING command.state INTO updated_state;

    IF updated_state IS NULL THEN
        RAISE EXCEPTION 'price command % changed while transitioning to %',
            p_command_id, p_to_state
            USING ERRCODE = 'MO030';
    END IF;

    RETURN updated_state;
END;
$$;

REVOKE ALL ON FUNCTION ops.transition_price_command(
    uuid, bigint, text, text, text, integer, uuid) FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- The write path has its own gate, evaluated inside the same transaction that
-- claims a command. It is deliberately not the acquisition call authority: a
-- price command must not cancel a running acquisition, and an acquisition must
-- not invalidate a command that a person has already authorised.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'price_command', 'NO_ROUTE', NULL,
        'write execution state; guarded by its own gate at lease time'),
    ('ops', 'price_command_transition', 'NO_ROUTE', NULL,
        'reviewed state machine; no acquisition authority reads it'),
    ('ops', 'price_command_attempt', 'NO_ROUTE', NULL,
        'append-only attempt evidence; no acquisition authority reads it'),
    ('ops', 'price_command_readback', 'NO_ROUTE', NULL,
        'append-only readback evidence; no acquisition authority reads it'),
    ('ops', 'kill_switch_event', 'NO_ROUTE', NULL,
        'append-only journal of a switch whose state lives in feature_flag');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- The command row is readable and, from the application's side, unwritable
-- except by insertion. State moves only through the two functions above, so the
-- lease, the fence, the reviewed transition set, the success-requires-readback
-- rule and the compensation safety check cannot be bypassed by any SQL client
-- connecting as this role.
GRANT SELECT ON ops.price_command_transition TO marketops_app;
GRANT SELECT, INSERT ON ops.price_command TO marketops_app;
GRANT SELECT, INSERT ON ops.price_command_attempt TO marketops_app;
GRANT SELECT, INSERT ON ops.price_command_readback TO marketops_app;
GRANT SELECT, INSERT ON ops.kill_switch_event TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.evaluate_price_write_gate(uuid) TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.lease_price_command(uuid, text, integer) TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.transition_price_command(
    uuid, bigint, text, text, text, integer, uuid) TO marketops_app;
