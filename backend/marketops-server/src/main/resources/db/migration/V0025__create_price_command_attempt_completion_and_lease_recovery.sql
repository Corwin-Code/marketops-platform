-- Two things the write path needs before a crash can be survived: an attempt
-- record that outlives the call it describes, and a way for a command whose
-- worker died to become an operator's problem rather than a stuck row.
--
-- An attempt is written before the platform is called and completed after, so a
-- process that dies mid-call still leaves evidence that a call was started. The
-- completion is granted at column level and permitted exactly once by the
-- trigger below, which keeps the guarantee that a finished attempt is immutable
-- while allowing the one transition that turns a started call into a recorded
-- outcome.
--
-- Lease recovery moves through the same reviewed transition set as everything
-- else. A command whose worker vanished mid-call goes to
-- UNKNOWN_REQUIRES_READBACK, because the call may have reached the marketplace
-- and may have changed a real price; a command that was only claimed goes back
-- to PENDING, because nothing was called. Neither is a new path: both edges
-- already exist in ops.price_command_transition.
--
-- Error conditions raised here:
--
--   MO037  PRICE_COMMAND_ATTEMPT_ALREADY_COMPLETED
--   MO038  PRICE_COMMAND_COMPENSATION_WITHOUT_READBACK

-- ---------------------------------------------------------------------------
-- Attempt completion
-- ---------------------------------------------------------------------------

-- A response is interpreted using the operation that was prepared before I/O.
-- Later maintenance cannot rewrite the meaning of an already dispatched call.
ALTER TABLE ops.price_command_attempt
    ADD COLUMN operation_snapshot jsonb NOT NULL,
    ADD COLUMN expected_version_token text;

-- A completed attempt is permanent. Allowing it to be rewritten would make the
-- record of what was called and what came back editable, which is the record a
-- disputed price change is settled by.
CREATE FUNCTION ops.price_command_attempt_completes_once()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.outcome_class <> 'IN_FLIGHT' THEN
        RAISE EXCEPTION 'price command attempt % is already completed', OLD.id
            USING ERRCODE = 'MO037';
    END IF;

    IF NEW.outcome_class = 'IN_FLIGHT' THEN
        RAISE EXCEPTION 'completing price command attempt % must classify the answer',
            OLD.id
            USING ERRCODE = 'MO037';
    END IF;

    -- Everything that identifies the call is fixed at the moment it was made.
    IF NEW.id <> OLD.id
        OR NEW.command_id <> OLD.command_id
        OR NEW.attempt_no <> OLD.attempt_no
        OR NEW.purpose <> OLD.purpose
        OR NEW.fence_token <> OLD.fence_token
        OR NEW.lease_owner <> OLD.lease_owner
        OR NEW.started_at <> OLD.started_at
        OR NEW.correlation_id <> OLD.correlation_id
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
        OR NEW.operation_snapshot IS DISTINCT FROM OLD.operation_snapshot
        OR NEW.expected_version_token IS DISTINCT FROM OLD.expected_version_token
    THEN
        RAISE EXCEPTION 'the identity of price command attempt % cannot change', OLD.id
            USING ERRCODE = 'MO037';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER price_command_attempt_completes_once_bu
    BEFORE UPDATE ON ops.price_command_attempt
    FOR EACH ROW EXECUTE FUNCTION ops.price_command_attempt_completes_once();

-- No application DML can invent or complete a provider call. The opening
-- transaction commits before dispatch; result custody commits independently.
CREATE FUNCTION platform.price_operation_snapshot(p_capability uuid,p_operation text)
RETURNS jsonb LANGUAGE sql STABLE SET search_path=pg_catalog,pg_temp
AS $$
    SELECT jsonb_build_object('operation',to_jsonb(op),'endpoint',to_jsonb(endpoint),
        'writeResultModel',capability.write_result_model,'capability',to_jsonb(capability),
        'profile',(SELECT to_jsonb(profile) FROM platform.platform_api_profile profile WHERE profile.platform_code=op.platform_code),
        'headers',(SELECT coalesce(jsonb_agg(to_jsonb(header) ORDER BY header.id),'[]'::jsonb)
            FROM platform.platform_auth_header header WHERE header.platform_code=op.platform_code))
      FROM platform.capability_operation op
      JOIN platform.platform_endpoint endpoint ON endpoint.id=op.endpoint_id
      JOIN platform.platform_capability capability ON capability.id=op.capability_id
     WHERE op.capability_id=p_capability AND op.operation=p_operation
       AND op.status='ACTIVE' AND op.verification_state='VERIFIED'
       AND endpoint.status='ACTIVE' AND endpoint.verification_state='VERIFIED' AND endpoint.deprecated_at IS NULL
       AND capability.status='ACTIVE' AND capability.verification_state='VERIFIED' AND capability.deprecated_at IS NULL
       AND capability.capability_code='price-change' AND capability.read_write_class='WRITE'
       AND endpoint.capability_id=op.capability_id
       AND endpoint.operation_function=CASE op.operation WHEN 'STATUS_ENQUIRY' THEN 'PRICE_STATUS' ELSE 'PRICE_'||op.operation END
       AND ((op.operation IN ('APPLY','RESTORE') AND endpoint.http_method IN ('POST','PUT','PATCH') AND endpoint.read_write_class='WRITE')
         OR (op.operation IN ('STATUS_ENQUIRY','READBACK') AND endpoint.http_method IN ('GET','POST') AND endpoint.read_write_class='READ'))
$$;
REVOKE ALL ON FUNCTION platform.price_operation_snapshot(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.price_operation_snapshot(uuid,text) TO marketops_app;

CREATE FUNCTION ops.open_price_command_attempt(p_id uuid, p_command uuid, p_purpose text,
    p_fence bigint, p_owner text, p_request_digest text, p_correlation text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE c ops.price_command%ROWTYPE; sequence integer; shape jsonb; precondition text;
BEGIN
    SELECT * INTO c FROM ops.price_command WHERE id = p_command FOR UPDATE;
    IF c.id IS NULL OR c.fence_token IS DISTINCT FROM p_fence
       OR c.lease_owner IS DISTINCT FROM p_owner OR c.lease_expires_at <= clock_timestamp()
       OR c.lease_expires_at IS NULL OR p_request_digest IS NULL
       OR p_correlation IS NULL OR length(btrim(p_correlation)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'attempt authority lost' USING ERRCODE = 'MO030';
    END IF;
    IF NOT ((p_purpose = 'APPLY' AND c.state = 'EXECUTING')
        OR (p_purpose = 'STATUS_ENQUIRY' AND c.state IN ('EXECUTING', 'PLATFORM_PENDING'))
        OR (p_purpose = 'READBACK' AND c.state IN ('READBACK_PENDING', 'COMPENSATION_PENDING'))
        OR (p_purpose = 'RESTORE' AND c.state = 'COMPENSATION_PENDING')) THEN
        RAISE EXCEPTION 'attempt purpose does not match command state' USING ERRCODE = 'MO031';
    END IF;
    IF p_purpose = 'RESTORE' AND NOT EXISTS (
        SELECT 1 FROM ops.price_command_readback r
        JOIN ops.price_command_attempt a ON a.id = r.attempt_id
        JOIN raw.price_response_observation evidence ON evidence.id = r.raw_observation_id
        JOIN platform.capability_operation operation ON operation.capability_id = c.capability_id
            AND operation.operation = 'RESTORE' AND operation.status = 'ACTIVE'
            AND operation.verification_state = 'VERIFIED'
        WHERE r.command_id = c.id AND r.match_state = 'MATCHES_TARGET'
          AND a.purpose = 'READBACK' AND a.fence_token = c.fence_token
          AND evidence.version_token IS NOT NULL AND operation.conditional_write_header IS NOT NULL
          AND r.observed_at >= c.updated_at
          AND r.observed_at > clock_timestamp() - interval '30 seconds'
          AND EXISTS (SELECT 1 FROM ops.price_command_attempt applied
               WHERE applied.command_id=c.id AND applied.purpose='APPLY'
                 AND applied.outcome_class IN ('ACCEPTED','UNKNOWN_STATE')
                 AND applied.completed_at<=r.observed_at)
          AND NOT EXISTS (SELECT 1 FROM ops.price_command_readback later
               WHERE later.command_id = c.id AND later.observed_at > r.observed_at)) THEN
        RAISE EXCEPTION 'restore requires a fresh conditional-write precondition'
            USING ERRCODE = 'MO034';
    END IF;
    IF p_purpose IN ('APPLY', 'RESTORE') AND cardinality(ops.evaluate_price_write_gate(c.id)) > 0 THEN
        RAISE EXCEPTION 'write gate closed before dispatch' USING ERRCODE = 'MO032';
    END IF;
    IF EXISTS (SELECT 1 FROM ops.price_command_attempt a WHERE a.command_id = c.id
        AND a.outcome_class = 'IN_FLIGHT' AND a.fence_token = c.fence_token) THEN
        RAISE EXCEPTION 'an unresolved attempt prohibits another call' USING ERRCODE = 'MO030';
    END IF;
    IF p_purpose IN ('APPLY','RESTORE') AND EXISTS (SELECT 1 FROM ops.price_command_attempt a
        WHERE a.command_id=c.id AND a.purpose=p_purpose) THEN
        RAISE EXCEPTION 'a mutating command operation cannot be dispatched twice' USING ERRCODE='MO030';
    END IF;
    shape := platform.price_operation_snapshot(c.capability_id,p_purpose);
    IF shape IS NULL THEN
        RAISE EXCEPTION 'attempt requires a verified operation snapshot' USING ERRCODE='MO033';
    END IF;
    IF p_purpose='RESTORE' THEN
        SELECT evidence.version_token INTO precondition
          FROM ops.price_command_readback r JOIN raw.price_response_observation evidence ON evidence.id=r.raw_observation_id
          JOIN ops.price_command_attempt a ON a.id=r.attempt_id
         WHERE r.command_id=c.id AND a.fence_token=c.fence_token AND r.match_state='MATCHES_TARGET'
         ORDER BY r.observed_at DESC LIMIT 1;
    END IF;
    SELECT coalesce(max(attempt_no), 0) + 1 INTO sequence
      FROM ops.price_command_attempt WHERE command_id = c.id;
    INSERT INTO ops.price_command_attempt (id, command_id, attempt_no, purpose, fence_token,
        lease_owner, started_at, outcome_class, correlation_id, request_digest,
        operation_snapshot, expected_version_token)
    VALUES (p_id, c.id, sequence, p_purpose, p_fence, p_owner, clock_timestamp(),
        'IN_FLIGHT', p_correlation, p_request_digest, shape, precondition);
    RETURN p_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.open_price_command_attempt(uuid,uuid,text,bigint,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.open_price_command_attempt(uuid,uuid,text,bigint,text,text,text)
    TO marketops_app;

CREATE FUNCTION ops.price_json_pointer(p_document jsonb, p_pointer text)
RETURNS text LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_document #>> array_agg(replace(replace(component, '~1', '/'), '~0', '~') ORDER BY ordinal)
      FROM unnest(string_to_array(substring(p_pointer FROM 2), '/')) WITH ORDINALITY part(component, ordinal)
$$;
REVOKE ALL ON FUNCTION ops.price_json_pointer(jsonb,text) FROM PUBLIC;

CREATE FUNCTION ops.price_json_value(p_document jsonb, p_pointer text)
RETURNS jsonb LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_document #> array_agg(replace(replace(component,'~1','/'),'~0','~') ORDER BY ordinal)
      FROM unnest(string_to_array(substring(p_pointer FROM 2),'/')) WITH ORDINALITY part(component,ordinal)
$$;
REVOKE ALL ON FUNCTION ops.price_json_value(jsonb,text) FROM PUBLIC;

CREATE FUNCTION ops.complete_price_command_attempt(p_id uuid, p_fence bigint, p_owner text,
    p_outcome text, p_native_status text, p_task text, p_error text, p_content uuid,
    p_body bytea, p_http_status integer, p_headers jsonb, p_evidence_class text,
    p_request_digest text, p_response_complete boolean DEFAULT true)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    a ops.price_command_attempt%ROWTYPE;
    operation platform.capability_operation%ROWTYPE;
    observation uuid;
    document jsonb;
    price_text text;
    currency text;
    price numeric;
    response_text text;
    response_task text;
    response_state text;
    resolved_outcome text;
    resolved_error text;
BEGIN
    SELECT * INTO a FROM ops.price_command_attempt WHERE id = p_id FOR UPDATE;
    IF a.id IS NULL OR a.fence_token IS DISTINCT FROM p_fence
       OR a.lease_owner IS DISTINCT FROM p_owner OR a.request_digest IS DISTINCT FROM p_request_digest THEN
        RAISE EXCEPTION 'attempt identity does not match response' USING ERRCODE = 'MO030';
    END IF;
    IF a.outcome_class <> 'IN_FLIGHT' THEN
        RAISE EXCEPTION 'attempt is already complete' USING ERRCODE = 'MO037';
    END IF;
    resolved_outcome := p_outcome;
    resolved_error := p_error;
    IF p_response_complete IS NULL OR (NOT p_response_complete AND p_outcome='ACCEPTED') THEN
        RAISE EXCEPTION 'incomplete response cannot be accepted' USING ERRCODE='MO033';
    END IF;
    IF p_content IS NULL THEN
        IF p_outcome = 'ACCEPTED' OR p_http_status IS NOT NULL OR p_error IS NULL
           OR coalesce(octet_length(p_body), 0) <> 0 THEN
            RAISE EXCEPTION 'a response requires immutable custody' USING ERRCODE = 'MO033';
        END IF;
        IF a.purpose IN ('APPLY','RESTORE') AND p_outcome IN ('RETRIABLE_ERROR','TIMEOUT') THEN
            resolved_outcome:='UNKNOWN_STATE'; resolved_error:='write_dispatch_not_proven_absent';
        END IF;
    ELSE
        PERFORM 1 FROM raw.raw_content content WHERE content.id = p_content
            AND content.hash_value = encode(sha256(p_body), 'hex')
            AND content.byte_length = octet_length(p_body);
        IF NOT FOUND THEN
            RAISE EXCEPTION 'response bytes do not match custody' USING ERRCODE = 'MO033';
        END IF;
        IF p_headers IS NULL OR EXISTS (SELECT 1 FROM jsonb_each_text(p_headers) header
            WHERE header.key NOT IN ('content-type', 'retry-after', 'x-request-id', 'etag', 'x-version-id')
               OR length(header.value) > 256 OR header.value ~ '[[:cntrl:]]') THEN
            RAISE EXCEPTION 'unsafe response header metadata' USING ERRCODE = 'MO033';
        END IF;
        SELECT * INTO operation FROM jsonb_populate_record(NULL::platform.capability_operation,a.operation_snapshot->'operation');
        IF operation.id IS NULL THEN
            RAISE EXCEPTION 'response operation is not verified' USING ERRCODE = 'MO033';
        END IF;
        IF p_response_complete AND p_http_status BETWEEN 200 AND 299 THEN
            BEGIN
                response_text := convert_from(p_body, 'UTF8');
                IF response_text IS JSON OBJECT WITH UNIQUE KEYS THEN
                    document := response_text::jsonb;
                END IF;
                IF a.purpose='READBACK' THEN
                price_text := ops.price_json_pointer(document, operation.observed_price_pointer);
                currency := ops.price_json_pointer(document, operation.observed_currency_pointer);
                IF price_text ~ '^[0-9]{1,14}([.][0-9]{1,4})?$' AND currency ~ '^[A-Z]{3}$'
                   AND price_text::numeric > 0 THEN price := price_text::numeric;
                ELSE price := NULL; currency := NULL;
                END IF;
                END IF;
            EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire
                OR numeric_value_out_of_range THEN price := NULL; currency := NULL;
            END;
        END IF;
        resolved_outcome := 'UNKNOWN_STATE'; resolved_error := 'response_semantics_unknown';
        IF NOT p_response_complete OR p_http_status IN (408,429) OR p_http_status >= 500 THEN
            resolved_outcome := CASE WHEN a.purpose IN ('APPLY','RESTORE') THEN 'UNKNOWN_STATE' ELSE 'RETRIABLE_ERROR' END;
            resolved_error := 'provider_response_inconclusive';
        ELSIF p_http_status NOT BETWEEN 200 AND 299 THEN
            resolved_outcome := 'REJECTED'; resolved_error := 'platform_rejected';
        ELSIF document IS NOT NULL THEN
            IF a.purpose IN ('APPLY','RESTORE') AND operation.accepted_value IS NOT NULL
                AND ops.price_json_value(document,operation.accepted_pointer)=operation.accepted_value THEN
                IF a.operation_snapshot->>'writeResultModel'='ASYNCHRONOUS_TASK' THEN
                    IF jsonb_typeof(ops.price_json_value(document,operation.task_key_pointer))='string' THEN
                        response_task := ops.price_json_pointer(document,operation.task_key_pointer);
                        IF length(response_task) NOT BETWEEN 1 AND 256 OR response_task ~ '[[:cntrl:]]'
                            OR length(btrim(response_task))=0 THEN response_task:=NULL; END IF;
                    END IF;
                    IF response_task IS NOT NULL THEN resolved_outcome:='ACCEPTED'; END IF;
                ELSIF a.operation_snapshot->>'writeResultModel'='SYNCHRONOUS' THEN resolved_outcome:='ACCEPTED';
                END IF;
            ELSIF a.purpose='READBACK' AND price IS NOT NULL AND currency IS NOT NULL THEN
                resolved_outcome:='ACCEPTED';
            ELSIF a.purpose='STATUS_ENQUIRY'
                AND jsonb_typeof(ops.price_json_value(document,operation.task_status_pointer))='string' THEN
                response_state:=ops.price_json_pointer(document,operation.task_status_pointer);
                IF response_state=operation.task_success_value THEN resolved_outcome:='ACCEPTED';
                ELSIF response_state=operation.task_failure_value THEN resolved_outcome:='REJECTED'; resolved_error:='platform_task_rejected';
                ELSIF response_state=ANY(operation.task_pending_values) THEN
                    resolved_outcome:='RETRIABLE_ERROR'; resolved_error:='platform_task_in_progress';
                END IF;
            END IF;
        END IF;
        IF resolved_outcome='ACCEPTED' THEN resolved_error:=NULL; END IF;
        PERFORM platform.defer_endpoint_quota(operation.endpoint_id,p_http_status,p_headers);
        observation := gen_random_uuid();
        INSERT INTO raw.price_response_observation (id, command_id, attempt_id, raw_content_id,
            request_digest, http_status, response_headers, evidence_class, operation_id,
            operation_version, observed_price, observed_currency, version_token, observed_at,
            correlation_id, response_complete)
        VALUES (observation, a.command_id, a.id, p_content, a.request_digest, p_http_status,
            p_headers, p_evidence_class, operation.id, operation.version, price, currency,
            CASE WHEN p_response_complete THEN p_headers ->> operation.version_token_header END,
            clock_timestamp(), a.correlation_id, p_response_complete);
    END IF;
    UPDATE ops.price_command_attempt SET completed_at = clock_timestamp(), outcome_class = resolved_outcome,
        native_status = CASE WHEN p_content IS NULL THEN p_native_status
            ELSE coalesce(response_state,'HTTP '||p_http_status) END,
        native_task_key = response_task, error_code = resolved_error,
        raw_observation_id = observation WHERE id = a.id;
    RETURN observation;
END;
$$;
REVOKE ALL ON FUNCTION ops.complete_price_command_attempt(uuid,bigint,text,text,text,text,text,
    uuid,bytea,integer,jsonb,text,text,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.complete_price_command_attempt(uuid,bigint,text,text,text,text,text,
    uuid,bytea,integer,jsonb,text,text,boolean) TO marketops_app;

CREATE FUNCTION ops.record_price_command_readback(p_id uuid, p_command uuid, p_attempt uuid,
    p_fence bigint, p_owner text, p_correlation text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE c ops.price_command%ROWTYPE; response raw.price_response_observation%ROWTYPE; match text;
BEGIN
    SELECT * INTO c FROM ops.price_command WHERE id = p_command FOR UPDATE;
    IF c.id IS NULL OR c.fence_token IS DISTINCT FROM p_fence
       OR c.lease_owner IS DISTINCT FROM p_owner OR c.lease_expires_at <= clock_timestamp()
       OR c.lease_expires_at IS NULL THEN
        RAISE EXCEPTION 'readback authority lost' USING ERRCODE = 'MO030';
    END IF;
    SELECT evidence.* INTO response FROM raw.price_response_observation evidence
      JOIN ops.price_command_attempt a ON a.id = evidence.attempt_id
     WHERE a.id = p_attempt AND a.command_id = c.id AND a.purpose = 'READBACK'
       AND a.fence_token = p_fence AND a.lease_owner = p_owner
       AND a.correlation_id = p_correlation AND a.outcome_class <> 'IN_FLIGHT'
       AND a.raw_observation_id = evidence.id;
    IF response.id IS NULL THEN
        RAISE EXCEPTION 'readback requires this attempt immutable response' USING ERRCODE = 'MO033';
    END IF;
    match := CASE WHEN response.observed_price IS NULL THEN 'UNREADABLE'
                  WHEN response.observed_currency <> c.currency_code THEN 'DIFFERENT'
                  WHEN response.observed_price = c.target_price THEN 'MATCHES_TARGET'
                  WHEN response.observed_price = c.prior_price THEN 'MATCHES_PRIOR'
                  ELSE 'DIFFERENT' END;
    INSERT INTO ops.price_command_readback (id, command_id, attempt_id, observed_at, observed_price,
        currency_code, match_state, raw_observation_id, correlation_id)
    VALUES (p_id, c.id, p_attempt, response.observed_at, response.observed_price,
        response.observed_currency, match, response.id, p_correlation);
    RETURN match;
END;
$$;
REVOKE ALL ON FUNCTION ops.record_price_command_readback(uuid,uuid,uuid,bigint,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_price_command_readback(uuid,uuid,uuid,bigint,text,text) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Lease recovery
-- ---------------------------------------------------------------------------

-- Hand back every command whose worker stopped holding it.
--
-- The function only ever acts on a lease that has already expired, and it moves
-- each command along an edge that is in the reviewed transition set. It grants
-- no authority: recovering a command makes it claimable again, and the write
-- gate is evaluated afresh when a worker next claims it.
CREATE FUNCTION ops.recover_expired_price_command_leases()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    recovered integer := 0;
    moved     integer;
BEGIN
    -- Claimed but never called: nothing reached the platform, so the command
    -- returns to the queue exactly as it was.
    WITH expired AS (
        SELECT command.id
          FROM ops.price_command AS command
         WHERE command.state = 'LEASED'
           AND command.lease_expires_at IS NOT NULL
           AND command.lease_expires_at <= clock_timestamp()
           FOR UPDATE OF command SKIP LOCKED
    )
    UPDATE ops.price_command AS command
       SET state = 'PENDING', lease_owner = NULL, lease_expires_at = NULL,
           next_attempt_at = clock_timestamp(), updated_at = clock_timestamp()
      FROM expired
     WHERE command.id = expired.id;
    GET DIAGNOSTICS moved = ROW_COUNT;
    recovered := recovered + moved;

    -- A call may have been made. Whether the marketplace applied it is exactly
    -- what nobody knows, which is the state this moves to and the reason there
    -- is no path from it back to executing.
    WITH expired AS (
        SELECT command.id
          FROM ops.price_command AS command
         WHERE command.state IN ('EXECUTING', 'PLATFORM_PENDING', 'READBACK_PENDING')
           AND command.lease_expires_at IS NOT NULL
           AND command.lease_expires_at <= clock_timestamp()
           FOR UPDATE OF command SKIP LOCKED
    )
    UPDATE ops.price_command AS command
       SET state = 'UNKNOWN_REQUIRES_READBACK', lease_owner = NULL,
           lease_expires_at = NULL, next_attempt_at = NULL,
           updated_at = clock_timestamp()
      FROM expired
     WHERE command.id = expired.id;
    GET DIAGNOSTICS moved = ROW_COUNT;
    recovered := recovered + moved;

    -- A restore that lost its worker goes to a person. Re-running it
    -- automatically would repeat a write whose effect nobody has observed.
    WITH expired AS (
        SELECT command.id
          FROM ops.price_command AS command
         WHERE command.state = 'COMPENSATION_PENDING'
           AND command.lease_expires_at IS NOT NULL
           AND command.lease_expires_at <= clock_timestamp()
           FOR UPDATE OF command SKIP LOCKED
    )
    UPDATE ops.price_command AS command
       SET state = 'MANUAL_RESOLUTION', lease_owner = NULL, lease_expires_at = NULL,
           next_attempt_at = NULL, updated_at = clock_timestamp()
      FROM expired
     WHERE command.id = expired.id;
    GET DIAGNOSTICS moved = ROW_COUNT;
    recovered := recovered + moved;

    RETURN recovered;
END;
$$;

REVOKE ALL ON FUNCTION ops.recover_expired_price_command_leases() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.recover_expired_price_command_leases() TO marketops_app;

CREATE FUNCTION ops.request_price_readback(p_command uuid, p_fence bigint)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    UPDATE ops.price_command SET requested_operation = 'READBACK', updated_at = clock_timestamp()
     WHERE id = p_command AND fence_token = p_fence AND state = 'UNKNOWN_REQUIRES_READBACK'
       AND lease_owner IS NULL;
    IF NOT FOUND THEN RAISE EXCEPTION 'command is not available for readback'
        USING ERRCODE = 'MO030'; END IF;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION ops.request_price_readback(uuid,bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.request_price_readback(uuid,bigint) TO marketops_app;

CREATE FUNCTION ops.lease_price_readback(p_command uuid, p_owner text, p_seconds integer)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE fence bigint;
BEGIN
    IF p_owner IS NULL OR length(btrim(p_owner)) = 0 OR p_seconds IS NULL
        OR p_seconds NOT BETWEEN 1 AND 900 THEN
        RAISE EXCEPTION 'invalid readback lease' USING ERRCODE = 'MO035';
    END IF;
    UPDATE ops.price_command SET state = 'READBACK_PENDING', requested_operation = NULL,
        fence_token = fence_token + 1, attempt_no = attempt_no + 1,
        lease_owner = p_owner, lease_expires_at = clock_timestamp() + make_interval(secs => p_seconds),
        updated_at = clock_timestamp()
    WHERE id = p_command AND state = 'UNKNOWN_REQUIRES_READBACK'
        AND requested_operation = 'READBACK' AND lease_owner IS NULL
    RETURNING fence_token INTO fence;
    IF fence IS NULL THEN RAISE EXCEPTION 'readback intent is not claimable' USING ERRCODE = 'MO030'; END IF;
    RETURN fence;
END;
$$;
REVOKE ALL ON FUNCTION ops.lease_price_readback(uuid,text,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_price_readback(uuid,text,integer) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Compensation leasing
-- ---------------------------------------------------------------------------

-- Claim a command that an operator authorised a restore for.
--
-- A restore is a platform write, so it needs the same fencing an apply does: a
-- lease and a fresh fence token, checked on the transition that records the
-- outcome. It also re-evaluates the write gate, because every reason a write
-- may not happen — a thrown switch, a withdrawn allowlist entry, an expired
-- authorization — applies to putting a price back just as it applies to
-- changing it.
--
-- The state does not move. COMPENSATION_PENDING is where an authorised restore
-- waits, and leasing it is a claim on the work rather than a step in the
-- lifecycle, which keeps the reviewed transition set exactly as it is.
CREATE FUNCTION ops.lease_price_compensation(
    p_command_id    uuid,
    p_lease_owner   text,
    p_lease_seconds integer)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    command_row  record;
    gate_reasons text[];
    new_fence    bigint;
BEGIN
    IF p_lease_owner IS NULL OR length(btrim(p_lease_owner)) = 0 THEN
        RAISE EXCEPTION 'a lease owner is required'
            USING ERRCODE = 'MO035';
    END IF;
    IF p_lease_seconds IS NULL OR p_lease_seconds <= 0 OR p_lease_seconds > 900 THEN
        RAISE EXCEPTION 'lease duration must be between 1 and 900 seconds'
            USING ERRCODE = 'MO035';
    END IF;

    SELECT command.id, command.state, command.lease_expires_at
      INTO command_row
      FROM ops.price_command AS command
     WHERE command.id = p_command_id
       FOR UPDATE OF command;

    IF command_row.id IS NULL THEN
        RAISE EXCEPTION 'price command % does not exist', p_command_id
            USING ERRCODE = 'MO030';
    END IF;

    IF command_row.state <> 'COMPENSATION_PENDING' THEN
        RAISE EXCEPTION 'price command % is not awaiting a restore; it is %',
            p_command_id, command_row.state
            USING ERRCODE = 'MO031';
    END IF;

    IF command_row.lease_expires_at IS NOT NULL
        AND command_row.lease_expires_at > clock_timestamp() THEN
        RAISE EXCEPTION 'price command % is already held', p_command_id
            USING ERRCODE = 'MO035';
    END IF;

    gate_reasons := ops.evaluate_price_write_gate(p_command_id);
    IF cardinality(gate_reasons) > 0 THEN
        RAISE EXCEPTION 'price write gate closed for command %: %',
            p_command_id, array_to_string(gate_reasons, ',')
            USING ERRCODE = 'MO032';
    END IF;

    UPDATE ops.price_command AS command
       SET fence_token = command.fence_token + 1,
           attempt_no = command.attempt_no + 1,
           lease_owner = p_lease_owner,
           lease_expires_at = clock_timestamp() + make_interval(secs => p_lease_seconds),
           updated_at = clock_timestamp()
     WHERE command.id = p_command_id
       AND command.state = 'COMPENSATION_PENDING'
    RETURNING command.fence_token INTO new_fence;

    IF new_fence IS NULL THEN
        RAISE EXCEPTION 'price command % changed while being leased', p_command_id
            USING ERRCODE = 'MO030';
    END IF;

    RETURN new_fence;
END;
$$;

REVOKE ALL ON FUNCTION ops.lease_price_compensation(uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_price_compensation(uuid, text, integer)
    TO marketops_app;

-- ---------------------------------------------------------------------------
-- A restore is only complete when the previous value was observed
-- ---------------------------------------------------------------------------
-- The same rule that stops an accepted write from counting as a success stops
-- an accepted restore from counting as a compensation. Platform acceptance is
-- not the price being back; only a readback that observed the prior value says
-- that, and without one the command is not compensated.
CREATE FUNCTION ops.price_command_compensation_is_observed()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    latest_match text;
BEGIN
    IF NEW.state <> 'COMPENSATED' OR OLD.state = 'COMPENSATED' THEN
        RETURN NEW;
    END IF;

    SELECT readback.match_state INTO latest_match
      FROM ops.price_command_readback AS readback
      JOIN raw.price_response_observation evidence ON evidence.id = readback.raw_observation_id
      JOIN ops.price_command_attempt attempt ON attempt.id = readback.attempt_id
     WHERE readback.command_id = NEW.id
       AND evidence.command_id = NEW.id AND evidence.attempt_id = attempt.id
       AND attempt.command_id = NEW.id AND attempt.purpose = 'READBACK'
       AND attempt.fence_token = NEW.fence_token
       AND evidence.observed_price = NEW.prior_price
       AND evidence.observed_currency = NEW.currency_code
       AND EXISTS (SELECT 1 FROM ops.price_command_attempt restore
           WHERE restore.command_id=NEW.id AND restore.fence_token=NEW.fence_token
             AND restore.purpose='RESTORE' AND restore.outcome_class='ACCEPTED'
             AND restore.raw_observation_id IS NOT NULL
             AND restore.completed_at < attempt.started_at)
     ORDER BY readback.observed_at DESC, readback.id DESC
     LIMIT 1;

    IF latest_match IS DISTINCT FROM 'MATCHES_PRIOR' OR NOT EXISTS (
        SELECT 1 FROM ops.price_command_attempt restore
         WHERE restore.command_id = NEW.id AND restore.fence_token = NEW.fence_token
           AND restore.purpose = 'RESTORE' AND restore.outcome_class = 'ACCEPTED'
           AND restore.raw_observation_id IS NOT NULL) THEN
        RAISE EXCEPTION
            'price command % cannot be compensated: the latest readback is %',
            NEW.id, coalesce(latest_match, 'absent')
            USING ERRCODE = 'MO038',
                  HINT = 'observe the restored value before claiming compensation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER price_command_compensation_is_observed_bu
    BEFORE UPDATE ON ops.price_command
    FOR EACH ROW EXECUTE FUNCTION ops.price_command_compensation_is_observed();

-- ---------------------------------------------------------------------------
-- Recovery uses only reviewed edges
-- ---------------------------------------------------------------------------
-- The three moves above are asserted against the transition table itself, so a
-- future change that removed one of those edges would fail this migration's own
-- expectation rather than leave recovery writing an unreviewed transition.
DO $verify$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(expected.from_state || '->' || expected.to_state, ', ')
      INTO missing
      FROM (VALUES
        ('LEASED', 'PENDING'),
        ('EXECUTING', 'UNKNOWN_REQUIRES_READBACK'),
        ('PLATFORM_PENDING', 'UNKNOWN_REQUIRES_READBACK'),
        ('READBACK_PENDING', 'UNKNOWN_REQUIRES_READBACK'),
        ('COMPENSATION_PENDING', 'MANUAL_RESOLUTION')
      ) AS expected(from_state, to_state)
     WHERE NOT EXISTS (
        SELECT 1 FROM ops.price_command_transition AS allowed
         WHERE allowed.from_state = expected.from_state
           AND allowed.to_state = expected.to_state);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'lease recovery would use unreviewed transitions: %', missing
            USING ERRCODE = 'MO031';
    END IF;
END;
$verify$;
