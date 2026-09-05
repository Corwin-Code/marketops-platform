-- A repeated canonical computation may reuse an immutable metric_value. This
-- append-only edge proves which exact value that run actually evaluated; merely
-- reporting a completed run through CalculationRunLedger creates no such edge.
CREATE TABLE mart.metric_value_evaluation (
 metric_value_id uuid NOT NULL REFERENCES mart.metric_value(id),
 calculation_run_id uuid NOT NULL REFERENCES mart.calculation_run(id),
 evaluated_at timestamptz NOT NULL,
 PRIMARY KEY(metric_value_id,calculation_run_id)
);
CREATE INDEX metric_value_evaluation_run_ix ON mart.metric_value_evaluation(calculation_run_id);

CREATE FUNCTION mart.validate_metric_value_evaluation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,mart,pg_temp AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'metric evaluation history is immutable' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM mart.metric_value v
  JOIN mart.calculation_run original ON original.id=v.calculation_run_id
  JOIN mart.calculation_run r ON r.id=NEW.calculation_run_id
  WHERE v.id=NEW.metric_value_id AND r.state='RUNNING'
   AND r.organization_id=v.organization_id AND original.organization_id=v.organization_id
   AND r.scope_kind=original.scope_kind AND r.store_ref_id IS NOT DISTINCT FROM original.store_ref_id
   AND r.window_code=v.window_code AND r.period_start=v.period_start AND r.period_end=v.period_end
   AND NEW.evaluated_at>=r.started_at AND NEW.evaluated_at>=v.computed_at
   AND r.period_end<=NEW.evaluated_at)
 THEN RAISE EXCEPTION 'metric evaluation must match an active canonical computation' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION mart.validate_metric_value_evaluation() FROM PUBLIC;
CREATE TRIGGER metric_value_evaluation_guard BEFORE INSERT OR UPDATE OR DELETE ON mart.metric_value_evaluation
FOR EACH ROW EXECUTE FUNCTION mart.validate_metric_value_evaluation();
REVOKE ALL ON mart.metric_value_evaluation FROM PUBLIC,marketops_app;
GRANT SELECT,INSERT ON mart.metric_value_evaluation TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES('mart','metric_value_evaluation','NO_ROUTE',NULL,'append-only evidence of the existing canonical Metric writer; no execution authority');

-- A failed/future/unrelated run never refreshes a value. Preserve the original
-- computed_at even after a successful evaluation. Old rows retain their age.
CREATE FUNCTION mart.metric_value_verification(p_value uuid,p_at timestamptz)
RETURNS TABLE(verified_at timestamptz,verification_run_id uuid)
LANGUAGE sql STABLE SET search_path=pg_catalog,mart,pg_temp AS $$
 SELECT coalesce(proof.evaluated_at,initial.computed_at),coalesce(proof.calculation_run_id,initial.calculation_run_id)
 FROM mart.metric_value v
 LEFT JOIN LATERAL (
  SELECT v.computed_at,v.calculation_run_id FROM mart.calculation_run original
  WHERE original.id=v.calculation_run_id AND original.state='SUCCEEDED'
   AND original.organization_id=v.organization_id AND original.window_code=v.window_code
   AND original.period_start=v.period_start AND original.period_end=v.period_end
   AND original.started_at<=v.computed_at AND v.period_end<=v.computed_at
   AND v.computed_at<=original.completed_at
   AND original.completed_at<=coalesce(p_at,statement_timestamp())
 ) initial ON true
 LEFT JOIN LATERAL (
  SELECT e.evaluated_at,e.calculation_run_id FROM mart.metric_value_evaluation e
  JOIN mart.calculation_run r ON r.id=e.calculation_run_id
  JOIN mart.calculation_run original ON original.id=v.calculation_run_id
  WHERE e.metric_value_id=v.id AND r.state='SUCCEEDED'
   AND r.organization_id=v.organization_id AND original.organization_id=v.organization_id
   AND r.scope_kind=original.scope_kind AND r.store_ref_id IS NOT DISTINCT FROM original.store_ref_id
   AND r.window_code=v.window_code AND r.period_start=v.period_start AND r.period_end=v.period_end
   AND r.started_at<=e.evaluated_at AND v.computed_at<=e.evaluated_at
   AND r.period_end<=e.evaluated_at AND e.evaluated_at<=r.completed_at
   AND e.evaluated_at<=coalesce(p_at,statement_timestamp()) AND r.completed_at<=coalesce(p_at,statement_timestamp())
  ORDER BY e.evaluated_at DESC,e.calculation_run_id DESC LIMIT 1
 ) proof ON true WHERE v.id=p_value
$$;
REVOKE ALL ON FUNCTION mart.metric_value_verification(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mart.metric_value_verification(uuid,timestamptz) TO marketops_app;

-- Outcome revision detection consumes the very same exact-value proof as MetricQuery.
CREATE OR REPLACE FUNCTION ops.ad_outcome_input_state_digest(p_observation uuid,p_input jsonb,p_at timestamptz) RETURNS text
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
   'metrics',(SELECT coalesce(jsonb_agg(to_jsonb(m)||jsonb_build_object('evaluationProof',to_jsonb(proof),'inputReferences',(
       SELECT coalesce(jsonb_agg(to_jsonb(ref) ORDER BY ref.id),'[]')
       FROM mart.metric_input_reference ref WHERE ref.metric_value_id=m.id)) ORDER BY m.id),'[]')
     FROM mart.metric_value m JOIN cost_cohorts cohort ON cohort.platform_listing_variant_id=m.subject_id
     CROSS JOIN LATERAL mart.metric_value_verification(m.id,p_at) proof
     WHERE m.organization_id=s.organization_id AND m.subject_kind='PLATFORM_LISTING_VARIANT'
       AND m.subject_id=ANY(s.listing_variant_ids) AND m.window_code=s.metric_window
       AND m.metric_code IN('UNIT_COST','PLATFORM_FEES_PER_UNIT','RETURN_LOSS_PER_UNIT','VARIABLE_TAX_PER_UNIT')
       AND m.computed_at<=p_at AND m.period_start<=cohort.cohort_from AND m.period_end>=cohort.cohort_to
       AND m.period_end<=p_at
       AND NOT EXISTS(SELECT 1 FROM mart.metric_value newer
         CROSS JOIN LATERAL mart.metric_value_verification(newer.id,p_at) newer_proof
         WHERE newer.organization_id=m.organization_id
         AND newer.subject_kind=m.subject_kind AND newer.subject_id=m.subject_id AND newer.window_code=m.window_code
         AND newer.metric_code=m.metric_code AND newer.computed_at<=p_at
         AND newer.period_start<=cohort.cohort_from AND newer.period_end>=cohort.cohort_to AND newer.period_end<=p_at
         AND (greatest(newer.computed_at,newer_proof.verified_at),newer.computed_at,newer.id)
             >(greatest(m.computed_at,proof.verified_at),m.computed_at,m.id))),
   'freshnessStates',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY to_jsonb(p)::text),'[]') FROM proof_states p)
  ) value FROM scope s
 ) SELECT encode(sha256(convert_to(value::text,'UTF8')),'hex') FROM states
$$;
REVOKE ALL ON FUNCTION ops.ad_outcome_input_state_digest(uuid,jsonb,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_outcome_input_state_digest(uuid,jsonb,timestamptz) TO marketops_app;
