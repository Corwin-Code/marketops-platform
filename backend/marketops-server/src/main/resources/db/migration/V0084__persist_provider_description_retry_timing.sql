-- Timing is derived from retained transport evidence, not a request delay or
-- worker configuration. No transition, shorter lease or restart can shorten it.
ALTER TABLE ops.lc_description_command
    ADD COLUMN provider_not_before timestamptz,
    ADD COLUMN provider_retry_timing_unknown boolean NOT NULL DEFAULT false;
ALTER TABLE raw.lc_description_response_observation ADD COLUMN retry_timing jsonb;

CREATE FUNCTION ops.lc_description_retry_timing(p_platform text, p_headers jsonb, p_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, pg_temp
SET timezone = 'UTC'
SET datestyle = 'ISO, YMD'
AS $$
DECLARE
    header record; name text; value text; delay numeric; latest numeric := 0;
    found_timing boolean := false; deadline timestamptz;
BEGIN
    IF p_headers IS NULL OR jsonb_typeof(p_headers) <> 'object' OR p_at IS NULL THEN
        RETURN jsonb_build_object('state','UNKNOWN','reason','RETRY_TIMING_SHAPE_UNKNOWN');
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_each(p_headers) e
        WHERE lower(e.key) IN ('retry-after','item-retry-after','x-ratelimit-retry')
        GROUP BY lower(e.key) HAVING count(*) > 1) THEN
        RETURN jsonb_build_object('state','UNKNOWN','reason','DUPLICATE_RETRY_HEADER');
    END IF;
    FOR header IN SELECT * FROM jsonb_each(p_headers) LOOP
        name := lower(header.key);
        IF name NOT IN ('retry-after','item-retry-after','x-ratelimit-retry') THEN CONTINUE; END IF;
        found_timing := true;
        IF jsonb_typeof(header.value) <> 'string'
            OR (name = 'item-retry-after' AND upper(p_platform) IS DISTINCT FROM 'OZON')
            OR (name = 'x-ratelimit-retry' AND upper(p_platform) IS DISTINCT FROM 'WILDBERRIES') THEN
            RETURN jsonb_build_object('state','UNKNOWN','reason','RETRY_HEADER_UNIT_UNKNOWN');
        END IF;
        value := btrim(header.value #>> '{}');
        IF value ~ '^[0-9]{1,10}$' THEN
            delay := value::numeric * CASE WHEN name='item-retry-after' THEN 60 ELSE 1 END;
        ELSIF name='retry-after' AND value ~ '^[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT$' THEN
            BEGIN
                deadline := value::timestamptz;
                IF to_char(deadline, 'Dy, DD Mon YYYY HH24:MI:SS "GMT"') <> value THEN
                    RETURN jsonb_build_object('state','UNKNOWN','reason','RETRY_DATE_INVALID');
                END IF;
                delay := greatest(0, ceil(extract(epoch FROM deadline-p_at)));
            EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN
                RETURN jsonb_build_object('state','UNKNOWN','reason','RETRY_DATE_INVALID');
            END;
        ELSE
            RETURN jsonb_build_object('state','UNKNOWN','reason','RETRY_VALUE_UNRESOLVED');
        END IF;
        -- The command protocol uses signed integer seconds. Overflow is a
        -- hold requiring resolution, never a cap that authorizes an early call.
        IF delay > 2147483647 THEN
            RETURN jsonb_build_object('state','UNKNOWN','reason','RETRY_DELAY_UNREPRESENTABLE');
        END IF;
        latest := greatest(latest,delay);
    END LOOP;
    RETURN CASE WHEN found_timing
        THEN jsonb_build_object('state','KNOWN','seconds',latest)
        ELSE jsonb_build_object('state','ABSENT') END;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_description_retry_timing(text,jsonb,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_description_retry_timing(text,jsonb,timestamptz) TO marketops_app;

CREATE FUNCTION ops.lc_capture_description_retry_timing()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE platform_code text;
BEGIN
    SELECT c.platform_code INTO platform_code FROM ops.lc_description_command c WHERE c.id=NEW.command_id;
    NEW.retry_timing := ops.lc_description_retry_timing(platform_code,NEW.response_headers,NEW.observed_at);
    UPDATE ops.lc_description_command
       SET provider_retry_timing_unknown = provider_retry_timing_unknown OR NEW.retry_timing->>'state'='UNKNOWN',
           provider_not_before = CASE WHEN NEW.retry_timing->>'state'='KNOWN'
               THEN greatest(provider_not_before,
                   NEW.observed_at + make_interval(secs => (NEW.retry_timing->>'seconds')::integer))
               ELSE provider_not_before END
     WHERE id=NEW.command_id;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_capture_description_retry_timing() FROM PUBLIC;
CREATE TRIGGER lc_capture_description_retry_timing BEFORE INSERT ON raw.lc_description_response_observation
    FOR EACH ROW EXECUTE FUNCTION ops.lc_capture_description_retry_timing();

-- Legacy response rows did not retain native timing headers. Missing historical
-- timing cannot be certified as ABSENT. Preserve those bytes and pause only
-- unfinished commands that have such responses.
UPDATE ops.lc_description_command c SET provider_retry_timing_unknown=true
 WHERE c.terminal_at IS NULL AND EXISTS (
    SELECT 1 FROM raw.lc_description_response_observation r WHERE r.command_id=c.id);

CREATE FUNCTION ops.lc_description_timing_before_lease()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    IF NEW.fence_token > OLD.fence_token AND
       (OLD.provider_retry_timing_unknown OR OLD.provider_not_before > clock_timestamp()
        OR OLD.next_attempt_at > clock_timestamp()) THEN
        RAISE EXCEPTION 'provider observation wait has not elapsed or is unknown' USING ERRCODE='MO092';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_description_timing_before_lease() FROM PUBLIC;
CREATE TRIGGER lc_description_timing_before_lease BEFORE UPDATE ON ops.lc_description_command
    FOR EACH ROW EXECUTE FUNCTION ops.lc_description_timing_before_lease();

CREATE FUNCTION ops.lc_description_timing_before_attempt()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE command ops.lc_description_command%ROWTYPE;
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id=NEW.command_id FOR UPDATE;
    IF command.provider_retry_timing_unknown OR command.provider_not_before > clock_timestamp() THEN
        RAISE EXCEPTION 'provider wait also applies to calls at an existing lease fence' USING ERRCODE='MO092';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_description_timing_before_attempt() FROM PUBLIC;
CREATE TRIGGER lc_description_timing_before_attempt BEFORE INSERT ON ops.lc_description_command_attempt
    FOR EACH ROW EXECUTE FUNCTION ops.lc_description_timing_before_attempt();

CREATE OR REPLACE FUNCTION ops.defer_lc_description_observation(
    p_command_id uuid,p_expected_fence bigint,p_owner text,p_seconds integer)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE command ops.lc_description_command%ROWTYPE;
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id=p_command_id FOR UPDATE;
    IF command.id IS NULL OR command.fence_token<>p_expected_fence
        OR command.lease_owner IS DISTINCT FROM p_owner
        OR command.lease_expires_at IS NULL OR command.lease_expires_at<=clock_timestamp()
        OR command.state NOT IN ('PLATFORM_PENDING','COMPENSATION_PENDING','READBACK_PENDING') THEN
        RAISE EXCEPTION 'the observation lease that would be deferred is not current' USING ERRCODE='MO090';
    END IF;
    IF p_seconds IS NULL OR p_seconds<1 THEN
        RAISE EXCEPTION 'observation delay must be positive' USING ERRCODE='MO093';
    END IF;
    IF command.state='READBACK_PENDING' THEN
        PERFORM ops.transition_lc_description_command(p_command_id,p_expected_fence,p_owner,
            'UNKNOWN_REQUIRES_READBACK',NULL,NULL,NULL);
    END IF;
    UPDATE ops.lc_description_command
       SET lease_owner=NULL,lease_expires_at=NULL,
           requested_operation=CASE WHEN command.state='READBACK_PENDING' THEN 'READBACK' ELSE requested_operation END,
           next_attempt_at=greatest(provider_not_before,clock_timestamp()+make_interval(secs=>p_seconds)),
           updated_at=clock_timestamp()
     WHERE id=p_command_id;
END;
$$;

-- A transport conflict is not proof that a dispatched write was rejected.
-- Preserve the complete prior classifier, adding 409 to the inconclusive set.
CREATE OR REPLACE FUNCTION ops.complete_lc_description_command_attempt(
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
        ELSIF NOT p_response_complete OR p_http_status IN (408, 409, 429) OR p_http_status >= 500 THEN
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
