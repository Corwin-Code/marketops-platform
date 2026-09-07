-- The Outcome Evaluation Plan, frozen before the write, and what it produced.
--
-- The plan exists to stop one specific thing: deciding after the fact what
-- "worked" meant. Once a bid has moved, every choice about which window to
-- measure, which metric to compare and what counts as an improvement is a
-- choice made by somebody who can already see the answer. So the plan is
-- written before the command is created, its identity is bound into the
-- command's authority, and it is never edited afterwards.
--
-- Two outcomes, not one, and the difference is the whole point of waiting.
-- An Operational outcome is what the numbers look like now: orders placed,
-- spend recorded, traffic seen. A Settled outcome is what survived — after
-- cancellations, after returns, after the marketplace corrected its own
-- reports. In this market the gap between them is routine and large, and a
-- product that reported the first as if it were the second would call a
-- campaign successful on sales that were later returned.
--
-- The early Completed-Sales Guard is the rule that stops that happening
-- automatically. An outcome that rests on sales too recent to have completed
-- cannot be Settled, whatever it looks like, and cannot close a case.
--
-- Forward-only. Nothing existing is altered except the bundle's foreign key,
-- which pointed at a table that did not exist yet.

-- ---------------------------------------------------------------------------
-- The plan
-- ---------------------------------------------------------------------------

CREATE TABLE core.ad_outcome_policy (
    id                            uuid           NOT NULL,
    organization_id               uuid           NOT NULL,
    policy_version                integer        NOT NULL,
    scope_kind                    text           NOT NULL,
    platform_code                 text,
    store_ref_id                  uuid,
    direction                     text           NOT NULL,
    -- The window. Both bounds are relative to the moment the write is proven
    -- to have landed, not to the moment somebody looks.
    observation_starts_minutes    integer        NOT NULL,
    operational_window_hours      integer        NOT NULL,
    settlement_window_hours       integer        NOT NULL,
    -- The guard. A sale younger than this has not had the chance to be
    -- cancelled or returned, so it cannot contribute to a settled claim.
    completed_sales_guard_hours   integer        NOT NULL,
    minimum_settled_coverage_ratio numeric(6, 5) NOT NULL,
    -- What is compared, and against what.
    primary_metric_code           text           NOT NULL,
    comparison_basis              text           NOT NULL,
    improvement_threshold_ratio   numeric(6, 5)  NOT NULL,
    regression_threshold_ratio    numeric(6, 5)  NOT NULL,
    minimum_traffic_count         bigint         NOT NULL,
    owner_user_id                 uuid           NOT NULL,
    reason                        text           NOT NULL,
    evidence_reference            text           NOT NULL,
    effective_from                timestamptz    NOT NULL,
    effective_to                  timestamptz,
    status                        text           NOT NULL,
    created_at                    timestamptz    NOT NULL,
    CONSTRAINT ad_outcome_policy_pk PRIMARY KEY (id),
    CONSTRAINT ad_outcome_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_outcome_policy_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_outcome_policy_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_outcome_policy_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_outcome_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_outcome_policy_version_uq
        UNIQUE (organization_id, direction, policy_version),
    CONSTRAINT ad_outcome_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT ad_outcome_policy_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT ad_outcome_policy_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION'
                AND platform_code IS NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'PLATFORM'
                AND platform_code IS NOT NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'STORE'
                AND platform_code IS NOT NULL AND store_ref_id IS NOT NULL)),
    CONSTRAINT ad_outcome_policy_direction_ck
        CHECK (direction IN ('PROTECTION_DECREASE', 'OPTIMIZATION_INCREASE')),
    -- Observation cannot start before the change has had a chance to
    -- propagate, and a window shorter than an hour would measure noise.
    CONSTRAINT ad_outcome_policy_start_ck
        CHECK (observation_starts_minutes BETWEEN 5 AND 1440),
    CONSTRAINT ad_outcome_policy_operational_ck
        CHECK (operational_window_hours BETWEEN 1 AND 720),
    -- Settlement always outlasts the operational view. If it did not, the two
    -- would be the same measurement under two names.
    CONSTRAINT ad_outcome_policy_settlement_ck
        CHECK (settlement_window_hours > operational_window_hours
            AND settlement_window_hours <= 2160),
    -- The guard is at least a day, because nothing is settled sooner, and no
    -- longer than the settlement window it sits inside.
    CONSTRAINT ad_outcome_policy_guard_ck
        CHECK (completed_sales_guard_hours BETWEEN 24 AND 2160
            AND completed_sales_guard_hours <= settlement_window_hours),
    CONSTRAINT ad_outcome_policy_coverage_ck
        CHECK (minimum_settled_coverage_ratio > 0
            AND minimum_settled_coverage_ratio <= 1),
    CONSTRAINT ad_outcome_policy_metric_ck
        CHECK (primary_metric_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT ad_outcome_policy_basis_ck
        CHECK (comparison_basis IN
            ('PRE_CHANGE_SAME_OBJECT', 'PRE_CHANGE_SAME_AFFECTED_SET')),
    CONSTRAINT ad_outcome_policy_threshold_ck
        CHECK (improvement_threshold_ratio > 0 AND improvement_threshold_ratio <= 1
            AND regression_threshold_ratio > 0 AND regression_threshold_ratio <= 1),
    CONSTRAINT ad_outcome_policy_traffic_ck CHECK (minimum_traffic_count >= 0),
    CONSTRAINT ad_outcome_policy_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ad_outcome_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_outcome_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_outcome_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512)
);

CREATE INDEX ad_outcome_policy_scope_ix
    ON core.ad_outcome_policy (organization_id, direction, effective_from DESC);

ALTER TABLE ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_outcome_fk
    FOREIGN KEY (outcome_policy_id, organization_id)
    REFERENCES core.ad_outcome_policy (id, organization_id);

-- ---------------------------------------------------------------------------
-- The lineage
-- ---------------------------------------------------------------------------

-- One evaluation of one command against the plan that was frozen for it. The
-- plan identity is stored on the row rather than looked up at read time,
-- because the policy row may have been retired since, and an outcome measured
-- under a rule nobody can name is not evidence about anything.
CREATE TABLE ops.ad_outcome_observation (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    command_id                  uuid           NOT NULL,
    ad_native_object_id         uuid           NOT NULL,
    affected_set_digest         text           NOT NULL,
    outcome_policy_id           uuid           NOT NULL,
    outcome_policy_version      integer        NOT NULL,
    outcome_stage               text           NOT NULL,
    window_starts_at            timestamptz    NOT NULL,
    window_ends_at              timestamptz    NOT NULL,
    baseline_metric_state       text           NOT NULL,
    baseline_metric_value       numeric(20, 6),
    observed_metric_state       text           NOT NULL,
    observed_metric_value       numeric(20, 6),
    observed_traffic_count      bigint,
    settled_coverage_ratio      numeric(6, 5),
    verdict                     text           NOT NULL,
    guard_state                 text           NOT NULL,
    unresolved_reason_codes     text[]         NOT NULL DEFAULT '{}',
    evaluated_at                timestamptz    NOT NULL,
    input_digest                text           NOT NULL,
    correlation_id              text           NOT NULL,
    CONSTRAINT ad_outcome_observation_pk PRIMARY KEY (id),
    CONSTRAINT ad_outcome_observation_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_outcome_observation_command_fk
        FOREIGN KEY (command_id) REFERENCES ops.ad_bid_command (id),
    CONSTRAINT ad_outcome_observation_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_outcome_observation_policy_fk
        FOREIGN KEY (outcome_policy_id, organization_id)
        REFERENCES core.ad_outcome_policy (id, organization_id),
    -- One command has at most one observation per stage. A second would be a
    -- second answer to a question that has already been answered.
    CONSTRAINT ad_outcome_observation_stage_uq UNIQUE (command_id, outcome_stage),
    CONSTRAINT ad_outcome_observation_stage_ck
        CHECK (outcome_stage IN ('OPERATIONAL', 'SETTLED')),
    CONSTRAINT ad_outcome_observation_window_ck
        CHECK (window_ends_at > window_starts_at),
    CONSTRAINT ad_outcome_observation_digest_ck
        CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'
            AND input_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ad_outcome_observation_value_states_ck
        CHECK (baseline_metric_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')
            AND observed_metric_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')),
    CONSTRAINT ad_outcome_observation_value_presence_ck
        CHECK ((baseline_metric_state = 'AVAILABLE') = (baseline_metric_value IS NOT NULL)
            AND (observed_metric_state = 'AVAILABLE') = (observed_metric_value IS NOT NULL)),
    -- Five verdicts and no sixth. INDETERMINATE is a real answer: it says the
    -- evidence did not settle the question, which is different from saying the
    -- change did nothing.
    CONSTRAINT ad_outcome_observation_verdict_ck
        CHECK (verdict IN ('IMPROVED', 'UNCHANGED', 'REGRESSED', 'INDETERMINATE',
                           'NOT_YET_EVALUABLE')),
    CONSTRAINT ad_outcome_observation_guard_ck
        CHECK (guard_state IN ('SATISFIED', 'SALES_TOO_RECENT', 'COVERAGE_INSUFFICIENT',
                               'NOT_APPLICABLE')),
    -- The guard applied to a settled claim, always. An operational observation
    -- is not a settled claim and the guard does not apply to it.
    CONSTRAINT ad_outcome_observation_guard_stage_ck
        CHECK ((outcome_stage = 'OPERATIONAL') = (guard_state = 'NOT_APPLICABLE')),
    -- This is the early Completed-Sales Guard, as a constraint rather than a
    -- branch. A settled verdict that claims improvement or regression while the
    -- guard is unsatisfied cannot be written at all.
    CONSTRAINT ad_outcome_observation_settled_guard_ck
        CHECK (outcome_stage <> 'SETTLED'
            OR guard_state = 'SATISFIED'
            OR verdict IN ('INDETERMINATE', 'NOT_YET_EVALUABLE')),
    CONSTRAINT ad_outcome_observation_coverage_ck
        CHECK (settled_coverage_ratio IS NULL
            OR (settled_coverage_ratio >= 0 AND settled_coverage_ratio <= 1)),
    CONSTRAINT ad_outcome_observation_traffic_ck
        CHECK (observed_traffic_count IS NULL OR observed_traffic_count >= 0),
    CONSTRAINT ad_outcome_observation_reasons_ck
        CHECK (cardinality(unresolved_reason_codes) BETWEEN 0 AND 32
            AND array_position(unresolved_reason_codes, NULL) IS NULL),
    -- A verdict that settled nothing says why, and one that settled something
    -- has nothing left to explain.
    CONSTRAINT ad_outcome_observation_reason_presence_ck
        CHECK ((verdict IN ('INDETERMINATE', 'NOT_YET_EVALUABLE'))
            = (cardinality(unresolved_reason_codes) >= 1)),
    CONSTRAINT ad_outcome_observation_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX ad_outcome_observation_command_ix
    ON ops.ad_outcome_observation (command_id, outcome_stage);
CREATE INDEX ad_outcome_observation_object_ix
    ON ops.ad_outcome_observation (organization_id, ad_native_object_id, evaluated_at DESC);

-- An outcome is written once and never revised. A later view of the same
-- window is a later stage, not an edit of an earlier one, so the history of
-- what this product believed and when stays readable.
CREATE FUNCTION ops.ad_outcome_observation_is_immutable()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'an outcome observation is a permanent record' USING ERRCODE = 'MO098';
END;
$$;

CREATE TRIGGER ad_outcome_observation_no_update
    BEFORE UPDATE OR DELETE ON ops.ad_outcome_observation
    FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

-- ---------------------------------------------------------------------------
-- Reading the plan and the guard
-- ---------------------------------------------------------------------------

-- Whether a settled claim may be made yet, for one command, right now.
-- Returns the guard state rather than a boolean, because "not yet" and "not
-- enough of it settled" are different problems with different remedies.
CREATE FUNCTION ops.ad_completed_sales_guard_state(
    p_command_id uuid,
    p_coverage   numeric)
RETURNS text
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE
    command  ops.ad_bid_command%ROWTYPE;
    policy   core.ad_outcome_policy%ROWTYPE;
    landed   timestamptz;
BEGIN
    SELECT * INTO command FROM ops.ad_bid_command WHERE id = p_command_id;
    IF NOT FOUND THEN
        RETURN 'SALES_TOO_RECENT';
    END IF;

    SELECT p.* INTO policy
      FROM ops.ad_decision_policy_bundle b
      JOIN core.ad_outcome_policy p ON p.id = b.outcome_policy_id
     WHERE b.id = command.bundle_id;
    IF NOT FOUND THEN
        RETURN 'SALES_TOO_RECENT';
    END IF;

    -- The clock starts when the write was proven to have landed, which is the
    -- first readback that matched the target. Not when the command was
    -- created, and not when somebody approved it.
    SELECT min(rb.observed_at) INTO landed
      FROM ops.ad_bid_command_readback rb
     WHERE rb.command_id = p_command_id AND rb.match_state = 'MATCHES_TARGET';
    IF landed IS NULL THEN
        RETURN 'SALES_TOO_RECENT';
    END IF;

    IF clock_timestamp() < landed + make_interval(hours => policy.completed_sales_guard_hours) THEN
        RETURN 'SALES_TOO_RECENT';
    END IF;
    IF p_coverage IS NULL OR p_coverage < policy.minimum_settled_coverage_ratio THEN
        RETURN 'COVERAGE_INSUFFICIENT';
    END IF;
    RETURN 'SATISFIED';
END;
$$;
REVOKE ALL ON FUNCTION ops.ad_completed_sales_guard_state(uuid, numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_completed_sales_guard_state(uuid, numeric) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Routing and grants
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'ad_outcome_policy', 'NO_ROUTE', NULL,
        'frozen outcome evaluation plan; describes measurement, transmits nothing'),
    ('ops', 'ad_outcome_observation', 'NO_ROUTE', NULL,
        'append-only operational and settled outcome lineage; no execution path');

GRANT SELECT, INSERT ON core.ad_outcome_policy TO marketops_app;
GRANT SELECT, INSERT ON ops.ad_outcome_observation TO marketops_app;
