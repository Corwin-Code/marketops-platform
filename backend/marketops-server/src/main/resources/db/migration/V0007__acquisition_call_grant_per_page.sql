-- V0007: let a held acquisition run make every page call its lease allows.
--
-- platform.grant_call_authority granted a call only while the run was LEASED, and moved it to
-- RUNNING. Nothing moves a run back to LEASED: ops.transition_ingestion_run has no RUNNING -> LEASED
-- edge and ops.renew_ingestion_run_lease keeps the state. AcquisitionRunner also moves the run to
-- RUNNING before its first page. Every acquisition run was therefore refused at its first call,
-- rested in RETRY_WAIT and ended FAILED_TERMINAL once its claim budget was spent; a paginated
-- endpoint could not have reached its second page either.
--
-- The rest of the run lifecycle already expects several calls per claim: ops.acknowledge_checkpoint
-- requires RUNNING, ops.renew_ingestion_run_lease renews LEASED or RUNNING, the grant increments
-- last_call_seq, and raw.bind_acquisition_receipt binds each observation to its own (run, call_seq)
-- decision. The grant now accepts LEASED or RUNNING under the same fence, owner and live lease.
-- Nothing else changes: every call still locks the four control epochs, re-evaluates the job,
-- account, platform, endpoint, scope grant and credential at its own instant, writes its own
-- ops.authorization_decision_evidence row and receives a new call sequence.

CREATE OR REPLACE FUNCTION platform.grant_call_authority(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_scope_grant_id uuid, p_requested_authority interval, p_correlation_id text) RETURNS platform.call_authority_grant
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
    server_max_call_authority CONSTANT interval := interval '30 seconds';
    run_row              record;
    job_row              record;
    epoch_scopes         text[];
    epoch_values         bigint[];
    evaluated            timestamptz;
    evaluation           platform.call_control_evaluation;
    grant_at             timestamptz;
    requested_deadline   timestamptz;
    server_policy_at     timestamptz;
    authority_at         timestamptz;
    selected_credential  uuid;
    granted_call_seq     integer;
    decision             uuid;
    grant_result         platform.call_authority_grant;
BEGIN
    IF p_requested_authority IS NULL OR p_requested_authority <= interval '0' THEN
        RAISE EXCEPTION 'requested call authority must be greater than zero'
            USING ERRCODE = 'MO016';
    END IF;

    -- First serialize the runtime authority. Missing row and wrong holder are
    -- the same refusal: this caller does not hold the named run.
    SELECT run.job_id, run.fence_token, run.lease_owner, run.state,
           run.lease_expires_at
      INTO run_row
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       FOR UPDATE OF run;

    IF run_row.job_id IS NULL
        OR run_row.fence_token <> p_expected_fence
        OR run_row.lease_owner IS DISTINCT FROM p_expected_lease_owner
        OR run_row.state NOT IN ('LEASED', 'RUNNING')
        OR run_row.lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION
            'run % is not held by % at fence % in a live lease',
            p_run_id, p_expected_lease_owner, p_expected_fence
            USING ERRCODE = 'MO008';
    END IF;

    -- Lock the exact Job row separately from the run, and derive every identity
    -- the call may use. A Job writer that committed first is followed to its
    -- current row version; a later writer waits behind this held lock.
    SELECT job.id, job.organization_id, job.marketplace_account_id,
           job.service_account_id, job.platform_code, job.endpoint_id, job.status
      INTO job_row
      FROM platform.ingestion_job AS job
     WHERE job.id = run_row.job_id
       FOR SHARE OF job;

    IF job_row.id IS NULL THEN
        RAISE EXCEPTION 'run % has no current ingestion Job', p_run_id
            USING ERRCODE = 'MO015';
    END IF;

    -- The four epoch locks are the control-metadata serialization point. Every
    -- point-of-use evaluation is deliberately below this statement, so a
    -- writer that reached an epoch first commits and becomes visible before any
    -- subject, grant, Credential, endpoint or temporal decision is made.
    SELECT array_agg(locked.scope_kind ORDER BY locked.scope_kind),
           array_agg(locked.epoch ORDER BY locked.scope_kind)
      INTO epoch_scopes, epoch_values
      FROM (SELECT epoch.scope_kind, epoch.scope_id, epoch.epoch
              FROM platform.control_epoch AS epoch
             WHERE (epoch.scope_kind, epoch.scope_id) IN (
                       ('ORGANIZATION',        job_row.organization_id),
                       ('MARKETPLACE_ACCOUNT', job_row.marketplace_account_id),
                       ('SERVICE_ACCOUNT',     job_row.service_account_id),
                       ('JOB',                 job_row.id))
             ORDER BY epoch.scope_kind, epoch.scope_id
               FOR SHARE) AS locked;

    IF coalesce(cardinality(epoch_scopes), 0) <> 4 THEN
        RAISE EXCEPTION
            'control snapshot is incomplete: % of 4 scopes have an epoch row',
            coalesce(cardinality(epoch_scopes), 0)
            USING ERRCODE = 'MO010';
    END IF;

    -- This is the sole evaluation instant for subject, scope-grant,
    -- Credential and boundary liveness. It is captured only after all four
    -- epoch locks are held, then passed unchanged through the whole resolver.
    evaluated := clock_timestamp();

    -- Evaluate the Job, owning entities and endpoint only after serialization.
    -- UNVERIFIED provider metadata is permitted only for provider-neutral
    -- design validation; the authorization boundary performs no outbound I/O.
    PERFORM 1
      FROM core.organization AS organization
      JOIN core.marketplace_account AS account
        ON account.id = job_row.marketplace_account_id
       AND account.organization_id = organization.id
      JOIN core.marketplace_platform AS marketplace
        ON marketplace.code = job_row.platform_code
      JOIN platform.platform_endpoint AS endpoint
        ON endpoint.id = job_row.endpoint_id
       AND endpoint.platform_code = job_row.platform_code
     WHERE organization.id = job_row.organization_id
       AND organization.status = 'ACTIVE'
       AND account.status = 'ACTIVE'
       AND account.platform_code = job_row.platform_code
       AND marketplace.status = 'ACTIVE'
       AND job_row.status = 'ACTIVE'
       AND endpoint.status = 'ACTIVE'
       AND endpoint.read_write_class = 'READ';
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'job % and its organization/account/platform/endpoint are not active READ authority',
            job_row.id
            USING ERRCODE = 'MO015';
    END IF;

    -- The caller names no Credential. This production resolver validates the
    -- scope grant, selects one complete acyclic rotation chain and resolves the
    -- temporal relation at the already-fixed database instant.
    evaluation := platform.evaluate_call_control_facts(
        job_row.id, p_scope_grant_id, evaluated);
    selected_credential := evaluation.credential_id;
    grant_at := clock_timestamp();

    IF grant_at >= evaluation.valid_until THEN
        RAISE EXCEPTION
            'control snapshot expired at % before the grant at %',
            evaluation.valid_until, grant_at
            USING ERRCODE = 'MO011';
    END IF;

    -- The request can shorten authority but can never widen the server policy,
    -- the consumed run lease or the locked control snapshot. Catch timestamp
    -- overflow inside the primitive so even an arbitrary SQL client receives a
    -- fail-closed authority refusal with no run sequence or evidence residue.
    BEGIN
        requested_deadline := grant_at + p_requested_authority;
    EXCEPTION
        WHEN datetime_field_overflow THEN
            RAISE EXCEPTION 'requested call authority is outside the timestamp range'
                USING ERRCODE = 'MO016';
    END;
    server_policy_at := grant_at + server_max_call_authority;
    authority_at := LEAST(
        requested_deadline,
        server_policy_at,
        run_row.lease_expires_at,
        evaluation.valid_until);
    IF authority_at <= grant_at THEN
        RAISE EXCEPTION 'call authority did not extend beyond its grant instant'
            USING ERRCODE = 'MO016';
    END IF;

    -- Take the next call sequence under the held lease in the same guarded
    -- update. No caller can choose or reuse that sequence.
    UPDATE ops.ingestion_run AS run
       SET state         = 'RUNNING',
           last_call_seq = run.last_call_seq + 1,
           updated_at    = grant_at
     WHERE run.id = p_run_id
       AND run.job_id = job_row.id
       AND run.fence_token = p_expected_fence
       AND run.lease_owner = p_expected_lease_owner
       AND run.state IN ('LEASED', 'RUNNING')
       AND run.lease_expires_at > grant_at
       AND run.lease_expires_at = run_row.lease_expires_at
    RETURNING run.last_call_seq INTO granted_call_seq;

    IF granted_call_seq IS NULL THEN
        RAISE EXCEPTION
            'run authority for % was lost before the grant could commit', p_run_id
            USING ERRCODE = 'MO014';
    END IF;

    decision := gen_random_uuid();
    INSERT INTO ops.authorization_decision_evidence (
        id, job_id, run_id, fence_token, lease_owner, platform_code, endpoint_id,
        call_seq, service_account_id, marketplace_account_id,
        scope_grant_id, credential_id,
        evaluated_at, granted_at, run_lease_expires_at, server_policy_deadline,
        control_epoch_scopes, control_epoch_values,
        control_snapshot_valid_until, boundary_kind_count, boundary_kind_set,
        boundary_set_digest, winning_boundary_kind,
        call_authority_expires_at, correlation_id)
    VALUES (
        decision, job_row.id, p_run_id, p_expected_fence, p_expected_lease_owner,
        job_row.platform_code, job_row.endpoint_id, granted_call_seq,
        job_row.service_account_id, job_row.marketplace_account_id,
        p_scope_grant_id, selected_credential,
        evaluated, grant_at, run_row.lease_expires_at, server_policy_at,
        epoch_scopes, epoch_values,
        evaluation.valid_until, evaluation.boundary_kind_count,
        evaluation.boundary_kind_set, evaluation.boundary_set_digest,
        evaluation.winning_kind,
        authority_at, p_correlation_id);

    grant_result.decision_id := decision;
    grant_result.job_id := job_row.id;
    grant_result.run_id := p_run_id;
    grant_result.fence_token := p_expected_fence;
    grant_result.lease_owner := p_expected_lease_owner;
    grant_result.platform_code := job_row.platform_code;
    grant_result.endpoint_id := job_row.endpoint_id;
    grant_result.credential_id := selected_credential;
    grant_result.scope_grant_id := p_scope_grant_id;
    grant_result.call_seq := granted_call_seq;
    grant_result.granted_at := grant_at;
    grant_result.call_authority_expires_at := authority_at;
    grant_result.run_lease_expires_at := run_row.lease_expires_at;
    grant_result.server_policy_deadline := server_policy_at;
    grant_result.control_epoch_scopes := epoch_scopes;
    grant_result.control_epoch_values := epoch_values;
    grant_result.boundary_set_digest := evaluation.boundary_set_digest;
    RETURN grant_result;
END;
$$;

REVOKE ALL ON FUNCTION platform.grant_call_authority(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_scope_grant_id uuid, p_requested_authority interval, p_correlation_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.grant_call_authority(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_scope_grant_id uuid, p_requested_authority interval, p_correlation_id text) TO marketops_app;
