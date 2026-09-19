-- Frozen root 015: a new promotion Action must bind one already-qualified
-- conditional calculation and the same current, complete promotion context.
-- Historical rows retain their original evidence state and can still be cancelled.
ALTER TABLE ops.lc_simulation DROP CONSTRAINT lc_simulation_basis_ck;
ALTER TABLE ops.lc_simulation ADD CONSTRAINT lc_simulation_basis_ck CHECK ((
 (model_version='LEGACY_UNQUALIFIED' AND calculation_run_id IS NOT NULL AND input_snapshot IS NULL
    AND conditional_scenarios_passed IS NULL)
 OR (model_version='LC_CONDITIONAL_PROFIT_2' AND calculation_run_id IS NULL
    AND demand_gate_passed IS NULL AND input_snapshot IS NOT NULL
    AND jsonb_typeof(input_snapshot)='object'
    AND octet_length(input_snapshot::text)<=524288
    AND inputs_digest=encode(sha256(convert_to(input_snapshot::text,'UTF8')),'hex')
    AND input_snapshot->>'sourceKind' IN
      ('CALLER_ASSUMPTIONS','DECLARED_CONDITIONAL_INPUTS','QUALIFIED_MATCHED_INPUTS')
    AND input_snapshot->>'qualificationState' IN ('UNQUALIFIED','QUALIFIED_CONDITIONAL_ECONOMICS')
    AND (input_snapshot->>'sourceKind'='QUALIFIED_MATCHED_INPUTS')=
      (input_snapshot->>'qualificationState'='QUALIFIED_CONDITIONAL_ECONOMICS')
    AND input_snapshot->>'modelVersion'=model_version
    AND input_snapshot->>'candidateId'=candidate_id::text
    AND input_snapshot->>'organizationId'=organization_id::text
    AND jsonb_typeof(input_snapshot->'inputs')='object'
    AND jsonb_typeof(input_snapshot->'context')='object'
    AND jsonb_typeof(input_snapshot->'scenarios')='array'
    AND (input_snapshot->>'qualificationState'<>'QUALIFIED_CONDITIONAL_ECONOMICS' OR (
      conditional_scenarios_passed IS TRUE
      AND cardinality(evidence_product_variant_ids)>0
      AND input_snapshot->>'purposeCode' IN ('PROMOTION','BOUNDED_EXPLORATION')
      AND input_snapshot->>'nativeUniverseQualification'='COMPLETE_IDENTITY_SOURCE'
      AND input_snapshot#>>'{knownPromotionContext,coverage}'='QUALIFIED_COMPLETE'
      AND input_snapshot#>>'{knownPromotionContext,digest}' ~ '^[0-9a-f]{64}$'
      AND input_snapshot#>>'{variableFeeEvidence,state}'='VARIABLE_FEES_MATCH_PROFILE'
      AND input_snapshot#>>'{fixedFeeEvidence,state}'='ACTIVITY_FIXED_FEE_MATCH'
      AND input_snapshot#>>'{revenueEvidence,state}'='COMMERCIAL_REVENUE_MATCH'
      AND input_snapshot#>>'{currentCostEvidence,state}'='PERIOD_MEMBER_COST_BOUND'
      AND input_snapshot#>>'{demandEvidence,state}'='ACCEPTED_NECESSARY_SCENARIOS_MATCH'
      AND input_snapshot#>>'{profitReferenceEvidence,state}'='ACCEPTED_PROFIT_REFERENCE_BOUND'
      AND input_snapshot#>>'{demandEvidence,packageId}'=input_snapshot#>>'{profitReferenceEvidence,packageId}'
      AND input_snapshot#>>'{demandEvidence,packageVersion}'=input_snapshot#>>'{profitReferenceEvidence,packageVersion}'))
 )) IS TRUE
);

CREATE OR REPLACE FUNCTION ops.lc_action_binds_selected_simulation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE parameters jsonb;
BEGIN
 SELECT proposed_parameters INTO parameters FROM ops.recommendation
  WHERE id=NEW.recommendation_id AND organization_id=NEW.organization_id;

 IF TG_OP='INSERT' AND NEW.action_kind='LISTING_PROMOTION_ACTION'
    AND (coalesce(parameters->>'purposeCode','') NOT IN ('PROMOTION','BOUNDED_EXPLORATION')
      OR NOT COALESCE(parameters ? 'simulationId' AND parameters ? 'simulationInputsDigest',false)) THEN
  RAISE EXCEPTION 'a new promotion action requires one exact qualified simulation' USING ERRCODE='MO092';
 END IF;
 IF NEW.action_kind<>'LISTING_PROMOTION_ACTION'
    AND (parameters ? 'simulationId' OR parameters ? 'simulationInputsDigest') THEN
  RAISE EXCEPTION 'only a promotion action may bind promotion simulation evidence' USING ERRCODE='MO092';
 END IF;

 IF parameters ? 'simulationId' OR parameters ? 'simulationInputsDigest' THEN
  IF NOT (parameters ? 'simulationId' AND parameters ? 'simulationInputsDigest') OR NOT EXISTS(
   SELECT 1 FROM ops.lc_simulation s
   CROSS JOIN LATERAL (SELECT ops.lc_current_promotion_context(NEW.organization_id,NEW.platform_listing_id,
     (s.input_snapshot#>>'{context,periodStart}')::timestamptz,
     (s.input_snapshot#>>'{context,periodEnd}')::timestamptz,NEW.created_at) AS value) current_context
   WHERE s.id::text=parameters->>'simulationId'
    AND s.inputs_digest=parameters->>'simulationInputsDigest'
    AND s.organization_id=NEW.organization_id AND s.candidate_id=NEW.candidate_id
    AND s.computed_at<=NEW.created_at
    AND s.input_snapshot->>'nativeIdentityDigest'=NEW.affected_set_digest
    AND s.input_snapshot->>'promotionTermsDigest'=ops.lc_promotion_terms_digest(NEW.promotion_terms)
    AND s.input_snapshot->>'promotionTermsDigest'=ops.lc_promotion_terms_digest(s.input_snapshot->'context'->'commercialDeclaration')
    AND s.input_snapshot->>'qualificationState'='QUALIFIED_CONDITIONAL_ECONOMICS'
    AND s.input_snapshot->>'sourceKind'='QUALIFIED_MATCHED_INPUTS'
    AND s.input_snapshot->>'purposeCode'=parameters->>'purposeCode'
    AND s.input_snapshot#>>'{demandEvidence,packageId}'=NEW.calibration_package_id::text
    AND s.input_snapshot#>>'{demandEvidence,packageVersion}'=NEW.calibration_version::text
    AND s.input_snapshot#>>'{profitReferenceEvidence,packageId}'=NEW.calibration_package_id::text
    AND s.input_snapshot#>>'{profitReferenceEvidence,packageVersion}'=NEW.calibration_version::text
    AND s.input_snapshot#>>'{knownPromotionContext,coverage}'='QUALIFIED_COMPLETE'
    -- Review, approval and launch still require the prepared context to be current. A terminal
    -- move must retain every exact frozen binding but cannot be blocked by facts created by execution.
    AND (NEW.state IN ('VERIFIED','CLOSED','CANCELLED','CONTAINED') OR (
      current_context.value->>'coverage'='QUALIFIED_COMPLETE'
      AND s.input_snapshot#>>'{knownPromotionContext,digest}'=current_context.value->>'digest'))
    AND s.inputs_digest=encode(sha256(convert_to(s.input_snapshot::text,'UTF8')),'hex')) THEN
   RAISE EXCEPTION 'selected simulation must bind qualified exact scope, terms, period, context and immutable inputs'
     USING ERRCODE='MO092';
  END IF;
 END IF;
 RETURN NEW;
END $$;
