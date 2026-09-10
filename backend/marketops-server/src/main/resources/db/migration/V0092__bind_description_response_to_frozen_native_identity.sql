-- No provider schema is installed or certified here. Technical verification
-- supplies the per-operation descriptor; absent historical metadata stays absent.
ALTER TABLE platform.capability_operation ADD COLUMN description_response_binding jsonb
    CHECK (description_response_binding IS NULL OR jsonb_typeof(description_response_binding) = 'object');
ALTER TABLE ops.lc_description_command ADD COLUMN native_listing_key text;
ALTER TABLE raw.lc_description_response_observation ADD COLUMN identity_binding jsonb;

CREATE FUNCTION ops.lc_capture_command_native_identity()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE identity jsonb;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.native_listing_key IS DISTINCT FROM OLD.native_listing_key THEN
            RAISE EXCEPTION 'command native identity is immutable' USING ERRCODE = 'MO093';
        END IF;
        RETURN NEW;
    END IF;
    SELECT s.identity_lineage INTO identity FROM ops.lc_action a
      JOIN core.lc_affected_set s ON s.id=a.affected_set_id AND s.organization_id=a.organization_id
     WHERE a.id=NEW.action_id AND a.organization_id=NEW.organization_id
       AND s.affected_set_digest=NEW.affected_set_digest;
    IF identity->>'nativeListingKey' IS NULL
        OR identity->>'listingId' IS DISTINCT FROM NEW.platform_listing_id::text THEN
        RAISE EXCEPTION 'command requires the approved frozen native identity' USING ERRCODE = 'MO093';
    END IF;
    NEW.native_listing_key := identity->>'nativeListingKey';
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_capture_command_native_identity() FROM PUBLIC;
CREATE TRIGGER lc_capture_command_native_identity BEFORE INSERT OR UPDATE ON ops.lc_description_command
    FOR EACH ROW EXECUTE FUNCTION ops.lc_capture_command_native_identity();

CREATE FUNCTION ops.lc_capture_attempt_response_identity()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE command ops.lc_description_command%ROWTYPE; task jsonb;
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id=NEW.command_id;
    IF NEW.purpose = 'STATUS_ENQUIRY' THEN
        SELECT CASE WHEN a.outcome_class='ACCEPTED' AND a.native_task_key IS NOT NULL
                THEN jsonb_build_object('attemptId',a.id,'nativeTaskKey',a.native_task_key) END
          INTO task FROM ops.lc_description_command_attempt a
         WHERE a.command_id=NEW.command_id AND a.purpose IN ('APPLY','RESTORE')
           AND a.started_at <= NEW.started_at
         ORDER BY a.attempt_no DESC LIMIT 1;
    END IF;
    NEW.operation_snapshot := NEW.operation_snapshot || jsonb_build_object('responseIdentity',
        jsonb_build_object('commandId',command.id,'listingId',command.platform_listing_id,
            'nativeListingKey',command.native_listing_key,'task',task));
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_capture_attempt_response_identity() FROM PUBLIC;
CREATE TRIGGER lc_capture_attempt_response_identity BEFORE INSERT ON ops.lc_description_command_attempt
    FOR EACH ROW EXECUTE FUNCTION ops.lc_capture_attempt_response_identity();

-- RFC 6901 root and escaped path components, with no scalar-to-string coercion.
CREATE FUNCTION ops.lc_response_value(p_document jsonb,p_pointer text)
RETURNS jsonb LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, ops, pg_temp
AS $$
    SELECT CASE WHEN p_pointer='' THEN p_document
        WHEN p_pointer LIKE '/%' AND p_pointer !~ '~([^01]|$)'
            THEN ops.ad_json_value(p_document,p_pointer) ELSE NULL END
$$;
REVOKE ALL ON FUNCTION ops.lc_response_value(jsonb,text) FROM PUBLIC;

CREATE FUNCTION ops.lc_select_description_response(p_document jsonb,p_shape jsonb,p_purpose text)
RETURNS jsonb LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE
    descriptor jsonb := p_shape->'operation'->'description_response_binding';
    identity jsonb := p_shape->'responseIdentity'; payload jsonb; item jsonb; value jsonb;
    selected jsonb; matches integer := 0; mode text; task_value jsonb;
BEGIN
    IF descriptor->>'schema' IS DISTINCT FROM 'DESCRIPTION_RESPONSE_IDENTITY_V1'
        OR nullif(descriptor->>'evidenceRef','') IS NULL
        OR descriptor->>'evidenceRef' IS DISTINCT FROM p_shape->'operation'->>'evidence_ref'
        OR p_shape->'operation'->>'verification_state' IS DISTINCT FROM 'VERIFIED' THEN
        RETURN jsonb_build_object('qualified',false,'gap','RESPONSE_BINDING_UNVERIFIED');
    END IF;
    mode := descriptor->>'mode';
    -- A task receipt is acceptance only, never proof of applied content.
    IF mode='TASK_ACCEPTANCE_ONLY' THEN
        IF p_purpose NOT IN ('APPLY','RESTORE') OR p_shape->>'writeResultModel' IS DISTINCT FROM 'ASYNCHRONOUS_TASK'
            OR jsonb_typeof(p_document) IS DISTINCT FROM 'object' THEN
            RETURN jsonb_build_object('qualified',false,'gap','TASK_RECEIPT_PURPOSE_MISMATCH');
        END IF;
        RETURN jsonb_build_object('qualified',true,'extent','TASK_ACCEPTANCE_ONLY','payload',p_document);
    END IF;
    IF mode IS DISTINCT FROM 'EXACT_OBJECT' OR nullif(identity->>'nativeListingKey','') IS NULL
        OR descriptor->>'listingKeyType' NOT IN ('string','number')
        OR descriptor->>'listingKeyType' IS NULL THEN
        RETURN jsonb_build_object('qualified',false,'gap','NATIVE_IDENTITY_UNBOUND');
    END IF;
    IF p_purpose='STATUS_ENQUIRY' THEN
        IF descriptor->>'statusValueType' IS NULL OR descriptor->>'statusValueType' NOT IN ('string','number')
            OR nullif(p_shape->'operation'->>'task_success_value','') IS NULL
            OR nullif(p_shape->'operation'->>'task_failure_value','') IS NULL
            OR p_shape->'operation'->>'task_success_value'=p_shape->'operation'->>'task_failure_value'
            OR (p_shape->'operation'->'task_pending_values') ? (p_shape->'operation'->>'task_success_value')
            OR (p_shape->'operation'->'task_pending_values') ? (p_shape->'operation'->>'task_failure_value') THEN
            RETURN jsonb_build_object('qualified',false,'gap','TASK_STATUS_SCHEMA_UNQUALIFIED');
        END IF;
        task_value := ops.lc_response_value(p_document,descriptor->>'taskEchoPointer');
        IF nullif(identity->'task'->>'nativeTaskKey','') IS NULL
            OR jsonb_typeof(task_value) NOT IN ('string','number') OR task_value IS NULL
            OR task_value #>> '{}' IS DISTINCT FROM identity->'task'->>'nativeTaskKey' THEN
            RETURN jsonb_build_object('qualified',false,'gap','NATIVE_TASK_RELATION_UNPROVED');
        END IF;
    END IF;
    payload := ops.lc_response_value(p_document,descriptor->>'payloadPointer');
    IF descriptor->>'selection'='OBJECT' AND jsonb_typeof(payload)='object' THEN
        payload := jsonb_build_array(payload);
    ELSIF descriptor->>'selection' IS DISTINCT FROM 'ITEMS' OR jsonb_typeof(payload) IS DISTINCT FROM 'array' THEN
        RETURN jsonb_build_object('qualified',false,'gap','RESPONSE_COLLECTION_SHAPE_UNKNOWN');
    END IF;
    FOR item IN SELECT * FROM jsonb_array_elements(payload) LOOP
        IF jsonb_typeof(item) IS DISTINCT FROM 'object' THEN
            RETURN jsonb_build_object('qualified',false,'gap','RESPONSE_ITEM_SHAPE_UNKNOWN');
        END IF;
        value := ops.lc_response_value(item,descriptor->>'listingKeyPointer');
        IF jsonb_typeof(value) IS DISTINCT FROM descriptor->>'listingKeyType' THEN
            RETURN jsonb_build_object('qualified',false,'gap','NATIVE_IDENTITY_TYPE_UNKNOWN');
        END IF;
        IF value #>> '{}' = identity->>'nativeListingKey' THEN
            matches := matches+1; selected := item;
        END IF;
    END LOOP;
    IF matches<>1 THEN
        RETURN jsonb_build_object('qualified',false,'gap','EXACTLY_ONE_NATIVE_OBJECT_REQUIRED','matchingItems',matches);
    END IF;
    RETURN jsonb_build_object('qualified',true,'extent','EXACT_NATIVE_OBJECT','payload',selected,
        'nativeListingKey',identity->>'nativeListingKey','task',identity->'task','matchingItems',matches);
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_select_description_response(jsonb,jsonb,text) FROM PUBLIC;

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
    selection jsonb; selected_document jsonb; typed_value jsonb;
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
        IF attempt.purpose IN ('APPLY', 'RESTORE') THEN
            resolved := 'UNKNOWN_STATE';
            failure := 'write_dispatch_not_proven_absent';
        ELSE
            resolved := 'UNKNOWN_STATE';
            failure := coalesce(p_error,'response_evidence_absent');
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
            IF convert_from(p_body, 'UTF8') IS JSON WITH UNIQUE KEYS THEN
                document := convert_from(p_body, 'UTF8')::jsonb;
            ELSE document := NULL; END IF;
        EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire THEN
            document := NULL;
        END;
        selection := ops.lc_select_description_response(document,attempt.operation_snapshot,attempt.purpose);
        selected_document := selection->'payload';
        IF document IS NULL THEN
            resolved := 'UNKNOWN_STATE'; failure := 'response_semantics_unknown';
        ELSIF p_response_complete IS NOT TRUE OR p_http_status IS NULL OR p_http_status IN (408, 409, 429) OR p_http_status >= 500 THEN
            resolved := CASE WHEN attempt.purpose IN ('APPLY', 'RESTORE') THEN 'UNKNOWN_STATE' ELSE 'RETRIABLE_ERROR' END;
            failure := 'provider_response_inconclusive';
        ELSIF selection->>'qualified' IS DISTINCT FROM 'true' THEN
            resolved := 'UNKNOWN_STATE'; failure := coalesce(selection->>'gap','response_semantics_unknown');
        ELSIF attempt.purpose IN ('APPLY', 'RESTORE', 'STATUS_ENQUIRY')
            AND selection->>'extent'='EXACT_NATIVE_OBJECT'
            AND operation.ad_not_applied_pointer IS NOT NULL AND operation.ad_not_applied_value IS NOT NULL
            AND operation.ad_not_applied_value <> 'null'::jsonb
            AND ops.lc_response_value(selected_document, operation.ad_not_applied_pointer) = operation.ad_not_applied_value THEN
            resolved := 'RETRIABLE_ERROR'; failure := 'provider_explicit_not_applied';
        ELSIF p_http_status >= 300 THEN
            resolved := CASE WHEN attempt.purpose IN ('APPLY','RESTORE') THEN 'UNKNOWN_STATE' ELSE 'RETRIABLE_ERROR' END;
            failure := 'http_status_does_not_prove_business_rejection';
        ELSIF attempt.purpose IN ('APPLY', 'RESTORE') THEN
            IF operation.accepted_pointer IS NOT NULL AND operation.accepted_value IS NOT NULL
                AND operation.accepted_value <> 'null'::jsonb
                AND ops.lc_response_value(selected_document, operation.accepted_pointer) = operation.accepted_value THEN
                IF attempt.operation_snapshot ->> 'writeResultModel' = 'ASYNCHRONOUS_TASK' THEN
                    typed_value := ops.lc_response_value(selected_document, operation.task_key_pointer);
                    native_task := CASE WHEN jsonb_typeof(typed_value) IN ('string','number') THEN typed_value #>> '{}' END;
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
            typed_value := ops.lc_response_value(selected_document, operation.description_observed_text_pointer);
            IF jsonb_typeof(typed_value)='string' THEN observed_text := typed_value #>> '{}'; END IF;
            IF operation.description_kiz_marked_pointer IS NOT NULL THEN
                typed_value := ops.lc_response_value(selected_document, operation.description_kiz_marked_pointer);
                IF jsonb_typeof(typed_value)='boolean' THEN observed_kiz := (typed_value #>> '{}')::boolean; END IF;
            END IF;
            IF observed_text IS NOT NULL AND (operation.description_kiz_marked_pointer IS NULL OR observed_kiz IS NOT NULL) THEN
                observed_digest := ops.lc_description_digest_under_rule(observed_text, command.equivalence_rule);
                resolved := 'ACCEPTED';
            ELSE
                resolved := 'UNKNOWN_STATE'; failure := 'readback_value_unreadable';
            END IF;
        ELSE
            typed_value := ops.lc_response_value(selected_document, operation.task_status_pointer);
            IF jsonb_typeof(typed_value) IS DISTINCT FROM operation.description_response_binding->>'statusValueType' THEN
                resolved := 'UNKNOWN_STATE'; failure := 'task_status_type_unknown';
            ELSIF typed_value #>> '{}' = operation.task_success_value THEN
                resolved := 'ACCEPTED';
            ELSIF (typed_value #>> '{}') = operation.task_failure_value THEN
                resolved := 'REJECTED'; failure := 'platform_task_rejected';
            ELSIF (typed_value #>> '{}') = ANY(operation.task_pending_values) THEN
                resolved := 'RETRIABLE_ERROR'; failure := 'platform_task_pending';
            ELSE
                resolved := 'UNKNOWN_STATE'; failure := 'response_semantics_unknown';
            END IF;
        END IF;

        observation_id := gen_random_uuid();
        INSERT INTO raw.lc_description_response_observation (
            id, command_id, attempt_id, raw_content_id, request_digest, http_status, response_headers,
            evidence_class, response_complete, operation_id, operation_version, observed_text_digest,
            observed_kiz_marked, version_token, observed_at, correlation_id, identity_binding)
        VALUES (observation_id, attempt.command_id, attempt.id, p_content, p_request_digest, p_http_status,
            coalesce(p_headers, '{}'::jsonb), p_evidence_class, p_response_complete, operation.id,
            operation.version, observed_digest, observed_kiz,
            p_headers ->> coalesce(operation.version_token_header, 'etag'), clock_timestamp(),
            attempt.correlation_id, (selection-'payload') || jsonb_build_object(
                'responseIdentity',attempt.operation_snapshot->'responseIdentity',
                'descriptorDigest',encode(sha256(convert_to(operation.description_response_binding::text,'UTF8')),'hex')));
    END IF;

    UPDATE ops.lc_description_command_attempt
       SET completed_at = clock_timestamp(), outcome_class = resolved,
           native_status = CASE WHEN attempt.purpose='STATUS_ENQUIRY'
               AND jsonb_typeof(typed_value)=operation.description_response_binding->>'statusValueType'
               THEN typed_value #>> '{}' ELSE p_http_status::text END,
           native_task_key = native_task, error_code = failure, raw_observation_id = observation_id
     WHERE id = p_id;
    RETURN observation_id;
END;
$$;

CREATE OR REPLACE FUNCTION ops.record_lc_description_command_readback(
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
        WHEN attempt.outcome_class <> 'ACCEPTED'
            OR observation.identity_binding->>'qualified' IS DISTINCT FROM 'true'
            OR observation.identity_binding->>'extent' IS DISTINCT FROM 'EXACT_NATIVE_OBJECT'
            OR observation.observed_text_digest IS NULL THEN 'UNREADABLE'
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

