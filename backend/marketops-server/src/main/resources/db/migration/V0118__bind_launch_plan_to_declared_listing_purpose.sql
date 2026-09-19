-- Root 007/019: use the existing declared purpose, review, binding and atomic launch authority.
-- No launch/command/provider switch is enabled; current protection and allowance checks remain.
ALTER TABLE ops.lc_launch ALTER COLUMN plan_id DROP NOT NULL;

ALTER TABLE ops.guardrail_evaluation ADD COLUMN listing_transaction_id bigint;
ALTER TABLE ops.lc_launch ADD COLUMN execution_guardrail_id uuid REFERENCES ops.guardrail_evaluation(id);

-- The database stamps new Listing evidence; historical evaluations remain unchanged.
CREATE FUNCTION ops.lc_stamp_guardrail_transaction() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ops.recommendation r WHERE r.id=NEW.recommendation_id
     AND r.organization_id=NEW.organization_id
     AND r.action_kind IN ('LISTING_DESCRIPTION_CHANGE','LISTING_PROMOTION_ACTION')) THEN
  NEW.listing_transaction_id:=txid_current();
 ELSE
  NEW.listing_transaction_id:=NULL;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_stamp_guardrail_transaction() FROM PUBLIC;
CREATE TRIGGER lc_guardrail_transaction BEFORE INSERT ON ops.guardrail_evaluation
 FOR EACH ROW EXECUTE FUNCTION ops.lc_stamp_guardrail_transaction();


CREATE FUNCTION ops.lc_launch_plan_matches_purpose() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE action ops.lc_action%ROWTYPE; purpose text;
BEGIN
 SELECT * INTO STRICT action FROM ops.lc_action WHERE id=NEW.action_id;
 SELECT r.proposed_parameters->>'purposeCode' INTO purpose FROM ops.recommendation r
  WHERE r.id=action.recommendation_id AND r.organization_id=action.organization_id;
 IF NEW.organization_id<>action.organization_id THEN
  RAISE EXCEPTION 'launch organization does not match its action' USING ERRCODE='MO092';
 END IF;
 IF purpose IN ('LISTING_CONVERSION','PROMOTION') THEN
  IF NOT EXISTS(SELECT 1 FROM ops.lc_evaluation_plan p WHERE p.id=NEW.plan_id AND p.action_id=action.id
      AND p.organization_id=action.organization_id AND p.frozen_at<=NEW.launched_at) THEN
   RAISE EXCEPTION 'formal launch requires its exact previously frozen plan' USING ERRCODE='MO092';
  END IF;
 ELSIF purpose IN ('DESCRIPTION_CORRECTION','BOUNDED_EXPLORATION') THEN
  IF NEW.plan_id IS NOT NULL OR ops.lc_purpose_basis_digest(purpose,action.purpose_basis) IS NULL
    OR (purpose='BOUNDED_EXPLORATION' AND action.execution_path<>'MANUAL') THEN
   RAISE EXCEPTION 'nonformal launch binds its declared use rather than a formal result plan' USING ERRCODE='MO092';
  END IF;
 ELSE
  RAISE EXCEPTION 'launch purpose is unresolved' USING ERRCODE='MO092';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_launch_plan_matches_purpose() FROM PUBLIC;
CREATE TRIGGER lc_launch_purpose_plan BEFORE INSERT OR UPDATE ON ops.lc_launch
 FOR EACH ROW EXECUTE FUNCTION ops.lc_launch_plan_matches_purpose();

-- Current Listing authority must not retain the statement-start time across lock waits.
CREATE OR REPLACE FUNCTION ops.lc_actor_holds_action(p_actor uuid, p_org uuid, p_store uuid, p_action text)
RETURNS boolean LANGUAGE sql VOLATILE SET search_path = pg_catalog, iam, core, pg_temp AS $$
 SELECT EXISTS (
   SELECT 1
     FROM iam.user_role_assignment r
     JOIN iam.business_role_action_scope m ON m.role_code = r.role_code
     JOIN iam.user_scope_grant s ON s.user_id = r.user_id AND s.action_code = m.action_code
     JOIN core.store st ON st.id = p_store
     JOIN core.marketplace_account account ON account.id = st.marketplace_account_id
    WHERE r.user_id = p_actor AND r.organization_id = p_org AND s.organization_id = p_org
      AND st.organization_id = p_org
      AND EXISTS (SELECT 1 FROM iam.user_account actor
                    JOIN iam.identity_provider provider ON provider.id = actor.identity_provider_id
                   WHERE actor.id = p_actor AND actor.organization_id = p_org
                     AND actor.status = 'ACTIVE' AND provider.status = 'ACTIVE')
      AND r.status = 'ACTIVE' AND m.action_code = p_action
      AND r.effective_from <= clock_timestamp()
      AND (r.effective_to IS NULL OR r.effective_to > clock_timestamp())
      AND s.status = 'ACTIVE' AND s.effective_from <= clock_timestamp()
      AND (s.effective_to IS NULL OR s.effective_to > clock_timestamp())
      AND (s.organization_ref_id = p_org OR s.store_ref_id = p_store
           OR s.marketplace_account_ref_id = account.id
           OR s.legal_entity_ref_id = account.legal_entity_id))
$$;

-- Java computes the execution guardrail before entering the atomic launch
-- function. Give that bounded phase the same owner-held configuration lock
-- without granting the application role direct table-lock authority.
CREATE FUNCTION ops.lock_lc_launch_evaluation(p_action uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE organization uuid;
BEGIN
 SELECT organization_id INTO STRICT organization FROM ops.lc_action WHERE id=p_action;
 PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(organization::text));
 LOCK TABLE ops.lc_exposure_allowance IN SHARE MODE;
END
$$;
REVOKE ALL ON FUNCTION ops.lock_lc_launch_evaluation(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lock_lc_launch_evaluation(uuid) TO marketops_app;

-- Retain the historical function body for evidence, but remove its application entry point.
REVOKE ALL ON FUNCTION ops.acquire_lc_launch_allowance(uuid,uuid,uuid,text,jsonb) FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.acquire_lc_launch_allowance(
    p_launch_id uuid, p_action uuid, p_actor uuid, p_proof text, p_requested jsonb, p_execution_evaluation uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, iam, pg_temp
AS $$
DECLARE
    action      ops.lc_action%ROWTYPE;
    binding     ops.lc_action_binding%ROWTYPE;
    plan_row    ops.lc_evaluation_plan%ROWTYPE;
    grant_row   iam.ad_invocation_grant%ROWTYPE;
    gaps        text[];
    projection jsonb;
    declared_purpose text;
    axis_row jsonb;
    short       text[] := '{}';
    occupations uuid[] := '{}';
    occupation_id uuid;
    command_id uuid;
    execution_evaluation uuid;
    now_at      timestamptz := clock_timestamp();
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = p_action FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'action does not exist' USING ERRCODE = 'MO090'; END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id = p_action;
    IF NOT FOUND THEN RAISE EXCEPTION 'the action has no binding' USING ERRCODE = 'MO092'; END IF;
    IF action.state NOT IN ('APPROVED', 'APPROVED_NOT_LAUNCHABLE') THEN
        RAISE EXCEPTION 'only an approved action is launched' USING ERRCODE = 'MO091';
    END IF;
    -- Stable authority spans manual/API, batches and every allowance configuration version.
    PERFORM ops.lock_lc_launch_evaluation(p_action);
    now_at:=clock_timestamp();
    -- Consume only after all allowance lock waits: proof and step-up expiry use wall time.
    grant_row := ops.consume_ad_control_invocation(p_proof, 'LISTING_ACTION_LAUNCH',
        action.recommendation_id, binding.approval_decision_id);
    IF grant_row.actor_user_id <> p_actor OR grant_row.organization_id <> action.organization_id THEN
        RAISE EXCEPTION 'the invocation proof belongs to another person' USING ERRCODE = 'MO092';
    END IF;
    IF NOT ops.lc_actor_holds_action(p_actor, action.organization_id, action.store_id, 'LISTING_ACTION_LAUNCH') THEN
        RAISE EXCEPTION 'the actor cannot launch a listing action here' USING ERRCODE = 'MO092';
    END IF;
    gaps := ops.lc_binding_gaps(p_action);
    IF cardinality(gaps) > 0 THEN
        RAISE EXCEPTION 'the binding no longer applies: %', array_to_string(gaps, ',') USING ERRCODE = 'MO092';
    END IF;
    SELECT r.proposed_parameters->>'purposeCode' INTO declared_purpose FROM ops.recommendation r
      WHERE r.id=action.recommendation_id AND r.organization_id=action.organization_id;
    SELECT * INTO plan_row FROM ops.lc_evaluation_plan WHERE action_id = p_action;
    IF declared_purpose IN ('LISTING_CONVERSION','PROMOTION') THEN
        IF plan_row.id IS NULL THEN
            RAISE EXCEPTION 'formal evaluation plan must be frozen before launch' USING ERRCODE='MO092';
        END IF;
    ELSIF declared_purpose IN ('DESCRIPTION_CORRECTION','BOUNDED_EXPLORATION') THEN
        IF plan_row.id IS NOT NULL OR ops.lc_purpose_basis_digest(declared_purpose,action.purpose_basis) IS NULL
           OR (declared_purpose='BOUNDED_EXPLORATION' AND action.execution_path<>'MANUAL') THEN
            RAISE EXCEPTION 'nonformal launch requires its reviewed use basis and execution path' USING ERRCODE='MO092';
        END IF;
    ELSE
        RAISE EXCEPTION 'launch purpose is unresolved' USING ERRCODE='MO092';
    END IF;
    IF declared_purpose<>'DESCRIPTION_CORRECTION' AND NOT EXISTS (SELECT 1 FROM mart.lc_listing_health h
                    WHERE h.platform_listing_id = action.platform_listing_id
                      AND h.health_version = (SELECT max(latest.health_version) FROM mart.lc_listing_health latest
                                              WHERE latest.platform_listing_id = action.platform_listing_id)
                      AND h.necessary_state = 'PASS') THEN
        RAISE EXCEPTION 'Listing Health necessary conditions are not passed' USING ERRCODE = 'MO092';
    END IF;
    IF ops.lc_scope_contained(action.organization_id, action.platform_listing_id) THEN
        RAISE EXCEPTION 'the scope is contained' USING ERRCODE = 'MO092';
    END IF;

    SELECT e.id INTO execution_evaluation FROM ops.guardrail_evaluation e
      WHERE e.id=p_execution_evaluation AND e.organization_id=action.organization_id AND e.recommendation_id=action.recommendation_id
        AND e.purpose='EXECUTION' AND e.outcome='PASS' AND cardinality(e.reason_codes)=0
        AND e.listing_transaction_id=txid_current()
        AND e.detail->>'actionId'=action.id::text
        AND e.detail->>'actionVersion'=action.version::text
        AND e.detail->>'actionState'=action.state
        AND e.detail->>'executionPath'=action.execution_path
        AND e.detail->>'affectedSetDigest'=action.affected_set_digest
        AND e.detail->>'purposeCode'=declared_purpose
        AND e.detail->>'targetTextDigest'=coalesce(action.target_text_digest,'null')
        AND e.lc_calibration_package_id=action.calibration_package_id
        AND e.lc_calibration_version=action.calibration_version
        AND e.detail->>'reviewAttested'='true'
        AND e.detail->>'materialityRecheck.state'='CURRENT'
        AND (declared_purpose IN ('LISTING_CONVERSION','PROMOTION')
          OR e.detail->>'purposeBasisDigest'=ops.lc_purpose_basis_digest(declared_purpose,action.purpose_basis))
        AND e.detail->>'calibrationPackageId'=action.calibration_package_id::text
        AND e.detail->>'calibrationVersion'=action.calibration_version::text
        AND (declared_purpose='DESCRIPTION_CORRECTION' OR (
          e.detail->>'protectionRecheck.financialInputState'='CANONICAL_INPUT_AVAILABLE'
          AND e.detail->>'protectionRecheck.currentProfitVerdict'='PASS'
          AND e.detail->>'protectionRecheck.currentReturnVerdict'='PASS'
          AND e.detail->>'protectionRecheck.unitProfitFloorVerdict'='PASS'
          AND e.detail->>'protectionRecheck.supplyVerdict'='PASS'
          AND (action.action_kind<>'LISTING_PROMOTION_ACTION'
            OR e.detail->>'protectionRecheck.selectedSimulationInputState'='QUALIFIED_CURRENT')))
        AND e.evaluated_at<=now_at
      ORDER BY e.evaluated_at DESC,e.id LIMIT 1;
    IF execution_evaluation IS NULL THEN
      RAISE EXCEPTION 'this transaction requires the exact current protected execution evaluation' USING ERRCODE='MO092';
    END IF;

    projection:=ops.lc_allowance_projection(p_action,now_at);
    SELECT coalesce(array_agg(value),'{}') INTO short FROM jsonb_array_elements_text(projection->'gaps');
    FOR axis_row IN SELECT value FROM jsonb_array_elements(projection->'axes') LOOP
      IF NOT (axis_row->>'sufficient')::boolean AND NOT EXISTS(
          SELECT 1 FROM unnest(short) gap WHERE gap LIKE (axis_row->>'axisCode')||':%') THEN
        short:=array_append(short,axis_row->>'axisCode');
      END IF;
    END LOOP;
    IF cardinality(short) > 0 THEN
        UPDATE ops.lc_action SET state = 'APPROVED_NOT_LAUNCHABLE', updated_at = now_at, version = version + 1
         WHERE id = p_action AND state = 'APPROVED';
        RETURN jsonb_build_object('launched', false, 'insufficientAxes', to_jsonb(short));
    END IF;

    -- One occupation per Action/axis; every applicable constraint is retained in its evidence.
    FOR axis_row IN SELECT DISTINCT ON (value->>'axisCode') value
        FROM jsonb_array_elements(projection->'axes') ORDER BY value->>'axisCode',value->>'scopeKey' LOOP
        occupation_id:=gen_random_uuid();
        INSERT INTO ops.lc_exposure_occupation(id,organization_id,allowance_id,action_id,axis_code,
          requested_value,occupied_value,state,acquired_at,demand_evidence)
        VALUES(occupation_id,action.organization_id,(axis_row->>'allowanceId')::uuid,p_action,axis_row->>'axisCode',
          (axis_row->>'canonicalDemand')::numeric,(axis_row->>'canonicalDemand')::numeric,'ACQUIRED',now_at,projection);
        occupations:=array_append(occupations,occupation_id);
    END LOOP;

    INSERT INTO ops.lc_launch (id, organization_id, action_id, binding_id, plan_id, launched_by_user_id,
        launched_at, proof_hash, execution_guardrail_id)
    VALUES (p_launch_id, action.organization_id, p_action, binding.id, plan_row.id, p_actor, now_at,
        grant_row.proof_hash, execution_evaluation);
    UPDATE ops.lc_action SET state = 'LAUNCHED', updated_at = now_at, version = version + 1
     WHERE id = p_action;
    IF action.execution_path='API' THEN
        command_id:=ops.create_lc_description_command(p_action,p_actor,action.version+1,'lc-launch:'||p_launch_id::text);
    END IF;
    RETURN jsonb_build_object('launched', true, 'launchId', p_launch_id,
        'occupationIds', to_jsonb(occupations),'commandId',command_id);
END;
$$;

REVOKE ALL ON FUNCTION ops.acquire_lc_launch_allowance(uuid,uuid,uuid,text,jsonb,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.acquire_lc_launch_allowance(uuid,uuid,uuid,text,jsonb,uuid) TO marketops_app;

-- Same invocation-expiry defect on the existing exact no-provider-call release path.
CREATE OR REPLACE FUNCTION ops.release_lc_occupation(
 p_occupation uuid,p_actor uuid,p_proof text,p_basis text,p_evidence_id uuid,p_evidence text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
DECLARE occupation ops.lc_exposure_occupation%ROWTYPE; action ops.lc_action%ROWTYPE;
 grant_row iam.ad_invocation_grant%ROWTYPE; command ops.lc_description_command%ROWTYPE; evidence jsonb;
BEGIN
 SELECT * INTO occupation FROM ops.lc_exposure_occupation WHERE id=p_occupation FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'occupation does not exist' USING ERRCODE='MO090'; END IF;
 SELECT * INTO STRICT action FROM ops.lc_action WHERE id=occupation.action_id;
 IF NOT ops.lc_actor_holds_action(p_actor,action.organization_id,action.store_id,'LISTING_ACTION_LAUNCH') THEN
   RAISE EXCEPTION 'occupation scope authority required' USING ERRCODE='MO092';
 END IF;
 IF occupation.state='RELEASED' THEN RAISE EXCEPTION 'occupation already released' USING ERRCODE='MO091'; END IF;
 IF p_evidence IS NULL OR length(btrim(p_evidence)) NOT BETWEEN 1 AND 512 THEN
   RAISE EXCEPTION 'release evidence reference required' USING ERRCODE='MO092';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(action.organization_id::text));
 -- This proof means no external mutation was admitted, not merely an old-value readback.
 -- It is safe for every axis because this exact Action formed no provider obligation.
 IF p_basis='NOT_APPLIED_PROVEN' AND action.execution_path='API' THEN
   SELECT * INTO command FROM ops.lc_description_command WHERE id=p_evidence_id AND action_id=action.id FOR UPDATE;
   IF FOUND AND command.state='TERMINATED_WITHOUT_PROVIDER_CALL'
     AND command.terminal_at>=occupation.acquired_at
     AND NOT EXISTS(SELECT 1 FROM ops.lc_promotion_engagement e WHERE e.action_id=action.id)
     AND NOT EXISTS(SELECT 1 FROM ops.lc_description_command_attempt a
       WHERE a.command_id=command.id AND a.purpose IN ('APPLY','RESTORE')) THEN
     evidence:=jsonb_build_object('purpose','NOT_APPLIED','actionId',action.id,'commandId',command.id,
       'axisCode',occupation.axis_code,'terminalAt',command.terminal_at,'mutationAttemptCount',0);
   END IF;
 END IF;
 -- Generic display/description observations, self-reported promotion state and MATCHED_PRIOR
 -- are deliberately not lifecycle evidence. Qualified STOP_NEW / historical-clearing follows
 -- the independently verified lifecycle, not these old inference routes.
 IF evidence IS NULL THEN
   RAISE EXCEPTION 'exact purpose-qualified release evidence is missing' USING ERRCODE='MO092';
 END IF;
 -- All occupation, organization and command lock waits precede proof consumption.
 grant_row:=ops.consume_ad_control_invocation(p_proof,'LISTING_OCCUPATION_RELEASE',p_occupation,p_occupation);
 IF grant_row.actor_user_id<>p_actor OR grant_row.organization_id<>action.organization_id
   OR NOT ops.lc_actor_holds_action(p_actor,action.organization_id,action.store_id,'LISTING_ACTION_LAUNCH') THEN
   RAISE EXCEPTION 'exact authenticated actor and occupation scope required' USING ERRCODE='MO092';
 END IF;
 UPDATE ops.lc_exposure_occupation SET state='RELEASED',released_at=clock_timestamp(),release_basis=p_basis,
   release_evidence_reference=p_evidence,released_by_user_id=p_actor,
   release_evidence_id=p_evidence_id,release_evidence=evidence WHERE id=p_occupation;
END
$$;
