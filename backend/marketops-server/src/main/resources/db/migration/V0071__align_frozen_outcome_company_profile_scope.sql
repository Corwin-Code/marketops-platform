-- Preserve the frozen baseline function and its existing ACL. Align its repeated
-- company-Profile scope checks with the exact applicability already enforced by
-- ad_outcome_input_profiles_are_canonical: platform and optional semantic store.
-- No historical row, policy, permission or production enablement changes.
CREATE OR REPLACE FUNCTION ops.ad_outcome_baseline_is_canonical(p_baseline uuid,p_at timestamptz) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,platform,mart,pg_temp AS $$
DECLARE b ops.ad_outcome_baseline%ROWTYPE; p core.ad_outcome_policy%ROWTYPE; a core.ad_affected_set%ROWTYPE;
 obj core.ad_native_object%ROWTYPE; st record; snap jsonb; item jsonb; units jsonb; expected_units jsonb;
 expected_critical jsonb; actual_critical jsonb; hours integer; policy_json jsonb; fresh core.ad_freshness_profile%ROWTYPE;
 direction_code text; original_cause text; original_semantic uuid; original_generation integer;
 purpose_code text; kind_code text; calc uuid; digest text; company numeric; total numeric;
BEGIN
 SELECT * INTO b FROM ops.ad_outcome_baseline WHERE id=p_baseline;
 IF NOT FOUND OR b.state<>'COMPLETE' OR cardinality(b.blocker_codes)<>0 OR b.prepared_at>p_at OR b.valid_until<=p_at
   OR NOT ops.ad_outcome_baseline_is_attested(b.id) THEN RETURN false; END IF;
 SELECT * INTO p FROM core.ad_outcome_policy WHERE id=b.outcome_policy_id AND organization_id=b.organization_id;
 SELECT * INTO a FROM core.ad_affected_set WHERE id=b.affected_set_id AND organization_id=b.organization_id;
 SELECT * INTO obj FROM core.ad_native_object WHERE id=b.ad_native_object_id AND organization_id=b.organization_id;
 IF p.id IS NULL OR a.id IS NULL OR obj.id IS NULL OR p.policy_version<>b.outcome_policy_version
   OR p.status NOT IN('ACTIVE','RETIRED') OR p.effective_from>b.prepared_at OR p.effective_from>p_at
   OR (p.effective_to IS NOT NULL AND (p.effective_to<=p_at OR b.valid_until>p.effective_to))
   OR a.resolution_state<>'COMPLETE' OR a.ad_native_object_id<>b.ad_native_object_id OR a.resolved_at>b.prepared_at
   OR a.affected_set_digest<>b.affected_set_digest OR NOT(a.product_variant_ids @> b.product_variant_ids AND a.product_variant_ids <@ b.product_variant_ids)
   OR NOT p.critical_unit_definition_complete
   OR num_nonnulls(p.material_profit_delta,p.material_profit_per_rub_delta,p.sales_preservation_tolerance_ratio,
      p.non_worsening_profit_band,p.non_worsening_per_rub_band,p.minimum_ad_spend_denominator,p.comparison_scale,
      p.comparison_rounding_mode,p.material_boundary_inclusive,p.negative_profit_terminal)<>10
   OR NOT(p.scope_kind='ORGANIZATION' OR (p.scope_kind='PLATFORM' AND p.platform_code=obj.platform_code)
      OR (p.scope_kind='STORE' AND p.store_ref_id=obj.store_id)) THEN RETURN false; END IF;
 IF EXISTS(SELECT 1 FROM core.ad_outcome_policy other WHERE other.organization_id=b.organization_id
   AND other.id<>p.id AND other.direction=p.direction AND other.status IN('ACTIVE','RETIRED')
   AND other.effective_from<=p_at AND (other.effective_to IS NULL OR other.effective_to>p_at)
   AND (other.scope_kind='ORGANIZATION' OR (other.scope_kind='PLATFORM' AND other.platform_code=obj.platform_code)
      OR (other.scope_kind='STORE' AND other.store_ref_id=obj.store_id))
   AND CASE other.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END
      <=CASE p.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END) THEN RETURN false; END IF;
 policy_json:=ops.ad_outcome_plan_snapshot(p.id);
 IF b.plan_snapshot IS DISTINCT FROM policy_json THEN RETURN false; END IF;
 IF b.candidate_id IS NOT NULL THEN
  SELECT k.calculation_id,k.policy_version_digest,c.direction,c.cause_code,c.semantic_profile_id,k.lineage_generation
    INTO calc,digest,direction_code,original_cause,original_semantic,original_generation
  FROM ops.ad_bid_candidate c JOIN mart.ad_case k ON k.id=c.case_id
  WHERE c.id=b.candidate_id AND c.organization_id=b.organization_id AND k.ad_native_object_id=b.ad_native_object_id
    AND k.affected_set_id=b.affected_set_id AND k.superseded_at IS NULL;
 ELSE
  SELECT k.calculation_id,k.policy_version_digest,m.intended_state->>'direction',k.cause_code,k.semantic_profile_id,k.lineage_generation
    INTO calc,digest,direction_code,original_cause,original_semantic,original_generation
  FROM ops.ad_manual_proposal m JOIN mart.ad_case k ON k.id=m.case_id
  WHERE m.id=b.manual_proposal_id AND m.organization_id=b.organization_id AND k.ad_native_object_id=b.ad_native_object_id
    AND k.affected_set_id=b.affected_set_id AND k.superseded_at IS NULL;
 END IF;
 IF calc IS DISTINCT FROM b.case_calculation_id OR digest IS DISTINCT FROM b.policy_version_digest
   OR direction_code IS DISTINCT FROM p.direction OR original_semantic IS DISTINCT FROM obj.semantic_profile_id
   OR original_generation IS DISTINCT FROM obj.lineage_generation THEN RETURN false; END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object('productVariantId',m.product_variant_id,'listingVariantId',m.platform_listing_variant_id,
    'storeId',listing.store_id,'ruleId',(SELECT r.id FROM core.ad_outcome_critical_unit_rule r
      WHERE r.organization_id=b.organization_id AND r.outcome_policy_id=p.id AND r.product_variant_id=m.product_variant_id
        AND (r.store_id IS NULL OR r.store_id=listing.store_id) ORDER BY r.store_id NULLS LAST LIMIT 1))
    ORDER BY m.product_variant_id,m.platform_listing_variant_id),'[]') INTO expected_units
 FROM core.listing_mapping m JOIN core.platform_listing_variant variant ON variant.id=m.platform_listing_variant_id
 JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id
 WHERE m.organization_id=b.organization_id AND m.product_variant_id=ANY(b.product_variant_ids)
   AND m.status IN('ACTIVE','ENDED') AND m.effective_from<=b.prepared_at AND (m.effective_to IS NULL OR m.effective_to>b.prepared_at);
 IF jsonb_array_length(expected_units)=0 OR EXISTS(SELECT 1 FROM unnest(b.product_variant_ids) product
      WHERE NOT EXISTS(SELECT 1 FROM jsonb_array_elements(expected_units) unit WHERE (unit->>'productVariantId')::uuid=product))
    OR (SELECT array_agg((unit->>'listingVariantId')::uuid ORDER BY unit->>'listingVariantId') FROM jsonb_array_elements(expected_units) unit)
      IS DISTINCT FROM (SELECT array_agg(listing ORDER BY listing::text) FROM unnest(b.listing_variant_ids) listing) THEN RETURN false; END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object('productVariantId',unit->'productVariantId','listingVariantId',unit->'listingVariantId','ruleId',unit->'ruleId')
   ORDER BY unit->>'productVariantId',unit->>'listingVariantId'),'[]') INTO expected_critical
 FROM jsonb_array_elements(expected_units) unit WHERE unit->>'ruleId' IS NOT NULL;
 SELECT coalesce(jsonb_agg(jsonb_build_object('productVariantId',u.product_variant_id,'listingVariantId',u.listing_variant_id,'ruleId',u.rule_id)
   ORDER BY u.product_variant_id,u.listing_variant_id),'[]') INTO actual_critical
 FROM ops.ad_outcome_critical_unit u WHERE u.outcome_baseline_id=b.id;
 IF expected_critical IS DISTINCT FROM actual_critical OR (SELECT count(*) FROM ops.ad_outcome_stage_baseline WHERE outcome_baseline_id=b.id)<>3 THEN RETURN false; END IF;
 FOR st IN SELECT * FROM ops.ad_outcome_stage_baseline WHERE outcome_baseline_id=b.id LOOP
  snap:=st.snapshot;
  hours:=CASE st.stage WHEN 'OPERATIONAL' THEN p.completed_sales_guard_hours WHEN 'RETAINED' THEN 720 ELSE greatest(720,p.settlement_window_hours) END;
  purpose_code:=CASE st.stage WHEN 'OPERATIONAL' THEN 'EARLY_COMPLETED_SALES_OUTCOME' WHEN 'RETAINED' THEN 'FINAL_RETAINED_SALES_OUTCOME' ELSE 'SETTLED_FINANCIAL_OUTCOME' END;
  kind_code:=CASE st.stage WHEN 'OPERATIONAL' THEN 'COMPANY_COMPLETED_SALE' WHEN 'RETAINED' THEN 'COMPANY_RETAINED_SALE' ELSE 'SETTLEMENT' END;
  IF st.window_hours<>hours OR snap->>'stage' IS DISTINCT FROM st.stage
    OR snap->'originalIdentity' IS DISTINCT FROM jsonb_build_object('semanticProfileId',original_semantic,'lineageGeneration',original_generation)
    OR (snap->>'from')::timestamptz IS DISTINCT FROM b.prepared_at-make_interval(hours=>hours)
    OR (snap->>'to')::timestamptz IS DISTINCT FROM b.prepared_at
    OR jsonb_typeof(snap->'units') IS DISTINCT FROM 'array' OR jsonb_typeof(snap->'evidenceIds') IS DISTINCT FROM 'array'
    OR jsonb_typeof(snap->'blockers') IS DISTINCT FROM 'array' OR jsonb_typeof(snap->'profit') IS DISTINCT FROM 'object'
    OR jsonb_typeof(snap->'companySales') IS DISTINCT FROM 'object' OR jsonb_typeof(snap->'officialSpend') IS DISTINCT FROM 'object'
    OR snap->>'confounderDigest' IS NULL OR original_cause IS NULL
    OR snap->>'originalCause' IS DISTINCT FROM original_cause THEN RETURN false; END IF;
  IF NOT ops.ad_outcome_input_profiles_are_canonical(snap,b.organization_id,b.ad_native_object_id,st.stage,
    direction_code,b.prepared_at,b.valid_until,p_at) THEN RETURN false; END IF;
  SELECT jsonb_agg(unit->'unit' ORDER BY unit#>>'{unit,productVariantId}',unit#>>'{unit,listingVariantId}') INTO units
   FROM jsonb_array_elements(snap->'units') unit;
  IF units IS DISTINCT FROM expected_units THEN RETURN false; END IF;
  SELECT * INTO fresh FROM core.ad_freshness_profile WHERE id=(snap#>>'{freshnessProfile,id}')::uuid
    AND organization_id=b.organization_id AND status IN('ACTIVE','RETIRED') AND effective_from<=b.prepared_at
    AND (effective_to IS NULL OR (effective_to>p_at AND b.valid_until<=effective_to))
    AND effective_from<=p_at AND decision_purpose=purpose_code AND evidence_kind=kind_code
    AND (scope_kind='ORGANIZATION' OR (scope_kind='PLATFORM' AND platform_code=obj.platform_code)
      OR (scope_kind='STORE' AND platform_code=obj.platform_code AND store_ref_id=obj.store_id)
      OR (scope_kind='SEMANTIC_PROFILE' AND platform_code=obj.platform_code AND semantic_profile_id=obj.semantic_profile_id
         AND (store_ref_id IS NULL OR store_ref_id=obj.store_id)));
  IF NOT FOUND OR ((snap->'freshnessProfile')-'effectiveTo') IS DISTINCT FROM (ops.ad_outcome_freshness_snapshot(fresh.id)-'effectiveTo')
    OR (snap#>>'{freshnessProfile,effectiveTo}')::timestamptz IS DISTINCT FROM fresh.effective_to THEN RETURN false; END IF;
  IF EXISTS(SELECT 1 FROM core.ad_freshness_profile other WHERE other.organization_id=b.organization_id
    AND other.id<>fresh.id AND other.evidence_kind=kind_code AND other.decision_purpose=purpose_code
    AND other.status IN('ACTIVE','RETIRED') AND other.effective_from<=p_at AND (other.effective_to IS NULL OR other.effective_to>p_at)
    AND (other.scope_kind='ORGANIZATION' OR (other.scope_kind='PLATFORM' AND other.platform_code=obj.platform_code)
      OR (other.scope_kind='STORE' AND other.platform_code=obj.platform_code AND other.store_ref_id=obj.store_id)
      OR (other.scope_kind='SEMANTIC_PROFILE' AND other.platform_code=obj.platform_code AND other.semantic_profile_id=obj.semantic_profile_id
         AND (other.store_ref_id IS NULL OR other.store_ref_id=obj.store_id)))
    AND CASE other.scope_kind WHEN 'SEMANTIC_PROFILE' THEN 0 WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END
      <=CASE fresh.scope_kind WHEN 'SEMANTIC_PROFILE' THEN 0 WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END) THEN RETURN false; END IF;
  total:=0;
  FOR item IN SELECT value FROM jsonb_array_elements(snap->'units') LOOP
   IF st.stage='OPERATIONAL' AND (item#>>'{sales,valueState}' IS DISTINCT FROM 'AVAILABLE'
      OR item#>>'{sales,evidenceState}' NOT IN('CANONICAL_CONFIRMED','OPERATIONAL')
      OR jsonb_typeof(item#>'{sales,value}') IS DISTINCT FROM 'number') THEN RETURN false; END IF;
   total:=total+(item#>>'{sales,value}')::numeric;
  END LOOP;
  IF st.stage='OPERATIONAL' AND (snap#>>'{companySales,valueState}' IS DISTINCT FROM 'AVAILABLE'
     OR snap#>>'{companySales,evidenceState}' NOT IN('CANONICAL_CONFIRMED','OPERATIONAL')
     OR (snap#>>'{companySales,value}')::numeric IS DISTINCT FROM total) THEN RETURN false; END IF;
  IF direction_code='OPTIMIZATION_INCREASE' AND st.stage='RETAINED' AND
    (snap#>>'{companySales,valueState}' IS DISTINCT FROM 'AVAILABLE'
     OR snap#>>'{profit,absoluteProfit,valueState}' IS DISTINCT FROM 'AVAILABLE'
     OR snap#>>'{profit,profitPerAdRub,valueState}' IS DISTINCT FROM 'AVAILABLE'
     OR snap#>>'{profit,absoluteProfit,evidenceState}' NOT IN('CANONICAL_CONFIRMED','OPERATIONAL')
     OR snap#>>'{profit,profitPerAdRub,evidenceState}' NOT IN('CANONICAL_CONFIRMED','OPERATIONAL')) THEN RETURN false; END IF;
 END LOOP;
 RETURN true;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR invalid_datetime_format THEN RETURN false;
END $$;
