-- Extend the existing local registry maintenance and independent verification
-- authority. No operation, account evidence, credential or write flag is seeded.
CREATE FUNCTION platform.lc_description_response_descriptor_valid(p_descriptor jsonb,p_operation text,p_model text)
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
            'listingKeyPointer','listingKeyType','taskEchoPointer','statusValueType']<>'{}'::jsonb THEN
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
        pointer := p_descriptor->>'taskEchoPointer';
        IF jsonb_typeof(p_descriptor->'taskEchoPointer') IS DISTINCT FROM 'string'
            OR left(pointer,1)<>'/' OR pointer ~ '~([^01]|$)'
            OR coalesce(p_descriptor->>'statusValueType','') NOT IN ('string','number') THEN RETURN false; END IF;
    END IF;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION platform.lc_description_response_descriptor_valid(jsonb,text,text) FROM PUBLIC;

CREATE OR REPLACE FUNCTION platform.registry_configuration_snapshot(p_capability uuid)
RETURNS jsonb LANGUAGE sql STABLE SET search_path=pg_catalog,pg_temp
AS $$
    SELECT jsonb_build_object('capability',to_jsonb(c),
        'profile',(SELECT to_jsonb(p) FROM platform.platform_api_profile p WHERE p.platform_code=c.platform_code),
        'headers',(SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY h.id),'[]'::jsonb)
            FROM platform.platform_auth_header h WHERE h.platform_code=c.platform_code
                AND h.credential_purpose=CASE WHEN c.capability_code='listing-description-change' AND c.read_write_class='WRITE' THEN 'CONTENT_WRITE'
                    WHEN c.read_write_class='WRITE' THEN 'PRICE_WRITE' ELSE 'READ' END),
        'endpoints',(SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.id),'[]'::jsonb)
            FROM platform.platform_endpoint e WHERE e.capability_id=c.id),
        'operations',(SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.id),'[]'::jsonb)
            FROM platform.capability_operation o WHERE o.capability_id=c.id),
        'permissionRequirements',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]'::jsonb)
            FROM platform.platform_permission_requirement r WHERE r.capability_id=c.id
                OR r.endpoint_id IN (SELECT id FROM platform.platform_endpoint WHERE capability_id=c.id)))
    FROM platform.platform_capability c WHERE c.id=p_capability
$$;

CREATE OR REPLACE FUNCTION platform.configure_registry_draft(p_account uuid,p_capability uuid,p_actor uuid,p_kind text,
    p_id uuid,p_expected_version bigint,p_definition jsonb,p_correlation text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp
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
            'response_content_type','continuation_pointer','pagination_model','rate_limit_per_minute']
        WHEN 'CAPABILITY' THEN ARRAY['write_result_model']
        WHEN 'OPERATION' THEN ARRAY['operation','endpoint_id','request_template','accepted_pointer','accepted_value','task_key_pointer',
            'task_status_pointer','task_success_value','task_failure_value','task_pending_values','observed_price_pointer',
            'observed_currency_pointer','conditional_write_header','version_token_header','owner_label'] END;
    IF p_kind='OPERATION' AND capability.capability_code='listing-description-change' THEN
        keys:=keys||ARRAY['description_observed_text_pointer','description_kiz_marked_pointer',
            'description_attribute_key','description_response_binding','ad_not_applied_pointer','ad_not_applied_value'];
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
            description_observed_text_pointer,description_kiz_marked_pointer,description_attribute_key,description_response_binding,ad_not_applied_pointer,ad_not_applied_value,
            verification_state,owner_label,status,created_at,updated_at)
        SELECT entity,capability.id,capability.platform_code,operation.operation,operation.endpoint_id,operation.request_template,
            operation.accepted_pointer,operation.accepted_value,operation.task_key_pointer,operation.task_status_pointer,
            operation.task_success_value,operation.task_failure_value,coalesce(operation.task_pending_values,'{}'::text[]),
            operation.observed_price_pointer,operation.observed_currency_pointer,operation.conditional_write_header,operation.version_token_header,
            operation.description_observed_text_pointer,operation.description_kiz_marked_pointer,operation.description_attribute_key,operation.description_response_binding,operation.ad_not_applied_pointer,operation.ad_not_applied_value,
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
