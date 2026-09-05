-- The controlled write path for AD_BID_CHANGE.
--
-- This file is the one place in the Slice where something could leave the
-- building, so every invariant here is a property of the database rather than of
-- the code that happens to call it. The application role can read every table
-- below and change none of them; every transition runs through a SECURITY
-- DEFINER function, and the legal transition set is rows in a table rather than
-- branches in a service, so a transition nobody reviewed cannot be reached by a
-- defect.
--
-- Six invariants shape the design, and five of them are the price path's,
-- because they were right there and the Contract requires one execution
-- authority rather than two opinions.
--
-- Platform acceptance is not success. A command reaches SUCCEEDED only after a
-- readback observes the exact approved value, and the transition refuses without
-- a matching readback row at the current fence in the same transaction.
--
-- An unknown result is never retried blindly. UNKNOWN_REQUIRES_READBACK has no
-- edge back to EXECUTING; the only ways out are a readback and a person. A
-- timeout plus "the old bid is still there" is not evidence the write did not
-- land, and the attempt completion below upgrades that combination to
-- UNKNOWN_STATE rather than letting an adapter's optimism through.
--
-- A stale worker gains nothing. A lease and a fence token are checked on every
-- transition, so a worker whose lease was taken over writes no row.
--
-- Compensation cannot overwrite a later legitimate change. It requires the
-- latest readback to still observe the value this command wrote, which is the
-- database asking whether anything else has moved the bid since.
--
-- The sixth is new, and it is the one the Contract adds. A third value — a bid
-- that is neither the approved target nor the captured prior — is never
-- overwritten and never retried. It becomes
-- LATER_CHANGE_OR_MISMATCH_INVESTIGATION, a terminal-for-automation state whose
-- only exit is a person, because somebody or something outside MarketOps owns
-- that number now and guessing which would be worse than stopping.
--
-- Error conditions raised here:
--
--   MO090  AD_BID_COMMAND_AUTHORITY_LOST
--   MO091  AD_BID_COMMAND_TRANSITION_NOT_ALLOWED
--   MO092  AD_BID_COMMAND_WRITE_GATE_CLOSED
--   MO093  AD_BID_COMMAND_SUCCESS_WITHOUT_READBACK
--   MO094  AD_BID_COMMAND_COMPENSATION_UNSAFE
--   MO095  AD_BID_COMMAND_LEASE_INVALID
--   MO096  AD_BID_COMMAND_ATTEMPT_ALREADY_COMPLETED
--   MO097  AD_BID_RESERVATION_CONFLICT
--   MO098  AD_BID_AGGREGATE_ENVELOPE_BLOCKED

-- ---------------------------------------------------------------------------
-- The parameter contract
-- ---------------------------------------------------------------------------

-- A recommendation that will become a bid command carries exactly three
-- parameters and no others. A closed shape is what stops an extra key arriving
-- between approval and execution and meaning something to a later version of the
-- code.
CREATE FUNCTION ops.ad_bid_parameter_contract_is_valid(p_parameters jsonb)
RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_parameters IS NOT NULL
       AND jsonb_typeof(p_parameters) = 'object'
       AND (p_parameters - 'candidateId' - 'direction' - 'targetBid') = '{}'::jsonb
       AND p_parameters ? 'candidateId'
       AND p_parameters ? 'direction'
       AND p_parameters ? 'targetBid'
       AND (p_parameters ->> 'candidateId') ~
           '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       AND (p_parameters ->> 'direction') IN
           ('PROTECTION_DECREASE', 'OPTIMIZATION_INCREASE', 'EXACT_PRIOR_BID_COMPENSATION')
       AND (p_parameters ->> 'targetBid') ~ '^[0-9]{1,14}([.][0-9]{1,4})?$'
$$;
REVOKE ALL ON FUNCTION ops.ad_bid_parameter_contract_is_valid(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_bid_parameter_contract_is_valid(jsonb) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Command
-- ---------------------------------------------------------------------------

CREATE TABLE ops.ad_bid_command (
    id                            uuid           NOT NULL,
    organization_id               uuid           NOT NULL,
    recommendation_id             uuid           NOT NULL,
    approval_decision_id          uuid           NOT NULL,
    store_id                      uuid           NOT NULL,
    ad_native_object_id           uuid           NOT NULL,
    platform_code                 text           NOT NULL,
    capability_id                 uuid           NOT NULL,
    semantic_profile_id           uuid           NOT NULL,
    candidate_id                  uuid           NOT NULL,
    bundle_id                     uuid           NOT NULL,
    reservation_id                uuid           NOT NULL,
    idempotency_key               text           NOT NULL,
    currency_code                 text           NOT NULL,
    bid_unit_code                 text           NOT NULL,
    direction                     text           NOT NULL,
    candidate_basis               text           NOT NULL,
    materiality_route             text           NOT NULL,
    prior_bid_amount              numeric(18, 4) NOT NULL,
    target_bid_amount             numeric(18, 4) NOT NULL,
    prior_configuration_id        uuid           NOT NULL,
    affected_set_digest           text           NOT NULL,
    lineage_generation            integer        NOT NULL,
    entity_version_digest         text           NOT NULL,
    authority_snapshot            jsonb          NOT NULL,
    approval_expires_at           timestamptz    NOT NULL,
    requested_operation           text CHECK (requested_operation = 'READBACK'),
    state                         text           NOT NULL,
    attempt_no                    integer        NOT NULL DEFAULT 0,
    retry_budget_remaining        integer        NOT NULL,
    fence_token                   bigint         NOT NULL DEFAULT 1,
    lease_owner                   text,
    lease_expires_at              timestamptz,
    next_attempt_at               timestamptz,
    failure_code                  text,
    terminal_at                   timestamptz,
    created_at                    timestamptz    NOT NULL,
    updated_at                    timestamptz    NOT NULL,
    CONSTRAINT ad_bid_command_pk PRIMARY KEY (id),
    CONSTRAINT ad_bid_command_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_bid_command_recommendation_fk
        FOREIGN KEY (recommendation_id, organization_id)
        REFERENCES ops.recommendation (id, organization_id),
    CONSTRAINT ad_bid_command_approval_fk
        FOREIGN KEY (approval_decision_id) REFERENCES ops.approval_decision (id),
    CONSTRAINT ad_bid_command_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_bid_command_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_bid_command_capability_fk
        FOREIGN KEY (capability_id, platform_code)
        REFERENCES platform.platform_capability (id, platform_code),
    CONSTRAINT ad_bid_command_profile_fk
        FOREIGN KEY (semantic_profile_id, platform_code)
        REFERENCES platform.ad_semantic_profile (id, platform_code),
    CONSTRAINT ad_bid_command_candidate_fk
        FOREIGN KEY (candidate_id, organization_id)
        REFERENCES ops.ad_bid_candidate (id, organization_id),
    CONSTRAINT ad_bid_command_bundle_fk
        FOREIGN KEY (bundle_id, organization_id)
        REFERENCES ops.ad_decision_policy_bundle (id, organization_id),
    CONSTRAINT ad_bid_command_reservation_fk
        FOREIGN KEY (reservation_id, organization_id)
        REFERENCES ops.ad_action_reservation (id, organization_id),
    CONSTRAINT ad_bid_command_configuration_fk
        FOREIGN KEY (prior_configuration_id, organization_id)
        REFERENCES core.ad_object_configuration_observation (id, organization_id),
    CONSTRAINT ad_bid_command_id_org_uq UNIQUE (id, organization_id),
    -- The idempotency key is the identity a provider retry must not duplicate.
    -- Unique across the whole table, not per store, because the same key
    -- reaching two commands would defeat its purpose entirely.
    CONSTRAINT ad_bid_command_idempotency_uq UNIQUE (idempotency_key),
    CONSTRAINT ad_bid_command_idempotency_ck
        CHECK (idempotency_key ~ '^[a-z0-9][a-z0-9._-]{15,127}$'),
    CONSTRAINT ad_bid_command_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_bid_command_unit_ck
        CHECK (bid_unit_code IN ('CURRENCY_MAJOR', 'CURRENCY_MINOR')),
    CONSTRAINT ad_bid_command_direction_ck
        CHECK (direction IN
            ('PROTECTION_DECREASE', 'OPTIMIZATION_INCREASE', 'EXACT_PRIOR_BID_COMPENSATION')),
    CONSTRAINT ad_bid_command_basis_ck
        CHECK (candidate_basis IN ('MAX_CPC_BOUNDED', 'CAUSE_BOUND_PROTECTION_STEP')),
    -- Three routes exist in the model. Only MATERIAL_IMPACT is reachable in this
    -- Slice, because no Ordinary-route promotion record can be created; the
    -- other two exist so the state is representable when one can.
    CONSTRAINT ad_bid_command_materiality_ck
        CHECK (materiality_route IN
            ('MATERIAL_IMPACT', 'ORDINARY_IMPACT', 'MATERIALITY_UNRESOLVED')),
    -- A command whose materiality could not be resolved never runs.
    CONSTRAINT ad_bid_command_unresolved_materiality_ck
        CHECK (materiality_route <> 'MATERIALITY_UNRESOLVED'
            OR state IN ('TERMINATED_WITHOUT_PROVIDER_CALL', 'FAILED_FINAL')),
    CONSTRAINT ad_bid_command_amounts_ck
        CHECK (prior_bid_amount >= 0 AND target_bid_amount >= 0),
    CONSTRAINT ad_bid_command_change_ck CHECK (target_bid_amount <> prior_bid_amount),
    CONSTRAINT ad_bid_command_direction_agrees_ck
        CHECK (direction = 'EXACT_PRIOR_BID_COMPENSATION'
            OR (direction = 'OPTIMIZATION_INCREASE')
               = (target_bid_amount > prior_bid_amount)),
    CONSTRAINT ad_bid_command_digest_ck
        CHECK (entity_version_digest ~ '^[0-9a-f]{64}$'
            AND affected_set_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ad_bid_command_generation_ck CHECK (lineage_generation >= 1),
    -- Fifteen states. Nothing here collapses provider acceptance, readback,
    -- protection completion, operational success and settled confirmation into
    -- one generic SUCCESS.
    CONSTRAINT ad_bid_command_state_ck
        CHECK (state IN (
            'PENDING', 'LEASED', 'EXECUTING', 'PLATFORM_PENDING', 'READBACK_PENDING',
            'READBACK_MATCHED', 'RETRY_WAIT', 'UNKNOWN_REQUIRES_READBACK',
            'READBACK_MISMATCH', 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION',
            'MANUAL_RESOLUTION', 'FAILED_FINAL', 'TERMINATED_WITHOUT_PROVIDER_CALL',
            'COMPENSATION_PENDING', 'COMPENSATED', 'COMPENSATION_FAILED')),
    CONSTRAINT ad_bid_command_attempt_ck CHECK (attempt_no >= 0),
    CONSTRAINT ad_bid_command_retry_budget_ck CHECK (retry_budget_remaining >= 0),
    CONSTRAINT ad_bid_command_fence_ck CHECK (fence_token > 0),
    CONSTRAINT ad_bid_command_lease_pairing_ck
        CHECK (num_nonnulls(lease_owner, lease_expires_at) <> 1),
    CONSTRAINT ad_bid_command_leased_state_ck
        CHECK (state NOT IN ('LEASED', 'EXECUTING', 'PLATFORM_PENDING', 'READBACK_PENDING')
            OR lease_owner IS NOT NULL),
    CONSTRAINT ad_bid_command_terminal_ck
        CHECK ((state IN ('READBACK_MATCHED', 'FAILED_FINAL',
                          'TERMINATED_WITHOUT_PROVIDER_CALL', 'COMPENSATED',
                          'COMPENSATION_FAILED'))
            = (terminal_at IS NOT NULL)),
    CONSTRAINT ad_bid_command_failure_ck
        CHECK (state NOT IN ('FAILED_FINAL', 'COMPENSATION_FAILED',
                             'TERMINATED_WITHOUT_PROVIDER_CALL')
            OR failure_code IS NOT NULL)
);

-- One live command per advertising object. A second in-flight change to the same
-- bid would race with the first and make the readback ambiguous.
CREATE UNIQUE INDEX ad_bid_command_live_uq
    ON ops.ad_bid_command (ad_native_object_id)
    WHERE state NOT IN ('READBACK_MATCHED', 'FAILED_FINAL',
                        'TERMINATED_WITHOUT_PROVIDER_CALL', 'COMPENSATED',
                        'COMPENSATION_FAILED');
CREATE INDEX ad_bid_command_queue_ix
    ON ops.ad_bid_command (state, next_attempt_at)
    WHERE state IN ('PENDING', 'RETRY_WAIT');
CREATE INDEX ad_bid_command_recommendation_ix ON ops.ad_bid_command (recommendation_id);
CREATE INDEX ad_bid_command_store_ix
    ON ops.ad_bid_command (store_id, state, created_at DESC);
-- The aggregate envelope counts unresolved transmitted writes from here.
CREATE INDEX ad_bid_command_unresolved_ix
    ON ops.ad_bid_command (organization_id, state)
    WHERE state IN ('UNKNOWN_REQUIRES_READBACK', 'READBACK_MISMATCH',
                    'LATER_CHANGE_OR_MISMATCH_INVESTIGATION', 'MANUAL_RESOLUTION');

-- The complete allowed transition set, as data.
--
-- Keeping the state machine in a table rather than in code means a transition
-- that was never reviewed cannot be reached by an application defect, and the
-- absence of a transition — such as UNKNOWN_REQUIRES_READBACK back to
-- EXECUTING, or anything at all out of LATER_CHANGE_OR_MISMATCH_INVESTIGATION
-- except a person — is asserted directly by reading this table.
CREATE TABLE ops.ad_bid_command_transition (
    from_state     text    NOT NULL,
    to_state       text    NOT NULL,
    requires_lease boolean NOT NULL,
    releases_lease boolean NOT NULL,
    note           text    NOT NULL,
    CONSTRAINT ad_bid_command_transition_pk PRIMARY KEY (from_state, to_state),
    CONSTRAINT ad_bid_command_transition_distinct_ck CHECK (from_state <> to_state)
);

INSERT INTO ops.ad_bid_command_transition
    (from_state, to_state, requires_lease, releases_lease, note) VALUES
    ('PENDING', 'LEASED', false, false,
        'a worker claims the command through the leasing function'),
    ('PENDING', 'TERMINATED_WITHOUT_PROVIDER_CALL', false, false,
        'a quarantine or kill activated before anything was sent'),
    ('LEASED', 'EXECUTING', true, false,
        'the adapter call is about to be made'),
    ('LEASED', 'PENDING', true, true,
        'the worker released the claim without calling the platform'),
    ('LEASED', 'TERMINATED_WITHOUT_PROVIDER_CALL', true, true,
        'live pre-transmission revalidation refused the send'),
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
    ('READBACK_PENDING', 'READBACK_MATCHED', true, true,
        'a readback observed the exact approved target'),
    ('READBACK_PENDING', 'READBACK_MISMATCH', true, true,
        'a readback observed the captured prior value'),
    ('READBACK_PENDING', 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION', true, true,
        'a readback observed a third value that nothing in this lineage wrote'),
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
    ('RETRY_WAIT', 'TERMINATED_WITHOUT_PROVIDER_CALL', false, false,
        'a quarantine or kill activated while the command was waiting'),
    ('UNKNOWN_REQUIRES_READBACK', 'READBACK_PENDING', false, false,
        'a readback attempt is authorised; the write itself is never repeated'),
    ('UNKNOWN_REQUIRES_READBACK', 'MANUAL_RESOLUTION', false, false,
        'an operator took the unresolved command over'),
    ('READBACK_MISMATCH', 'MANUAL_RESOLUTION', false, false,
        'an operator took the mismatch over'),
    ('READBACK_MISMATCH', 'COMPENSATION_PENDING', false, false,
        'an operator authorised restoring the captured prior bid'),
    ('LATER_CHANGE_OR_MISMATCH_INVESTIGATION', 'MANUAL_RESOLUTION', false, false,
        'an operator took the externally-owned value over; nothing automatic may'),
    ('MANUAL_RESOLUTION', 'COMPENSATION_PENDING', false, false,
        'an operator authorised restoring the captured prior bid'),
    ('MANUAL_RESOLUTION', 'READBACK_MATCHED', false, false,
        'an operator confirmed the approved target against a matching readback'),
    ('MANUAL_RESOLUTION', 'FAILED_FINAL', false, false,
        'an operator closed the command as failed'),
    ('COMPENSATION_PENDING', 'COMPENSATED', true, true,
        'the captured prior bid was restored and read back'),
    ('COMPENSATION_PENDING', 'COMPENSATION_FAILED', true, true,
        'the restore could not be completed'),
    ('COMPENSATION_PENDING', 'MANUAL_RESOLUTION', false, true,
        'the restore was withdrawn and returned to an operator');

-- ---------------------------------------------------------------------------
-- Attempts, readbacks and exact response custody
-- ---------------------------------------------------------------------------

CREATE TABLE ops.ad_bid_command_attempt (
    id                    uuid        NOT NULL,
    command_id            uuid        NOT NULL,
    attempt_no            integer     NOT NULL,
    purpose               text        NOT NULL,
    fence_token           bigint      NOT NULL,
    lease_owner           text        NOT NULL,
    started_at            timestamptz NOT NULL,
    completed_at          timestamptz,
    outcome_class         text        NOT NULL,
    native_status         text,
    native_task_key       text,
    raw_observation_id    uuid,
    error_code            text,
    correlation_id        text        NOT NULL,
    request_digest        text        NOT NULL,
    operation_snapshot    jsonb       NOT NULL,
    expected_version_token text,
    CONSTRAINT ad_bid_command_attempt_pk PRIMARY KEY (id),
    CONSTRAINT ad_bid_command_attempt_command_fk
        FOREIGN KEY (command_id) REFERENCES ops.ad_bid_command (id),
    CONSTRAINT ad_bid_command_attempt_no_uq UNIQUE (command_id, attempt_no),
    CONSTRAINT ad_bid_command_attempt_no_ck CHECK (attempt_no > 0),
    CONSTRAINT ad_bid_command_attempt_purpose_ck
        CHECK (purpose IN ('APPLY', 'STATUS_ENQUIRY', 'READBACK', 'RESTORE')),
    CONSTRAINT ad_bid_command_attempt_fence_ck CHECK (fence_token > 0),
    CONSTRAINT ad_bid_command_attempt_outcome_ck
        CHECK (outcome_class IN (
            'IN_FLIGHT', 'ACCEPTED', 'REJECTED', 'RETRIABLE_ERROR',
            'TIMEOUT', 'UNKNOWN_STATE')),
    CONSTRAINT ad_bid_command_attempt_completion_ck
        CHECK ((outcome_class = 'IN_FLIGHT') = (completed_at IS NULL)),
    CONSTRAINT ad_bid_command_attempt_digest_ck
        CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ad_bid_command_attempt_snapshot_ck
        CHECK (jsonb_typeof(operation_snapshot) = 'object'),
    CONSTRAINT ad_bid_command_attempt_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX ad_bid_command_attempt_command_ix
    ON ops.ad_bid_command_attempt (command_id, started_at DESC);

CREATE TABLE ops.ad_bid_command_readback (
    id                 uuid           NOT NULL,
    command_id         uuid           NOT NULL,
    attempt_id         uuid           NOT NULL,
    observed_at        timestamptz    NOT NULL,
    observed_bid       numeric(18, 4),
    currency_code      text,
    bid_unit_code      text,
    match_state        text           NOT NULL,
    raw_observation_id uuid           NOT NULL,
    correlation_id     text           NOT NULL,
    CONSTRAINT ad_bid_command_readback_pk PRIMARY KEY (id),
    CONSTRAINT ad_bid_command_readback_command_fk
        FOREIGN KEY (command_id) REFERENCES ops.ad_bid_command (id),
    CONSTRAINT ad_bid_command_readback_attempt_fk
        FOREIGN KEY (attempt_id) REFERENCES ops.ad_bid_command_attempt (id),
    CONSTRAINT ad_bid_command_readback_attempt_uq UNIQUE (attempt_id),
    -- Four outcomes and no tolerance band. MATCHES_TARGET means equal to the
    -- exact approved native value; DIFFERENT is the third value nothing in this
    -- lineage wrote.
    CONSTRAINT ad_bid_command_readback_match_ck
        CHECK (match_state IN ('MATCHES_TARGET', 'MATCHES_PRIOR', 'DIFFERENT', 'UNREADABLE')),
    CONSTRAINT ad_bid_command_readback_value_ck
        CHECK ((match_state = 'UNREADABLE')
            = (observed_bid IS NULL AND currency_code IS NULL AND bid_unit_code IS NULL)),
    CONSTRAINT ad_bid_command_readback_currency_ck
        CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_bid_command_readback_unit_ck
        CHECK (bid_unit_code IS NULL
            OR bid_unit_code IN ('CURRENCY_MAJOR', 'CURRENCY_MINOR')),
    CONSTRAINT ad_bid_command_readback_bid_ck
        CHECK (observed_bid IS NULL OR observed_bid >= 0),
    CONSTRAINT ad_bid_command_readback_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX ad_bid_command_readback_command_ix
    ON ops.ad_bid_command_readback (command_id, observed_at DESC);

-- Exact response custody shares raw.raw_content and the single RawCustody port,
-- exactly as the price response observation does. It is not an acquisition run:
-- a write cannot fabricate an ingestion identity.
CREATE TABLE raw.ad_bid_response_observation (
    id                uuid           PRIMARY KEY,
    command_id        uuid           NOT NULL REFERENCES ops.ad_bid_command (id),
    attempt_id        uuid           NOT NULL UNIQUE
                                     REFERENCES ops.ad_bid_command_attempt (id) ON DELETE CASCADE,
    raw_content_id    uuid           NOT NULL REFERENCES raw.raw_content (id),
    request_digest    text           NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    http_status       integer        NOT NULL CHECK (http_status BETWEEN 100 AND 599),
    response_headers  jsonb          NOT NULL CHECK (jsonb_typeof(response_headers) = 'object'),
    evidence_class    text           NOT NULL
                                     CHECK (evidence_class IN ('PROTOCOL_FIXTURE', 'PROVIDER_RESPONSE')),
    response_complete boolean        NOT NULL,
    operation_id      uuid,
    operation_version bigint,
    observed_bid      numeric(18, 4),
    observed_currency text,
    observed_unit     text,
    version_token     text,
    observed_at       timestamptz    NOT NULL,
    correlation_id    text           NOT NULL
);
ALTER TABLE ops.ad_bid_command_attempt ADD CONSTRAINT ad_bid_command_attempt_raw_fk
    FOREIGN KEY (raw_observation_id) REFERENCES raw.ad_bid_response_observation (id);
ALTER TABLE ops.ad_bid_command_readback ADD CONSTRAINT ad_bid_command_readback_raw_fk
    FOREIGN KEY (raw_observation_id) REFERENCES raw.ad_bid_response_observation (id);

-- ---------------------------------------------------------------------------
-- Authority snapshot
-- ---------------------------------------------------------------------------

-- Everything the approval was given against, frozen as one comparable value.
-- Approval, guardrail and command all carry it; the gate re-derives it and
-- compares. A snapshot that captured only metrics would not bind the target, the
-- object, the affected set or the bundle, and each of those is a way the meaning
-- of an approval could change between the click and the send.
CREATE FUNCTION ops.ad_bid_authority_snapshot(p_recommendation_id uuid)
RETURNS jsonb
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, core, platform, mart, pg_temp
AS $$
    SELECT jsonb_build_object(
        'proposal', jsonb_build_object(
            'id', r.id, 'organizationId', r.organization_id, 'storeId', r.store_id,
            'subjectKind', r.subject_kind, 'subjectId', r.subject_id,
            'actionKind', r.action_kind, 'parameters', r.proposed_parameters,
            'risk', r.risk_label, 'window', r.window_code,
            'validUntil', r.valid_until, 'entityDigest', r.entity_version_digest),
        'platformCode', obj.platform_code,
        'nativeObjectKey', obj.native_object_key,
        'nativeCampaignKey', obj.native_campaign_key,
        'nativeObjectKind', obj.native_object_kind,
        'lineageKey', obj.lineage_key,
        'lineageGeneration', obj.lineage_generation,
        'controlGranularityState', obj.control_granularity_state,
        'biddingMode', obj.bidding_mode,
        'semanticProfileId', obj.semantic_profile_id,
        'affectedSet', affected.item,
        'currentConfiguration', config.item,
        'candidate', candidate.item)
      FROM ops.recommendation r
      LEFT JOIN core.ad_native_object obj
        ON obj.id = r.subject_id AND obj.organization_id = r.organization_id
       AND r.subject_kind = 'AD_NATIVE_OBJECT'
      LEFT JOIN LATERAL (
          SELECT jsonb_build_object('id', a.id, 'digest', a.affected_set_digest,
                     'resolution', a.resolution_state,
                     'variantIds', to_jsonb(a.product_variant_ids)) AS item
            FROM core.ad_affected_set a
           WHERE a.ad_native_object_id = obj.id AND a.organization_id = r.organization_id
           ORDER BY a.resolved_at DESC, a.id DESC LIMIT 1
      ) affected ON true
      LEFT JOIN LATERAL (
          SELECT jsonb_build_object('id', c.id, 'bid', c.observed_bid_amount,
                     'currency', c.bid_currency_code, 'unit', c.bid_unit_code,
                     'status', c.observed_status, 'grade', c.evidence_grade,
                     'generation', c.lineage_generation,
                     'observedAt', c.observed_at) AS item
            FROM core.ad_object_configuration_observation c
           WHERE c.ad_native_object_id = obj.id AND c.organization_id = r.organization_id
             AND NOT EXISTS (SELECT 1 FROM core.ad_object_configuration_observation later
                              WHERE later.supersedes_observation_id = c.id)
           ORDER BY c.observed_at DESC, c.id DESC LIMIT 1
      ) config ON true
      LEFT JOIN LATERAL (
          SELECT jsonb_build_object('id', cd.id, 'direction', cd.direction,
                     'basis', cd.candidate_basis, 'target', cd.provider_normalized_amount,
                     'currency', cd.currency_code, 'unit', cd.bid_unit_code,
                     'digest', cd.affected_set_digest,
                     'targetPolicyId', cd.target_policy_id,
                     'maxCpc', cd.max_cpc_amount) AS item
            FROM ops.ad_bid_candidate cd
           WHERE cd.organization_id = r.organization_id
             AND cd.id = CASE WHEN ops.ad_bid_parameter_contract_is_valid(r.proposed_parameters)
                              THEN (r.proposed_parameters ->> 'candidateId')::uuid END
      ) candidate ON true
     WHERE r.id = p_recommendation_id
$$;
REVOKE ALL ON FUNCTION ops.ad_bid_authority_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_bid_authority_snapshot(uuid) TO marketops_app;

CREATE FUNCTION ops.bind_ad_bid_authority_snapshot()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, platform, pg_temp
AS $$
DECLARE snapshot jsonb;
BEGIN
    SELECT ops.ad_bid_authority_snapshot(NEW.recommendation_id) INTO snapshot;
    IF snapshot IS NULL OR snapshot #>> '{proposal,organizationId}'
            IS DISTINCT FROM NEW.organization_id::text THEN
        RAISE EXCEPTION 'recommendation ownership does not match' USING ERRCODE = 'MO092';
    END IF;
    NEW.authority_snapshot := snapshot;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.bind_ad_bid_authority_snapshot() FROM PUBLIC;
CREATE TRIGGER ad_bid_command_bind_snapshot BEFORE INSERT ON ops.ad_bid_command
    FOR EACH ROW EXECUTE FUNCTION ops.bind_ad_bid_authority_snapshot();

-- Whether the command still describes the same decision the approval was given
-- for. Every conjunct is a way the meaning could have drifted.
CREATE FUNCTION ops.ad_bid_command_authority_matches(p_command_id uuid)
RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, core, platform, pg_temp
AS $$
    SELECT coalesce(bool_and(
        c.authority_snapshot = ops.ad_bid_authority_snapshot(r.id)
        AND a.recommendation_id = r.id AND a.organization_id = c.organization_id
        AND r.organization_id = c.organization_id AND r.store_id = c.store_id
        AND r.subject_kind = 'AD_NATIVE_OBJECT'
        AND r.subject_id = c.ad_native_object_id
        AND r.action_kind = 'AD_BID_CHANGE'
        AND r.valid_until > statement_timestamp()
        AND c.approval_expires_at > statement_timestamp()
        AND c.entity_version_digest = r.entity_version_digest
        AND ops.ad_bid_parameter_contract_is_valid(r.proposed_parameters)
        AND c.idempotency_key = 'abc-' || r.id::text
        AND c.candidate_id = (r.proposed_parameters ->> 'candidateId')::uuid
        AND c.direction = (r.proposed_parameters ->> 'direction')
        AND c.target_bid_amount = (r.proposed_parameters ->> 'targetBid')::numeric
        -- The transmitted number is the generated, provider-normalized one. If
        -- these ever differ, something rounded after the approval.
        AND c.target_bid_amount = cd.provider_normalized_amount
        AND c.currency_code = cd.currency_code
        AND c.bid_unit_code = cd.bid_unit_code
        AND c.candidate_basis = cd.candidate_basis
        AND c.direction = cd.direction
        AND c.affected_set_digest = cd.affected_set_digest
        -- The affected set has not changed since the candidate was generated.
        AND c.affected_set_digest = (c.authority_snapshot #>> '{affectedSet,digest}')
        AND (c.authority_snapshot #>> '{affectedSet,resolution}') = 'COMPLETE'
        -- The object is the same generation, and still independently controllable.
        AND c.lineage_generation = obj.lineage_generation
        AND obj.control_granularity_state = 'PROVEN_INDEPENDENT'
        AND obj.status = 'ACTIVE'
        -- The prior bid the command captured is still the observed one.
        AND c.prior_configuration_id::text = (c.authority_snapshot #>> '{currentConfiguration,id}')
        AND c.prior_bid_amount = (c.authority_snapshot #>> '{currentConfiguration,bid}')::numeric
        AND c.currency_code = (c.authority_snapshot #>> '{currentConfiguration,currency}')
        -- The capability is the advertising one, verified and live.
        AND cap.capability_code = 'ad-bid-change'
        AND cap.read_write_class = 'WRITE'
        AND cap.verification_state = 'VERIFIED'
        AND cap.status = 'ACTIVE'
        AND cap.deprecated_at IS NULL
        AND cap.platform_code = c.platform_code
        -- The bundle is the unique complete active one for this exact scope.
        AND b.status = 'ACTIVE'
        AND b.validation_state = 'VALIDATED'
        AND b.organization_id = c.organization_id
        AND b.store_id = c.store_id
        AND b.direction = c.direction
        AND b.candidate_basis = c.candidate_basis
        AND b.native_object_kind = obj.native_object_kind
        AND b.effective_from <= statement_timestamp()
        AND (b.effective_to IS NULL OR b.effective_to > statement_timestamp())
        -- The reservation is this command's, and still held.
        AND res.state = 'ACTIVE'
        AND res.ad_native_object_id = c.ad_native_object_id
        AND res.affected_set_digest = c.affected_set_digest), false)
      FROM ops.ad_bid_command c
      JOIN ops.recommendation r
        ON r.id = c.recommendation_id AND r.organization_id = c.organization_id
      JOIN ops.approval_decision a ON a.id = c.approval_decision_id
      JOIN ops.ad_bid_candidate cd
        ON cd.id = c.candidate_id AND cd.organization_id = c.organization_id
      JOIN core.ad_native_object obj
        ON obj.id = c.ad_native_object_id AND obj.organization_id = c.organization_id
      JOIN platform.platform_capability cap ON cap.id = c.capability_id
      JOIN ops.ad_decision_policy_bundle b
        ON b.id = c.bundle_id AND b.organization_id = c.organization_id
      JOIN ops.ad_action_reservation res
        ON res.id = c.reservation_id AND res.organization_id = c.organization_id
     WHERE c.id = p_command_id
$$;
REVOKE ALL ON FUNCTION ops.ad_bid_command_authority_matches(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_bid_command_authority_matches(uuid) TO marketops_app;

-- ---------------------------------------------------------------------------
-- The write gate
-- ---------------------------------------------------------------------------

-- Empty array is the only permission. Every other result is a refusal carrying
-- the reasons, and the reasons are business words rather than SQL, because the
-- person who has to act on a refusal is an operator and not a DBA.
--
-- The gate is evaluated at three points: when a worker leases the command, when
-- it leases a compensation, and again inside the attempt-opening function for
-- APPLY and RESTORE. The third is the transmission boundary: it is the last
-- moment before something leaves, and it is where a quarantine activated one
-- second ago takes effect.
CREATE FUNCTION ops.evaluate_ad_bid_write_gate(p_command_id uuid)
RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, platform, iam, pg_temp
AS $$
DECLARE
    command      ops.ad_bid_command%ROWTYPE;
    envelope     core.ad_exposure_envelope%ROWTYPE;
    reasons      text[] := '{}';
    containment  text[];
    overlapping  uuid;
    active_count integer;
    unresolved   integer;
    cumulative   numeric;
BEGIN
    SELECT * INTO command FROM ops.ad_bid_command WHERE id = p_command_id;
    IF NOT FOUND THEN
        RETURN ARRAY['COMMAND_NOT_FOUND'];
    END IF;

    IF NOT ops.ad_bid_command_authority_matches(p_command_id) THEN
        reasons := reasons || 'COMMAND_AUTHORITY_MISMATCH';
    END IF;

    -- Capability verification, per platform and per store. Ozon evidence never
    -- authorizes Wildberries, which is why the subject status is checked for
    -- this store rather than for the platform.
    IF NOT EXISTS (
        SELECT 1 FROM platform.platform_capability cap
         WHERE cap.id = command.capability_id
           AND cap.capability_code = 'ad-bid-change'
           AND cap.read_write_class = 'WRITE'
           AND cap.verification_state = 'VERIFIED'
           AND cap.status = 'ACTIVE'
           AND cap.deprecated_at IS NULL) THEN
        reasons := reasons || 'CAPABILITY_NOT_VERIFIED';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM platform.capability_subject_status s
         WHERE s.capability_id = command.capability_id
           AND s.store_id = command.store_id
           AND s.availability = 'AVAILABLE') THEN
        reasons := reasons || 'CAPABILITY_NOT_AVAILABLE_FOR_STORE';
    END IF;

    -- The feature flag, at its own scopes. Missing is off, at every scope.
    IF NOT EXISTS (
        SELECT 1 FROM platform.feature_flag f
         WHERE f.flag_code = 'ad-bid-change-write'
           AND f.scope_kind = 'CAPABILITY' AND f.state = 'ENABLED') THEN
        reasons := reasons || 'CAPABILITY_SWITCH_DISABLED';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM platform.feature_flag f
         WHERE f.flag_code = 'ad-bid-change-write'
           AND f.scope_kind = 'GLOBAL' AND f.state = 'ENABLED') THEN
        reasons := reasons || 'GLOBAL_SWITCH_DISABLED';
    END IF;
    IF EXISTS (
        SELECT 1 FROM platform.feature_flag f
         WHERE f.flag_code = 'ad-bid-change-write'
           AND f.scope_kind IN ('PLATFORM', 'MARKETPLACE_ACCOUNT', 'STORE')
           AND f.state = 'DISABLED') THEN
        reasons := reasons || 'SCOPED_SWITCH_DISABLED';
    END IF;

    -- The entity allowlist. A Pilot that enabled a capability without naming the
    -- objects it may touch would be an unbounded Pilot.
    IF NOT EXISTS (
        SELECT 1 FROM ops.pilot_allowlist_entry entry
         WHERE entry.organization_id = command.organization_id
           AND entry.action_kind = 'AD_BID_CHANGE'
           AND entry.ad_native_object_id = command.ad_native_object_id
           AND entry.status = 'ACTIVE'
           AND entry.valid_from <= statement_timestamp()
           AND (entry.valid_until IS NULL OR entry.valid_until > statement_timestamp())) THEN
        reasons := reasons || 'ENTITY_NOT_ALLOWLISTED';
    END IF;

    -- Approval and its lease. Expiry is checked here and again at every later
    -- point, because waiting never extends it.
    IF NOT EXISTS (
        SELECT 1 FROM ops.approval_decision a
         WHERE a.id = command.approval_decision_id
           AND a.recommendation_id = command.recommendation_id
           AND a.decision IN ('APPROVED', 'POLICY_AUTHORIZED')
           AND a.scope_expires_at > statement_timestamp()) THEN
        reasons := reasons || 'AUTHORIZATION_INVALID_OR_EXPIRED';
    END IF;
    IF command.approval_expires_at <= statement_timestamp() THEN
        reasons := reasons || 'APPROVAL_LEASE_EXPIRED';
    END IF;

    -- The recommendation must still be the live one for this object and action.
    IF NOT EXISTS (
        SELECT 1 FROM ops.recommendation r
         WHERE r.id = command.recommendation_id
           AND r.state IN ('APPROVED', 'POLICY_AUTHORIZED', 'COMMAND_CREATED',
                           'EXECUTION_TRACKING')
           AND r.valid_until > statement_timestamp()) THEN
        reasons := reasons || 'RECOMMENDATION_STALE';
    END IF;

    -- The affected set has not moved under the approval.
    IF NOT EXISTS (
        SELECT 1 FROM core.ad_affected_set a
         WHERE a.organization_id = command.organization_id
           AND a.ad_native_object_id = command.ad_native_object_id
           AND a.affected_set_digest = command.affected_set_digest
           AND a.resolution_state = 'COMPLETE') THEN
        reasons := reasons || 'AFFECTED_SET_DIGEST_CHANGED';
    END IF;

    -- Mapping health for every variant this object promotes. An unresolved or
    -- conflicted mapping means we do not know whose sales this bid affects.
    IF EXISTS (
        SELECT 1 FROM core.ad_affected_set a
         CROSS JOIN LATERAL unnest(a.product_variant_ids) AS variant(id)
         WHERE a.organization_id = command.organization_id
           AND a.ad_native_object_id = command.ad_native_object_id
           AND a.affected_set_digest = command.affected_set_digest
           AND NOT EXISTS (
               SELECT 1 FROM core.listing_mapping m
                WHERE m.organization_id = a.organization_id
                  AND m.product_variant_id = variant.id
                  AND m.status = 'ACTIVE'
                  AND m.effective_from <= statement_timestamp()
                  AND (m.effective_to IS NULL OR m.effective_to > statement_timestamp()))) THEN
        reasons := reasons || 'MAPPING_UNRESOLVED';
    END IF;
    IF EXISTS (
        SELECT 1 FROM core.ad_affected_set a
         CROSS JOIN LATERAL unnest(a.product_variant_ids) AS variant(id)
          JOIN core.listing_mapping m
            ON m.organization_id = a.organization_id
           AND m.product_variant_id = variant.id
          JOIN core.mapping_conflict conflict
            ON conflict.platform_listing_variant_id = m.platform_listing_variant_id
           AND conflict.status = 'OPEN'
         WHERE a.organization_id = command.organization_id
           AND a.ad_native_object_id = command.ad_native_object_id
           AND a.affected_set_digest = command.affected_set_digest) THEN
        reasons := reasons || 'MAPPING_CONFLICT_OPEN';
    END IF;

    -- A passing execution guardrail bound to the same authority snapshot.
    IF NOT EXISTS (
        SELECT 1 FROM ops.guardrail_evaluation g
         WHERE g.recommendation_id = command.recommendation_id
           AND g.organization_id = command.organization_id
           AND g.purpose = 'EXECUTION'
           AND g.outcome = 'PASS') THEN
        reasons := reasons || 'GUARDRAIL_NOT_PASSED';
    END IF;

    -- The bundle: unique, complete, active, validated and covering this scope.
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_decision_policy_bundle b
         WHERE b.id = command.bundle_id
           AND b.status = 'ACTIVE'
           AND b.validation_state = 'VALIDATED'
           AND b.effective_from <= statement_timestamp()
           AND (b.effective_to IS NULL OR b.effective_to > statement_timestamp())) THEN
        reasons := reasons || 'BUNDLE_UNRESOLVED';
    ELSIF NOT EXISTS (
        SELECT 1 FROM ops.ad_decision_policy_bundle b
          JOIN core.ad_native_object obj
            ON obj.id = command.ad_native_object_id
           AND obj.organization_id = command.organization_id
         WHERE b.id = command.bundle_id
           AND b.store_id = command.store_id
           AND b.direction = command.direction
           AND b.candidate_basis = command.candidate_basis
           AND b.native_object_kind = obj.native_object_kind
           AND b.capability_code = 'ad-bid-change') THEN
        reasons := reasons || 'BUNDLE_SCOPE_EXCEEDED';
    END IF;

    -- Direction and candidate basis have to be the ones the bundle enabled.
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_decision_policy_bundle b
         WHERE b.id = command.bundle_id AND b.direction = command.direction) THEN
        reasons := reasons || 'DIRECTION_NOT_ENABLED';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_decision_policy_bundle b
         WHERE b.id = command.bundle_id AND b.candidate_basis = command.candidate_basis) THEN
        reasons := reasons || 'CANDIDATE_BASIS_NOT_ENABLED';
    END IF;

    -- The Ordinary route exists only where a promotion record says so, and this
    -- Slice creates none. An ordinary-routed command therefore always refuses.
    IF command.materiality_route = 'ORDINARY_IMPACT' THEN
        reasons := reasons || 'ORDINARY_ROUTE_NOT_PROMOTED';
    END IF;
    IF command.materiality_route = 'MATERIALITY_UNRESOLVED' THEN
        reasons := reasons || 'MATERIALITY_UNRESOLVED';
    END IF;

    -- Containment, at every scope it can be held at.
    containment := ops.ad_active_containment(
        command.organization_id, command.ad_native_object_id, command.store_id,
        command.platform_code, 'ad-bid-change', command.affected_set_digest);
    IF 'KILL_SWITCH_ACTIVE' = ANY(containment) THEN
        reasons := reasons || 'KILL_SWITCH_ACTIVE';
    END IF;
    IF cardinality(containment) > 0
        AND NOT (containment = ARRAY['KILL_SWITCH_ACTIVE']) THEN
        reasons := reasons || 'QUARANTINE_ACTIVE';
    END IF;

    -- Reservation: this command must hold one, and nothing else may overlap it.
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_action_reservation res
         WHERE res.id = command.reservation_id
           AND res.state = 'ACTIVE'
           AND res.ad_native_object_id = command.ad_native_object_id) THEN
        reasons := reasons || 'RESERVATION_CONFLICT';
    ELSE
        SELECT o.reservation_id INTO overlapping
          FROM ops.ad_action_reservation res
         CROSS JOIN LATERAL ops.ad_overlapping_reservation(
             command.organization_id, res.product_variant_ids, command.ad_native_object_id) AS o
         WHERE res.id = command.reservation_id
         LIMIT 1;
        IF overlapping IS NOT NULL THEN
            reasons := reasons || 'RESERVATION_CONFLICT';
        END IF;
    END IF;

    -- The aggregate envelope. Every axis is checked independently; there is no
    -- point in this function where one axis's slack is added to another's.
    SELECT * INTO envelope
      FROM core.ad_exposure_envelope e
     WHERE e.organization_id = command.organization_id
       AND e.status IN ('ACTIVE', 'RETIRED')
       AND e.effective_from <= statement_timestamp()
       AND (e.effective_to IS NULL OR e.effective_to > statement_timestamp())
     ORDER BY CASE e.scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
              e.effective_from DESC
     LIMIT 1;
    IF NOT FOUND THEN
        reasons := reasons || 'AGGREGATE_ENVELOPE_UNRESOLVED';
    ELSE
        SELECT count(*) INTO active_count
          FROM ops.ad_action_reservation res
         WHERE res.organization_id = command.organization_id AND res.state = 'ACTIVE';
        -- Ordinary work may not consume the reserved recovery headroom. A
        -- compensation may, which is what the headroom is for.
        IF command.direction <> 'EXACT_PRIOR_BID_COMPENSATION'
            AND active_count > envelope.max_active_interventions
                               - envelope.reserved_recovery_headroom_count THEN
            reasons := reasons || 'AGGREGATE_ENVELOPE_BLOCKED';
        ELSIF active_count > envelope.max_active_interventions THEN
            reasons := reasons || 'AGGREGATE_ENVELOPE_BLOCKED';
        END IF;

        SELECT count(*) INTO unresolved
          FROM ops.ad_bid_command other
         WHERE other.organization_id = command.organization_id
           AND other.state IN ('UNKNOWN_REQUIRES_READBACK', 'READBACK_MISMATCH',
                               'LATER_CHANGE_OR_MISMATCH_INVESTIGATION', 'MANUAL_RESOLUTION');
        IF unresolved > envelope.max_unresolved_transmitted_writes THEN
            reasons := reasons || 'AGGREGATE_ENVELOPE_BLOCKED';
        END IF;

        SELECT coalesce(sum(abs(other.target_bid_amount - other.prior_bid_amount)), 0)
          INTO cumulative
          FROM ops.ad_bid_command other
         WHERE other.organization_id = command.organization_id
           AND other.created_at > statement_timestamp()
                                  - make_interval(hours => envelope.cumulative_window_hours);
        IF cumulative + abs(command.target_bid_amount - command.prior_bid_amount)
                > envelope.max_cumulative_bid_change_amount THEN
            reasons := reasons || 'AGGREGATE_ENVELOPE_BLOCKED';
        END IF;
    END IF;

    RETURN reasons;
END;
$$;
REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_write_gate(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.evaluate_ad_bid_write_gate(uuid) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Transition
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.transition_ad_bid_command(
    p_command_id           uuid,
    p_expected_fence       bigint,
    p_expected_lease_owner text,
    p_to_state             text,
    p_failure_code         text,
    p_retry_delay_seconds  integer,
    p_evidence_id          uuid)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE
    command    ops.ad_bid_command%ROWTYPE;
    edge       ops.ad_bid_command_transition%ROWTYPE;
    now_at     timestamptz := clock_timestamp();
BEGIN
    SELECT * INTO command FROM ops.ad_bid_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090';
    END IF;

    SELECT * INTO edge FROM ops.ad_bid_command_transition
     WHERE from_state = command.state AND to_state = p_to_state;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'transition % -> % is not in the reviewed set',
            command.state, p_to_state USING ERRCODE = 'MO091';
    END IF;

    IF edge.requires_lease THEN
        IF command.fence_token <> p_expected_fence
            OR command.lease_owner IS DISTINCT FROM p_expected_lease_owner
            OR command.lease_expires_at IS NULL
            OR command.lease_expires_at <= now_at THEN
            RAISE EXCEPTION 'the lease that authorised this transition is not current'
                USING ERRCODE = 'MO090';
        END IF;
    END IF;

    -- Success requires evidence, in the same transaction, at the current fence.
    IF p_to_state = 'READBACK_MATCHED' THEN
        IF NOT EXISTS (
            SELECT 1 FROM ops.ad_bid_command_readback rb
              JOIN ops.ad_bid_command_attempt at ON at.id = rb.attempt_id
             WHERE rb.id = p_evidence_id
               AND rb.command_id = p_command_id
               AND rb.match_state = 'MATCHES_TARGET'
               AND rb.observed_bid = command.target_bid_amount
               AND rb.currency_code = command.currency_code
               AND rb.bid_unit_code = command.bid_unit_code
               AND at.purpose = 'READBACK'
               AND at.fence_token = command.fence_token
               AND at.raw_observation_id IS NOT NULL) THEN
            RAISE EXCEPTION 'a matched readback at the current fence is required for success'
                USING ERRCODE = 'MO093';
        END IF;
    END IF;

    -- Compensation is only safe while the bid is still what this command wrote.
    IF p_to_state = 'RETRY_WAIT' AND NOT ops.ad_bid_retry_is_proven(p_command_id) THEN
        RAISE EXCEPTION 'retry requires status/readback-first proof and all current authorities' USING ERRCODE='MO092';
    END IF;
    IF p_to_state = 'COMPENSATION_PENDING' THEN
        IF cardinality(ops.evaluate_ad_bid_compensation_gate(p_command_id))>0 THEN
            RAISE EXCEPTION 'new exact human compensation authority required' USING ERRCODE='MO094';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM ops.ad_bid_command_readback rb
             WHERE rb.command_id = p_command_id
               AND rb.observed_at = (SELECT max(latest.observed_at)
                                       FROM ops.ad_bid_command_readback latest
                                      WHERE latest.command_id = p_command_id)
               AND rb.match_state = 'MATCHES_TARGET')
            OR NOT EXISTS (
            SELECT 1 FROM ops.ad_bid_command_attempt at
             WHERE at.command_id = p_command_id
               AND at.purpose = 'APPLY'
               AND at.outcome_class IN ('ACCEPTED', 'UNKNOWN_STATE')) THEN
            RAISE EXCEPTION 'a compensation may not overwrite a value this command did not write'
                USING ERRCODE = 'MO094';
        END IF;
    END IF;

    IF p_to_state IN ('FAILED_FINAL', 'COMPENSATION_FAILED',
                      'TERMINATED_WITHOUT_PROVIDER_CALL')
        AND p_failure_code IS NULL THEN
        RAISE EXCEPTION 'a terminal failure names its reason' USING ERRCODE = 'MO091';
    END IF;

    UPDATE ops.ad_bid_command
       SET state = p_to_state,
           lease_owner = CASE WHEN edge.releases_lease THEN NULL ELSE lease_owner END,
           lease_expires_at = CASE WHEN edge.releases_lease THEN NULL ELSE lease_expires_at END,
           retry_budget_remaining = CASE WHEN p_to_state = 'RETRY_WAIT'
               THEN greatest(retry_budget_remaining - 1, 0) ELSE retry_budget_remaining END,
           next_attempt_at = CASE WHEN p_to_state = 'RETRY_WAIT'
               THEN now_at + make_interval(secs => coalesce(p_retry_delay_seconds, 60))
               ELSE NULL END,
           requested_operation = CASE WHEN p_to_state = 'READBACK_PENDING'
               THEN NULL ELSE requested_operation END,
           failure_code = coalesce(p_failure_code, failure_code),
           terminal_at = CASE WHEN p_to_state IN ('READBACK_MATCHED', 'FAILED_FINAL',
                                                  'TERMINATED_WITHOUT_PROVIDER_CALL',
                                                  'COMPENSATED', 'COMPENSATION_FAILED')
               THEN now_at ELSE terminal_at END,
           updated_at = now_at
     WHERE id = p_command_id;

    RETURN p_to_state;
END;
$$;
REVOKE ALL ON FUNCTION ops.transition_ad_bid_command(uuid, bigint, text, text, text, integer, uuid)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.transition_ad_bid_command(uuid, bigint, text, text, text, integer, uuid)
    TO marketops_app;

-- ---------------------------------------------------------------------------
-- Leasing
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.lease_ad_bid_command(
    p_command_id uuid, p_owner text, p_seconds integer)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE
    command ops.ad_bid_command%ROWTYPE;
    reasons text[];
    fence   bigint;
BEGIN
    IF p_owner IS NULL OR length(p_owner) NOT BETWEEN 1 AND 100
        OR p_seconds NOT BETWEEN 1 AND 900 THEN
        RAISE EXCEPTION 'lease owner or duration is invalid' USING ERRCODE = 'MO095';
    END IF;
    SELECT * INTO command FROM ops.ad_bid_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.ad_bid_command_transition
                    WHERE from_state = command.state AND to_state = 'LEASED') THEN
        RAISE EXCEPTION 'this command cannot be claimed from %', command.state
            USING ERRCODE = 'MO091';
    END IF;
    -- The gate is evaluated inside the same transaction that takes the lease, so
    -- a command cannot be claimed against authority that has already lapsed.
    reasons := ops.evaluate_ad_bid_write_gate(p_command_id);
    IF cardinality(reasons) > 0 THEN
        RAISE EXCEPTION 'the advertising write gate is closed: %',
            array_to_string(reasons, ',') USING ERRCODE = 'MO092';
    END IF;
    UPDATE ops.ad_bid_command
       SET state = 'LEASED', fence_token = fence_token + 1, attempt_no = attempt_no + 1,
           lease_owner = p_owner,
           lease_expires_at = clock_timestamp() + make_interval(secs => p_seconds),
           next_attempt_at = NULL, updated_at = clock_timestamp()
     WHERE id = p_command_id
     RETURNING fence_token INTO fence;
    RETURN fence;
END;
$$;
REVOKE ALL ON FUNCTION ops.lease_ad_bid_command(uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_ad_bid_command(uuid, text, integer) TO marketops_app;

-- A readback-only lease. This is the only route out of
-- UNKNOWN_REQUIRES_READBACK, and it can only ever observe: the write itself is
-- never repeated, which is why the state has no edge back to EXECUTING.
CREATE FUNCTION ops.request_ad_bid_readback(p_command_id uuid, p_expected_fence bigint)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    UPDATE ops.ad_bid_command
       SET requested_operation = 'READBACK', updated_at = clock_timestamp()
     WHERE id = p_command_id
       AND fence_token = p_expected_fence
       AND state = 'UNKNOWN_REQUIRES_READBACK';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a readback can only be requested for an unresolved command at its fence'
            USING ERRCODE = 'MO090';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION ops.request_ad_bid_readback(uuid, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.request_ad_bid_readback(uuid, bigint) TO marketops_app;

CREATE FUNCTION ops.lease_ad_bid_readback(
    p_command_id uuid, p_owner text, p_seconds integer)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE fence bigint;
BEGIN
    IF p_owner IS NULL OR length(p_owner) NOT BETWEEN 1 AND 100
        OR p_seconds NOT BETWEEN 1 AND 900 THEN
        RAISE EXCEPTION 'lease owner or duration is invalid' USING ERRCODE = 'MO095';
    END IF;
    UPDATE ops.ad_bid_command
       SET state = 'READBACK_PENDING', fence_token = fence_token + 1,
           attempt_no = attempt_no + 1, lease_owner = p_owner,
           lease_expires_at = clock_timestamp() + make_interval(secs => p_seconds),
           requested_operation = NULL, updated_at = clock_timestamp()
     WHERE id = p_command_id
       AND state = 'UNKNOWN_REQUIRES_READBACK'
       AND requested_operation = 'READBACK'
       AND lease_owner IS NULL
     RETURNING fence_token INTO fence;
    IF fence IS NULL THEN
        RAISE EXCEPTION 'no readback is authorised for this command' USING ERRCODE = 'MO090';
    END IF;
    RETURN fence;
END;
$$;
REVOKE ALL ON FUNCTION ops.lease_ad_bid_readback(uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_ad_bid_readback(uuid, text, integer) TO marketops_app;

CREATE FUNCTION ops.lease_ad_bid_compensation(
    p_command_id uuid, p_owner text, p_seconds integer)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE reasons text[]; fence bigint;
BEGIN
    IF p_owner IS NULL OR length(p_owner) NOT BETWEEN 1 AND 100
        OR p_seconds NOT BETWEEN 1 AND 900 THEN
        RAISE EXCEPTION 'lease owner or duration is invalid' USING ERRCODE = 'MO095';
    END IF;
    reasons := ops.evaluate_ad_bid_compensation_gate(p_command_id);
    IF cardinality(reasons) > 0 THEN
        RAISE EXCEPTION 'the advertising write gate is closed: %',
            array_to_string(reasons, ',') USING ERRCODE = 'MO092';
    END IF;
    UPDATE ops.ad_bid_command
       SET fence_token = fence_token + 1, attempt_no = attempt_no + 1,
           lease_owner = p_owner,
           lease_expires_at = clock_timestamp() + make_interval(secs => p_seconds),
           updated_at = clock_timestamp()
     WHERE id = p_command_id
       AND state = 'COMPENSATION_PENDING'
       AND lease_owner IS NULL
     RETURNING fence_token INTO fence;
    IF fence IS NULL THEN
        RAISE EXCEPTION 'no compensation is authorised for this command' USING ERRCODE = 'MO090';
    END IF;
    RETURN fence;
END;
$$;
REVOKE ALL ON FUNCTION ops.lease_ad_bid_compensation(uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_ad_bid_compensation(uuid, text, integer) TO marketops_app;

-- A worker that vanished hands its work back. EXECUTING, PLATFORM_PENDING and
-- READBACK_PENDING become UNKNOWN_REQUIRES_READBACK rather than PENDING, because
-- a lease that expired mid-flight tells us nothing about whether the call landed.
CREATE FUNCTION ops.recover_expired_ad_bid_command_leases()
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE recovered integer := 0;
BEGIN
    WITH expired AS (
        SELECT id, state FROM ops.ad_bid_command
         WHERE lease_expires_at IS NOT NULL
           AND lease_expires_at <= clock_timestamp()
           AND state IN ('LEASED', 'EXECUTING', 'PLATFORM_PENDING',
                         'READBACK_PENDING', 'COMPENSATION_PENDING')
         FOR UPDATE SKIP LOCKED)
    UPDATE ops.ad_bid_command c
       SET state = CASE expired.state
               WHEN 'LEASED' THEN 'PENDING'
               WHEN 'COMPENSATION_PENDING' THEN 'MANUAL_RESOLUTION'
               ELSE 'UNKNOWN_REQUIRES_READBACK' END,
           lease_owner = NULL, lease_expires_at = NULL,
           updated_at = clock_timestamp()
      FROM expired
     WHERE c.id = expired.id;
    GET DIAGNOSTICS recovered = ROW_COUNT;
    RETURN recovered;
END;
$$;
REVOKE ALL ON FUNCTION ops.recover_expired_ad_bid_command_leases() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.recover_expired_ad_bid_command_leases() TO marketops_app;

-- The migration asserts the five recovery edges exist, so a future transition
-- edit that removed one would fail here rather than at three in the morning.
DO $verify$
DECLARE missing text;
BEGIN
    SELECT string_agg(pair.from_state || '->' || pair.to_state, ', ') INTO missing
      FROM (VALUES
        ('LEASED', 'PENDING'),
        ('EXECUTING', 'UNKNOWN_REQUIRES_READBACK'),
        ('PLATFORM_PENDING', 'UNKNOWN_REQUIRES_READBACK'),
        ('READBACK_PENDING', 'UNKNOWN_REQUIRES_READBACK'),
        ('COMPENSATION_PENDING', 'MANUAL_RESOLUTION')
      ) AS pair(from_state, to_state)
     WHERE NOT EXISTS (SELECT 1 FROM ops.ad_bid_command_transition t
                        WHERE t.from_state = pair.from_state AND t.to_state = pair.to_state);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'lease recovery needs transitions that do not exist: %', missing
            USING ERRCODE = 'MO091';
    END IF;
    -- And that the one edge which must not exist still does not.
    IF EXISTS (SELECT 1 FROM ops.ad_bid_command_transition
                WHERE from_state = 'UNKNOWN_REQUIRES_READBACK' AND to_state = 'EXECUTING') THEN
        RAISE EXCEPTION 'an unknown result must never be retried as a write'
            USING ERRCODE = 'MO091';
    END IF;
END;
$verify$;

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'ad_bid_command', 'NO_ROUTE', NULL,
        'controlled advertising write outbox; state moves only through SECURITY DEFINER functions'),
    ('ops', 'ad_bid_command_transition', 'NO_ROUTE', NULL,
        'the reviewed transition graph as data; read by the transition function'),
    ('ops', 'ad_bid_command_attempt', 'NO_ROUTE', NULL,
        'append-only record of one call made on behalf of one command'),
    ('ops', 'ad_bid_command_readback', 'NO_ROUTE', NULL,
        'append-only evidence a success or a compensation rests on'),
    ('raw', 'ad_bid_response_observation', 'NO_ROUTE', NULL,
        'exact provider response custody; not an acquisition run');

-- The application role reads and never writes. Every state move is a function.
GRANT SELECT ON ops.ad_bid_command TO marketops_app;
GRANT SELECT ON ops.ad_bid_command_transition TO marketops_app;
GRANT SELECT ON ops.ad_bid_command_attempt TO marketops_app;
GRANT SELECT ON ops.ad_bid_command_readback TO marketops_app;
GRANT SELECT ON raw.ad_bid_response_observation TO marketops_app;
