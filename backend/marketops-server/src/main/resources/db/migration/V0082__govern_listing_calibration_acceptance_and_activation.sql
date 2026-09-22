-- S4-DR-R1-011: calibration is governed by the existing Policy authority.
-- No grants, platform capabilities, allowances or write switches are enabled.
INSERT INTO iam.action_scope(code,display_name,description,requires_step_up,ordinal) VALUES
 ('LISTING_CALIBRATION_PREPARE','Prepare listing calibration','Prepare an exact scoped calibration package with source evidence.',true,44),
 ('LISTING_CALIBRATION_VALIDATE','Validate listing calibration','Professionally validate the exact complete calibration package.',true,45),
 ('LISTING_CALIBRATION_ACCEPT','Accept listing calibration','Owner accepts and activates an exact professionally validated package.',true,46);
INSERT INTO iam.business_role_action_scope(role_code,action_code)
 SELECT r, a FROM unnest(ARRAY['OWNER','OPS_LEAD','FINANCE','RISK_AUTHORITY','TECH_DATA']) r
 CROSS JOIN unnest(ARRAY['LISTING_CALIBRATION_PREPARE','LISTING_CALIBRATION_VALIDATE']) a;
INSERT INTO iam.business_role_action_scope(role_code,action_code) VALUES ('OWNER','LISTING_CALIBRATION_ACCEPT');

ALTER TABLE core.lc_calibration_package ADD COLUMN purpose_code text NOT NULL DEFAULT 'LISTING_CONVERSION'
 CHECK (purpose_code IN ('LISTING_CONVERSION','DESCRIPTION_CORRECTION','BOUNDED_EXPLORATION','PROMOTION'));
ALTER TABLE core.lc_calibration_package DROP CONSTRAINT lc_calibration_package_no_overlap;
ALTER TABLE core.lc_calibration_package ADD CONSTRAINT lc_calibration_package_no_overlap
 EXCLUDE USING gist (organization_id WITH =,scope_key WITH =,purpose_code WITH =,
                    tstzrange(effective_from,effective_to,'[)') WITH &&) WHERE (status='ACTIVE');

CREATE TABLE ops.lc_calibration_governance (
 package_id uuid PRIMARY KEY REFERENCES core.lc_calibration_package(id),
 replaces_package_id uuid REFERENCES core.lc_calibration_package(id),
 rationale text NOT NULL CHECK (length(btrim(rationale)) BETWEEN 1 AND 4096),
 impact text NOT NULL CHECK (length(btrim(impact)) BETWEEN 1 AND 4096),
 differences text NOT NULL CHECK (length(btrim(differences)) BETWEEN 1 AND 4096),
 drafted_by_user_id uuid NOT NULL REFERENCES iam.user_account(id),
 drafted_at timestamptz NOT NULL,
 draft_digest text NOT NULL CHECK (draft_digest ~ '^[0-9a-f]{64}$'),
 validated_by_user_id uuid REFERENCES iam.user_account(id),
 validated_at timestamptz,
 validation_reference text,
 validated_digest text,
 accepted_by_user_id uuid REFERENCES iam.user_account(id),
 accepted_at timestamptz,
 acceptance_reference text,
 accepted_digest text,
 CHECK ((validated_at IS NULL AND validated_by_user_id IS NULL AND validated_digest IS NULL AND validation_reference IS NULL)
     OR (validated_at IS NOT NULL AND validated_by_user_id IS NOT NULL AND validated_digest IS NOT NULL
       AND validation_reference IS NOT NULL AND validated_digest=draft_digest
       AND length(btrim(validation_reference)) BETWEEN 1 AND 512)),
 CHECK ((accepted_at IS NULL AND accepted_by_user_id IS NULL AND accepted_digest IS NULL AND acceptance_reference IS NULL)
     OR (accepted_at IS NOT NULL AND accepted_by_user_id IS NOT NULL AND accepted_digest IS NOT NULL
       AND acceptance_reference IS NOT NULL AND accepted_digest=validated_digest
       AND validated_at IS NOT NULL AND accepted_by_user_id<>drafted_by_user_id
       AND accepted_by_user_id<>validated_by_user_id AND length(btrim(acceptance_reference)) BETWEEN 1 AND 512))
);
CREATE TABLE ops.lc_calibration_event (
 id uuid PRIMARY KEY,
 package_id uuid NOT NULL REFERENCES core.lc_calibration_package(id),
 event_kind text NOT NULL CHECK (event_kind IN ('DRAFTED','VALIDATED','ACCEPTED','ACTIVATED','RETIRED')),
 actor_user_id uuid NOT NULL REFERENCES iam.user_account(id),
 package_digest text NOT NULL CHECK (package_digest ~ '^[0-9a-f]{64}$'),
 evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
 occurred_at timestamptz NOT NULL
);
GRANT SELECT ON ops.lc_calibration_governance,ops.lc_calibration_event TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note) VALUES
 ('ops','lc_calibration_governance','NO_ROUTE',NULL,'exact scoped package validation and Owner acceptance; only sealed Policy functions write'),
 ('ops','lc_calibration_event','NO_ROUTE',NULL,'append-only calibration lifecycle evidence');

CREATE OR REPLACE FUNCTION iam.issue_ad_control_invocation_grant(p_purpose text,p_proof_hash text,p_actor uuid,p_org uuid,
 p_provider uuid,p_subject text,p_session text,p_authenticated timestamptz,p_step_up_until timestamptz,
 p_target uuid,p_version uuid,p_backend integer,p_transaction bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,iam,pg_temp AS $$ BEGIN
 IF p_purpose NOT IN ('COMPENSATION_PREVIEW','COMPENSATION_ENDORSE','COMPENSATION_APPROVE',
 'BUNDLE_DRAFT','BUNDLE_ENDORSE','BUNDLE_APPROVE','CONTAINMENT_STOP','AUTHORITY_VERSION_STOP','CONTAINMENT_REENABLE',
 'CONTAINMENT_ATTEST','CONTAINMENT_ENDORSE','MANUAL_POLICY_PUBLISH','MANUAL_PACKET_SELECT',
 'MANUAL_PACKET_ENDORSE','MANUAL_PACKET_APPROVE','MANUAL_EXECUTION_REPORT','MANUAL_EXECUTION_START','MANUAL_INDEPENDENT_VERIFY',
 'LISTING_ACTION_LAUNCH','LISTING_ACTION_REVIEW','LISTING_ACTION_APPROVE','LISTING_MANUAL_VERIFY',
 'LISTING_OCCUPATION_RELEASE','LISTING_CONTAINMENT_STOP','LISTING_CONTAINMENT_ATTEST',
 'LISTING_CONTAINMENT_CONSENT','LISTING_PROMOTION_EXIT',
 'LISTING_CALIBRATION_PREPARE','LISTING_CALIBRATION_VALIDATE','LISTING_CALIBRATION_ACCEPT','LISTING_CALIBRATION_ACTIVATE') THEN
  RAISE EXCEPTION 'unknown control invocation purpose' USING ERRCODE='MO092'; END IF;
 PERFORM iam.issue_ad_invocation_grant(p_proof_hash,p_actor,p_org,p_provider,p_subject,p_session,
 p_authenticated,p_step_up_until,p_target,p_version,p_backend,p_transaction);
 UPDATE iam.ad_invocation_grant SET purpose=p_purpose WHERE proof_hash=p_proof_hash;
END $$;

CREATE FUNCTION ops.lc_calibration_actor_scope(p_actor uuid,p_org uuid,p_scope text,p_store uuid,p_action text)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT CASE WHEN p_scope='STORE' THEN ops.lc_actor_holds_action(p_actor,p_org,p_store,p_action)
 ELSE EXISTS (
  SELECT 1 FROM iam.user_role_assignment r
   JOIN iam.business_role_action_scope m ON m.role_code=r.role_code
   JOIN iam.user_scope_grant s ON s.user_id=r.user_id AND s.organization_id=r.organization_id AND s.action_code=m.action_code
  WHERE r.user_id=p_actor AND r.organization_id=p_org AND r.status='ACTIVE' AND m.action_code=p_action
   AND r.effective_from<=statement_timestamp() AND (r.effective_to IS NULL OR r.effective_to>statement_timestamp())
   AND s.organization_ref_id=p_org AND s.status='ACTIVE' AND s.effective_from<=statement_timestamp()
   AND (s.effective_to IS NULL OR s.effective_to>statement_timestamp())) END
$$;
REVOKE ALL ON FUNCTION ops.lc_calibration_actor_scope(uuid,uuid,text,uuid,text) FROM PUBLIC,marketops_app;

CREATE FUNCTION ops.lc_calibration_digest(p_package uuid) RETURNS text
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object(
  'id',p.id,'organizationId',p.organization_id,'code',p.package_code,'version',p.package_version,
  'scopeKind',p.scope_kind,'platformCode',p.platform_code,'storeId',p.store_ref_id,
  'effectiveFrom',p.effective_from,'effectiveTo',p.effective_to,'evidence',p.evidence_reference,
  'replacesPackageId',g.replaces_package_id,'purpose',p.purpose_code,'rationale',g.rationale,'impact',g.impact,'differences',g.differences,
  'values',(SELECT jsonb_agg(to_jsonb(v)-'id'-'package_id' ORDER BY v.category_code)
            FROM core.lc_calibration_value v WHERE v.package_id=p.id))::text,'UTF8')),'hex')
 FROM core.lc_calibration_package p LEFT JOIN ops.lc_calibration_governance g ON g.package_id=p.id WHERE p.id=p_package
$$;
REVOKE ALL ON FUNCTION ops.lc_calibration_digest(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_calibration_digest(uuid) TO marketops_app;

CREATE FUNCTION ops.prepare_lc_calibration(p_id uuid,p_proof text,p_body jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; v jsonb; digest text; now_at timestamptz:=clock_timestamp();
BEGIN
 g:=ops.consume_ad_control_invocation(p_proof,'LISTING_CALIBRATION_PREPARE',p_id,p_id);
 IF NOT ops.lc_calibration_actor_scope(g.actor_user_id,g.organization_id,p_body->>'scopeKind',
       (p_body->>'storeId')::uuid,'LISTING_CALIBRATION_PREPARE') THEN
  RAISE EXCEPTION 'calibration preparation scope denied' USING ERRCODE='MO092';
 END IF;
 IF jsonb_typeof(p_body->'values')<>'array' OR jsonb_array_length(p_body->'values')=0 THEN
  RAISE EXCEPTION 'calibration values required' USING ERRCODE='MO036';
 END IF;
 INSERT INTO core.lc_calibration_package(id,organization_id,package_code,package_version,scope_kind,platform_code,
   store_ref_id,purpose_code,status,published_by_user_id,published_at,evidence_reference,effective_from,effective_to)
 VALUES(p_id,g.organization_id,p_body->>'code',(p_body->>'version')::integer,p_body->>'scopeKind',p_body->>'platformCode',
   (p_body->>'storeId')::uuid,p_body->>'purposeCode','DRAFT',g.actor_user_id,now_at,p_body->>'evidenceReference',
   (p_body->>'effectiveFrom')::timestamptz,(p_body->>'effectiveTo')::timestamptz);
 FOR v IN SELECT value FROM jsonb_array_elements(p_body->'values') LOOP
  INSERT INTO core.lc_calibration_value(id,package_id,category_code,value_numeric,value_text,value_json,unit_code,
    scope_note,window_days,evidence_reference)
  VALUES(gen_random_uuid(),p_id,v->>'categoryCode',(v->>'numeric')::numeric,v->>'text',nullif(v->'json','null'::jsonb),
    v->>'unitCode',v->>'scopeNote',(v->>'windowDays')::integer,v->>'evidenceReference');
 END LOOP;
 INSERT INTO ops.lc_calibration_governance(package_id,replaces_package_id,rationale,impact,differences,
   drafted_by_user_id,drafted_at,draft_digest)
 VALUES(p_id,(p_body->>'replacesPackageId')::uuid,p_body->>'rationale',p_body->>'impact',p_body->>'differences',g.actor_user_id,now_at,repeat('0',64));
 digest:=ops.lc_calibration_digest(p_id);
 UPDATE ops.lc_calibration_governance SET draft_digest=digest WHERE package_id=p_id;
 INSERT INTO ops.lc_calibration_event VALUES(gen_random_uuid(),p_id,'DRAFTED',g.actor_user_id,digest,p_body->>'evidenceReference',now_at);
 RETURN p_id;
END $$;
REVOKE ALL ON FUNCTION ops.prepare_lc_calibration(uuid,text,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.prepare_lc_calibration(uuid,text,jsonb) TO marketops_app;

CREATE FUNCTION ops.validate_lc_calibration(p_id uuid,p_proof text,p_digest text,p_reference text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; p core.lc_calibration_package%ROWTYPE; v ops.lc_calibration_governance%ROWTYPE;
BEGIN
 SELECT * INTO p FROM core.lc_calibration_package WHERE id=p_id FOR UPDATE;
 SELECT * INTO v FROM ops.lc_calibration_governance WHERE package_id=p_id FOR UPDATE;
 IF NOT FOUND OR p.status<>'DRAFT' OR v.accepted_at IS NOT NULL THEN
  RAISE EXCEPTION 'calibration is not an unaccepted governed draft' USING ERRCODE='MO036';
 END IF;
 g:=ops.consume_ad_control_invocation(p_proof,'LISTING_CALIBRATION_VALIDATE',p_id,p_id);
 IF g.organization_id<>p.organization_id OR NOT ops.lc_calibration_actor_scope(g.actor_user_id,p.organization_id,
      p.scope_kind,p.store_ref_id,'LISTING_CALIBRATION_VALIDATE') THEN
  RAISE EXCEPTION 'calibration validation scope denied' USING ERRCODE='MO092';
 END IF;
 IF p_digest IS NULL OR p_reference IS NULL OR length(btrim(p_reference)) NOT BETWEEN 1 AND 512
    OR p_digest<>v.draft_digest OR p_digest<>ops.lc_calibration_digest(p_id)
    OR cardinality(ops.lc_calibration_combination_failures(p_id))<>0 THEN
  RAISE EXCEPTION 'exact complete calibration draft required' USING ERRCODE='MO036';
 END IF;
 UPDATE ops.lc_calibration_governance SET validated_by_user_id=g.actor_user_id,validated_at=clock_timestamp(),
   validation_reference=p_reference,validated_digest=p_digest WHERE package_id=p_id;
 INSERT INTO ops.lc_calibration_event VALUES(gen_random_uuid(),p_id,'VALIDATED',g.actor_user_id,p_digest,p_reference,clock_timestamp());
END $$;
REVOKE ALL ON FUNCTION ops.validate_lc_calibration(uuid,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.validate_lc_calibration(uuid,text,text,text) TO marketops_app;

CREATE FUNCTION ops.accept_lc_calibration(p_id uuid,p_proof text,p_digest text,p_reference text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; p core.lc_calibration_package%ROWTYPE; v ops.lc_calibration_governance%ROWTYPE;
BEGIN
 SELECT * INTO p FROM core.lc_calibration_package WHERE id=p_id FOR UPDATE;
 SELECT * INTO v FROM ops.lc_calibration_governance WHERE package_id=p_id FOR UPDATE;
 IF NOT FOUND OR p.status<>'DRAFT' OR v.validated_at IS NULL OR v.accepted_at IS NOT NULL THEN
  RAISE EXCEPTION 'calibration needs professional validation and a new exact acceptance' USING ERRCODE='MO036';
 END IF;
 g:=ops.consume_ad_control_invocation(p_proof,'LISTING_CALIBRATION_ACCEPT',p_id,p_id);
 IF g.organization_id<>p.organization_id OR NOT ops.lc_calibration_actor_scope(g.actor_user_id,p.organization_id,
      p.scope_kind,p.store_ref_id,'LISTING_CALIBRATION_ACCEPT')
    OR g.actor_user_id=v.drafted_by_user_id OR g.actor_user_id=v.validated_by_user_id THEN
  RAISE EXCEPTION 'independent current Owner acceptance required' USING ERRCODE='MO092';
 END IF;
 IF p_digest IS NULL OR p_reference IS NULL OR length(btrim(p_reference)) NOT BETWEEN 1 AND 512
    OR p_digest<>v.validated_digest OR p_digest<>ops.lc_calibration_digest(p_id)
    OR (p.effective_to IS NOT NULL AND p.effective_to<=clock_timestamp()) THEN
  RAISE EXCEPTION 'calibration validation is changed or expired' USING ERRCODE='MO036';
 END IF;
 UPDATE ops.lc_calibration_governance SET accepted_by_user_id=g.actor_user_id,accepted_at=clock_timestamp(),
   acceptance_reference=p_reference,accepted_digest=p_digest WHERE package_id=p_id;
 UPDATE core.lc_calibration_package SET published_by_user_id=g.actor_user_id,published_at=clock_timestamp() WHERE id=p_id;
 INSERT INTO ops.lc_calibration_event VALUES(gen_random_uuid(),p_id,'ACCEPTED',g.actor_user_id,p_digest,p_reference,clock_timestamp());
END $$;
REVOKE ALL ON FUNCTION ops.accept_lc_calibration(uuid,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.accept_lc_calibration(uuid,text,text,text) TO marketops_app;

CREATE FUNCTION ops.activate_lc_calibration(p_id uuid,p_proof text,p_digest text,p_reference text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; p core.lc_calibration_package%ROWTYPE;
 v ops.lc_calibration_governance%ROWTYPE; previous uuid; overlap_count integer; now_at timestamptz:=clock_timestamp();
BEGIN
 SELECT * INTO p FROM core.lc_calibration_package WHERE id=p_id FOR UPDATE;
 SELECT * INTO v FROM ops.lc_calibration_governance WHERE package_id=p_id FOR UPDATE;
 IF NOT FOUND OR p.status<>'DRAFT' OR v.accepted_at IS NULL THEN
  RAISE EXCEPTION 'exact accepted draft required for activation' USING ERRCODE='MO036';
 END IF;
 g:=ops.consume_ad_control_invocation(p_proof,'LISTING_CALIBRATION_ACTIVATE',p_id,p_id);
 IF g.organization_id<>p.organization_id OR NOT ops.lc_calibration_actor_scope(g.actor_user_id,p.organization_id,
      p.scope_kind,p.store_ref_id,'LISTING_CALIBRATION_ACCEPT') THEN
  RAISE EXCEPTION 'current calibration activation authority required' USING ERRCODE='MO092';
 END IF;
 IF p_digest IS NULL OR p_reference IS NULL OR length(btrim(p_reference)) NOT BETWEEN 1 AND 512
    OR p_digest<>v.accepted_digest OR p_digest<>ops.lc_calibration_digest(p_id)
    OR cardinality(ops.lc_calibration_combination_failures(p_id))<>0
    OR p.effective_from>now_at OR (p.effective_to IS NOT NULL AND p.effective_to<=now_at) THEN
  RAISE EXCEPTION 'accepted calibration is changed or outside its effective period' USING ERRCODE='MO036';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(p.organization_id::text||':'||p.scope_key||':'||p.purpose_code,0));
 SELECT count(*),min(id::text)::uuid INTO overlap_count,previous FROM core.lc_calibration_package
   WHERE organization_id=p.organization_id AND scope_key=p.scope_key AND purpose_code=p.purpose_code
     AND status='ACTIVE' AND tstzrange(effective_from,effective_to,'[)') && tstzrange(p.effective_from,p.effective_to,'[)');
 IF overlap_count>1 THEN
  RAISE EXCEPTION 'multiple calibration ranges conflict with this exact replacement' USING ERRCODE='MO036';
 END IF;
 IF previous IS DISTINCT FROM v.replaces_package_id THEN
  RAISE EXCEPTION 'current calibration replacement differs from exact acceptance' USING ERRCODE='MO036';
 END IF;
 IF previous IS NOT NULL THEN
  UPDATE core.lc_calibration_package SET status='RETIRED',retired_at=now_at WHERE id=previous;
  INSERT INTO ops.lc_calibration_event VALUES(gen_random_uuid(),previous,'RETIRED',g.actor_user_id,
    ops.lc_calibration_digest(previous),p_reference,now_at);
 END IF;
 UPDATE core.lc_calibration_package SET status='ACTIVE',activated_at=now_at WHERE id=p_id;
 INSERT INTO ops.lc_calibration_event VALUES(gen_random_uuid(),p_id,'ACTIVATED',g.actor_user_id,p_digest,p_reference,now_at);
END $$;
REVOKE ALL ON FUNCTION ops.activate_lc_calibration(uuid,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.activate_lc_calibration(uuid,text,text,text) TO marketops_app;

CREATE FUNCTION core.lc_resolve_calibration_for(
    p_org uuid, p_platform text, p_store uuid, p_at timestamptz, p_purpose text)
RETURNS TABLE (package_id uuid, package_version integer, resolution_state text)
LANGUAGE sql STABLE
SET search_path = pg_catalog, core, pg_temp
AS $$
    WITH candidates AS (
        SELECT p.id, p.package_version,
               CASE p.scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END AS precedence
          FROM core.lc_calibration_package p
          JOIN ops.lc_calibration_governance g ON g.package_id=p.id
         WHERE p.organization_id = p_org
           AND p.status = 'ACTIVE' AND p.purpose_code=p_purpose
           AND p.activated_at<=p_at AND g.accepted_at<=p_at
           AND g.accepted_digest=ops.lc_calibration_digest(p.id)
           AND p.effective_from <= p_at
           AND (p.effective_to IS NULL OR p.effective_to > p_at)
           AND (p.scope_kind = 'ORGANIZATION'
                OR (p.scope_kind = 'PLATFORM' AND p.platform_code = p_platform)
                OR (p.scope_kind = 'STORE' AND p.store_ref_id = p_store))),
    best AS (SELECT * FROM candidates WHERE precedence = (SELECT min(precedence) FROM candidates))
    SELECT CASE WHEN (SELECT count(*) FROM best) = 1 THEN (SELECT id FROM best) END,
           CASE WHEN (SELECT count(*) FROM best) = 1 THEN (SELECT package_version FROM best) END,
           CASE (SELECT count(*) FROM best)
               WHEN 0 THEN 'CALIBRATION_UNRESOLVED'
               WHEN 1 THEN 'RESOLVED'
               ELSE 'CALIBRATION_CONFLICTED' END
$$;
REVOKE ALL ON FUNCTION core.lc_resolve_calibration_for(uuid,text,uuid,timestamptz,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_resolve_calibration_for(uuid,text,uuid,timestamptz,text) TO marketops_app;
CREATE OR REPLACE FUNCTION core.lc_resolve_calibration(p_org uuid,p_platform text,p_store uuid,p_at timestamptz)
RETURNS TABLE(package_id uuid,package_version integer,resolution_state text)
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT * FROM core.lc_resolve_calibration_for(p_org,p_platform,p_store,p_at,'LISTING_CONVERSION')
$$;

-- The professional validates evidence, while the deterministic combination
-- checks prevent malformed or contradictory parameter sets from activating.
CREATE FUNCTION ops.lc_calibration_combination_failures(p_id uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE failures text[]:=core.lc_calibration_package_failures(p_id); v record;
 ordinary numeric; material numeric; node jsonb; codes text[]:='{}';
BEGIN
 FOR v IN SELECT * FROM core.lc_calibration_value WHERE package_id=p_id LOOP
  IF v.category_code IN ('MATERIAL_IMPROVEMENT_BOUND','NON_WORSENING_RETURN_BOUND',
       'ORDINARY_TRIGGER_CONTENT','MATERIAL_TRIGGER_CONTENT','ORDINARY_TRIGGER_EXPOSURE','MATERIAL_TRIGGER_EXPOSURE')
     AND (v.unit_code<>'RATIO' OR v.value_numeric<0 OR v.value_numeric>1) THEN
   failures:=array_append(failures,v.category_code||'_RATIO_INVALID');
  END IF;
  IF v.category_code='NON_WORSENING_PROFIT_BOUND' AND v.value_numeric<0 THEN
   failures:=array_append(failures,'NON_WORSENING_PROFIT_BOUND_INVALID');
  END IF;
  IF v.category_code='APPROVAL_VALIDITY' AND (v.value_numeric<=0 OR v.value_numeric<>trunc(v.value_numeric)
       OR v.unit_code NOT IN ('MINUTES','HOURS','DAYS')) THEN
   failures:=array_append(failures,'APPROVAL_VALIDITY_INVALID');
  END IF;
  IF v.category_code='CROSS_PERIOD_WINDOW' AND (v.value_numeric<0 OR v.value_numeric>3660
       OR v.value_numeric<>trunc(v.value_numeric) OR v.unit_code<>'DAYS') THEN
   failures:=array_append(failures,'CROSS_PERIOD_WINDOW_INVALID');
  END IF;
  IF v.category_code='STOP_RULE' AND jsonb_typeof(v.value_json)<>'object' THEN
   failures:=array_append(failures,'STOP_RULE_INVALID');
  END IF;
  IF v.category_code='FORMAL_NODES' THEN
   IF jsonb_typeof(v.value_json)<>'array' THEN
    failures:=array_append(failures,'FORMAL_NODES_INVALID');
   ELSE
    IF jsonb_array_length(v.value_json) NOT BETWEEN 1 AND 8 THEN failures:=array_append(failures,'FORMAL_NODE_COUNT_INVALID'); END IF;
    FOR node IN SELECT value FROM jsonb_array_elements(v.value_json) LOOP
     IF coalesce(node->>'nodeCode','') !~ '^[A-Z][A-Z0-9_]{1,62}$' OR (node->>'nodeCode')=ANY(codes)
       OR coalesce(node->>'maturityDays','') NOT IN ('7','14','30')
       OR length(btrim(coalesce(node->>'method','')))=0
       OR coalesce(node->>'threshold','') !~ '^[0-9]+([.][0-9]+)?$' THEN
      failures:=array_append(failures,'FORMAL_NODE_INVALID');
     END IF;
     codes:=array_append(codes,node->>'nodeCode');
    END LOOP;
   END IF;
  END IF;
 END LOOP;
 SELECT value_numeric INTO ordinary FROM core.lc_calibration_value WHERE package_id=p_id AND category_code='ORDINARY_TRIGGER_CONTENT';
 SELECT value_numeric INTO material FROM core.lc_calibration_value WHERE package_id=p_id AND category_code='MATERIAL_TRIGGER_CONTENT';
 IF ordinary>material THEN failures:=array_append(failures,'CONTENT_TRIGGER_ORDER_INVALID'); END IF;
 SELECT value_numeric INTO ordinary FROM core.lc_calibration_value WHERE package_id=p_id AND category_code='ORDINARY_TRIGGER_EXPOSURE';
 SELECT value_numeric INTO material FROM core.lc_calibration_value WHERE package_id=p_id AND category_code='MATERIAL_TRIGGER_EXPOSURE';
 IF ordinary>material THEN failures:=array_append(failures,'EXPOSURE_TRIGGER_ORDER_INVALID'); END IF;
 RETURN failures;
END $$;
REVOKE ALL ON FUNCTION ops.lc_calibration_combination_failures(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_calibration_combination_failures(uuid) TO marketops_app;
