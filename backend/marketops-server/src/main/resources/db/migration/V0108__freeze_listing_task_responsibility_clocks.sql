-- The existing Task remains the responsibility authority. This immutable binding
-- records which Listing calibration its original ordinary-work clocks consumed.
CREATE TABLE ops.lc_task_responsibility (
    task_id uuid PRIMARY KEY REFERENCES ops.work_task(id),
    organization_id uuid NOT NULL,
    recommendation_id uuid NOT NULL UNIQUE,
    calibration_package_id uuid REFERENCES core.lc_calibration_package(id),
    calibration_version integer,
    first_raised_at timestamptz NOT NULL,
    slo_snapshot jsonb NOT NULL,
    coverage_snapshot jsonb NOT NULL,
    basis_digest text NOT NULL CHECK (basis_digest ~ '^[0-9a-f]{64}$'),
    clock_state text NOT NULL CHECK (clock_state IN ('SLO_UNRESOLVED','COVERAGE_UNRESOLVED','COVERAGE_CONFIGURED')),
    acknowledgement_due_at timestamptz,
    action_due_at timestamptz,
    outcome_maturity_due_at timestamptz,
    recorded_at timestamptz NOT NULL,
    FOREIGN KEY (recommendation_id,organization_id) REFERENCES ops.recommendation(id,organization_id),
    CHECK ((calibration_package_id IS NULL) = (calibration_version IS NULL)),
    CHECK (jsonb_typeof(slo_snapshot)='object' AND jsonb_typeof(coverage_snapshot)='object'),
    CHECK ((clock_state='COVERAGE_CONFIGURED') = (acknowledgement_due_at IS NOT NULL AND action_due_at IS NOT NULL)),
    CHECK (clock_state<>'SLO_UNRESOLVED' OR outcome_maturity_due_at IS NULL),
    CHECK (acknowledgement_due_at IS NULL OR acknowledgement_due_at>first_raised_at),
    CHECK (action_due_at IS NULL OR action_due_at>first_raised_at),
    CHECK (outcome_maturity_due_at IS NULL OR outcome_maturity_due_at>first_raised_at),
    CHECK (recorded_at>=first_raised_at)
);

CREATE FUNCTION ops.lc_task_responsibility_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,core AS $$
DECLARE task ops.work_task%ROWTYPE; action ops.lc_action%ROWTYPE;
        expected_slo jsonb; expected_coverage jsonb;
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Listing responsibility origins and policy snapshots are immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO task FROM ops.work_task WHERE id=NEW.task_id;
    SELECT * INTO action FROM ops.lc_action WHERE recommendation_id=NEW.recommendation_id;
    IF task.id IS NULL OR action.id IS NULL OR task.organization_id<>NEW.organization_id
       OR task.recommendation_id<>NEW.recommendation_id OR action.organization_id<>NEW.organization_id
       OR task.created_at<>NEW.first_raised_at OR task.due_at IS DISTINCT FROM NEW.action_due_at
       OR action.calibration_package_id IS DISTINCT FROM NEW.calibration_package_id
       OR action.calibration_version IS DISTINCT FROM NEW.calibration_version THEN
        RAISE EXCEPTION 'Listing responsibility does not match its original Task and Action' USING ERRCODE='23514';
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
CREATE TRIGGER lc_task_responsibility_guard BEFORE INSERT OR UPDATE OR DELETE ON ops.lc_task_responsibility
FOR EACH ROW EXECUTE FUNCTION ops.lc_task_responsibility_guard();
GRANT SELECT,INSERT ON ops.lc_task_responsibility TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.lc_task_responsibility_guard() TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES ('ops','lc_task_responsibility','NO_ROUTE',NULL,'immutable original Task clock basis; no provider or control-write route');
