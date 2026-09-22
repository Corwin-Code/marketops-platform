-- SLICE-V1-004: formal evaluation nodes and their results, late-fact revisions,
-- promotion simulation records, late association of unassociated changes, and
-- the recalculation queue with its per-class internal targets.
--
-- A node result carries the primary ratio, its conservative bound, the
-- accepted threshold and a verdict, together with the protection vector. No
-- protection compensates another: the vector verdict is derived by a trigger
-- and is FAIL if any protection failed, UNDETERMINED if any is undetermined,
-- PASS only when every protection passed. An unmet primary target never
-- becomes a failed protection, and "no harm proven" is not a pass. Operational
-- and Settled stages are separate rows; a late fact appends a revision under
-- the original plan and the original conclusion stays readable.
--
-- Forward-only: new tables and functions only.

-- ---------------------------------------------------------------------------
-- Node results and revisions
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_node_result (
    id                  uuid           NOT NULL,
    organization_id     uuid           NOT NULL,
    plan_id             uuid           NOT NULL,
    node_code           text           NOT NULL,
    stage               text           NOT NULL,
    revision_no         integer        NOT NULL DEFAULT 0,
    measurement_id      uuid,
    calculation_run_id  uuid           NOT NULL,
    primary_ratio       numeric(9, 6),
    conservative_bound  numeric(9, 6),
    accepted_threshold  numeric(9, 6)  NOT NULL,
    verdict             text           NOT NULL,
    protection_vector   jsonb          NOT NULL,
    protection_verdict  text           NOT NULL,
    stop_triggered      boolean        NOT NULL,
    maturity_reached    boolean        NOT NULL,
    source_time         timestamptz,
    evaluated_at        timestamptz    NOT NULL,
    CONSTRAINT lc_node_result_pk PRIMARY KEY (id),
    CONSTRAINT lc_node_result_plan_fk FOREIGN KEY (plan_id) REFERENCES ops.lc_evaluation_plan (id),
    CONSTRAINT lc_node_result_measurement_fk
        FOREIGN KEY (measurement_id) REFERENCES mart.lc_conversion_measurement (id),
    CONSTRAINT lc_node_result_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run (id),
    CONSTRAINT lc_node_result_uq UNIQUE (plan_id, node_code, stage, revision_no),
    CONSTRAINT lc_node_result_node_ck CHECK (node_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT lc_node_result_stage_ck CHECK (stage IN ('OPERATIONAL', 'SETTLED')),
    CONSTRAINT lc_node_result_revision_ck CHECK (revision_no >= 0),
    CONSTRAINT lc_node_result_ratio_ck
        CHECK ((primary_ratio IS NULL OR (primary_ratio >= 0 AND primary_ratio <= 1))
            AND (conservative_bound IS NULL OR (conservative_bound >= 0 AND conservative_bound <= 1))
            AND (conservative_bound IS NULL OR primary_ratio IS NULL OR conservative_bound <= primary_ratio)),
    CONSTRAINT lc_node_result_verdict_ck CHECK (verdict IN ('MET', 'NOT_MET', 'UNDETERMINED')),
    -- MET and NOT_MET need a ratio and a bound; an absent ratio is undetermined,
    -- never a miss.
    CONSTRAINT lc_node_result_verdict_shape_ck
        CHECK ((verdict = 'UNDETERMINED') = (primary_ratio IS NULL OR conservative_bound IS NULL OR NOT maturity_reached)),
    CONSTRAINT lc_node_result_met_ck
        CHECK (verdict <> 'MET' OR conservative_bound >= accepted_threshold),
    CONSTRAINT lc_node_result_not_met_ck
        CHECK (verdict <> 'NOT_MET' OR conservative_bound < accepted_threshold),
    CONSTRAINT lc_node_result_vector_ck
        CHECK (jsonb_typeof(protection_vector) = 'object'
            AND protection_vector ? 'DIRECT_CONTRIBUTION_PROFIT'
            AND protection_vector ? 'LINKED_SCOPE_PROFIT'
            AND protection_vector ? 'OVERALL_RETURN_RATE'
            AND protection_vector ? 'CRITICAL_VARIANT_RETURN'
            AND protection_vector ? 'SUPPLY_COVERAGE'),
    CONSTRAINT lc_node_result_protection_ck
        CHECK (protection_verdict IN ('PASS', 'FAIL', 'UNDETERMINED'))
);

CREATE INDEX lc_node_result_plan_ix ON ops.lc_node_result (plan_id, node_code, stage, revision_no DESC);

-- The protection verdict is derived from the vector and nothing else. A row
-- that names a different verdict is refused rather than corrected.
CREATE FUNCTION ops.lc_protection_verdict_of(p_vector jsonb)
RETURNS text
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_each_text(p_vector) e WHERE e.value NOT IN ('PASS', 'FAIL', 'UNDETERMINED'))
            THEN 'UNDETERMINED'
        WHEN EXISTS (SELECT 1 FROM jsonb_each_text(p_vector) e WHERE e.value = 'FAIL') THEN 'FAIL'
        WHEN EXISTS (SELECT 1 FROM jsonb_each_text(p_vector) e WHERE e.value = 'UNDETERMINED') THEN 'UNDETERMINED'
        ELSE 'PASS' END
$$;
REVOKE ALL ON FUNCTION ops.lc_protection_verdict_of(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_protection_verdict_of(jsonb) TO marketops_app;

CREATE FUNCTION ops.lc_node_result_is_consistent()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    IF NEW.protection_verdict <> ops.lc_protection_verdict_of(NEW.protection_vector) THEN
        RAISE EXCEPTION 'the protection verdict is derived from the vector; % was supplied where % follows',
            NEW.protection_verdict, ops.lc_protection_verdict_of(NEW.protection_vector) USING ERRCODE = 'MO093';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_evaluation_plan p
                    WHERE p.id = NEW.plan_id AND p.organization_id = NEW.organization_id
                      AND EXISTS (SELECT 1 FROM jsonb_array_elements(p.formal_nodes) node
                                   WHERE node ->> 'nodeCode' = NEW.node_code)) THEN
        RAISE EXCEPTION 'a result belongs to a node the frozen plan names' USING ERRCODE = 'MO092';
    END IF;
    IF NEW.revision_no > 0 AND NOT EXISTS (
        SELECT 1 FROM ops.lc_node_result prior
         WHERE prior.plan_id = NEW.plan_id AND prior.node_code = NEW.node_code
           AND prior.stage = NEW.stage AND prior.revision_no = NEW.revision_no - 1) THEN
        RAISE EXCEPTION 'a revision follows the result it revises' USING ERRCODE = 'MO092';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_node_result_is_consistent() FROM PUBLIC;
CREATE TRIGGER lc_node_result_is_consistent
    BEFORE INSERT ON ops.lc_node_result
    FOR EACH ROW EXECUTE FUNCTION ops.lc_node_result_is_consistent();

CREATE TABLE ops.lc_outcome_revision (
    id                      uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    plan_id                 uuid        NOT NULL,
    original_result_id      uuid        NOT NULL,
    revised_result_id       uuid        NOT NULL,
    revision_reason         text        NOT NULL,
    late_fact_reference     text        NOT NULL,
    recorded_at             timestamptz NOT NULL,
    CONSTRAINT lc_outcome_revision_pk PRIMARY KEY (id),
    CONSTRAINT lc_outcome_revision_plan_fk FOREIGN KEY (plan_id) REFERENCES ops.lc_evaluation_plan (id),
    CONSTRAINT lc_outcome_revision_original_fk
        FOREIGN KEY (original_result_id) REFERENCES ops.lc_node_result (id),
    CONSTRAINT lc_outcome_revision_revised_fk
        FOREIGN KEY (revised_result_id) REFERENCES ops.lc_node_result (id),
    CONSTRAINT lc_outcome_revision_revised_uq UNIQUE (revised_result_id),
    CONSTRAINT lc_outcome_revision_distinct_ck CHECK (original_result_id <> revised_result_id),
    CONSTRAINT lc_outcome_revision_reason_ck CHECK (revision_reason IN ('LATE_FACT', 'CORRECTION')),
    CONSTRAINT lc_outcome_revision_reference_ck CHECK (length(btrim(late_fact_reference)) BETWEEN 1 AND 512)
);

-- ---------------------------------------------------------------------------
-- Promotion simulation
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_simulation (
    id                        uuid           NOT NULL,
    organization_id           uuid           NOT NULL,
    candidate_id              uuid           NOT NULL,
    calculation_run_id        uuid           NOT NULL,
    scenario_set              jsonb          NOT NULL,
    inputs_digest             text           NOT NULL,
    results                   jsonb          NOT NULL,
    inverse_minimum_quantity  numeric(18, 4),
    inverse_state             text           NOT NULL,
    demand_gate_passed        boolean,
    computed_at               timestamptz    NOT NULL,
    CONSTRAINT lc_simulation_pk PRIMARY KEY (id),
    CONSTRAINT lc_simulation_candidate_fk
        FOREIGN KEY (candidate_id, organization_id) REFERENCES ops.lc_candidate (id, organization_id),
    CONSTRAINT lc_simulation_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run (id),
    CONSTRAINT lc_simulation_scenarios_ck CHECK (jsonb_typeof(scenario_set) = 'array'),
    CONSTRAINT lc_simulation_digest_ck CHECK (inputs_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT lc_simulation_results_ck CHECK (jsonb_typeof(results) = 'array'),
    -- The inverse answer is a number, NO_SOLUTION or UNDETERMINED; never a
    -- fabricated figure.
    CONSTRAINT lc_simulation_inverse_ck CHECK (inverse_state IN ('COMPUTED', 'NO_SOLUTION', 'UNDETERMINED')),
    CONSTRAINT lc_simulation_inverse_shape_ck
        CHECK ((inverse_state = 'COMPUTED') = (inverse_minimum_quantity IS NOT NULL)),
    CONSTRAINT lc_simulation_quantity_ck
        CHECK (inverse_minimum_quantity IS NULL OR inverse_minimum_quantity >= 0)
);

CREATE INDEX lc_simulation_candidate_ix ON ops.lc_simulation (candidate_id, computed_at DESC);

-- ---------------------------------------------------------------------------
-- Late association of unassociated changes
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_late_association (
    id                      uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    platform_listing_id     uuid        NOT NULL,
    observation_id          uuid        NOT NULL,
    action_id               uuid,
    association_kind        text        NOT NULL,
    operation_time          timestamptz,
    report_time             timestamptz,
    authority_gap           text,
    forward_disposition     text,
    closure_verification_id uuid,
    state                   text        NOT NULL,
    recorded_by_user_id     uuid        NOT NULL,
    recorded_at             timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    version                 bigint      NOT NULL DEFAULT 0,
    CONSTRAINT lc_late_association_pk PRIMARY KEY (id),
    CONSTRAINT lc_late_association_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_late_association_observation_fk
        FOREIGN KEY (observation_id, organization_id)
        REFERENCES core.lc_description_observation (id, organization_id),
    CONSTRAINT lc_late_association_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_late_association_verification_fk
        FOREIGN KEY (closure_verification_id) REFERENCES ops.lc_manual_verification (id),
    CONSTRAINT lc_late_association_recorder_fk
        FOREIGN KEY (recorded_by_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_late_association_kind_ck
        CHECK (association_kind IN ('LAWFUL_LATE_REPORT', 'UNAUTHORISED_DEVIATION', 'UNRESOLVED_CHANGE')),
    -- A lawful late report links the exact approved action without a new
    -- approval and keeps both times; an unauthorised deviation keeps the real
    -- state, the authority gap and the forward disposition; an unresolved
    -- change stays under verification.
    CONSTRAINT lc_late_association_lawful_ck
        CHECK (association_kind <> 'LAWFUL_LATE_REPORT'
            OR (action_id IS NOT NULL AND operation_time IS NOT NULL AND report_time IS NOT NULL
                AND report_time >= operation_time)),
    CONSTRAINT lc_late_association_deviation_ck
        CHECK (association_kind <> 'UNAUTHORISED_DEVIATION'
            OR (authority_gap IS NOT NULL AND forward_disposition IS NOT NULL)),
    CONSTRAINT lc_late_association_state_ck
        CHECK (state IN ('OPEN', 'LINKED', 'UNDER_VERIFICATION', 'CLOSED')),
    CONSTRAINT lc_late_association_unresolved_ck
        CHECK (association_kind <> 'UNRESOLVED_CHANGE' OR state IN ('UNDER_VERIFICATION', 'CLOSED')),
    -- An unresolved change is closed only by a verification, never by a
    -- resubmission or by text that happens to match.
    CONSTRAINT lc_late_association_closure_ck
        CHECK (association_kind <> 'UNRESOLVED_CHANGE' OR state <> 'CLOSED' OR closure_verification_id IS NOT NULL),
    CONSTRAINT lc_late_association_gap_ck
        CHECK ((authority_gap IS NULL OR length(btrim(authority_gap)) BETWEEN 1 AND 2000)
            AND (forward_disposition IS NULL OR length(btrim(forward_disposition)) BETWEEN 1 AND 2000))
);

CREATE INDEX lc_late_association_listing_ix ON ops.lc_late_association (platform_listing_id, state);

-- A lawful late report links an action that was actually approved and launched
-- on the manual path, and the observation is of that action's target.
CREATE FUNCTION ops.lc_late_association_is_lawful()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE action ops.lc_action%ROWTYPE; observed text;
BEGIN
    IF NEW.association_kind = 'LAWFUL_LATE_REPORT' THEN
        SELECT * INTO action FROM ops.lc_action WHERE id = NEW.action_id;
        IF NOT FOUND OR action.platform_listing_id <> NEW.platform_listing_id
            OR action.execution_path <> 'MANUAL'
            OR NOT EXISTS (SELECT 1 FROM ops.lc_launch l WHERE l.action_id = action.id
                            AND l.launched_at <= NEW.operation_time) THEN
            RAISE EXCEPTION 'a lawful late report names an action launched before the operation'
                USING ERRCODE = 'MO092';
        END IF;
        SELECT o.text_digest INTO observed FROM core.lc_description_observation o WHERE o.id = NEW.observation_id;
        IF observed IS DISTINCT FROM action.target_text_digest THEN
            RAISE EXCEPTION 'a lawful late report is of the exact approved target' USING ERRCODE = 'MO092';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.association_kind <> NEW.association_kind
        AND NOT (OLD.association_kind = 'UNRESOLVED_CHANGE' AND OLD.state = 'UNDER_VERIFICATION') THEN
        RAISE EXCEPTION 'only an unresolved change under verification is reclassified' USING ERRCODE = 'MO091';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_late_association_is_lawful() FROM PUBLIC;
CREATE TRIGGER lc_late_association_is_lawful
    BEFORE INSERT OR UPDATE ON ops.lc_late_association
    FOR EACH ROW EXECUTE FUNCTION ops.lc_late_association_is_lawful();

-- ---------------------------------------------------------------------------
-- Recalculation queue
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_recalculation_queue (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    platform_listing_id uuid        NOT NULL,
    trigger_class       text        NOT NULL,
    target_minutes      integer     NOT NULL,
    trigger_reference   text        NOT NULL,
    source_time         timestamptz,
    accepted_at         timestamptz NOT NULL,
    started_at          timestamptz,
    finished_at         timestamptz,
    state               text        NOT NULL,
    calculation_run_id  uuid,
    failure_code        text,
    CONSTRAINT lc_recalculation_queue_pk PRIMARY KEY (id),
    CONSTRAINT lc_recalculation_queue_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_recalculation_queue_run_fk
        FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run (id),
    CONSTRAINT lc_recalculation_queue_class_ck CHECK (trigger_class IN ('RISK', 'ORDINARY', 'FULL_REVIEW')),
    -- The internal target per class: five, fifteen and sixty minutes.
    CONSTRAINT lc_recalculation_queue_target_ck
        CHECK ((trigger_class = 'RISK' AND target_minutes = 5)
            OR (trigger_class = 'ORDINARY' AND target_minutes = 15)
            OR (trigger_class = 'FULL_REVIEW' AND target_minutes = 60)),
    CONSTRAINT lc_recalculation_queue_reference_ck CHECK (length(btrim(trigger_reference)) BETWEEN 1 AND 512),
    CONSTRAINT lc_recalculation_queue_state_ck CHECK (state IN ('QUEUED', 'RUNNING', 'FINISHED', 'FAILED')),
    CONSTRAINT lc_recalculation_queue_times_ck
        CHECK ((started_at IS NULL OR started_at >= accepted_at)
            AND (finished_at IS NULL OR (started_at IS NOT NULL AND finished_at >= started_at))),
    CONSTRAINT lc_recalculation_queue_state_shape_ck
        CHECK ((state = 'QUEUED' AND started_at IS NULL AND finished_at IS NULL)
            OR (state = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL)
            OR (state IN ('FINISHED', 'FAILED') AND finished_at IS NOT NULL)),
    CONSTRAINT lc_recalculation_queue_failure_ck CHECK ((state = 'FAILED') = (failure_code IS NOT NULL))
);

CREATE INDEX lc_recalculation_queue_open_ix
    ON ops.lc_recalculation_queue (state, trigger_class, accepted_at) WHERE state IN ('QUEUED', 'RUNNING');

-- ---------------------------------------------------------------------------
-- Route inventory and privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'lc_node_result', 'NO_ROUTE', NULL,
        'formal node result with primary ratio, bound, verdict and protection vector; append-only'),
    ('ops', 'lc_outcome_revision', 'NO_ROUTE', NULL,
        'late-fact revision under the original plan; the original stays readable'),
    ('ops', 'lc_simulation', 'NO_ROUTE', NULL,
        'promotion forward scenarios and inverse minimum quantity; append-only'),
    ('ops', 'lc_late_association', 'NO_ROUTE', NULL,
        'lawful late report, unauthorised deviation or unresolved change after adoption'),
    ('ops', 'lc_recalculation_queue', 'NO_ROUTE', NULL,
        'canonical change triggers classed RISK, ORDINARY or FULL_REVIEW with per-class targets');

GRANT SELECT, INSERT ON ops.lc_node_result TO marketops_app;
GRANT SELECT, INSERT ON ops.lc_outcome_revision TO marketops_app;
GRANT SELECT, INSERT ON ops.lc_simulation TO marketops_app;
GRANT SELECT, INSERT,
      UPDATE (association_kind, action_id, operation_time, report_time, authority_gap,
              forward_disposition, closure_verification_id, state, updated_at, version)
    ON ops.lc_late_association TO marketops_app;
GRANT SELECT, INSERT, UPDATE (started_at, finished_at, state, calculation_run_id, failure_code)
    ON ops.lc_recalculation_queue TO marketops_app;
