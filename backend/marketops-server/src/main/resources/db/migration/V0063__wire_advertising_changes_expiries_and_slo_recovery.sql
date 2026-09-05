-- Accepted facts and bounded authorities wake the same advertising calculation seam.
-- The queue retains the oldest unanswered event, including events arriving during a lease.
ALTER TABLE ops.ad_recalculation_request ADD COLUMN calculation_as_of timestamptz;
ALTER TABLE ops.ad_recalculation_request ADD COLUMN latest_fact_accepted_at timestamptz;
UPDATE ops.ad_recalculation_request SET latest_fact_accepted_at=fact_accepted_at;
-- Older integrations inserting directly still get an exact accepted upper bound.
CREATE FUNCTION ops.ad_request_acceptance_bounds() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    NEW.latest_fact_accepted_at:=coalesce(NEW.latest_fact_accepted_at,NEW.fact_accepted_at);RETURN NEW;
END $$;
CREATE TRIGGER ad_request_acceptance_bounds BEFORE INSERT ON ops.ad_recalculation_request
    FOR EACH ROW EXECUTE FUNCTION ops.ad_request_acceptance_bounds();
ALTER TABLE ops.ad_recalculation_request ADD COLUMN next_fact_accepted_at timestamptz;
ALTER TABLE ops.ad_slo_observation DROP CONSTRAINT ad_slo_observation_order_ck;
ALTER TABLE ops.ad_slo_observation DROP CONSTRAINT ad_slo_observation_internal_ck;
ALTER TABLE ops.ad_slo_observation ALTER COLUMN internal_latency_ms DROP NOT NULL;
ALTER TABLE ops.ad_slo_observation ADD COLUMN clock_state text NOT NULL DEFAULT 'VALID'
    CHECK(clock_state IN ('VALID','CLOCK_INCONSISTENT'));
ALTER TABLE ops.ad_slo_observation ADD CONSTRAINT ad_slo_clock_evidence_ck CHECK(
    (clock_state='VALID' AND calculated_at>=fact_accepted_at AND internal_latency_ms>=0)
    OR (clock_state='CLOCK_INCONSISTENT' AND calculated_at<fact_accepted_at AND internal_latency_ms IS NULL));

ALTER TABLE ops.ad_trace_event DROP CONSTRAINT ad_trace_event_stage_ck;
ALTER TABLE ops.ad_trace_event ADD CONSTRAINT ad_trace_event_stage_ck CHECK(stage_code IN(
    'TARGET_DEDUP_QUEUED','TARGET_DEDUP_COALESCED','TARGET_DEDUP_SUPPRESSED','CALCULATION_STARTED',
    'EVIDENCE_AND_LANE_CALCULATED','PROJECTION_WRITTEN','CASE_SYNCHRONIZED','AUTO_VERIFICATION',
    'SLO_RECORDED','SWEEP_STARTED','SWEEP_COMPLETED','SWEEP_FAILED','BACKLOG_SNAPSHOT',
    'EXCEPTION_EXPIRY_REVALIDATION','APPROVAL_EXPIRY_SWEEP','RESERVATION_RELEASE_SWEEP','OUTCOME_MATURITY_SWEEP','FACT_ACCEPTED'));

CREATE TABLE ops.ad_recalculation_due (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL REFERENCES core.organization(id),
    ad_native_object_id uuid NOT NULL, trigger_class text NOT NULL, source_reference text NOT NULL,
    due_at timestamptz NOT NULL, delivered_at timestamptz,
    FOREIGN KEY(ad_native_object_id,organization_id) REFERENCES core.ad_native_object(id,organization_id),
    UNIQUE(ad_native_object_id,source_reference,due_at)
);
CREATE INDEX ad_recalculation_due_ready_ix ON ops.ad_recalculation_due(due_at) WHERE delivered_at IS NULL;
GRANT SELECT,UPDATE ON ops.ad_recalculation_due TO marketops_app;

CREATE FUNCTION ops.enqueue_ad_change(p_org uuid,p_object uuid,p_class text,p_reference text,p_accepted timestamptz)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops AS $$
BEGIN
    INSERT INTO ops.ad_recalculation_request(id,organization_id,ad_native_object_id,trigger_class,
        trigger_reference,fact_accepted_at,requested_at,state,correlation_id)
    VALUES(gen_random_uuid(),p_org,p_object,p_class,left(p_reference,240),p_accepted,clock_timestamp(),'PENDING',
        'ad-change:'||gen_random_uuid())
    ON CONFLICT(organization_id,ad_native_object_id) WHERE state IN ('PENDING','LEASED')
    DO UPDATE SET fact_accepted_at=least(ops.ad_recalculation_request.fact_accepted_at,EXCLUDED.fact_accepted_at),
        latest_fact_accepted_at=greatest(ops.ad_recalculation_request.latest_fact_accepted_at,EXCLUDED.fact_accepted_at),
        next_fact_accepted_at=CASE WHEN ops.ad_recalculation_request.state='LEASED'
            AND EXCLUDED.fact_accepted_at>ops.ad_recalculation_request.calculation_as_of
            THEN least(coalesce(ops.ad_recalculation_request.next_fact_accepted_at,EXCLUDED.fact_accepted_at),EXCLUDED.fact_accepted_at)
            ELSE ops.ad_recalculation_request.next_fact_accepted_at END,
        version=ops.ad_recalculation_request.version+1;
END $$;
REVOKE ALL ON FUNCTION ops.enqueue_ad_change(uuid,uuid,text,text,timestamptz) FROM PUBLIC;
-- Only canonical row triggers and the due scheduler invoke this function.

CREATE FUNCTION ops.ad_change_to_targeted_request() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,core,ledger,platform AS $$
DECLARE
    data jsonb; versions jsonb[];
    v_organization uuid; v_object_id uuid; v_store_id uuid; v_account_id uuid;
    v_variant_id uuid; v_listing_id uuid; v_platform_code text; v_dependent_variants uuid[];
    source_reference text;
    accepted timestamptz:=clock_timestamp();
    due timestamptz; object_row record; field text; provenance core.fact_provenance;
BEGIN
    IF TG_OP='UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    versions:=CASE WHEN TG_OP='UPDATE' THEN ARRAY[to_jsonb(OLD),to_jsonb(NEW)]
        WHEN TG_OP='DELETE' THEN ARRAY[to_jsonb(OLD)] ELSE ARRAY[to_jsonb(NEW)] END;
    -- Both sides of a remapping/status/scope change must wake their prior dependants.
    FOREACH data IN ARRAY versions LOOP
    v_organization:=nullif(data->>'organization_id','')::uuid;
    v_object_id:=nullif(data->>'ad_native_object_id','')::uuid;
    v_store_id:=nullif(coalesce(data->>'store_id',data->>'store_ref_id'),'')::uuid;
    v_account_id:=nullif(data->>'marketplace_account_id','')::uuid;
    v_variant_id:=nullif(data->>'product_variant_id','')::uuid;
    v_listing_id:=nullif(coalesce(data->>'platform_listing_variant_id',data->>'listing_variant_id'),'')::uuid;
    v_platform_code:=data->>'platform_code';
    source_reference:=TG_TABLE_SCHEMA||'.'||TG_TABLE_NAME||':'||coalesce(data->>'id','version');
    provenance:=NULL;
    IF data->>'provenance_id' IS NOT NULL THEN
        SELECT * INTO provenance FROM core.fact_provenance WHERE id=(data->>'provenance_id')::uuid AND organization_id=v_organization;
    END IF;
    v_dependent_variants:=NULL;
    IF TG_TABLE_NAME='listing_mapping' THEN
        v_dependent_variants:=ARRAY[v_variant_id];v_variant_id:=NULL;
        v_store_id:=NULL;v_account_id:=NULL;v_platform_code:=NULL;
    ELSIF TG_TABLE_SCHEMA='ledger' AND TG_TABLE_NAME IN ('sales_fact','return_fact','settlement_fact','finance_fee_fact','return_quality_evidence_snapshot') THEN
        -- Company economics includes every channel of each affected ProductVariant.
        -- A sale in another store therefore wakes the same product's advertising,
        -- while a different product or organization remains outside that fanout.
        SELECT array_agg(DISTINCT mapping.product_variant_id) INTO v_dependent_variants
          FROM core.listing_mapping mapping WHERE mapping.organization_id=v_organization
            AND mapping.platform_listing_variant_id=v_listing_id;
        v_store_id:=NULL;v_account_id:=NULL;v_platform_code:=NULL;
    END IF;
    IF TG_TABLE_NAME='ad_native_object' THEN v_object_id:=(data->>'id')::uuid; END IF;
    IF TG_TABLE_NAME='ad_object_relationship' THEN v_object_id:=nullif(data->>'parent_object_id','')::uuid; END IF;
    IF v_object_id IS NULL AND data->>'case_id' IS NOT NULL THEN
        SELECT ad_native_object_id INTO v_object_id FROM mart.ad_case WHERE id=(data->>'case_id')::uuid;
    END IF;
    IF v_object_id IS NULL AND data->>'command_id' IS NOT NULL THEN
        SELECT ad_native_object_id INTO v_object_id FROM ops.ad_bid_command WHERE id=(data->>'command_id')::uuid;
    END IF;
    IF v_object_id IS NULL AND data->>'packet_id' IS NOT NULL THEN
        SELECT ad_native_object_id INTO v_object_id FROM ops.ad_manual_execution_packet WHERE id=(data->>'packet_id')::uuid;
    END IF;
    FOR object_row IN SELECT obj.id,obj.organization_id FROM core.ad_native_object obj
        WHERE (v_organization IS NULL OR obj.organization_id=v_organization)
          AND (v_platform_code IS NULL OR obj.platform_code=v_platform_code)
          AND (v_object_id IS NULL OR obj.id=v_object_id)
          AND (v_store_id IS NULL OR obj.store_id=v_store_id)
          AND (v_account_id IS NULL OR EXISTS(SELECT 1 FROM core.store object_store
                WHERE object_store.id=obj.store_id AND object_store.marketplace_account_id=v_account_id))
          AND (v_variant_id IS NULL OR EXISTS(SELECT 1 FROM core.ad_affected_set affected
                WHERE affected.ad_native_object_id=obj.id AND v_variant_id=ANY(affected.product_variant_ids)))
          AND ((v_listing_id IS NULL AND v_dependent_variants IS NULL) OR EXISTS(SELECT 1 FROM core.ad_affected_set affected
                WHERE affected.ad_native_object_id=obj.id AND (v_listing_id=ANY(affected.platform_listing_variant_ids)
                    OR affected.product_variant_ids && v_dependent_variants))
                OR EXISTS(SELECT 1 FROM core.ad_object_relationship rel
                    WHERE rel.parent_object_id=obj.id AND rel.platform_listing_variant_id=v_listing_id))
    LOOP
        PERFORM ops.enqueue_ad_change(object_row.organization_id,object_row.id,TG_ARGV[0],source_reference,accepted);
        INSERT INTO ops.ad_trace_event(id,organization_id,ad_native_object_id,path_kind,stage_code,status,
            correlation_id,subject_reference,detail,occurred_at)
        VALUES(gen_random_uuid(),object_row.organization_id,object_row.id,'TARGETED','FACT_ACCEPTED','OBSERVED',
            'ad-fact:'||gen_random_uuid(),source_reference,jsonb_build_object('triggerClass',TG_ARGV[0],
              'sourceEventTime',coalesce(data->'occurred_at',data->'source_time',to_jsonb(provenance.source_time)),
              'sourceUpdatedAt',data->'source_updated_at',
              'ingestedAt',coalesce(data->'ingested_at',to_jsonb(provenance.ingestion_time)),
              'factAcceptedAt',accepted),accepted);
        FOREACH field IN ARRAY ARRAY['expires_at','valid_until','effective_from','effective_to','review_due_at',
                'early_observation_due_at','operational_observation_due_at','settled_observation_due_at']
        LOOP
            due:=nullif(data->>field,'')::timestamptz;
            IF due IS NOT NULL AND due>accepted THEN
                INSERT INTO ops.ad_recalculation_due(organization_id,ad_native_object_id,trigger_class,source_reference,due_at)
                VALUES(object_row.organization_id,object_row.id,TG_ARGV[0],source_reference||':'||field,due)
                ON CONFLICT DO NOTHING;
            END IF;
        END LOOP;
    END LOOP;
    END LOOP;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
REVOKE ALL ON FUNCTION ops.ad_change_to_targeted_request() FROM PUBLIC;

DO $$ DECLARE entry text[]; BEGIN
    FOREACH entry SLICE 1 IN ARRAY ARRAY[
      ['core.ad_native_object','AD_CONFIGURATION'],
      ['core.ad_object_configuration_observation','AD_CONFIGURATION'],
      ['core.ad_object_relationship','PRODUCT_MAPPING_OR_AFFECTED_SET'],
      ['core.ad_affected_set','PRODUCT_MAPPING_OR_AFFECTED_SET'],
      ['core.listing_mapping','PRODUCT_MAPPING_OR_AFFECTED_SET'],
      ['ledger.ad_object_fact','AD_SPEND_OR_TRAFFIC'],
      ['ledger.ad_object_listing_allocation','PROVIDER_ATTRIBUTION'],
      ['ledger.ad_linked_sale_event','COMPANY_SALES_OR_RETURNS'],
      ['ledger.sales_fact','COMPANY_SALES_OR_RETURNS'],
      ['ledger.return_fact','COMPANY_SALES_OR_RETURNS'],
      ['ledger.return_quality_evidence_snapshot','COMPANY_SALES_OR_RETURNS'],
      ['ledger.finance_fee_fact','COST_OR_FEE'],
      ['core.cost_version','COST_OR_FEE'],
      ['core.listing_price_observation','COST_OR_FEE'],
      ['core.listing_stock_observation','SELLABILITY_OR_AVAILABILITY'],
      ['core.listing_health_observation','SELLABILITY_OR_AVAILABILITY'],
      ['core.internal_stock_snapshot','SELLABILITY_OR_AVAILABILITY'],
      ['core.source_feed_watermark','SELLABILITY_OR_AVAILABILITY'],
      ['ledger.settlement_fact','SETTLEMENT_OR_ADJUSTMENT'],
      ['ledger.ad_settlement_attribution','SETTLEMENT_OR_ADJUSTMENT'],
      ['core.ad_conversion_definition','CONVERSION_OR_ALLOWABLE_CPA'],
      ['core.ad_allowable_cpa_definition','CONVERSION_OR_ALLOWABLE_CPA'],
      ['core.ad_freshness_profile','FRESHNESS_OR_QUALIFICATION_POLICY'],
      ['core.ad_optimization_qualification_policy','FRESHNESS_OR_QUALIFICATION_POLICY'],
      ['core.ad_bid_target_policy','TARGET_OR_OUTCOME_POLICY'],
      ['core.ad_outcome_policy','TARGET_OR_OUTCOME_POLICY'],
      ['core.ad_outcome_critical_unit_rule','CRITICAL_SALES_OR_CONFOUNDER'],
      ['core.ad_priority_policy','PRIORITY_OR_SLO_POLICY'],
      ['core.ad_human_slo_profile','PRIORITY_OR_SLO_POLICY'],
      ['core.ad_reporting_calendar','PRIORITY_OR_SLO_POLICY'],
      ['core.ad_materiality_policy','TARGET_OR_OUTCOME_POLICY'],
      ['core.ad_approval_lease_policy','LEASE_OR_EXPOSURE_POLICY'],
      ['core.ad_exposure_envelope','LEASE_OR_EXPOSURE_POLICY'],
      ['ops.ad_decision_policy_bundle','POLICY_BUNDLE_LIFECYCLE'],
      ['ops.ad_accepted_exception','EXCEPTION_HOLD_KILL_OR_QUARANTINE'],
      ['ops.ad_containment','EXCEPTION_HOLD_KILL_OR_QUARANTINE'],
      ['platform.ad_provider_incident','PROVIDER_READBACK_OR_UNKNOWN'],
      ['platform.ad_semantic_profile','POLICY_BUNDLE_LIFECYCLE'],
      ['ops.ad_bid_command_readback','PROVIDER_READBACK_OR_UNKNOWN'],
      ['ops.ad_bid_command','PROVIDER_READBACK_OR_UNKNOWN'],
      ['ops.ad_manual_execution_packet','PROVIDER_READBACK_OR_UNKNOWN'],
      ['ops.ad_manual_configuration_verification','PROVIDER_READBACK_OR_UNKNOWN'],
      ['core.ad_manual_policy','TARGET_OR_OUTCOME_POLICY'],
      ['ops.ad_outcome_observation','OUTCOME_MATURITY_OR_REGRESSION']
    ] LOOP
      IF to_regclass(entry[1]) IS NOT NULL THEN
        EXECUTE format('CREATE TRIGGER ad_targeted_change AFTER INSERT OR UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request(%L)',entry[1],entry[2]);
      END IF;
    END LOOP;
END $$;

CREATE FUNCTION ops.deliver_due_ad_recalculations(p_now timestamptz,p_limit integer) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops AS $$
DECLARE row ops.ad_recalculation_due; delivered integer:=0;
BEGIN
    FOR row IN SELECT * FROM ops.ad_recalculation_due WHERE delivered_at IS NULL AND due_at<=p_now
        ORDER BY due_at,id LIMIT greatest(1,least(p_limit,10000)) FOR UPDATE SKIP LOCKED
    LOOP
        PERFORM ops.enqueue_ad_change(row.organization_id,row.ad_native_object_id,row.trigger_class,row.source_reference,row.due_at);
        UPDATE ops.ad_recalculation_due SET delivered_at=clock_timestamp() WHERE id=row.id;
        delivered:=delivered+1;
    END LOOP;
    RETURN delivered;
END $$;
REVOKE ALL ON FUNCTION ops.deliver_due_ad_recalculations(timestamptz,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.deliver_due_ad_recalculations(timestamptz,integer) TO marketops_app;

-- Calculation publishes exact purpose deadlines. Schedule them without retriggering
-- the calculation which just produced them; equality uses the original expiry clock.
CREATE FUNCTION ops.schedule_ad_purpose_expiry() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,mart AS $$
BEGIN
    IF NEW.expires_at IS NOT NULL AND NEW.expires_at>clock_timestamp() THEN
        INSERT INTO ops.ad_recalculation_due(organization_id,ad_native_object_id,trigger_class,source_reference,due_at)
        SELECT NEW.organization_id,c.ad_native_object_id,'FRESHNESS_OR_QUALIFICATION_POLICY',
            'purpose:'||NEW.case_id||':'||NEW.decision_purpose||':'||NEW.evidence_kind,NEW.expires_at
        FROM mart.ad_case c WHERE c.id=NEW.case_id AND c.organization_id=NEW.organization_id
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.schedule_ad_purpose_expiry() FROM PUBLIC;
CREATE TRIGGER ad_purpose_deadline AFTER INSERT ON mart.ad_case_purpose_evidence
    FOR EACH ROW EXECUTE FUNCTION ops.schedule_ad_purpose_expiry();

CREATE FUNCTION ops.schedule_ad_outcome_maturity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops AS $$
DECLARE baseline ops.ad_outcome_baseline; landed timestamptz; stage ops.ad_outcome_stage_baseline;
BEGIN
    IF TG_TABLE_NAME='ad_bid_command_readback' THEN
        IF NEW.match_state<>'MATCHES_TARGET' THEN RETURN NEW; END IF;
        SELECT b.* INTO baseline FROM ops.ad_bid_command c JOIN ops.ad_outcome_baseline b ON b.id=c.outcome_baseline_id
            WHERE c.id=NEW.command_id;landed:=NEW.observed_at;
    ELSE
        IF NOT NEW.proves_configuration THEN RETURN NEW; END IF;
        SELECT b.* INTO baseline FROM ops.ad_manual_execution_packet p JOIN ops.ad_outcome_baseline b ON b.id=p.outcome_baseline_id
            WHERE p.id=NEW.packet_id;landed:=NEW.observed_at;
    END IF;
    IF baseline.id IS NULL THEN RETURN NEW; END IF;
    IF (CASE WHEN jsonb_typeof(baseline.plan_snapshot->'observationStartsMinutes')='number'
      THEN (baseline.plan_snapshot->>'observationStartsMinutes')::numeric NOT BETWEEN 0 AND 2147483647
        OR trunc((baseline.plan_snapshot->>'observationStartsMinutes')::numeric)<>(baseline.plan_snapshot->>'observationStartsMinutes')::numeric
      ELSE true END) THEN
        -- Preserve the external configuration fact. Missing local planning authority
        -- blocks outcome scheduling and is visible as an incident, never a zero deadline.
        INSERT INTO ops.ad_trace_event(id,organization_id,ad_native_object_id,path_kind,stage_code,status,
          correlation_id,subject_reference,detail,occurred_at)
        VALUES(gen_random_uuid(),baseline.organization_id,baseline.ad_native_object_id,'OPERATIONS','OUTCOME_MATURITY_SWEEP','FAILED',
          'outcome-deadline:'||gen_random_uuid(),baseline.id::text,
          '{"reason":"AD_OUTCOME_PLAN_DEADLINE_UNRESOLVED"}',clock_timestamp());
        RETURN NEW;
    END IF;
    FOR stage IN SELECT * FROM ops.ad_outcome_stage_baseline WHERE outcome_baseline_id=baseline.id LOOP
        INSERT INTO ops.ad_recalculation_due(organization_id,ad_native_object_id,trigger_class,source_reference,due_at)
        VALUES(baseline.organization_id,baseline.ad_native_object_id,'OUTCOME_MATURITY_OR_REGRESSION',
            'outcome:'||baseline.id||':'||stage.stage,
            landed+make_interval(mins=>(baseline.plan_snapshot->>'observationStartsMinutes')::integer,hours=>stage.window_hours))
        ON CONFLICT DO NOTHING;
    END LOOP;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.schedule_ad_outcome_maturity() FROM PUBLIC;
CREATE TRIGGER ad_outcome_maturity AFTER INSERT ON ops.ad_bid_command_readback
    FOR EACH ROW EXECUTE FUNCTION ops.schedule_ad_outcome_maturity();
CREATE TRIGGER ad_manual_outcome_maturity AFTER INSERT ON ops.ad_manual_configuration_verification
    FOR EACH ROW EXECUTE FUNCTION ops.schedule_ad_outcome_maturity();
