-- Non-echo task enquiries need the exact query retained before dispatch.
-- Historical attempts remain without retrospective query evidence.
ALTER TABLE ops.lc_description_command_attempt ADD COLUMN query_body bytea;
ALTER TABLE ops.lc_description_command_attempt ADD COLUMN query_identity jsonb;
ALTER TABLE ops.lc_description_command_attempt ADD COLUMN query_recorded_at timestamptz;
ALTER TABLE ops.lc_description_command_attempt ADD CONSTRAINT lc_query_evidence_complete_ck CHECK (
    (query_body IS NULL AND query_identity IS NULL AND query_recorded_at IS NULL) OR
    (purpose='STATUS_ENQUIRY' AND query_body IS NOT NULL AND octet_length(query_body) BETWEEN 1 AND 65536
      AND query_identity IS NOT NULL AND query_recorded_at IS NOT NULL));

CREATE FUNCTION ops.lc_description_query_is_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF OLD.query_identity IS NOT NULL AND (NEW.query_identity IS DISTINCT FROM OLD.query_identity
        OR NEW.query_body IS DISTINCT FROM OLD.query_body OR NEW.query_recorded_at IS DISTINCT FROM OLD.query_recorded_at) THEN
        RAISE EXCEPTION 'retained task query is immutable' USING ERRCODE='MO093';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_description_query_is_immutable() FROM PUBLIC;
CREATE TRIGGER lc_description_query_is_immutable BEFORE UPDATE ON ops.lc_description_command_attempt
    FOR EACH ROW EXECUTE FUNCTION ops.lc_description_query_is_immutable();

CREATE OR REPLACE FUNCTION platform.lc_description_response_descriptor_valid(p_descriptor jsonb,p_operation text,p_model text)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE field text; pointer text;
BEGIN
    IF p_descriptor IS NULL OR jsonb_typeof(p_descriptor)<>'object'
        OR p_descriptor->>'schema' IS DISTINCT FROM 'DESCRIPTION_RESPONSE_IDENTITY_V1'
        OR jsonb_typeof(p_descriptor->'evidenceRef') IS DISTINCT FROM 'string'
        OR nullif(btrim(p_descriptor->>'evidenceRef'),'') IS NULL
        OR p_descriptor-ARRAY['schema','evidenceRef','mode','selection','payloadPointer',
            'listingKeyPointer','listingKeyType','taskEchoPointer','statusValueType',
            'taskBindingMethod','taskRequestPointer','taskRequestValueType']<>'{}'::jsonb THEN
        RETURN false;
    END IF;
    IF p_descriptor->>'mode'='TASK_ACCEPTANCE_ONLY' THEN
        RETURN p_operation IN ('APPLY','RESTORE') AND p_model='ASYNCHRONOUS_TASK'
            AND p_descriptor-ARRAY['schema','evidenceRef','mode']='{}'::jsonb;
    END IF;
    IF p_descriptor->>'mode' IS DISTINCT FROM 'EXACT_OBJECT'
        OR coalesce(p_descriptor->>'selection','') NOT IN ('OBJECT','ITEMS')
        OR coalesce(p_descriptor->>'listingKeyType','') NOT IN ('string','number') THEN RETURN false; END IF;
    FOREACH field IN ARRAY ARRAY['payloadPointer','listingKeyPointer'] LOOP
        pointer := p_descriptor->>field;
        IF jsonb_typeof(p_descriptor->field) IS DISTINCT FROM 'string'
            OR (pointer<>'' AND (left(pointer,1)<>'/' OR pointer ~ '~([^01]|$)'))
            OR (field='listingKeyPointer' AND pointer='') THEN RETURN false; END IF;
    END LOOP;
    IF p_operation='STATUS_ENQUIRY' THEN
        IF coalesce(p_descriptor->>'taskBindingMethod','ECHO') NOT IN ('ECHO','REQUEST_UNIQUE') THEN RETURN false; END IF;
        IF p_descriptor->>'taskBindingMethod'='REQUEST_UNIQUE' THEN
            IF p_descriptor ? 'taskEchoPointer' OR coalesce(p_descriptor->>'taskRequestValueType','') NOT IN ('string','number') THEN RETURN false; END IF;
            pointer:=p_descriptor->>'taskRequestPointer';
        ELSE
            IF p_descriptor ?| ARRAY['taskRequestPointer','taskRequestValueType'] THEN RETURN false; END IF;
            pointer:=p_descriptor->>'taskEchoPointer';
        END IF;
        IF pointer IS NULL
            OR left(pointer,1)<>'/' OR pointer ~ '~([^01]|$)'
            OR coalesce(p_descriptor->>'statusValueType','') NOT IN ('string','number') THEN RETURN false; END IF;
    ELSIF p_descriptor ?| ARRAY['taskBindingMethod','taskRequestPointer','taskRequestValueType'] THEN RETURN false;
    END IF;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION platform.lc_description_response_descriptor_valid(jsonb,text,text) FROM PUBLIC;

-- Render only the supported query vocabulary from the immutable attempt identity.
-- Split the original template once so a provider key cannot introduce another placeholder.
CREATE FUNCTION ops.lc_description_render_task_query(p_template text,p_values jsonb) RETURNS bytea
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE parts text[]; token text[]; position integer:=1; rendered text; value text; quoted text;
BEGIN
    IF p_template IS NULL THEN RETURN NULL; END IF;
    parts:=regexp_split_to_array(p_template,'\{[a-zA-Z][a-zA-Z0-9]{0,31}\}');
    rendered:=parts[1];
    FOR token IN SELECT regexp_matches(p_template,'\{([a-zA-Z][a-zA-Z0-9]{0,31})\}','g') LOOP
        IF token[1] NOT IN ('nativeTaskKey','nativeListingKey','idempotencyKey') THEN RETURN NULL; END IF;
        value:=p_values->>token[1];
        IF value IS NULL THEN RETURN NULL; END IF;
        quoted:=to_json(value)::text;
        position:=position+1;
        rendered:=rendered||substring(quoted,2,length(quoted)-2)||parts[position];
    END LOOP;
    RETURN convert_to(rendered,'UTF8');
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_description_render_task_query(text,jsonb) FROM PUBLIC;

CREATE FUNCTION ops.record_lc_description_task_query(p_attempt uuid,p_digest text,p_body bytea)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,platform,pg_temp AS $$
DECLARE a ops.lc_description_command_attempt%ROWTYPE; c ops.lc_description_command%ROWTYPE;
    d jsonb; document jsonb; task jsonb; value jsonb; expected bytea; identity jsonb;
BEGIN
    SELECT * INTO a FROM ops.lc_description_command_attempt WHERE id=p_attempt FOR UPDATE;
    IF NOT FOUND OR a.purpose<>'STATUS_ENQUIRY' OR a.outcome_class<>'IN_FLIGHT'
        OR a.request_digest IS DISTINCT FROM p_digest THEN RETURN false; END IF;
    SELECT * INTO c FROM ops.lc_description_command WHERE id=a.command_id FOR SHARE;
    IF c.fence_token<>a.fence_token OR c.lease_owner IS DISTINCT FROM a.lease_owner
        OR c.lease_expires_at IS NULL OR c.lease_expires_at<=clock_timestamp() OR c.provider_retry_timing_unknown
        OR c.provider_not_before>clock_timestamp()
        OR (a.operation_snapshot-'responseIdentity') IS DISTINCT FROM platform.lc_description_operation_snapshot(c.capability_id,a.purpose)
        THEN RETURN false; END IF;
    d:=a.operation_snapshot #> '{operation,description_response_binding}';
    task:=a.operation_snapshot #> '{responseIdentity,task}';
    IF d->>'taskBindingMethod' IS DISTINCT FROM 'REQUEST_UNIQUE'
        OR NOT platform.lc_description_response_descriptor_valid(d,a.purpose,a.operation_snapshot->>'writeResultModel')
        OR task->>'attemptId' IS DISTINCT FROM (SELECT prior.id::text FROM ops.lc_description_command_attempt prior
            WHERE prior.command_id=c.id AND prior.purpose IN ('APPLY','RESTORE') ORDER BY prior.attempt_no DESC LIMIT 1)
        OR nullif(task->>'nativeTaskKey','') IS NULL
        OR a.operation_snapshot #>> '{endpoint,http_method}' IS DISTINCT FROM 'POST'
        OR p_body IS NULL OR octet_length(p_body) NOT BETWEEN 1 AND 65536 THEN RETURN false; END IF;
    expected:=ops.lc_description_render_task_query(a.operation_snapshot #>> '{operation,request_template}',
        jsonb_build_object('nativeTaskKey',task->>'nativeTaskKey','nativeListingKey',c.native_listing_key,'idempotencyKey',c.idempotency_key));
    IF p_body IS DISTINCT FROM expected THEN RETURN false; END IF;
    BEGIN
        IF NOT (convert_from(p_body,'UTF8') IS JSON WITH UNIQUE KEYS) THEN RETURN false; END IF;
        document:=convert_from(p_body,'UTF8')::jsonb;
    EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire THEN RETURN false;
    END;
    value:=ops.lc_response_value(document,d->>'taskRequestPointer');
    IF jsonb_typeof(value) IS DISTINCT FROM d->>'taskRequestValueType'
        OR value #>> '{}' IS DISTINCT FROM task->>'nativeTaskKey' THEN RETURN false; END IF;
    identity:=jsonb_build_object('method','REQUEST_UNIQUE','attemptId',a.id,'task',task,
        'requestDigest',a.request_digest,'bodySha256',encode(sha256(p_body),'hex'),
        'operationSnapshotSha256',encode(sha256(convert_to(a.operation_snapshot::text,'UTF8')),'hex'));
    IF a.query_identity IS NOT NULL THEN RETURN a.query_identity=identity AND a.query_body=p_body; END IF;
    UPDATE ops.lc_description_command_attempt SET query_body=p_body,query_identity=identity,query_recorded_at=clock_timestamp()
        WHERE id=a.id;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION ops.record_lc_description_task_query(uuid,text,bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_lc_description_task_query(uuid,text,bytea) TO marketops_app;

CREATE OR REPLACE FUNCTION ops.lc_select_description_response(p_document jsonb,p_shape jsonb,p_purpose text)
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
        IF descriptor->>'taskBindingMethod'='REQUEST_UNIQUE' THEN
            IF p_shape->'queryIdentity'->>'method' IS DISTINCT FROM 'REQUEST_UNIQUE'
                OR p_shape->'queryIdentity'->'task' IS DISTINCT FROM identity->'task'
                OR nullif(identity->'task'->>'nativeTaskKey','') IS NULL
                OR p_shape->'queryIdentity'->>'operationSnapshotSha256' IS DISTINCT FROM
                    encode(sha256(convert_to((p_shape-'queryIdentity')::text,'UTF8')),'hex') THEN
                RETURN jsonb_build_object('qualified',false,'gap','EXACT_TASK_QUERY_EVIDENCE_MISSING');
            END IF;
        ELSE
        task_value := ops.lc_response_value(p_document,descriptor->>'taskEchoPointer');
        IF nullif(identity->'task'->>'nativeTaskKey','') IS NULL
            OR jsonb_typeof(task_value) NOT IN ('string','number') OR task_value IS NULL
            OR task_value #>> '{}' IS DISTINCT FROM identity->'task'->>'nativeTaskKey' THEN
            RETURN jsonb_build_object('qualified',false,'gap','NATIVE_TASK_RELATION_UNPROVED');
        END IF;
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
        'nativeListingKey',identity->>'nativeListingKey','task',identity->'task','matchingItems',matches,
        'taskQueryBinding',p_shape->'queryIdentity');
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
        selection := ops.lc_select_description_response(document,attempt.operation_snapshot ||
            CASE WHEN attempt.query_identity IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('queryIdentity',attempt.query_identity) END,attempt.purpose);
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


CREATE FUNCTION platform.lc_description_task_query_shape_valid(p_operation jsonb,p_endpoint jsonb)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE d jsonb:=p_operation->'description_response_binding'; probe text; rendered bytea; document jsonb; value jsonb;
BEGIN
    IF jsonb_typeof(p_operation) IS DISTINCT FROM 'object' OR jsonb_typeof(p_endpoint) IS DISTINCT FROM 'object' THEN RETURN false; END IF;
    IF d->>'taskBindingMethod' IS DISTINCT FROM 'REQUEST_UNIQUE' THEN RETURN true; END IF;
    IF p_operation->>'operation' IS DISTINCT FROM 'STATUS_ENQUIRY'
        OR p_endpoint->>'http_method' IS DISTINCT FROM 'POST' THEN RETURN false; END IF;
    FOREACH probe IN ARRAY ARRAY['1','2'] LOOP
        rendered:=ops.lc_description_render_task_query(p_operation->>'request_template',
            jsonb_build_object('nativeTaskKey',probe,'nativeListingKey','fixture-listing','idempotencyKey','fixture-query'));
        IF rendered IS NULL THEN RETURN false; END IF;
        BEGIN
            IF NOT (convert_from(rendered,'UTF8') IS JSON WITH UNIQUE KEYS) THEN RETURN false; END IF;
            document:=convert_from(rendered,'UTF8')::jsonb;
        EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire THEN RETURN false;
        END;
        value:=ops.lc_response_value(document,d->>'taskRequestPointer');
        IF jsonb_typeof(value) IS DISTINCT FROM d->>'taskRequestValueType'
            OR value #>> '{}' IS DISTINCT FROM probe THEN RETURN false; END IF;
    END LOOP;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION platform.lc_description_task_query_shape_valid(jsonb,jsonb) FROM PUBLIC;

CREATE OR REPLACE FUNCTION platform.review_registry_verification(p_case uuid,p_actor uuid,p_expected_version bigint,
    p_approve boolean,p_correlation text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp
AS $$
DECLARE evidence platform.registry_verification_case%ROWTYPE; capability platform.platform_capability%ROWTYPE;
    expected_purpose text;
BEGIN
    SELECT * INTO evidence FROM platform.registry_verification_case WHERE id=p_case FOR UPDATE;
    IF evidence.id IS NULL OR evidence.state<>'SUBMITTED' OR evidence.version IS DISTINCT FROM p_expected_version
        OR evidence.submitted_by_user_id=p_actor OR p_approve IS NULL
        OR NOT platform.registry_operator_allowed(p_actor,evidence.marketplace_account_id)
        OR NOT platform.registry_operator_allowed(evidence.submitted_by_user_id,evidence.marketplace_account_id) THEN
        RAISE EXCEPTION 'independent verification reviewer authority denied' USING ERRCODE='MO039';
    END IF;
    SELECT * INTO capability FROM platform.platform_capability WHERE id=evidence.capability_id FOR UPDATE;
    expected_purpose:=CASE WHEN capability.capability_code='listing-description-change' AND capability.read_write_class='WRITE' THEN 'CONTENT_WRITE'
        WHEN capability.read_write_class='WRITE' THEN 'PRICE_WRITE' ELSE 'READ' END;
    PERFORM 1 FROM platform.platform_api_profile WHERE platform_code=capability.platform_code FOR UPDATE;
    PERFORM 1 FROM platform.platform_auth_header WHERE platform_code=capability.platform_code ORDER BY id FOR UPDATE;
    PERFORM 1 FROM platform.platform_endpoint WHERE capability_id=capability.id ORDER BY id FOR UPDATE;
    PERFORM 1 FROM platform.capability_operation WHERE capability_id=capability.id ORDER BY id FOR UPDATE;
    IF p_approve THEN
        IF evidence.evidence_class<>'REAL_ACCOUNT' OR evidence.valid_until<=clock_timestamp()
            OR evidence.configuration_snapshot IS DISTINCT FROM platform.registry_configuration_snapshot(capability.id)
            OR evidence.configuration_snapshot->'profile'='null'::jsonb
            OR NOT EXISTS (SELECT 1 FROM platform.platform_auth_header h WHERE h.id=ANY(evidence.auth_header_ids)
                AND h.value_source='RESOLVED_SECRET' AND h.credential_purpose=expected_purpose)
            OR EXISTS (SELECT 1 FROM platform.platform_auth_header h WHERE h.id=ANY(evidence.auth_header_ids)
                AND h.credential_purpose<>expected_purpose)
            OR EXISTS (SELECT 1 FROM platform.platform_endpoint e WHERE e.id=ANY(evidence.endpoint_ids)
                AND (e.http_method IS NULL OR e.path_template IS NULL OR e.response_content_type IS NULL
                     OR e.rate_limit_per_minute IS NULL OR e.rate_limit_per_minute NOT BETWEEN 1 AND 60000
                     OR e.pagination_model='UNKNOWN' OR (e.pagination_model<>'NONE' AND e.continuation_pointer IS NULL)
                     OR (capability.read_write_class='READ' AND
                         (e.read_write_class<>'READ' OR e.operation_function<>'READ_DATA' OR e.http_method NOT IN ('GET','POST')
                          OR NOT platform.request_template_is_well_formed(e.path_template,false,false)
                          OR NOT platform.request_template_is_well_formed(e.query_template,false,false)
                          OR NOT platform.request_template_is_well_formed(e.body_template,true,false))))) THEN
            RAISE EXCEPTION 'real-account evidence or complete protocol semantics missing' USING ERRCODE='MO039';
        END IF;
        IF capability.read_write_class='WRITE' THEN
            IF capability.capability_code NOT IN ('price-change','listing-description-change') OR capability.write_result_model='UNKNOWN'
                OR NOT EXISTS (SELECT 1 FROM platform.capability_operation WHERE capability_id=capability.id AND operation='APPLY')
                OR NOT EXISTS (SELECT 1 FROM platform.capability_operation WHERE capability_id=capability.id AND operation='READBACK')
                OR (capability.write_result_model='ASYNCHRONOUS_TASK' AND NOT EXISTS
                    (SELECT 1 FROM platform.capability_operation WHERE capability_id=capability.id AND operation='STATUS_ENQUIRY'))
                OR EXISTS (SELECT 1 FROM platform.capability_operation WHERE capability_id=capability.id
                    AND NOT endpoint_id=ANY(evidence.endpoint_ids))
                OR EXISTS (SELECT 1 FROM platform.capability_operation restore WHERE restore.capability_id=capability.id AND restore.operation='RESTORE'
                    AND (restore.conditional_write_header IS NULL OR NOT EXISTS (SELECT 1 FROM platform.capability_operation readback
                        WHERE readback.capability_id=capability.id AND readback.operation='READBACK' AND readback.version_token_header IS NOT NULL))) THEN
                RAISE EXCEPTION 'write protocol is incomplete' USING ERRCODE='MO039';
            END IF;
        END IF;
        IF capability.capability_code='listing-description-change' AND EXISTS (
            SELECT 1 FROM platform.capability_operation operation WHERE operation.capability_id=capability.id
                AND (NOT platform.lc_description_response_descriptor_valid(operation.description_response_binding,
                        operation.operation,capability.write_result_model)
                    OR operation.description_response_binding->>'evidenceRef' IS DISTINCT FROM
                        CASE WHEN operation.verification_state='VERIFIED' AND operation.status='ACTIVE'
                            THEN operation.evidence_ref ELSE evidence.account_evidence_ref END
                    OR (operation.operation='READBACK' AND operation.description_observed_text_pointer IS NULL)
                    OR (operation.operation IN ('APPLY','RESTORE') AND operation.description_attribute_key IS NULL))) THEN
            RAISE EXCEPTION 'Description response or request semantics incomplete' USING ERRCODE='MO039';
        END IF;
        IF capability.capability_code='listing-description-change' AND EXISTS (
            SELECT 1 FROM platform.capability_operation operation WHERE operation.capability_id=capability.id
                AND operation.operation IN ('APPLY','RESTORE')
                AND (NOT platform.lc_description_request_guard_valid(operation.description_request_guard,operation.description_attribute_key)
                    OR operation.description_request_guard->>'evidenceRef' IS DISTINCT FROM
                        CASE WHEN operation.verification_state='VERIFIED' AND operation.status='ACTIVE'
                            THEN operation.evidence_ref ELSE evidence.account_evidence_ref END)) THEN
            RAISE EXCEPTION 'Description exact request schema is incomplete or unsupported' USING ERRCODE='MO039';
        END IF;
        IF capability.capability_code='listing-description-change' AND EXISTS (
            SELECT 1 FROM platform.capability_operation operation
              JOIN platform.platform_endpoint endpoint ON endpoint.id=operation.endpoint_id
             WHERE operation.capability_id=capability.id
               AND NOT platform.lc_description_task_query_shape_valid(to_jsonb(operation.*),to_jsonb(endpoint.*))) THEN
            RAISE EXCEPTION 'Description exact task query is unsupported or does not bind its declared task field' USING ERRCODE='MO039';
        END IF;
        UPDATE platform.platform_capability SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            contract_test_status='PASSING',status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE id=capability.id
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE' OR contract_test_status<>'PASSING');
        UPDATE platform.platform_api_profile SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE platform_code=capability.platform_code
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE');
        UPDATE platform.platform_auth_header SET status='RETIRED',updated_at=clock_timestamp(),version=version+1
            WHERE platform_code=capability.platform_code AND credential_purpose=expected_purpose
              AND status='ACTIVE' AND NOT id=ANY(evidence.auth_header_ids);
        UPDATE platform.platform_auth_header SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE id=ANY(evidence.auth_header_ids)
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE');
        UPDATE platform.platform_endpoint SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            contract_test_status='PASSING',status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE id=ANY(evidence.endpoint_ids)
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE' OR contract_test_status<>'PASSING');
        UPDATE platform.capability_operation SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE capability_id=capability.id
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE');
    END IF;
    UPDATE platform.registry_verification_case SET state=CASE WHEN p_approve THEN 'APPROVED' ELSE 'REJECTED' END,
        reviewed_by_user_id=p_actor,reviewed_at=clock_timestamp(),version=version+1,
        configuration_snapshot=CASE WHEN p_approve THEN platform.registry_configuration_snapshot(capability.id) ELSE configuration_snapshot END
        WHERE id=p_case;
    PERFORM platform.audit_registry_verification(p_actor,p_case,'SUBMITTED',CASE WHEN p_approve THEN 'APPROVED' ELSE 'REJECTED' END,
        evidence.account_evidence_ref,p_correlation);
END;
$$;


-- The shared price/ad template validator keeps its original vocabulary and root
-- restriction. Description supports a verified single-item array and typed IDs.
