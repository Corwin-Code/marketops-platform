-- A missing historical schema remains unqualified. No provider-specific
-- attribute number, category bound or whole-card guarantee is invented.
ALTER TABLE platform.capability_operation ADD COLUMN description_request_guard jsonb
    CHECK (description_request_guard IS NULL OR jsonb_typeof(description_request_guard)='object');

CREATE FUNCTION platform.lc_description_request_guard_valid(p_guard jsonb,p_attribute text)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    node record; total integer:=0; listing_count integer:=0; attribute_count integer:=0;
    description_count integer:=0; marking_count integer:=0; binding text; scalar_type text;
    fields text[]:=ARRAY['schema','evidenceRef','mutationSemantics','markingPolicy','body'];
BEGIN
    IF p_guard IS NULL OR jsonb_typeof(p_guard)<>'object' OR NOT (p_guard ?& fields)
        OR p_guard-fields<>'{}'::jsonb OR p_guard->>'schema' IS DISTINCT FROM 'DESCRIPTION_REQUEST_V1'
        OR jsonb_typeof(p_guard->'evidenceRef') IS DISTINCT FROM 'string'
        OR nullif(btrim(p_guard->>'evidenceRef'),'') IS NULL
        OR coalesce(p_guard->>'mutationSemantics','') NOT IN ('PARTIAL_ATTRIBUTE','PARTIAL_FIELD')
        OR coalesce(p_guard->>'markingPolicy','') NOT IN ('REQUIRED','NOT_APPLICABLE')
        OR jsonb_typeof(p_guard->'body') NOT IN ('object','array') OR nullif(p_attribute,'') IS NULL THEN
        RETURN false;
    END IF;
    FOR node IN
        WITH RECURSIVE nodes(value,field_name,depth) AS (
            SELECT p_guard->'body',''::text,0
            UNION ALL
            SELECT child.value,child.field_name,parent.depth+1 FROM nodes parent
            CROSS JOIN LATERAL (
                SELECT e.value,e.key AS field_name FROM jsonb_each(
                    CASE WHEN jsonb_typeof(parent.value)='object' THEN parent.value ELSE '{}'::jsonb END) e
                UNION ALL
                SELECT a.value,(a.ordinality-1)::text FROM jsonb_array_elements(
                    CASE WHEN jsonb_typeof(parent.value)='array' THEN parent.value ELSE '[]'::jsonb END)
                    WITH ORDINALITY a(value,ordinality)
            ) child
            WHERE parent.depth<=32 AND NOT (jsonb_typeof(parent.value)='object' AND parent.value ? '$bind')
        ) SELECT * FROM nodes
    LOOP
        total:=total+1;
        IF total>4096 OR node.depth>32 THEN RETURN false; END IF;
        IF jsonb_typeof(node.value)='object' AND node.value ? '$bind' THEN
            IF NOT (node.value ?& ARRAY['$bind','$type']) OR node.value-ARRAY['$bind','$type']<>'{}'::jsonb
                OR jsonb_typeof(node.value->'$bind') IS DISTINCT FROM 'string'
                OR jsonb_typeof(node.value->'$type') IS DISTINCT FROM 'string' THEN RETURN false; END IF;
            binding:=node.value->>'$bind'; scalar_type:=node.value->>'$type';
            CASE binding
                WHEN 'LISTING_KEY' THEN
                    IF scalar_type NOT IN ('string','integer') THEN RETURN false; END IF;
                    listing_count:=listing_count+1;
                WHEN 'ATTRIBUTE_KEY' THEN
                    IF scalar_type NOT IN ('string','integer') THEN RETURN false; END IF;
                    attribute_count:=attribute_count+1;
                WHEN 'DESCRIPTION_TEXT' THEN
                    IF scalar_type<>'string' OR (p_guard->>'mutationSemantics'='PARTIAL_FIELD'
                        AND node.field_name IS DISTINCT FROM p_attribute) THEN RETURN false; END IF;
                    description_count:=description_count+1;
                WHEN 'KIZ_MARKED' THEN
                    IF scalar_type<>'boolean' THEN RETURN false; END IF;
                    marking_count:=marking_count+1;
                ELSE RETURN false;
            END CASE;
        END IF;
    END LOOP;
    RETURN listing_count=1 AND description_count=1
        AND attribute_count=CASE WHEN p_guard->>'mutationSemantics'='PARTIAL_ATTRIBUTE' THEN 1 ELSE 0 END
        AND marking_count=CASE WHEN p_guard->>'markingPolicy'='REQUIRED' THEN 1 ELSE 0 END;
END;
$$;
REVOKE ALL ON FUNCTION platform.lc_description_request_guard_valid(jsonb,text) FROM PUBLIC;

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
CREATE FUNCTION platform.lc_description_template_is_well_formed(p_template text)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp
AS $$
DECLARE rendered text:=p_template; token text[];
BEGIN
    IF p_template IS NULL OR length(p_template)>4096 THEN RETURN false; END IF;
    FOR token IN SELECT regexp_matches(p_template,'\{([a-zA-Z][a-zA-Z0-9]{0,31})\}','g') LOOP
        IF token[1] NOT IN ('nativeListingKey','nativeVariantKey','idempotencyKey','nativeTaskKey',
            'descriptionText','descriptionAttributeKey','kizMarkedDeclared') THEN RETURN false; END IF;
        rendered:=replace(rendered,'{'||token[1]||'}',CASE
            WHEN token[1] IN ('nativeListingKey','nativeVariantKey','nativeTaskKey','descriptionAttributeKey') THEN '1'
            WHEN token[1]='kizMarkedDeclared' THEN 'false' ELSE 'fixture' END);
    END LOOP;
    RETURN rendered IS JSON OBJECT WITH UNIQUE KEYS OR rendered IS JSON ARRAY WITH UNIQUE KEYS;
END;
$$;
REVOKE ALL ON FUNCTION platform.lc_description_template_is_well_formed(text) FROM PUBLIC;

CREATE OR REPLACE FUNCTION platform.capability_operation_matches_write_model()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    model text;
    capability platform.platform_capability%ROWTYPE;
    endpoint platform.platform_endpoint%ROWTYPE;
    expected_function text;
    all_templates text;
    required_value_token text;
    forbidden_value_tokens text[];
    forbidden_token text;
    required_object_tokens text[];
BEGIN
    IF cardinality(NEW.task_pending_values) <> (SELECT count(DISTINCT value) FROM unnest(NEW.task_pending_values) value)
        OR EXISTS (SELECT 1 FROM unnest(NEW.task_pending_values) value
                    WHERE length(value) NOT BETWEEN 1 AND 256 OR value ~ '[[:cntrl:]]') THEN
        RAISE EXCEPTION 'task pending states must be distinct bounded values' USING ERRCODE = 'MO036';
    END IF;

    SELECT * INTO capability FROM platform.platform_capability WHERE id = NEW.capability_id;
    SELECT * INTO endpoint FROM platform.platform_endpoint WHERE id = NEW.endpoint_id;
    model := capability.write_result_model;

    IF capability.capability_code = 'price-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'PRICE_APPLY' WHEN 'RESTORE' THEN 'PRICE_RESTORE'
            WHEN 'READBACK' THEN 'PRICE_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'PRICE_STATUS' END;
        required_value_token := '%{targetPrice}%';
        forbidden_value_tokens := ARRAY['%{targetBid}%', '%{descriptionText}%', '%{kizMarkedDeclared}%'];
        required_object_tokens := ARRAY['%{nativeListingKey}%', '%{nativeVariantKey}%'];
    ELSIF capability.capability_code = 'ad-bid-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'AD_BID_APPLY' WHEN 'RESTORE' THEN 'AD_BID_RESTORE'
            WHEN 'READBACK' THEN 'AD_BID_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'AD_BID_STATUS' END;
        required_value_token := '%{targetBid}%';
        forbidden_value_tokens := ARRAY['%{targetPrice}%', '%{descriptionText}%', '%{kizMarkedDeclared}%'];
        required_object_tokens := ARRAY['%{nativeCampaignKey}%', '%{nativeObjectKey}%'];
    ELSIF capability.capability_code = 'listing-description-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'DESCRIPTION_APPLY' WHEN 'RESTORE' THEN 'DESCRIPTION_RESTORE'
            WHEN 'READBACK' THEN 'DESCRIPTION_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'DESCRIPTION_STATUS' END;
        required_value_token := '%{descriptionText}%';
        forbidden_value_tokens := ARRAY['%{targetPrice}%', '%{targetBid}%'];
        required_object_tokens := ARRAY['%{nativeListingKey}%', '%{nativeVariantKey}%'];
    ELSE
        RAISE EXCEPTION 'no write shape is defined for this capability' USING ERRCODE = 'MO036';
    END IF;

    IF NEW.operation IN ('APPLY', 'RESTORE') THEN
        IF NEW.request_template NOT LIKE required_value_token THEN
            RAISE EXCEPTION 'a mutating operation must carry its own target placeholder'
                USING ERRCODE = 'MO036';
        END IF;
    END IF;
    all_templates := coalesce(endpoint.path_template, '') || coalesce(endpoint.query_template, '')
        || coalesce(NEW.request_template, '');
    FOREACH forbidden_token IN ARRAY forbidden_value_tokens LOOP
        IF all_templates LIKE forbidden_token THEN
            RAISE EXCEPTION 'an operation may not carry another capability''s target placeholder'
                USING ERRCODE = 'MO036';
        END IF;
    END LOOP;

    IF capability.read_write_class <> 'WRITE'
        OR endpoint.capability_id IS DISTINCT FROM NEW.capability_id
        OR endpoint.platform_code IS DISTINCT FROM NEW.platform_code
        OR capability.platform_code IS DISTINCT FROM NEW.platform_code
        OR (NEW.operation IN ('APPLY', 'RESTORE') AND
            (endpoint.read_write_class <> 'WRITE' OR endpoint.http_method NOT IN ('POST','PUT','PATCH')))
        OR (NEW.operation IN ('READBACK', 'STATUS_ENQUIRY') AND
            (endpoint.read_write_class <> 'READ' OR endpoint.http_method NOT IN ('GET','POST')))
        OR (endpoint.operation_function <> 'UNDECLARED'
            AND endpoint.operation_function <> expected_function) THEN
        RAISE EXCEPTION 'operation is incompatible with capability or endpoint semantics'
            USING ERRCODE = 'MO036';
    END IF;

    IF NEW.verification_state = 'VERIFIED' THEN
        IF endpoint.operation_function <> expected_function OR endpoint.http_method IS NULL
            OR endpoint.body_template IS NOT NULL
            OR (endpoint.http_method = 'GET' AND NEW.request_template <> '')
            OR (NEW.operation <> 'STATUS_ENQUIRY'
                AND all_templates NOT LIKE required_object_tokens[1]
                AND all_templates NOT LIKE required_object_tokens[2])
            OR (NEW.operation IN ('APPLY', 'RESTORE')
                AND capability.capability_code = 'price-change'
                AND all_templates NOT LIKE '%{currencyCode}%')
            OR (NEW.operation IN ('APPLY', 'RESTORE')
                AND capability.capability_code = 'ad-bid-change'
                AND (all_templates NOT LIKE '%{currencyCode}%'
                     OR all_templates NOT LIKE '%{bidUnitCode}%'))
            -- A description write names the attribute it changes. Without the
            -- attribute key the request could be a whole-card import, and a
            -- whole-card import is exactly the write this product does not make.
            OR (NEW.operation IN ('APPLY', 'RESTORE')
                AND capability.capability_code = 'listing-description-change'
                AND (all_templates NOT LIKE '%{descriptionAttributeKey}%'
                     OR NEW.description_attribute_key IS NULL))
            OR (NEW.operation = 'READBACK'
                AND capability.capability_code = 'listing-description-change'
                AND NEW.description_observed_text_pointer IS NULL)
            OR (NEW.operation = 'STATUS_ENQUIRY' AND all_templates NOT LIKE '%{nativeTaskKey}%') THEN
            RAISE EXCEPTION 'verified operation has incomplete request semantics'
                USING ERRCODE = 'MO036';
        END IF;
        IF NOT platform.request_template_is_well_formed(endpoint.path_template, false, true)
            OR NOT platform.request_template_is_well_formed(endpoint.query_template, false, true)
            OR (endpoint.http_method <> 'GET'
                AND CASE WHEN capability.capability_code='listing-description-change'
                    THEN NOT platform.lc_description_template_is_well_formed(NEW.request_template)
                    ELSE NOT platform.request_template_is_well_formed(NEW.request_template, true, true) END) THEN
            RAISE EXCEPTION 'verified operation request template is invalid' USING ERRCODE = 'MO036';
        END IF;
    END IF;

    IF model = 'ASYNCHRONOUS_TASK' AND NEW.operation IN ('APPLY', 'RESTORE')
        AND NEW.task_key_pointer IS NULL THEN
        RAISE EXCEPTION 'an asynchronous apply must record where the platform task key lives'
            USING ERRCODE = 'MO036';
    END IF;

    IF model = 'SYNCHRONOUS' AND NEW.operation = 'STATUS_ENQUIRY' THEN
        RAISE EXCEPTION 'a synchronous capability has no asynchronous task to enquire about'
            USING ERRCODE = 'MO036';
    END IF;

    RETURN NEW;
END;
$$;
