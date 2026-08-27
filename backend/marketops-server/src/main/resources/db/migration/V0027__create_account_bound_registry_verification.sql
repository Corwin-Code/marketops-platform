-- Account evidence and official source evidence are reviewed together against
-- one immutable configuration snapshot. No provider facts or approvals are seeded.
-- This workflow does not change feature flags, write gates or pilot allowlists.

CREATE TABLE platform.registry_verification_case (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    marketplace_account_id uuid NOT NULL,
    capability_id uuid NOT NULL REFERENCES platform.platform_capability(id),
    endpoint_ids uuid[] NOT NULL,
    auth_header_ids uuid[] NOT NULL,
    official_source_url text NOT NULL,
    official_source_sha256 text NOT NULL,
    account_evidence_ref text NOT NULL,
    account_evidence_sha256 text NOT NULL,
    evidence_class text NOT NULL,
    tested_at timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    submitted_by_user_id uuid NOT NULL,
    reviewed_by_user_id uuid,
    submitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    reviewed_at timestamptz,
    state text NOT NULL DEFAULT 'SUBMITTED',
    configuration_snapshot jsonb NOT NULL,
    submitted_configuration_snapshot jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (marketplace_account_id,organization_id) REFERENCES core.marketplace_account(id,organization_id),
    FOREIGN KEY (submitted_by_user_id,organization_id) REFERENCES iam.user_account(id,organization_id),
    FOREIGN KEY (reviewed_by_user_id,organization_id) REFERENCES iam.user_account(id,organization_id),
    CHECK (state IN ('SUBMITTED','APPROVED','REJECTED','REVOKED')),
    CHECK (evidence_class IN ('REAL_ACCOUNT','PROTOCOL_FIXTURE')),
    CHECK (official_source_sha256 ~ '^[0-9a-f]{64}$' AND account_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (length(official_source_url)<=2048 AND official_source_url ~ '^https://[a-z0-9.-]+(/[A-Za-z0-9._~%/-]*)?$'),
    CHECK (length(account_evidence_ref) BETWEEN 13 AND 412 AND account_evidence_ref ~ '^evidence://[a-z0-9][a-z0-9./_-]+$'),
    CHECK (tested_at<valid_until AND valid_until<=tested_at+interval '30 days'),
    CHECK (cardinality(endpoint_ids) BETWEEN 1 AND 32 AND array_position(endpoint_ids,NULL) IS NULL),
    CHECK (cardinality(auth_header_ids) BETWEEN 1 AND 16 AND array_position(auth_header_ids,NULL) IS NULL),
    CHECK (reviewed_by_user_id IS NULL OR reviewed_by_user_id<>submitted_by_user_id),
    CHECK (state<>'APPROVED' OR (evidence_class='REAL_ACCOUNT' AND reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE INDEX registry_verification_current_ix ON platform.registry_verification_case
    (marketplace_account_id,capability_id,valid_until) WHERE state='APPROVED';
GRANT SELECT ON platform.registry_verification_case TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES ('platform','registry_verification_case','NO_ROUTE',NULL,
    'account-bound evidence checked at every external dispatch; no ingestion schedule or write enablement');

CREATE FUNCTION platform.registry_configuration_snapshot(p_capability uuid)
RETURNS jsonb LANGUAGE sql STABLE SET search_path=pg_catalog,pg_temp
AS $$
    SELECT jsonb_build_object('capability',to_jsonb(c),
        'profile',(SELECT to_jsonb(p) FROM platform.platform_api_profile p WHERE p.platform_code=c.platform_code),
        'headers',(SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY h.id),'[]'::jsonb)
            FROM platform.platform_auth_header h WHERE h.platform_code=c.platform_code
                AND h.credential_purpose=CASE c.read_write_class WHEN 'WRITE' THEN 'PRICE_WRITE' ELSE 'READ' END),
        'endpoints',(SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.id),'[]'::jsonb)
            FROM platform.platform_endpoint e WHERE e.capability_id=c.id),
        'operations',(SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.id),'[]'::jsonb)
            FROM platform.capability_operation o WHERE o.capability_id=c.id),
        'permissionRequirements',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]'::jsonb)
            FROM platform.platform_permission_requirement r WHERE r.capability_id=c.id
                OR r.endpoint_id IN (SELECT id FROM platform.platform_endpoint WHERE capability_id=c.id)))
    FROM platform.platform_capability c WHERE c.id=p_capability
$$;
REVOKE ALL ON FUNCTION platform.registry_configuration_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.registry_configuration_snapshot(uuid) TO marketops_app;

CREATE FUNCTION platform.registry_operator_allowed(p_actor uuid,p_account uuid)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM core.marketplace_account account
        JOIN core.organization org ON org.id=account.organization_id
        JOIN iam.user_account actor ON actor.organization_id=org.id
        JOIN iam.user_role_assignment role ON role.user_id=actor.id
        JOIN iam.user_scope_grant grant_row ON grant_row.user_id=actor.id
        WHERE account.id=p_account AND actor.id=p_actor AND actor.status='ACTIVE'
          AND account.status='ACTIVE' AND org.status='ACTIVE'
          AND role.role_code='OWNER' AND role.status='ACTIVE'
          AND role.effective_from<=statement_timestamp() AND (role.effective_to IS NULL OR role.effective_to>statement_timestamp())
          AND grant_row.action_code='KILL_SWITCH_OPERATE' AND grant_row.status='ACTIVE'
          AND grant_row.effective_from<=statement_timestamp() AND (grant_row.effective_to IS NULL OR grant_row.effective_to>statement_timestamp())
          AND (grant_row.organization_ref_id=org.id OR grant_row.marketplace_account_ref_id=account.id))
$$;
REVOKE ALL ON FUNCTION platform.registry_operator_allowed(uuid,uuid) FROM PUBLIC;

CREATE FUNCTION platform.audit_registry_verification(p_actor uuid,p_entity uuid,p_from text,p_to text,p_evidence text,p_correlation text)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp
AS $$
BEGIN
    IF p_correlation IS NULL OR length(p_correlation) NOT BETWEEN 1 AND 128 OR p_correlation ~ '[[:cntrl:]]' THEN
        RAISE EXCEPTION 'verification correlation is invalid' USING ERRCODE='MO039';
    END IF;
    INSERT INTO ops.metadata_audit_event(id,actor_type,actor_id,source_domain,action,entity_type,entity_id,
        change_summary,evidence_ref,correlation_id)
    VALUES (gen_random_uuid(),'OPERATOR',p_actor::text,'marketplaceintegration','VERIFICATION_CHANGE',
        'registry-verification',p_entity,jsonb_build_object('state',jsonb_build_object('oldValue',p_from,'newValue',p_to)),
        p_evidence,p_correlation);
END;
$$;
REVOKE ALL ON FUNCTION platform.audit_registry_verification(uuid,uuid,text,text,text,text) FROM PUBLIC;

-- Ordinary metadata DML can prepare unverified facts, but cannot create or
-- mutate verified facts. SECURITY DEFINER workflow functions remain the only
-- application-reachable writer of the verified projection.
CREATE FUNCTION platform.guard_verified_registry_writer() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp
AS $$
BEGIN
    IF current_user='marketops_app' AND (NEW.verification_state='VERIFIED'
        OR (TG_OP='UPDATE' AND OLD.verification_state='VERIFIED')) THEN
        RAISE EXCEPTION 'verified registry facts require the reviewed evidence workflow' USING ERRCODE='MO039';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION platform.guard_verified_registry_writer() FROM PUBLIC;
CREATE TRIGGER profile_verified_writer BEFORE INSERT OR UPDATE ON platform.platform_api_profile
    FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();
CREATE TRIGGER header_verified_writer BEFORE INSERT OR UPDATE ON platform.platform_auth_header
    FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();
CREATE TRIGGER operation_verified_writer BEFORE INSERT OR UPDATE ON platform.capability_operation
    FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();
CREATE TRIGGER endpoint_verified_writer BEFORE INSERT OR UPDATE ON platform.platform_endpoint
    FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();
CREATE TRIGGER capability_verified_writer BEFORE INSERT OR UPDATE ON platform.platform_capability
    FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();

CREATE FUNCTION platform.submit_registry_verification(p_account uuid,p_capability uuid,p_actor uuid,
    p_endpoints uuid[],p_headers uuid[],p_evidence jsonb,p_expected_digest text,p_correlation text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp
AS $$
DECLARE account core.marketplace_account%ROWTYPE; capability platform.platform_capability%ROWTYPE;
    snapshot jsonb; case_id uuid:=gen_random_uuid(); tested timestamptz; expires timestamptz;
BEGIN
    SELECT * INTO account FROM core.marketplace_account WHERE id=p_account FOR SHARE;
    SELECT * INTO capability FROM platform.platform_capability WHERE id=p_capability FOR SHARE;
    IF NOT platform.registry_operator_allowed(p_actor,p_account) OR capability.platform_code IS DISTINCT FROM account.platform_code THEN
        RAISE EXCEPTION 'verification account authority denied' USING ERRCODE='MO039';
    END IF;
    snapshot:=platform.registry_configuration_snapshot(p_capability);
    IF snapshot IS NULL OR encode(sha256(convert_to(snapshot::text,'UTF8')),'hex') IS DISTINCT FROM p_expected_digest
        OR octet_length(snapshot::text)>262144 OR jsonb_typeof(p_evidence) IS DISTINCT FROM 'object'
        OR (p_evidence-ARRAY['officialSourceUrl','officialSourceSha256','accountEvidenceRef','accountEvidenceSha256','evidenceClass','testedAt','validUntil'])<>'{}'::jsonb THEN
        RAISE EXCEPTION 'verification configuration or evidence changed' USING ERRCODE='MO039';
    END IF;
    tested:=(p_evidence->>'testedAt')::timestamptz; expires:=(p_evidence->>'validUntil')::timestamptz;
    IF tested>clock_timestamp() OR expires<=clock_timestamp()
        OR cardinality(p_endpoints) NOT BETWEEN 1 AND 32 OR cardinality(p_headers) NOT BETWEEN 1 AND 16
        OR (SELECT count(*) FROM platform.platform_endpoint WHERE id=ANY(p_endpoints) AND capability_id=p_capability)<>cardinality(p_endpoints)
        OR (SELECT count(*) FROM platform.platform_auth_header WHERE id=ANY(p_headers) AND platform_code=capability.platform_code)<>cardinality(p_headers) THEN
        RAISE EXCEPTION 'verification members or evidence window are invalid' USING ERRCODE='MO039';
    END IF;
    INSERT INTO platform.registry_verification_case(id,organization_id,marketplace_account_id,capability_id,
        endpoint_ids,auth_header_ids,official_source_url,official_source_sha256,account_evidence_ref,account_evidence_sha256,
        evidence_class,tested_at,valid_until,submitted_by_user_id,configuration_snapshot,submitted_configuration_snapshot)
    VALUES (case_id,account.organization_id,p_account,p_capability,p_endpoints,p_headers,
        p_evidence->>'officialSourceUrl',p_evidence->>'officialSourceSha256',p_evidence->>'accountEvidenceRef',
        p_evidence->>'accountEvidenceSha256',p_evidence->>'evidenceClass',tested,expires,p_actor,snapshot,snapshot);
    PERFORM platform.audit_registry_verification(p_actor,case_id,NULL,'SUBMITTED',p_evidence->>'accountEvidenceRef',p_correlation);
    RETURN case_id;
END;
$$;
REVOKE ALL ON FUNCTION platform.submit_registry_verification(uuid,uuid,uuid,uuid[],uuid[],jsonb,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.submit_registry_verification(uuid,uuid,uuid,uuid[],uuid[],jsonb,text,text) TO marketops_app;

CREATE FUNCTION platform.review_registry_verification(p_case uuid,p_actor uuid,p_expected_version bigint,
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
    expected_purpose:=CASE capability.read_write_class WHEN 'WRITE' THEN 'PRICE_WRITE' ELSE 'READ' END;
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
            IF capability.capability_code<>'price-change' OR capability.write_result_model='UNKNOWN'
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
REVOKE ALL ON FUNCTION platform.review_registry_verification(uuid,uuid,bigint,boolean,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.review_registry_verification(uuid,uuid,bigint,boolean,text) TO marketops_app;

CREATE FUNCTION platform.capability_evidence_current(p_account uuid,p_capability uuid,p_endpoint uuid)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM platform.registry_verification_case evidence
        JOIN core.marketplace_account account ON account.id=evidence.marketplace_account_id
        JOIN core.organization org ON org.id=account.organization_id
        JOIN core.legal_entity legal ON legal.id=account.legal_entity_id
        WHERE evidence.marketplace_account_id=p_account AND evidence.capability_id=p_capability
          AND account.status='ACTIVE' AND org.status='ACTIVE' AND legal.status='ACTIVE'
          AND evidence.state='APPROVED' AND evidence.evidence_class='REAL_ACCOUNT'
          AND evidence.tested_at<=statement_timestamp() AND evidence.valid_until>statement_timestamp()
          AND (p_endpoint IS NULL OR p_endpoint=ANY(evidence.endpoint_ids))
          AND evidence.configuration_snapshot=platform.registry_configuration_snapshot(p_capability))
$$;
REVOKE ALL ON FUNCTION platform.capability_evidence_current(uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.capability_evidence_current(uuid,uuid,uuid) TO marketops_app;

CREATE FUNCTION platform.revoke_registry_verification(p_case uuid,p_actor uuid,p_expected_version bigint,p_correlation text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp
AS $$
DECLARE evidence platform.registry_verification_case%ROWTYPE;
BEGIN
    SELECT * INTO evidence FROM platform.registry_verification_case WHERE id=p_case FOR UPDATE;
    IF evidence.id IS NULL OR evidence.state<>'APPROVED' OR evidence.version IS DISTINCT FROM p_expected_version
        OR NOT platform.registry_operator_allowed(p_actor,evidence.marketplace_account_id) THEN
        RAISE EXCEPTION 'verification revocation authority denied' USING ERRCODE='MO039';
    END IF;
    UPDATE platform.registry_verification_case SET state='REVOKED',version=version+1 WHERE id=p_case;
    PERFORM platform.audit_registry_verification(p_actor,p_case,'APPROVED','REVOKED',evidence.account_evidence_ref,p_correlation);
END;
$$;
REVOKE ALL ON FUNCTION platform.revoke_registry_verification(uuid,uuid,bigint,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.revoke_registry_verification(uuid,uuid,bigint,text) TO marketops_app;

-- A configuration revision invalidates every account snapshot before ordinary
-- unverified maintenance can proceed.
CREATE FUNCTION platform.begin_registry_revision(p_account uuid,p_capability uuid,p_actor uuid,p_expected_digest text,p_correlation text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp
AS $$
DECLARE capability platform.platform_capability%ROWTYPE;
BEGIN
    SELECT * INTO capability FROM platform.platform_capability WHERE id=p_capability FOR UPDATE;
    IF NOT platform.registry_operator_allowed(p_actor,p_account) OR NOT EXISTS (
        SELECT 1 FROM core.marketplace_account WHERE id=p_account AND platform_code=capability.platform_code)
        OR encode(sha256(convert_to(platform.registry_configuration_snapshot(p_capability)::text,'UTF8')),'hex') IS DISTINCT FROM p_expected_digest THEN
        RAISE EXCEPTION 'configuration revision authority or version invalid' USING ERRCODE='MO039';
    END IF;
    UPDATE platform.platform_api_profile SET status='RETIRED',verification_state='UNVERIFIED',updated_at=clock_timestamp(),version=version+1
        WHERE platform_code=capability.platform_code;
    UPDATE platform.platform_auth_header SET status='RETIRED',verification_state='UNVERIFIED',updated_at=clock_timestamp(),version=version+1
        WHERE platform_code=capability.platform_code;
    UPDATE platform.platform_capability SET verification_state='UNVERIFIED',updated_at=clock_timestamp(),version=version+1 WHERE id=p_capability;
    UPDATE platform.platform_endpoint SET verification_state='UNVERIFIED',updated_at=clock_timestamp(),version=version+1 WHERE capability_id=p_capability;
    UPDATE platform.capability_operation SET status='RETIRED',verification_state='UNVERIFIED',updated_at=clock_timestamp(),version=version+1 WHERE capability_id=p_capability;
    INSERT INTO ops.metadata_audit_event(id,actor_type,actor_id,source_domain,action,entity_type,entity_id,change_summary,correlation_id)
    VALUES (gen_random_uuid(),'OPERATOR',p_actor::text,'marketplaceintegration','VERIFICATION_CHANGE','platform-capability',p_capability,
        jsonb_build_object('verificationState',jsonb_build_object('oldValue',capability.verification_state,'newValue','UNVERIFIED')),p_correlation);
END;
$$;
REVOKE ALL ON FUNCTION platform.begin_registry_revision(uuid,uuid,uuid,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.begin_registry_revision(uuid,uuid,uuid,text,text) TO marketops_app;

CREATE FUNCTION platform.configure_registry_draft(p_account uuid,p_capability uuid,p_actor uuid,p_kind text,
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
            verification_state,owner_label,status,created_at,updated_at)
        SELECT entity,capability.id,capability.platform_code,operation.operation,operation.endpoint_id,operation.request_template,
            operation.accepted_pointer,operation.accepted_value,operation.task_key_pointer,operation.task_status_pointer,
            operation.task_success_value,operation.task_failure_value,coalesce(operation.task_pending_values,'{}'::text[]),
            operation.observed_price_pointer,operation.observed_currency_pointer,operation.conditional_write_header,operation.version_token_header,
            'UNVERIFIED',operation.owner_label,'RETIRED',clock_timestamp(),clock_timestamp()
        WHERE p_expected_version=-1 OR EXISTS (SELECT 1 FROM platform.capability_operation
            WHERE id=entity AND capability_id=capability.id AND version=p_expected_version AND verification_state<>'VERIFIED')
        ON CONFLICT (id) DO UPDATE SET endpoint_id=excluded.endpoint_id,request_template=excluded.request_template,
            accepted_pointer=excluded.accepted_pointer,accepted_value=excluded.accepted_value,task_key_pointer=excluded.task_key_pointer,
            task_status_pointer=excluded.task_status_pointer,task_success_value=excluded.task_success_value,task_failure_value=excluded.task_failure_value,
            task_pending_values=excluded.task_pending_values,observed_price_pointer=excluded.observed_price_pointer,
            observed_currency_pointer=excluded.observed_currency_pointer,conditional_write_header=excluded.conditional_write_header,
            version_token_header=excluded.version_token_header,owner_label=excluded.owner_label,updated_at=clock_timestamp(),
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
REVOKE ALL ON FUNCTION platform.configure_registry_draft(uuid,uuid,uuid,text,uuid,bigint,jsonb,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.configure_registry_draft(uuid,uuid,uuid,text,uuid,bigint,jsonb,text) TO marketops_app;
