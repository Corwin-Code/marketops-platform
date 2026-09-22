-- Finite deferral leaves Task deadlines, state and all platform controls untouched.
CREATE FUNCTION ops.lc_task_reassessment_basis(p_task uuid) RETURNS text
LANGUAGE sql STABLE SET search_path=pg_catalog,ops,mart AS $$
    SELECT encode(sha256(convert_to(jsonb_build_object(
        'scope',h.affected_set_id,'necessary',h.necessary_conditions,'eligibility',h.eligibility)::text,'UTF8')),'hex')
    FROM ops.lc_task_responsibility b LEFT JOIN ops.lc_action a ON a.recommendation_id=b.recommendation_id
    JOIN LATERAL (SELECT * FROM mart.lc_listing_health h
        WHERE h.platform_listing_id=coalesce(b.platform_listing_id,a.platform_listing_id)
          AND h.computed_at<=clock_timestamp() ORDER BY h.health_version DESC LIMIT 1) h ON true
    WHERE b.task_id=p_task;
$$;

CREATE TABLE ops.lc_task_deferral (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES ops.lc_task_responsibility(task_id),
    organization_id uuid NOT NULL,
    requester_user_id uuid NOT NULL,
    defer_minutes integer NOT NULL CHECK (defer_minutes>0),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 512),
    requested_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    basis_digest text NOT NULL CHECK (basis_digest ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK (state IN ('ACTIVE','EXPIRED','INVALIDATED')),
    ended_at timestamptz,
    review_health_id uuid REFERENCES mart.lc_listing_health(id),
    review_queue_id uuid REFERENCES ops.lc_recalculation_queue(id),
    FOREIGN KEY (requester_user_id,organization_id) REFERENCES iam.user_account(id,organization_id),
    CHECK (isfinite(requested_at) AND isfinite(expires_at) AND expires_at>requested_at),
    CHECK ((state='ACTIVE')=(ended_at IS NULL)),
    CHECK (state<>'INVALIDATED' OR review_health_id IS NOT NULL),
    CHECK (ended_at IS NULL OR ended_at>=requested_at)
);
CREATE UNIQUE INDEX lc_task_deferral_active ON ops.lc_task_deferral(task_id) WHERE state='ACTIVE';
CREATE INDEX lc_task_deferral_due ON ops.lc_task_deferral(expires_at,id) WHERE state='ACTIVE';

CREATE FUNCTION ops.lc_task_deferral_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops AS $$
DECLARE binding ops.lc_task_responsibility%ROWTYPE; task ops.work_task%ROWTYPE; limit_value jsonb;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Task deferral history cannot be deleted' USING ERRCODE='23514'; END IF;
    SELECT * INTO binding FROM ops.lc_task_responsibility WHERE task_id=NEW.task_id;
    SELECT * INTO task FROM ops.work_task WHERE id=NEW.task_id;
    IF NEW.review_queue_id IS NOT NULL AND (NEW.state<>'EXPIRED' OR NOT EXISTS(
        SELECT 1 FROM ops.lc_recalculation_queue q LEFT JOIN ops.lc_action a ON a.recommendation_id=binding.recommendation_id
        WHERE q.id=NEW.review_queue_id AND q.organization_id=NEW.organization_id
          AND q.platform_listing_id=coalesce(binding.platform_listing_id,a.platform_listing_id)
          AND q.trigger_reference='task-deferral-expired:'||NEW.id::text AND q.source_time=NEW.expires_at)) THEN
        RAISE EXCEPTION 'Expired deferral requires its exact scoped queue request' USING ERRCODE='23514';
    END IF;
    IF NEW.review_health_id IS NOT NULL AND NOT EXISTS(
        SELECT 1 FROM mart.lc_listing_health h LEFT JOIN ops.lc_action a ON a.recommendation_id=binding.recommendation_id
        WHERE h.id=NEW.review_health_id AND h.organization_id=NEW.organization_id
          AND h.platform_listing_id=coalesce(binding.platform_listing_id,a.platform_listing_id)
          AND h.computed_at>=coalesce(NEW.ended_at,NEW.requested_at)) THEN
        RAISE EXCEPTION 'Deferral review must name a subsequent diagnosis in its own scope' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        limit_value:=CASE WHEN binding.source_health_id IS NULL THEN binding.slo_snapshot->'maximumDeferMinutes'
                         ELSE binding.slo_snapshot#>'{necessaryRisk,maximumDeferMinutes}' END;
        IF binding.task_id IS NULL OR binding.organization_id<>NEW.organization_id
           OR task.state NOT IN ('OPEN','ASSIGNED','IN_PROGRESS') OR NEW.state<>'ACTIVE'
           OR NEW.requested_at<binding.first_raised_at OR NEW.requested_at>clock_timestamp()
           OR NEW.expires_at<>NEW.requested_at+make_interval(mins=>NEW.defer_minutes)
           OR NEW.review_health_id IS NOT NULL OR NEW.review_queue_id IS NOT NULL OR NEW.ended_at IS NOT NULL
           OR NEW.basis_digest IS DISTINCT FROM ops.lc_task_reassessment_basis(NEW.task_id)
           OR jsonb_typeof(limit_value) IS DISTINCT FROM 'number' THEN
            RAISE EXCEPTION 'Deferral requires original Task policy and current diagnosis' USING ERRCODE='23514';
        END IF;
        IF limit_value::text !~ '^[0-9]+$' OR (limit_value::text)::numeric<NEW.defer_minutes
           OR (limit_value::text)::numeric>2147483647 THEN
            RAISE EXCEPTION 'Deferral exceeds the declared finite policy' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['state','ended_at','review_health_id','review_queue_id'])
             IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','ended_at','review_health_id','review_queue_id'])
           OR (OLD.state<>'ACTIVE' AND (NEW.state<>OLD.state OR NEW.ended_at IS DISTINCT FROM OLD.ended_at))
           OR (OLD.review_health_id IS NOT NULL AND NEW.review_health_id IS DISTINCT FROM OLD.review_health_id)
           OR (OLD.review_queue_id IS NOT NULL AND NEW.review_queue_id IS DISTINCT FROM OLD.review_queue_id)
           OR (OLD.state='ACTIVE' AND NEW.state='ACTIVE') THEN
            RAISE EXCEPTION 'Deferral origin and finite deadline cannot restart' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lc_task_deferral_guard BEFORE INSERT OR UPDATE OR DELETE ON ops.lc_task_deferral
FOR EACH ROW EXECUTE FUNCTION ops.lc_task_deferral_guard();
CREATE FUNCTION ops.lc_task_deferral_review_required() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM ops.lc_task_deferral d WHERE d.id=NEW.id AND d.state='EXPIRED'
              AND d.review_health_id IS NULL AND d.review_queue_id IS NULL) THEN
        RAISE EXCEPTION 'Deferral expiry and its review request must commit together' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER lc_task_deferral_review_required AFTER INSERT OR UPDATE ON ops.lc_task_deferral
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ops.lc_task_deferral_review_required();
GRANT SELECT,INSERT,UPDATE ON ops.lc_task_deferral TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.lc_task_reassessment_basis(uuid),ops.lc_task_deferral_guard() TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES ('ops','lc_task_deferral','NO_ROUTE',NULL,'finite Task reconsideration; does not pause SLO or authorize execution');
ALTER TABLE ops.work_task_event DROP CONSTRAINT work_task_event_kind_ck;
ALTER TABLE ops.work_task_event ADD CONSTRAINT work_task_event_kind_ck CHECK (event_kind IN (
    'RAISED','VIEWED','ACKNOWLEDGED','ASSIGNED','REASSIGNED','ACTION_RECORDED','OUTCOME_OBSERVED',
    'REOPENED','ESCALATED','COMPLETED','CANCELLED','EXECUTION_OBSERVED','DEFERRED','REASSESSMENT_REQUIRED'));
