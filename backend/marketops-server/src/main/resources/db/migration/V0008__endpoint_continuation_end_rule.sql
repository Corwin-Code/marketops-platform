-- V0008: let a registered read endpoint record how its source ends a listing.
--
-- A cursor endpoint could only end on a JSON null token. Ozon's Seller API pages with
-- `last_id` / `cursor` and does not end that way: the official documentation says to pass the
-- previous answer's token to get the next values, and the listing ends with an empty page of
-- records or an empty token. Under the old contract such an endpoint either rested BLOCKED as
-- schema drift or kept calling until the per-run ceiling.
--
-- Two recorded facts per endpoint, reviewed like every other endpoint column:
--   continuation_end_rule  JSON_NULL (the default and the previous behaviour), EMPTY_TOKEN
--                          (an empty string at the continuation pointer), EMPTY_RECORDS (an
--                          empty array at records_pointer) or EMPTY_TOKEN_OR_RECORDS (either);
--   records_pointer        where one page's records live, required by the two *_RECORDS rules.
-- A missing token, a token of another type, and a records pointer that does not address an
-- array are still schema drift. Both columns are part of the endpoint row, so they are in the
-- reviewed registry snapshot, a change makes approved evidence stale, and the verified-writer
-- guard keeps the application role from changing them on a VERIFIED row.
--
-- platform.configure_registry_draft is recreated unchanged except that an ENDPOINT draft may set
-- the two columns (an omitted rule drafts JSON_NULL).

ALTER TABLE platform.platform_endpoint
    ADD COLUMN continuation_end_rule text DEFAULT 'JSON_NULL' NOT NULL,
    ADD COLUMN records_pointer text;

ALTER TABLE platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_continuation_end_rule_ck CHECK ((continuation_end_rule = ANY (ARRAY['JSON_NULL'::text, 'EMPTY_TOKEN'::text, 'EMPTY_RECORDS'::text, 'EMPTY_TOKEN_OR_RECORDS'::text]))),
    ADD CONSTRAINT platform_endpoint_records_pointer_ck CHECK (((records_pointer IS NULL) OR (records_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$'::text))),
    ADD CONSTRAINT platform_endpoint_records_rule_ck CHECK (((continuation_end_rule <> ALL (ARRAY['EMPTY_RECORDS'::text, 'EMPTY_TOKEN_OR_RECORDS'::text])) OR (records_pointer IS NOT NULL)));

CREATE OR REPLACE FUNCTION platform.configure_registry_draft(p_account uuid, p_capability uuid, p_actor uuid, p_kind text, p_id uuid, p_expected_version bigint, p_definition jsonb, p_correlation text) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE capability platform.platform_capability%ROWTYPE; entity uuid:=coalesce(p_id,gen_random_uuid());
    keys text[]; changed integer;
    profile platform.platform_api_profile%ROWTYPE; header platform.platform_auth_header%ROWTYPE;
    endpoint platform.platform_endpoint%ROWTYPE; operation platform.capability_operation%ROWTYPE;
BEGIN
    SELECT * INTO capability FROM platform.platform_capability WHERE id=p_capability FOR SHARE;
    IF NOT platform.registry_operator_allowed(p_actor,p_account) OR NOT EXISTS
        (SELECT 1 FROM core.marketplace_account WHERE id=p_account AND platform_code=capability.platform_code) THEN
        RAISE EXCEPTION 'configuration account authority denied' USING ERRCODE='MO039';
    END IF;
    keys:=CASE p_kind
        WHEN 'PROFILE' THEN ARRAY['base_url','request_timeout_ms','max_response_bytes','owner_label']
        WHEN 'HEADER' THEN ARRAY['header_name','value_source','value_template','credential_purpose','ordinal','owner_label']
        WHEN 'ENDPOINT' THEN ARRAY['http_method','path_template','operation_function','query_template','body_template',
            'response_content_type','continuation_pointer','pagination_model','rate_limit_per_minute',
            'continuation_end_rule','records_pointer']
        WHEN 'CAPABILITY' THEN ARRAY['write_result_model']
        WHEN 'OPERATION' THEN ARRAY['operation','endpoint_id','request_template','accepted_pointer','accepted_value','task_key_pointer',
            'task_status_pointer','task_success_value','task_failure_value','task_pending_values','observed_price_pointer',
            'observed_currency_pointer','conditional_write_header','version_token_header','owner_label'] END;
    IF p_kind='OPERATION' AND capability.capability_code='listing-description-change' THEN
        keys:=keys||ARRAY['description_observed_text_pointer','description_kiz_marked_pointer',
            'description_attribute_key','description_response_binding','ad_not_applied_pointer','ad_not_applied_value','description_request_guard'];
    END IF;
    IF keys IS NULL OR jsonb_typeof(p_definition) IS DISTINCT FROM 'object'
        OR (p_definition-keys)<>'{}'::jsonb OR octet_length(p_definition::text)>16384 THEN
        RAISE EXCEPTION 'unknown or unbounded draft fields' USING ERRCODE='MO039';
    END IF;
    IF p_kind='PROFILE' THEN
        SELECT * INTO profile FROM jsonb_populate_record(NULL::platform.platform_api_profile,p_definition);
        IF profile.request_timeout_ms NOT BETWEEN 1000 AND 60000 OR profile.max_response_bytes NOT BETWEEN 1024 AND 8388608 THEN
            RAISE EXCEPTION 'profile exceeds transport limits' USING ERRCODE='MO039';
        END IF;
        INSERT INTO platform.platform_api_profile(platform_code,base_url,request_timeout_ms,max_response_bytes,
            verification_state,owner_label,status,created_at,updated_at,version)
        SELECT capability.platform_code,profile.base_url,profile.request_timeout_ms,profile.max_response_bytes,
            'UNVERIFIED',profile.owner_label,'RETIRED',clock_timestamp(),clock_timestamp(),0
        WHERE p_expected_version=-1 OR EXISTS (SELECT 1 FROM platform.platform_api_profile
            WHERE platform_code=capability.platform_code AND version=p_expected_version AND verification_state<>'VERIFIED')
        ON CONFLICT (platform_code) DO UPDATE SET base_url=excluded.base_url,request_timeout_ms=excluded.request_timeout_ms,
            max_response_bytes=excluded.max_response_bytes,owner_label=excluded.owner_label,updated_at=clock_timestamp(),
            version=platform.platform_api_profile.version+1
        WHERE platform.platform_api_profile.version=p_expected_version AND platform.platform_api_profile.verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
        entity:=capability.id;
    ELSIF p_kind='HEADER' THEN
        SELECT * INTO header FROM jsonb_populate_record(NULL::platform.platform_auth_header,p_definition);
        IF lower(header.header_name) IN ('host','connection','content-length','transfer-encoding','cookie','forwarded','proxy-authorization')
            OR lower(header.header_name) LIKE 'x-forwarded-%' OR lower(header.header_name) LIKE 'proxy-%'
            OR header.value_template ~ '[[:cntrl:]]' OR length(header.value_template)>256 THEN
            RAISE EXCEPTION 'unsafe authentication header shape' USING ERRCODE='MO039';
        END IF;
        INSERT INTO platform.platform_auth_header(id,platform_code,header_name,value_source,value_template,credential_purpose,
            ordinal,verification_state,owner_label,status,created_at,updated_at)
        SELECT entity,capability.platform_code,header.header_name,header.value_source,header.value_template,header.credential_purpose,
            header.ordinal,'UNVERIFIED',header.owner_label,'RETIRED',clock_timestamp(),clock_timestamp()
        WHERE p_expected_version=-1 OR EXISTS (SELECT 1 FROM platform.platform_auth_header
            WHERE id=entity AND version=p_expected_version AND platform_code=capability.platform_code AND verification_state<>'VERIFIED')
        ON CONFLICT (id) DO UPDATE SET header_name=excluded.header_name,value_source=excluded.value_source,value_template=excluded.value_template,
            credential_purpose=excluded.credential_purpose,ordinal=excluded.ordinal,owner_label=excluded.owner_label,
            updated_at=clock_timestamp(),version=platform.platform_auth_header.version+1
        WHERE platform.platform_auth_header.version=p_expected_version AND platform.platform_auth_header.platform_code=capability.platform_code
            AND platform.platform_auth_header.verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
    ELSIF p_kind='ENDPOINT' THEN
        SELECT * INTO endpoint FROM jsonb_populate_record(NULL::platform.platform_endpoint,p_definition);
        UPDATE platform.platform_endpoint SET http_method=endpoint.http_method,path_template=endpoint.path_template,
            operation_function=endpoint.operation_function,query_template=endpoint.query_template,body_template=endpoint.body_template,
            response_content_type=endpoint.response_content_type,continuation_pointer=endpoint.continuation_pointer,
            pagination_model=endpoint.pagination_model,rate_limit_per_minute=endpoint.rate_limit_per_minute,
            continuation_end_rule=coalesce(endpoint.continuation_end_rule,'JSON_NULL'),records_pointer=endpoint.records_pointer,
            updated_at=clock_timestamp(),version=version+1
        WHERE id=entity AND capability_id=capability.id AND version=p_expected_version AND verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
    ELSIF p_kind='CAPABILITY' THEN
        UPDATE platform.platform_capability SET write_result_model=p_definition->>'write_result_model',updated_at=clock_timestamp(),version=version+1
        WHERE id=capability.id AND version=p_expected_version AND verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
        entity:=capability.id;
    ELSE
        SELECT * INTO operation FROM jsonb_populate_record(NULL::platform.capability_operation,p_definition);
        INSERT INTO platform.capability_operation(id,capability_id,platform_code,operation,endpoint_id,request_template,accepted_pointer,
            accepted_value,task_key_pointer,task_status_pointer,task_success_value,task_failure_value,task_pending_values,
            observed_price_pointer,observed_currency_pointer,conditional_write_header,version_token_header,
            description_observed_text_pointer,description_kiz_marked_pointer,description_attribute_key,description_response_binding,ad_not_applied_pointer,ad_not_applied_value,description_request_guard,
            verification_state,owner_label,status,created_at,updated_at)
        SELECT entity,capability.id,capability.platform_code,operation.operation,operation.endpoint_id,operation.request_template,
            operation.accepted_pointer,operation.accepted_value,operation.task_key_pointer,operation.task_status_pointer,
            operation.task_success_value,operation.task_failure_value,coalesce(operation.task_pending_values,'{}'::text[]),
            operation.observed_price_pointer,operation.observed_currency_pointer,operation.conditional_write_header,operation.version_token_header,
            operation.description_observed_text_pointer,operation.description_kiz_marked_pointer,operation.description_attribute_key,operation.description_response_binding,operation.ad_not_applied_pointer,operation.ad_not_applied_value,operation.description_request_guard,
            'UNVERIFIED',operation.owner_label,'RETIRED',clock_timestamp(),clock_timestamp()
        WHERE p_expected_version=-1 OR EXISTS (SELECT 1 FROM platform.capability_operation
            WHERE id=entity AND capability_id=capability.id AND version=p_expected_version AND verification_state<>'VERIFIED')
        ON CONFLICT (id) DO UPDATE SET endpoint_id=excluded.endpoint_id,request_template=excluded.request_template,
            accepted_pointer=excluded.accepted_pointer,accepted_value=excluded.accepted_value,task_key_pointer=excluded.task_key_pointer,
            task_status_pointer=excluded.task_status_pointer,task_success_value=excluded.task_success_value,task_failure_value=excluded.task_failure_value,
            task_pending_values=excluded.task_pending_values,observed_price_pointer=excluded.observed_price_pointer,
            observed_currency_pointer=excluded.observed_currency_pointer,conditional_write_header=excluded.conditional_write_header,
            version_token_header=excluded.version_token_header,
            description_observed_text_pointer=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.description_observed_text_pointer ELSE platform.capability_operation.description_observed_text_pointer END,
            description_kiz_marked_pointer=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.description_kiz_marked_pointer ELSE platform.capability_operation.description_kiz_marked_pointer END,
            description_attribute_key=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.description_attribute_key ELSE platform.capability_operation.description_attribute_key END,
            description_response_binding=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.description_response_binding ELSE platform.capability_operation.description_response_binding END,
            ad_not_applied_pointer=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.ad_not_applied_pointer ELSE platform.capability_operation.ad_not_applied_pointer END,
            ad_not_applied_value=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.ad_not_applied_value ELSE platform.capability_operation.ad_not_applied_value END,
            description_request_guard=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.description_request_guard ELSE platform.capability_operation.description_request_guard END,
            owner_label=excluded.owner_label,updated_at=clock_timestamp(),
            version=platform.capability_operation.version+1
        WHERE platform.capability_operation.version=p_expected_version AND platform.capability_operation.capability_id=capability.id
            AND platform.capability_operation.operation=excluded.operation AND platform.capability_operation.verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
    END IF;
    IF changed<>1 THEN RAISE EXCEPTION 'draft version changed or verified revision not opened' USING ERRCODE='MO039'; END IF;
    INSERT INTO ops.metadata_audit_event(id,actor_type,actor_id,source_domain,action,entity_type,entity_id,change_summary,correlation_id)
    VALUES (gen_random_uuid(),'OPERATOR',p_actor::text,'marketplaceintegration','UPDATE','registry-draft',entity,
        jsonb_build_object('kind',p_kind,'definitionSha256',encode(sha256(convert_to(p_definition::text,'UTF8')),'hex')),p_correlation);
    RETURN entity;
END;
$$;

REVOKE ALL ON FUNCTION platform.configure_registry_draft(p_account uuid, p_capability uuid, p_actor uuid, p_kind text, p_id uuid, p_expected_version bigint, p_definition jsonb, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.configure_registry_draft(p_account uuid, p_capability uuid, p_actor uuid, p_kind text, p_id uuid, p_expected_version bigint, p_definition jsonb, p_correlation text) TO marketops_app;
