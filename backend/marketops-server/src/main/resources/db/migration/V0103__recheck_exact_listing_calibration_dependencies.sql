-- Roots 011/017: preserve frozen authority, recheck only the actual rule dependencies.
-- No acceptance, grant, capability or platform write is created by this migration.
ALTER TABLE ops.lc_action ADD COLUMN calibration_dependencies jsonb;
COMMENT ON COLUMN ops.lc_action.calibration_dependencies IS
 'Exact rule dependency projection captured at preparation; historical NULL is unproven for cross-package continuation.';

CREATE FUNCTION core.lc_action_calibration_dependencies(p_package uuid,p_kind text) RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog SET timezone='UTC' SET datestyle='ISO, YMD' AS $$
 SELECT jsonb_build_object('model','LC_ACTION_RULE_DEPENDENCIES_1','purpose',p.purpose_code,
   'actionKind',p_kind,'values',
   (SELECT jsonb_object_agg(v.category_code,jsonb_build_object('numeric',v.value_numeric,'text',v.value_text,
      'json',v.value_json,'unit',v.unit_code,'windowDays',v.window_days,'evidenceReference',v.evidence_reference)
       ORDER BY v.category_code)
    FROM core.lc_calibration_value v WHERE v.package_id=p.id
      -- A versioned, closed consumer inventory: future categories cannot silently enlarge old dependencies.
      AND v.category_code IN ('MATERIAL_IMPROVEMENT_BOUND','NON_WORSENING_PROFIT_BOUND',
        'NON_WORSENING_RETURN_BOUND','CRITICAL_GROUP_RULE','DEMAND_SCENARIO_SET','FRESHNESS_RULE',
        'RESPONSIBILITY_SLO','RESPONSIBILITY_COVERAGE','ORDINARY_TRIGGER_CONTENT','MATERIAL_TRIGGER_CONTENT',
        'ORDINARY_TRIGGER_EXPOSURE','MATERIAL_TRIGGER_EXPOSURE','APPROVAL_VALIDITY',
        'REPRESENTATION_EQUIVALENCE_RULE','ALLOWANCE_AXES','ALLOWANCE_RESERVE','FORMAL_NODES','STOP_RULE',
        'CROSS_PERIOD_WINDOW','DESCRIPTION_LENGTH_RULE')
      -- Description actions never consume promotion demand scenarios. Promotion actions never
      -- serialize description text or use the description readback representation rule.
      AND NOT (p_kind='LISTING_DESCRIPTION_CHANGE' AND v.category_code='DEMAND_SCENARIO_SET')
      AND NOT (p_kind='LISTING_PROMOTION_ACTION' AND v.category_code IN
           ('DESCRIPTION_LENGTH_RULE','REPRESENTATION_EQUIVALENCE_RULE'))))
 FROM core.lc_calibration_package p WHERE p.id=p_package
  AND p_kind IN ('LISTING_DESCRIPTION_CHANGE','LISTING_PROMOTION_ACTION')
$$;
REVOKE ALL ON FUNCTION core.lc_action_calibration_dependencies(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_action_calibration_dependencies(uuid,text) TO marketops_app;

CREATE FUNCTION ops.capture_lc_action_calibration_dependencies() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='UPDATE' THEN
   IF NEW.calibration_dependencies IS DISTINCT FROM OLD.calibration_dependencies
     OR NEW.calibration_package_id IS DISTINCT FROM OLD.calibration_package_id
     OR NEW.calibration_version IS DISTINCT FROM OLD.calibration_version THEN
     RAISE EXCEPTION 'prepared rule dependencies and authority are immutable' USING ERRCODE='MO090';
   END IF;
 ELSE
   NEW.calibration_dependencies:=core.lc_action_calibration_dependencies(NEW.calibration_package_id,NEW.action_kind);
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.capture_lc_action_calibration_dependencies() FROM PUBLIC;
CREATE TRIGGER lc_action_captures_calibration_dependencies BEFORE INSERT OR UPDATE ON ops.lc_action
 FOR EACH ROW EXECUTE FUNCTION ops.capture_lc_action_calibration_dependencies();

CREATE FUNCTION ops.lc_action_calibration_recheck(p_action uuid,p_at timestamptz) RETURNS jsonb
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE action ops.lc_action; listing core.platform_listing; bound core.lc_calibration_package;
 current_id uuid; current_version integer; resolution text; projection jsonb; state text;
BEGIN
 SELECT * INTO action FROM ops.lc_action WHERE id=p_action;
 SELECT * INTO listing FROM core.platform_listing WHERE id=action.platform_listing_id;
 SELECT * INTO bound FROM core.lc_calibration_package WHERE id=action.calibration_package_id
  AND organization_id=action.organization_id AND package_version=action.calibration_version
  AND (scope_kind='ORGANIZATION' OR (scope_kind='PLATFORM' AND platform_code=listing.platform_code)
       OR (scope_kind='STORE' AND store_ref_id=action.store_id));
 IF bound.id IS NULL THEN RETURN jsonb_build_object('state','CALIBRATION_UNRESOLVED'); END IF;
 SELECT r.package_id,r.package_version,r.resolution_state INTO current_id,current_version,resolution
  FROM core.lc_resolve_calibration_for(action.organization_id,listing.platform_code,action.store_id,p_at,bound.purpose_code) r;
 state:='CALIBRATION_UNRESOLVED';
 IF resolution='RESOLVED' THEN
   projection:=core.lc_action_calibration_dependencies(current_id,action.action_kind);
   IF current_id=bound.id THEN state:='CURRENT';
   ELSIF action.calibration_dependencies IS NULL THEN state:='CALIBRATION_DEPENDENCIES_UNPROVEN';
   ELSIF NOT EXISTS(SELECT 1 FROM ops.lc_calibration_governance g WHERE g.package_id=bound.id
       AND g.accepted_at<=action.created_at AND g.accepted_digest=ops.lc_calibration_digest(bound.id))
       OR bound.activated_at IS NULL OR bound.activated_at>action.created_at
       OR (bound.retired_at IS NOT NULL AND bound.retired_at<=action.created_at)
       OR bound.effective_from>action.created_at OR (bound.effective_to IS NOT NULL AND bound.effective_to<=action.created_at)
       OR action.calibration_dependencies IS DISTINCT FROM core.lc_action_calibration_dependencies(bound.id,action.action_kind)
       THEN state:='CALIBRATION_DEPENDENCIES_UNPROVEN';
   ELSIF projection IS DISTINCT FROM action.calibration_dependencies THEN state:='CALIBRATION_DEPENDENCIES_CHANGED';
   ELSE state:='UNCHANGED_DEPENDENCIES'; END IF;
 END IF;
 RETURN jsonb_build_object('state',state,'purpose',bound.purpose_code,'boundPackageId',bound.id,
   'currentPackageId',current_id,'currentVersion',current_version,
   'dependencyDigest',CASE WHEN projection IS NULL THEN NULL ELSE encode(sha256(convert_to(projection::text,'UTF8')),'hex') END);
END $$;
REVOKE ALL ON FUNCTION ops.lc_action_calibration_recheck(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_action_calibration_recheck(uuid,timestamptz) TO marketops_app;

CREATE OR REPLACE FUNCTION ops.lc_binding_gaps(p_action uuid)
RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE
    action  ops.lc_action%ROWTYPE;
    binding ops.lc_action_binding%ROWTYPE;
    reasons text[] := '{}';
    latest_digest text;
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = p_action;
    IF NOT FOUND THEN RETURN ARRAY['ACTION_NOT_FOUND']; END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id = p_action;
    IF NOT FOUND THEN RETURN ARRAY['BINDING_MISSING']; END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_evaluation_plan p
        WHERE p.action_id=action.id AND p.organization_id=action.organization_id
          AND p.plan_digest=binding.evaluation_plan_digest AND p.frozen_at<=binding.bound_at) THEN
        reasons:=array_append(reasons,'EVALUATION_PLAN_BINDING_MISSING_OR_CHANGED');
    END IF;
    IF binding.state <> 'BOUND' THEN
        reasons := array_append(reasons, 'BINDING_INAPPLICABLE');
    END IF;
    IF binding.expires_at <= statement_timestamp() THEN
        reasons := array_append(reasons, 'BINDING_EXPIRED');
    END IF;
    IF core.lc_listing_affected_set_digest(action.platform_listing_id) <> binding.affected_set_digest THEN
        reasons := array_append(reasons, 'AFFECTED_SET_DIGEST_CHANGED');
    END IF;
    IF action.action_kind = 'LISTING_DESCRIPTION_CHANGE' THEN
        SELECT o.text_digest INTO latest_digest
          FROM core.lc_description_observation o
         WHERE o.platform_listing_id = action.platform_listing_id
         ORDER BY o.observed_at DESC, o.acquired_at DESC LIMIT 1;
        IF latest_digest IS DISTINCT FROM binding.current_text_digest THEN
            reasons := array_append(reasons, 'CURRENT_TEXT_MOVED');
        END IF;
    END IF;
    IF (ops.lc_action_calibration_recheck(action.id,statement_timestamp())->>'state')
         NOT IN ('CURRENT','UNCHANGED_DEPENDENCIES') THEN
        reasons := array_append(reasons, 'CALIBRATION_NOT_CURRENT');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.approval_decision a
                    WHERE a.id = binding.approval_decision_id
                      AND a.decision = 'APPROVED'
                      AND a.scope_expires_at > statement_timestamp()) THEN
        reasons := array_append(reasons, 'AUTHORIZATION_INVALID_OR_EXPIRED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.recommendation r
                    WHERE r.id = action.recommendation_id
                      AND r.state IN ('APPROVED', 'COMMAND_CREATED', 'EXECUTION_TRACKING')
                      AND r.valid_until > statement_timestamp()) THEN
        reasons := array_append(reasons, 'RECOMMENDATION_STALE');
    END IF;
    RETURN reasons;
END;
$$;

CREATE OR REPLACE FUNCTION ops.lc_allowance_projection(p_action uuid,p_at timestamptz)
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
 SELECT (r.value->>'currentPackageId')::uuid INTO resolved_package
   FROM (SELECT ops.lc_action_calibration_recheck(action.id,p_at) AS value) r
   WHERE r.value->>'state' IN ('CURRENT','UNCHANGED_DEPENDENCIES');
 IF resolved_package IS NULL THEN
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
