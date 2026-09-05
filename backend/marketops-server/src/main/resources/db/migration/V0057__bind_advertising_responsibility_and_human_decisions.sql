-- R1: workflow owns responsibility independently from executable candidates.
-- These records extend the existing Task/recommendation/approval authorities.
CREATE TABLE ops.ad_case_responsibility (
    case_id uuid PRIMARY KEY REFERENCES mart.ad_case(id),
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    task_id uuid NOT NULL UNIQUE REFERENCES ops.work_task(id),
    recommendation_id uuid NOT NULL UNIQUE REFERENCES ops.recommendation(id),
    owner_role_code text NOT NULL REFERENCES iam.business_role(code),
    slo_profile_id uuid REFERENCES core.ad_human_slo_profile(id),
    slo_profile_version integer,
    calendar_id uuid REFERENCES core.ad_reporting_calendar(id),
    calendar_version integer,
    first_raised_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    acknowledgement_due_at timestamptz,
    action_due_at timestamptz,
    escalation_due_at timestamptz,
    next_staffed_response_at timestamptz,
    coverage_state text NOT NULL CHECK (coverage_state IN
        ('IN_COVERAGE','OUT_OF_COVERAGE_ACTIVE_HARM','OUT_OF_COVERAGE','PROFILE_MISSING','CALENDAR_MISSING')),
    profile_snapshot jsonb NOT NULL CHECK (jsonb_typeof(profile_snapshot) = 'object'),
    CONSTRAINT ad_responsibility_profile_shape CHECK
        ((slo_profile_id IS NULL AND slo_profile_version IS NULL AND acknowledgement_due_at IS NULL
            AND action_due_at IS NULL AND escalation_due_at IS NULL)
         OR (slo_profile_id IS NOT NULL AND slo_profile_version > 0)),
    CONSTRAINT ad_responsibility_deadline_order CHECK
        (acknowledgement_due_at <= action_due_at AND action_due_at <= escalation_due_at)
);

CREATE TABLE ops.ad_candidate_selection (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    case_id uuid NOT NULL REFERENCES mart.ad_case(id),
    candidate_id uuid NOT NULL REFERENCES ops.ad_bid_candidate(id),
    recommendation_id uuid NOT NULL UNIQUE REFERENCES ops.recommendation(id),
    maker_user_id uuid NOT NULL,
    outcome_baseline_id uuid NOT NULL,
    selected_at timestamptz NOT NULL,
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    bundle_id uuid NOT NULL REFERENCES ops.ad_decision_policy_bundle(id),
    bundle_version integer NOT NULL CHECK (bundle_version > 0),
    affected_set_digest text NOT NULL CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    authority_snapshot jsonb NOT NULL CHECK (jsonb_typeof(authority_snapshot) = 'object'),
    FOREIGN KEY (maker_user_id,organization_id) REFERENCES iam.user_account(id,organization_id)
);

CREATE TABLE ops.ad_candidate_endorsement (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    selection_id uuid NOT NULL UNIQUE REFERENCES ops.ad_candidate_selection(id),
    recommendation_id uuid NOT NULL UNIQUE REFERENCES ops.recommendation(id),
    endorser_user_id uuid NOT NULL,
    endorsed_at timestamptz NOT NULL,
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    authority_snapshot jsonb NOT NULL CHECK (jsonb_typeof(authority_snapshot) = 'object'),
    FOREIGN KEY (endorser_user_id,organization_id) REFERENCES iam.user_account(id,organization_id)
);

CREATE FUNCTION ops.validate_ad_candidate_endorsement() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog,ops,pg_temp AS $$
DECLARE selection ops.ad_candidate_selection%ROWTYPE;
BEGIN
    SELECT * INTO STRICT selection FROM ops.ad_candidate_selection WHERE id=NEW.selection_id;
    IF NEW.organization_id <> selection.organization_id
       OR NEW.recommendation_id <> selection.recommendation_id
       OR NEW.endorser_user_id = selection.maker_user_id
       OR NEW.endorsed_at < selection.selected_at
       OR NEW.authority_snapshot IS DISTINCT FROM selection.authority_snapshot THEN
        RAISE EXCEPTION 'endorsement requires a distinct person and the exact selected authority'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ad_candidate_endorsement_exact BEFORE INSERT ON ops.ad_candidate_endorsement
    FOR EACH ROW EXECUTE FUNCTION ops.validate_ad_candidate_endorsement();
REVOKE ALL ON FUNCTION ops.validate_ad_candidate_endorsement() FROM PUBLIC;

GRANT SELECT,INSERT ON ops.ad_case_responsibility,ops.ad_candidate_selection,
    ops.ad_candidate_endorsement TO marketops_app;

INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES ('ops','ad_case_responsibility','NO_ROUTE',NULL,'canonical task SLO binding; never a write authorization'),
       ('ops','ad_candidate_selection','NO_ROUTE',NULL,'immutable authenticated maker selection'),
       ('ops','ad_candidate_endorsement','NO_ROUTE',NULL,'immutable independent Operations endorsement');

-- A new version may explicitly permit an intermediate Protection target. The
-- initial/missing decision remains false, and the preview states recovery only.
ALTER TABLE core.ad_bid_target_policy ADD COLUMN allow_protection_intermediate_target boolean NOT NULL DEFAULT false;
ALTER TABLE core.ad_bid_target_policy ADD CONSTRAINT ad_target_intermediate_direction_ck
    CHECK (NOT allow_protection_intermediate_target OR direction='PROTECTION_DECREASE');
ALTER TABLE ops.ad_bid_candidate DROP CONSTRAINT ad_bid_candidate_ordinal_uq;
ALTER TABLE ops.ad_bid_candidate ADD CONSTRAINT ad_bid_candidate_generation_uq UNIQUE
    (case_id,direction,ordinal,target_policy_id,target_policy_version,semantic_profile_id,
     affected_set_digest,current_bid_amount,provider_normalized_amount);

-- An Exception is a bounded risk disposition, never an authorization to write.
CREATE TABLE ops.ad_accepted_exception (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    case_id uuid NOT NULL REFERENCES mart.ad_case(id),
    ad_native_object_id uuid NOT NULL REFERENCES core.ad_native_object(id),
    store_id uuid NOT NULL REFERENCES core.store(id),
    platform_code text NOT NULL REFERENCES core.marketplace_platform(code),
    semantic_profile_id uuid NOT NULL REFERENCES platform.ad_semantic_profile(id),
    affected_set_digest text NOT NULL CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    cause_code text NOT NULL,
    lane text NOT NULL CHECK (lane IN ('PROTECTION','DATA_REPAIR')),
    policy_version_digest text NOT NULL,
    bundle_id uuid REFERENCES ops.ad_decision_policy_bundle(id),
    known_consequence jsonb NOT NULL CHECK(jsonb_typeof(known_consequence)='object'),
    exposure_snapshot jsonb NOT NULL CHECK(jsonb_typeof(exposure_snapshot)='object'),
    requester_user_id uuid NOT NULL,
    requester_role_code text NOT NULL REFERENCES iam.business_role(code),
    requested_at timestamptz NOT NULL,
    endorser_user_id uuid,
    endorsed_at timestamptz,
    approver_user_id uuid,
    approved_at timestamptz,
    reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 1024),
    evidence_reference text NOT NULL CHECK(length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    effective_from timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    review_due_at timestamptz NOT NULL,
    state text NOT NULL CHECK(state IN ('REQUESTED','ENDORSED','ACTIVE','ENDED','INVALIDATED','EXPIRED')),
    ended_at timestamptz,
    end_reason text,
    version bigint NOT NULL DEFAULT 0,
    FOREIGN KEY(requester_user_id,organization_id) REFERENCES iam.user_account(id,organization_id),
    FOREIGN KEY(endorser_user_id,organization_id) REFERENCES iam.user_account(id,organization_id),
    FOREIGN KEY(approver_user_id,organization_id) REFERENCES iam.user_account(id,organization_id),
    CHECK(effective_from<expires_at AND review_due_at>effective_from AND review_due_at<=expires_at),
    CHECK(endorser_user_id IS NULL OR endorser_user_id<>requester_user_id),
    CHECK(approver_user_id IS NULL OR (approver_user_id<>requester_user_id AND approver_user_id<>endorser_user_id)),
    CHECK(state<>'ACTIVE' OR (approved_at IS NOT NULL AND approver_user_id IS NOT NULL
        AND endorsed_at IS NOT NULL AND endorser_user_id IS NOT NULL)),
    CHECK((state IN ('ENDED','INVALIDATED','EXPIRED'))=(ended_at IS NOT NULL AND end_reason IS NOT NULL))
);
CREATE UNIQUE INDEX ad_accepted_exception_one_active ON ops.ad_accepted_exception(case_id) WHERE state='ACTIVE';
GRANT SELECT,INSERT,UPDATE(state,endorser_user_id,endorsed_at,approver_user_id,approved_at,ended_at,end_reason,version)
    ON ops.ad_accepted_exception TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES ('ops','ad_accepted_exception','NO_ROUTE',NULL,'Owner-approved expiring risk disposition, not write authority');

CREATE FUNCTION ops.guard_ad_exception_transition() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,iam,pg_temp AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF NEW.version<>OLD.version+1 OR NOT
            ((OLD.state='REQUESTED' AND NEW.state IN('ENDORSED','ENDED','INVALIDATED','EXPIRED'))
             OR (OLD.state='ENDORSED' AND NEW.state IN('ACTIVE','ENDED','INVALIDATED','EXPIRED'))
             OR (OLD.state='ACTIVE' AND NEW.state IN('ENDED','INVALIDATED','EXPIRED'))) THEN
            RAISE EXCEPTION 'Exception transitions never renew or resurrect a risk disposition' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.state<>'REQUESTED' THEN
        RAISE EXCEPTION 'An Exception starts as a request, not an approval' USING ERRCODE='23514';
    END IF;
    IF NEW.state IN('ENDORSED','ACTIVE') AND NOT EXISTS(
        SELECT 1 FROM iam.user_role_assignment r JOIN iam.user_account u ON u.id=r.user_id
        WHERE r.user_id=NEW.endorser_user_id AND r.role_code='OPS_LEAD' AND r.status='ACTIVE'
          AND u.status='ACTIVE' AND u.organization_id=NEW.organization_id
          AND r.effective_from<=clock_timestamp() AND (r.effective_to IS NULL OR r.effective_to>clock_timestamp())) THEN
        RAISE EXCEPTION 'Exception requires live independent Operations endorsement' USING ERRCODE='23514';
    END IF;
    IF NEW.state='ACTIVE' AND NOT EXISTS(
        SELECT 1 FROM iam.user_role_assignment r JOIN iam.user_account u ON u.id=r.user_id
        WHERE r.user_id=NEW.approver_user_id AND r.role_code='OWNER' AND r.status='ACTIVE'
          AND u.status='ACTIVE' AND u.organization_id=NEW.organization_id
          AND r.effective_from<=clock_timestamp() AND (r.effective_to IS NULL OR r.effective_to>clock_timestamp())) THEN
        RAISE EXCEPTION 'Only a live Owner approval pauses advertising Action SLO' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ad_exception_transition_guard BEFORE INSERT OR UPDATE ON ops.ad_accepted_exception
    FOR EACH ROW EXECUTE FUNCTION ops.guard_ad_exception_transition();
REVOKE ALL ON FUNCTION ops.guard_ad_exception_transition() FROM PUBLIC;
