-- CV-A/C: every frozen Outcome input uses the existing scoped Freshness authority.
-- Historical baselines/observations are preserved verbatim. Missing new proof
-- cannot admit a new action or support a new confirmed claim; no data is backfilled.
ALTER TABLE core.ad_freshness_profile DROP CONSTRAINT ad_freshness_profile_evidence_kind_ck;
ALTER TABLE core.ad_freshness_profile ADD CONSTRAINT ad_freshness_profile_evidence_kind_ck
 CHECK (evidence_kind IN (
  'AD_OBJECT_CONFIGURATION','OFFICIAL_AD_SPEND','OFFICIAL_AD_TRAFFIC',
  'PROVIDER_ATTRIBUTION','AD_LINKED_SALE_EVENT','COMPANY_ORDER',
  'COMPANY_COMPLETED_SALE','COMPANY_RETAINED_SALE','SETTLEMENT',
  'COST_AND_FEE','PRODUCT_MAPPING','AFFECTED_SET','SELLABILITY','AVAILABILITY',
  'CAPABILITY_EVIDENCE','PRICE_AND_PROMOTION'));

-- This additional digest binds authority scope as well as the existing typed
-- version/bounds. Epoch serialization does not depend on session timezone.
CREATE OR REPLACE FUNCTION ops.ad_outcome_freshness_snapshot(p_profile uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog,core,pg_temp AS $$
 SELECT jsonb_build_object('id',f.id,'version',f.profile_version,'evidenceKind',f.evidence_kind,'decisionPurpose',f.decision_purpose,
  'sourceMaxAgeMinutes',f.source_max_age_minutes,'acceptedFactMaxAgeMinutes',f.accepted_fact_max_age_minutes,
  'expectedPublicationLagMinutes',f.expected_publication_lag_minutes,'correctionWindowMinutes',f.correction_window_minutes,
  'requiresWindowComplete',f.requires_window_complete,'requiresCorrectionWindowClosed',f.requires_correction_window_closed,
  'minimumCoverageRatio',f.minimum_coverage_ratio,'minimumConfidenceState',f.minimum_confidence_state,
  'providerIncidentBlocks',f.provider_incident_blocks,'effectiveTo',f.effective_to,
  'authorityDigest',encode(sha256(convert_to(jsonb_build_object(
    'organizationId',f.organization_id,'scopeKind',f.scope_kind,'platformCode',f.platform_code,
    'storeRefId',f.store_ref_id,'semanticProfileId',f.semantic_profile_id,
    'effectiveFromEpoch',extract(epoch FROM f.effective_from))::text,'UTF8')),'hex'))
 FROM core.ad_freshness_profile f WHERE f.id=p_profile
$$;

-- Shared exact-version validity for both frozen observations and plan seals.
-- Deliberately does not resolve a newer Profile: a new version cannot replace
-- the one selected and attested before the action.
CREATE FUNCTION ops.ad_outcome_frozen_profile_is_valid(p_snapshot jsonb,p_organization uuid,p_object uuid,
 p_kind text,p_purpose text,p_at timestamptz) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE f core.ad_freshness_profile%ROWTYPE; o core.ad_native_object%ROWTYPE; exact jsonb;
BEGIN
 IF p_at IS NULL OR jsonb_typeof(p_snapshot) IS DISTINCT FROM 'object'
   OR p_snapshot->>'evidenceKind' IS DISTINCT FROM p_kind
   OR p_snapshot->>'decisionPurpose' IS DISTINCT FROM p_purpose THEN RETURN false; END IF;
 SELECT * INTO o FROM core.ad_native_object WHERE id=p_object AND organization_id=p_organization;
 SELECT * INTO f FROM core.ad_freshness_profile WHERE id=(p_snapshot->>'id')::uuid
  AND organization_id=p_organization AND evidence_kind=p_kind AND decision_purpose=p_purpose
  AND status IN('ACTIVE','RETIRED') AND effective_from<=p_at
  AND (effective_to IS NULL OR effective_to>p_at);
 IF o.id IS NULL OR f.id IS NULL OR NOT (f.scope_kind='ORGANIZATION'
   OR (f.scope_kind='PLATFORM' AND f.platform_code=o.platform_code)
   OR (f.scope_kind='STORE' AND f.platform_code=o.platform_code AND f.store_ref_id=o.store_id)
   OR (f.scope_kind='SEMANTIC_PROFILE' AND f.platform_code=o.platform_code AND f.semantic_profile_id=o.semantic_profile_id
      AND (f.store_ref_id IS NULL OR f.store_ref_id=o.store_id))) THEN RETURN false; END IF;
 exact:=ops.ad_outcome_freshness_snapshot(f.id);
 IF (p_snapshot-'effectiveTo') IS DISTINCT FROM (exact-'effectiveTo')
   OR (p_snapshot->>'effectiveTo')::timestamptz IS DISTINCT FROM f.effective_to THEN RETURN false; END IF;
 RETURN true;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR invalid_datetime_format
 OR datetime_field_overflow THEN RETURN false;
END $$;

-- A stable read-state fingerprint schedules a revision only when an input that
-- can affect this frozen window has changed. It is bookkeeping, not a second
-- calculation/Policy authority. No readAt/evaluation clock value is hashed.
CREATE FUNCTION ops.ad_outcome_input_state_digest(p_observation uuid,p_input jsonb,p_at timestamptz) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,ledger,mart,platform,pg_temp SET timezone='UTC' AS $$
 WITH scope AS (
  SELECT o.*,b.listing_variant_ids,b.product_variant_ids,b.affected_set_id,
    replace(o.outcome_stage,'_REVISED','') stage,
    CASE WHEN o.window_ends_at-o.window_starts_at=interval '7 days' THEN 'D7'
      WHEN o.window_ends_at-o.window_starts_at=interval '14 days' THEN 'D14' ELSE 'D30' END metric_window
  FROM ops.ad_outcome_observation o JOIN ops.ad_outcome_baseline b ON b.id=(
    SELECT s.outcome_baseline_id FROM ops.ad_outcome_stage_baseline s
    WHERE s.outcome_baseline_id=coalesce(
      (SELECT c.outcome_baseline_id FROM ops.ad_bid_command c WHERE c.id=o.command_id),
      (SELECT m.outcome_baseline_id FROM ops.ad_manual_execution_packet m WHERE m.id=o.manual_packet_id))
      AND s.stage=replace(o.outcome_stage,'_REVISED',''))
  WHERE o.id=p_observation AND o.evaluated_at<=p_at
 ), selected_sales AS (
  SELECT f.*,source.source_time provenance_source,source.ingestion_time provenance_accepted
  FROM scope s JOIN ledger.sales_fact f ON f.organization_id=s.organization_id
    AND f.platform_listing_variant_id=ANY(s.listing_variant_ids)
  JOIN core.fact_provenance source ON source.id=f.provenance_id AND source.ingestion_time<=p_at
  WHERE f.occurred_at>=s.window_starts_at AND f.occurred_at<s.window_ends_at
    AND f.sale_stage=CASE s.stage WHEN 'OPERATIONAL' THEN 'COMPLETED' ELSE 'RETAINED' END
    AND (f.sale_stage<>'RETAINED' OR f.retention_window_days=30)
    AND NOT EXISTS(SELECT 1 FROM ledger.sales_fact newer JOIN core.fact_provenance provenance ON provenance.id=newer.provenance_id
      WHERE newer.supersedes_fact_id=f.id AND provenance.ingestion_time<=p_at)
 ), selected_linked AS (
  SELECT e.*,coalesce(e.source_time<=p_at,false) source_time_admissible FROM scope s JOIN ledger.ad_linked_sale_event e ON e.organization_id=s.organization_id
    AND e.ad_native_object_id=s.ad_native_object_id AND e.occurred_at>=s.window_starts_at
    AND e.occurred_at<s.window_ends_at AND e.recorded_at<=p_at
    AND e.sale_stage=CASE s.stage WHEN 'OPERATIONAL' THEN 'CANONICAL_AD_LINKED_COMPLETED_SALE' ELSE 'CANONICAL_AD_LINKED_RETAINED_SALE' END
    AND NOT EXISTS(SELECT 1 FROM ledger.ad_linked_sale_event newer WHERE newer.supersedes_event_id=e.id AND newer.recorded_at<=p_at)
 ), cost_cohorts AS (
  SELECT e.platform_listing_variant_id,min(e.period_start) cohort_from,max(e.period_end) cohort_to
  FROM selected_linked e WHERE e.platform_listing_variant_id IS NOT NULL
  GROUP BY e.platform_listing_variant_id
 ), health AS (
  SELECT h.*,p.source_time provenance_source,p.ingestion_time provenance_accepted,coalesce(p.source_time<=p_at,false) source_time_admissible
  FROM scope s JOIN core.listing_health_observation h ON h.organization_id=s.organization_id
    AND h.platform_listing_variant_id=ANY(s.listing_variant_ids) AND h.observed_at<=p_at
  JOIN core.fact_provenance p ON p.id=h.provenance_id AND p.ingestion_time<=p_at
 ), stock AS (
  SELECT h.*,p.source_time provenance_source,p.ingestion_time provenance_accepted,coalesce(p.source_time<=p_at,false) source_time_admissible
  FROM scope s JOIN core.listing_stock_observation h ON h.organization_id=s.organization_id
    AND h.platform_listing_variant_id=ANY(s.listing_variant_ids) AND h.observed_at<=p_at
  JOIN core.fact_provenance p ON p.id=h.provenance_id AND p.ingestion_time<=p_at
 ), price AS (
  SELECT h.*,p.source_time provenance_source,p.ingestion_time provenance_accepted,coalesce(p.source_time<=p_at,false) source_time_admissible
  FROM scope s JOIN core.listing_price_observation h ON h.organization_id=s.organization_id
    AND h.platform_listing_variant_id=ANY(s.listing_variant_ids) AND h.observed_at<=p_at
  JOIN core.fact_provenance p ON p.id=h.provenance_id AND p.ingestion_time<=p_at
 ), config AS (
  SELECT c.*,p.ingestion_time provenance_accepted FROM scope s
  JOIN core.ad_object_configuration_observation c ON c.organization_id=s.organization_id
    AND c.ad_native_object_id=s.ad_native_object_id AND c.observed_at<=p_at AND c.source_time<=p_at
  JOIN core.fact_provenance p ON p.id=c.provenance_id AND p.ingestion_time<=p_at
 ), proof_states AS (
  SELECT proof->>'kind' kind,proof->>'profileId' profile_id,
    ops.ad_outcome_frozen_profile_is_valid(p_input#>ARRAY['observation','freshnessProfiles',proof->>'kind'],s.organization_id,
      s.ad_native_object_id,proof->>'kind',proof->>'purpose',p_at) exact_profile_valid,
    coalesce((proof->>'expiresAt')::timestamptz<=p_at,false) expiry_crossed,
    coalesce((proof->>'sourceTime')::timestamptz<=p_at,false) source_time_admissible,
    coalesce((proof->>'acceptedAt')::timestamptz<=p_at,false) accepted_time_admissible,
    coalesce(p_at>=s.window_ends_at+make_interval(mins=>greatest(
      (p_input#>>ARRAY['observation','freshnessProfiles',proof->>'kind','expectedPublicationLagMinutes'])::integer,
      CASE WHEN p_input#>>ARRAY['observation','freshnessProfiles',proof->>'kind','requiresCorrectionWindowClosed']='true'
        THEN (p_input#>>ARRAY['observation','freshnessProfiles',proof->>'kind','correctionWindowMinutes'])::integer ELSE 0 END)),false) publication_mature,
    coalesce((p_input#>>ARRAY['observation','freshnessProfiles',proof->>'kind','providerIncidentBlocks'])::boolean,false)
      AND EXISTS(SELECT 1 FROM platform.ad_provider_incident i JOIN core.fact_provenance provenance
        ON provenance.id=i.provenance_id AND provenance.ingestion_time<=p_at
        JOIN core.ad_native_object obj ON obj.id=s.ad_native_object_id AND obj.organization_id=s.organization_id
        WHERE i.organization_id=s.organization_id AND i.platform_code=obj.platform_code
          AND (i.store_id IS NULL OR i.store_id=obj.store_id) AND i.incident_open AND i.observed_at<=p_at AND i.valid_until>p_at) incident_blocks
  FROM scope s CROSS JOIN LATERAL jsonb_array_elements(coalesce(p_input#>'{observation,purposeEvidence}','[]'::jsonb)) proof
 ), states AS (
  SELECT jsonb_build_object(
   'object',(SELECT to_jsonb(obj) FROM core.ad_native_object obj WHERE obj.id=s.ad_native_object_id AND obj.organization_id=s.organization_id),
   'semanticProfile',(SELECT to_jsonb(profile) FROM platform.ad_semantic_profile profile JOIN core.ad_native_object obj
     ON obj.semantic_profile_id=profile.id WHERE obj.id=s.ad_native_object_id AND obj.organization_id=s.organization_id),
   'adFacts',(SELECT coalesce(jsonb_agg(to_jsonb(f)||jsonb_build_object('sourceTimeAdmissible',coalesce(f.source_time<=p_at,false)) ORDER BY f.id),'[]') FROM ledger.ad_object_fact f
     WHERE f.organization_id=s.organization_id AND f.ad_native_object_id=s.ad_native_object_id AND f.recorded_at<=p_at
       AND f.period_start<s.window_ends_at AND f.period_end>s.window_starts_at
       AND NOT EXISTS(SELECT 1 FROM ledger.ad_object_fact newer WHERE newer.supersedes_fact_id=f.id AND newer.recorded_at<=p_at)),
   'linkedSales',(SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.id),'[]') FROM selected_linked e),
   'companySales',(SELECT coalesce(jsonb_agg(to_jsonb(f) ORDER BY f.id),'[]') FROM selected_sales f),
   'companySettlement',(SELECT coalesce(jsonb_agg(to_jsonb(f) ORDER BY f.id),'[]') FROM ledger.sales_fact f
     JOIN core.fact_provenance provenance ON provenance.id=f.provenance_id AND provenance.ingestion_time<=p_at
     WHERE s.stage='SETTLED' AND f.organization_id=s.organization_id AND f.sale_stage='SETTLED'
       AND NOT EXISTS(SELECT 1 FROM ledger.sales_fact newer JOIN core.fact_provenance source ON source.id=newer.provenance_id
         WHERE newer.supersedes_fact_id=f.id AND source.ingestion_time<=p_at)
       AND EXISTS(SELECT 1 FROM selected_sales r WHERE r.platform_listing_variant_id=f.platform_listing_variant_id
         AND r.native_order_key=f.native_order_key AND r.native_line_key IS NOT DISTINCT FROM f.native_line_key)),
   'attribution',(SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY a.id),'[]') FROM ledger.ad_settlement_attribution a
     WHERE s.stage='SETTLED' AND a.organization_id=s.organization_id AND a.accepted_at<=p_at
       AND a.ad_linked_sale_event_id IN(SELECT id FROM selected_linked)),
   'companyCoverage',(SELECT coalesce(jsonb_agg(to_jsonb(q)||jsonb_build_object('consumedSourcesAdmissible',
     CASE s.stage WHEN 'OPERATIONAL' THEN coalesce(q.completed_source_updated_at<=p_at,false)
       WHEN 'RETAINED' THEN coalesce(q.retained_source_updated_at<=p_at,false) AND coalesce(q.return_source_updated_at<=p_at,false)
       ELSE coalesce(q.retained_source_updated_at<=p_at,false) AND coalesce(q.return_source_updated_at<=p_at,false)
         AND coalesce(q.qc_source_updated_at<=p_at,false) END) ORDER BY q.id),'[]') FROM ledger.return_quality_evidence_snapshot q
     WHERE q.organization_id=s.organization_id AND q.platform_listing_variant_id=ANY(s.listing_variant_ids)
       AND q.accepted_at<=p_at AND q.report_window_start<=s.window_starts_at AND q.report_window_end>=s.window_ends_at
       AND NOT EXISTS(SELECT 1 FROM ledger.return_quality_evidence_snapshot newer WHERE newer.supersedes_snapshot_id=q.id AND newer.accepted_at<=p_at)),
   'health',(SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY h.id),'[]') FROM health h
     WHERE h.observed_at BETWEEN s.window_starts_at AND s.window_ends_at
       OR h.observed_at=(SELECT max(prior.observed_at) FROM health prior WHERE prior.platform_listing_variant_id=h.platform_listing_variant_id AND prior.observed_at<=s.window_starts_at)
       OR h.observed_at=(SELECT max(present.observed_at) FROM health present WHERE present.platform_listing_variant_id=h.platform_listing_variant_id)),
   'stock',(SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY h.id),'[]') FROM stock h
     WHERE h.observed_at BETWEEN s.window_starts_at AND s.window_ends_at
       OR h.observed_at=(SELECT max(prior.observed_at) FROM stock prior WHERE prior.platform_listing_variant_id=h.platform_listing_variant_id AND prior.fulfillment_mode_code=h.fulfillment_mode_code AND prior.observed_at<=s.window_starts_at)
       OR h.observed_at=(SELECT max(present.observed_at) FROM stock present WHERE present.platform_listing_variant_id=h.platform_listing_variant_id AND present.fulfillment_mode_code=h.fulfillment_mode_code)),
   'price',(SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY h.id),'[]') FROM price h
     WHERE h.observed_at BETWEEN s.window_starts_at AND s.window_ends_at
       OR h.observed_at=(SELECT max(prior.observed_at) FROM price prior WHERE prior.platform_listing_variant_id=h.platform_listing_variant_id AND prior.observed_at<=s.window_starts_at)
       OR h.observed_at=(SELECT max(present.observed_at) FROM price present WHERE present.platform_listing_variant_id=h.platform_listing_variant_id)),
   'configuration',(SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.id),'[]') FROM config c
     WHERE c.observed_at BETWEEN s.window_starts_at AND s.window_ends_at
       OR c.observed_at=(SELECT max(prior.observed_at) FROM config prior WHERE prior.observed_at<=s.window_starts_at)
       OR c.observed_at=(SELECT max(present.observed_at) FROM config present)),
   'affectedSets',(SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY a.id),'[]') FROM core.ad_affected_set a
     WHERE a.organization_id=s.organization_id AND a.ad_native_object_id=s.ad_native_object_id AND a.resolved_at<=p_at AND a.created_at<=p_at
       AND (a.resolved_at>=s.window_starts_at OR a.id=s.affected_set_id
         OR a.resolved_at=(SELECT max(prior.resolved_at) FROM core.ad_affected_set prior
           WHERE prior.organization_id=s.organization_id AND prior.ad_native_object_id=s.ad_native_object_id AND prior.resolved_at<=s.window_starts_at AND prior.created_at<=p_at))),
   'mappings',(SELECT coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.id),'[]') FROM core.listing_mapping m
     WHERE m.organization_id=s.organization_id AND m.platform_listing_variant_id=ANY(s.listing_variant_ids)
       AND m.created_at<=p_at AND m.effective_from<s.window_ends_at AND (m.effective_to IS NULL OR m.effective_to>s.window_starts_at)),
   'mappingConflicts',(SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.id),'[]') FROM core.mapping_conflict c
     WHERE c.organization_id=s.organization_id AND c.platform_listing_variant_id=ANY(s.listing_variant_ids)
       AND c.detected_at<=least(s.window_ends_at,p_at) AND (c.resolved_at IS NULL OR c.resolved_at>s.window_starts_at)),
   'metrics',(SELECT coalesce(jsonb_agg(to_jsonb(m)||jsonb_build_object('inputReferences',(
       SELECT coalesce(jsonb_agg(to_jsonb(ref) ORDER BY ref.id),'[]')
       FROM mart.metric_input_reference ref WHERE ref.metric_value_id=m.id)) ORDER BY m.id),'[]')
     FROM mart.metric_value m JOIN cost_cohorts cohort ON cohort.platform_listing_variant_id=m.subject_id
     WHERE m.organization_id=s.organization_id AND m.subject_kind='PLATFORM_LISTING_VARIANT'
       AND m.subject_id=ANY(s.listing_variant_ids) AND m.window_code=s.metric_window
       AND m.metric_code IN('UNIT_COST','PLATFORM_FEES_PER_UNIT','RETURN_LOSS_PER_UNIT','VARIABLE_TAX_PER_UNIT')
       AND m.computed_at<=p_at AND m.period_start<=cohort.cohort_from AND m.period_end>=cohort.cohort_to
       AND m.period_end<=p_at
       AND NOT EXISTS(SELECT 1 FROM mart.metric_value newer WHERE newer.organization_id=m.organization_id
         AND newer.subject_kind=m.subject_kind AND newer.subject_id=m.subject_id AND newer.window_code=m.window_code
         AND newer.metric_code=m.metric_code AND newer.computed_at<=p_at
         AND newer.period_start<=cohort.cohort_from AND newer.period_end>=cohort.cohort_to AND newer.period_end<=p_at
         AND (newer.computed_at,newer.id)>(m.computed_at,m.id))),
   'freshnessStates',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY to_jsonb(p)::text),'[]') FROM proof_states p)
  ) value FROM scope s
 ) SELECT encode(sha256(convert_to(value::text,'UTF8')),'hex') FROM states
$$;
REVOKE ALL ON FUNCTION ops.ad_outcome_input_state_digest(uuid,jsonb,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_outcome_input_state_digest(uuid,jsonb,timestamptz) TO marketops_app;

CREATE FUNCTION ops.ad_outcome_input_profiles_are_canonical(p_snapshot jsonb,p_organization uuid,p_object uuid,
 p_stage text,p_direction text,p_prepared timestamptz,p_valid_until timestamptz,p_at timestamptz) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE o core.ad_native_object%ROWTYPE; f core.ad_freshness_profile%ROWTYPE;
 profiles jsonb; entry record; proof jsonb; purpose text; company_kind text; required text[]; allowed text[];
BEGIN
 IF p_prepared IS NULL OR p_valid_until IS NULL OR p_at IS NULL OR p_prepared>p_at OR p_valid_until<=p_at
   OR p_stage IS NULL OR p_stage NOT IN('OPERATIONAL','RETAINED','SETTLED')
   OR p_direction IS NULL OR p_direction NOT IN('PROTECTION_DECREASE','OPTIMIZATION_INCREASE') THEN RETURN false; END IF;
 purpose:=CASE p_stage WHEN 'OPERATIONAL' THEN 'EARLY_COMPLETED_SALES_OUTCOME'
  WHEN 'RETAINED' THEN 'FINAL_RETAINED_SALES_OUTCOME' ELSE 'SETTLED_FINANCIAL_OUTCOME' END;
 company_kind:=CASE p_stage WHEN 'OPERATIONAL' THEN 'COMPANY_COMPLETED_SALE'
  WHEN 'RETAINED' THEN 'COMPANY_RETAINED_SALE' ELSE 'SETTLEMENT' END;
 allowed:=ARRAY[company_kind,'OFFICIAL_AD_SPEND','OFFICIAL_AD_TRAFFIC','AD_LINKED_SALE_EVENT','COST_AND_FEE',
  'AD_OBJECT_CONFIGURATION','AFFECTED_SET','SELLABILITY','AVAILABILITY','PRICE_AND_PROMOTION'];
 profiles:=p_snapshot->'freshnessProfiles';
 IF jsonb_typeof(profiles) IS DISTINCT FROM 'object' OR NOT profiles ? company_kind
   OR profiles->company_kind IS DISTINCT FROM p_snapshot->'freshnessProfile' THEN RETURN false; END IF;
 SELECT * INTO o FROM core.ad_native_object WHERE id=p_object AND organization_id=p_organization;
 IF NOT FOUND THEN RETURN false; END IF;
 FOR entry IN SELECT key,value FROM jsonb_each(profiles) LOOP
  IF NOT entry.key=ANY(allowed) OR NOT ops.ad_outcome_frozen_profile_is_valid(entry.value,p_organization,p_object,entry.key,purpose,p_at)
    THEN RETURN false; END IF;
  SELECT * INTO f FROM core.ad_freshness_profile WHERE id=(entry.value->>'id')::uuid;
  IF f.effective_from>p_prepared OR (f.effective_to IS NOT NULL AND p_valid_until>f.effective_to) THEN RETURN false; END IF;
  -- A planning/seal decision must resolve the unique applicable Profile. This
  -- check is absent from frozen observation validation above.
  IF EXISTS(SELECT 1 FROM core.ad_freshness_profile other WHERE other.organization_id=p_organization
    AND other.id<>f.id AND other.evidence_kind=entry.key AND other.decision_purpose=purpose
    AND other.status IN('ACTIVE','RETIRED') AND other.effective_from<=p_at AND (other.effective_to IS NULL OR other.effective_to>p_at)
    AND (other.scope_kind='ORGANIZATION' OR (other.scope_kind='PLATFORM' AND other.platform_code=o.platform_code)
      OR (other.scope_kind='STORE' AND other.platform_code=o.platform_code AND other.store_ref_id=o.store_id)
      OR (other.scope_kind='SEMANTIC_PROFILE' AND other.platform_code=o.platform_code AND other.semantic_profile_id=o.semantic_profile_id
         AND (other.store_ref_id IS NULL OR other.store_ref_id=o.store_id)))
    AND CASE other.scope_kind WHEN 'SEMANTIC_PROFILE' THEN 0 WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END
      <=CASE f.scope_kind WHEN 'SEMANTIC_PROFILE' THEN 0 WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END) THEN RETURN false; END IF;
 END LOOP;
 -- Physical Protection requires company safety; absent unrelated economics
 -- remain unresolved. A Profile becomes mandatory when its value is consumed.
 required:=ARRAY[company_kind];
 IF p_snapshot#>>'{officialSpend,valueState}'='AVAILABLE'
   AND p_snapshot#>>'{officialSpend,evidenceState}' IN('CANONICAL_CONFIRMED','OPERATIONAL') THEN
  required:=required||ARRAY['OFFICIAL_AD_SPEND']; END IF;
 IF jsonb_typeof(p_snapshot->'traffic')='number' THEN required:=required||ARRAY['OFFICIAL_AD_TRAFFIC']; END IF;
 IF (p_snapshot#>>'{profit,absoluteProfit,valueState}'='AVAILABLE'
     AND p_snapshot#>>'{profit,absoluteProfit,evidenceState}' IN('CANONICAL_CONFIRMED','OPERATIONAL'))
   OR (p_snapshot#>>'{profit,profitPerAdRub,valueState}'='AVAILABLE'
     AND p_snapshot#>>'{profit,profitPerAdRub,evidenceState}' IN('CANONICAL_CONFIRMED','OPERATIONAL')) THEN
  required:=required||ARRAY['OFFICIAL_AD_SPEND','AD_LINKED_SALE_EVENT','COST_AND_FEE']; END IF;
 IF p_snapshot#>>'{protectionEvidence,exactAffectedScope}'='true' THEN required:=required||ARRAY['AFFECTED_SET']; END IF;
 IF p_snapshot#>>'{protectionEvidence,configurationVerified}'='true' THEN required:=required||ARRAY['AD_OBJECT_CONFIGURATION']; END IF;
 IF p_snapshot#>>'{protectionEvidence,sellabilityCleared}'='true' THEN required:=required||ARRAY['SELLABILITY']; END IF;
 IF p_snapshot#>>'{protectionEvidence,availabilityCleared}'='true' THEN required:=required||ARRAY['AVAILABILITY']; END IF;
 IF p_snapshot ? 'purposeEvidence' THEN
  IF jsonb_typeof(p_snapshot->'purposeEvidence') IS DISTINCT FROM 'array' THEN RETURN false; END IF;
  FOR proof IN SELECT value FROM jsonb_array_elements(p_snapshot->'purposeEvidence') LOOP
   IF proof->>'eligible'='true' THEN
    IF proof->>'purpose' IS DISTINCT FROM purpose OR NOT coalesce((proof->>'kind')=ANY(allowed),false)
      OR proof->>'profileId' IS DISTINCT FROM profiles#>>ARRAY[proof->>'kind','id'] THEN RETURN false; END IF;
    required:=required||ARRAY[proof->>'kind'];
   END IF;
  END LOOP;
 END IF;
 IF p_direction='OPTIMIZATION_INCREASE' THEN
  required:=required||ARRAY['OFFICIAL_AD_SPEND','OFFICIAL_AD_TRAFFIC','AD_LINKED_SALE_EVENT','COST_AND_FEE','AFFECTED_SET'];
 END IF;
 IF EXISTS(SELECT 1 FROM unnest(required) kind WHERE NOT profiles ? kind) THEN RETURN false; END IF;
 RETURN true;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR invalid_datetime_format
 OR datetime_field_overflow THEN RETURN false;
END $$;

REVOKE ALL ON FUNCTION ops.ad_outcome_frozen_profile_is_valid(jsonb,uuid,uuid,text,text,timestamptz),
 ops.ad_outcome_input_profiles_are_canonical(jsonb,uuid,uuid,text,text,timestamptz,timestamptz,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_outcome_frozen_profile_is_valid(jsonb,uuid,uuid,text,text,timestamptz),
 ops.ad_outcome_input_profiles_are_canonical(jsonb,uuid,uuid,text,text,timestamptz,timestamptz,timestamptz) TO marketops_app;


-- Preserve every existing canonical Planner/authority/affected-set/stage check.
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
      OR (scope_kind='STORE' AND store_ref_id=obj.store_id) OR (scope_kind='SEMANTIC_PROFILE' AND semantic_profile_id=obj.semantic_profile_id));
  IF NOT FOUND OR ((snap->'freshnessProfile')-'effectiveTo') IS DISTINCT FROM (ops.ad_outcome_freshness_snapshot(fresh.id)-'effectiveTo')
    OR (snap#>>'{freshnessProfile,effectiveTo}')::timestamptz IS DISTINCT FROM fresh.effective_to THEN RETURN false; END IF;
  IF EXISTS(SELECT 1 FROM core.ad_freshness_profile other WHERE other.organization_id=b.organization_id
    AND other.id<>fresh.id AND other.evidence_kind=kind_code AND other.decision_purpose=purpose_code
    AND other.status IN('ACTIVE','RETIRED') AND other.effective_from<=p_at AND (other.effective_to IS NULL OR other.effective_to>p_at)
    AND (other.scope_kind='ORGANIZATION' OR (other.scope_kind='PLATFORM' AND other.platform_code=obj.platform_code)
      OR (other.scope_kind='STORE' AND other.store_ref_id=obj.store_id) OR (other.scope_kind='SEMANTIC_PROFILE' AND other.semantic_profile_id=obj.semantic_profile_id))
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
