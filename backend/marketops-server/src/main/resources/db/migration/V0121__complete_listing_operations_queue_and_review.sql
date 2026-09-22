-- SLICE-V1-004 roots 023-026.  This migration extends the existing Listing
-- queue and Task authorities.  It does not create a provider route, a second
-- scheduler, or a second case/work ledger.

-- ---------------------------------------------------------------------------
-- Fenced recalculation receipts and the bounded sixty-minute sweep
-- ---------------------------------------------------------------------------

ALTER TABLE ops.lc_recalculation_queue
    ADD COLUMN consumer_contract_version integer,
    ADD COLUMN measurement_result_ids uuid[] NOT NULL DEFAULT '{}',
    ADD COLUMN binding_assessed_count integer,
    ADD COLUMN binding_invalidated_count integer,
    ADD COLUMN outcome_assessed_count integer,
    ADD COLUMN outcome_result_ids uuid[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT lc_recalculation_consumer_counts_ck CHECK (
        (binding_assessed_count IS NULL OR (binding_assessed_count >= 0
            AND binding_invalidated_count BETWEEN 0 AND binding_assessed_count))
        AND (outcome_assessed_count IS NULL OR (outcome_assessed_count >= 0
            AND cardinality(outcome_result_ids) <= outcome_assessed_count))),
    ADD CONSTRAINT lc_recalculation_consumer_version_ck CHECK (
        consumer_contract_version IS NULL OR consumer_contract_version = 1);

CREATE UNIQUE INDEX lc_recalculation_one_open_full_review
    ON ops.lc_recalculation_queue(platform_listing_id)
    WHERE trigger_class='FULL_REVIEW' AND state IN ('QUEUED','RUNNING');

CREATE OR REPLACE FUNCTION ops.lc_recalculation_result_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ops, mart AS $$
BEGIN
    IF TG_OP='UPDATE' AND OLD.state IN ('FINISHED','FAILED') THEN
        RAISE EXCEPTION 'terminal recalculation receipt is immutable' USING ERRCODE='MO105';
    END IF;
    IF TG_OP='UPDATE' AND OLD.lease_generation IS NOT NULL
       AND (NEW.lease_generation IS NULL OR NEW.lease_generation<OLD.lease_generation) THEN
        RAISE EXCEPTION 'recalculation generation cannot be erased or rewound' USING ERRCODE='MO105';
    END IF;
    IF NEW.state IN ('RUNNING','FINISHED','FAILED') AND NEW.lease_generation IS NULL THEN
        RAISE EXCEPTION 'managed recalculation state requires a lease generation' USING ERRCODE='MO105';
    END IF;
    IF NEW.state IN ('FINISHED','FAILED') AND (TG_OP<>'UPDATE' OR OLD.state<>'RUNNING'
       OR OLD.lease_generation IS NULL OR NEW.lease_generation IS DISTINCT FROM OLD.lease_generation
       OR OLD.leased_until IS NULL OR OLD.leased_until<=clock_timestamp()
       OR NEW.consumer_contract_version IS DISTINCT FROM OLD.consumer_contract_version) THEN
        RAISE EXCEPTION 'terminal recalculation receipt requires its current fenced claim' USING ERRCODE='MO105';
    END IF;
    IF NEW.lease_generation IS NOT NULL THEN
        IF (NEW.state='RUNNING') <> (NEW.leased_until IS NOT NULL) THEN
            RAISE EXCEPTION 'recalculation lease shape mismatch' USING ERRCODE='MO105';
        END IF;
        IF NEW.state='FINISHED' AND (NEW.consumer_contract_version IS DISTINCT FROM 1
           OR NEW.measurement_result_ids IS NULL OR NEW.binding_assessed_count IS NULL
           OR NEW.binding_invalidated_count IS NULL OR NEW.outcome_assessed_count IS NULL
           OR NEW.outcome_result_ids IS NULL) THEN
            RAISE EXCEPTION 'recalculation requires its complete consumer receipt' USING ERRCODE='MO105';
        END IF;
        IF NEW.state='FINISHED' AND NOT EXISTS (
            SELECT 1 FROM mart.lc_listing_health h
            JOIN mart.calculation_run r ON r.id=h.calculation_run_id
            WHERE h.id=NEW.health_result_id AND h.calculation_run_id=NEW.calculation_run_id
              AND h.organization_id=NEW.organization_id AND h.platform_listing_id=NEW.platform_listing_id
              AND r.organization_id=NEW.organization_id AND r.state='SUCCEEDED'
              AND h.computed_at>=NEW.started_at AND h.computed_at<=NEW.finished_at
        ) THEN
            RAISE EXCEPTION 'recalculation requires its actual listing result and calculation run' USING ERRCODE='MO105';
        END IF;
        IF NEW.state='FINISHED' THEN
            IF EXISTS (SELECT 1 FROM unnest(NEW.measurement_result_ids) result_id
                       WHERE NOT EXISTS (SELECT 1 FROM mart.lc_conversion_measurement m
                         WHERE m.id=result_id AND m.organization_id=NEW.organization_id
                           AND m.platform_listing_id=NEW.platform_listing_id
                           AND m.computed_at>=NEW.started_at AND m.computed_at<=NEW.finished_at)) THEN
                RAISE EXCEPTION 'recalculation measurement receipt is outside its fenced run' USING ERRCODE='MO105';
            END IF;
            IF EXISTS (SELECT 1 FROM unnest(NEW.outcome_result_ids) result_id
                       WHERE NOT EXISTS (SELECT 1 FROM ops.lc_node_result r
                         JOIN ops.lc_evaluation_plan p ON p.id=r.plan_id
                         JOIN ops.lc_action a ON a.id=p.action_id
                         WHERE r.id=result_id AND r.organization_id=NEW.organization_id
                           AND a.organization_id=NEW.organization_id
                           AND a.platform_listing_id=NEW.platform_listing_id
                           AND r.evaluated_at>=NEW.started_at AND r.evaluated_at<=NEW.finished_at)) THEN
                RAISE EXCEPTION 'recalculation outcome receipt is outside its fenced run' USING ERRCODE='MO105';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;

GRANT UPDATE (consumer_contract_version,measurement_result_ids,binding_assessed_count,binding_invalidated_count,
    outcome_assessed_count,outcome_result_ids)
    ON ops.lc_recalculation_queue TO marketops_app;

-- Existing installations begin one current sweep at installation time.  New
-- listings become due sixty minutes after their own creation; later sweeps retain
-- a sixty-minute accepted cadence, so processing time cannot extend the interval.
INSERT INTO ops.lc_recalculation_queue(id,organization_id,platform_listing_id,trigger_class,target_minutes,
    trigger_reference,source_time,accepted_at,state)
SELECT gen_random_uuid(),l.organization_id,l.id,'FULL_REVIEW',60,
       'full-review:initial:'||l.id::text,clock_timestamp(),clock_timestamp(),'QUEUED'
FROM core.platform_listing l
WHERE l.status='OBSERVED'
  AND NOT EXISTS (SELECT 1 FROM ops.lc_recalculation_queue q
      WHERE q.platform_listing_id=l.id AND q.trigger_class='FULL_REVIEW'
        AND q.state IN ('QUEUED','RUNNING'));

-- ---------------------------------------------------------------------------
-- Canonical source changes fan out to the sole Listing recalculation queue
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.lc_enqueue_source_recalculation(p_org uuid,p_listing uuid,p_class text,
    p_reference text,p_source_time timestamptz) RETURNS uuid
LANGUAGE plpgsql SET search_path=pg_catalog,ops,core,mart AS $$
DECLARE queued uuid;
BEGIN
    IF p_class NOT IN ('RISK','ORDINARY') OR NOT EXISTS (
        SELECT 1 FROM core.platform_listing l
        WHERE l.id=p_listing AND l.organization_id=p_org AND l.status='OBSERVED') THEN
        RETURN NULL;
    END IF;
    SELECT q.id INTO queued FROM ops.lc_recalculation_queue q
      WHERE q.organization_id=p_org AND q.platform_listing_id=p_listing
        AND q.trigger_reference=p_reference LIMIT 1;
    IF queued IS NOT NULL THEN RETURN queued; END IF;
    queued:=gen_random_uuid();
    INSERT INTO ops.lc_recalculation_queue(id,organization_id,platform_listing_id,trigger_class,target_minutes,
        trigger_reference,source_time,accepted_at,state)
    VALUES(queued,p_org,p_listing,p_class,CASE p_class WHEN 'RISK' THEN 5 ELSE 15 END,
        left(p_reference,512),p_source_time,clock_timestamp(),'QUEUED');
    RETURN queued;
END $$;

CREATE FUNCTION core.lc_enqueue_variant_source_change() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,core,ops AS $$
DECLARE payload jsonb:=to_jsonb(NEW); listing_id uuid; source_at timestamptz; reference text;
BEGIN
    SELECT v.platform_listing_id INTO listing_id FROM core.platform_listing_variant v
      WHERE v.id=(payload->>'platform_listing_variant_id')::uuid AND v.organization_id=NEW.organization_id;
    IF listing_id IS NULL THEN RETURN NEW; END IF;
    source_at:=CASE WHEN nullif(payload->>TG_ARGV[1],'') IS NULL THEN NULL
                    ELSE (payload->>TG_ARGV[1])::timestamptz END;
    reference:=TG_TABLE_SCHEMA||'.'||TG_TABLE_NAME||':'||(payload->>'id');
    IF TG_OP='UPDATE' THEN
        reference:=reference||':v:'||coalesce(payload->>'version',payload->>'updated_at',payload->>'status','changed');
    END IF;
    PERFORM ops.lc_enqueue_source_recalculation(NEW.organization_id,listing_id,TG_ARGV[0],reference,source_at);
    RETURN NEW;
END $$;

-- Append-only canonical fact loaders commonly accept a whole provider page in
-- one INSERT.  Collapse that statement to one deterministic request per Listing
-- while retaining the exact fact reference for the single-row intake path.
CREATE FUNCTION core.lc_enqueue_variant_fact_batch() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,core,ops AS $$
DECLARE table_reference text:=TG_TABLE_SCHEMA||'.'||TG_TABLE_NAME;
BEGIN
    EXECUTE format($statement$
        WITH affected AS (
            SELECT fact.organization_id,listing.id AS platform_listing_id,
                   count(*) AS fact_count,min(fact.id::text) AS first_fact_id,
                   min(fact.%1$I) AS source_time,
                   encode(sha256(convert_to(string_agg(fact.id::text,',' ORDER BY fact.id::text),'UTF8')),'hex')
                       AS fact_digest
            FROM lc_inserted_facts fact
            JOIN core.platform_listing_variant variant
              ON variant.id=fact.platform_listing_variant_id
             AND variant.organization_id=fact.organization_id
            JOIN core.platform_listing listing
              ON listing.id=variant.platform_listing_id
             AND listing.organization_id=fact.organization_id
             AND listing.status='OBSERVED'
            GROUP BY fact.organization_id,listing.id
        ), requests AS (
            SELECT affected.*,
                   CASE WHEN fact_count=1 THEN $1||':'||first_fact_id
                        ELSE $1||':batch:'||fact_count::text||':'||fact_digest END AS trigger_reference
            FROM affected
        )
        INSERT INTO ops.lc_recalculation_queue(id,organization_id,platform_listing_id,trigger_class,target_minutes,
            trigger_reference,source_time,accepted_at,state)
        SELECT gen_random_uuid(),request.organization_id,request.platform_listing_id,$2,
               CASE $2 WHEN 'RISK' THEN 5 ELSE 15 END,left(request.trigger_reference,512),
               request.source_time,clock_timestamp(),'QUEUED'
        FROM requests request
        WHERE NOT EXISTS (
            SELECT 1 FROM ops.lc_recalculation_queue queued
            WHERE queued.organization_id=request.organization_id
              AND queued.platform_listing_id=request.platform_listing_id
              AND queued.trigger_reference=request.trigger_reference)
        $statement$,TG_ARGV[1]) USING table_reference,TG_ARGV[0];
    RETURN NULL;
END $$;

CREATE FUNCTION core.lc_enqueue_listing_source_change() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,core,ops AS $$
DECLARE payload jsonb:=to_jsonb(NEW); source_at timestamptz; reference text;
BEGIN
    source_at:=CASE WHEN nullif(payload->>TG_ARGV[1],'') IS NULL THEN NULL
                    ELSE (payload->>TG_ARGV[1])::timestamptz END;
    reference:=TG_TABLE_SCHEMA||'.'||TG_TABLE_NAME||':'||(payload->>'id');
    IF TG_OP='UPDATE' THEN
        reference:=reference||':v:'||coalesce(payload->>'version',payload->>'updated_at',payload->>'status','changed');
    END IF;
    PERFORM ops.lc_enqueue_source_recalculation(NEW.organization_id,
        (payload->>'platform_listing_id')::uuid,TG_ARGV[0],reference,source_at);
    RETURN NEW;
END $$;

CREATE FUNCTION core.lc_enqueue_internal_variant_source_change() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,core,ops AS $$
DECLARE listing record; payload jsonb:=to_jsonb(NEW); source_at timestamptz;
BEGIN
    source_at:=CASE WHEN nullif(payload->>TG_ARGV[1],'') IS NULL THEN NULL
                    ELSE (payload->>TG_ARGV[1])::timestamptz END;
    FOR listing IN SELECT DISTINCT v.platform_listing_id
        FROM core.listing_mapping m JOIN core.platform_listing_variant v
          ON v.id=m.platform_listing_variant_id AND v.organization_id=m.organization_id
        WHERE m.organization_id=NEW.organization_id
          AND m.product_variant_id=(payload->>'product_variant_id')::uuid
          AND m.status='ACTIVE' AND v.status='OBSERVED'
    LOOP
        PERFORM ops.lc_enqueue_source_recalculation(NEW.organization_id,listing.platform_listing_id,TG_ARGV[0],
          TG_TABLE_SCHEMA||'.'||TG_TABLE_NAME||':'||(payload->>'id')||':listing:'||listing.platform_listing_id::text,source_at);
    END LOOP;
    RETURN NEW;
END $$;

CREATE FUNCTION core.lc_enqueue_finance_input_change() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,core,ops AS $$
DECLARE listing record; payload jsonb:=to_jsonb(NEW);
BEGIN
    FOR listing IN
      SELECT DISTINCT l.id
      FROM core.platform_listing l LEFT JOIN core.platform_listing_variant v ON v.platform_listing_id=l.id
      LEFT JOIN core.listing_mapping m ON m.platform_listing_variant_id=v.id AND m.status='ACTIVE'
      WHERE l.organization_id=NEW.organization_id AND l.status='OBSERVED'
        AND (NEW.scope_kind='ORGANIZATION'
          OR (NEW.scope_kind='STORE' AND l.store_id=NEW.store_ref_id)
          OR (NEW.scope_kind='PRODUCT_VARIANT' AND m.product_variant_id=NEW.product_variant_ref_id))
    LOOP
      PERFORM ops.lc_enqueue_source_recalculation(NEW.organization_id,listing.id,'RISK',
        'core.finance_input_version:'||NEW.id::text||':v:'||NEW.version::text||':listing:'||listing.id::text,
        NEW.effective_from);
    END LOOP;
    RETURN NEW;
END $$;

CREATE FUNCTION core.lc_enqueue_feedback_classification() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,core,ops AS $$
DECLARE item core.lc_feedback_item%ROWTYPE;
BEGIN
    SELECT * INTO item FROM core.lc_feedback_item WHERE id=NEW.feedback_item_id;
    PERFORM ops.lc_enqueue_source_recalculation(NEW.organization_id,item.platform_listing_id,'ORDINARY',
        'mart.lc_feedback_classification:'||NEW.id::text,NEW.classified_at);
    RETURN NEW;
END $$;

CREATE TRIGGER lc_platform_listing_variant_changed AFTER INSERT OR UPDATE ON core.platform_listing_variant
    FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_listing_source_change('RISK','updated_at');
CREATE TRIGGER lc_listing_mapping_changed AFTER INSERT OR UPDATE ON core.listing_mapping
    FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_variant_source_change('RISK','updated_at');
CREATE TRIGGER lc_mapping_conflict_changed AFTER INSERT OR UPDATE ON core.mapping_conflict
    FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_variant_source_change('RISK','updated_at');
CREATE TRIGGER lc_listing_health_fact_accepted AFTER INSERT ON core.listing_health_observation
    REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT
    EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK','observed_at');
CREATE TRIGGER lc_listing_price_fact_accepted AFTER INSERT ON core.listing_price_observation
    REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT
    EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK','observed_at');
CREATE TRIGGER lc_listing_stock_fact_accepted AFTER INSERT ON core.listing_stock_observation
    REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT
    EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK','observed_at');
CREATE TRIGGER lc_listing_traffic_fact_accepted AFTER INSERT ON core.listing_traffic_observation
    REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT
    EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('ORDINARY','period_end');
CREATE TRIGGER lc_listing_sales_fact_accepted AFTER INSERT ON ledger.sales_fact
    REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT
    EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('ORDINARY','occurred_at');
CREATE TRIGGER lc_listing_return_fact_accepted AFTER INSERT ON ledger.return_fact
    REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT
    EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK','occurred_at');
CREATE TRIGGER lc_listing_fee_fact_accepted AFTER INSERT ON ledger.finance_fee_fact
    REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT
    EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK','occurred_at');
CREATE TRIGGER lc_listing_ad_spend_fact_accepted AFTER INSERT ON ledger.ad_spend_fact
    REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT
    EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('ORDINARY','period_end');
CREATE TRIGGER lc_listing_internal_stock_accepted AFTER INSERT ON core.internal_stock_snapshot
    FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_internal_variant_source_change('RISK','observed_at');
CREATE TRIGGER lc_listing_cost_changed AFTER INSERT OR UPDATE ON core.cost_version
    FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_internal_variant_source_change('RISK','effective_from');
CREATE TRIGGER lc_listing_finance_input_changed AFTER INSERT OR UPDATE ON core.finance_input_version
    FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_finance_input_change();
CREATE TRIGGER lc_listing_feedback_item_accepted AFTER INSERT ON core.lc_feedback_item
    FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_listing_source_change('ORDINARY','observed_at');
CREATE TRIGGER lc_listing_feedback_classified AFTER INSERT ON mart.lc_feedback_classification
    FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_feedback_classification();

GRANT EXECUTE ON FUNCTION ops.lc_enqueue_source_recalculation(uuid,uuid,text,text,timestamptz),
    core.lc_enqueue_variant_source_change(),core.lc_enqueue_variant_fact_batch(),
    core.lc_enqueue_listing_source_change(),
    core.lc_enqueue_internal_variant_source_change(),core.lc_enqueue_finance_input_change(),
    core.lc_enqueue_feedback_classification() TO marketops_app;

-- ---------------------------------------------------------------------------
-- Necessary-risk and qualified-opportunity work reuse the original Task
-- ---------------------------------------------------------------------------

ALTER TABLE ops.lc_task_responsibility DROP CONSTRAINT lc_task_diagnostic_identity;
ALTER TABLE ops.lc_task_responsibility DROP CONSTRAINT lc_task_diagnostic_clock_kind;
ALTER TABLE ops.lc_task_responsibility ADD COLUMN responsibility_lane text GENERATED ALWAYS AS (
    CASE WHEN source_health_id IS NULL THEN 'ACTION'
         WHEN cause_code IN ('AFFECTED_SET_COMPLETE','MAPPING_RESOLVED','DESCRIPTION_OBSERVED',
              'NOT_CONTAINED','CALIBRATION_RESOLVED') THEN 'NECESSARY_RISK'
         ELSE 'QUALIFIED_OPPORTUNITY' END) STORED;
ALTER TABLE ops.lc_task_responsibility ADD CONSTRAINT lc_task_diagnostic_identity CHECK (
    (source_health_id IS NULL AND platform_listing_id IS NULL AND cause_code IS NULL AND recommendation_id IS NOT NULL)
    OR (source_health_id IS NOT NULL AND platform_listing_id IS NOT NULL AND recommendation_id IS NULL
        AND cause_code IN ('AFFECTED_SET_COMPLETE','MAPPING_RESOLVED','DESCRIPTION_OBSERVED','NOT_CONTAINED',
            'CALIBRATION_RESOLVED','DESCRIPTION_NOT_RUSSIAN','KIZ_MARKING_UNDECLARED',
            'SOURCE_STRATIFICATION_MISSING','NOT_SELLABLE_AT_LAST_OBSERVATION','FEEDBACK_THEMES_PRESENT')));
ALTER TABLE ops.lc_task_responsibility ADD CONSTRAINT lc_task_diagnostic_clock_kind CHECK (
    (responsibility_lane='ACTION' AND clock_state<>'CONTINUOUS_RISK')
    OR (responsibility_lane='NECESSARY_RISK' AND clock_state IN ('SLO_UNRESOLVED','CONTINUOUS_RISK'))
    OR (responsibility_lane='QUALIFIED_OPPORTUNITY' AND clock_state IN
        ('SLO_UNRESOLVED','COVERAGE_UNRESOLVED','COVERAGE_CONFIGURED')));

CREATE OR REPLACE FUNCTION ops.lc_task_responsibility_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,core AS $$
DECLARE task ops.work_task%ROWTYPE; action ops.lc_action%ROWTYPE; health mart.lc_listing_health%ROWTYPE;
        expected_slo jsonb; expected_coverage jsonb; opportunity boolean;
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Listing responsibility origins and policy snapshots are immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO task FROM ops.work_task WHERE id=NEW.task_id;
    IF task.id IS NULL OR task.organization_id<>NEW.organization_id
       OR task.recommendation_id IS DISTINCT FROM NEW.recommendation_id OR task.created_at<>NEW.first_raised_at
       OR task.due_at IS DISTINCT FROM NEW.action_due_at THEN
        RAISE EXCEPTION 'Listing responsibility does not match its original Task' USING ERRCODE='23514';
    END IF;
    IF NEW.source_health_id IS NULL THEN
        SELECT * INTO action FROM ops.lc_action WHERE recommendation_id=NEW.recommendation_id;
        IF action.id IS NULL OR action.organization_id<>NEW.organization_id
           OR action.calibration_package_id IS DISTINCT FROM NEW.calibration_package_id
           OR action.calibration_version IS DISTINCT FROM NEW.calibration_version THEN
            RAISE EXCEPTION 'Listing responsibility does not match its original Action' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT * INTO health FROM mart.lc_listing_health WHERE id=NEW.source_health_id;
        opportunity:=NEW.cause_code IN ('DESCRIPTION_NOT_RUSSIAN','KIZ_MARKING_UNDECLARED',
            'SOURCE_STRATIFICATION_MISSING','NOT_SELLABLE_AT_LAST_OBSERVATION','FEEDBACK_THEMES_PRESENT');
        IF health.id IS NULL OR health.organization_id<>NEW.organization_id
           OR health.platform_listing_id<>NEW.platform_listing_id OR NEW.recommendation_id IS NOT NULL
           OR (NOT opportunity AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(health.necessary_conditions) condition
                 WHERE condition->>'code'=NEW.cause_code AND condition->>'state'='FAIL'))
           OR (opportunity AND (health.necessary_state<>'PASS' OR health.eligibility->>'EVALUATION'<>'ELIGIBLE'
                 OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(health.opportunities) item
                    WHERE item->>'code'=NEW.cause_code)))
           OR EXISTS(SELECT 1 FROM mart.lc_listing_health newer WHERE newer.platform_listing_id=health.platform_listing_id
                AND newer.health_version>health.health_version) THEN
            RAISE EXCEPTION 'Listing responsibility requires its current qualified diagnosis' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM core.platform_listing listing
            CROSS JOIN LATERAL core.lc_resolve_calibration_for(NEW.organization_id,listing.platform_code,
                listing.store_id,health.computed_at,'LISTING_CONVERSION') resolved
            WHERE listing.id=NEW.platform_listing_id
              AND (CASE WHEN resolved.resolution_state='RESOLVED' THEN resolved.package_id END)
                    IS NOT DISTINCT FROM NEW.calibration_package_id
              AND (CASE WHEN resolved.resolution_state='RESOLVED' THEN resolved.package_version END)
                    IS NOT DISTINCT FROM NEW.calibration_version) THEN
            RAISE EXCEPTION 'Listing responsibility calibration identity mismatch' USING ERRCODE='23514';
        END IF;
    END IF;
    SELECT value_json INTO expected_slo FROM core.lc_calibration_value
      WHERE package_id=NEW.calibration_package_id AND category_code='RESPONSIBILITY_SLO';
    SELECT value_json INTO expected_coverage FROM core.lc_calibration_value
      WHERE package_id=NEW.calibration_package_id AND category_code='RESPONSIBILITY_COVERAGE';
    IF jsonb_typeof(expected_slo) IS DISTINCT FROM 'object' THEN expected_slo:='{}'::jsonb; END IF;
    IF jsonb_typeof(expected_coverage) IS DISTINCT FROM 'object' THEN expected_coverage:='{}'::jsonb; END IF;
    IF NEW.slo_snapshot<>expected_slo OR NEW.coverage_snapshot<>expected_coverage THEN
        RAISE EXCEPTION 'Listing responsibility cannot substitute calibration values' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION ops.lc_task_deferral_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops AS $$
DECLARE binding ops.lc_task_responsibility%ROWTYPE; task ops.work_task%ROWTYPE; limit_value jsonb;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Task deferral history cannot be deleted' USING ERRCODE='23514'; END IF;
    SELECT * INTO binding FROM ops.lc_task_responsibility WHERE task_id=NEW.task_id;
    SELECT * INTO task FROM ops.work_task WHERE id=NEW.task_id;
    IF NEW.review_queue_id IS NOT NULL AND (NEW.state<>'EXPIRED' OR NOT EXISTS(
        SELECT 1 FROM ops.lc_recalculation_queue q LEFT JOIN ops.lc_action a ON a.recommendation_id=binding.recommendation_id
        WHERE q.id=NEW.review_queue_id AND q.organization_id=NEW.organization_id
          AND q.platform_listing_id=coalesce(binding.platform_listing_id,a.platform_listing_id)
          AND q.trigger_reference='task-deferral-expired:'||NEW.id::text AND q.source_time=NEW.expires_at)) THEN
        RAISE EXCEPTION 'Expired deferral requires its exact scoped queue request' USING ERRCODE='23514';
    END IF;
    IF NEW.review_health_id IS NOT NULL AND NOT EXISTS(
        SELECT 1 FROM mart.lc_listing_health h LEFT JOIN ops.lc_action a ON a.recommendation_id=binding.recommendation_id
        WHERE h.id=NEW.review_health_id AND h.organization_id=NEW.organization_id
          AND h.platform_listing_id=coalesce(binding.platform_listing_id,a.platform_listing_id)
          AND h.computed_at>=coalesce(NEW.ended_at,NEW.requested_at)) THEN
        RAISE EXCEPTION 'Deferral review must name a subsequent diagnosis in its own scope' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        limit_value:=CASE WHEN binding.responsibility_lane='NECESSARY_RISK'
                         THEN binding.slo_snapshot#>'{necessaryRisk,maximumDeferMinutes}'
                         ELSE binding.slo_snapshot->'maximumDeferMinutes' END;
        IF binding.task_id IS NULL OR binding.organization_id<>NEW.organization_id
           OR task.state NOT IN ('OPEN','ASSIGNED','IN_PROGRESS') OR NEW.state<>'ACTIVE'
           OR NEW.requested_at<binding.first_raised_at OR NEW.requested_at>clock_timestamp()
           OR NEW.expires_at<>NEW.requested_at+make_interval(mins=>NEW.defer_minutes)
           OR NEW.review_health_id IS NOT NULL OR NEW.review_queue_id IS NOT NULL OR NEW.ended_at IS NOT NULL
           OR NEW.basis_digest IS DISTINCT FROM ops.lc_task_reassessment_basis(NEW.task_id)
           OR jsonb_typeof(limit_value) IS DISTINCT FROM 'number' THEN
            RAISE EXCEPTION 'Deferral requires original Task policy and current diagnosis' USING ERRCODE='23514';
        END IF;
        IF limit_value::text !~ '^[0-9]+$' OR (limit_value::text)::numeric<NEW.defer_minutes
           OR (limit_value::text)::numeric>2147483647 THEN
            RAISE EXCEPTION 'Deferral exceeds the declared finite policy' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['state','ended_at','review_health_id','review_queue_id'])
             IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','ended_at','review_health_id','review_queue_id'])
           OR (OLD.state<>'ACTIVE' AND (NEW.state<>OLD.state OR NEW.ended_at IS DISTINCT FROM OLD.ended_at))
           OR (OLD.review_health_id IS NOT NULL AND NEW.review_health_id IS DISTINCT FROM OLD.review_health_id)
           OR (OLD.review_queue_id IS NOT NULL AND NEW.review_queue_id IS DISTINCT FROM OLD.review_queue_id)
           OR (OLD.state='ACTIVE' AND NEW.state='ACTIVE') THEN
            RAISE EXCEPTION 'Deferral origin and finite deadline cannot restart' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

-- ---------------------------------------------------------------------------
-- Finite, evidence-bound dependency holds pause only the Task action stage
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_task_dependency_hold (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES ops.lc_task_responsibility(task_id),
    dependency_task_id uuid NOT NULL REFERENCES ops.work_task(id),
    organization_id uuid NOT NULL,
    requester_user_id uuid NOT NULL,
    hold_minutes integer NOT NULL CHECK (hold_minutes>0),
    evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    collaboration_link_id uuid NOT NULL REFERENCES ops.lc_collaboration_link(id),
    started_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    state text NOT NULL CHECK (state IN ('ACTIVE','RESUMED','EXPIRED','INVALIDATED')),
    ended_at timestamptz,
    end_reason text,
    review_queue_id uuid UNIQUE REFERENCES ops.lc_recalculation_queue(id),
    FOREIGN KEY (requester_user_id,organization_id) REFERENCES iam.user_account(id,organization_id),
    CHECK (task_id<>dependency_task_id AND expires_at>started_at),
    CHECK ((state='ACTIVE')=(ended_at IS NULL)),
    CHECK ((state='ACTIVE')=(end_reason IS NULL)),
    CHECK ((state='ACTIVE')=(review_queue_id IS NULL)),
    CHECK (ended_at IS NULL OR (ended_at>=started_at AND ended_at<=expires_at))
);
CREATE UNIQUE INDEX lc_task_dependency_hold_active ON ops.lc_task_dependency_hold(task_id) WHERE state='ACTIVE';
CREATE INDEX lc_task_dependency_hold_due ON ops.lc_task_dependency_hold(expires_at,id) WHERE state='ACTIVE';

CREATE FUNCTION ops.lc_task_dependency_hold_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops AS $$
DECLARE binding ops.lc_task_responsibility%ROWTYPE; held ops.work_task%ROWTYPE;
        dependency ops.work_task%ROWTYPE; evidence ops.lc_collaboration_link%ROWTYPE;
        limit_value jsonb; used_minutes bigint; listing_id uuid;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Task dependency hold history cannot be deleted' USING ERRCODE='23514';
    END IF;
    SELECT * INTO binding FROM ops.lc_task_responsibility WHERE task_id=NEW.task_id;
    IF TG_OP='INSERT' THEN
        PERFORM id FROM ops.work_task WHERE id IN (NEW.task_id,NEW.dependency_task_id)
          ORDER BY id FOR UPDATE;
        SELECT * INTO held FROM ops.work_task WHERE id=NEW.task_id;
        SELECT * INTO dependency FROM ops.work_task WHERE id=NEW.dependency_task_id;
        SELECT * INTO evidence FROM ops.lc_collaboration_link WHERE id=NEW.collaboration_link_id;
        SELECT coalesce(binding.platform_listing_id,(SELECT a.platform_listing_id FROM ops.lc_action a
          WHERE a.recommendation_id=binding.recommendation_id ORDER BY a.id LIMIT 1)) INTO listing_id;
        limit_value:=CASE WHEN binding.responsibility_lane='NECESSARY_RISK'
            THEN binding.slo_snapshot#>'{necessaryRisk,maximumDeferMinutes}'
            ELSE binding.slo_snapshot->'maximumDeferMinutes' END;
        SELECT coalesce(sum(h.hold_minutes),0) INTO used_minutes
          FROM ops.lc_task_dependency_hold h WHERE h.task_id=NEW.task_id;
        IF binding.task_id IS NULL OR held.organization_id<>NEW.organization_id
           OR dependency.organization_id<>NEW.organization_id
           OR evidence.id IS NULL OR evidence.organization_id<>NEW.organization_id
           OR evidence.platform_listing_id<>listing_id OR evidence.task_id<>NEW.dependency_task_id
           OR evidence.link_kind<>'DEPENDENCY_REEVALUATION'
           OR evidence.evidence_reference<>NEW.evidence_reference
           OR evidence.recorded_at>NEW.started_at
           OR (evidence.source_time IS NOT NULL AND evidence.source_time>NEW.started_at)
           OR held.state NOT IN ('OPEN','ASSIGNED','IN_PROGRESS')
           OR dependency.state NOT IN ('OPEN','ASSIGNED','IN_PROGRESS') OR NEW.state<>'ACTIVE'
           OR NEW.started_at<binding.first_raised_at OR NEW.started_at>clock_timestamp()
           OR NEW.expires_at<>NEW.started_at+make_interval(mins=>NEW.hold_minutes)
           OR binding.action_due_at IS NULL OR NEW.started_at>=binding.action_due_at
           OR NEW.ended_at IS NOT NULL OR NEW.end_reason IS NOT NULL OR NEW.review_queue_id IS NOT NULL
           OR jsonb_typeof(limit_value) IS DISTINCT FROM 'number'
           OR limit_value::text !~ '^[0-9]+$' OR used_minutes+NEW.hold_minutes>(limit_value::text)::numeric
           OR (limit_value::text)::numeric>2147483647 THEN
            RAISE EXCEPTION 'dependency hold requires open scoped Tasks and the original finite policy' USING ERRCODE='23514';
        END IF;
        IF EXISTS(WITH RECURSIVE path(task_id) AS (
              SELECT NEW.dependency_task_id UNION
              SELECT h.dependency_task_id FROM ops.lc_task_dependency_hold h JOIN path p ON h.task_id=p.task_id
                WHERE h.state='ACTIVE') SELECT 1 FROM path WHERE task_id=NEW.task_id) THEN
            RAISE EXCEPTION 'dependency holds cannot create a cycle' USING ERRCODE='23514';
        END IF;
        IF EXISTS(SELECT 1 FROM ops.work_task_event e WHERE e.task_id=NEW.task_id
            AND e.event_kind='ACTION_RECORDED' AND e.occurred_at>=binding.first_raised_at) THEN
            RAISE EXCEPTION 'completed action work cannot be paused afterwards' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT coalesce(binding.platform_listing_id,(SELECT a.platform_listing_id FROM ops.lc_action a
          WHERE a.recommendation_id=binding.recommendation_id ORDER BY a.id LIMIT 1)) INTO listing_id;
        IF NEW.review_queue_id IS NULL OR NOT EXISTS(
              SELECT 1 FROM ops.lc_recalculation_queue q WHERE q.id=NEW.review_queue_id
                AND q.organization_id=NEW.organization_id AND q.platform_listing_id=listing_id
                AND q.trigger_class=CASE WHEN binding.responsibility_lane='NECESSARY_RISK' THEN 'RISK' ELSE 'ORDINARY' END
                AND q.trigger_reference='task-dependency-hold:'||NEW.id::text||':'||lower(NEW.state)
                AND q.source_time=NEW.ended_at)
           OR (to_jsonb(NEW)-ARRAY['state','ended_at','end_reason','review_queue_id']) IS DISTINCT FROM
             (to_jsonb(OLD)-ARRAY['state','ended_at','end_reason','review_queue_id'])
           OR OLD.state<>'ACTIVE' OR NEW.state='ACTIVE' OR NEW.ended_at IS NULL OR NEW.end_reason IS NULL THEN
            RAISE EXCEPTION 'dependency hold origin and finite deadline are immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lc_task_dependency_hold_guard BEFORE INSERT OR UPDATE OR DELETE ON ops.lc_task_dependency_hold
    FOR EACH ROW EXECUTE FUNCTION ops.lc_task_dependency_hold_guard();
GRANT SELECT,INSERT,UPDATE ON ops.lc_task_dependency_hold TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.lc_task_dependency_hold_guard() TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES('ops','lc_task_dependency_hold','NO_ROUTE',NULL,
    'finite evidence-bound pause of an existing Task action clock; no provider, notification, or write authority');

ALTER TABLE ops.work_task_event DROP CONSTRAINT work_task_event_kind_ck;
ALTER TABLE ops.work_task_event ADD CONSTRAINT work_task_event_kind_ck CHECK (event_kind IN (
    'RAISED','VIEWED','ACKNOWLEDGED','ASSIGNED','REASSIGNED','ACTION_RECORDED','OUTCOME_OBSERVED',
    'REOPENED','ESCALATED','COMPLETED','CANCELLED','EXECUTION_OBSERVED','DEFERRED','REASSESSMENT_REQUIRED',
    'DEPENDENCY_HOLD_STARTED','DEPENDENCY_RESUMED','DEPENDENCY_HOLD_EXPIRED','DEPENDENCY_INVALIDATED',
    'EXECUTION_PENDING','EXECUTION_FAILED','EXECUTION_COMPENSATED',
    'QUALIFICATION_INVALIDATED'));

CREATE UNIQUE INDEX work_task_listing_command_state_uq ON ops.work_task_event(correlation_id)
    WHERE correlation_id LIKE 'lc-command-state:%';
CREATE FUNCTION ops.lock_lc_command_task_deliveries(p_limit integer)
RETURNS TABLE(command_id uuid,organization_id uuid,task_id uuid,recommendation_id uuid,
    command_state text,failure_code text,recorded_at timestamptz)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,ops AS $$
    SELECT c.id,c.organization_id,t.id,a.recommendation_id,c.state,c.failure_code,c.updated_at
    FROM ops.lc_description_command c JOIN ops.lc_action a ON a.id=c.action_id
    JOIN LATERAL (SELECT w.id FROM ops.work_task w
      WHERE w.organization_id=c.organization_id AND w.recommendation_id=a.recommendation_id
      ORDER BY w.id LIMIT 1) t ON true
    WHERE c.state<>'READBACK_MATCHED'
      AND NOT EXISTS(SELECT 1 FROM ops.work_task_event e
        WHERE e.correlation_id='lc-command-state:'||c.id::text||':'||c.state)
    ORDER BY c.updated_at,c.id LIMIT least(greatest(p_limit,0),100)
    FOR UPDATE OF c SKIP LOCKED
$$;
REVOKE ALL ON FUNCTION ops.lock_lc_command_task_deliveries(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lock_lc_command_task_deliveries(integer) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Evidence-stage and target-scope bound experience application
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_experience_application (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    source_action_id uuid NOT NULL REFERENCES ops.lc_action(id),
    source_result_id uuid NOT NULL REFERENCES ops.lc_node_result(id),
    target_listing_id uuid NOT NULL,
    target_affected_set_digest text NOT NULL CHECK (target_affected_set_digest~'^[0-9a-f]{64}$'),
    candidate_kind text NOT NULL CHECK (candidate_kind IN
        ('CONTENT_DESCRIPTION','OFFICIAL_PROMOTION_PARTICIPATION','SELLER_DIRECT_DISCOUNT')),
    applicability_evidence_reference text NOT NULL CHECK (
        length(btrim(applicability_evidence_reference)) BETWEEN 1 AND 512),
    recorded_by_user_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    FOREIGN KEY(target_listing_id,organization_id) REFERENCES core.platform_listing(id,organization_id),
    FOREIGN KEY(recorded_by_user_id,organization_id) REFERENCES iam.user_account(id,organization_id),
    UNIQUE(source_result_id,target_listing_id,candidate_kind,target_affected_set_digest,applicability_evidence_reference)
);

CREATE FUNCTION ops.lc_experience_application_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,core,mart AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'experience application evidence is append-only' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM ops.lc_action a JOIN ops.lc_evaluation_plan p ON p.action_id=a.id
        JOIN ops.lc_node_result r ON r.plan_id=p.id
        WHERE a.id=NEW.source_action_id AND r.id=NEW.source_result_id
          AND a.organization_id=NEW.organization_id AND r.organization_id=NEW.organization_id)
       OR NOT EXISTS(SELECT 1 FROM mart.lc_listing_health h JOIN core.lc_affected_set s ON s.id=h.affected_set_id
          WHERE h.platform_listing_id=NEW.target_listing_id AND s.affected_set_digest=NEW.target_affected_set_digest
            AND h.health_version=(SELECT max(x.health_version) FROM mart.lc_listing_health x
                                  WHERE x.platform_listing_id=NEW.target_listing_id)) THEN
        RAISE EXCEPTION 'experience application requires exact source result and current target scope' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lc_experience_application_guard BEFORE INSERT OR UPDATE OR DELETE ON ops.lc_experience_application
    FOR EACH ROW EXECUTE FUNCTION ops.lc_experience_application_guard();
GRANT SELECT,INSERT ON ops.lc_experience_application TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.lc_experience_application_guard() TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES('ops','lc_experience_application','NO_ROUTE',NULL,
    'candidate-preparation knowledge reference; never copies effect, approval, authority, or provider write');
