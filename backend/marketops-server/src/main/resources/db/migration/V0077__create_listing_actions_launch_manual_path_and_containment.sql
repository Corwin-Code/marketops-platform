-- SLICE-V1-004: candidates, the one exact action per round, independent review,
-- the frozen binding, the evaluation plan, launch with allowance acquisition,
-- the governed manual path, simple promotion engagements, batches, containment
-- and result isolation.
--
-- Approval never sends. The only route from an approved action to anything
-- external is the launch function below, which consumes a one-use
-- authenticated invocation proof, re-checks every authority, serialises on the
-- allowance scope and acquires every axis independently. Insufficient
-- allowance leaves the action APPROVED_NOT_LAUNCHABLE and nothing external
-- happens first. A manual packet is issued only from a launched action and
-- creates no command; a description command is created only from a launched
-- action on the API path, in the next migration.
--
-- Forward-only: new tables and functions only.

-- ---------------------------------------------------------------------------
-- Candidates
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_candidate (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    store_id             uuid        NOT NULL,
    platform_listing_id  uuid        NOT NULL,
    calculation_run_id   uuid        NOT NULL,
    health_id            uuid,
    candidate_kind       text        NOT NULL,
    comparison_round_key text        NOT NULL,
    evidence_references  jsonb       NOT NULL,
    expected_effect      jsonb       NOT NULL,
    prepared_by_user_id  uuid        NOT NULL,
    prepared_at          timestamptz NOT NULL,
    state                text        NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL DEFAULT 0,
    CONSTRAINT lc_candidate_pk PRIMARY KEY (id),
    CONSTRAINT lc_candidate_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_candidate_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_candidate_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_candidate_run_fk
        FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run (id),
    CONSTRAINT lc_candidate_health_fk
        FOREIGN KEY (health_id, organization_id) REFERENCES mart.lc_listing_health (id, organization_id),
    CONSTRAINT lc_candidate_author_fk
        FOREIGN KEY (prepared_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    -- Three comparable kinds. Nothing else is a candidate of this Slice.
    CONSTRAINT lc_candidate_kind_ck
        CHECK (candidate_kind IN ('CONTENT_DESCRIPTION', 'OFFICIAL_PROMOTION_PARTICIPATION',
                                  'SELLER_DIRECT_DISCOUNT')),
    CONSTRAINT lc_candidate_round_ck CHECK (comparison_round_key ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT lc_candidate_evidence_ck CHECK (jsonb_typeof(evidence_references) = 'array'),
    CONSTRAINT lc_candidate_effect_ck CHECK (jsonb_typeof(expected_effect) = 'object'),
    CONSTRAINT lc_candidate_state_ck CHECK (state IN ('OPEN', 'SELECTED', 'DISMISSED'))
);

CREATE INDEX lc_candidate_round_ix
    ON ops.lc_candidate (platform_listing_id, comparison_round_key, state);

-- ---------------------------------------------------------------------------
-- The exact action
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_action (
    id                                uuid        NOT NULL,
    organization_id                   uuid        NOT NULL,
    store_id                          uuid        NOT NULL,
    platform_listing_id               uuid        NOT NULL,
    candidate_id                      uuid        NOT NULL,
    recommendation_id                 uuid        NOT NULL,
    affected_set_id                   uuid        NOT NULL,
    affected_set_digest               text        NOT NULL,
    action_kind                       text        NOT NULL,
    execution_path                    text        NOT NULL,
    current_description_observation_id uuid,
    current_text_digest               text,
    target_text                       text,
    target_text_digest                text,
    target_language_code              text,
    kiz_marked_declared               boolean,
    content_axis_material             boolean,
    exposure_axis_material            boolean,
    materiality_route                 text        NOT NULL,
    calibration_package_id            uuid,
    calibration_version               integer,
    author_user_id                    uuid        NOT NULL,
    state                             text        NOT NULL,
    created_at                        timestamptz NOT NULL,
    updated_at                        timestamptz NOT NULL,
    version                           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT lc_action_pk PRIMARY KEY (id),
    CONSTRAINT lc_action_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_action_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_action_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_action_candidate_fk
        FOREIGN KEY (candidate_id, organization_id) REFERENCES ops.lc_candidate (id, organization_id),
    CONSTRAINT lc_action_recommendation_fk
        FOREIGN KEY (recommendation_id, organization_id)
        REFERENCES ops.recommendation (id, organization_id),
    CONSTRAINT lc_action_recommendation_uq UNIQUE (recommendation_id),
    CONSTRAINT lc_action_affected_set_fk
        FOREIGN KEY (affected_set_id, organization_id)
        REFERENCES core.lc_affected_set (id, organization_id),
    CONSTRAINT lc_action_current_observation_fk
        FOREIGN KEY (current_description_observation_id, organization_id)
        REFERENCES core.lc_description_observation (id, organization_id),
    CONSTRAINT lc_action_calibration_fk
        FOREIGN KEY (calibration_package_id, organization_id)
        REFERENCES core.lc_calibration_package (id, organization_id),
    CONSTRAINT lc_action_author_fk
        FOREIGN KEY (author_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_action_kind_ck
        CHECK (action_kind IN ('LISTING_DESCRIPTION_CHANGE', 'LISTING_PROMOTION_ACTION')),
    CONSTRAINT lc_action_path_ck CHECK (execution_path IN ('MANUAL', 'API')),
    -- Only the description change may take the API path; a promotion action is
    -- governed manual work and nothing else.
    CONSTRAINT lc_action_promotion_path_ck
        CHECK (action_kind <> 'LISTING_PROMOTION_ACTION' OR execution_path = 'MANUAL'),
    CONSTRAINT lc_action_digest_ck CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    -- A description change binds the exact current text digest and the exact
    -- target Russian full text. Nothing about it is a fragment or a template.
    CONSTRAINT lc_action_description_shape_ck
        CHECK (action_kind <> 'LISTING_DESCRIPTION_CHANGE'
            OR (current_description_observation_id IS NOT NULL
                AND current_text_digest ~ '^[0-9a-f]{64}$'
                AND target_text IS NOT NULL
                AND length(target_text) BETWEEN 1 AND 65536
                AND target_text_digest = encode(sha256(convert_to(target_text, 'UTF8')), 'hex')
                AND target_language_code = 'ru'
                AND kiz_marked_declared IS NOT NULL)),
    CONSTRAINT lc_action_promotion_shape_ck
        CHECK (action_kind <> 'LISTING_PROMOTION_ACTION'
            OR (target_text IS NULL AND target_text_digest IS NULL)),
    CONSTRAINT lc_action_materiality_ck
        CHECK (materiality_route IN ('MATERIAL_IMPACT', 'ORDINARY_IMPACT', 'MATERIALITY_UNRESOLVED')),
    -- Either axis crossing its trigger is material. Unresolved means the
    -- calibration could not be resolved, and an unresolved action never leaves
    -- draft.
    CONSTRAINT lc_action_materiality_axes_ck
        CHECK ((materiality_route = 'MATERIALITY_UNRESOLVED'
                AND content_axis_material IS NULL AND exposure_axis_material IS NULL
                AND calibration_package_id IS NULL)
            OR (materiality_route <> 'MATERIALITY_UNRESOLVED'
                AND content_axis_material IS NOT NULL AND exposure_axis_material IS NOT NULL
                AND calibration_package_id IS NOT NULL AND calibration_version IS NOT NULL
                AND (materiality_route = 'MATERIAL_IMPACT')
                    = (content_axis_material OR exposure_axis_material))),
    CONSTRAINT lc_action_state_ck
        CHECK (state IN ('DRAFT', 'REVIEWED', 'APPROVED', 'APPROVED_NOT_LAUNCHABLE',
                         'LAUNCHED', 'VERIFIED', 'CLOSED', 'CANCELLED', 'CONTAINED')),
    CONSTRAINT lc_action_unresolved_stays_draft_ck
        CHECK (materiality_route <> 'MATERIALITY_UNRESOLVED' OR state IN ('DRAFT', 'CANCELLED'))
);

-- One primary change per listing at a time. A second live action on the same
-- listing would make the version-attributed window unattributable.
CREATE UNIQUE INDEX lc_action_live_uq
    ON ops.lc_action (platform_listing_id)
    WHERE state IN ('DRAFT', 'REVIEWED', 'APPROVED', 'APPROVED_NOT_LAUNCHABLE', 'LAUNCHED');
CREATE INDEX lc_action_store_ix ON ops.lc_action (store_id, state, updated_at DESC);

CREATE TABLE ops.lc_action_transition (
    from_state text NOT NULL,
    to_state   text NOT NULL,
    note       text NOT NULL,
    CONSTRAINT lc_action_transition_pk PRIMARY KEY (from_state, to_state),
    CONSTRAINT lc_action_transition_distinct_ck CHECK (from_state <> to_state)
);

INSERT INTO ops.lc_action_transition (from_state, to_state, note) VALUES
    ('DRAFT', 'REVIEWED', 'a person other than the author attested the text and the facts'),
    ('DRAFT', 'CANCELLED', 'withdrawn before review'),
    ('REVIEWED', 'DRAFT', 'the review returned the action to its author'),
    ('REVIEWED', 'APPROVED', 'the approval decision was recorded and the binding frozen'),
    ('REVIEWED', 'CANCELLED', 'withdrawn after review'),
    ('APPROVED', 'LAUNCHED', 'the launch function acquired every allowance axis'),
    ('APPROVED', 'APPROVED_NOT_LAUNCHABLE', 'the launch function found an axis short'),
    ('APPROVED', 'CANCELLED', 'withdrawn before launch'),
    ('APPROVED', 'CONTAINED', 'a containment covering the scope stopped the action'),
    ('APPROVED_NOT_LAUNCHABLE', 'LAUNCHED', 'a later launch found every axis sufficient'),
    ('APPROVED_NOT_LAUNCHABLE', 'CANCELLED', 'withdrawn while unlaunchable'),
    ('APPROVED_NOT_LAUNCHABLE', 'CONTAINED', 'a containment covering the scope stopped the action'),
    ('LAUNCHED', 'VERIFIED', 'management match and display were verified separately'),
    ('LAUNCHED', 'CONTAINED', 'a containment covering the scope stopped the action'),
    ('LAUNCHED', 'CLOSED', 'the action ended without verification and the record says so'),
    ('VERIFIED', 'CLOSED', 'the evaluation plan concluded or the action was superseded'),
    ('CONTAINED', 'CLOSED', 'closed under containment; nothing was resumed');

-- ---------------------------------------------------------------------------
-- Independent review
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_action_review (
    id                           uuid        NOT NULL,
    organization_id              uuid        NOT NULL,
    action_id                    uuid        NOT NULL,
    reviewer_user_id             uuid        NOT NULL,
    attested_target_text_digest  text,
    attested_current_text_digest text,
    attested_affected_set_digest text        NOT NULL,
    facts_digest                 text        NOT NULL,
    verdict                      text        NOT NULL,
    reason                       text        NOT NULL,
    reviewed_at                  timestamptz NOT NULL,
    CONSTRAINT lc_action_review_pk PRIMARY KEY (id),
    CONSTRAINT lc_action_review_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_action_review_reviewer_fk
        FOREIGN KEY (reviewer_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_action_review_verdict_ck CHECK (verdict IN ('ATTESTED', 'RETURNED')),
    CONSTRAINT lc_action_review_digests_ck
        CHECK (attested_affected_set_digest ~ '^[0-9a-f]{64}$' AND facts_digest ~ '^[0-9a-f]{64}$'
            AND (attested_target_text_digest IS NULL OR attested_target_text_digest ~ '^[0-9a-f]{64}$')
            AND (attested_current_text_digest IS NULL OR attested_current_text_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT lc_action_review_reason_ck CHECK (length(btrim(reason)) BETWEEN 1 AND 2000)
);

CREATE INDEX lc_action_review_action_ix ON ops.lc_action_review (action_id, reviewed_at DESC);

-- The reviewer is not the author, and an attestation names the exact digests
-- the action carries. A review that attested a different text would be a
-- review of something else.
CREATE FUNCTION ops.lc_action_review_is_independent()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE action ops.lc_action%ROWTYPE;
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = NEW.action_id;
    IF NOT FOUND OR action.organization_id <> NEW.organization_id THEN
        RAISE EXCEPTION 'review names an unknown action' USING ERRCODE = 'MO090';
    END IF;
    IF action.author_user_id = NEW.reviewer_user_id THEN
        RAISE EXCEPTION 'the author of an action cannot review it' USING ERRCODE = 'MO092';
    END IF;
    IF action.state <> 'DRAFT' THEN
        RAISE EXCEPTION 'only a draft action is reviewed' USING ERRCODE = 'MO091';
    END IF;
    IF NEW.verdict = 'ATTESTED' AND (
        NEW.attested_affected_set_digest <> action.affected_set_digest
        OR NEW.attested_target_text_digest IS DISTINCT FROM action.target_text_digest
        OR NEW.attested_current_text_digest IS DISTINCT FROM action.current_text_digest) THEN
        RAISE EXCEPTION 'an attestation names the exact digests of the action' USING ERRCODE = 'MO092';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_action_review_is_independent() FROM PUBLIC;
CREATE TRIGGER lc_action_review_is_independent
    BEFORE INSERT ON ops.lc_action_review
    FOR EACH ROW EXECUTE FUNCTION ops.lc_action_review_is_independent();

-- ---------------------------------------------------------------------------
-- The frozen binding
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_action_binding (
    id                      uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    action_id               uuid        NOT NULL,
    approval_decision_id    uuid        NOT NULL,
    guardrail_evaluation_id uuid        NOT NULL,
    target_text_digest      text,
    current_text_digest     text,
    affected_set_digest     text        NOT NULL,
    execution_path          text        NOT NULL,
    evidence_versions       jsonb       NOT NULL,
    rule_versions           jsonb       NOT NULL,
    calibration_package_id  uuid        NOT NULL,
    calibration_version     integer     NOT NULL,
    binding_digest          text        NOT NULL,
    bound_at                timestamptz NOT NULL,
    expires_at              timestamptz NOT NULL,
    state                   text        NOT NULL,
    inapplicable_reason     text,
    inapplicable_at         timestamptz,
    CONSTRAINT lc_action_binding_pk PRIMARY KEY (id),
    CONSTRAINT lc_action_binding_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_action_binding_action_uq UNIQUE (action_id),
    CONSTRAINT lc_action_binding_approval_fk
        FOREIGN KEY (approval_decision_id) REFERENCES ops.approval_decision (id),
    CONSTRAINT lc_action_binding_guardrail_fk
        FOREIGN KEY (guardrail_evaluation_id) REFERENCES ops.guardrail_evaluation (id),
    CONSTRAINT lc_action_binding_calibration_fk
        FOREIGN KEY (calibration_package_id, organization_id)
        REFERENCES core.lc_calibration_package (id, organization_id),
    CONSTRAINT lc_action_binding_path_ck CHECK (execution_path IN ('MANUAL', 'API')),
    CONSTRAINT lc_action_binding_digests_ck
        CHECK (affected_set_digest ~ '^[0-9a-f]{64}$' AND binding_digest ~ '^[0-9a-f]{64}$'
            AND (target_text_digest IS NULL OR target_text_digest ~ '^[0-9a-f]{64}$')
            AND (current_text_digest IS NULL OR current_text_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT lc_action_binding_versions_ck
        CHECK (jsonb_typeof(evidence_versions) = 'object' AND jsonb_typeof(rule_versions) = 'object'),
    CONSTRAINT lc_action_binding_expiry_ck CHECK (expires_at > bound_at),
    CONSTRAINT lc_action_binding_state_ck CHECK (state IN ('BOUND', 'INAPPLICABLE')),
    CONSTRAINT lc_action_binding_inapplicable_ck
        CHECK ((state = 'INAPPLICABLE') = (inapplicable_reason IS NOT NULL AND inapplicable_at IS NOT NULL))
);

-- A binding freezes exactly what the approval was given against.
CREATE FUNCTION ops.lc_action_binding_matches_action()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE action ops.lc_action%ROWTYPE; approval ops.approval_decision%ROWTYPE;
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = NEW.action_id;
    IF NOT FOUND OR action.organization_id <> NEW.organization_id THEN
        RAISE EXCEPTION 'binding names an unknown action' USING ERRCODE = 'MO090';
    END IF;
    IF action.state <> 'REVIEWED' THEN
        RAISE EXCEPTION 'only a reviewed action is bound' USING ERRCODE = 'MO091';
    END IF;
    IF NEW.affected_set_digest <> action.affected_set_digest
        OR NEW.target_text_digest IS DISTINCT FROM action.target_text_digest
        OR NEW.current_text_digest IS DISTINCT FROM action.current_text_digest
        OR NEW.execution_path <> action.execution_path
        OR NEW.calibration_package_id IS DISTINCT FROM action.calibration_package_id
        OR NEW.calibration_version IS DISTINCT FROM action.calibration_version THEN
        RAISE EXCEPTION 'a binding freezes exactly what the action carries' USING ERRCODE = 'MO092';
    END IF;
    SELECT * INTO approval FROM ops.approval_decision WHERE id = NEW.approval_decision_id;
    IF NOT FOUND OR approval.recommendation_id <> action.recommendation_id
        OR approval.decision <> 'APPROVED' OR NEW.expires_at > approval.scope_expires_at THEN
        RAISE EXCEPTION 'a binding rests on the standing approval of its own action'
            USING ERRCODE = 'MO092';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.guardrail_evaluation g
                    WHERE g.id = NEW.guardrail_evaluation_id
                      AND g.recommendation_id = action.recommendation_id
                      AND g.purpose = 'APPROVAL' AND g.outcome = 'PASS'
                      AND g.lc_calibration_package_id = NEW.calibration_package_id
                      AND g.lc_calibration_version = NEW.calibration_version) THEN
        RAISE EXCEPTION 'a binding rests on an approval PASS naming its calibration package'
            USING ERRCODE = 'MO092';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_action_binding_matches_action() FROM PUBLIC;
CREATE TRIGGER lc_action_binding_matches_action
    BEFORE INSERT ON ops.lc_action_binding
    FOR EACH ROW EXECUTE FUNCTION ops.lc_action_binding_matches_action();

-- Why the binding no longer applies, or nothing. Queueing, preparation,
-- launch, notification and allowance acquisition never extend it.
CREATE FUNCTION ops.lc_binding_gaps(p_action uuid)
RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE
    action  ops.lc_action%ROWTYPE;
    binding ops.lc_action_binding%ROWTYPE;
    reasons text[] := '{}';
    latest_digest text;
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = p_action;
    IF NOT FOUND THEN RETURN ARRAY['ACTION_NOT_FOUND']; END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id = p_action;
    IF NOT FOUND THEN RETURN ARRAY['BINDING_MISSING']; END IF;
    IF binding.state <> 'BOUND' THEN
        reasons := array_append(reasons, 'BINDING_INAPPLICABLE');
    END IF;
    IF binding.expires_at <= statement_timestamp() THEN
        reasons := array_append(reasons, 'BINDING_EXPIRED');
    END IF;
    IF core.lc_listing_affected_set_digest(action.platform_listing_id) <> binding.affected_set_digest THEN
        reasons := array_append(reasons, 'AFFECTED_SET_DIGEST_CHANGED');
    END IF;
    IF action.action_kind = 'LISTING_DESCRIPTION_CHANGE' THEN
        SELECT o.text_digest INTO latest_digest
          FROM core.lc_description_observation o
         WHERE o.platform_listing_id = action.platform_listing_id
         ORDER BY o.observed_at DESC, o.acquired_at DESC LIMIT 1;
        IF latest_digest IS DISTINCT FROM binding.current_text_digest THEN
            reasons := array_append(reasons, 'CURRENT_TEXT_MOVED');
        END IF;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM core.lc_calibration_package p
                    WHERE p.id = binding.calibration_package_id
                      AND p.package_version = binding.calibration_version
                      AND p.status = 'ACTIVE') THEN
        reasons := array_append(reasons, 'CALIBRATION_NOT_CURRENT');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.approval_decision a
                    WHERE a.id = binding.approval_decision_id
                      AND a.decision = 'APPROVED'
                      AND a.scope_expires_at > statement_timestamp()) THEN
        reasons := array_append(reasons, 'AUTHORIZATION_INVALID_OR_EXPIRED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.recommendation r
                    WHERE r.id = action.recommendation_id
                      AND r.state IN ('APPROVED', 'COMMAND_CREATED', 'EXECUTION_TRACKING')
                      AND r.valid_until > statement_timestamp()) THEN
        reasons := array_append(reasons, 'RECOMMENDATION_STALE');
    END IF;
    RETURN reasons;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_binding_gaps(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_binding_gaps(uuid) TO marketops_app;

-- ---------------------------------------------------------------------------
-- The evaluation plan, frozen before launch
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_evaluation_plan (
    id                       uuid        NOT NULL,
    organization_id          uuid        NOT NULL,
    action_id                uuid        NOT NULL,
    calibration_package_id   uuid        NOT NULL,
    calibration_version      integer     NOT NULL,
    version_coverage         jsonb       NOT NULL,
    transition_handling      text        NOT NULL,
    latest_boundary          timestamptz NOT NULL,
    formal_nodes             jsonb       NOT NULL,
    stop_rule                jsonb       NOT NULL,
    critical_groups          jsonb       NOT NULL,
    comparison_basis         text        NOT NULL,
    cross_period_window_days integer     NOT NULL,
    plan_digest              text        NOT NULL,
    frozen_at                timestamptz NOT NULL,
    CONSTRAINT lc_evaluation_plan_pk PRIMARY KEY (id),
    CONSTRAINT lc_evaluation_plan_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_evaluation_plan_action_uq UNIQUE (action_id),
    CONSTRAINT lc_evaluation_plan_calibration_fk
        FOREIGN KEY (calibration_package_id, organization_id)
        REFERENCES core.lc_calibration_package (id, organization_id),
    CONSTRAINT lc_evaluation_plan_coverage_ck CHECK (jsonb_typeof(version_coverage) = 'object'),
    -- A day in which two descriptions were both displayed is excluded from the
    -- version-attributed window. Nothing is prorated.
    CONSTRAINT lc_evaluation_plan_transition_ck
        CHECK (transition_handling = 'EXCLUDE_TRANSITION_DAYS'),
    CONSTRAINT lc_evaluation_plan_nodes_ck
        CHECK (jsonb_typeof(formal_nodes) = 'array' AND jsonb_array_length(formal_nodes) BETWEEN 1 AND 8),
    CONSTRAINT lc_evaluation_plan_stop_ck CHECK (jsonb_typeof(stop_rule) = 'object'),
    CONSTRAINT lc_evaluation_plan_groups_ck CHECK (jsonb_typeof(critical_groups) = 'array'),
    CONSTRAINT lc_evaluation_plan_basis_ck
        CHECK (comparison_basis IN ('PRIOR_VERSION_WINDOW', 'MATCHED_CONTROL_GROUP')),
    CONSTRAINT lc_evaluation_plan_window_ck CHECK (cross_period_window_days BETWEEN 1 AND 3660),
    CONSTRAINT lc_evaluation_plan_digest_ck CHECK (plan_digest ~ '^[0-9a-f]{64}$')
);

-- ---------------------------------------------------------------------------
-- Containment and result isolation
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_batch (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    store_id        uuid        NOT NULL,
    batch_code      text        NOT NULL,
    created_by_user_id uuid     NOT NULL,
    created_at      timestamptz NOT NULL,
    state           text        NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT lc_batch_pk PRIMARY KEY (id),
    CONSTRAINT lc_batch_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_batch_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_batch_creator_fk
        FOREIGN KEY (created_by_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_batch_code_uq UNIQUE (organization_id, batch_code),
    CONSTRAINT lc_batch_code_ck CHECK (batch_code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT lc_batch_state_ck CHECK (state IN ('OPEN', 'CLOSED'))
);

-- Membership history is append-only. A member has its own action, approval,
-- launch, current check and result; a new member inherits nothing.
CREATE TABLE ops.lc_batch_member (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    batch_id         uuid        NOT NULL,
    action_id        uuid        NOT NULL,
    sequence_no      integer     NOT NULL,
    membership_state text        NOT NULL,
    recorded_by_user_id uuid     NOT NULL,
    recorded_at      timestamptz NOT NULL,
    CONSTRAINT lc_batch_member_pk PRIMARY KEY (id),
    CONSTRAINT lc_batch_member_batch_fk
        FOREIGN KEY (batch_id, organization_id) REFERENCES ops.lc_batch (id, organization_id),
    CONSTRAINT lc_batch_member_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_batch_member_recorder_fk
        FOREIGN KEY (recorded_by_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_batch_member_sequence_uq UNIQUE (batch_id, action_id, sequence_no),
    CONSTRAINT lc_batch_member_sequence_ck CHECK (sequence_no >= 1),
    CONSTRAINT lc_batch_member_state_ck CHECK (membership_state IN ('ACTIVE', 'REMOVED'))
);

CREATE INDEX lc_batch_member_action_ix ON ops.lc_batch_member (action_id, sequence_no DESC);

CREATE TABLE ops.lc_containment (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    scope_kind          text        NOT NULL,
    platform_listing_id uuid,
    store_id            uuid,
    platform_code       text,
    batch_id            uuid,
    cause_class         text        NOT NULL,
    cause_owner_role_code text      NOT NULL,
    stopped_by_user_id  uuid        NOT NULL,
    stopped_at          timestamptz NOT NULL,
    reason              text        NOT NULL,
    evidence_reference  text        NOT NULL,
    state               text        NOT NULL,
    reenabled_at        timestamptz,
    CONSTRAINT lc_containment_pk PRIMARY KEY (id),
    CONSTRAINT lc_containment_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_containment_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT lc_containment_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_containment_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_containment_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT lc_containment_batch_fk
        FOREIGN KEY (batch_id, organization_id) REFERENCES ops.lc_batch (id, organization_id),
    CONSTRAINT lc_containment_role_fk
        FOREIGN KEY (cause_owner_role_code) REFERENCES iam.business_role (code),
    CONSTRAINT lc_containment_actor_fk
        FOREIGN KEY (stopped_by_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_containment_scope_ck
        CHECK (scope_kind IN ('LISTING', 'STORE', 'PLATFORM', 'ORGANIZATION', 'BATCH')),
    CONSTRAINT lc_containment_scope_shape_ck
        CHECK ((scope_kind = 'LISTING' AND platform_listing_id IS NOT NULL AND store_id IS NULL
                AND platform_code IS NULL AND batch_id IS NULL)
            OR (scope_kind = 'STORE' AND store_id IS NOT NULL AND platform_listing_id IS NULL
                AND platform_code IS NULL AND batch_id IS NULL)
            OR (scope_kind = 'PLATFORM' AND platform_code IS NOT NULL AND platform_listing_id IS NULL
                AND store_id IS NULL AND batch_id IS NULL)
            OR (scope_kind = 'ORGANIZATION' AND platform_listing_id IS NULL AND store_id IS NULL
                AND platform_code IS NULL AND batch_id IS NULL)
            OR (scope_kind = 'BATCH' AND batch_id IS NOT NULL AND platform_listing_id IS NULL
                AND store_id IS NULL AND platform_code IS NULL)),
    CONSTRAINT lc_containment_cause_ck
        CHECK (cause_class IN ('LOCAL_COST', 'SHARED_VERSION', 'PATH_INTEGRITY',
                               'SAFETY_FAILURE', 'PLATFORM_INCIDENT')),
    CONSTRAINT lc_containment_reason_ck CHECK (length(btrim(reason)) BETWEEN 1 AND 2000),
    CONSTRAINT lc_containment_reference_ck CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT lc_containment_state_ck CHECK (state IN ('ACTIVE', 'REENABLED')),
    CONSTRAINT lc_containment_reenabled_ck CHECK ((state = 'REENABLED') = (reenabled_at IS NOT NULL))
);

CREATE INDEX lc_containment_active_ix
    ON ops.lc_containment (organization_id, scope_kind) WHERE state = 'ACTIVE';

-- Reenablement needs the cause owner's repair attestation and the business
-- owner's consent as separate rows by different people.
CREATE TABLE ops.lc_containment_attestation (
    id                 uuid        NOT NULL,
    containment_id     uuid        NOT NULL,
    attestation_kind   text        NOT NULL,
    actor_user_id      uuid        NOT NULL,
    evidence_reference text        NOT NULL,
    attested_at        timestamptz NOT NULL,
    CONSTRAINT lc_containment_attestation_pk PRIMARY KEY (id),
    CONSTRAINT lc_containment_attestation_containment_fk
        FOREIGN KEY (containment_id) REFERENCES ops.lc_containment (id),
    CONSTRAINT lc_containment_attestation_actor_fk
        FOREIGN KEY (actor_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT lc_containment_attestation_uq UNIQUE (containment_id, attestation_kind),
    CONSTRAINT lc_containment_attestation_kind_ck
        CHECK (attestation_kind IN ('REPAIR_ATTESTATION', 'BUSINESS_CONSENT')),
    CONSTRAINT lc_containment_attestation_reference_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512)
);

-- Proven dependencies between listings, along which an isolation may widen.
-- An unknown link is not an independent one, so only a proven row is read.
CREATE TABLE ops.lc_isolation_dependency (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    from_listing_id     uuid        NOT NULL,
    to_listing_id       uuid        NOT NULL,
    dependency_kind     text        NOT NULL,
    proof_reference     text        NOT NULL,
    proven_at           timestamptz NOT NULL,
    recorded_by_user_id uuid        NOT NULL,
    CONSTRAINT lc_isolation_dependency_pk PRIMARY KEY (id),
    CONSTRAINT lc_isolation_dependency_from_fk
        FOREIGN KEY (from_listing_id, organization_id) REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_isolation_dependency_to_fk
        FOREIGN KEY (to_listing_id, organization_id) REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_isolation_dependency_recorder_fk
        FOREIGN KEY (recorded_by_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_isolation_dependency_uq UNIQUE (from_listing_id, to_listing_id, dependency_kind),
    CONSTRAINT lc_isolation_dependency_distinct_ck CHECK (from_listing_id <> to_listing_id),
    CONSTRAINT lc_isolation_dependency_kind_ck
        CHECK (dependency_kind IN ('SHARED_TEMPLATE', 'SHARED_VARIANT_SET', 'SHARED_CAMPAIGN')),
    CONSTRAINT lc_isolation_dependency_proof_ck CHECK (length(btrim(proof_reference)) BETWEEN 1 AND 512)
);

-- Whether any active containment covers a listing, its store, its platform,
-- the organization or a batch it belongs to.
CREATE FUNCTION ops.lc_scope_contained(p_org uuid, p_listing uuid)
RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM ops.lc_containment c
          JOIN core.platform_listing l ON l.id = p_listing AND l.organization_id = p_org
         WHERE c.organization_id = p_org AND c.state = 'ACTIVE'
           AND ((c.scope_kind = 'ORGANIZATION')
             OR (c.scope_kind = 'PLATFORM' AND c.platform_code = l.platform_code)
             OR (c.scope_kind = 'STORE' AND c.store_id = l.store_id)
             OR (c.scope_kind = 'LISTING' AND c.platform_listing_id = l.id)
             OR (c.scope_kind = 'BATCH' AND EXISTS (
                    SELECT 1 FROM ops.lc_batch_member m
                      JOIN ops.lc_action a ON a.id = m.action_id
                     WHERE m.batch_id = c.batch_id AND a.platform_listing_id = l.id
                       AND m.sequence_no = (SELECT max(latest.sequence_no) FROM ops.lc_batch_member latest
                                             WHERE latest.batch_id = m.batch_id AND latest.action_id = m.action_id)
                       AND m.membership_state = 'ACTIVE'))))
$$;
REVOKE ALL ON FUNCTION ops.lc_scope_contained(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_scope_contained(uuid, uuid) TO marketops_app;

CREATE FUNCTION ops.record_lc_containment(
    p_id uuid, p_actor uuid, p_org uuid, p_proof text, p_scope_kind text,
    p_listing uuid, p_store uuid, p_platform text, p_batch uuid,
    p_cause_class text, p_cause_owner_role text, p_reason text, p_evidence text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, iam, pg_temp
AS $$
DECLARE scope_store uuid;
BEGIN
    PERFORM ops.consume_ad_control_invocation(p_proof, 'LISTING_CONTAINMENT_STOP', p_id, p_id);
    scope_store := coalesce(p_store,
        (SELECT l.store_id FROM core.platform_listing l WHERE l.id = p_listing),
        (SELECT b.store_id FROM ops.lc_batch b WHERE b.id = p_batch));
    IF scope_store IS NOT NULL THEN
        IF NOT ops.lc_actor_holds_action(p_actor, p_org, scope_store, 'LISTING_CONTAINMENT_STOP') THEN
            RAISE EXCEPTION 'the actor cannot stop this listing scope' USING ERRCODE = 'MO092';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1 FROM iam.user_role_assignment r
          JOIN iam.business_role_action_scope m ON m.role_code = r.role_code
          JOIN iam.user_scope_grant s ON s.user_id = r.user_id AND s.action_code = m.action_code
         WHERE r.user_id = p_actor AND r.organization_id = p_org AND r.status = 'ACTIVE'
           AND m.action_code = 'LISTING_CONTAINMENT_STOP' AND s.status = 'ACTIVE'
           AND s.organization_ref_id = p_org) THEN
        RAISE EXCEPTION 'an organization-wide stop needs an organization-wide grant'
            USING ERRCODE = 'MO092';
    END IF;
    INSERT INTO ops.lc_containment (id, organization_id, scope_kind, platform_listing_id, store_id,
        platform_code, batch_id, cause_class, cause_owner_role_code, stopped_by_user_id, stopped_at,
        reason, evidence_reference, state)
    VALUES (p_id, p_org, p_scope_kind, p_listing, p_store, p_platform, p_batch, p_cause_class,
        p_cause_owner_role, p_actor, clock_timestamp(), p_reason, p_evidence, 'ACTIVE');
    -- Every live action inside the scope stops. Nothing is resumed by the stop
    -- itself; reenablement is a separate, doubly attested act.
    UPDATE ops.lc_action a
       SET state = 'CONTAINED', updated_at = clock_timestamp(), version = version + 1
     WHERE a.organization_id = p_org
       AND a.state IN ('APPROVED', 'APPROVED_NOT_LAUNCHABLE', 'LAUNCHED')
       AND ops.lc_scope_contained(p_org, a.platform_listing_id);
    RETURN p_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.record_lc_containment(uuid, uuid, uuid, text, text, uuid, uuid, text, uuid, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_lc_containment(uuid, uuid, uuid, text, text, uuid, uuid, text, uuid, text, text, text, text) TO marketops_app;

CREATE FUNCTION ops.attest_lc_containment(
    p_id uuid, p_containment uuid, p_actor uuid, p_proof text, p_kind text, p_evidence text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, iam, pg_temp
AS $$
DECLARE containment ops.lc_containment%ROWTYPE; purpose text; action text;
BEGIN
    SELECT * INTO containment FROM ops.lc_containment WHERE id = p_containment FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'containment does not exist' USING ERRCODE = 'MO090'; END IF;
    IF containment.state <> 'ACTIVE' THEN
        RAISE EXCEPTION 'only an active containment is attested' USING ERRCODE = 'MO091';
    END IF;
    IF p_kind = 'REPAIR_ATTESTATION' THEN
        purpose := 'LISTING_CONTAINMENT_ATTEST'; action := 'LISTING_CONTAINMENT_ATTEST';
    ELSIF p_kind = 'BUSINESS_CONSENT' THEN
        purpose := 'LISTING_CONTAINMENT_CONSENT'; action := 'LISTING_CONTAINMENT_CONSENT';
    ELSE
        RAISE EXCEPTION 'unknown attestation kind' USING ERRCODE = 'MO092';
    END IF;
    PERFORM ops.consume_ad_control_invocation(p_proof, purpose, p_containment, p_containment);
    IF NOT EXISTS (
        SELECT 1 FROM iam.user_role_assignment r
          JOIN iam.business_role_action_scope m ON m.role_code = r.role_code
          JOIN iam.user_scope_grant s ON s.user_id = r.user_id AND s.action_code = m.action_code
         WHERE r.user_id = p_actor AND r.organization_id = containment.organization_id
           AND r.status = 'ACTIVE' AND m.action_code = action AND s.status = 'ACTIVE'
           AND (s.organization_ref_id = containment.organization_id
                OR s.store_ref_id = coalesce(containment.store_id,
                    (SELECT l.store_id FROM core.platform_listing l WHERE l.id = containment.platform_listing_id)))) THEN
        RAISE EXCEPTION 'the actor holds no attestation authority here' USING ERRCODE = 'MO092';
    END IF;
    -- The repair attestation comes from the cause owner's role; the consent
    -- from the business owner. One person cannot give both.
    IF p_kind = 'REPAIR_ATTESTATION' AND NOT EXISTS (
        SELECT 1 FROM iam.user_role_assignment r
         WHERE r.user_id = p_actor AND r.role_code = containment.cause_owner_role_code AND r.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'the repair attestation comes from the cause owner role' USING ERRCODE = 'MO092';
    END IF;
    IF EXISTS (SELECT 1 FROM ops.lc_containment_attestation a
                WHERE a.containment_id = p_containment AND a.actor_user_id = p_actor) THEN
        RAISE EXCEPTION 'one person cannot give both halves of a reenablement' USING ERRCODE = 'MO092';
    END IF;
    INSERT INTO ops.lc_containment_attestation (id, containment_id, attestation_kind, actor_user_id,
        evidence_reference, attested_at)
    VALUES (p_id, p_containment, p_kind, p_actor, p_evidence, clock_timestamp());
    RETURN p_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.attest_lc_containment(uuid, uuid, uuid, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.attest_lc_containment(uuid, uuid, uuid, text, text, text) TO marketops_app;

CREATE FUNCTION ops.reenable_lc_containment(p_containment uuid, p_actor uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE containment ops.lc_containment%ROWTYPE;
BEGIN
    SELECT * INTO containment FROM ops.lc_containment WHERE id = p_containment FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'containment does not exist' USING ERRCODE = 'MO090'; END IF;
    IF containment.state <> 'ACTIVE' THEN
        RAISE EXCEPTION 'the containment is not active' USING ERRCODE = 'MO091';
    END IF;
    IF (SELECT count(DISTINCT attestation_kind) FROM ops.lc_containment_attestation
         WHERE containment_id = p_containment) <> 2
       OR (SELECT count(DISTINCT actor_user_id) FROM ops.lc_containment_attestation
            WHERE containment_id = p_containment) <> 2 THEN
        RAISE EXCEPTION 'reenablement needs a repair attestation and a business consent by different people'
            USING ERRCODE = 'MO092';
    END IF;
    UPDATE ops.lc_containment SET state = 'REENABLED', reenabled_at = clock_timestamp()
     WHERE id = p_containment;
END;
$$;
REVOKE ALL ON FUNCTION ops.reenable_lc_containment(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.reenable_lc_containment(uuid, uuid) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Launch, occupation and release
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_launch (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    action_id           uuid        NOT NULL,
    binding_id          uuid        NOT NULL,
    plan_id             uuid        NOT NULL,
    launched_by_user_id uuid        NOT NULL,
    launched_at         timestamptz NOT NULL,
    proof_hash          text        NOT NULL,
    CONSTRAINT lc_launch_pk PRIMARY KEY (id),
    CONSTRAINT lc_launch_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_launch_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_launch_action_uq UNIQUE (action_id),
    CONSTRAINT lc_launch_binding_fk FOREIGN KEY (binding_id) REFERENCES ops.lc_action_binding (id),
    CONSTRAINT lc_launch_plan_fk FOREIGN KEY (plan_id) REFERENCES ops.lc_evaluation_plan (id),
    CONSTRAINT lc_launch_actor_fk
        FOREIGN KEY (launched_by_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_launch_proof_ck CHECK (proof_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE ops.lc_exposure_occupation (
    id                 uuid           NOT NULL,
    organization_id    uuid           NOT NULL,
    allowance_id       uuid           NOT NULL,
    action_id          uuid           NOT NULL,
    axis_code          text           NOT NULL,
    requested_value    numeric(18, 4) NOT NULL,
    occupied_value     numeric(18, 4) NOT NULL,
    state              text           NOT NULL,
    acquired_at        timestamptz    NOT NULL,
    actualized_at      timestamptz,
    released_at        timestamptz,
    release_basis      text,
    release_evidence_reference text,
    released_by_user_id uuid,
    CONSTRAINT lc_exposure_occupation_pk PRIMARY KEY (id),
    CONSTRAINT lc_exposure_occupation_allowance_fk
        FOREIGN KEY (allowance_id, organization_id) REFERENCES ops.lc_exposure_allowance (id, organization_id),
    CONSTRAINT lc_exposure_occupation_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_exposure_occupation_releaser_fk
        FOREIGN KEY (released_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT lc_exposure_occupation_axis_ck
        CHECK (axis_code IN ('CONCURRENT_LISTINGS', 'AFFECTED_VARIANTS', 'REVENUE_EXPOSURE', 'CATEGORY_SHARE')),
    CONSTRAINT lc_exposure_occupation_values_ck CHECK (requested_value >= 0 AND occupied_value >= 0),
    CONSTRAINT lc_exposure_occupation_state_ck
        CHECK (state IN ('ACQUIRED', 'ACTUAL', 'UNKNOWN', 'RELEASED')),
    -- Released only through the release function, with a basis and evidence.
    -- There is no timer.
    CONSTRAINT lc_exposure_occupation_release_ck
        CHECK ((state = 'RELEASED')
            = (released_at IS NOT NULL AND release_basis IS NOT NULL
               AND release_evidence_reference IS NOT NULL AND released_by_user_id IS NOT NULL)),
    CONSTRAINT lc_exposure_occupation_basis_ck
        CHECK (release_basis IS NULL
            OR release_basis IN ('STOP_EVIDENCE', 'OBLIGATION_CLEARED', 'NOT_APPLIED_PROVEN'))
);

-- Technical retries of one action open no second occupation on an axis.
CREATE UNIQUE INDEX lc_exposure_occupation_live_uq
    ON ops.lc_exposure_occupation (action_id, axis_code) WHERE state <> 'RELEASED';
CREATE INDEX lc_exposure_occupation_allowance_ix
    ON ops.lc_exposure_occupation (allowance_id, state);

-- The most specific active allowance for each axis at one listing's scope.
CREATE FUNCTION ops.lc_allowances_for(p_org uuid, p_listing uuid, p_at timestamptz)
RETURNS SETOF ops.lc_exposure_allowance
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
    WITH scoped AS (
        SELECT a.*,
               CASE a.scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END AS precedence
          FROM ops.lc_exposure_allowance a
          JOIN core.platform_listing l ON l.id = p_listing AND l.organization_id = p_org
         WHERE a.organization_id = p_org AND a.status = 'ACTIVE'
           AND a.effective_from <= p_at AND (a.effective_to IS NULL OR a.effective_to > p_at)
           AND (a.scope_kind = 'ORGANIZATION'
                OR (a.scope_kind = 'PLATFORM' AND a.platform_code = l.platform_code)
                OR (a.scope_kind = 'STORE' AND a.store_ref_id = l.store_id))),
    best AS (
        SELECT axis_code, min(precedence) AS precedence FROM scoped GROUP BY axis_code)
    SELECT s.id, s.organization_id, s.allowance_version, s.scope_kind, s.platform_code, s.store_ref_id,
           s.scope_key, s.axis_code, s.limit_value, s.reserve_value, s.unit_code,
           s.published_by_user_id, s.published_at, s.evidence_reference, s.effective_from,
           s.effective_to, s.status
      FROM scoped s JOIN best b ON b.axis_code = s.axis_code AND b.precedence = s.precedence
$$;
REVOKE ALL ON FUNCTION ops.lc_allowances_for(uuid, uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_allowances_for(uuid, uuid, timestamptz) TO marketops_app;

-- The one launch route. Everything is re-checked here against the rows that
-- exist at this instant, and every axis is acquired or none is.
CREATE FUNCTION ops.acquire_lc_launch_allowance(
    p_launch_id uuid, p_action uuid, p_actor uuid, p_proof text, p_requested jsonb)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, iam, pg_temp
AS $$
DECLARE
    action      ops.lc_action%ROWTYPE;
    binding     ops.lc_action_binding%ROWTYPE;
    plan_row    ops.lc_evaluation_plan%ROWTYPE;
    grant_row   iam.ad_invocation_grant%ROWTYPE;
    gaps        text[];
    allowance   ops.lc_exposure_allowance%ROWTYPE;
    occupied    numeric(18, 4);
    requested   numeric(18, 4);
    short       text[] := '{}';
    axes_seen   integer := 0;
    occupations uuid[] := '{}';
    occupation_id uuid;
    now_at      timestamptz := clock_timestamp();
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = p_action FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'action does not exist' USING ERRCODE = 'MO090'; END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id = p_action;
    IF NOT FOUND THEN RAISE EXCEPTION 'the action has no binding' USING ERRCODE = 'MO092'; END IF;
    grant_row := ops.consume_ad_control_invocation(p_proof, 'LISTING_ACTION_LAUNCH',
        action.recommendation_id, binding.approval_decision_id);
    IF grant_row.actor_user_id <> p_actor OR grant_row.organization_id <> action.organization_id THEN
        RAISE EXCEPTION 'the invocation proof belongs to another person' USING ERRCODE = 'MO092';
    END IF;
    IF NOT ops.lc_actor_holds_action(p_actor, action.organization_id, action.store_id, 'LISTING_ACTION_LAUNCH') THEN
        RAISE EXCEPTION 'the actor cannot launch a listing action here' USING ERRCODE = 'MO092';
    END IF;
    IF action.state NOT IN ('APPROVED', 'APPROVED_NOT_LAUNCHABLE') THEN
        RAISE EXCEPTION 'only an approved action is launched' USING ERRCODE = 'MO091';
    END IF;
    gaps := ops.lc_binding_gaps(p_action);
    IF cardinality(gaps) > 0 THEN
        RAISE EXCEPTION 'the binding no longer applies: %', array_to_string(gaps, ',') USING ERRCODE = 'MO092';
    END IF;
    SELECT * INTO plan_row FROM ops.lc_evaluation_plan WHERE action_id = p_action;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'an evaluation plan is frozen before launch' USING ERRCODE = 'MO092';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mart.lc_listing_health h
                    WHERE h.platform_listing_id = action.platform_listing_id
                      AND h.health_version = (SELECT max(latest.health_version) FROM mart.lc_listing_health latest
                                              WHERE latest.platform_listing_id = action.platform_listing_id)
                      AND h.necessary_state = 'PASS') THEN
        RAISE EXCEPTION 'Listing Health necessary conditions are not passed' USING ERRCODE = 'MO092';
    END IF;
    IF ops.lc_scope_contained(action.organization_id, action.platform_listing_id) THEN
        RAISE EXCEPTION 'the scope is contained' USING ERRCODE = 'MO092';
    END IF;

    -- Every axis, independently, under one advisory lock per allowance row.
    FOR allowance IN SELECT * FROM ops.lc_allowances_for(action.organization_id, action.platform_listing_id, now_at) LOOP
        axes_seen := axes_seen + 1;
        PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_allowance'), hashtext(allowance.id::text));
        requested := CASE allowance.axis_code
            WHEN 'CONCURRENT_LISTINGS' THEN 1
            WHEN 'AFFECTED_VARIANTS' THEN (SELECT cardinality(s.platform_listing_variant_ids)
                                             FROM core.lc_affected_set s WHERE s.id = action.affected_set_id)
            ELSE nullif(p_requested ->> allowance.axis_code, '')::numeric END;
        IF requested IS NULL THEN
            short := array_append(short, allowance.axis_code || ':REQUEST_UNSTATED');
            CONTINUE;
        END IF;
        SELECT coalesce(sum(o.occupied_value), 0) INTO occupied
          FROM ops.lc_exposure_occupation o
         WHERE o.allowance_id = allowance.id AND o.state <> 'RELEASED';
        IF occupied + requested > allowance.limit_value - allowance.reserve_value THEN
            short := array_append(short, allowance.axis_code);
        END IF;
    END LOOP;
    IF axes_seen = 0 THEN
        short := array_append(short, 'ALLOWANCE_UNRESOLVED');
    END IF;
    IF cardinality(short) > 0 THEN
        UPDATE ops.lc_action SET state = 'APPROVED_NOT_LAUNCHABLE', updated_at = now_at, version = version + 1
         WHERE id = p_action AND state = 'APPROVED';
        RETURN jsonb_build_object('launched', false, 'insufficientAxes', to_jsonb(short));
    END IF;

    FOR allowance IN SELECT * FROM ops.lc_allowances_for(action.organization_id, action.platform_listing_id, now_at) LOOP
        requested := CASE allowance.axis_code
            WHEN 'CONCURRENT_LISTINGS' THEN 1
            WHEN 'AFFECTED_VARIANTS' THEN (SELECT cardinality(s.platform_listing_variant_ids)
                                             FROM core.lc_affected_set s WHERE s.id = action.affected_set_id)
            ELSE (p_requested ->> allowance.axis_code)::numeric END;
        occupation_id := gen_random_uuid();
        INSERT INTO ops.lc_exposure_occupation (id, organization_id, allowance_id, action_id, axis_code,
            requested_value, occupied_value, state, acquired_at)
        VALUES (occupation_id, action.organization_id, allowance.id, p_action, allowance.axis_code,
            requested, requested, 'ACQUIRED', now_at);
        occupations := array_append(occupations, occupation_id);
    END LOOP;

    INSERT INTO ops.lc_launch (id, organization_id, action_id, binding_id, plan_id, launched_by_user_id,
        launched_at, proof_hash)
    VALUES (p_launch_id, action.organization_id, p_action, binding.id, plan_row.id, p_actor, now_at,
        grant_row.proof_hash);
    UPDATE ops.lc_action SET state = 'LAUNCHED', updated_at = now_at, version = version + 1
     WHERE id = p_action;
    RETURN jsonb_build_object('launched', true, 'launchId', p_launch_id,
        'occupationIds', to_jsonb(occupations));
END;
$$;
REVOKE ALL ON FUNCTION ops.acquire_lc_launch_allowance(uuid, uuid, uuid, text, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.acquire_lc_launch_allowance(uuid, uuid, uuid, text, jsonb) TO marketops_app;

-- An occupation moves to ACTUAL or UNKNOWN from execution facts, and is
-- released only with a basis and evidence.
CREATE FUNCTION ops.observe_lc_occupation(p_occupation uuid, p_state text, p_occupied numeric)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    IF p_state NOT IN ('ACTUAL', 'UNKNOWN') THEN
        RAISE EXCEPTION 'an observation is ACTUAL or UNKNOWN' USING ERRCODE = 'MO091';
    END IF;
    UPDATE ops.lc_exposure_occupation
       SET state = p_state, occupied_value = coalesce(p_occupied, occupied_value),
           actualized_at = clock_timestamp()
     WHERE id = p_occupation AND state <> 'RELEASED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a released occupation is not observed' USING ERRCODE = 'MO091';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION ops.observe_lc_occupation(uuid, text, numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.observe_lc_occupation(uuid, text, numeric) TO marketops_app;

CREATE FUNCTION ops.release_lc_occupation(
    p_occupation uuid, p_actor uuid, p_proof text, p_basis text, p_evidence_id uuid, p_evidence text)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, iam, pg_temp
AS $$
DECLARE occupation ops.lc_exposure_occupation%ROWTYPE; action ops.lc_action%ROWTYPE; proven boolean := false;
BEGIN
    SELECT * INTO occupation FROM ops.lc_exposure_occupation WHERE id = p_occupation FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'occupation does not exist' USING ERRCODE = 'MO090'; END IF;
    IF occupation.state = 'RELEASED' THEN
        RAISE EXCEPTION 'the occupation is already released' USING ERRCODE = 'MO091';
    END IF;
    SELECT * INTO action FROM ops.lc_action WHERE id = occupation.action_id;
    PERFORM ops.consume_ad_control_invocation(p_proof, 'LISTING_OCCUPATION_RELEASE', p_occupation, p_occupation);
    IF NOT ops.lc_actor_holds_action(p_actor, action.organization_id, action.store_id, 'LISTING_ACTION_LAUNCH') THEN
        RAISE EXCEPTION 'the actor cannot release an occupation here' USING ERRCODE = 'MO092';
    END IF;
    -- Each basis names the evidence that proves it. No basis is a timer.
    IF p_basis = 'STOP_EVIDENCE' THEN
        proven := EXISTS (SELECT 1 FROM core.lc_display_observation d
                           WHERE d.id = p_evidence_id AND d.platform_listing_id = action.platform_listing_id
                             AND d.observed_at >= occupation.acquired_at)
               OR EXISTS (SELECT 1 FROM core.lc_description_observation o
                           WHERE o.id = p_evidence_id AND o.platform_listing_id = action.platform_listing_id
                             AND o.observed_at >= occupation.acquired_at);
    ELSIF p_basis = 'OBLIGATION_CLEARED' THEN
        proven := EXISTS (SELECT 1 FROM ops.lc_promotion_engagement e
                           WHERE e.id = p_evidence_id AND e.action_id = action.id
                             AND e.obligations_cleared_at IS NOT NULL);
    ELSIF p_basis = 'NOT_APPLIED_PROVEN' THEN
        proven := EXISTS (SELECT 1 FROM ops.lc_manual_verification v
                           JOIN ops.lc_manual_packet p ON p.id = v.packet_id
                          WHERE v.id = p_evidence_id AND p.action_id = action.id
                            AND v.management_match = 'MATCHED_PRIOR');
    ELSE
        RAISE EXCEPTION 'unknown release basis' USING ERRCODE = 'MO092';
    END IF;
    IF NOT proven THEN
        RAISE EXCEPTION 'the release basis is not proven by the named evidence' USING ERRCODE = 'MO092';
    END IF;
    UPDATE ops.lc_exposure_occupation
       SET state = 'RELEASED', released_at = clock_timestamp(), release_basis = p_basis,
           release_evidence_reference = p_evidence, released_by_user_id = p_actor
     WHERE id = p_occupation;
END;
$$;

-- ---------------------------------------------------------------------------
-- The governed manual path
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_manual_packet (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    action_id           uuid        NOT NULL,
    launch_id           uuid        NOT NULL,
    executor_user_id    uuid        NOT NULL,
    issued_by_user_id   uuid        NOT NULL,
    issued_at           timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    native_listing_key  text        NOT NULL,
    affected_set_digest text        NOT NULL,
    target_text         text,
    execution_path      text        NOT NULL,
    state               text        NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT lc_manual_packet_pk PRIMARY KEY (id),
    CONSTRAINT lc_manual_packet_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_manual_packet_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_manual_packet_launch_fk
        FOREIGN KEY (launch_id, organization_id) REFERENCES ops.lc_launch (id, organization_id),
    CONSTRAINT lc_manual_packet_launch_uq UNIQUE (launch_id),
    CONSTRAINT lc_manual_packet_executor_fk
        FOREIGN KEY (executor_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_manual_packet_issuer_fk
        FOREIGN KEY (issued_by_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_manual_packet_path_ck CHECK (execution_path = 'MANUAL'),
    CONSTRAINT lc_manual_packet_key_ck CHECK (length(btrim(native_listing_key)) BETWEEN 1 AND 128),
    CONSTRAINT lc_manual_packet_digest_ck CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT lc_manual_packet_expiry_ck CHECK (expires_at > issued_at),
    CONSTRAINT lc_manual_packet_state_ck
        CHECK (state IN ('ISSUED', 'REPORTED', 'VERIFIED', 'EXPIRED', 'WITHDRAWN'))
);

CREATE FUNCTION ops.lc_manual_packet_requires_launch()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE action ops.lc_action%ROWTYPE; launch ops.lc_launch%ROWTYPE; binding ops.lc_action_binding%ROWTYPE;
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = NEW.action_id;
    SELECT * INTO launch FROM ops.lc_launch WHERE id = NEW.launch_id;
    IF action.id IS NULL OR launch.id IS NULL OR launch.action_id <> action.id THEN
        RAISE EXCEPTION 'a packet is issued from its own launch' USING ERRCODE = 'MO090';
    END IF;
    IF action.state <> 'LAUNCHED' OR action.execution_path <> 'MANUAL' THEN
        RAISE EXCEPTION 'a packet is issued only from a launched manual action' USING ERRCODE = 'MO091';
    END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE id = launch.binding_id;
    IF NEW.affected_set_digest <> action.affected_set_digest
        OR NEW.target_text IS DISTINCT FROM action.target_text
        OR NEW.expires_at > binding.expires_at THEN
        RAISE EXCEPTION 'a packet carries the exact bound material and never outlives the binding'
            USING ERRCODE = 'MO092';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_manual_packet_requires_launch() FROM PUBLIC;
CREATE TRIGGER lc_manual_packet_requires_launch
    BEFORE INSERT ON ops.lc_manual_packet
    FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_packet_requires_launch();

CREATE TABLE ops.lc_manual_report (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    packet_id        uuid        NOT NULL,
    reporter_user_id uuid        NOT NULL,
    operation_time   timestamptz NOT NULL,
    reported_at      timestamptz NOT NULL,
    report_state     text        NOT NULL,
    note             text        NOT NULL,
    CONSTRAINT lc_manual_report_pk PRIMARY KEY (id),
    CONSTRAINT lc_manual_report_packet_fk
        FOREIGN KEY (packet_id, organization_id) REFERENCES ops.lc_manual_packet (id, organization_id),
    CONSTRAINT lc_manual_report_reporter_fk
        FOREIGN KEY (reporter_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_manual_report_state_ck CHECK (report_state IN ('APPLIED', 'NOT_APPLIED', 'PARTIAL')),
    CONSTRAINT lc_manual_report_note_ck CHECK (length(btrim(note)) BETWEEN 1 AND 2000),
    CONSTRAINT lc_manual_report_times_ck CHECK (reported_at >= operation_time)
);

CREATE INDEX lc_manual_report_packet_ix ON ops.lc_manual_report (packet_id, reported_at DESC);

-- The executor's own report. It verifies nothing.
CREATE FUNCTION ops.lc_manual_report_by_executor()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE packet ops.lc_manual_packet%ROWTYPE;
BEGIN
    SELECT * INTO packet FROM ops.lc_manual_packet WHERE id = NEW.packet_id;
    IF NOT FOUND OR packet.executor_user_id <> NEW.reporter_user_id THEN
        RAISE EXCEPTION 'a manual report comes from the packet executor' USING ERRCODE = 'MO092';
    END IF;
    IF packet.state NOT IN ('ISSUED', 'REPORTED') THEN
        RAISE EXCEPTION 'the packet is not open for a report' USING ERRCODE = 'MO091';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_manual_report_by_executor() FROM PUBLIC;
CREATE TRIGGER lc_manual_report_by_executor
    BEFORE INSERT ON ops.lc_manual_report
    FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_report_by_executor();

CREATE TABLE ops.lc_manual_verification (
    id                        uuid        NOT NULL,
    organization_id           uuid        NOT NULL,
    packet_id                 uuid        NOT NULL,
    verifier_user_id          uuid,
    verification_basis        text        NOT NULL,
    management_match          text        NOT NULL,
    management_observation_id uuid,
    display_observation_id    uuid,
    display_state             text        NOT NULL,
    verified_at               timestamptz NOT NULL,
    note                      text        NOT NULL,
    CONSTRAINT lc_manual_verification_pk PRIMARY KEY (id),
    CONSTRAINT lc_manual_verification_packet_fk
        FOREIGN KEY (packet_id, organization_id) REFERENCES ops.lc_manual_packet (id, organization_id),
    CONSTRAINT lc_manual_verification_verifier_fk
        FOREIGN KEY (verifier_user_id, organization_id) REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_manual_verification_management_fk
        FOREIGN KEY (management_observation_id, organization_id)
        REFERENCES core.lc_description_observation (id, organization_id),
    CONSTRAINT lc_manual_verification_display_fk
        FOREIGN KEY (display_observation_id, organization_id)
        REFERENCES core.lc_display_observation (id, organization_id),
    CONSTRAINT lc_manual_verification_basis_ck
        CHECK (verification_basis IN ('INDEPENDENT_HUMAN', 'OFFICIAL_EVIDENCE')),
    CONSTRAINT lc_manual_verification_verifier_ck
        CHECK ((verification_basis = 'INDEPENDENT_HUMAN') = (verifier_user_id IS NOT NULL)),
    -- Management match and display are separate answers. Neither is inferred
    -- from the other.
    CONSTRAINT lc_manual_verification_match_ck
        CHECK (management_match IN ('MATCHED_TARGET', 'MATCHED_PRIOR', 'DIFFERENT', 'UNKNOWN')),
    CONSTRAINT lc_manual_verification_display_ck
        CHECK (display_state IN ('DISPLAYED', 'NOT_DISPLAYED', 'UNKNOWN')),
    CONSTRAINT lc_manual_verification_management_evidence_ck
        CHECK (management_match = 'UNKNOWN' OR management_observation_id IS NOT NULL),
    CONSTRAINT lc_manual_verification_display_evidence_ck
        CHECK (display_state = 'UNKNOWN' OR display_observation_id IS NOT NULL),
    CONSTRAINT lc_manual_verification_note_ck CHECK (length(btrim(note)) BETWEEN 1 AND 2000)
);

CREATE INDEX lc_manual_verification_packet_ix ON ops.lc_manual_verification (packet_id, verified_at DESC);

-- Verification is by a distinct person or by official evidence; the executor
-- and the reporter cannot verify their own work, and a claimed match must name
-- an observation whose digest actually equals the target or the prior.
CREATE FUNCTION ops.lc_manual_verification_is_independent()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE packet ops.lc_manual_packet%ROWTYPE; action ops.lc_action%ROWTYPE; observed text;
BEGIN
    SELECT * INTO packet FROM ops.lc_manual_packet WHERE id = NEW.packet_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'verification names an unknown packet' USING ERRCODE = 'MO090'; END IF;
    SELECT * INTO action FROM ops.lc_action WHERE id = packet.action_id;
    IF NEW.verifier_user_id IS NOT NULL AND (NEW.verifier_user_id = packet.executor_user_id
        OR EXISTS (SELECT 1 FROM ops.lc_manual_report r
                    WHERE r.packet_id = packet.id AND r.reporter_user_id = NEW.verifier_user_id)) THEN
        RAISE EXCEPTION 'the executor does not verify the execution' USING ERRCODE = 'MO092';
    END IF;
    IF NEW.verification_basis = 'OFFICIAL_EVIDENCE' AND NOT EXISTS (
        SELECT 1 FROM core.lc_description_observation o
          JOIN core.fact_provenance p ON p.id = o.provenance_id
         WHERE o.id = NEW.management_observation_id AND p.source_kind = 'MARKETPLACE_RAW') THEN
        RAISE EXCEPTION 'official evidence is a marketplace-sourced observation' USING ERRCODE = 'MO092';
    END IF;
    IF NEW.management_observation_id IS NOT NULL THEN
        SELECT o.text_digest INTO observed FROM core.lc_description_observation o
         WHERE o.id = NEW.management_observation_id AND o.platform_listing_id = action.platform_listing_id
           AND o.observed_at >= packet.issued_at;
        IF observed IS NULL THEN
            RAISE EXCEPTION 'a verification observation is of this listing after the packet was issued'
                USING ERRCODE = 'MO092';
        END IF;
        IF (NEW.management_match = 'MATCHED_TARGET' AND observed IS DISTINCT FROM action.target_text_digest)
            OR (NEW.management_match = 'MATCHED_PRIOR' AND observed IS DISTINCT FROM action.current_text_digest)
            OR (NEW.management_match = 'DIFFERENT'
                AND (observed = action.target_text_digest OR observed = action.current_text_digest)) THEN
            RAISE EXCEPTION 'the claimed management match contradicts the observation digest'
                USING ERRCODE = 'MO093';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_manual_verification_is_independent() FROM PUBLIC;
CREATE TRIGGER lc_manual_verification_is_independent
    BEFORE INSERT ON ops.lc_manual_verification
    FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_verification_is_independent();

-- ---------------------------------------------------------------------------
-- Simple promotion engagements
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_promotion_engagement (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    store_id                    uuid        NOT NULL,
    platform_listing_id         uuid        NOT NULL,
    action_id                   uuid,
    engagement_kind             text        NOT NULL,
    native_promotion_key        text,
    terms                       jsonb       NOT NULL,
    price_freeze                boolean     NOT NULL,
    auto_participation          boolean     NOT NULL,
    terms_evidence_reference    text        NOT NULL,
    adopted                     boolean     NOT NULL,
    obligations                 jsonb       NOT NULL,
    exit_reason_code            text,
    exit_authorized_by_user_id  uuid,
    exit_authorized_at          timestamptz,
    new_transactions_stopped_at timestamptz,
    obligations_cleared_at      timestamptz,
    state                       text        NOT NULL,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    version                     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT lc_promotion_engagement_pk PRIMARY KEY (id),
    CONSTRAINT lc_promotion_engagement_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_promotion_engagement_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_promotion_engagement_action_fk
        FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action (id, organization_id),
    CONSTRAINT lc_promotion_engagement_exit_actor_fk
        FOREIGN KEY (exit_authorized_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT lc_promotion_engagement_kind_ck
        CHECK (engagement_kind IN ('OFFICIAL_PROMOTION_PARTICIPATION', 'SELLER_DIRECT_DISCOUNT')),
    CONSTRAINT lc_promotion_engagement_key_ck
        CHECK (native_promotion_key IS NULL OR length(btrim(native_promotion_key)) BETWEEN 1 AND 128),
    CONSTRAINT lc_promotion_engagement_terms_ck
        CHECK (jsonb_typeof(terms) = 'object' AND jsonb_typeof(obligations) = 'object'),
    CONSTRAINT lc_promotion_engagement_reference_ck
        CHECK (length(btrim(terms_evidence_reference)) BETWEEN 1 AND 512),
    -- An adopted engagement has no action of this product behind it; one this
    -- product entered does.
    CONSTRAINT lc_promotion_engagement_origin_ck CHECK (adopted = (action_id IS NULL)),
    CONSTRAINT lc_promotion_engagement_exit_reason_ck
        CHECK (exit_reason_code IS NULL OR exit_reason_code IN (
            'MARGIN_BELOW_BOUND', 'RETURN_RATE_ABOVE_BOUND', 'SUPPLY_COVERAGE_LOST',
            'PLATFORM_TERMS_CHANGED', 'OWNER_DECISION')),
    CONSTRAINT lc_promotion_engagement_exit_shape_ck
        CHECK ((exit_reason_code IS NULL) = (exit_authorized_by_user_id IS NULL)
            AND (exit_reason_code IS NULL) = (exit_authorized_at IS NULL)),
    -- Two separate releases, in order: new transactions stop, then obligations
    -- clear. Neither happens without an authorised exit.
    CONSTRAINT lc_promotion_engagement_release_order_ck
        CHECK ((new_transactions_stopped_at IS NULL OR exit_authorized_at IS NOT NULL)
            AND (obligations_cleared_at IS NULL OR new_transactions_stopped_at IS NOT NULL)
            AND (obligations_cleared_at IS NULL OR obligations_cleared_at >= new_transactions_stopped_at)),
    CONSTRAINT lc_promotion_engagement_state_ck
        CHECK (state IN ('ACTIVE', 'EXITING', 'STOPPED', 'CLEARED')),
    CONSTRAINT lc_promotion_engagement_state_shape_ck
        CHECK ((state = 'ACTIVE' AND exit_authorized_at IS NULL)
            OR (state = 'EXITING' AND exit_authorized_at IS NOT NULL AND new_transactions_stopped_at IS NULL)
            OR (state = 'STOPPED' AND new_transactions_stopped_at IS NOT NULL AND obligations_cleared_at IS NULL)
            OR (state = 'CLEARED' AND obligations_cleared_at IS NOT NULL))
);

CREATE INDEX lc_promotion_engagement_listing_ix
    ON ops.lc_promotion_engagement (platform_listing_id, state);

CREATE FUNCTION ops.authorize_lc_promotion_exit(
    p_engagement uuid, p_actor uuid, p_proof text, p_reason_code text)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, iam, pg_temp
AS $$
DECLARE engagement ops.lc_promotion_engagement%ROWTYPE;
BEGIN
    SELECT * INTO engagement FROM ops.lc_promotion_engagement WHERE id = p_engagement FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'engagement does not exist' USING ERRCODE = 'MO090'; END IF;
    IF engagement.state <> 'ACTIVE' THEN
        RAISE EXCEPTION 'only an active engagement is exited' USING ERRCODE = 'MO091';
    END IF;
    PERFORM ops.consume_ad_control_invocation(p_proof, 'LISTING_PROMOTION_EXIT', p_engagement, p_engagement);
    IF NOT ops.lc_actor_holds_action(p_actor, engagement.organization_id, engagement.store_id,
                                     'LISTING_PROMOTION_MANAGE') THEN
        RAISE EXCEPTION 'the actor cannot exit a promotion here' USING ERRCODE = 'MO092';
    END IF;
    UPDATE ops.lc_promotion_engagement
       SET exit_reason_code = p_reason_code, exit_authorized_by_user_id = p_actor,
           exit_authorized_at = clock_timestamp(), state = 'EXITING',
           updated_at = clock_timestamp(), version = version + 1
     WHERE id = p_engagement;
END;
$$;
REVOKE ALL ON FUNCTION ops.authorize_lc_promotion_exit(uuid, uuid, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.authorize_lc_promotion_exit(uuid, uuid, text, text) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Action state moves only along reviewed edges, with the evidence each needs
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.lc_action_moves_lawfully()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    IF NEW.state = OLD.state THEN
        IF OLD.state NOT IN ('DRAFT') AND (
            NEW.target_text_digest IS DISTINCT FROM OLD.target_text_digest
            OR NEW.current_text_digest IS DISTINCT FROM OLD.current_text_digest
            OR NEW.affected_set_digest <> OLD.affected_set_digest
            OR NEW.execution_path <> OLD.execution_path
            OR NEW.materiality_route <> OLD.materiality_route) THEN
            RAISE EXCEPTION 'a reviewed action does not change what was reviewed' USING ERRCODE = 'MO092';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_action_transition t
                    WHERE t.from_state = OLD.state AND t.to_state = NEW.state) THEN
        RAISE EXCEPTION 'transition % -> % is not in the reviewed set', OLD.state, NEW.state
            USING ERRCODE = 'MO091';
    END IF;
    IF NEW.state = 'REVIEWED' AND NOT EXISTS (
        SELECT 1 FROM ops.lc_action_review r
         WHERE r.action_id = NEW.id AND r.verdict = 'ATTESTED'
           AND r.attested_affected_set_digest = NEW.affected_set_digest
           AND r.attested_target_text_digest IS NOT DISTINCT FROM NEW.target_text_digest) THEN
        RAISE EXCEPTION 'REVIEWED needs an attestation of the exact action' USING ERRCODE = 'MO092';
    END IF;
    IF NEW.state = 'APPROVED' AND NOT EXISTS (
        SELECT 1 FROM ops.lc_action_binding b WHERE b.action_id = NEW.id AND b.state = 'BOUND') THEN
        RAISE EXCEPTION 'APPROVED needs a frozen binding' USING ERRCODE = 'MO092';
    END IF;
    IF NEW.state = 'LAUNCHED' AND NOT EXISTS (SELECT 1 FROM ops.lc_launch l WHERE l.action_id = NEW.id) THEN
        RAISE EXCEPTION 'LAUNCHED is reached only through the launch function' USING ERRCODE = 'MO092';
    END IF;
    IF NEW.state = 'VERIFIED' AND NOT EXISTS (
        SELECT 1 FROM ops.lc_manual_verification v
          JOIN ops.lc_manual_packet p ON p.id = v.packet_id
         WHERE p.action_id = NEW.id AND v.management_match = 'MATCHED_TARGET')
       AND NOT EXISTS (
        SELECT 1 FROM ops.lc_description_command c
         WHERE c.action_id = NEW.id AND c.state = 'READBACK_MATCHED') THEN
        RAISE EXCEPTION 'VERIFIED needs a matched verification or a matched readback' USING ERRCODE = 'MO092';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_action_moves_lawfully() FROM PUBLIC;
CREATE TRIGGER lc_action_moves_lawfully
    BEFORE UPDATE ON ops.lc_action
    FOR EACH ROW EXECUTE FUNCTION ops.lc_action_moves_lawfully();

REVOKE ALL ON FUNCTION ops.release_lc_occupation(uuid, uuid, text, text, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.release_lc_occupation(uuid, uuid, text, text, uuid, text) TO marketops_app;

-- ---------------------------------------------------------------------------
-- The listing decision authority, described by the database
-- ---------------------------------------------------------------------------

-- A stable description of what a listing decision rests on: the proposal, the
-- exact action, its latest review and the calibration it names. A guardrail
-- verdict carries this document and an approval must find a PASS carrying the
-- same one, so an action that moved after its evaluation cannot be approved.
CREATE FUNCTION ops.lc_authority_snapshot(p_recommendation_id uuid)
RETURNS jsonb
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
    SELECT jsonb_build_object(
        'proposal', jsonb_build_object(
            'id', r.id, 'organizationId', r.organization_id, 'storeId', r.store_id,
            'subjectKind', r.subject_kind, 'subjectId', r.subject_id,
            'actionKind', r.action_kind, 'parameters', r.proposed_parameters,
            'risk', r.risk_label, 'window', r.window_code,
            'validUntil', r.valid_until, 'entityDigest', r.entity_version_digest),
        'currentEntityDigest', r.entity_version_digest,
        'action', CASE WHEN a.id IS NULL THEN NULL ELSE jsonb_build_object(
            'id', a.id, 'version', a.version, 'state', a.state,
            'executionPath', a.execution_path, 'materialityRoute', a.materiality_route,
            'contentAxisMaterial', a.content_axis_material,
            'exposureAxisMaterial', a.exposure_axis_material,
            'affectedSetDigest', a.affected_set_digest,
            'targetTextDigest', a.target_text_digest,
            'currentTextDigest', a.current_text_digest,
            'kizMarkedDeclared', a.kiz_marked_declared,
            'authorUserId', a.author_user_id) END,
        'review', review.item,
        'calibrationPackageId', a.calibration_package_id,
        'calibrationVersion', a.calibration_version)
      FROM ops.recommendation r
      LEFT JOIN ops.lc_action a ON a.recommendation_id = r.id AND a.organization_id = r.organization_id
      LEFT JOIN LATERAL (
          SELECT jsonb_build_object('id', v.id, 'reviewerUserId', v.reviewer_user_id,
                     'verdict', v.verdict, 'reviewedAt', v.reviewed_at) AS item
            FROM ops.lc_action_review v
           WHERE v.action_id = a.id
           ORDER BY v.reviewed_at DESC, v.id DESC LIMIT 1
      ) review ON true
     WHERE r.id = p_recommendation_id
$$;
REVOKE ALL ON FUNCTION ops.lc_authority_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_authority_snapshot(uuid) TO marketops_app;

-- The one authority-binding trigger gains a listing branch. The price and
-- advertising branches are the text of V0051, unchanged.
CREATE OR REPLACE FUNCTION ops.bind_price_authority_snapshot()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE snapshot jsonb;
        evaluated_snapshot jsonb;
        decided_action text;
BEGIN
    SELECT r.action_kind INTO decided_action
      FROM ops.recommendation r WHERE r.id = NEW.recommendation_id;
    IF decided_action IS NULL THEN
        RAISE EXCEPTION 'decision names no recommendation' USING ERRCODE = 'MO032';
    END IF;

    IF decided_action IN ('LISTING_DESCRIPTION_CHANGE', 'LISTING_PROMOTION_ACTION') THEN
        SELECT ops.lc_authority_snapshot(NEW.recommendation_id) INTO snapshot;
        IF snapshot IS NULL OR snapshot #>> '{proposal,organizationId}'
                IS DISTINCT FROM NEW.organization_id::text THEN
            RAISE EXCEPTION 'recommendation ownership does not match' USING ERRCODE = 'MO032';
        END IF;
        IF TG_TABLE_NAME = 'guardrail_evaluation' THEN
            IF NEW.authority_snapshot IS DISTINCT FROM snapshot THEN
                RAISE EXCEPTION 'guardrail inputs changed' USING ERRCODE = 'MO032';
            END IF;
            IF NEW.outcome = 'PASS' AND (snapshot -> 'action') IS NULL THEN
                RAISE EXCEPTION 'a listing PASS names an exact action' USING ERRCODE = 'MO032';
            END IF;
        ELSIF TG_TABLE_NAME = 'approval_decision' THEN
            IF NEW.decision IN ('APPROVED', 'POLICY_AUTHORIZED') THEN
                IF NEW.decision = 'POLICY_AUTHORIZED' THEN
                    RAISE EXCEPTION 'a listing action is approved by a person' USING ERRCODE = 'MO032';
                END IF;
                IF NEW.entity_version_digest IS DISTINCT FROM snapshot ->> 'currentEntityDigest'
                   OR NOT EXISTS (
                       SELECT 1 FROM ops.guardrail_evaluation g
                        WHERE g.recommendation_id = NEW.recommendation_id
                          AND g.organization_id = NEW.organization_id
                          AND g.purpose = 'APPROVAL' AND g.outcome = 'PASS'
                          AND g.authority_snapshot = snapshot) THEN
                    RAISE EXCEPTION 'approval has no matching current guardrail authority'
                        USING ERRCODE = 'MO032';
                END IF;
                -- The person approving is not the person who wrote the action.
                IF NEW.decided_by_user_id IS NOT DISTINCT FROM (snapshot #>> '{action,authorUserId}')::uuid THEN
                    RAISE EXCEPTION 'the author of a listing action does not approve it'
                        USING ERRCODE = 'MO032';
                END IF;
            END IF;
            NEW.authority_snapshot := snapshot;
        ELSE
            NEW.authority_snapshot := snapshot;
        END IF;
        RETURN NEW;
    END IF;

    IF decided_action = 'AD_BID_CHANGE' THEN
        SELECT ops.ad_bid_authority_snapshot(NEW.recommendation_id) INTO snapshot;
        IF snapshot IS NULL OR snapshot #>> '{proposal,organizationId}'
                IS DISTINCT FROM NEW.organization_id::text THEN
            RAISE EXCEPTION 'recommendation ownership does not match' USING ERRCODE = 'MO032';
        END IF;
        IF TG_TABLE_NAME = 'guardrail_evaluation' THEN
            IF NEW.authority_snapshot IS DISTINCT FROM snapshot THEN
                RAISE EXCEPTION 'guardrail inputs changed' USING ERRCODE = 'MO032';
            END IF;
        ELSIF TG_TABLE_NAME = 'approval_decision' THEN
            IF NEW.decision IN ('APPROVED', 'POLICY_AUTHORIZED') THEN
                IF NEW.entity_version_digest IS DISTINCT FROM snapshot ->> 'currentEntityDigest'
                   OR NEW.entity_version_digest
                       IS DISTINCT FROM snapshot #>> '{proposal,entityDigest}'
                   OR NOT EXISTS (
                       SELECT 1 FROM ops.guardrail_evaluation g
                        WHERE g.recommendation_id = NEW.recommendation_id
                          AND g.organization_id = NEW.organization_id
                          AND g.purpose = 'APPROVAL' AND g.outcome = 'PASS'
                          AND g.authority_snapshot = snapshot) THEN
                    RAISE EXCEPTION 'approval has no matching current guardrail authority'
                        USING ERRCODE = 'MO032';
                END IF;
            END IF;
            NEW.authority_snapshot := snapshot;
        ELSE
            NEW.authority_snapshot := snapshot;
        END IF;
        RETURN NEW;
    END IF;

    SELECT ops.price_authority_snapshot(NEW.recommendation_id) INTO snapshot;
    IF snapshot IS NULL OR snapshot #>> '{proposal,organizationId}'
            IS DISTINCT FROM NEW.organization_id::text THEN
        RAISE EXCEPTION 'recommendation ownership does not match' USING ERRCODE = 'MO032';
    END IF;
    IF TG_TABLE_NAME = 'guardrail_evaluation' THEN
        SELECT ops.price_authority_snapshot(NEW.recommendation_id, NEW.evaluated_at)
          INTO evaluated_snapshot;
        IF NEW.authority_snapshot IS DISTINCT FROM evaluated_snapshot THEN
            RAISE EXCEPTION 'guardrail inputs do not match evaluation as-of authority'
                USING ERRCODE = 'MO032';
        END IF;
        IF NEW.outcome = 'PASS'
           AND (NEW.authority_snapshot IS DISTINCT FROM snapshot
                OR NOT ops.r2_price_authority_is_current(
                    NEW.authority_snapshot, statement_timestamp())) THEN
            RAISE EXCEPTION 'guardrail authority is stale or incomplete'
                USING ERRCODE = 'MO032';
        END IF;
    ELSIF TG_TABLE_NAME = 'approval_decision' THEN
        IF NEW.decision IN ('APPROVED', 'POLICY_AUTHORIZED') THEN
            IF NOT ops.r2_price_authority_is_current(snapshot, statement_timestamp())
               OR NEW.entity_version_digest IS DISTINCT FROM snapshot ->> 'currentEntityDigest'
               OR NEW.entity_version_digest IS DISTINCT FROM snapshot #>> '{proposal,entityDigest}'
               OR NOT EXISTS (
                   SELECT 1 FROM ops.guardrail_evaluation g
                    WHERE g.recommendation_id = NEW.recommendation_id
                      AND g.organization_id = NEW.organization_id
                      AND g.purpose = 'APPROVAL' AND g.outcome = 'PASS'
                      AND g.authority_snapshot = snapshot) THEN
                RAISE EXCEPTION 'approval has no matching current guardrail authority'
                    USING ERRCODE = 'MO032';
            END IF;
        END IF;
        NEW.authority_snapshot := snapshot;
    ELSE
        IF NOT ops.r2_price_authority_is_current(snapshot, statement_timestamp()) THEN
            RAISE EXCEPTION 'command authority is stale or incomplete'
                USING ERRCODE = 'MO032';
        END IF;
        NEW.authority_snapshot := snapshot;
        NEW.fulfillment_mode_code := snapshot #>> '{economics,fulfillmentModeCode}';
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
-- Route inventory and privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'lc_candidate', 'NO_ROUTE', NULL,
        'comparable candidate of one round with evidence references'),
    ('ops', 'lc_action', 'NO_ROUTE', NULL,
        'the one exact listing action; state moves along the reviewed transition set'),
    ('ops', 'lc_action_transition', 'NO_ROUTE', NULL,
        'the reviewed action transition graph as data'),
    ('ops', 'lc_action_review', 'NO_ROUTE', NULL,
        'independent professional review attesting the exact digests; append-only'),
    ('ops', 'lc_action_binding', 'NO_ROUTE', NULL,
        'frozen binding of approval, guardrail, digests, versions and earliest expiry'),
    ('ops', 'lc_evaluation_plan', 'NO_ROUTE', NULL,
        'evaluation plan frozen before launch; append-only'),
    ('ops', 'lc_batch', 'NO_ROUTE', NULL,
        'bounded batch of independently governed member actions'),
    ('ops', 'lc_batch_member', 'NO_ROUTE', NULL,
        'append-only batch membership history'),
    ('ops', 'lc_containment', 'NO_ROUTE', NULL,
        'technical or business stop at exact scope; written only through its function'),
    ('ops', 'lc_containment_attestation', 'NO_ROUTE', NULL,
        'repair attestation and business consent as separate rows; written only through its function'),
    ('ops', 'lc_isolation_dependency', 'NO_ROUTE', NULL,
        'proven dependency along which an isolation may widen'),
    ('ops', 'lc_launch', 'NO_ROUTE', NULL,
        'the one launch of an action; written only by the launch function'),
    ('ops', 'lc_exposure_occupation', 'NO_ROUTE', NULL,
        'per-axis allowance occupation; acquired, observed and released only through functions'),
    ('ops', 'lc_manual_packet', 'NO_ROUTE', NULL,
        'governed manual packet issued from a launched manual action'),
    ('ops', 'lc_manual_report', 'NO_ROUTE', NULL,
        'the executor''s own report; verifies nothing; append-only'),
    ('ops', 'lc_manual_verification', 'NO_ROUTE', NULL,
        'independent verification of management match and display; append-only'),
    ('ops', 'lc_promotion_engagement', 'NO_ROUTE', NULL,
        'simple promotion engagement with obligations and two separate releases');

GRANT SELECT, INSERT, UPDATE (state, updated_at, version) ON ops.lc_candidate TO marketops_app;
GRANT SELECT, INSERT,
      UPDATE (state, updated_at, version, materiality_route, content_axis_material,
              exposure_axis_material, calibration_package_id, calibration_version,
              current_description_observation_id, current_text_digest, target_text,
              target_text_digest, kiz_marked_declared, execution_path)
    ON ops.lc_action TO marketops_app;
GRANT SELECT ON ops.lc_action_transition TO marketops_app;
GRANT SELECT, INSERT ON ops.lc_action_review TO marketops_app;
GRANT SELECT, INSERT, UPDATE (state, inapplicable_reason, inapplicable_at)
    ON ops.lc_action_binding TO marketops_app;
GRANT SELECT, INSERT ON ops.lc_evaluation_plan TO marketops_app;
GRANT SELECT, INSERT, UPDATE (state, updated_at, version) ON ops.lc_batch TO marketops_app;
GRANT SELECT, INSERT ON ops.lc_batch_member TO marketops_app;
GRANT SELECT ON ops.lc_containment TO marketops_app;
GRANT SELECT ON ops.lc_containment_attestation TO marketops_app;
GRANT SELECT, INSERT ON ops.lc_isolation_dependency TO marketops_app;
GRANT SELECT ON ops.lc_launch TO marketops_app;
GRANT SELECT ON ops.lc_exposure_occupation TO marketops_app;
GRANT SELECT, INSERT, UPDATE (state, updated_at, version) ON ops.lc_manual_packet TO marketops_app;
GRANT SELECT, INSERT ON ops.lc_manual_report TO marketops_app;
GRANT SELECT, INSERT ON ops.lc_manual_verification TO marketops_app;
GRANT SELECT, INSERT,
      UPDATE (terms, obligations, new_transactions_stopped_at, obligations_cleared_at, state,
              updated_at, version)
    ON ops.lc_promotion_engagement TO marketops_app;
