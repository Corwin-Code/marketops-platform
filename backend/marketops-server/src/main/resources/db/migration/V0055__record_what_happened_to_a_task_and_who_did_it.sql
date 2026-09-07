-- What happened to a task, in the order it happened.
--
-- `ops.work_task` records the present: who holds it, when it is due, whether it
-- is closed. It cannot answer the questions a service level is actually made of
-- — when somebody first looked, when they took it, whether taking it is the
-- same as doing it, how old the work is after it changed hands, and whether the
-- thing reopened is the same thing that was opened. A row that is overwritten
-- cannot answer any of those, so this journal is appended beside it.
--
-- One journal for every task the product raises, advertising included. The
-- workflow module is the one Task authority: an advertising proposal already
-- creates its task here rather than beside it, so a task journal covers
-- advertising by construction and adds no second writer.
--
-- Four distinctions the schema enforces rather than trusts:
--
--   * opening a page is not an acknowledgement. `VIEWED` may carry no
--     acknowledgement and no action, so a console that recorded a page open
--     could not present it as either.
--   * an acknowledgement is not an action. `ACKNOWLEDGED` may carry no
--     action_kind and no action evidence, and `ACTION_RECORDED` must carry
--     both, plus the person who did it.
--   * an action is not an outcome. `OUTCOME_OBSERVED` must name the outcome it
--     observed, and no action event may name one.
--   * a reassignment is not a new task. It names who held it and who holds it
--     now, they must differ, and nothing in this table touches the task's
--     created_at — which is what makes age survive it.
--
-- Append-only by the absence of a grant, the way every journal in this schema
-- is: SELECT and INSERT to the application role, and no UPDATE or DELETE to
-- anybody but the owner.

CREATE TABLE ops.work_task_event (
    id                     uuid        NOT NULL,
    task_id                uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    sequence_no            integer     NOT NULL,
    event_kind             text        NOT NULL,
    -- The lineage a reopen or an escalation belongs to. A task reopened for the
    -- same cause continues its own lineage; a task that started a new one would
    -- be a different piece of work wearing the same name.
    lineage_key            text        NOT NULL,
    action_kind            text,
    action_evidence        jsonb,
    evidence_reference     text,
    outcome_kind           text,
    outcome_reference      text,
    from_assignee_user_id  uuid,
    to_assignee_user_id    uuid,
    actor_user_id          uuid,
    actor_role_code        text,
    reason                 text        NOT NULL,
    occurred_at            timestamptz NOT NULL,
    correlation_id         text        NOT NULL,
    CONSTRAINT work_task_event_pk PRIMARY KEY (id),
    CONSTRAINT work_task_event_task_fk
        FOREIGN KEY (task_id) REFERENCES ops.work_task (id),
    CONSTRAINT work_task_event_actor_fk
        FOREIGN KEY (actor_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT work_task_event_from_assignee_fk
        FOREIGN KEY (from_assignee_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT work_task_event_to_assignee_fk
        FOREIGN KEY (to_assignee_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT work_task_event_role_fk
        FOREIGN KEY (actor_role_code) REFERENCES iam.business_role (code),
    CONSTRAINT work_task_event_sequence_uq UNIQUE (task_id, sequence_no),
    CONSTRAINT work_task_event_sequence_ck CHECK (sequence_no >= 1),
    CONSTRAINT work_task_event_kind_ck
        CHECK (event_kind IN (
            'RAISED', 'VIEWED', 'ACKNOWLEDGED', 'ASSIGNED', 'REASSIGNED',
            'ACTION_RECORDED', 'OUTCOME_OBSERVED', 'REOPENED', 'ESCALATED',
            'COMPLETED', 'CANCELLED')),
    -- The closed set of structured actions. Anything outside it is not an
    -- action this product recognises, whatever a caller calls it.
    CONSTRAINT work_task_event_action_kind_ck
        CHECK (action_kind IS NULL OR action_kind IN (
            'DECISION_SUBMITTED_FOR_APPROVAL', 'DECISION_ENDORSED',
            'DECISION_APPROVED', 'DECISION_REJECTED', 'MANUAL_PACKET_ISSUED',
            'MANUAL_EXECUTION_VERIFIED', 'DATA_OR_MAPPING_REPAIR',
            'EXCEPTION_REQUESTED', 'COMPENSATION_REQUESTED')),
    -- An action must carry a structured action, its evidence and the person who
    -- performed it. This is what makes "a page view is not an acknowledgement
    -- and an acknowledgement is not an action" a property of the schema.
    CONSTRAINT work_task_event_action_shape_ck
        CHECK (event_kind <> 'ACTION_RECORDED'
            OR (action_kind IS NOT NULL
                AND action_evidence IS NOT NULL
                AND jsonb_typeof(action_evidence) = 'object'
                AND action_evidence ? 'reference'
                AND evidence_reference IS NOT NULL
                AND actor_user_id IS NOT NULL)),
    -- Only an action carries one. A view or an acknowledgement that arrived
    -- with an action_kind would be an action recorded under another name.
    CONSTRAINT work_task_event_action_exclusive_ck
        CHECK (event_kind = 'ACTION_RECORDED'
            OR (action_kind IS NULL AND action_evidence IS NULL)),
    -- Reading is not acting, so a view names no actor decision and no evidence.
    CONSTRAINT work_task_event_view_shape_ck
        CHECK (event_kind <> 'VIEWED'
            OR (action_kind IS NULL AND action_evidence IS NULL
                AND outcome_kind IS NULL AND evidence_reference IS NULL)),
    -- An acknowledgement says somebody has taken the work on. It names them and
    -- nothing else; it cannot carry the action it has not performed yet.
    CONSTRAINT work_task_event_acknowledgement_shape_ck
        CHECK (event_kind <> 'ACKNOWLEDGED'
            OR (actor_user_id IS NOT NULL AND action_kind IS NULL
                AND outcome_kind IS NULL)),
    CONSTRAINT work_task_event_outcome_kind_ck
        CHECK (outcome_kind IS NULL OR outcome_kind IN (
            'OPERATIONAL', 'SETTLED', 'SETTLED_REVISED', 'REGRESSION',
            'NO_IMPROVEMENT', 'UNKNOWN')),
    -- An outcome is a separate observation from the action that caused it, made
    -- later, against evidence the action could not have carried.
    CONSTRAINT work_task_event_outcome_shape_ck
        CHECK (event_kind <> 'OUTCOME_OBSERVED'
            OR (outcome_kind IS NOT NULL AND outcome_reference IS NOT NULL
                AND action_kind IS NULL)),
    CONSTRAINT work_task_event_outcome_exclusive_ck
        CHECK (event_kind = 'OUTCOME_OBSERVED'
            OR (outcome_kind IS NULL AND outcome_reference IS NULL)),
    -- An assignment names who holds it now. A reassignment additionally names
    -- who held it before, and the two may not be the same person: handing work
    -- to its current holder is not a reassignment and must not read as one.
    CONSTRAINT work_task_event_assignment_shape_ck
        CHECK ((event_kind NOT IN ('ASSIGNED', 'REASSIGNED')
                AND from_assignee_user_id IS NULL AND to_assignee_user_id IS NULL)
            OR (event_kind = 'ASSIGNED'
                AND to_assignee_user_id IS NOT NULL AND from_assignee_user_id IS NULL)
            OR (event_kind = 'REASSIGNED'
                AND to_assignee_user_id IS NOT NULL
                AND from_assignee_user_id IS NOT NULL
                AND from_assignee_user_id <> to_assignee_user_id)),
    CONSTRAINT work_task_event_lineage_ck
        CHECK (length(btrim(lineage_key)) BETWEEN 1 AND 200),
    CONSTRAINT work_task_event_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT work_task_event_evidence_ck
        CHECK (evidence_reference IS NULL
            OR length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT work_task_event_outcome_reference_ck
        CHECK (outcome_reference IS NULL
            OR length(btrim(outcome_reference)) BETWEEN 1 AND 512),
    CONSTRAINT work_task_event_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX work_task_event_task_ix
    ON ops.work_task_event (task_id, sequence_no DESC);
CREATE INDEX work_task_event_kind_ix
    ON ops.work_task_event (organization_id, event_kind, occurred_at DESC);
CREATE INDEX work_task_event_lineage_ix
    ON ops.work_task_event (organization_id, lineage_key, occurred_at);

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'work_task_event', 'NO_ROUTE', NULL,
        'append-only task lifecycle, assignment, action and outcome journal');

-- Append-only, the way every journal here is: the application role may read it
-- and add to it, and there is no grant that lets it change or remove anything.
GRANT SELECT, INSERT ON ops.work_task_event TO marketops_app;

-- The age a service level is measured from, kept where a reader can find it.
--
-- A task that changes hands keeps the instant it was raised, because that is
-- what its age means. The column is set once, from the task's own created_at,
-- and there is no route that writes it again — so a reassignment cannot reset
-- it even by accident.
ALTER TABLE ops.work_task
    ADD COLUMN first_raised_at timestamptz;

UPDATE ops.work_task SET first_raised_at = created_at WHERE first_raised_at IS NULL;

ALTER TABLE ops.work_task
    ALTER COLUMN first_raised_at SET NOT NULL;

ALTER TABLE ops.work_task
    ADD CONSTRAINT work_task_first_raised_ck CHECK (first_raised_at <= created_at);

CREATE FUNCTION ops.hold_work_task_first_raised_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    -- Set once, from the row's own creation, and never moved afterwards. A
    -- caller that tried would be told rather than silently obeyed, because a
    -- task whose age could be edited is a service level nobody can audit.
    IF TG_OP = 'INSERT' THEN
        NEW.first_raised_at := coalesce(NEW.first_raised_at, NEW.created_at);
        RETURN NEW;
    END IF;
    IF NEW.first_raised_at IS DISTINCT FROM OLD.first_raised_at THEN
        RAISE EXCEPTION 'a task keeps the instant it was raised'
            USING ERRCODE = 'MO018';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER work_task_first_raised_at_is_held
    BEFORE INSERT OR UPDATE ON ops.work_task
    FOR EACH ROW EXECUTE FUNCTION ops.hold_work_task_first_raised_at();

REVOKE ALL ON FUNCTION ops.hold_work_task_first_raised_at() FROM PUBLIC;
