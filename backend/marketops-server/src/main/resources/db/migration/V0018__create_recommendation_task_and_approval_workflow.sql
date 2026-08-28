-- The operating workflow: a recommendation, the evidence behind it, the tasks
-- it creates, and the approval that may authorise it.
--
-- A recommendation is a proposal and never an authorization. It carries the
-- exact entity versions it was computed against, so an approval granted against
-- one state of the world cannot be spent against another. It expires, because
-- an unbounded proposal against changing facts is a stale instruction waiting
-- to be executed.
--
-- Approval is append-only and attributed. A decision is a person, a moment, a
-- reason and, for a sensitive action, proof that the person authenticated
-- recently. There is no update path by which a recorded decision can be
-- softened after the fact.

-- ---------------------------------------------------------------------------
-- Recommendation
-- ---------------------------------------------------------------------------

CREATE TABLE ops.recommendation (
    id                     uuid           NOT NULL,
    organization_id        uuid           NOT NULL,
    store_id               uuid           NOT NULL,
    subject_kind           text           NOT NULL,
    subject_id             uuid           NOT NULL,
    action_kind            text           NOT NULL,
    origin                 text           NOT NULL,
    ai_invocation_id       uuid,
    calculation_run_id     uuid           NOT NULL,
    window_code            text           NOT NULL,
    state                  text           NOT NULL,
    priority_score         numeric(9, 4)  NOT NULL,
    proposed_parameters    jsonb          NOT NULL,
    expected_effect        jsonb          NOT NULL,
    risk_label             text           NOT NULL,
    validation_horizon_days integer       NOT NULL,
    entity_version_digest  text           NOT NULL,
    valid_until            timestamptz    NOT NULL,
    terminal_reason        text,
    created_at             timestamptz    NOT NULL,
    updated_at             timestamptz    NOT NULL,
    version                bigint         NOT NULL DEFAULT 0,
    CONSTRAINT recommendation_pk PRIMARY KEY (id),
    CONSTRAINT recommendation_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT recommendation_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT recommendation_run_fk
        FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run (id),
    CONSTRAINT recommendation_ai_fk
        FOREIGN KEY (ai_invocation_id) REFERENCES ops.ai_invocation (id),
    CONSTRAINT recommendation_subject_ck
        CHECK (subject_kind IN ('PRODUCT_VARIANT', 'PLATFORM_LISTING_VARIANT', 'STORE')),
    -- The only action with a write capability in this product is PRICE_CHANGE.
    -- Everything else is a task a person performs, which is why TASK_ONLY is a
    -- first-class action kind rather than a degraded price recommendation.
    CONSTRAINT recommendation_action_ck
        CHECK (action_kind IN (
            'PRICE_CHANGE', 'RESOLVE_MAPPING', 'RESTOCK_REVIEW',
            'LISTING_CONTENT_REVIEW', 'ADVERTISING_REVIEW', 'COST_DATA_REVIEW')),
    CONSTRAINT recommendation_origin_ck
        CHECK (origin IN ('DETERMINISTIC', 'AI_ASSISTED')),
    -- A model-assisted recommendation must name the invocation it came from.
    -- A deterministic one must not, so its provenance cannot be blurred.
    CONSTRAINT recommendation_ai_presence_ck
        CHECK ((origin = 'AI_ASSISTED') = (ai_invocation_id IS NOT NULL)),
    CONSTRAINT recommendation_window_ck CHECK (window_code IN ('D7', 'D14', 'D30')),
    CONSTRAINT recommendation_state_ck
        CHECK (state IN (
            'DRAFT', 'VALIDATED', 'READY_FOR_REVIEW', 'TASK_ONLY',
            'APPROVED', 'POLICY_AUTHORIZED', 'REJECTED', 'EXPIRED', 'CANCELLED',
            'COMMAND_CREATED', 'EXECUTION_TRACKING', 'OUTCOME_OBSERVATION', 'CLOSED')),
    CONSTRAINT recommendation_terminal_reason_ck
        CHECK (state NOT IN ('REJECTED', 'EXPIRED', 'CANCELLED')
            OR terminal_reason IS NOT NULL),
    CONSTRAINT recommendation_priority_ck
        CHECK (priority_score >= 0 AND priority_score <= 1000),
    CONSTRAINT recommendation_parameters_ck CHECK (jsonb_typeof(proposed_parameters) = 'object'),
    CONSTRAINT recommendation_effect_ck CHECK (jsonb_typeof(expected_effect) = 'object'),
    CONSTRAINT recommendation_risk_ck CHECK (risk_label IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT recommendation_horizon_ck
        CHECK (validation_horizon_days BETWEEN 1 AND 90),
    CONSTRAINT recommendation_digest_ck CHECK (entity_version_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT recommendation_validity_ck CHECK (valid_until > created_at)
);

-- One live recommendation per subject and action. A second proposal for the
-- same thing would let two approvals authorise two commands for one change.
CREATE UNIQUE INDEX recommendation_live_uq
    ON ops.recommendation (subject_kind, subject_id, action_kind)
    WHERE state IN ('DRAFT', 'VALIDATED', 'READY_FOR_REVIEW', 'TASK_ONLY',
                    'APPROVED', 'POLICY_AUTHORIZED', 'COMMAND_CREATED',
                    'EXECUTION_TRACKING', 'OUTCOME_OBSERVATION');

CREATE INDEX recommendation_queue_ix
    ON ops.recommendation (organization_id, state, priority_score DESC, valid_until);
CREATE INDEX recommendation_store_ix ON ops.recommendation (store_id, state);
CREATE INDEX recommendation_expiry_ix
    ON ops.recommendation (valid_until)
    WHERE state IN ('DRAFT', 'VALIDATED', 'READY_FOR_REVIEW',
                    'APPROVED', 'POLICY_AUTHORIZED');

-- What the recommendation was built from. Every reference resolves to a stored
-- canonical value, deterministic finding or validated model claim, so a
-- reviewer can reconstruct the case without trusting the summary text.
CREATE TABLE ops.recommendation_evidence (
    id                uuid NOT NULL,
    recommendation_id uuid NOT NULL,
    metric_value_id   uuid,
    finding_id        uuid,
    ai_claim_id       uuid,
    role              text NOT NULL,
    CONSTRAINT recommendation_evidence_pk PRIMARY KEY (id),
    CONSTRAINT recommendation_evidence_recommendation_fk
        FOREIGN KEY (recommendation_id) REFERENCES ops.recommendation (id),
    CONSTRAINT recommendation_evidence_metric_fk
        FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value (id),
    CONSTRAINT recommendation_evidence_finding_fk
        FOREIGN KEY (finding_id) REFERENCES mart.diagnosis_finding (id),
    CONSTRAINT recommendation_evidence_claim_fk
        FOREIGN KEY (ai_claim_id) REFERENCES ops.ai_output_claim (id),
    CONSTRAINT recommendation_evidence_one_target_ck
        CHECK (num_nonnulls(metric_value_id, finding_id, ai_claim_id) = 1),
    CONSTRAINT recommendation_evidence_role_ck
        CHECK (role IN ('PRIMARY_CAUSE', 'SUPPORTING', 'COUNTER_EVIDENCE', 'UNKNOWN_GAP')),
    CONSTRAINT recommendation_evidence_uq
        UNIQUE (recommendation_id, metric_value_id, finding_id, ai_claim_id)
);

CREATE INDEX recommendation_evidence_recommendation_ix
    ON ops.recommendation_evidence (recommendation_id, role);

-- ---------------------------------------------------------------------------
-- Task
-- ---------------------------------------------------------------------------

CREATE TABLE ops.work_task (
    id                uuid        NOT NULL,
    organization_id   uuid        NOT NULL,
    recommendation_id uuid        NOT NULL,
    title             text        NOT NULL,
    state             text        NOT NULL,
    assignee_user_id  uuid,
    due_at            timestamptz,
    closed_at         timestamptz,
    closure_reason    text,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT work_task_pk PRIMARY KEY (id),
    CONSTRAINT work_task_recommendation_fk
        FOREIGN KEY (recommendation_id, organization_id)
        REFERENCES ops.recommendation (id, organization_id),
    CONSTRAINT work_task_assignee_fk
        FOREIGN KEY (assignee_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT work_task_state_ck
        CHECK (state IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    CONSTRAINT work_task_assignment_ck
        CHECK (state NOT IN ('ASSIGNED', 'IN_PROGRESS') OR assignee_user_id IS NOT NULL),
    CONSTRAINT work_task_closure_ck
        CHECK ((state IN ('DONE', 'CANCELLED'))
            = (closed_at IS NOT NULL AND closure_reason IS NOT NULL))
);

CREATE INDEX work_task_queue_ix
    ON ops.work_task (organization_id, state, due_at);
CREATE INDEX work_task_assignee_ix
    ON ops.work_task (assignee_user_id, state)
    WHERE assignee_user_id IS NOT NULL;
CREATE INDEX work_task_recommendation_ix ON ops.work_task (recommendation_id);

-- ---------------------------------------------------------------------------
-- Approval
-- ---------------------------------------------------------------------------

-- One recorded decision about one recommendation. Append-only: a decision that
-- could be edited is not an audit record, and this is the row a real platform
-- write is later justified by.
--
-- A person-made decision names the person and, for a step-up action, the time
-- their authentication was proven. A policy authorization names the bounded
-- authorization it consumed instead; the two are mutually exclusive so no
-- decision can be attributed to both a person and a standing rule.
CREATE TABLE ops.approval_decision (
    id                      uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    recommendation_id       uuid        NOT NULL,
    decision                text        NOT NULL,
    decided_by_user_id      uuid,
    policy_authorization_id uuid,
    authenticated_at        timestamptz,
    step_up_satisfied       boolean     NOT NULL,
    entity_version_digest   text        NOT NULL,
    scope_expires_at        timestamptz NOT NULL,
    reason                  text        NOT NULL,
    decided_at              timestamptz NOT NULL,
    correlation_id          text        NOT NULL,
    CONSTRAINT approval_decision_pk PRIMARY KEY (id),
    CONSTRAINT approval_decision_recommendation_fk
        FOREIGN KEY (recommendation_id, organization_id)
        REFERENCES ops.recommendation (id, organization_id),
    CONSTRAINT approval_decision_user_fk
        FOREIGN KEY (decided_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT approval_decision_decision_ck
        CHECK (decision IN ('APPROVED', 'REJECTED', 'POLICY_AUTHORIZED')),
    CONSTRAINT approval_decision_actor_ck CHECK (
        (decision IN ('APPROVED', 'REJECTED')
            AND decided_by_user_id IS NOT NULL AND policy_authorization_id IS NULL)
        OR (decision = 'POLICY_AUTHORIZED'
            AND policy_authorization_id IS NOT NULL AND decided_by_user_id IS NULL)),
    -- A satisfied step-up must name when the person authenticated. Without the
    -- time there is nothing to check the recency requirement against.
    CONSTRAINT approval_decision_step_up_ck
        CHECK (step_up_satisfied = false OR authenticated_at IS NOT NULL),
    CONSTRAINT approval_decision_digest_ck
        CHECK (entity_version_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT approval_decision_scope_ck CHECK (scope_expires_at > decided_at)
);

-- One standing authorization per recommendation. A second approval of the same
-- proposal would be a second licence to write.
CREATE UNIQUE INDEX approval_decision_authorization_uq
    ON ops.approval_decision (recommendation_id)
    WHERE decision IN ('APPROVED', 'POLICY_AUTHORIZED');

CREATE INDEX approval_decision_recommendation_ix
    ON ops.approval_decision (recommendation_id, decided_at DESC);
CREATE INDEX approval_decision_actor_ix
    ON ops.approval_decision (decided_by_user_id, decided_at DESC)
    WHERE decided_by_user_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Workflow authorizes the write path, which has its own gate. It is not an
-- input to an acquisition call authority, so an approval does not invalidate a
-- running acquisition and an acquisition does not invalidate an approval.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'recommendation', 'NO_ROUTE', NULL,
        'decision proposal; consumed by the write gate, not by call authority'),
    ('ops', 'recommendation_evidence', 'NO_ROUTE', NULL,
        'append-only evidence link; no acquisition authority reads it'),
    ('ops', 'work_task', 'NO_ROUTE', NULL,
        'human task state; no acquisition authority reads it'),
    ('ops', 'approval_decision', 'NO_ROUTE', NULL,
        'append-only decision; consumed by the write gate, not by call authority');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Recommendations and tasks carry state and accept versioned updates. Evidence
-- and decisions are append-only. No DELETE is granted anywhere: withdrawing a
-- recommendation is a recorded transition and a decision is permanent.
GRANT SELECT, INSERT, UPDATE ON ops.recommendation TO marketops_app;
GRANT SELECT, INSERT ON ops.recommendation_evidence TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.work_task TO marketops_app;
GRANT SELECT, INSERT ON ops.approval_decision TO marketops_app;
