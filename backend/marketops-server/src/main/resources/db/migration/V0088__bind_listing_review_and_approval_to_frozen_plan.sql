-- Historical approvals are not retroactively attributed to an unreviewed plan.
ALTER TABLE ops.lc_action_review ADD COLUMN evaluation_plan_digest text
    CHECK (evaluation_plan_digest IS NULL OR evaluation_plan_digest ~ '^[0-9a-f]{64}$');
ALTER TABLE ops.lc_action_binding ADD COLUMN evaluation_plan_digest text
    CHECK (evaluation_plan_digest IS NULL OR evaluation_plan_digest ~ '^[0-9a-f]{64}$');

CREATE FUNCTION ops.lc_plan_precedes_review()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp
AS $$
DECLARE action ops.lc_action%ROWTYPE;
BEGIN
    SELECT * INTO STRICT action FROM ops.lc_action WHERE id=NEW.action_id;
    IF action.state<>'DRAFT' OR NEW.organization_id<>action.organization_id
        OR NEW.calibration_package_id IS DISTINCT FROM action.calibration_package_id
        OR NEW.calibration_version IS DISTINCT FROM action.calibration_version
        OR NEW.frozen_at<action.created_at OR NEW.frozen_at>statement_timestamp()
        OR EXISTS (SELECT 1 FROM ops.lc_action_review r WHERE r.action_id=action.id AND r.verdict='ATTESTED') THEN
        RAISE EXCEPTION 'a plan must be frozen for the exact draft before review or approval' USING ERRCODE='MO092';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_plan_precedes_review() FROM PUBLIC;
CREATE TRIGGER lc_plan_precedes_review BEFORE INSERT ON ops.lc_evaluation_plan
    FOR EACH ROW EXECUTE FUNCTION ops.lc_plan_precedes_review();

CREATE FUNCTION ops.lc_review_attests_frozen_plan()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp
AS $$
DECLARE plan ops.lc_evaluation_plan%ROWTYPE;
BEGIN
    IF NEW.verdict<>'ATTESTED' THEN RETURN NEW; END IF;
    SELECT * INTO plan FROM ops.lc_evaluation_plan WHERE action_id=NEW.action_id;
    IF NOT FOUND OR plan.organization_id<>NEW.organization_id OR plan.frozen_at>NEW.reviewed_at
        OR (NEW.evaluation_plan_digest IS NOT NULL AND NEW.evaluation_plan_digest<>plan.plan_digest) THEN
        RAISE EXCEPTION 'review must attest the exact plan frozen before it' USING ERRCODE='MO092';
    END IF;
    NEW.evaluation_plan_digest:=plan.plan_digest;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_review_attests_frozen_plan() FROM PUBLIC;
CREATE TRIGGER lc_review_attests_frozen_plan BEFORE INSERT ON ops.lc_action_review
    FOR EACH ROW EXECUTE FUNCTION ops.lc_review_attests_frozen_plan();

CREATE FUNCTION ops.lc_approval_binds_reviewed_plan()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp
AS $$
DECLARE plan ops.lc_evaluation_plan%ROWTYPE; approval ops.approval_decision%ROWTYPE;
BEGIN
    SELECT * INTO plan FROM ops.lc_evaluation_plan WHERE action_id=NEW.action_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'approval cannot acquire an evaluation plan after its decision' USING ERRCODE='MO092';
    END IF;
    SELECT * INTO STRICT approval FROM ops.approval_decision WHERE id=NEW.approval_decision_id;
    IF plan.organization_id<>NEW.organization_id OR plan.frozen_at>approval.decided_at
        OR approval.decided_at>NEW.bound_at
        OR (NEW.evaluation_plan_digest IS NOT NULL AND NEW.evaluation_plan_digest<>plan.plan_digest)
        OR NOT EXISTS (SELECT 1 FROM ops.lc_action_review r WHERE r.action_id=NEW.action_id
            AND r.verdict='ATTESTED' AND r.evaluation_plan_digest=plan.plan_digest
            AND r.attested_target_text_digest IS NOT DISTINCT FROM NEW.target_text_digest
            AND r.attested_current_text_digest IS NOT DISTINCT FROM NEW.current_text_digest
            AND r.attested_affected_set_digest=NEW.affected_set_digest
            AND r.reviewed_at<=approval.decided_at) THEN
        RAISE EXCEPTION 'approval requires the exact pre-existing, independently reviewed plan' USING ERRCODE='MO092';
    END IF;
    NEW.evaluation_plan_digest:=plan.plan_digest;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_approval_binds_reviewed_plan() FROM PUBLIC;
CREATE TRIGGER lc_approval_binds_reviewed_plan BEFORE INSERT ON ops.lc_action_binding
    FOR EACH ROW EXECUTE FUNCTION ops.lc_approval_binds_reviewed_plan();

-- Preserve every existing binding control; add exact frozen plan identity.
CREATE OR REPLACE FUNCTION ops.lc_binding_gaps(p_action uuid)
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
    IF NOT EXISTS (SELECT 1 FROM ops.lc_evaluation_plan p
        WHERE p.action_id=action.id AND p.organization_id=action.organization_id
          AND p.plan_digest=binding.evaluation_plan_digest AND p.frozen_at<=binding.bound_at) THEN
        reasons:=array_append(reasons,'EVALUATION_PLAN_BINDING_MISSING_OR_CHANGED');
    END IF;
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
