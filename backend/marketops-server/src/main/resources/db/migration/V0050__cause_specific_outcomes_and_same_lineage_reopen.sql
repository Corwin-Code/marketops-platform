-- Cause-specific Protection outcomes, late adjustments, and the reopen.
--
-- Three things V0049 left open.
--
-- First, a Protection decrease is not one question. Lowering a bid because the
-- promoted variant cannot be sold and lowering it because the advertising was
-- provably losing money are answered by different measurements: the first by
-- whether spend on that variant actually stopped, the second by whether the
-- loss stopped. A single plan per direction cannot say both, so a plan may now
-- name the cause it answers, and a cause-specific plan outranks a generic one.
--
-- Second, marketplaces restate their own reports. A settled outcome recorded
-- last week can be contradicted by a correction this week, and the observation
-- table is immutable by design. So a late adjustment is a new revision that
-- names the observation it supersedes, and the history of what this product
-- believed and when stays readable.
--
-- Third, a settled regression has to go somewhere. It reopens the same lineage
-- rather than opening a new one, through the containment mechanism that already
-- exists, so there is no second authority deciding that something went wrong.
-- The quarantine is what the lane resolver reads on the next calculation, which
-- is what produces the ACTION_OUTCOME_REGRESSION case at P0 with its accountable
-- role. Responsibility routing is recorded on the containment rather than
-- inferred from the cause, so an operator can see who owns it without knowing
-- the cause table by heart.
--
-- Forward-only. No applied migration is edited and no row is rewritten.

-- ---------------------------------------------------------------------------
-- A plan may answer one cause
-- ---------------------------------------------------------------------------

ALTER TABLE core.ad_outcome_policy ADD COLUMN cause_code text;

ALTER TABLE core.ad_outcome_policy
    ADD CONSTRAINT ad_outcome_policy_cause_ck
    CHECK (cause_code IS NULL OR cause_code ~ '^[A-Z][A-Z0-9_]{1,62}$');

-- A cause-specific plan is only meaningful for a direction that has causes
-- behind it. Compensation restores a number and claims no outcome at all.
ALTER TABLE core.ad_outcome_policy
    ADD CONSTRAINT ad_outcome_policy_cause_direction_ck
    CHECK (cause_code IS NULL OR direction IN ('PROTECTION_DECREASE', 'OPTIMIZATION_INCREASE'));

-- The version uniqueness was per (organization, direction, version). A
-- cause-specific plan is a different plan, so the cause joins the key. NULL is
-- distinct from itself in a plain UNIQUE, so the generic plan is keyed
-- separately by a partial index instead.
ALTER TABLE core.ad_outcome_policy DROP CONSTRAINT ad_outcome_policy_version_uq;
CREATE UNIQUE INDEX ad_outcome_policy_cause_version_uq
    ON core.ad_outcome_policy (organization_id, direction, cause_code, policy_version)
    WHERE cause_code IS NOT NULL;
CREATE UNIQUE INDEX ad_outcome_policy_generic_version_uq
    ON core.ad_outcome_policy (organization_id, direction, policy_version)
    WHERE cause_code IS NULL;

-- Resolving the plan for one command, with the precedence stated once here
-- rather than in every caller: cause before generic, then store before platform
-- before organization, then the newest in force.
CREATE FUNCTION core.resolve_ad_outcome_policy(
    p_organization_id uuid,
    p_platform_code   text,
    p_store_id        uuid,
    p_direction       text,
    p_cause_code      text,
    p_at              timestamptz)
RETURNS core.ad_outcome_policy
LANGUAGE sql STABLE
SET search_path = pg_catalog, core, pg_temp
AS $$
    SELECT p.*
      FROM core.ad_outcome_policy p
     WHERE p.organization_id = p_organization_id
       AND p.direction = p_direction
       AND (p.cause_code IS NULL OR p.cause_code = p_cause_code)
       AND (p.scope_kind = 'ORGANIZATION'
            OR (p.scope_kind = 'PLATFORM' AND p.platform_code = p_platform_code)
            OR (p.scope_kind = 'STORE' AND p.store_ref_id = p_store_id))
       AND p.status IN ('ACTIVE', 'RETIRED')
       AND p.effective_from <= p_at
       AND (p.effective_to IS NULL OR p.effective_to > p_at)
     ORDER BY (p.cause_code IS NULL),
              CASE p.scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
              p.effective_from DESC
     LIMIT 1
$$;
REVOKE ALL ON FUNCTION core.resolve_ad_outcome_policy(uuid, text, uuid, text, text, timestamptz)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.resolve_ad_outcome_policy(uuid, text, uuid, text, text, timestamptz)
    TO marketops_app;

-- ---------------------------------------------------------------------------
-- A late adjustment is a revision, never an edit
-- ---------------------------------------------------------------------------

ALTER TABLE ops.ad_outcome_observation ADD COLUMN revision_no integer NOT NULL DEFAULT 1;
ALTER TABLE ops.ad_outcome_observation ADD COLUMN supersedes_observation_id uuid;
ALTER TABLE ops.ad_outcome_observation ADD COLUMN adjustment_reason text;

ALTER TABLE ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_supersedes_fk
    FOREIGN KEY (supersedes_observation_id) REFERENCES ops.ad_outcome_observation (id);

ALTER TABLE ops.ad_outcome_observation DROP CONSTRAINT ad_outcome_observation_stage_ck;
ALTER TABLE ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_stage_ck
    CHECK (outcome_stage IN ('OPERATIONAL', 'SETTLED', 'SETTLED_REVISED'));

-- The guard applies to any settled claim, revised or not. Only the operational
-- view is exempt, because it makes no settled claim.
ALTER TABLE ops.ad_outcome_observation DROP CONSTRAINT ad_outcome_observation_guard_stage_ck;
ALTER TABLE ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_guard_stage_ck
    CHECK ((outcome_stage = 'OPERATIONAL') = (guard_state = 'NOT_APPLICABLE'));

ALTER TABLE ops.ad_outcome_observation DROP CONSTRAINT ad_outcome_observation_settled_guard_ck;
ALTER TABLE ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_settled_guard_ck
    CHECK (outcome_stage = 'OPERATIONAL'
        OR guard_state = 'SATISFIED'
        OR verdict IN ('INDETERMINATE', 'NOT_YET_EVALUABLE'));

-- One observation per command per stage per revision. The original constraint
-- allowed exactly one settled view forever, which is the thing a restatement
-- has to be able to follow.
ALTER TABLE ops.ad_outcome_observation DROP CONSTRAINT ad_outcome_observation_stage_uq;
ALTER TABLE ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_revision_uq
    UNIQUE (command_id, outcome_stage, revision_no);

ALTER TABLE ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_revision_ck CHECK (revision_no >= 1);

-- A first view supersedes nothing and a revision always supersedes something.
ALTER TABLE ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_revision_shape_ck
    CHECK ((outcome_stage = 'SETTLED_REVISED')
        = (supersedes_observation_id IS NOT NULL AND revision_no > 1
           AND adjustment_reason IS NOT NULL));

ALTER TABLE ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_first_revision_ck
    CHECK (outcome_stage = 'SETTLED_REVISED' OR revision_no = 1);

ALTER TABLE ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_adjustment_reason_ck
    CHECK (adjustment_reason IS NULL
        OR length(btrim(adjustment_reason)) BETWEEN 1 AND 512);

CREATE INDEX ad_outcome_observation_revision_ix
    ON ops.ad_outcome_observation (supersedes_observation_id)
    WHERE supersedes_observation_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Responsibility routing on a containment
-- ---------------------------------------------------------------------------

ALTER TABLE ops.ad_containment ADD COLUMN accountable_role_code text;

ALTER TABLE ops.ad_containment
    ADD CONSTRAINT ad_containment_accountable_role_ck
    CHECK (accountable_role_code IS NULL
        OR accountable_role_code IN
            ('OWNER', 'OPERATIONS', 'FINANCE', 'MARKETPLACE_OPERATOR',
             'PRODUCT_PROCUREMENT', 'TECH_DATA', 'FINANCE_ANALYST', 'OPS_LEAD',
             'RISK_AUTHORITY'));

-- ---------------------------------------------------------------------------
-- The reopen
-- ---------------------------------------------------------------------------

-- A settled regression reopens the lineage it came from. It does not open a new
-- one, and it does not create a second place where "something went wrong" is
-- decided: it writes the quarantine the lane resolver already reads, and the
-- next calculation produces the ACTION_OUTCOME_REGRESSION case at P0.
--
-- Idempotent on the command. A reconciliation that re-reads the same regression
-- an hour later finds the quarantine it already opened rather than opening a
-- second one against the same lineage.
CREATE FUNCTION ops.reopen_ad_lineage_after_regression(
    p_containment_id  uuid,
    p_observation_id  uuid,
    p_accountable_role text,
    p_correlation_id  text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE
    observation ops.ad_outcome_observation%ROWTYPE;
    command     ops.ad_bid_command%ROWTYPE;
    existing_id uuid;
BEGIN
    IF p_correlation_id IS NULL
        OR length(btrim(p_correlation_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'a correlation identifier is required' USING ERRCODE = 'MO098';
    END IF;

    SELECT * INTO observation FROM ops.ad_outcome_observation WHERE id = p_observation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'no such outcome observation' USING ERRCODE = 'MO098';
    END IF;

    -- Only a settled regression reopens anything. An operational regression is
    -- a number that has not survived returns yet, and quarantining a lineage on
    -- one of those would stop work on evidence this product does not yet have.
    IF observation.outcome_stage = 'OPERATIONAL' OR observation.verdict <> 'REGRESSED'
        OR observation.guard_state <> 'SATISFIED' THEN
        RAISE EXCEPTION 'only a guarded settled regression reopens a lineage'
            USING ERRCODE = 'MO098';
    END IF;

    SELECT * INTO command FROM ops.ad_bid_command WHERE id = observation.command_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'the observation names no command' USING ERRCODE = 'MO098';
    END IF;

    -- Same lineage, so the quarantine is scoped to the affected set the command
    -- acted on rather than to the object alone. Another object promoting the
    -- same variants is part of the same question.
    SELECT c.id INTO existing_id
      FROM ops.ad_containment c
     WHERE c.organization_id = command.organization_id
       AND c.containment_kind = 'ACTION_OUTCOME_QUARANTINE'
       AND c.affected_set_digest = command.affected_set_digest
       AND c.state <> 'REENABLED';
    IF existing_id IS NOT NULL THEN
        RETURN existing_id;
    END IF;

    INSERT INTO ops.ad_containment (
        id, organization_id, containment_kind, scope_kind, affected_set_digest,
        cause_class, reason, evidence_reference, activated_by_trigger, activated_at,
        state, accountable_role_code, correlation_id, created_at, updated_at)
    VALUES (p_containment_id, command.organization_id, 'ACTION_OUTCOME_QUARANTINE',
        'AFFECTED_SET', command.affected_set_digest, 'OUTCOME_REGRESSION',
        'a settled outcome for this lineage regressed past the plan threshold',
        'ad-outcome-observation:' || p_observation_id::text,
        'SETTLED_OUTCOME_REGRESSION', clock_timestamp(), 'ACTIVE',
        p_accountable_role, p_correlation_id, clock_timestamp(), clock_timestamp());

    INSERT INTO ops.metadata_audit_event (
        id, actor_type, actor_id, source_domain, action, entity_type, entity_id,
        change_summary, reason, correlation_id)
    VALUES (gen_random_uuid(), 'SYSTEM', 'advertising-outcome', 'advertisingefficiency',
        'KILL_SWITCH', 'ad-containment', p_containment_id,
        jsonb_build_object('containmentKind',
            jsonb_build_object('previous', NULL, 'current', 'ACTION_OUTCOME_QUARANTINE'),
            'accountableRole',
            jsonb_build_object('previous', NULL, 'current', p_accountable_role)),
        'settled outcome regression reopened this lineage', p_correlation_id);

    RETURN p_containment_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.reopen_ad_lineage_after_regression(uuid, uuid, text, text)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.reopen_ad_lineage_after_regression(uuid, uuid, text, text)
    TO marketops_app;
