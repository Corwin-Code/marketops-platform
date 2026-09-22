-- Necessary diagnostic work uses the existing sole Task and original-clock binding.
-- It does not require, create or authorize a Listing Action.
ALTER TABLE ops.work_task ALTER COLUMN recommendation_id DROP NOT NULL;
ALTER TABLE ops.lc_task_responsibility
    ALTER COLUMN recommendation_id DROP NOT NULL,
    ADD COLUMN source_health_id uuid REFERENCES mart.lc_listing_health(id),
    ADD COLUMN platform_listing_id uuid REFERENCES core.platform_listing(id),
    ADD COLUMN cause_code text,
    ADD CONSTRAINT lc_task_diagnostic_identity CHECK (
      (source_health_id IS NULL AND platform_listing_id IS NULL AND cause_code IS NULL AND recommendation_id IS NOT NULL)
      OR (source_health_id IS NOT NULL AND platform_listing_id IS NOT NULL
          AND cause_code IN ('MAPPING_RESOLVED','NOT_CONTAINED'))),
    ADD CONSTRAINT lc_task_diagnostic_cause UNIQUE (organization_id,platform_listing_id,cause_code);
ALTER TABLE ops.lc_task_responsibility ADD CONSTRAINT lc_task_diagnostic_clock_kind
    CHECK ((source_health_id IS NULL AND clock_state<>'CONTINUOUS_RISK')
      OR (source_health_id IS NOT NULL AND clock_state IN ('SLO_UNRESOLVED','CONTINUOUS_RISK')));
-- A Task without a proposal must acquire its exact retained diagnostic binding
-- in the creating transaction. This is not a general unowned Task escape hatch.
CREATE FUNCTION ops.lc_diagnostic_task_reference_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM ops.work_task t WHERE t.id=NEW.id AND t.recommendation_id IS NULL)
       AND NOT EXISTS(SELECT 1 FROM ops.lc_task_responsibility b WHERE b.task_id=NEW.id
         AND b.source_health_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Task requires its proposal or exact retained Listing diagnosis' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER lc_diagnostic_task_reference_guard AFTER INSERT OR UPDATE ON ops.work_task
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ops.lc_diagnostic_task_reference_guard();
ALTER TABLE ops.lc_task_responsibility DROP CONSTRAINT lc_task_responsibility_clock_state_check;
ALTER TABLE ops.lc_task_responsibility ADD CONSTRAINT lc_task_responsibility_clock_state_check
    CHECK (clock_state IN ('SLO_UNRESOLVED','COVERAGE_UNRESOLVED','COVERAGE_CONFIGURED','CONTINUOUS_RISK'));
-- This check's generated name is resolved by its definition, not installation order.
DO $$ DECLARE constraint_name text; BEGIN
    SELECT conname INTO STRICT constraint_name FROM pg_constraint
      WHERE conrelid='ops.lc_task_responsibility'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%acknowledgement_due_at IS NOT NULL%action_due_at IS NOT NULL%';
    EXECUTE format('ALTER TABLE ops.lc_task_responsibility DROP CONSTRAINT %I',constraint_name);
END $$;
ALTER TABLE ops.lc_task_responsibility ADD CONSTRAINT lc_task_responsibility_deadline_state
    CHECK ((clock_state IN ('COVERAGE_CONFIGURED','CONTINUOUS_RISK')) =
        (acknowledgement_due_at IS NOT NULL AND action_due_at IS NOT NULL));

CREATE OR REPLACE FUNCTION ops.lc_task_responsibility_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,core AS $$
DECLARE task ops.work_task%ROWTYPE; action ops.lc_action%ROWTYPE; health mart.lc_listing_health%ROWTYPE;
        expected_slo jsonb; expected_coverage jsonb;
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
        IF health.id IS NULL OR health.organization_id<>NEW.organization_id
           OR health.platform_listing_id<>NEW.platform_listing_id
           OR NEW.recommendation_id IS NOT NULL
           OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(health.necessary_conditions) condition
               WHERE condition->>'code'=NEW.cause_code AND condition->>'state'='FAIL')
           OR EXISTS(SELECT 1 FROM mart.lc_listing_health newer WHERE newer.platform_listing_id=health.platform_listing_id
               AND newer.health_version>health.health_version) THEN
            RAISE EXCEPTION 'Risk responsibility requires the current retained failed diagnosis' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM core.platform_listing listing
            CROSS JOIN LATERAL core.lc_resolve_calibration_for(NEW.organization_id,listing.platform_code,
                listing.store_id,health.computed_at,'LISTING_CONVERSION') resolved
            WHERE listing.id=NEW.platform_listing_id
              AND (CASE WHEN resolved.resolution_state='RESOLVED' THEN resolved.package_id END)
                    IS NOT DISTINCT FROM NEW.calibration_package_id
              AND (CASE WHEN resolved.resolution_state='RESOLVED' THEN resolved.package_version END)
                    IS NOT DISTINCT FROM NEW.calibration_version) THEN
            RAISE EXCEPTION 'Risk responsibility calibration identity mismatch' USING ERRCODE='23514';
        END IF;
    END IF;
    SELECT value_json INTO expected_slo FROM core.lc_calibration_value
      WHERE package_id=NEW.calibration_package_id AND category_code='RESPONSIBILITY_SLO';
    SELECT value_json INTO expected_coverage FROM core.lc_calibration_value
      WHERE package_id=NEW.calibration_package_id AND category_code='RESPONSIBILITY_COVERAGE';
    -- Non-object legacy values stay explicitly unresolved, never a fabricated schedule.
    IF jsonb_typeof(expected_slo) IS DISTINCT FROM 'object' THEN expected_slo:='{}'::jsonb; END IF;
    IF jsonb_typeof(expected_coverage) IS DISTINCT FROM 'object' THEN expected_coverage:='{}'::jsonb; END IF;
    IF NEW.slo_snapshot<>expected_slo OR NEW.coverage_snapshot<>expected_coverage THEN
        RAISE EXCEPTION 'Listing responsibility cannot substitute calibration values' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
