-- The attempt lifecycle: opening a call, completing it from the bytes the
-- provider actually returned, and recording what a later look observed.
--
-- The shape of this file follows the price path's, because the property it
-- protects is the same and was already argued once: the classification of a
-- provider response is made by the database from the recorded operation shape
-- and the exact response body, not by the adapter that made the call. An
-- adapter that decided its own timeout was retriable would be the single most
-- expensive bug this system could have, and the way to make that impossible is
-- to not ask it.
--
-- Three rules are worth restating because they are where the money is.
--
-- Re-dispatch is bounded and requires the frozen native-idempotency contract or
-- an exact provider NOT_APPLIED observation. Unknown absence never proves a retry safe.
-- Not once per lease, not once per fence — once. A retry of a mutating call
-- without verified provider idempotency is a second bid change wearing the first
-- one's name.
--
-- A response with no bytes is not an answer. It may only be recorded as a
-- non-accepted outcome, and on a mutating operation a transport failure is
-- upgraded to UNKNOWN_STATE rather than left as retriable, because "the socket
-- closed" and "the marketplace did not change the bid" are different claims and
-- only one of them is evidence.
--
-- The readback's match state is derived by the database from the recorded
-- response, never supplied by the caller. A caller that could name the match
-- state could name success.

-- ---------------------------------------------------------------------------
-- JSON pointer helpers
-- ---------------------------------------------------------------------------

-- Deliberately separate from the price path's identical pair rather than shared
-- through it. They are five lines each, and a function named for one write path
-- appearing in the call graph of another is the kind of coupling that survives
-- until somebody changes it for the wrong reason.
CREATE FUNCTION ops.ad_json_pointer(p_document jsonb, p_pointer text)
RETURNS text LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_document #>> array_agg(replace(replace(component, '~1', '/'), '~0', '~') ORDER BY ordinal)
      FROM unnest(string_to_array(substring(p_pointer FROM 2), '/'))
           WITH ORDINALITY part(component, ordinal)
$$;
REVOKE ALL ON FUNCTION ops.ad_json_pointer(jsonb, text) FROM PUBLIC;

CREATE FUNCTION ops.ad_json_value(p_document jsonb, p_pointer text)
RETURNS jsonb LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_document #> array_agg(replace(replace(component, '~1', '/'), '~0', '~') ORDER BY ordinal)
      FROM unnest(string_to_array(substring(p_pointer FROM 2), '/'))
           WITH ORDINALITY part(component, ordinal)
$$;
REVOKE ALL ON FUNCTION ops.ad_json_value(jsonb, text) FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- The frozen operation shape
-- ---------------------------------------------------------------------------

-- Returns NULL unless every one of the registry facts is simultaneously ACTIVE
-- and VERIFIED. That NULL is what makes an unverified advertising Provider path
-- structurally unreachable: the attempt-opening function below refuses without
-- a shape, so no attempt row can be created, so no call can be made — before any
-- socket, before any credential is resolved, before anything is rendered.
CREATE FUNCTION platform.ad_bid_operation_snapshot(p_capability uuid, p_operation text)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp
AS $$
    SELECT jsonb_build_object('operation', to_jsonb(op), 'endpoint', to_jsonb(endpoint),
        'writeResultModel', capability.write_result_model, 'capability', to_jsonb(capability),
        'profile', (SELECT to_jsonb(profile) FROM platform.platform_api_profile profile
                     WHERE profile.platform_code = op.platform_code),
        'headers', (SELECT coalesce(jsonb_agg(to_jsonb(header) ORDER BY header.id), '[]'::jsonb)
            FROM platform.platform_auth_header header
            WHERE header.platform_code = op.platform_code
              AND header.credential_purpose = 'ADS_WRITE'))
      FROM platform.capability_operation op
      JOIN platform.platform_endpoint endpoint ON endpoint.id = op.endpoint_id
      JOIN platform.platform_capability capability ON capability.id = op.capability_id
     WHERE op.capability_id = p_capability AND op.operation = p_operation
       AND op.status = 'ACTIVE' AND op.verification_state = 'VERIFIED'
       AND endpoint.status = 'ACTIVE' AND endpoint.verification_state = 'VERIFIED'
       AND endpoint.deprecated_at IS NULL
       AND capability.status = 'ACTIVE' AND capability.verification_state = 'VERIFIED'
       AND capability.deprecated_at IS NULL
       AND capability.capability_code = 'ad-bid-change' AND capability.read_write_class = 'WRITE'
       AND endpoint.capability_id = op.capability_id
       AND (op.operation <> 'READBACK' OR op.ad_observed_unit_pointer IS NOT NULL)
       AND endpoint.operation_function = CASE op.operation
               WHEN 'STATUS_ENQUIRY' THEN 'AD_BID_STATUS' ELSE 'AD_BID_' || op.operation END
       AND ((op.operation IN ('APPLY', 'RESTORE')
             AND endpoint.http_method IN ('POST', 'PUT', 'PATCH')
             AND endpoint.read_write_class = 'WRITE')
         OR (op.operation IN ('STATUS_ENQUIRY', 'READBACK')
             AND endpoint.http_method IN ('GET', 'POST')
             AND endpoint.read_write_class = 'READ'))
$$;
REVOKE ALL ON FUNCTION platform.ad_bid_operation_snapshot(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.ad_bid_operation_snapshot(uuid, text) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Opening an attempt
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.open_ad_bid_command_attempt(
    p_attempt_id     uuid,
    p_command_id     uuid,
    p_purpose        text,
    p_fence          bigint,
    p_owner          text,
    p_request_digest text,
    p_correlation_id text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, platform, pg_temp
AS $$
DECLARE
    command ops.ad_bid_command%ROWTYPE;
    shape   jsonb;
    reasons text[];
    next_no integer;
    expected_version text;
BEGIN
    SELECT * INTO command FROM ops.ad_bid_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(command.organization_id::text));
    IF command.fence_token <> p_fence
        OR command.lease_owner IS DISTINCT FROM p_owner
        OR command.lease_expires_at IS NULL
        OR command.lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'the lease that authorised this attempt is not current'
            USING ERRCODE = 'MO090';
    END IF;
    IF p_request_digest !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'a request digest is required before a call is made'
            USING ERRCODE = 'MO090';
    END IF;
    IF p_correlation_id IS NULL OR length(btrim(p_correlation_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'a correlation identifier is required' USING ERRCODE = 'MO090';
    END IF;

    -- Purpose and state must agree. A READBACK during EXECUTING would observe a
    -- value before the write it is meant to verify.
    IF NOT ((p_purpose = 'APPLY' AND command.state = 'EXECUTING')
         OR (p_purpose = 'STATUS_ENQUIRY' AND command.state IN ('EXECUTING', 'PLATFORM_PENDING', 'COMPENSATION_PENDING'))
         OR (p_purpose = 'READBACK' AND command.state IN ('READBACK_PENDING', 'COMPENSATION_PENDING'))
         OR (p_purpose = 'RESTORE' AND command.state = 'COMPENSATION_PENDING')) THEN
        RAISE EXCEPTION 'a % attempt cannot be opened from %', p_purpose, command.state
            USING ERRCODE = 'MO091';
    END IF;

    -- No second in-flight attempt at the same fence.
    IF EXISTS (SELECT 1 FROM ops.ad_bid_command_attempt a
                WHERE a.command_id = p_command_id
                  AND a.fence_token = p_fence
                  AND a.outcome_class = 'IN_FLIGHT') THEN
        RAISE EXCEPTION 'another attempt is already in flight at this fence'
            USING ERRCODE = 'MO090';
    END IF;

    -- A mutating operation happens at most once per command, ever. This is the
    -- rule that makes a retry of an unacknowledged write impossible rather than
    -- merely discouraged.
    IF p_purpose IN ('APPLY', 'RESTORE')
        AND EXISTS (SELECT 1 FROM ops.ad_bid_command_attempt a
                     WHERE a.command_id = p_command_id AND a.purpose = p_purpose)
        AND NOT (command.retry_budget_remaining > 0 AND EXISTS (
            SELECT 1 FROM ops.ad_bid_command_attempt a
            WHERE a.command_id = p_command_id AND a.purpose = p_purpose
              AND a.id = (SELECT last_attempt.id FROM ops.ad_bid_command_attempt last_attempt
                           WHERE last_attempt.command_id = p_command_id
                             AND last_attempt.purpose = p_purpose
                           ORDER BY last_attempt.attempt_no DESC LIMIT 1)
              AND ops.ad_bid_retry_is_proven(p_command_id)
              AND a.raw_observation_id IS NOT NULL
              AND (a.error_code = 'provider_explicit_not_applied'
                   OR EXISTS(SELECT 1 FROM ops.ad_bid_command_attempt proof
                      WHERE proof.command_id=p_command_id AND proof.error_code='provider_explicit_not_applied'
                        AND proof.completed_at>a.completed_at AND proof.raw_observation_id IS NOT NULL)
                   OR a.operation_snapshot #>> '{adSemanticProfile,idempotency_semantics}'
                        = 'VERIFIED_NATIVE_KEY'))) THEN
        RAISE EXCEPTION 'a mutating command operation cannot be dispatched twice'
            USING ERRCODE = 'MO090';
    END IF;

    -- The transmission boundary. The gate is evaluated once more, here, so a
    -- quarantine or kill activated after the lease was taken stops the call.
    IF p_purpose IN ('APPLY', 'RESTORE') THEN
        reasons := CASE WHEN p_purpose='RESTORE' THEN ops.evaluate_ad_bid_compensation_gate(p_command_id)
                   ELSE ops.evaluate_ad_bid_write_gate(p_command_id) END;
        IF cardinality(reasons) > 0 THEN
            RAISE EXCEPTION 'the advertising write gate is closed at transmission: %',
                array_to_string(reasons, ',') USING ERRCODE = 'MO092';
        END IF;
    END IF;

    -- A restore may only be attempted while the bid is still what this command
    -- wrote, proven by the latest readback rather than by an earlier belief.
    IF p_purpose = 'RESTORE' THEN
        IF NOT EXISTS (
            SELECT 1 FROM ops.ad_bid_command_readback rb
             WHERE rb.command_id = p_command_id
               AND rb.match_state = 'MATCHES_TARGET'
               AND rb.observed_at = (SELECT max(latest.observed_at)
                                       FROM ops.ad_bid_command_readback latest
                                      WHERE latest.command_id = p_command_id)
               AND rb.observed_at > clock_timestamp() - interval '30 seconds') THEN
            RAISE EXCEPTION 'a restore needs a current readback proving this command still owns the bid'
                USING ERRCODE = 'MO094';
        END IF;
        SELECT obs.version_token INTO expected_version
          FROM ops.ad_bid_command_readback rb
          JOIN raw.ad_bid_response_observation obs ON obs.id = rb.raw_observation_id
         WHERE rb.command_id = p_command_id
         ORDER BY rb.observed_at DESC LIMIT 1;
    END IF;

    shape := platform.ad_bid_operation_snapshot(command.capability_id, p_purpose);
    IF shape IS NULL THEN
        RAISE EXCEPTION 'no verified advertising write shape exists for this operation'
            USING ERRCODE = 'MO093';
    END IF;

    shape := shape || jsonb_build_object('adSemanticProfile',
        (SELECT to_jsonb(p) FROM platform.ad_semantic_profile p
          WHERE p.id = command.semantic_profile_id AND p.verification_state = 'VERIFIED'
            AND p.status = 'ACTIVE'));
    IF shape -> 'adSemanticProfile' = 'null'::jsonb THEN
        RAISE EXCEPTION 'verified semantic profile is required' USING ERRCODE = 'MO093';
    END IF;
    SELECT coalesce(max(a.attempt_no), 0) + 1 INTO next_no
      FROM ops.ad_bid_command_attempt a WHERE a.command_id = p_command_id;

    INSERT INTO ops.ad_bid_command_attempt (
        id, command_id, attempt_no, purpose, fence_token, lease_owner, started_at,
        outcome_class, correlation_id, request_digest, operation_snapshot,
        expected_version_token)
    VALUES (p_attempt_id, p_command_id, next_no, p_purpose, p_fence, p_owner,
        clock_timestamp(), 'IN_FLIGHT', p_correlation_id, p_request_digest, shape,
        expected_version);

    RETURN p_attempt_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.open_ad_bid_command_attempt(uuid, uuid, text, bigint, text, text, text)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.open_ad_bid_command_attempt(uuid, uuid, text, bigint, text, text, text)
    TO marketops_app;

-- An attempt is completed once and its identity never changes afterwards.
CREATE FUNCTION ops.ad_bid_attempt_completes_once()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.outcome_class <> 'IN_FLIGHT' THEN
        RAISE EXCEPTION 'a completed attempt is a permanent record' USING ERRCODE = 'MO096';
    END IF;
    IF NEW.id <> OLD.id OR NEW.command_id <> OLD.command_id
        OR NEW.attempt_no <> OLD.attempt_no OR NEW.purpose <> OLD.purpose
        OR NEW.fence_token <> OLD.fence_token OR NEW.lease_owner <> OLD.lease_owner
        OR NEW.started_at <> OLD.started_at OR NEW.correlation_id <> OLD.correlation_id
        OR NEW.request_digest <> OLD.request_digest
        OR NEW.operation_snapshot <> OLD.operation_snapshot
        OR NEW.expected_version_token IS DISTINCT FROM OLD.expected_version_token THEN
        RAISE EXCEPTION 'the identity of an attempt cannot be edited' USING ERRCODE = 'MO096';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.ad_bid_attempt_completes_once() FROM PUBLIC;
CREATE TRIGGER ad_bid_command_attempt_completes_once_bu
    BEFORE UPDATE ON ops.ad_bid_command_attempt
    FOR EACH ROW EXECUTE FUNCTION ops.ad_bid_attempt_completes_once();

-- ---------------------------------------------------------------------------
-- Completing an attempt
-- ---------------------------------------------------------------------------

-- The classification happens here, from the frozen operation shape and the exact
-- bytes, so the adapter's opinion of what happened is discarded in favour of
-- what the recorded contract says the response means.
CREATE FUNCTION ops.complete_ad_bid_command_attempt(
    p_id                uuid,
    p_fence             bigint,
    p_owner             text,
    p_outcome           text,
    p_native_status     text,
    p_task              text,
    p_error             text,
    p_content           uuid,
    p_body              bytea,
    p_http_status       integer,
    p_headers           jsonb,
    p_evidence_class    text,
    p_request_digest    text,
    p_response_complete boolean DEFAULT true)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, platform, raw, pg_temp
AS $$
DECLARE
    attempt   ops.ad_bid_command_attempt%ROWTYPE;
    operation platform.capability_operation%ROWTYPE;
    document  jsonb;
    resolved  text;
    failure   text;
    observation_id uuid;
    observed_bid numeric(18, 4);
    observed_currency text;
    observed_unit text;
    native_task text;
BEGIN
    SELECT * INTO attempt FROM ops.ad_bid_command_attempt WHERE id = p_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'attempt does not exist' USING ERRCODE = 'MO090';
    END IF;
    IF attempt.fence_token <> p_fence OR attempt.lease_owner IS DISTINCT FROM p_owner
        OR attempt.request_digest IS DISTINCT FROM p_request_digest THEN
        RAISE EXCEPTION 'this attempt does not belong to the caller' USING ERRCODE = 'MO090';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.ad_bid_command c WHERE c.id = attempt.command_id
        AND c.fence_token = p_fence AND c.lease_owner = p_owner
        AND c.lease_expires_at > clock_timestamp()) THEN
        RAISE EXCEPTION 'stale completion fence' USING ERRCODE = 'MO090';
    END IF;
    IF attempt.outcome_class <> 'IN_FLIGHT' THEN
        RAISE EXCEPTION 'this attempt is already complete' USING ERRCODE = 'MO096';
    END IF;

    IF p_content IS NULL THEN
        -- No bytes came back. That is only ever a non-accepted outcome, and on a
        -- mutating operation it is upgraded to unknown, because a closed socket
        -- is not evidence that the marketplace left the bid alone.
        IF p_outcome = 'ACCEPTED' OR p_http_status IS NOT NULL OR p_error IS NULL
            OR (p_body IS NOT NULL AND length(p_body) > 0) THEN
            RAISE EXCEPTION 'an answer with no recorded bytes cannot be an acceptance'
                USING ERRCODE = 'MO093';
        END IF;
        IF attempt.purpose IN ('APPLY', 'RESTORE')
            AND p_outcome IN ('RETRIABLE_ERROR', 'TIMEOUT') THEN
            resolved := 'UNKNOWN_STATE';
            failure := 'write_dispatch_not_proven_absent';
        ELSE
            resolved := p_outcome;
            failure := p_error;
        END IF;
    ELSE
        -- Selected from the function, not into a row variable from a single
        -- expression: PL/pgSQL assigns a select list to a row variable field by
        -- field, so the composite would land in the first field and fail.
        SELECT * INTO operation
          FROM jsonb_populate_record(NULL::platform.capability_operation,
                                     attempt.operation_snapshot -> 'operation');
        IF NOT EXISTS (SELECT 1 FROM raw.raw_content rc
                        WHERE rc.id = p_content
                          AND rc.hash_value = encode(sha256(p_body), 'hex')) THEN
            RAISE EXCEPTION 'the recorded bytes do not match their custody hash'
                USING ERRCODE = 'MO093';
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
            resolved := 'UNKNOWN_STATE';
            failure := 'response_semantics_unknown';
        ELSIF NOT p_response_complete OR p_http_status IN (408, 429) OR p_http_status >= 500 THEN
            resolved := CASE WHEN attempt.purpose IN ('APPLY', 'RESTORE')
                             THEN 'UNKNOWN_STATE' ELSE 'RETRIABLE_ERROR' END;
            failure := 'provider_response_inconclusive';
        ELSIF attempt.purpose IN ('APPLY', 'RESTORE', 'STATUS_ENQUIRY')
            AND operation.ad_not_applied_pointer IS NOT NULL
            AND ops.ad_json_value(document, operation.ad_not_applied_pointer)
                  = operation.ad_not_applied_value THEN
            resolved := 'RETRIABLE_ERROR';
            failure := 'provider_explicit_not_applied';
        ELSIF p_http_status >= 300 THEN
            resolved := 'REJECTED';
            failure := 'platform_rejected';
        ELSIF attempt.purpose IN ('APPLY', 'RESTORE') THEN
            IF ops.ad_json_value(document, operation.accepted_pointer)
                    IS NOT DISTINCT FROM operation.accepted_value THEN
                IF attempt.operation_snapshot ->> 'writeResultModel' = 'ASYNCHRONOUS_TASK' THEN
                    native_task := ops.ad_json_pointer(document, operation.task_key_pointer);
                    IF native_task IS NOT NULL AND length(native_task) BETWEEN 1 AND 256
                        AND native_task !~ '[[:cntrl:]]' THEN
                        resolved := 'ACCEPTED';
                    ELSE
                        resolved := 'UNKNOWN_STATE';
                        failure := 'asynchronous_accept_without_task_key';
                    END IF;
                ELSE
                    resolved := 'ACCEPTED';
                END IF;
            ELSE
                resolved := 'UNKNOWN_STATE';
                failure := 'response_semantics_unknown';
            END IF;
        ELSIF attempt.purpose = 'READBACK' THEN
            observed_bid := nullif(ops.ad_json_pointer(document, operation.observed_price_pointer), '')::numeric;
            observed_currency := ops.ad_json_pointer(document, operation.observed_currency_pointer);
            observed_unit := ops.ad_json_pointer(document, operation.ad_observed_unit_pointer);
            IF observed_bid IS NOT NULL AND observed_bid >= 0
                AND observed_currency ~ '^[A-Z]{3}$'
                AND observed_unit IN ('CURRENCY_MAJOR', 'CURRENCY_MINOR') THEN
                resolved := 'ACCEPTED';
            ELSE
                resolved := 'UNKNOWN_STATE';
                failure := 'readback_value_unreadable';
            END IF;
        ELSE
            IF ops.ad_json_pointer(document, operation.task_status_pointer)
                    = operation.task_success_value THEN
                resolved := 'ACCEPTED';
            ELSIF ops.ad_json_pointer(document, operation.task_status_pointer)
                    = operation.task_failure_value THEN
                resolved := 'REJECTED';
                failure := 'platform_task_rejected';
            ELSIF ops.ad_json_pointer(document, operation.task_status_pointer)
                    = ANY(operation.task_pending_values) THEN
                resolved := 'RETRIABLE_ERROR';
                failure := 'platform_task_pending';
            ELSE
                resolved := 'UNKNOWN_STATE';
                failure := 'response_semantics_unknown';
            END IF;
        END IF;

        observation_id := gen_random_uuid();
        INSERT INTO raw.ad_bid_response_observation (
            id, command_id, attempt_id, raw_content_id, request_digest, http_status,
            response_headers, evidence_class, response_complete, operation_id,
            operation_version, observed_bid, observed_currency, observed_unit,
            version_token, observed_at, correlation_id)
        VALUES (observation_id, attempt.command_id, attempt.id, p_content, p_request_digest,
            p_http_status, coalesce(p_headers, '{}'::jsonb), p_evidence_class,
            p_response_complete, operation.id, operation.version,
            observed_bid, observed_currency, observed_unit,
            p_headers ->> coalesce(operation.version_token_header, 'etag'),
            clock_timestamp(), attempt.correlation_id);
    END IF;

    UPDATE ops.ad_bid_command_attempt
       SET completed_at = clock_timestamp(), outcome_class = resolved,
           native_status = p_native_status, native_task_key = native_task,
           error_code = failure, raw_observation_id = observation_id
     WHERE id = p_id;

    RETURN observation_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.complete_ad_bid_command_attempt(
    uuid, bigint, text, text, text, text, text, uuid, bytea, integer, jsonb, text, text, boolean)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.complete_ad_bid_command_attempt(
    uuid, bigint, text, text, text, text, text, uuid, bytea, integer, jsonb, text, text, boolean)
    TO marketops_app;

-- ---------------------------------------------------------------------------
-- Recording a readback
-- ---------------------------------------------------------------------------

-- The match state is derived here and returned, never supplied. A caller that
-- could name the match state could name success, which is the whole thing this
-- path exists to prevent.
CREATE FUNCTION ops.record_ad_bid_command_readback(
    p_readback_id    uuid,
    p_command_id     uuid,
    p_attempt_id     uuid,
    p_fence          bigint,
    p_owner          text,
    p_correlation_id text)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, raw, pg_temp
AS $$
DECLARE
    command     ops.ad_bid_command%ROWTYPE;
    attempt     ops.ad_bid_command_attempt%ROWTYPE;
    observation raw.ad_bid_response_observation%ROWTYPE;
    match       text;
BEGIN
    SELECT * INTO command FROM ops.ad_bid_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090';
    END IF;
    IF command.fence_token <> p_fence OR command.lease_owner IS DISTINCT FROM p_owner
        OR command.lease_expires_at IS NULL OR command.lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'the lease that authorised this readback is not current'
            USING ERRCODE = 'MO090';
    END IF;

    SELECT * INTO attempt FROM ops.ad_bid_command_attempt WHERE id = p_attempt_id;
    IF NOT FOUND OR attempt.command_id <> p_command_id OR attempt.purpose <> 'READBACK'
        OR attempt.fence_token <> p_fence OR attempt.lease_owner IS DISTINCT FROM p_owner
        OR attempt.outcome_class = 'IN_FLIGHT' OR attempt.raw_observation_id IS NULL THEN
        RAISE EXCEPTION 'a readback needs a completed readback attempt at this fence'
            USING ERRCODE = 'MO093';
    END IF;

    SELECT * INTO observation FROM raw.ad_bid_response_observation
     WHERE id = attempt.raw_observation_id;

    -- Four outcomes, derived. There is no tolerance: an equal value is equal.
    match := CASE
        WHEN observation.observed_bid IS NULL OR observation.observed_unit IS NULL THEN 'UNREADABLE'
        WHEN observation.observed_unit IS DISTINCT FROM command.bid_unit_code THEN 'DIFFERENT'
        WHEN observation.observed_currency IS DISTINCT FROM command.currency_code THEN 'DIFFERENT'
        WHEN observation.observed_bid = command.target_bid_amount THEN 'MATCHES_TARGET'
        WHEN observation.observed_bid = command.prior_bid_amount THEN 'MATCHES_PRIOR'
        ELSE 'DIFFERENT'
    END;

    INSERT INTO ops.ad_bid_command_readback (
        id, command_id, attempt_id, observed_at, observed_bid, currency_code,
        bid_unit_code, match_state, raw_observation_id, correlation_id)
    VALUES (p_readback_id, p_command_id, p_attempt_id, clock_timestamp(),
        CASE WHEN match = 'UNREADABLE' THEN NULL ELSE observation.observed_bid END,
        CASE WHEN match = 'UNREADABLE' THEN NULL ELSE observation.observed_currency END,
        CASE WHEN match = 'UNREADABLE' THEN NULL ELSE observation.observed_unit END,
        match, attempt.raw_observation_id, p_correlation_id);

    RETURN match;
END;
$$;
REVOKE ALL ON FUNCTION ops.record_ad_bid_command_readback(uuid, uuid, uuid, bigint, text, text)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_ad_bid_command_readback(uuid, uuid, uuid, bigint, text, text)
    TO marketops_app;

-- A compensation is only ever observed, never asserted. This trigger re-proves
-- independently of the transition function that the restore actually landed:
-- the latest readback at the current fence observed the captured prior bid, and
-- an accepted RESTORE attempt at that fence preceded it with custody.
CREATE FUNCTION ops.ad_bid_compensation_is_observed()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    IF NEW.state <> 'COMPENSATED' OR OLD.state = 'COMPENSATED' THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_bid_command_readback rb
          JOIN ops.ad_bid_command_attempt at ON at.id = rb.attempt_id
         WHERE rb.command_id = NEW.id
           AND rb.match_state = 'MATCHES_PRIOR'
           AND rb.observed_bid = NEW.prior_bid_amount
           AND rb.currency_code = NEW.currency_code
           AND rb.bid_unit_code = NEW.bid_unit_code
           AND at.fence_token = NEW.fence_token
           AND at.raw_observation_id IS NOT NULL)
        OR NOT EXISTS (
        SELECT 1 FROM ops.ad_bid_command_attempt at
         WHERE at.command_id = NEW.id AND at.purpose = 'RESTORE'
           AND at.outcome_class = 'ACCEPTED'
           AND at.raw_observation_id IS NOT NULL
           AND at.completed_at < (SELECT max(rb.observed_at) FROM ops.ad_bid_command_readback rb
              JOIN ops.ad_bid_command_attempt current_read ON current_read.id=rb.attempt_id
              WHERE rb.command_id=NEW.id AND current_read.fence_token=NEW.fence_token)) THEN
        RAISE EXCEPTION 'a compensation is complete only when the prior bid was observed'
            USING ERRCODE = 'MO094';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.ad_bid_compensation_is_observed() FROM PUBLIC;
CREATE TRIGGER ad_bid_command_compensation_is_observed_bu
    BEFORE UPDATE ON ops.ad_bid_command
    FOR EACH ROW EXECUTE FUNCTION ops.ad_bid_compensation_is_observed();
