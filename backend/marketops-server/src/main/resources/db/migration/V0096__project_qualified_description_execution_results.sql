-- Execution observations are not business effect, customer display or release evidence.
-- Historical terminal commands do not acquire retrospective native completion proof.
CREATE TABLE ops.lc_execution_receipt (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    action_id uuid NOT NULL REFERENCES ops.lc_action(id),
    command_id uuid NOT NULL REFERENCES ops.lc_description_command(id),
    readback_id uuid NOT NULL UNIQUE REFERENCES ops.lc_description_command_readback(id),
    execution_state text NOT NULL CHECK (execution_state IN ('MANAGEMENT_VERIFIED','NATIVE_COMPLETION_UNPROVEN')),
    evidence jsonb NOT NULL CHECK (jsonb_typeof(evidence)='object'),
    recorded_at timestamptz NOT NULL,
    task_event_id uuid UNIQUE REFERENCES ops.work_task_event(id),
    task_recorded_at timestamptz,
    CHECK ((task_event_id IS NULL)=(task_recorded_at IS NULL))
);
GRANT SELECT ON ops.lc_execution_receipt TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES ('ops','lc_execution_receipt','NO_ROUTE',NULL,'derived immutable native execution evidence; workflow journal delivery only');

CREATE FUNCTION ops.lc_execution_receipt_is_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF (to_jsonb(NEW)-ARRAY['task_event_id','task_recorded_at']) IS DISTINCT FROM
        (to_jsonb(OLD)-ARRAY['task_event_id','task_recorded_at'])
        OR (OLD.task_event_id IS NOT NULL AND (NEW.task_event_id IS DISTINCT FROM OLD.task_event_id
            OR NEW.task_recorded_at IS DISTINCT FROM OLD.task_recorded_at)) THEN
        RAISE EXCEPTION 'execution evidence and completed delivery are immutable' USING ERRCODE='MO093';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_execution_receipt_is_immutable() FROM PUBLIC;
CREATE TRIGGER lc_execution_receipt_is_immutable BEFORE UPDATE ON ops.lc_execution_receipt
    FOR EACH ROW EXECUTE FUNCTION ops.lc_execution_receipt_is_immutable();

CREATE FUNCTION ops.lc_description_execution_evidence(p_command uuid,p_readback uuid)
RETURNS jsonb LANGUAGE plpgsql STABLE SET search_path=pg_catalog,ops,raw,pg_temp AS $$
DECLARE c ops.lc_description_command%ROWTYPE; rb ops.lc_description_command_readback%ROWTYPE;
    read_attempt ops.lc_description_command_attempt%ROWTYPE; mutation ops.lc_description_command_attempt%ROWTYPE;
    final_status ops.lc_description_command_attempt%ROWTYPE; ro raw.lc_description_response_observation%ROWTYPE;
    mo raw.lc_description_response_observation%ROWTYPE; gaps text[]:='{}'; model text;
BEGIN
    SELECT * INTO c FROM ops.lc_description_command WHERE id=p_command;
    SELECT * INTO rb FROM ops.lc_description_command_readback WHERE id=p_readback AND command_id=c.id;
    SELECT * INTO read_attempt FROM ops.lc_description_command_attempt WHERE id=rb.attempt_id AND command_id=c.id;
    SELECT * INTO ro FROM raw.lc_description_response_observation WHERE id=rb.raw_observation_id AND attempt_id=read_attempt.id;
    IF c.id IS NULL OR rb.id IS NULL OR read_attempt.purpose IS DISTINCT FROM 'READBACK'
        OR rb.match_state IS DISTINCT FROM 'MATCHES_TARGET' OR read_attempt.outcome_class IS DISTINCT FROM 'ACCEPTED'
        OR ro.response_complete IS NOT TRUE OR ro.identity_binding->>'qualified' IS DISTINCT FROM 'true'
        OR ro.identity_binding->>'extent' IS DISTINCT FROM 'EXACT_NATIVE_OBJECT'
        OR ro.identity_binding->>'nativeListingKey' IS DISTINCT FROM c.native_listing_key
        OR read_attempt.raw_observation_id IS DISTINCT FROM ro.id
        OR rb.observed_text_digest IS DISTINCT FROM ops.lc_description_digest_under_rule(c.target_text,c.equivalence_rule) THEN
        gaps:=array_append(gaps,'EXACT_MANAGEMENT_READBACK_UNPROVEN');
    END IF;
    SELECT * INTO mutation FROM ops.lc_description_command_attempt WHERE command_id=c.id AND purpose IN ('APPLY','RESTORE')
        ORDER BY attempt_no DESC LIMIT 1;
    SELECT * INTO mo FROM raw.lc_description_response_observation WHERE id=mutation.raw_observation_id AND attempt_id=mutation.id;
    model:=mutation.operation_snapshot->>'writeResultModel';
    IF NOT platform.lc_description_request_guard_valid(
            mutation.operation_snapshot #> '{operation,description_request_guard}',
            mutation.operation_snapshot #>> '{operation,description_attribute_key}')
        OR mutation.operation_snapshot #>> '{operation,description_request_guard,evidenceRef}' IS DISTINCT FROM
            mutation.operation_snapshot #>> '{operation,evidence_ref}'
        OR mutation.operation_snapshot #>> '{responseIdentity,commandId}' IS DISTINCT FROM c.id::text
        OR mutation.operation_snapshot #>> '{responseIdentity,nativeListingKey}' IS DISTINCT FROM c.native_listing_key THEN
        gaps:=array_append(gaps,'EXACT_MUTATION_REQUEST_UNQUALIFIED');
    END IF;
    IF mutation.id IS NULL OR mutation.purpose IS DISTINCT FROM 'APPLY' OR mutation.outcome_class IS DISTINCT FROM 'ACCEPTED'
        OR mutation.completed_at IS NULL OR mutation.completed_at>read_attempt.started_at
        OR mo.response_complete IS NOT TRUE OR mo.identity_binding->>'qualified' IS DISTINCT FROM 'true' THEN
        gaps:=array_append(gaps,'LATEST_APPLY_COMPLETION_UNPROVEN');
    ELSIF model='ASYNCHRONOUS_TASK' THEN
        SELECT a.* INTO final_status FROM ops.lc_description_command_attempt a
          JOIN raw.lc_description_response_observation r ON r.id=a.raw_observation_id AND r.attempt_id=a.id
         WHERE a.command_id=c.id AND a.purpose='STATUS_ENQUIRY' AND a.outcome_class='ACCEPTED'
           AND a.attempt_no>mutation.attempt_no AND a.completed_at<=read_attempt.started_at
           AND r.response_complete AND r.identity_binding->>'qualified'='true'
           AND r.identity_binding->>'extent'='EXACT_NATIVE_OBJECT'
           AND r.identity_binding->>'nativeListingKey'=c.native_listing_key
           AND r.identity_binding #>> '{task,attemptId}'=mutation.id::text
           AND r.identity_binding #>> '{task,nativeTaskKey}'=mutation.native_task_key
         ORDER BY a.attempt_no DESC LIMIT 1;
        IF final_status.id IS NULL OR mutation.native_task_key IS NULL THEN
            gaps:=array_append(gaps,'ACCEPTED_TASK_COMPLETION_UNPROVEN');
        END IF;
        IF EXISTS (SELECT 1 FROM ops.lc_description_command_attempt a
            JOIN raw.lc_description_response_observation r ON r.id=a.raw_observation_id AND r.attempt_id=a.id
            WHERE a.command_id=c.id AND a.purpose='STATUS_ENQUIRY' AND a.completed_at<=read_attempt.started_at
              AND (a.outcome_class='REJECTED' OR a.error_code='provider_explicit_not_applied')
              AND r.identity_binding->>'qualified'='true'
              AND r.identity_binding #>> '{task,attemptId}'=mutation.id::text
              AND r.identity_binding #>> '{task,nativeTaskKey}'=mutation.native_task_key) THEN
            gaps:=array_append(gaps,'CONTRADICTORY_NATIVE_TASK_RESULT');
        END IF;
    ELSIF model IS DISTINCT FROM 'SYNCHRONOUS' OR mo.identity_binding->>'extent' IS DISTINCT FROM 'EXACT_NATIVE_OBJECT'
        OR mo.identity_binding->>'nativeListingKey' IS DISTINCT FROM c.native_listing_key THEN
        gaps:=array_append(gaps,'SYNCHRONOUS_NATIVE_COMPLETION_UNPROVEN');
    END IF;
    RETURN jsonb_build_object('schema','DESCRIPTION_EXECUTION_V1','commandId',c.id,'actionId',c.action_id,
        'listingId',c.platform_listing_id,'nativeListingKey',c.native_listing_key,'targetTextDigest',c.target_text_digest,
        'affectedSetDigest',c.affected_set_digest,'approvalDecisionId',c.approval_decision_id,
        'readbackId',rb.id,'readbackAttemptId',read_attempt.id,'readbackRawId',ro.id,
        'mutationAttemptId',mutation.id,'mutationRawId',mo.id,'nativeStatusAttemptId',final_status.id,
        'nativeTaskKey',mutation.native_task_key,'managementObservedAt',ro.observed_at,
        'customerDisplay','UNKNOWN','businessEffect','NOT_EVALUATED','gaps',to_jsonb(gaps),
        'executionState',CASE WHEN cardinality(gaps)=0 THEN 'MANAGEMENT_VERIFIED' ELSE 'NATIVE_COMPLETION_UNPROVEN' END);
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_description_execution_evidence(uuid,uuid) FROM PUBLIC;

CREATE FUNCTION ops.record_lc_description_execution_result(p_command uuid,p_readback uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE c ops.lc_description_command%ROWTYPE; proof jsonb; receipt uuid;
BEGIN
    SELECT * INTO c FROM ops.lc_description_command WHERE id=p_command FOR UPDATE;
    IF c.state IS DISTINCT FROM 'READBACK_MATCHED' OR NOT EXISTS (
        SELECT 1 FROM ops.lc_description_command_readback rb WHERE rb.id=p_readback AND rb.command_id=c.id) THEN
        RAISE EXCEPTION 'execution observation needs the exact terminal readback' USING ERRCODE='MO093';
    END IF;
    SELECT id INTO receipt FROM ops.lc_execution_receipt WHERE readback_id=p_readback;
    IF receipt IS NOT NULL THEN RETURN receipt; END IF;
    proof:=ops.lc_description_execution_evidence(c.id,p_readback); receipt:=gen_random_uuid();
    INSERT INTO ops.lc_execution_receipt(id,organization_id,action_id,command_id,readback_id,execution_state,evidence,recorded_at)
    VALUES(receipt,c.organization_id,c.action_id,c.id,p_readback,proof->>'executionState',proof,clock_timestamp());
    IF proof->>'executionState'='MANAGEMENT_VERIFIED' THEN
        UPDATE ops.lc_action SET state='VERIFIED',updated_at=clock_timestamp(),version=version+1
         WHERE id=c.action_id AND organization_id=c.organization_id AND execution_path='API' AND state='LAUNCHED'
           AND target_text_digest=c.target_text_digest AND affected_set_digest=c.affected_set_digest;
    END IF;
    RETURN receipt;
END;
$$;
REVOKE ALL ON FUNCTION ops.record_lc_description_execution_result(uuid,uuid) FROM PUBLIC;

CREATE FUNCTION ops.lc_api_verified_requires_execution_receipt() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,pg_temp AS $$
BEGIN
    IF NEW.execution_path='API' AND NEW.state='VERIFIED' AND OLD.state<>NEW.state AND NOT EXISTS (
        SELECT 1 FROM ops.lc_execution_receipt r WHERE r.action_id=NEW.id AND r.organization_id=NEW.organization_id
          AND r.execution_state='MANAGEMENT_VERIFIED' AND r.evidence->>'targetTextDigest'=NEW.target_text_digest
          AND r.evidence->>'affectedSetDigest'=NEW.affected_set_digest) THEN
        RAISE EXCEPTION 'API verification needs exact native completion and management evidence' USING ERRCODE='MO093';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_api_verified_requires_execution_receipt() FROM PUBLIC;
CREATE TRIGGER lc_api_verified_requires_execution_receipt BEFORE UPDATE ON ops.lc_action
    FOR EACH ROW EXECUTE FUNCTION ops.lc_api_verified_requires_execution_receipt();

CREATE OR REPLACE FUNCTION ops.transition_lc_description_command(
    p_command_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text,
    p_failure_code text, p_retry_delay_seconds integer, p_evidence_id uuid)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE
    command ops.lc_description_command%ROWTYPE;
    edge    ops.lc_description_command_transition%ROWTYPE;
    now_at  timestamptz := clock_timestamp();
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090'; END IF;
    SELECT * INTO edge FROM ops.lc_description_command_transition
     WHERE from_state = command.state AND to_state = p_to_state;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'transition % -> % is not in the reviewed set', command.state, p_to_state
            USING ERRCODE = 'MO091';
    END IF;
    IF edge.requires_lease THEN
        IF command.fence_token <> p_expected_fence
            OR command.lease_owner IS DISTINCT FROM p_expected_lease_owner
            OR command.lease_expires_at IS NULL OR command.lease_expires_at <= now_at THEN
            RAISE EXCEPTION 'the lease that authorised this transition is not current'
                USING ERRCODE = 'MO090';
        END IF;
    END IF;
    IF command.state = 'LEASED' AND p_to_state = 'READBACK_PENDING' THEN
        IF command.retry_preflight_fence IS DISTINCT FROM command.fence_token
            OR NOT EXISTS (SELECT 1 FROM ops.lc_description_command_attempt previous_apply
                            WHERE previous_apply.command_id = p_command_id AND previous_apply.purpose = 'APPLY') THEN
            RAISE EXCEPTION 'a governed retry lease is required for preflight readback' USING ERRCODE = 'MO092';
        END IF;
    END IF;
    IF p_to_state = 'EXECUTING' THEN
        IF (command.state = 'LEASED' AND command.retry_preflight_fence = command.fence_token)
            OR (command.state = 'READBACK_PENDING'
                AND (command.retry_preflight_fence IS DISTINCT FROM command.fence_token
                     OR NOT ops.lc_description_retry_is_proven(p_command_id))) THEN
            RAISE EXCEPTION 'retry requires fresh proof at the current lease fence' USING ERRCODE = 'MO092';
        END IF;
    END IF;
    IF p_to_state = 'READBACK_MATCHED' THEN
        IF NOT EXISTS (SELECT 1 FROM ops.lc_description_command_readback rb
                         JOIN ops.lc_description_command_attempt at ON at.id = rb.attempt_id
                        WHERE rb.id = p_evidence_id AND rb.command_id = p_command_id
                          AND rb.match_state = 'MATCHES_TARGET'
                          AND at.purpose = 'READBACK' AND at.fence_token = command.fence_token
                          AND at.raw_observation_id IS NOT NULL) THEN
            RAISE EXCEPTION 'a matched readback at the current fence is required for success'
                USING ERRCODE = 'MO093';
        END IF;
    END IF;
    IF p_to_state = 'RETRY_WAIT' AND NOT ops.lc_description_retry_is_proven(p_command_id) THEN
        RAISE EXCEPTION 'retry requires readback-first proof and all current authorities' USING ERRCODE = 'MO092';
    END IF;
    IF p_to_state = 'COMPENSATION_PENDING' THEN
        -- Precise restore needs a captured complete prior text, and the text
        -- the platform holds now must be the one this command wrote.
        IF command.prior_text IS NULL THEN
            RAISE EXCEPTION 'RESTORE_UNSUPPORTED: no complete prior text was captured' USING ERRCODE = 'MO094';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM ops.lc_description_command_readback rb
                        WHERE rb.command_id = p_command_id
                          AND rb.observed_at = (SELECT max(latest.observed_at) FROM ops.lc_description_command_readback latest
                                                 WHERE latest.command_id = p_command_id)
                          AND rb.match_state = 'MATCHES_TARGET')
            OR NOT EXISTS (SELECT 1 FROM ops.lc_description_command_attempt at
                            WHERE at.command_id = p_command_id AND at.purpose = 'APPLY'
                              AND at.outcome_class IN ('ACCEPTED', 'UNKNOWN_STATE')) THEN
            RAISE EXCEPTION 'a compensation may not overwrite a text this command did not write'
                USING ERRCODE = 'MO094';
        END IF;
    END IF;
    IF p_to_state IN ('FAILED_FINAL', 'COMPENSATION_FAILED', 'TERMINATED_WITHOUT_PROVIDER_CALL')
        AND p_failure_code IS NULL THEN
        RAISE EXCEPTION 'a terminal failure names its reason' USING ERRCODE = 'MO091';
    END IF;
    UPDATE ops.lc_description_command
       SET state = p_to_state,
           lease_owner = CASE WHEN edge.releases_lease THEN NULL ELSE lease_owner END,
           lease_expires_at = CASE WHEN edge.releases_lease THEN NULL ELSE lease_expires_at END,
           retry_preflight_fence = CASE WHEN edge.releases_lease OR p_to_state = 'EXECUTING'
               THEN NULL ELSE retry_preflight_fence END,
           retry_budget_remaining = CASE WHEN p_to_state = 'RETRY_WAIT'
               THEN greatest(retry_budget_remaining - 1, 0) ELSE retry_budget_remaining END,
           next_attempt_at = CASE WHEN p_to_state = 'RETRY_WAIT'
               THEN now_at + make_interval(secs => coalesce(p_retry_delay_seconds, 60)) ELSE NULL END,
           requested_operation = CASE WHEN p_to_state = 'READBACK_PENDING' THEN NULL ELSE requested_operation END,
           failure_code = coalesce(p_failure_code, failure_code),
           terminal_at = CASE WHEN p_to_state IN ('READBACK_MATCHED', 'FAILED_FINAL',
                                                  'TERMINATED_WITHOUT_PROVIDER_CALL', 'COMPENSATED',
                                                  'COMPENSATION_FAILED') THEN now_at ELSE terminal_at END,
           updated_at = now_at
     WHERE id = p_command_id;
    IF p_to_state='READBACK_MATCHED' THEN
        PERFORM ops.record_lc_description_execution_result(p_command_id,p_evidence_id);
    END IF;
    RETURN p_to_state;
END;
$$;
REVOKE ALL ON FUNCTION ops.transition_lc_description_command(uuid, bigint, text, text, text, integer, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.transition_lc_description_command(uuid, bigint, text, text, text, integer, uuid) TO marketops_app;

ALTER TABLE ops.work_task_event DROP CONSTRAINT work_task_event_kind_ck;
ALTER TABLE ops.work_task_event ADD CONSTRAINT work_task_event_kind_ck CHECK (event_kind IN (
    'RAISED','VIEWED','ACKNOWLEDGED','ASSIGNED','REASSIGNED','ACTION_RECORDED','OUTCOME_OBSERVED',
    'REOPENED','ESCALATED','COMPLETED','CANCELLED','EXECUTION_OBSERVED'));
ALTER TABLE ops.work_task_event ADD COLUMN execution_receipt_id uuid REFERENCES ops.lc_execution_receipt(id);
CREATE UNIQUE INDEX work_task_execution_receipt_uq ON ops.work_task_event(execution_receipt_id)
    WHERE execution_receipt_id IS NOT NULL;
ALTER TABLE ops.work_task_event ADD CONSTRAINT work_task_execution_shape_ck CHECK (
    (event_kind='EXECUTION_OBSERVED' AND execution_receipt_id IS NOT NULL AND actor_user_id IS NULL
        AND actor_role_code IS NULL AND action_kind IS NULL AND action_evidence IS NULL
        AND outcome_kind IS NULL AND outcome_reference IS NULL)
    OR (event_kind<>'EXECUTION_OBSERVED' AND execution_receipt_id IS NULL));

CREATE FUNCTION ops.bind_lc_execution_task_event() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE receipt ops.lc_execution_receipt%ROWTYPE; recommendation uuid;
BEGIN
    IF NEW.event_kind<>'EXECUTION_OBSERVED' THEN RETURN NEW; END IF;
    SELECT * INTO receipt FROM ops.lc_execution_receipt
     WHERE 'lc-execution:'||id::text=NEW.evidence_reference;
    SELECT recommendation_id INTO recommendation FROM ops.lc_action WHERE id=receipt.action_id;
    IF receipt.id IS NULL OR receipt.organization_id IS DISTINCT FROM NEW.organization_id
        OR receipt.task_event_id IS NOT NULL OR NEW.occurred_at IS DISTINCT FROM receipt.recorded_at
        OR NEW.lineage_key IS DISTINCT FROM 'recommendation:'||recommendation::text
        OR NEW.reason IS DISTINCT FROM receipt.execution_state
        OR NOT EXISTS (SELECT 1 FROM ops.work_task t WHERE t.id=NEW.task_id
            AND t.organization_id=receipt.organization_id AND t.recommendation_id=recommendation) THEN
        RAISE EXCEPTION 'execution journal needs the exact receipt and responsibility Task' USING ERRCODE='MO093';
    END IF;
    NEW.execution_receipt_id:=receipt.id;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.bind_lc_execution_task_event() FROM PUBLIC;
CREATE TRIGGER bind_lc_execution_task_event BEFORE INSERT ON ops.work_task_event
    FOR EACH ROW EXECUTE FUNCTION ops.bind_lc_execution_task_event();

-- The caller must keep the transaction open through the journal append and acknowledgement.
-- A crash rolls back the lock, event and acknowledgement together; there is no stranded RUNNING state.
CREATE FUNCTION ops.lock_lc_execution_deliveries(p_limit integer)
RETURNS TABLE(receipt_id uuid,organization_id uuid,task_id uuid,recommendation_id uuid,execution_state text,recorded_at timestamptz)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
    SELECT r.id,r.organization_id,t.id,a.recommendation_id,r.execution_state,r.recorded_at
      FROM ops.lc_execution_receipt r JOIN ops.lc_action a ON a.id=r.action_id
      JOIN LATERAL (SELECT w.id FROM ops.work_task w WHERE w.recommendation_id=a.recommendation_id
          AND w.organization_id=r.organization_id ORDER BY w.id LIMIT 1) t ON true
     WHERE r.task_event_id IS NULL ORDER BY r.recorded_at,r.id
     LIMIT least(greatest(p_limit,0),100) FOR UPDATE OF r SKIP LOCKED;
$$;
REVOKE ALL ON FUNCTION ops.lock_lc_execution_deliveries(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lock_lc_execution_deliveries(integer) TO marketops_app;

CREATE FUNCTION ops.acknowledge_lc_execution_delivery(p_receipt uuid,p_event uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE receipt ops.lc_execution_receipt%ROWTYPE;
BEGIN
    SELECT * INTO receipt FROM ops.lc_execution_receipt WHERE id=p_receipt FOR UPDATE;
    IF receipt.id IS NULL OR NOT EXISTS (SELECT 1 FROM ops.work_task_event e
        WHERE e.id=p_event AND e.execution_receipt_id=receipt.id AND e.event_kind='EXECUTION_OBSERVED') THEN
        RAISE EXCEPTION 'delivery acknowledgement needs its exact journal event' USING ERRCODE='MO093';
    END IF;
    IF receipt.task_event_id IS NOT NULL THEN RETURN receipt.task_event_id=p_event; END IF;
    UPDATE ops.lc_execution_receipt SET task_event_id=p_event,task_recorded_at=clock_timestamp() WHERE id=receipt.id;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION ops.acknowledge_lc_execution_delivery(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.acknowledge_lc_execution_delivery(uuid,uuid) TO marketops_app;
