-- Economic danger uses complete canonical economics, independently of a missing
-- conversion rate. Its dependency exemption is narrower than a physical cause.
CREATE OR REPLACE FUNCTION ops.ad_required_action_evidence_kinds(p_basis text,p_cause text) RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
 SELECT CASE WHEN p_basis='MAX_CPC_BOUNDED' THEN ARRAY['OFFICIAL_AD_SPEND','OFFICIAL_AD_TRAFFIC',
   'AD_LINKED_SALE_EVENT','COST_AND_FEE','AD_OBJECT_CONFIGURATION','AFFECTED_SET']
 WHEN p_basis='CAUSE_BOUND_PROTECTION_STEP' AND p_cause='PROMOTED_VARIANT_NOT_SELLABLE'
 THEN ARRAY['OFFICIAL_AD_SPEND','AD_OBJECT_CONFIGURATION','AFFECTED_SET','SELLABILITY']
 WHEN p_basis='CAUSE_BOUND_PROTECTION_STEP' AND p_cause='PROMOTED_VARIANT_UNAVAILABLE'
 THEN ARRAY['OFFICIAL_AD_SPEND','AD_OBJECT_CONFIGURATION','AFFECTED_SET','AVAILABILITY']
 WHEN p_basis='CAUSE_BOUND_PROTECTION_STEP' AND p_cause='PROVEN_ADVERTISING_LOSS'
 THEN ARRAY['OFFICIAL_AD_SPEND','AD_OBJECT_CONFIGURATION','AFFECTED_SET','AD_LINKED_SALE_EVENT','COST_AND_FEE','SELLABILITY','AVAILABILITY'] END
$$;

CREATE OR REPLACE FUNCTION ops.ad_action_blockers(p_basis text,p_cause text,p_blockers text[]) RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
 SELECT CASE WHEN p_basis='CAUSE_BOUND_PROTECTION_STEP'
   AND ops.ad_required_action_evidence_kinds(p_basis,p_cause) IS NULL
   THEN coalesce(p_blockers,ARRAY['BLOCKER_PROJECTION_MISSING'])||ARRAY['CAUSE_BOUND_CAUSE_UNSUPPORTED']
 ELSE ARRAY(SELECT blocker FROM unnest(coalesce(p_blockers,ARRAY['BLOCKER_PROJECTION_MISSING'])) blocker
 WHERE NOT coalesce((p_basis='CAUSE_BOUND_PROTECTION_STEP' AND (
   p_cause='PROVEN_ADVERTISING_LOSS' AND blocker='AD_LINKED_CONVERSION_NOT_WRITE_GRADE'
   OR p_cause IN('PROMOTED_VARIANT_NOT_SELLABLE','PROMOTED_VARIANT_UNAVAILABLE')
   AND (blocker=ANY(ARRAY['AD_LINKED_QUANTITY_LINEAGE_UNAVAILABLE','MIXED_OR_UNRESOLVED_SALES_CURRENCY',
     'AD_LINKED_CONVERSION_NOT_WRITE_GRADE','PROVIDER_TO_CANONICAL_ATTRIBUTION_GAP_MATERIAL'])
   OR blocker~'^(LINE_ECONOMICS_OR_MAPPING_UNRESOLVED|LINE_COST_COMPONENT_UNAVAILABLE):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'))),false)) END
$$;

CREATE FUNCTION ops.ad_economic_cause_bound_failures(p_candidate uuid,p_at timestamptz) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,mart,pg_temp AS $$
DECLARE candidate ops.ad_bid_candidate%ROWTYPE; kase mart.ad_case%ROWTYPE;
 required text[]; reasons text[]:=ARRAY[]::text[];
BEGIN
 SELECT * INTO candidate FROM ops.ad_bid_candidate WHERE id=p_candidate;
 IF NOT FOUND THEN RETURN ARRAY['CANDIDATE_UNRESOLVED']; END IF;
 IF candidate.candidate_basis<>'CAUSE_BOUND_PROTECTION_STEP' OR candidate.cause_code<>'PROVEN_ADVERTISING_LOSS'
 THEN RETURN reasons; END IF;
 SELECT * INTO kase FROM mart.ad_case WHERE id=candidate.case_id AND organization_id=candidate.organization_id;
 IF NOT FOUND OR kase.superseded_at IS NOT NULL OR kase.lane<>'PROTECTION'
 OR kase.cause_code<>candidate.cause_code OR candidate.direction<>'PROTECTION_DECREASE'
 OR candidate.max_cpc_amount IS NOT NULL OR kase.max_cpc_state='AVAILABLE'
 OR kase.contribution_profit_state<>'AVAILABLE' OR kase.contribution_profit_amount IS NULL
 OR kase.contribution_profit_amount>=0 OR kase.official_spend_state<>'AVAILABLE'
 OR kase.official_spend_amount IS NULL OR kase.official_spend_amount<=0
 OR kase.current_bid_state<>'AVAILABLE' OR kase.current_bid_amount IS NULL
 OR kase.current_bid_amount<>candidate.current_bid_amount
 OR NOT EXISTS(SELECT 1 FROM core.ad_affected_set a WHERE a.id=kase.affected_set_id
   AND a.organization_id=candidate.organization_id AND a.resolution_state='COMPLETE'
   AND a.affected_set_digest=candidate.affected_set_digest AND cardinality(a.product_variant_ids)>0)
 THEN reasons:=array_append(reasons,'ECONOMIC_CAUSE_PROOF_UNRESOLVED'); END IF;
 reasons:=reasons||ops.ad_action_blockers(candidate.candidate_basis,candidate.cause_code,kase.blocker_codes);
 IF NOT EXISTS(SELECT 1 FROM core.ad_bid_target_policy p WHERE p.id=candidate.target_policy_id
   AND p.organization_id=candidate.organization_id AND p.policy_version=candidate.target_policy_version
   AND p.status='ACTIVE' AND p.effective_from<=p_at AND (p.effective_to IS NULL OR p.effective_to>p_at)
   AND p.direction=candidate.direction AND p.candidate_basis=candidate.candidate_basis
   AND p.cause_bound_step_enabled AND candidate.cause_code=ANY(p.cause_bound_causes))
 THEN reasons:=array_append(reasons,'ECONOMIC_CAUSE_TARGET_POLICY_NOT_ACCEPTED'); END IF;
 required:=ops.ad_required_action_evidence_kinds(candidate.candidate_basis,candidate.cause_code);
 IF (SELECT count(*) FROM mart.ad_case_purpose_evidence e
   JOIN core.ad_freshness_profile f ON f.id=e.freshness_profile_id
   WHERE e.case_id=kase.id AND e.calculation_id=kase.calculation_id
     AND e.evidence_kind=ANY(required) AND e.decision_purpose='PROTECTION_BID_WRITE'
     AND e.eligible AND e.expires_at>p_at
     AND f.organization_id=candidate.organization_id AND f.evidence_kind=e.evidence_kind
     AND f.decision_purpose=e.decision_purpose
     AND f.status='ACTIVE' AND f.effective_from<=p_at AND (f.effective_to IS NULL OR f.effective_to>p_at))
   <>cardinality(required)
 THEN reasons:=array_append(reasons,'ECONOMIC_CAUSE_PURPOSE_EVIDENCE_UNRESOLVED'); END IF;
 RETURN reasons;
END $$;
REVOKE ALL ON FUNCTION ops.ad_economic_cause_bound_failures(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_economic_cause_bound_failures(uuid,timestamptz) TO marketops_app;

-- Preserve all existing approval, identity, exception, safety and Gate checks.
-- These wrappers add the same cause proof at each existing authority boundary.
ALTER FUNCTION ops.seal_ad_action_authorization(uuid,uuid,uuid,text) RENAME TO seal_ad_action_authorization_before_economic_cause;
REVOKE ALL ON FUNCTION ops.seal_ad_action_authorization_before_economic_cause(uuid,uuid,uuid,text) FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.seal_ad_action_authorization(p_recommendation uuid,p_approval uuid,p_baseline uuid,p_proof text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE candidate uuid;
BEGIN
 SELECT candidate_id INTO candidate FROM ops.ad_candidate_selection WHERE recommendation_id=p_recommendation;
 IF candidate IS NOT NULL AND cardinality(ops.ad_economic_cause_bound_failures(candidate,clock_timestamp()))>0 THEN
   RAISE EXCEPTION 'economic cause-specific action proof remains unresolved' USING ERRCODE='MO092'; END IF;
 RETURN ops.seal_ad_action_authorization_before_economic_cause(p_recommendation,p_approval,p_baseline,p_proof);
END $$;
REVOKE ALL ON FUNCTION ops.seal_ad_action_authorization(uuid,uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.seal_ad_action_authorization(uuid,uuid,uuid,text) TO marketops_app;

ALTER FUNCTION ops.create_ad_bid_command(uuid,bigint,uuid,text) RENAME TO create_ad_bid_command_before_economic_cause;
REVOKE ALL ON FUNCTION ops.create_ad_bid_command_before_economic_cause(uuid,bigint,uuid,text) FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.create_ad_bid_command(p_recommendation uuid,p_version bigint,p_reservation uuid,p_correlation text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE candidate uuid;
BEGIN
 SELECT candidate_id INTO candidate FROM ops.ad_action_authorization WHERE recommendation_id=p_recommendation;
 IF candidate IS NOT NULL AND cardinality(ops.ad_economic_cause_bound_failures(candidate,clock_timestamp()))>0 THEN
   RAISE EXCEPTION 'economic cause-specific action proof remains unresolved' USING ERRCODE='MO092'; END IF;
 RETURN ops.create_ad_bid_command_before_economic_cause(p_recommendation,p_version,p_reservation,p_correlation);
END $$;
REVOKE ALL ON FUNCTION ops.create_ad_bid_command(uuid,bigint,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.create_ad_bid_command(uuid,bigint,uuid,text) TO marketops_app;

ALTER FUNCTION ops.evaluate_ad_bid_write_gate(uuid) RENAME TO evaluate_ad_bid_write_gate_before_economic_cause;
REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_write_gate_before_economic_cause(uuid) FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.evaluate_ad_bid_write_gate(p_command uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE candidate uuid; reasons text[];
BEGIN
 reasons:=ops.evaluate_ad_bid_write_gate_before_economic_cause(p_command);
 SELECT candidate_id INTO candidate FROM ops.ad_bid_command WHERE id=p_command;
 IF NOT FOUND THEN RETURN reasons; END IF;
 RETURN reasons||ops.ad_economic_cause_bound_failures(candidate,statement_timestamp());
END $$;
REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_write_gate(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.evaluate_ad_bid_write_gate(uuid) TO marketops_app;
