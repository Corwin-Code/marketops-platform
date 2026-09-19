-- Frozen root 012: one canonical preview/acquisition authority across policy versions.
-- No provider capability, grant, allowance value or platform write switch is enabled.
ALTER TABLE ops.lc_exposure_occupation ADD COLUMN demand_evidence jsonb;
COMMENT ON COLUMN ops.lc_exposure_occupation.demand_evidence IS
 'Exact acquisition projection; historical NULL is retained, never fabricated.';

-- Return all applicable constraints. Policy composition is checked by the consumer.
CREATE OR REPLACE FUNCTION ops.lc_allowances_for(p_org uuid,p_listing uuid,p_at timestamptz)
RETURNS SETOF ops.lc_exposure_allowance LANGUAGE sql STABLE
SET search_path=pg_catalog,ops,core,pg_temp AS $$
 SELECT a.* FROM ops.lc_exposure_allowance a
 JOIN core.platform_listing l ON l.id=p_listing AND l.organization_id=p_org
 WHERE a.organization_id=p_org AND a.status='ACTIVE'
   AND a.published_at<=p_at AND a.effective_from<=p_at AND (a.effective_to IS NULL OR a.effective_to>p_at)
   AND (a.scope_kind='ORGANIZATION' OR (a.scope_kind='PLATFORM' AND a.platform_code=l.platform_code)
        OR (a.scope_kind='STORE' AND a.store_ref_id=l.store_id))
$$;

CREATE FUNCTION ops.lc_allowance_projection(p_action uuid,p_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql STABLE
SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE
 action ops.lc_action%ROWTYPE; listing core.platform_listing%ROWTYPE;
 allowance ops.lc_exposure_allowance%ROWTYPE;
 resolved_package uuid; policy jsonb; reserves jsonb; required_axes jsonb; axis text;
 composition text; gaps text[]:='{}'; axes jsonb:='[]'; scoped_ids uuid[];
 occupied numeric; requested numeric; canonical numeric; reserve numeric; headroom numeric;
 variants uuid[]; members uuid[]; unresolved boolean;
BEGIN
 SELECT * INTO action FROM ops.lc_action WHERE id=p_action;
 IF NOT FOUND THEN RAISE EXCEPTION 'action does not exist' USING ERRCODE='MO090'; END IF;
 SELECT * INTO STRICT listing FROM core.platform_listing WHERE id=action.platform_listing_id;
 SELECT r.package_id INTO resolved_package FROM core.lc_resolve_calibration(
     action.organization_id,listing.platform_code,action.store_id,p_at) r WHERE resolution_state='RESOLVED';
 IF resolved_package IS NULL OR resolved_package IS DISTINCT FROM action.calibration_package_id THEN
   gaps:=array_append(gaps,'ALLOWANCE_POLICY_UNRESOLVED');
 ELSE
   SELECT value_json INTO policy FROM core.lc_calibration_value v
     WHERE v.package_id=resolved_package AND category_code='ALLOWANCE_AXES';
   SELECT value_json INTO reserves FROM core.lc_calibration_value v
     WHERE v.package_id=resolved_package AND category_code='ALLOWANCE_RESERVE';
 END IF;
 IF jsonb_typeof(policy)='array' THEN required_axes:=policy; composition:='SINGLE_SCOPE';
 ELSIF jsonb_typeof(policy)='object' THEN
   IF policy->>'scopeComposition'='ALL_APPLICABLE'
       AND NOT EXISTS(SELECT 1 FROM jsonb_object_keys(policy) k WHERE k NOT IN ('axes','scopeComposition')) THEN
     required_axes:=policy->'axes'; composition:='ALL_APPLICABLE';
   END IF;
 END IF;
 IF jsonb_typeof(required_axes) IS DISTINCT FROM 'array' THEN
   required_axes:='[]'; gaps:=array_append(gaps,'ALLOWANCE_AXES_UNRESOLVED');
 END IF;
 IF jsonb_array_length(required_axes)=0 OR jsonb_array_length(required_axes)>4
   OR EXISTS(SELECT 1 FROM jsonb_array_elements(required_axes) x WHERE jsonb_typeof(x)<>'string'
     OR x#>>'{}' NOT IN ('CONCURRENT_LISTINGS','AFFECTED_VARIANTS','REVENUE_EXPOSURE','CATEGORY_SHARE'))
   OR (SELECT count(*)<>count(DISTINCT x) FROM jsonb_array_elements(required_axes) x) THEN
   required_axes:='[]'; gaps:=array_append(gaps,'ALLOWANCE_AXES_UNRESOLVED');
 END IF;
 SELECT array_agg(DISTINCT member ORDER BY member) INTO variants FROM core.lc_affected_set s,
   unnest(s.platform_listing_variant_ids) member WHERE s.id=action.affected_set_id AND s.resolution_state='COMPLETE' AND s.identity_lineage IS NOT NULL;
 FOR axis IN SELECT jsonb_array_elements_text(required_axes) ORDER BY 1 LOOP
   IF coalesce(reserves->>axis,'') !~ '^[0-9]+([.][0-9]{1,4})?$'
      OR length(reserves->>axis)>19 THEN
     gaps:=array_append(gaps,axis||':RESERVE_UNRESOLVED'); CONTINUE;
   END IF;
   reserve:=(reserves->>axis)::numeric;
   SELECT array_agg(a.id ORDER BY a.scope_key,a.id) INTO scoped_ids
     FROM ops.lc_allowances_for(action.organization_id,action.platform_listing_id,p_at) a WHERE a.axis_code=axis;
   IF coalesce(cardinality(scoped_ids),0)=0 THEN
     gaps:=array_append(gaps,axis||':ALLOWANCE_MISSING'); CONTINUE;
   END IF;
   IF composition='SINGLE_SCOPE' AND cardinality(scoped_ids)<>1 THEN
     gaps:=array_append(gaps,axis||':SCOPE_COMPOSITION_UNRESOLVED'); CONTINUE;
   END IF;
   IF axis NOT IN ('CONCURRENT_LISTINGS','AFFECTED_VARIANTS') THEN
     gaps:=array_append(gaps,axis||':CANONICAL_DEMAND_UNRESOLVED'); CONTINUE;
   END IF;
   FOR allowance IN SELECT a.* FROM ops.lc_exposure_allowance a WHERE a.id=ANY(scoped_ids)
       ORDER BY a.scope_key,a.id LOOP
     -- Scope membership comes from Actions, never from the configuration row that acquired them.
     WITH outstanding AS (
       SELECT a.platform_listing_id,a.affected_set_id FROM ops.lc_exposure_occupation o
       JOIN ops.lc_action a ON a.id=o.action_id
       JOIN core.platform_listing l ON l.id=a.platform_listing_id
       WHERE o.organization_id=action.organization_id AND o.axis_code=axis AND o.state<>'RELEASED'
         AND (allowance.scope_kind='ORGANIZATION'
           OR (allowance.scope_kind='PLATFORM' AND allowance.platform_code=l.platform_code)
           OR (allowance.scope_kind='STORE' AND allowance.store_ref_id=a.store_id)))
     SELECT CASE WHEN axis='CONCURRENT_LISTINGS' THEN
         (SELECT array_agg(DISTINCT platform_listing_id) FROM outstanding)
       ELSE (SELECT array_agg(DISTINCT member) FROM outstanding o
         JOIN core.lc_affected_set s ON s.id=o.affected_set_id, unnest(s.platform_listing_variant_ids) member) END,
       EXISTS(SELECT 1 FROM outstanding o LEFT JOIN core.lc_affected_set s ON s.id=o.affected_set_id
         WHERE axis='AFFECTED_VARIANTS' AND (s.id IS NULL OR s.resolution_state<>'COMPLETE'
           OR s.identity_lineage IS NULL OR coalesce(cardinality(s.platform_listing_variant_ids),0)=0))
       INTO members,unresolved;
     occupied:=coalesce(cardinality(members),0); requested:=NULL; canonical:=NULL;
     IF axis='CONCURRENT_LISTINGS' AND allowance.unit_code='COUNT' THEN
       canonical:=1; requested:=CASE WHEN action.platform_listing_id=ANY(coalesce(members,'{}')) THEN 0 ELSE 1 END;
     ELSIF axis='AFFECTED_VARIANTS' AND allowance.unit_code='COUNT' AND cardinality(variants)>0 THEN
       canonical:=cardinality(variants);
       SELECT count(*) INTO requested FROM unnest(variants) member WHERE NOT(member=ANY(coalesce(members,'{}')));
     END IF;
     IF unresolved OR requested IS NULL THEN
       gaps:=array_append(gaps,axis||':CANONICAL_DEMAND_UNRESOLVED');
       requested:=NULL;
     END IF;
     IF allowance.reserve_value<reserve THEN
       gaps:=array_append(gaps,axis||':RESERVE_BELOW_ACCEPTED_POLICY');
     END IF;
     headroom:=allowance.limit_value-allowance.reserve_value-occupied;
     axes:=axes||jsonb_build_array(jsonb_build_object('allowanceId',allowance.id,'axisCode',axis,
       'scopeKind',allowance.scope_kind,'limitValue',allowance.limit_value,'reserveValue',allowance.reserve_value,
       'occupiedValue',occupied,'requestedValue',requested,'canonicalDemand',canonical,
       'headroom',headroom,'sufficient',requested IS NOT NULL AND requested<=headroom,
       'unitCode',allowance.unit_code,'scopeKey',allowance.scope_key,'allowanceVersion',allowance.allowance_version));
   END LOOP;
 END LOOP;
 RETURN jsonb_build_object('platformListingId',action.platform_listing_id,'actionId',action.id,
   'affectedSetId',action.affected_set_id,'affectedSetDigest',action.affected_set_digest,
   'calibrationPackageId',resolved_package,'policy',policy,'evaluatedAt',p_at,
   'axes',axes,'resolved',cardinality(gaps)=0,'gaps',to_jsonb(gaps));
END
$$;
REVOKE ALL ON FUNCTION ops.lc_allowance_projection(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_allowance_projection(uuid,timestamptz) TO marketops_app;

CREATE OR REPLACE FUNCTION ops.acquire_lc_launch_allowance(
    p_launch_id uuid, p_action uuid, p_actor uuid, p_proof text, p_requested jsonb)
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
    axis_row jsonb;
    short       text[] := '{}';
    occupations uuid[] := '{}';
    occupation_id uuid;
    command_id uuid;
    now_at      timestamptz := clock_timestamp();
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = p_action FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'action does not exist' USING ERRCODE = 'MO090'; END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id = p_action;
    IF NOT FOUND THEN RAISE EXCEPTION 'the action has no binding' USING ERRCODE = 'MO092'; END IF;
    grant_row := ops.consume_ad_control_invocation(p_proof, 'LISTING_ACTION_LAUNCH',
        action.recommendation_id, binding.approval_decision_id);
    IF grant_row.actor_user_id <> p_actor OR grant_row.organization_id <> action.organization_id THEN
        RAISE EXCEPTION 'the invocation proof belongs to another person' USING ERRCODE = 'MO092';
    END IF;
    IF NOT ops.lc_actor_holds_action(p_actor, action.organization_id, action.store_id, 'LISTING_ACTION_LAUNCH') THEN
        RAISE EXCEPTION 'the actor cannot launch a listing action here' USING ERRCODE = 'MO092';
    END IF;
    IF action.state NOT IN ('APPROVED', 'APPROVED_NOT_LAUNCHABLE') THEN
        RAISE EXCEPTION 'only an approved action is launched' USING ERRCODE = 'MO091';
    END IF;
    -- Stable authority spans manual/API, batches and every allowance configuration version.
    PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(action.organization_id::text));
    now_at:=clock_timestamp();
    -- Concurrent publication cannot change the evaluated constraints before this launch commits.
    -- SHARE allows other launch readers; only allowance configuration writes wait.
    LOCK TABLE ops.lc_exposure_allowance IN SHARE MODE;
    now_at:=clock_timestamp();
    gaps := ops.lc_binding_gaps(p_action);
    IF cardinality(gaps) > 0 THEN
        RAISE EXCEPTION 'the binding no longer applies: %', array_to_string(gaps, ',') USING ERRCODE = 'MO092';
    END IF;
    SELECT * INTO plan_row FROM ops.lc_evaluation_plan WHERE action_id = p_action;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'an evaluation plan is frozen before launch' USING ERRCODE = 'MO092';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mart.lc_listing_health h
                    WHERE h.platform_listing_id = action.platform_listing_id
                      AND h.health_version = (SELECT max(latest.health_version) FROM mart.lc_listing_health latest
                                              WHERE latest.platform_listing_id = action.platform_listing_id)
                      AND h.necessary_state = 'PASS') THEN
        RAISE EXCEPTION 'Listing Health necessary conditions are not passed' USING ERRCODE = 'MO092';
    END IF;
    IF ops.lc_scope_contained(action.organization_id, action.platform_listing_id) THEN
        RAISE EXCEPTION 'the scope is contained' USING ERRCODE = 'MO092';
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
        launched_at, proof_hash)
    VALUES (p_launch_id, action.organization_id, p_action, binding.id, plan_row.id, p_actor, now_at,
        grant_row.proof_hash);
    UPDATE ops.lc_action SET state = 'LAUNCHED', updated_at = now_at, version = version + 1
     WHERE id = p_action;
    IF action.execution_path='API' THEN
        command_id:=ops.create_lc_description_command(p_action,p_actor,action.version+1,'lc-launch:'||p_launch_id::text);
    END IF;
    RETURN jsonb_build_object('launched', true, 'launchId', p_launch_id,
        'occupationIds', to_jsonb(occupations),'commandId',command_id);
END;
$$;


-- Root 013: a caller cannot overwrite actual/Unknown exposure with a number.
CREATE OR REPLACE FUNCTION ops.observe_lc_occupation(p_occupation uuid,p_state text,p_occupied numeric)
RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 RAISE EXCEPTION 'occupation state and quantity require purpose-qualified canonical evidence' USING ERRCODE='MO092';
END
$$;
REVOKE ALL ON FUNCTION ops.observe_lc_occupation(uuid,text,numeric) FROM marketops_app;

ALTER TABLE ops.lc_exposure_occupation ADD COLUMN release_evidence_id uuid;
ALTER TABLE ops.lc_exposure_occupation ADD COLUMN release_evidence jsonb;

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
 grant_row:=ops.consume_ad_control_invocation(p_proof,'LISTING_OCCUPATION_RELEASE',p_occupation,p_occupation);
 IF grant_row.actor_user_id<>p_actor OR grant_row.organization_id<>action.organization_id
   OR NOT ops.lc_actor_holds_action(p_actor,action.organization_id,action.store_id,'LISTING_ACTION_LAUNCH') THEN
   RAISE EXCEPTION 'exact authenticated actor and occupation scope required' USING ERRCODE='MO092';
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
 UPDATE ops.lc_exposure_occupation SET state='RELEASED',released_at=clock_timestamp(),release_basis=p_basis,
   release_evidence_reference=p_evidence,released_by_user_id=p_actor,
   release_evidence_id=p_evidence_id,release_evidence=evidence WHERE id=p_occupation;
END
$$;
