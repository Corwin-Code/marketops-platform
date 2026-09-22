-- Exact restoration is a new business action, with the existing approval/launch authority.
-- This reference is immutable from preparation; the source's opposite approval is never reused.
ALTER TABLE ops.lc_action ADD COLUMN restores_command_id uuid REFERENCES ops.lc_description_command(id);

CREATE FUNCTION ops.lc_restoration_intent_is_exact() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE source ops.lc_description_command%ROWTYPE;
BEGIN
    IF TG_OP='UPDATE' AND NEW.restores_command_id IS DISTINCT FROM OLD.restores_command_id THEN
        RAISE EXCEPTION 'the restoration source is immutable from preparation' USING ERRCODE='MO094';
    END IF;
    IF (SELECT r.proposed_parameters->>'restoresCommandId' FROM ops.recommendation r WHERE r.id=NEW.recommendation_id)
        IS DISTINCT FROM NEW.restores_command_id::text THEN
        RAISE EXCEPTION 'the proposal and exact restoration source must agree' USING ERRCODE='MO094';
    END IF;
    IF NEW.restores_command_id IS NULL THEN RETURN NEW; END IF;
    SELECT * INTO source FROM ops.lc_description_command WHERE id=NEW.restores_command_id;
    IF source.id IS NULL OR source.organization_id IS DISTINCT FROM NEW.organization_id
        OR source.platform_listing_id IS DISTINCT FROM NEW.platform_listing_id
        OR source.store_id IS DISTINCT FROM NEW.store_id OR source.action_id=NEW.id
        OR NEW.action_kind<>'LISTING_DESCRIPTION_CHANGE'
        OR source.prior_text IS NULL OR source.prior_text=''
        OR NEW.target_text IS DISTINCT FROM source.prior_text
        OR NEW.target_text_digest IS DISTINCT FROM encode(sha256(convert_to(source.prior_text,'UTF8')),'hex')
        OR NEW.current_text_digest IS DISTINCT FROM source.target_text_digest THEN
        RAISE EXCEPTION 'restoration must name the exact captured nonempty prior text of this listing' USING ERRCODE='MO094';
    END IF;
    IF TG_OP='INSERT' AND (NEW.state<>'DRAFT' OR source.state<>'READBACK_MATCHED'
        OR NOT EXISTS (SELECT 1 FROM ops.lc_execution_receipt r WHERE r.command_id=source.id
            AND r.execution_state='MANAGEMENT_VERIFIED')
        OR NOT EXISTS (SELECT 1 FROM ops.lc_action a WHERE a.id=source.action_id
            AND a.state IN ('VERIFIED','CONTAINED','CLOSED'))) THEN
        RAISE EXCEPTION 'restoration needs a new draft and a resolved exact source execution' USING ERRCODE='MO094';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_restoration_intent_is_exact() FROM PUBLIC;
CREATE TRIGGER lc_restoration_intent_is_exact BEFORE INSERT OR UPDATE ON ops.lc_action
    FOR EACH ROW EXECUTE FUNCTION ops.lc_restoration_intent_is_exact();

CREATE FUNCTION ops.lc_restoration_preflight_version(p_command uuid) RETURNS text
LANGUAGE sql STABLE SET search_path=pg_catalog,ops,raw,pg_temp AS $$
    SELECT CASE WHEN a.outcome_class='ACCEPTED' AND a.fence_token=c.fence_token
        AND rb.match_state='MATCHES_PRIOR' AND r.response_complete
        AND r.identity_binding->>'qualified'='true'
        AND r.identity_binding->>'extent'='EXACT_NATIVE_OBJECT'
        AND r.identity_binding->>'nativeListingKey'=c.native_listing_key
        AND a.operation_snapshot #>> '{responseIdentity,commandId}'=c.id::text
        AND NOT EXISTS (SELECT 1 FROM ops.lc_description_command_attempt later
            WHERE later.command_id=c.id AND later.purpose IN ('APPLY','RESTORE') AND later.attempt_no>a.attempt_no)
        AND c.state IN ('READBACK_PENDING','EXECUTING') AND c.lease_expires_at>clock_timestamp()
        THEN nullif(r.version_token,'') END
      FROM ops.lc_description_command c JOIN ops.lc_action action ON action.id=c.action_id
      JOIN LATERAL (SELECT at.* FROM ops.lc_description_command_attempt at
          WHERE at.command_id=c.id AND at.purpose='READBACK' ORDER BY at.attempt_no DESC LIMIT 1) a ON true
      LEFT JOIN ops.lc_description_command_readback rb ON rb.attempt_id=a.id AND rb.command_id=c.id
      LEFT JOIN raw.lc_description_response_observation r ON r.id=rb.raw_observation_id AND r.id=a.raw_observation_id
     WHERE c.id=p_command AND action.restores_command_id IS NOT NULL;
$$;
REVOKE ALL ON FUNCTION ops.lc_restoration_preflight_version(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_restoration_preflight_version(uuid) TO marketops_app;

CREATE OR REPLACE FUNCTION ops.open_lc_description_command_attempt(
    p_attempt_id uuid, p_command_id uuid, p_purpose text, p_fence bigint, p_owner text,
    p_request_digest text, p_correlation_id text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, platform, pg_temp
AS $$
DECLARE command ops.lc_description_command%ROWTYPE; shape jsonb; reasons text[]; next_no integer; restoration boolean; expected_version text;
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'command does not exist' USING ERRCODE = 'MO090'; END IF;
    IF command.fence_token <> p_fence OR command.lease_owner IS DISTINCT FROM p_owner
        OR command.lease_expires_at IS NULL OR command.lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'the lease that authorised this attempt is not current' USING ERRCODE = 'MO090';
    END IF;
    IF p_request_digest !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'a request digest is required before a call is made' USING ERRCODE = 'MO090';
    END IF;
    IF p_correlation_id IS NULL OR length(btrim(p_correlation_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'a correlation identifier is required' USING ERRCODE = 'MO090';
    END IF;
    SELECT restores_command_id IS NOT NULL INTO restoration FROM ops.lc_action WHERE id=command.action_id;
    IF NOT ((p_purpose = 'APPLY' AND command.state = 'EXECUTING' AND NOT restoration)
         OR (p_purpose = 'STATUS_ENQUIRY' AND command.state IN ('EXECUTING', 'PLATFORM_PENDING', 'COMPENSATION_PENDING'))
         OR (p_purpose = 'READBACK' AND command.state IN ('READBACK_PENDING', 'COMPENSATION_PENDING'))
         OR (p_purpose = 'RESTORE' AND command.state = 'EXECUTING' AND restoration)) THEN
        RAISE EXCEPTION 'a % attempt cannot be opened from %', p_purpose, command.state USING ERRCODE = 'MO091';
    END IF;
    IF EXISTS (SELECT 1 FROM ops.lc_description_command_attempt a
                WHERE a.command_id = p_command_id AND a.fence_token = p_fence AND a.outcome_class = 'IN_FLIGHT') THEN
        RAISE EXCEPTION 'another attempt is already in flight at this fence' USING ERRCODE = 'MO096';
    END IF;
    -- The gate again, immediately before the call. A mutating attempt also
    -- needs every reason gone; an observation may proceed under some of them.
    reasons := ops.evaluate_lc_description_write_gate(p_command_id);
    IF p_purpose IN ('APPLY', 'RESTORE') AND cardinality(reasons) > 0 THEN
        RAISE EXCEPTION 'the description write gate is closed: %', array_to_string(reasons, ',')
            USING ERRCODE = 'MO092';
    END IF;
    IF p_purpose = 'RESTORE' THEN
        expected_version:=ops.lc_restoration_preflight_version(command.id);
        IF expected_version IS NULL THEN
            RAISE EXCEPTION 'RESTORE_UNSUPPORTED: current exact preflight version is missing' USING ERRCODE='MO094';
        END IF;
    END IF;
    -- The operation shape is frozen into the attempt so completion classifies
    -- against what was called, not against what the registry says later.
    shape := platform.lc_description_operation_snapshot(command.capability_id, p_purpose);
    IF shape IS NULL THEN
        RAISE EXCEPTION 'no verified % operation is recorded for this capability', p_purpose
            USING ERRCODE = 'MO092';
    END IF;
    SELECT coalesce(max(attempt_no), 0) + 1 INTO next_no FROM ops.lc_description_command_attempt
     WHERE command_id = p_command_id;
    INSERT INTO ops.lc_description_command_attempt (id, command_id, attempt_no, purpose, fence_token,
        lease_owner, started_at, outcome_class, correlation_id, request_digest, operation_snapshot, expected_version_token)
    VALUES (p_attempt_id, p_command_id, next_no, p_purpose, p_fence, p_owner, clock_timestamp(),
        'IN_FLIGHT', p_correlation_id, p_request_digest, shape, expected_version);
    RETURN p_attempt_id;
END;
$$;

CREATE OR REPLACE FUNCTION ops.lc_description_execution_evidence(p_command uuid,p_readback uuid)
RETURNS jsonb LANGUAGE plpgsql STABLE SET search_path=pg_catalog,ops,raw,pg_temp AS $$
DECLARE c ops.lc_description_command%ROWTYPE; rb ops.lc_description_command_readback%ROWTYPE;
    read_attempt ops.lc_description_command_attempt%ROWTYPE; mutation ops.lc_description_command_attempt%ROWTYPE;
    final_status ops.lc_description_command_attempt%ROWTYPE; ro raw.lc_description_response_observation%ROWTYPE;
    mo raw.lc_description_response_observation%ROWTYPE; gaps text[]:='{}'; model text; expected_purpose text;
BEGIN
    SELECT * INTO c FROM ops.lc_description_command WHERE id=p_command;
    SELECT CASE WHEN restores_command_id IS NULL THEN 'APPLY' ELSE 'RESTORE' END INTO expected_purpose
      FROM ops.lc_action WHERE id=c.action_id;
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
    IF mutation.id IS NULL OR mutation.purpose IS DISTINCT FROM expected_purpose OR mutation.outcome_class IS DISTINCT FROM 'ACCEPTED'
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
        'nativeTaskKey',mutation.native_task_key,'mutationPurpose',expected_purpose,'managementObservedAt',ro.observed_at,
        'customerDisplay','UNKNOWN','businessEffect','NOT_EVALUATED','gaps',to_jsonb(gaps),
        'executionState',CASE WHEN cardinality(gaps)=0 THEN 'MANAGEMENT_VERIFIED' ELSE 'NATIVE_COMPLETION_UNPROVEN' END);
END;
$$;

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
    restoration boolean;
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
    SELECT restores_command_id IS NOT NULL INTO restoration FROM ops.lc_action WHERE id=command.action_id;
    IF command.state = 'LEASED' AND p_to_state = 'READBACK_PENDING' AND NOT restoration THEN
        IF command.retry_preflight_fence IS DISTINCT FROM command.fence_token
            OR NOT EXISTS (SELECT 1 FROM ops.lc_description_command_attempt previous_apply
                            WHERE previous_apply.command_id = p_command_id AND previous_apply.purpose = 'APPLY') THEN
            RAISE EXCEPTION 'a governed retry lease is required for preflight readback' USING ERRCODE = 'MO092';
        END IF;
    END IF;
    IF p_to_state = 'EXECUTING' THEN
        IF restoration THEN
            IF command.state<>'READBACK_PENDING' OR ops.lc_restoration_preflight_version(command.id) IS NULL
                OR cardinality(ops.evaluate_lc_description_write_gate(command.id))>0
                OR (EXISTS (SELECT 1 FROM ops.lc_description_command_attempt a
                    WHERE a.command_id=command.id AND a.purpose IN ('APPLY','RESTORE'))
                    AND NOT ops.lc_description_retry_is_proven(command.id)) THEN
                RAISE EXCEPTION 'restoration requires current exact preflight and its own authority' USING ERRCODE='MO094';
            END IF;
        ELSIF (command.state = 'LEASED' AND command.retry_preflight_fence = command.fence_token)
            OR (command.state = 'READBACK_PENDING'
                AND (command.retry_preflight_fence IS DISTINCT FROM command.fence_token
                     OR NOT ops.lc_description_retry_is_proven(p_command_id))) THEN
            RAISE EXCEPTION 'retry requires fresh proof at the current lease fence' USING ERRCODE='MO092';
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
        RAISE EXCEPTION 'RESTORE_UNSUPPORTED: prepare and approve a new exact restoration action' USING ERRCODE='MO094';
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


CREATE OR REPLACE FUNCTION ops.evaluate_lc_description_write_gate(p_command_id uuid)
RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, platform, iam, pg_temp
AS $$
DECLARE
    command ops.lc_description_command%ROWTYPE;
    reasons text[] := '{}';
    gaps    text[];
    latest  text;
    mutation_purpose text; shape jsonb;
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id;
    IF NOT FOUND THEN RETURN ARRAY['COMMAND_NOT_FOUND']; END IF;

    IF NOT EXISTS (SELECT 1 FROM platform.platform_capability cap
                    WHERE cap.id = command.capability_id
                      AND cap.capability_code = 'listing-description-change'
                      AND cap.read_write_class = 'WRITE'
                      AND cap.verification_state = 'VERIFIED'
                      AND cap.status = 'ACTIVE' AND cap.deprecated_at IS NULL) THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_VERIFIED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.capability_subject_status s
                    WHERE s.capability_id = command.capability_id
                      AND s.store_id = command.store_id AND s.availability = 'AVAILABLE') THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_AVAILABLE_FOR_STORE');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.feature_flag f
                    WHERE f.flag_code = 'listing-description-write'
                      AND f.scope_kind = 'CAPABILITY' AND f.capability_id = command.capability_id
                      AND f.flag_kind = 'WRITE_CAPABILITY' AND f.status = 'ACTIVE' AND f.state = 'ENABLED') THEN
        reasons := array_append(reasons, 'CAPABILITY_SWITCH_DISABLED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.feature_flag f
                    WHERE f.flag_code = 'listing-description-write'
                      AND f.scope_kind = 'GLOBAL' AND f.flag_kind = 'WRITE_CAPABILITY'
                      AND f.status = 'ACTIVE' AND f.state = 'ENABLED') THEN
        reasons := array_append(reasons, 'GLOBAL_SWITCH_DISABLED');
    END IF;
    IF EXISTS (SELECT 1 FROM platform.feature_flag f
                WHERE f.flag_code = 'listing-description-write'
                  AND f.status = 'ACTIVE' AND f.state = 'DISABLED'
                  AND ((f.scope_kind = 'PLATFORM' AND f.platform_code = command.platform_code)
                    OR (f.scope_kind = 'MARKETPLACE_ACCOUNT' AND f.marketplace_account_id =
                        (SELECT st.marketplace_account_id FROM core.store st WHERE st.id = command.store_id))
                    OR (f.scope_kind = 'STORE' AND f.store_id = command.store_id))) THEN
        reasons := array_append(reasons, 'SCOPED_SWITCH_DISABLED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.pilot_allowlist_entry entry
                    WHERE entry.organization_id = command.organization_id
                      AND entry.action_kind = 'LISTING_DESCRIPTION_CHANGE'
                      AND entry.platform_listing_id = command.platform_listing_id
                      AND entry.status = 'ACTIVE'
                      AND entry.valid_from <= statement_timestamp()
                      AND (entry.valid_until IS NULL OR entry.valid_until > statement_timestamp())) THEN
        reasons := array_append(reasons, 'ENTITY_NOT_ALLOWLISTED');
    END IF;
    -- A real write needs an active, exact-head-bound gate authority that names
    -- this listing and has production writes enabled. None exists.
    IF NOT EXISTS (SELECT 1 FROM ops.lc_gate_authority g
                    WHERE g.organization_id = command.organization_id
                      AND g.store_id = command.store_id
                      AND g.status = 'ACTIVE' AND g.production_write_enabled
                      AND command.platform_listing_id = ANY (g.platform_listing_ids)
                      AND g.valid_from <= statement_timestamp()
                      AND g.valid_until > statement_timestamp()) THEN
        reasons := array_append(reasons, 'PRODUCTION_WRITE_DISABLED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.approval_decision a
                    WHERE a.id = command.approval_decision_id
                      AND a.recommendation_id = command.recommendation_id
                      AND a.decision = 'APPROVED'
                      AND a.scope_expires_at > statement_timestamp()) THEN
        reasons := array_append(reasons, 'AUTHORIZATION_INVALID_OR_EXPIRED');
    END IF;
    IF command.approval_expires_at <= statement_timestamp() THEN
        reasons := array_append(reasons, 'APPROVAL_LEASE_EXPIRED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_action a
                    WHERE a.id = command.action_id AND a.state = 'LAUNCHED') THEN
        reasons := array_append(reasons, 'ACTION_NOT_LAUNCHED');
    END IF;
    gaps := ops.lc_binding_gaps(command.action_id);
    IF cardinality(gaps) > 0 THEN
        reasons := reasons || gaps;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_exposure_occupation o
                    WHERE o.action_id = command.action_id AND o.state <> 'RELEASED') THEN
        reasons := array_append(reasons, 'ALLOWANCE_NOT_OCCUPIED');
    END IF;
    IF ops.lc_scope_contained(command.organization_id, command.platform_listing_id) THEN
        reasons := array_append(reasons, 'SCOPE_CONTAINED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.guardrail_evaluation g
                    JOIN ops.lc_action_binding b ON b.action_id = command.action_id
                   WHERE g.recommendation_id = command.recommendation_id
                     AND g.purpose = 'EXECUTION' AND g.outcome = 'PASS'
                     AND g.lc_calibration_package_id = b.calibration_package_id
                     AND g.lc_calibration_version = b.calibration_version) THEN
        reasons := array_append(reasons, 'EXECUTION_PASS_MISSING');
    END IF;
    SELECT CASE WHEN restores_command_id IS NULL THEN 'APPLY' ELSE 'RESTORE' END INTO mutation_purpose
      FROM ops.lc_action WHERE id=command.action_id;
    -- The verified operation names the description attribute it changes, so
    -- the request cannot be a whole-card import. Without it, nothing is sent.
    IF mutation_purpose='APPLY' AND NOT EXISTS (SELECT 1 FROM platform.capability_operation o
                    JOIN platform.platform_endpoint e ON e.id = o.endpoint_id
                   WHERE o.capability_id = command.capability_id AND o.operation = mutation_purpose
                     AND o.status = 'ACTIVE' AND o.verification_state = 'VERIFIED'
                     AND (coalesce(e.path_template, '') || coalesce(e.query_template, '')
                          || o.request_template) LIKE '%{descriptionAttributeKey}%') THEN
        reasons := array_append(reasons, 'NON_TARGET_FIELD_RISK');
    END IF;
    IF mutation_purpose='RESTORE' THEN
        shape:=platform.lc_description_operation_snapshot(command.capability_id,'RESTORE');
        IF shape IS NULL OR NOT platform.lc_description_request_guard_valid(
                shape #> '{operation,description_request_guard}',shape #>> '{operation,description_attribute_key}')
            OR shape #>> '{operation,description_request_guard,evidenceRef}' IS DISTINCT FROM shape #>> '{operation,evidence_ref}'
            OR nullif(shape #>> '{operation,conditional_write_header}','') IS NULL
            OR NOT EXISTS (SELECT 1 FROM platform.capability_operation readback
                WHERE readback.capability_id=command.capability_id AND readback.operation='READBACK'
                  AND readback.status='ACTIVE' AND readback.verification_state='VERIFIED'
                  AND nullif(readback.version_token_header,'') IS NOT NULL) THEN
            reasons:=array_append(reasons,'RESTORE_UNSUPPORTED');
        END IF;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_action a
                    WHERE a.id = command.action_id AND a.kiz_marked_declared IS NOT NULL) THEN
        reasons := array_append(reasons, 'KIZ_MARKED_UNDECLARED');
    END IF;
    IF length(command.target_text) NOT BETWEEN command.length_bound_min AND command.length_bound_max THEN
        reasons := array_append(reasons, 'TEXT_LENGTH_OUT_OF_BOUNDS');
    END IF;
    SELECT o.text_digest INTO latest FROM core.lc_description_observation o
     WHERE o.platform_listing_id = command.platform_listing_id
     ORDER BY o.observed_at DESC, o.acquired_at DESC LIMIT 1;
    IF latest IS DISTINCT FROM command.prior_text_digest
        AND command.state IN ('PENDING', 'LEASED', 'RETRY_WAIT') THEN
        reasons := array_append(reasons, 'PRIOR_TEXT_MOVED');
    END IF;
    RETURN reasons;
END;
$$;

-- Neither an unqualified error string nor another operation's idempotency is retry proof.
CREATE OR REPLACE FUNCTION ops.lc_description_retry_is_proven(p_command_id uuid)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,ops,platform,raw,pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM ops.lc_description_command c JOIN ops.lc_action action ON action.id=c.action_id
          JOIN LATERAL (SELECT a.* FROM ops.lc_description_command_attempt a
              WHERE a.command_id=c.id AND a.purpose IN ('APPLY','RESTORE') ORDER BY a.attempt_no DESC LIMIT 1) mutation ON true
          JOIN LATERAL (SELECT a.* FROM ops.lc_description_command_attempt a
              WHERE a.command_id=c.id AND a.purpose='READBACK' ORDER BY a.attempt_no DESC LIMIT 1) read_attempt ON true
          JOIN ops.lc_description_command_readback rb ON rb.attempt_id=read_attempt.id AND rb.command_id=c.id
          JOIN raw.lc_description_response_observation ro ON ro.id=rb.raw_observation_id AND ro.id=read_attempt.raw_observation_id
         WHERE c.id=p_command_id AND cardinality(ops.evaluate_lc_description_write_gate(c.id))=0
           AND mutation.purpose=CASE WHEN action.restores_command_id IS NULL THEN 'APPLY' ELSE 'RESTORE' END
           AND read_attempt.attempt_no>mutation.attempt_no AND read_attempt.fence_token=c.fence_token
           AND read_attempt.outcome_class='ACCEPTED' AND rb.match_state='MATCHES_PRIOR'
           AND ro.response_complete AND ro.identity_binding->>'qualified'='true'
           AND ro.identity_binding->>'extent'='EXACT_NATIVE_OBJECT'
           AND ro.identity_binding->>'nativeListingKey'=c.native_listing_key
           AND (EXISTS (SELECT 1 FROM ops.lc_description_command_attempt proof
                 JOIN raw.lc_description_response_observation evidence ON evidence.id=proof.raw_observation_id AND evidence.attempt_id=proof.id
                WHERE proof.command_id=c.id AND proof.error_code='provider_explicit_not_applied'
                  AND proof.outcome_class='RETRIABLE_ERROR' AND proof.attempt_no<read_attempt.attempt_no
                  AND (proof.id=mutation.id OR (proof.purpose='STATUS_ENQUIRY' AND proof.attempt_no>mutation.attempt_no
                    AND evidence.identity_binding #>> '{task,attemptId}'=mutation.id::text
                    AND evidence.identity_binding #>> '{task,nativeTaskKey}'=mutation.native_task_key))
                  AND evidence.response_complete AND evidence.identity_binding->>'qualified'='true'
                  AND evidence.identity_binding->>'extent'='EXACT_NATIVE_OBJECT'
                  AND evidence.identity_binding->>'nativeListingKey'=c.native_listing_key)
             OR (mutation.operation_snapshot->>'writeResultModel'='SYNCHRONOUS'
                 AND mutation.outcome_class IN ('UNKNOWN_STATE','TIMEOUT','RETRIABLE_ERROR')
                 AND mutation.operation_snapshot #>> '{endpoint,idempotency_support}'='YES'
                 AND (mutation.operation_snapshot-'responseIdentity')=platform.lc_description_operation_snapshot(c.capability_id,mutation.purpose)))
    );
$$;

-- A completed source can remain under Outcome observation while its opposite, newly
-- approved restoration proceeds. Keep the original task and evidence open.
DROP INDEX ops.recommendation_live_uq;
CREATE UNIQUE INDEX recommendation_live_uq ON ops.recommendation(subject_kind,subject_id,action_kind)
 WHERE action_kind NOT IN ('ADVERTISING_REVIEW','AD_BID_CHANGE')
   AND NOT (action_kind='LISTING_DESCRIPTION_CHANGE' AND proposed_parameters ? 'restoresCommandId')
   AND state IN ('DRAFT','VALIDATED','READY_FOR_REVIEW','TASK_ONLY','APPROVED','POLICY_AUTHORIZED',
                 'COMMAND_CREATED','EXECUTION_TRACKING','OUTCOME_OBSERVATION');
CREATE UNIQUE INDEX lc_restoration_live_proposal_uq ON ops.recommendation(subject_kind,subject_id,action_kind)
 WHERE action_kind='LISTING_DESCRIPTION_CHANGE' AND proposed_parameters ? 'restoresCommandId'
   AND state IN ('DRAFT','VALIDATED','READY_FOR_REVIEW','TASK_ONLY','APPROVED','POLICY_AUTHORIZED',
                 'COMMAND_CREATED','EXECUTION_TRACKING','OUTCOME_OBSERVATION');

CREATE FUNCTION ops.lc_restoration_proposal_scope() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE source ops.lc_description_command%ROWTYPE;
BEGIN
    IF NEW.action_kind<>'LISTING_DESCRIPTION_CHANGE' THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND NEW.proposed_parameters->>'restoresCommandId' IS DISTINCT FROM OLD.proposed_parameters->>'restoresCommandId' THEN
        RAISE EXCEPTION 'restoration proposal source is immutable' USING ERRCODE='MO094';
    END IF;
    IF NEW.state NOT IN ('DRAFT','VALIDATED','READY_FOR_REVIEW','TASK_ONLY','APPROVED','POLICY_AUTHORIZED',
                        'COMMAND_CREATED','EXECUTION_TRACKING','OUTCOME_OBSERVATION') THEN RETURN NEW; END IF;
    -- Serialise both normal and restoration proposal creation on the same existing listing row.
    PERFORM 1 FROM core.platform_listing WHERE id=NEW.subject_id AND organization_id=NEW.organization_id FOR UPDATE;
    IF NEW.proposed_parameters ? 'restoresCommandId' THEN
        SELECT * INTO source FROM ops.lc_description_command WHERE id=(NEW.proposed_parameters->>'restoresCommandId')::uuid;
        IF source.id IS NULL OR source.organization_id IS DISTINCT FROM NEW.organization_id
            OR source.platform_listing_id IS DISTINCT FROM NEW.subject_id OR source.store_id IS DISTINCT FROM NEW.store_id
            OR source.state<>'READBACK_MATCHED' OR source.prior_text IS NULL OR source.prior_text=''
            OR NOT EXISTS (SELECT 1 FROM ops.lc_execution_receipt r WHERE r.command_id=source.id AND r.execution_state='MANAGEMENT_VERIFIED')
            OR NOT EXISTS (SELECT 1 FROM ops.lc_action a WHERE a.id=source.action_id AND a.state IN ('VERIFIED','CONTAINED','CLOSED')) THEN
            RAISE EXCEPTION 'restoration proposal needs the resolved exact source of this listing' USING ERRCODE='MO094';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM ops.recommendation other WHERE other.id<>NEW.id
        AND other.subject_kind=NEW.subject_kind AND other.subject_id=NEW.subject_id AND other.action_kind=NEW.action_kind
        AND other.state IN ('DRAFT','VALIDATED','READY_FOR_REVIEW','TASK_ONLY','APPROVED','POLICY_AUTHORIZED',
                            'COMMAND_CREATED','EXECUTION_TRACKING','OUTCOME_OBSERVATION')
        AND other.id IS DISTINCT FROM source.recommendation_id
        AND NOT EXISTS (SELECT 1 FROM ops.lc_description_command own_source
            WHERE own_source.recommendation_id=NEW.id AND own_source.id::text=other.proposed_parameters->>'restoresCommandId')) THEN
        RAISE EXCEPTION 'another live listing change already owns this scope' USING ERRCODE='23505';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.lc_restoration_proposal_scope() FROM PUBLIC;
CREATE TRIGGER lc_restoration_proposal_scope BEFORE INSERT OR UPDATE ON ops.recommendation
 FOR EACH ROW EXECUTE FUNCTION ops.lc_restoration_proposal_scope();

-- Pure bounded input validation has no table access or authority mutation.
GRANT EXECUTE ON FUNCTION platform.lc_description_request_guard_valid(jsonb,text) TO marketops_app;
