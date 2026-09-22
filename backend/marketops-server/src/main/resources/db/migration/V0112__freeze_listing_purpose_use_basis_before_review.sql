-- Freeze the proposed nonformal use conditions in the original Action, not a second case system.
ALTER TABLE ops.lc_action ADD COLUMN purpose_basis jsonb;
ALTER TABLE ops.lc_action_review ADD COLUMN purpose_basis_digest text
 CHECK (purpose_basis_digest IS NULL OR purpose_basis_digest ~ '^[0-9a-f]{64}$');

CREATE FUNCTION ops.lc_purpose_basis_digest(p_purpose text,p_basis jsonb) RETURNS text
LANGUAGE sql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
 SELECT encode(sha256(convert_to(jsonb_build_array('LC_PURPOSE_BASIS_1',p_purpose,p_basis)::text,'UTF8')),'hex')
$$;
REVOKE ALL ON FUNCTION ops.lc_purpose_basis_digest(text,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_purpose_basis_digest(text,jsonb) TO marketops_app;

CREATE FUNCTION ops.guard_lc_purpose_use_basis() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE proposal ops.recommendation%ROWTYPE; purpose text; item jsonb; until_at timestamptz;
BEGIN
 IF TG_TABLE_NAME='recommendation' THEN
   IF TG_OP='UPDATE' AND NEW.proposed_parameters->'purposeBasisDigest' IS DISTINCT FROM OLD.proposed_parameters->'purposeBasisDigest' THEN
     RAISE EXCEPTION 'Proposed purpose evidence digest cannot change' USING ERRCODE='MO090';
   END IF;
   RETURN NEW;
 END IF;
 IF TG_OP='UPDATE' THEN
   IF NEW.purpose_basis IS DISTINCT FROM OLD.purpose_basis THEN
     RAISE EXCEPTION 'Prepared purpose evidence and use boundaries are immutable' USING ERRCODE='MO090';
   END IF;
   RETURN NEW;
 END IF;
 SELECT * INTO proposal FROM ops.recommendation WHERE id=NEW.recommendation_id;
 purpose:=proposal.proposed_parameters->>'purposeCode';
 IF purpose IN ('DESCRIPTION_CORRECTION','BOUNDED_EXPLORATION') AND NEW.purpose_basis IS NULL THEN
   RAISE EXCEPTION 'Nonformal purpose requires its explicit proposed use basis' USING ERRCODE='23514';
 END IF;
 IF NEW.purpose_basis IS NULL THEN RETURN NEW; END IF;
 IF purpose IS NULL OR jsonb_typeof(NEW.purpose_basis) IS DISTINCT FROM 'object'
    OR jsonb_typeof(NEW.purpose_basis->'evidenceReference') IS DISTINCT FROM 'string'
    OR length(btrim(NEW.purpose_basis->>'evidenceReference')) NOT BETWEEN 1 AND 512
    OR jsonb_typeof(NEW.purpose_basis->'useConditions') IS DISTINCT FROM 'array'
    OR jsonb_typeof(NEW.purpose_basis->'endConditions') IS DISTINCT FROM 'array'
    OR EXISTS(SELECT 1 FROM jsonb_object_keys(NEW.purpose_basis) k
         WHERE k NOT IN ('evidenceReference','useConditions','endConditions','useUntil'))
    OR proposal.proposed_parameters->>'purposeBasisDigest' IS DISTINCT FROM ops.lc_purpose_basis_digest(purpose,NEW.purpose_basis) THEN
   RAISE EXCEPTION 'Purpose basis must match the exact proposed evidence and boundaries' USING ERRCODE='23514';
 END IF;
 IF jsonb_array_length(NEW.purpose_basis->'useConditions')=0 OR jsonb_array_length(NEW.purpose_basis->'endConditions')=0 THEN
   RAISE EXCEPTION 'Purpose use and ending conditions must be explicit' USING ERRCODE='23514';
 END IF;
 FOR item IN SELECT value FROM jsonb_array_elements((NEW.purpose_basis->'useConditions')||(NEW.purpose_basis->'endConditions')) LOOP
   IF jsonb_typeof(item) IS DISTINCT FROM 'string' OR length(btrim(item#>>'{}')) NOT BETWEEN 1 AND 512 THEN
     RAISE EXCEPTION 'Purpose conditions must be nonempty statements' USING ERRCODE='23514';
   END IF;
 END LOOP;
 IF NEW.purpose_basis->>'useUntil' IS NOT NULL THEN
   until_at:=(NEW.purpose_basis->>'useUntil')::timestamptz;
   IF NOT isfinite(until_at) OR until_at<=NEW.created_at OR until_at<=statement_timestamp() THEN
     RAISE EXCEPTION 'Purpose use deadline must be finite and future at preparation' USING ERRCODE='23514';
   END IF;
 END IF;
 IF purpose='BOUNDED_EXPLORATION' AND until_at IS NULL THEN
   RAISE EXCEPTION 'Bounded exploration requires its explicit finite use deadline' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.guard_lc_purpose_use_basis() FROM PUBLIC;
CREATE TRIGGER lc_recommendation_purpose_use_basis BEFORE INSERT OR UPDATE ON ops.recommendation
 FOR EACH ROW WHEN (NEW.action_kind IN ('LISTING_DESCRIPTION_CHANGE','LISTING_PROMOTION_ACTION'))
 EXECUTE FUNCTION ops.guard_lc_purpose_use_basis();
CREATE TRIGGER lc_action_purpose_use_basis BEFORE INSERT OR UPDATE ON ops.lc_action
 FOR EACH ROW EXECUTE FUNCTION ops.guard_lc_purpose_use_basis();

-- Keep historical meaning digests exact when there was no declared purpose-use basis.
ALTER FUNCTION ops.lc_meaning_review_basis_digest(uuid) RENAME TO lc_meaning_review_basis_digest_v0107;
CREATE FUNCTION ops.lc_meaning_review_basis_digest(p_action uuid) RETURNS text
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT CASE WHEN a.purpose_basis IS NULL THEN ops.lc_meaning_review_basis_digest_v0107(p_action)
   ELSE encode(sha256(convert_to(jsonb_build_array('LC_MEANING_PURPOSE_BASIS_1',
      ops.lc_meaning_review_basis_digest_v0107(p_action),
      ops.lc_purpose_basis_digest(r.proposed_parameters->>'purposeCode',a.purpose_basis))::text,'UTF8')),'hex') END
 FROM ops.lc_action a JOIN ops.recommendation r ON r.id=a.recommendation_id WHERE a.id=p_action
$$;
REVOKE ALL ON FUNCTION ops.lc_meaning_review_basis_digest(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_meaning_review_basis_digest(uuid) TO marketops_app;

CREATE OR REPLACE FUNCTION ops.lc_review_attests_frozen_plan() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE plan ops.lc_evaluation_plan%ROWTYPE; action ops.lc_action%ROWTYPE; purpose text; expected_digest text;
BEGIN
 IF NEW.verdict<>'ATTESTED' THEN RETURN NEW; END IF;
 SELECT * INTO action FROM ops.lc_action WHERE id=NEW.action_id;
 SELECT r.proposed_parameters->>'purposeCode' INTO purpose FROM ops.recommendation r WHERE r.id=action.recommendation_id;
 IF purpose IN ('DESCRIPTION_CORRECTION','BOUNDED_EXPLORATION') THEN
   expected_digest:=ops.lc_purpose_basis_digest(purpose,action.purpose_basis);
   IF expected_digest IS NULL OR action.created_at>NEW.reviewed_at OR action.organization_id<>NEW.organization_id
      OR NEW.evaluation_plan_digest IS NOT NULL
      OR (NEW.purpose_basis_digest IS NOT NULL AND NEW.purpose_basis_digest<>expected_digest)
      OR (action.purpose_basis->>'useUntil')::timestamptz<=NEW.reviewed_at THEN
     RAISE EXCEPTION 'Review requires the exact current nonformal use basis' USING ERRCODE='MO092';
   END IF;
   NEW.purpose_basis_digest:=expected_digest;
   RETURN NEW;
 END IF;
 SELECT * INTO plan FROM ops.lc_evaluation_plan WHERE action_id=NEW.action_id;
 IF NOT FOUND OR plan.organization_id<>NEW.organization_id OR plan.frozen_at>NEW.reviewed_at
    OR (NEW.evaluation_plan_digest IS NOT NULL AND NEW.evaluation_plan_digest<>plan.plan_digest) THEN
   RAISE EXCEPTION 'review must attest the exact plan frozen before it' USING ERRCODE='MO092';
 END IF;
 NEW.evaluation_plan_digest:=plan.plan_digest;
 RETURN NEW;
END $$;

-- Approval retains the same independent review; its clock cannot outlive proposed use.
ALTER TABLE ops.lc_action_binding ADD COLUMN purpose_basis_digest text
 CHECK (purpose_basis_digest IS NULL OR purpose_basis_digest ~ '^[0-9a-f]{64}$');

CREATE OR REPLACE FUNCTION ops.lc_approval_binds_reviewed_plan() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE plan ops.lc_evaluation_plan%ROWTYPE; approval ops.approval_decision%ROWTYPE;
 action ops.lc_action%ROWTYPE; purpose text; expected_digest text; until_at timestamptz;
BEGIN
 SELECT * INTO STRICT action FROM ops.lc_action WHERE id=NEW.action_id;
 SELECT * INTO STRICT approval FROM ops.approval_decision WHERE id=NEW.approval_decision_id;
 SELECT r.proposed_parameters->>'purposeCode' INTO purpose FROM ops.recommendation r WHERE r.id=action.recommendation_id;
 expected_digest:=ops.lc_purpose_basis_digest(purpose,action.purpose_basis);
 until_at:=(action.purpose_basis->>'useUntil')::timestamptz;
 IF approval.decided_at>NEW.bound_at OR approval.decided_at<action.created_at
    OR action.organization_id<>NEW.organization_id
    OR (until_at IS NOT NULL AND (until_at<=statement_timestamp() OR NEW.expires_at>until_at))
    OR (NEW.purpose_basis_digest IS NOT NULL AND NEW.purpose_basis_digest IS DISTINCT FROM expected_digest) THEN
   RAISE EXCEPTION 'Approval must bind the exact current purpose within its use deadline' USING ERRCODE='MO092';
 END IF;
 NEW.purpose_basis_digest:=expected_digest;
 IF purpose IN ('DESCRIPTION_CORRECTION','BOUNDED_EXPLORATION') THEN
   IF expected_digest IS NULL OR NEW.evaluation_plan_digest IS NOT NULL
      OR NOT EXISTS(SELECT 1 FROM ops.lc_action_review r WHERE r.action_id=action.id
          AND r.verdict='ATTESTED' AND r.purpose_basis_digest=expected_digest
          AND r.evaluation_plan_digest IS NULL AND r.reviewed_at<=approval.decided_at
          AND r.attested_target_text_digest IS NOT DISTINCT FROM NEW.target_text_digest
          AND r.attested_current_text_digest IS NOT DISTINCT FROM NEW.current_text_digest
          AND r.attested_affected_set_digest=NEW.affected_set_digest) THEN
     RAISE EXCEPTION 'Approval requires the exact independently reviewed nonformal use basis' USING ERRCODE='MO092';
   END IF;
   RETURN NEW;
 END IF;
 SELECT * INTO plan FROM ops.lc_evaluation_plan WHERE action_id=NEW.action_id;
 IF NOT FOUND THEN
   RAISE EXCEPTION 'approval cannot acquire an evaluation plan after its decision' USING ERRCODE='MO092';
 END IF;
 IF plan.organization_id<>NEW.organization_id OR plan.frozen_at>approval.decided_at
    OR (NEW.evaluation_plan_digest IS NOT NULL AND NEW.evaluation_plan_digest<>plan.plan_digest)
    OR NOT EXISTS (SELECT 1 FROM ops.lc_action_review r WHERE r.action_id=NEW.action_id
        AND r.verdict='ATTESTED' AND r.evaluation_plan_digest=plan.plan_digest
        AND r.attested_target_text_digest IS NOT DISTINCT FROM NEW.target_text_digest
        AND r.attested_current_text_digest IS NOT DISTINCT FROM NEW.current_text_digest
        AND r.attested_affected_set_digest=NEW.affected_set_digest
        AND r.reviewed_at<=approval.decided_at) THEN
   RAISE EXCEPTION 'approval requires the exact pre-existing, independently reviewed plan' USING ERRCODE='MO092';
 END IF;
 NEW.evaluation_plan_digest:=plan.plan_digest;
 RETURN NEW;
END $$;

-- Retain the complete earlier guard and replace only its inapplicable formal-plan requirement.
ALTER FUNCTION ops.lc_binding_gaps(uuid) RENAME TO lc_binding_gaps_v0107;
CREATE FUNCTION ops.lc_binding_gaps(p_action uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE reasons text[]; action ops.lc_action%ROWTYPE; binding ops.lc_action_binding%ROWTYPE;
 purpose text; expected_digest text; nonformal boolean;
BEGIN
 reasons:=ops.lc_binding_gaps_v0107(p_action);
 SELECT * INTO action FROM ops.lc_action WHERE id=p_action;
 IF NOT FOUND THEN RETURN reasons; END IF;
 SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id=p_action;
 IF NOT FOUND THEN RETURN reasons; END IF;
 SELECT r.proposed_parameters->>'purposeCode' INTO purpose FROM ops.recommendation r WHERE r.id=action.recommendation_id;
 nonformal:=purpose IN ('DESCRIPTION_CORRECTION','BOUNDED_EXPLORATION');
 IF nonformal THEN reasons:=array_remove(reasons,'EVALUATION_PLAN_BINDING_MISSING_OR_CHANGED'); END IF;
 IF nonformal OR action.purpose_basis IS NOT NULL THEN
   expected_digest:=ops.lc_purpose_basis_digest(purpose,action.purpose_basis);
   IF expected_digest IS NULL OR binding.purpose_basis_digest IS DISTINCT FROM expected_digest
      OR (nonformal AND (binding.evaluation_plan_digest IS NOT NULL OR NOT EXISTS(
          SELECT 1 FROM ops.lc_action_review r JOIN ops.approval_decision d ON d.id=binding.approval_decision_id
           WHERE r.action_id=action.id AND r.verdict='ATTESTED' AND r.purpose_basis_digest=expected_digest
             AND r.evaluation_plan_digest IS NULL AND r.reviewed_at<=d.decided_at AND d.decided_at<=binding.bound_at))) THEN
     reasons:=array_append(reasons,'PURPOSE_USE_BASIS_MISSING_OR_CHANGED');
   END IF;
   IF (action.purpose_basis->>'useUntil')::timestamptz<=statement_timestamp() THEN
     reasons:=array_append(reasons,'PURPOSE_USE_EXPIRED');
   END IF;
   IF binding.expires_at>(action.purpose_basis->>'useUntil')::timestamptz THEN
     reasons:=array_append(reasons,'PURPOSE_USE_BINDING_OUTLIVES_USE');
   END IF;
 END IF;
 RETURN reasons;
END $$;
REVOKE ALL ON FUNCTION ops.lc_binding_gaps(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_binding_gaps(uuid) TO marketops_app;
