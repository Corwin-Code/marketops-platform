-- The runtime authority the control plane protects: a leased and fenced run, a
-- cursor that may not outrun durable evidence, immutable Raw evidence, and the
-- single statement that turns a control snapshot into a call authority.
--
-- The database owns authorization, lease/fence validation, custody evidence,
-- and checkpoint ordering. Scheduling, dispatch and outbound transport remain
-- outside this authority boundary, and no database path reaches a Marketplace.
--
-- Two invariants decide the structure.
--
-- A stale worker gains nothing. Authority is a lease plus a fence token, and
-- every authoritative write re-checks both. A worker whose lease expired and
-- whose run was taken over holds a fence token lower than the row's, so its
-- writes match zero rows instead of overwriting a live successor's work.
--
-- The cursor never outruns the evidence. Acknowledging a checkpoint requires an
-- observation of durable bytes in the same transaction; the two either commit
-- together or neither does. A cursor that advanced past bytes nobody kept is
-- silent, permanent data loss, so the ordering is enforced here rather than
-- left to call order in application code.

-- ---------------------------------------------------------------------------
-- Run, lease and fence
-- ---------------------------------------------------------------------------

CREATE TABLE ops.ingestion_run (
    id                  uuid        NOT NULL,
    job_id              uuid        NOT NULL,
    state               text        NOT NULL,
    -- The fence token separates two workers that both believe they hold this
    -- run. It increases on every claim, and an authoritative write carries the
    -- token it claimed with; a superseded worker's token no longer matches and
    -- its writes affect no row.
    fence_token         bigint      NOT NULL,
    lease_owner         text,
    lease_expires_at    timestamptz,
    attempt_no          integer     NOT NULL,
    last_call_seq       integer     NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT ingestion_run_pk PRIMARY KEY (id),
    CONSTRAINT ingestion_run_id_job_uq UNIQUE (id, job_id),
    CONSTRAINT ingestion_run_job_fk
        FOREIGN KEY (job_id) REFERENCES platform.ingestion_job (id),
    CONSTRAINT ingestion_run_state_ck
        CHECK (state IN ('QUEUED', 'LEASED', 'RUNNING', 'RETRY_WAIT',
                         'BLOCKED', 'SUCCEEDED', 'FAILED_TERMINAL')),
    CONSTRAINT ingestion_run_fence_ck CHECK (fence_token > 0),
    CONSTRAINT ingestion_run_attempt_ck CHECK (attempt_no >= 0),
    CONSTRAINT ingestion_run_call_seq_ck CHECK (last_call_seq >= 0),
    -- A lease is an owner and a deadline together. Half a lease describes no
    -- reachable state, and a state that cannot be reached should not be
    -- representable.
    CONSTRAINT ingestion_run_lease_pairing_ck
        CHECK (num_nonnulls(lease_owner, lease_expires_at) <> 1),
    CONSTRAINT ingestion_run_leased_state_ck
        CHECK (state NOT IN ('LEASED', 'RUNNING') OR lease_owner IS NOT NULL)
);

CREATE INDEX ingestion_run_job_ix ON ops.ingestion_run (job_id, state);

-- One live run per job. The bound is what makes each Job control-epoch row hold
-- at most one share lock at a time, which is the argument that a platform-wide
-- control change waits for at most one acquisition per job rather than for an
-- unbounded number of them.
CREATE UNIQUE INDEX ingestion_run_live_uq
    ON ops.ingestion_run (job_id)
    WHERE state IN ('QUEUED', 'LEASED', 'RUNNING', 'RETRY_WAIT', 'BLOCKED');

-- A grant is a structured, identity-bound decision rather than a bearer-like
-- expiry instant. Every field is derived by the database grant primitive and
-- is carried together to the sole acquisition executor.
CREATE TYPE platform.call_authority_grant AS (
    decision_id                  uuid,
    job_id                       uuid,
    run_id                       uuid,
    fence_token                  bigint,
    lease_owner                  text,
    platform_code                text,
    endpoint_id                  uuid,
    credential_id                uuid,
    scope_grant_id               uuid,
    call_seq                     integer,
    granted_at                   timestamptz,
    call_authority_expires_at    timestamptz,
    control_epoch_scopes         text[],
    control_epoch_values         bigint[],
    boundary_set_digest          text
);

-- V0009 creates the decision journal before the runtime run exists. Complete
-- its identity graph here, after both referenced tables are available.
ALTER TABLE ops.authorization_decision_evidence
    ADD COLUMN run_id        uuid   NOT NULL,
    ADD COLUMN fence_token   bigint NOT NULL,
    ADD COLUMN lease_owner   text   NOT NULL,
    ADD COLUMN platform_code text   NOT NULL,
    ADD COLUMN endpoint_id   uuid   NOT NULL,
    ADD COLUMN call_seq      integer NOT NULL,
    ADD CONSTRAINT authorization_decision_evidence_run_job_fk
        FOREIGN KEY (run_id, job_id) REFERENCES ops.ingestion_run (id, job_id),
    ADD CONSTRAINT authorization_decision_evidence_endpoint_platform_fk
        FOREIGN KEY (endpoint_id, platform_code)
        REFERENCES platform.platform_endpoint (id, platform_code),
    ADD CONSTRAINT authorization_decision_evidence_fence_ck CHECK (fence_token > 0),
    ADD CONSTRAINT authorization_decision_evidence_owner_ck
        CHECK (length(btrim(lease_owner)) > 0),
    ADD CONSTRAINT authorization_decision_evidence_call_seq_ck CHECK (call_seq > 0),
    ADD CONSTRAINT authorization_decision_evidence_authority_after_grant_ck
        CHECK (call_authority_expires_at > granted_at);

CREATE FUNCTION ops.ingestion_run_fence_monotonic()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.fence_token < OLD.fence_token THEN
        RAISE EXCEPTION 'run % fence token may not move backwards: % -> %',
            OLD.id, OLD.fence_token, NEW.fence_token
            USING ERRCODE = 'MO007';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ingestion_run_fence_monotonic_bu
    BEFORE UPDATE ON ops.ingestion_run
    FOR EACH ROW
    EXECUTE FUNCTION ops.ingestion_run_fence_monotonic();

-- ---------------------------------------------------------------------------
-- Raw evidence
-- ---------------------------------------------------------------------------
-- Three identities, deliberately separate:
--
--   raw_content              the bytes, addressed by their hash
--   raw_logical_unit         the business thing those bytes are about
--   raw_acquisition_observation  the fact that one call returned them
--
-- Collapsing these loses information the system needs. Two calls that return
-- byte-identical payloads share one content row and stay two observations; a
-- payload re-fetched after a retry is one logical unit with two observations;
-- and the same bytes describing two units are one content row referenced twice.
--
-- The object reference is opaque. No provider is chosen here, and no bucket,
-- region or endpoint appears: OQ-006 is open, and a provider-shaped column
-- would pre-empt an Owner decision that is still pending.
CREATE TABLE raw.raw_content (
    id             uuid        NOT NULL,
    hash_algorithm text        NOT NULL,
    hash_value     text        NOT NULL,
    byte_length    bigint      NOT NULL,
    object_ref     text        NOT NULL,
    first_seen_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT raw_content_pk PRIMARY KEY (id),
    -- Content addressing: the same bytes are one row, whoever fetched them.
    CONSTRAINT raw_content_hash_uq UNIQUE (hash_algorithm, hash_value),
    CONSTRAINT raw_content_hash_algorithm_ck CHECK (hash_algorithm IN ('SHA256')),
    CONSTRAINT raw_content_hash_value_ck CHECK (hash_value ~ '^[0-9a-f]{64}$'),
    CONSTRAINT raw_content_byte_length_ck CHECK (byte_length >= 0),
    CONSTRAINT raw_content_object_ref_ck
        CHECK (object_ref ~ '^object-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,6}$')
);

CREATE TABLE raw.raw_logical_unit (
    id                     uuid        NOT NULL,
    job_id                 uuid        NOT NULL,
    marketplace_account_id uuid        NOT NULL,
    unit_kind              text        NOT NULL,
    source_unit_key        text        NOT NULL,
    source_time            timestamptz,
    first_seen_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT raw_logical_unit_pk PRIMARY KEY (id),
    CONSTRAINT raw_logical_unit_job_fk
        FOREIGN KEY (job_id) REFERENCES platform.ingestion_job (id),
    -- The source's own identity for the unit, unique within the job that reads
    -- it. Re-acquiring the same page is idempotent at this level; the
    -- observations below still record both calls.
    CONSTRAINT raw_logical_unit_source_key_uq UNIQUE (job_id, unit_kind, source_unit_key),
    CONSTRAINT raw_logical_unit_kind_ck
        CHECK (unit_kind ~ '^[A-Z][A-Z0-9_]{1,62}$')
);

CREATE TABLE raw.raw_acquisition_observation (
    id               uuid        NOT NULL,
    run_id           uuid        NOT NULL,
    logical_unit_id  uuid        NOT NULL,
    content_id       uuid        NOT NULL,
    call_seq         integer     NOT NULL,
    -- The source's own status text, kept exactly as returned. An unrecognised
    -- value stays unrecognised: coercing it to a success is how a failed
    -- acquisition becomes a silent gap.
    native_status    text        NOT NULL,
    outcome_class    text        NOT NULL,
    ingestion_time   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT raw_acquisition_observation_pk PRIMARY KEY (id),
    -- Runs are permanent operational facts, never deleted, so the observation
    -- can safely pin the run that produced it.
    CONSTRAINT raw_acquisition_observation_run_fk
        FOREIGN KEY (run_id) REFERENCES ops.ingestion_run (id),
    CONSTRAINT raw_acquisition_observation_unit_fk
        FOREIGN KEY (logical_unit_id) REFERENCES raw.raw_logical_unit (id),
    CONSTRAINT raw_acquisition_observation_content_fk
        FOREIGN KEY (content_id) REFERENCES raw.raw_content (id),
    CONSTRAINT raw_acquisition_observation_call_uq UNIQUE (run_id, call_seq, logical_unit_id),
    CONSTRAINT raw_acquisition_observation_call_seq_ck CHECK (call_seq > 0),
    CONSTRAINT raw_acquisition_observation_outcome_ck
        CHECK (outcome_class IN
            ('SUCCESS_BYTES', 'BUSINESS_FAILURE_BYTES', 'UNKNOWN_STATE'))
);

CREATE INDEX raw_acquisition_observation_unit_ix
    ON raw.raw_acquisition_observation (logical_unit_id, ingestion_time DESC);

-- ---------------------------------------------------------------------------
-- Checkpoint
-- ---------------------------------------------------------------------------

CREATE TABLE ops.ingestion_checkpoint (
    job_id             uuid        NOT NULL,
    strategy           text        NOT NULL,
    position_value     text,
    checkpoint_version bigint      NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT ingestion_checkpoint_pk PRIMARY KEY (job_id),
    CONSTRAINT ingestion_checkpoint_job_fk
        FOREIGN KEY (job_id) REFERENCES platform.ingestion_job (id),
    CONSTRAINT ingestion_checkpoint_strategy_ck
        CHECK (strategy IN ('CURSOR', 'OFFSET', 'PAGE', 'DATE_WINDOW', 'NONE', 'UNKNOWN')),
    CONSTRAINT ingestion_checkpoint_version_ck CHECK (checkpoint_version >= 0),
    -- An unknown pagination model may not carry a position: advancing a cursor
    -- whose semantics are unrecorded is a guess, and a guess that skips source
    -- data looks exactly like success.
    CONSTRAINT ingestion_checkpoint_unknown_ck
        CHECK (strategy <> 'UNKNOWN' OR position_value IS NULL)
);

-- Advance the cursor only if the evidence for it is already committed, and
-- only for the worker that still holds the run.
--
-- The run row is locked first and held to commit, so the authority check and
-- the cursor write are one unit: a takeover cannot land between them, it can
-- only wait behind them or precede them entirely. The observation lookup is
-- the second half of the point: the caller cannot advance past bytes that were
-- not stored, because the row it must name has to exist in this transaction.
--
-- The compare-and-set on checkpoint_version makes a superseded worker's late
-- advance a zero-row update rather than a rewind, and a zero-row outcome is
-- raised, never returned as success.
--
-- SECURITY DEFINER is the enforcement, not a convenience: the application
-- role holds no UPDATE on the run or the checkpoint, so this function is the
-- only path by which a cursor can move at all.
CREATE FUNCTION ops.acknowledge_checkpoint(
    p_run_id               uuid,
    p_expected_fence       bigint,
    p_expected_lease_owner text,
    p_observation_id       uuid,
    p_expected_version     bigint,
    p_position_value       text)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    target_job  uuid;
    new_version bigint;
BEGIN
    -- Lock the run and hold the lock through the checkpoint write.
    SELECT run.job_id INTO target_job
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       AND run.fence_token = p_expected_fence
       AND run.lease_owner = p_expected_lease_owner
       AND run.state = 'RUNNING'
       AND run.lease_expires_at > clock_timestamp()
       FOR UPDATE OF run;

    IF target_job IS NULL THEN
        RAISE EXCEPTION
            'run % is not held by % at fence % with a live lease',
            p_run_id, p_expected_lease_owner, p_expected_fence
            USING ERRCODE = 'MO008';
    END IF;

    -- The evidence must exist, belong to this run, and be durable content.
    PERFORM 1
      FROM raw.raw_acquisition_observation AS observation
      JOIN raw.raw_logical_unit AS unit ON unit.id = observation.logical_unit_id
      JOIN raw.raw_content AS content ON content.id = observation.content_id
     WHERE observation.id = p_observation_id
       AND observation.run_id = p_run_id
       AND unit.job_id = target_job;
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'checkpoint for run % has no committed observation %', p_run_id, p_observation_id
            USING ERRCODE = 'MO009',
                  HINT = 'store and hash the returned bytes before acknowledging a cursor';
    END IF;

    -- Lock the exact checkpoint row after the run. A concurrent holder may
    -- delay this acquisition past the lease deadline; after the row is obtained
    -- the final UPDATE below therefore rechecks wall-clock lease truth.
    PERFORM 1
      FROM ops.ingestion_checkpoint AS checkpoint
     WHERE checkpoint.job_id = target_job
       AND checkpoint.checkpoint_version = p_expected_version
       FOR UPDATE OF checkpoint;
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'checkpoint for job % is not at expected version %', target_job, p_expected_version
            USING ERRCODE = 'MO008';
    END IF;

    UPDATE ops.ingestion_checkpoint AS checkpoint
       SET position_value     = p_position_value,
           checkpoint_version = checkpoint.checkpoint_version + 1,
           updated_at         = clock_timestamp()
      FROM ops.ingestion_run AS run
     WHERE checkpoint.job_id = target_job
       AND checkpoint.checkpoint_version = p_expected_version
       AND run.id = p_run_id
       AND run.job_id = checkpoint.job_id
       AND run.state = 'RUNNING'
       AND run.fence_token = p_expected_fence
       AND run.lease_owner = p_expected_lease_owner
       AND run.lease_expires_at > clock_timestamp()
       AND EXISTS (
           SELECT 1
             FROM raw.raw_acquisition_observation AS observation
             JOIN raw.raw_logical_unit AS unit
               ON unit.id = observation.logical_unit_id
             JOIN raw.raw_content AS content
               ON content.id = observation.content_id
            WHERE observation.id = p_observation_id
              AND observation.run_id = run.id
              AND unit.job_id = run.job_id)
    RETURNING checkpoint.checkpoint_version INTO new_version;

    IF new_version IS NULL THEN
        RAISE EXCEPTION
            'checkpoint authority for run % was lost before version % could advance',
            p_run_id, p_expected_version
            USING ERRCODE = 'MO008';
    END IF;

    RETURN new_version;
END;
$$;

REVOKE ALL ON FUNCTION ops.acknowledge_checkpoint(uuid, bigint, text, uuid, bigint, text)
    FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- The grant
-- ---------------------------------------------------------------------------

-- Evaluate, consume and record one bounded call authority in one transaction,
-- or refuse with nothing changed.
--
-- The caller supplies the run holder identity and a scope-grant reference. The
-- function locks and derives the Job graph, selects the unique current
-- Credential rotation leaf, consumes the epoch tuple and returns one structured
-- grant whose identities are the recorded decision. Evidence describes the
-- decision; nothing a caller submits can substitute for it.
--
-- The transaction is the serialization point:
--
--   authority   the run row is locked first, and the final transition re-checks
--               fence, owner, state and lease in the UPDATE itself; zero rows
--               is a raised refusal, never a fall-through
--   change      the Job is locked, then the four epoch rows are locked FOR SHARE
--               in the writers' ascending order. Every metadata evaluation is
--               performed after those locks, so a prior writer is observed and
--               a later writer lands after the already-bounded grant
--   time        the boundary relation is recomputed under those locks, and the
--               grant instant must be strictly before its minimum
--
-- The returned grant binds the cap to its decision, Job, run, fence, holder,
-- platform, endpoint, Credential, scope grant, call sequence and epoch/digest
-- proof. The acquisition request is derived from this whole value, so the cap
-- cannot be rebound to another call identity.
--
-- Note what is not claimed. This bounds when a call may START. A remote call
-- that starts inside the window and returns after it has still started under a
-- live authority; bounding its completion would need a separate timeout proof,
-- and no such proof is claimed here.
CREATE FUNCTION platform.grant_call_authority(
    p_run_id               uuid,
    p_expected_fence       bigint,
    p_expected_lease_owner text,
    p_scope_grant_id       uuid,
    p_nominal_authority    interval,
    p_correlation_id       text)
RETURNS platform.call_authority_grant
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    run_row              record;
    job_row              record;
    epoch_scopes         text[];
    epoch_values         bigint[];
    evaluated            timestamptz;
    snapshot             platform.control_snapshot_temporal;
    grant_at             timestamptz;
    authority_at         timestamptz;
    selected_credential  uuid;
    credential_count     integer;
    credential_leaves    integer;
    credential_chain     integer;
    granted_call_seq     integer;
    decision             uuid;
    grant_result         platform.call_authority_grant;
BEGIN
    IF p_nominal_authority IS NULL OR p_nominal_authority <= interval '0' THEN
        RAISE EXCEPTION 'nominal call authority must be greater than zero'
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
        OR run_row.state <> 'LEASED'
        OR run_row.lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION
            'run % is not held by % at fence % in a live LEASED lease',
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

    -- The selected scope grant is caller-named but evaluated against the locked,
    -- derived Job identity under the epoch locks.
    PERFORM 1
      FROM iam.service_account_scope_grant AS scope_grant
      JOIN iam.service_account AS subject
        ON subject.id = scope_grant.service_account_id
     WHERE scope_grant.id = p_scope_grant_id
       AND scope_grant.service_account_id = job_row.service_account_id
       AND scope_grant.organization_id = job_row.organization_id
       AND scope_grant.permission_code = 'READ'
       AND scope_grant.status = 'ACTIVE'
       AND scope_grant.effective_from <= clock_timestamp()
       AND (scope_grant.effective_to IS NULL
            OR scope_grant.effective_to > clock_timestamp())
       AND (scope_grant.organization_ref_id = job_row.organization_id
            OR scope_grant.marketplace_account_ref_id = job_row.marketplace_account_id)
       AND subject.organization_id = job_row.organization_id
       AND subject.status = 'ACTIVE'
       AND subject.expires_at > clock_timestamp();
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'scope grant % does not authorise subject % over this job now',
            p_scope_grant_id, job_row.service_account_id
            USING ERRCODE = 'MO012';
    END IF;

    -- Derive the unique current ACCOUNT-scoped READ Credential rotation leaf.
    -- The caller cannot select a credential. One unrelated branch, a cycle, a
    -- STORE_SET candidate or no candidate is a fail-closed ambiguity.
    WITH RECURSIVE candidates AS MATERIALIZED (
        SELECT credential.id, credential.replaces_credential_id
          FROM platform.credential_metadata AS credential
         WHERE credential.marketplace_account_id = job_row.marketplace_account_id
           AND credential.organization_id = job_row.organization_id
           AND credential.purpose_code = 'READ'
           AND credential.scope_mode = 'ACCOUNT'
           AND credential.status = 'ACTIVE'
           AND credential.effective_from <= clock_timestamp()
           AND credential.expires_at > clock_timestamp()
    ), leaves AS (
        SELECT candidate.id, candidate.replaces_credential_id
          FROM candidates AS candidate
         WHERE NOT EXISTS (
                   SELECT 1
                     FROM candidates AS successor
                    WHERE successor.replaces_credential_id = candidate.id)
    ), chain (id, replaces_credential_id) AS (
        SELECT leaf.id, leaf.replaces_credential_id FROM leaves AS leaf
        UNION
        SELECT predecessor.id, predecessor.replaces_credential_id
          FROM candidates AS predecessor
          JOIN chain AS successor ON successor.replaces_credential_id = predecessor.id
    )
    SELECT (SELECT leaf.id FROM leaves AS leaf LIMIT 1),
           (SELECT count(*) FROM candidates),
           (SELECT count(*) FROM leaves),
           (SELECT count(*) FROM chain)
      INTO selected_credential, credential_count, credential_leaves, credential_chain;

    IF credential_count = 0
        OR credential_leaves <> 1
        OR credential_chain <> credential_count THEN
        RAISE EXCEPTION
            'account % has no unique current ACCOUNT READ credential rotation leaf',
            job_row.marketplace_account_id
            USING ERRCODE = 'MO013';
    END IF;

    -- Recompute the complete temporal relation using the server-selected leaf.
    evaluated := clock_timestamp();
    snapshot := platform.control_snapshot_temporal(
        job_row.service_account_id, p_scope_grant_id,
        job_row.marketplace_account_id, selected_credential, evaluated);
    grant_at := clock_timestamp();

    IF grant_at >= snapshot.valid_until THEN
        RAISE EXCEPTION
            'control snapshot expired at % before the grant at %',
            snapshot.valid_until, grant_at
            USING ERRCODE = 'MO011';
    END IF;

    authority_at := LEAST(grant_at + p_nominal_authority, snapshot.valid_until);
    IF authority_at <= grant_at THEN
        RAISE EXCEPTION 'call authority did not extend beyond its grant instant'
            USING ERRCODE = 'MO016';
    END IF;

    -- Consume the lease exactly once and derive the call sequence in the same
    -- guarded transition. No caller can choose or reuse that sequence.
    UPDATE ops.ingestion_run AS run
       SET state         = 'RUNNING',
           last_call_seq = run.last_call_seq + 1,
           updated_at    = grant_at
     WHERE run.id = p_run_id
       AND run.job_id = job_row.id
       AND run.fence_token = p_expected_fence
       AND run.lease_owner = p_expected_lease_owner
       AND run.state = 'LEASED'
       AND run.lease_expires_at > clock_timestamp()
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
        evaluated_at, granted_at,
        control_epoch_scopes, control_epoch_values,
        control_snapshot_valid_until, boundary_kind_count, boundary_kind_set,
        boundary_set_digest, winning_boundary_kind,
        call_authority_expires_at, correlation_id)
    VALUES (
        decision, job_row.id, p_run_id, p_expected_fence, p_expected_lease_owner,
        job_row.platform_code, job_row.endpoint_id, granted_call_seq,
        job_row.service_account_id, job_row.marketplace_account_id,
        p_scope_grant_id, selected_credential,
        evaluated, grant_at, epoch_scopes, epoch_values,
        snapshot.valid_until, snapshot.boundary_kind_count, snapshot.boundary_kind_set,
        snapshot.boundary_set_digest, snapshot.winning_kind,
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
    grant_result.control_epoch_scopes := epoch_scopes;
    grant_result.control_epoch_values := epoch_values;
    grant_result.boundary_set_digest := snapshot.boundary_set_digest;
    RETURN grant_result;
END;
$$;

REVOKE ALL ON FUNCTION platform.grant_call_authority(
    uuid, bigint, text, uuid, interval, text) FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'ingestion_run', 'NO_ROUTE', NULL,
        'runtime state of an acquisition; it consumes control facts and is not one'),
    ('ops', 'ingestion_checkpoint', 'NO_ROUTE', NULL,
        'acquisition progress; no evaluation reads it to decide authority'),
    ('raw', 'raw_content', 'NO_ROUTE', NULL,
        'immutable evidence; append-only and never an authorisation input'),
    ('raw', 'raw_logical_unit', 'NO_ROUTE', NULL,
        'immutable evidence; append-only and never an authorisation input'),
    ('raw', 'raw_acquisition_observation', 'NO_ROUTE', NULL,
        'immutable evidence; append-only and never an authorisation input');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Raw evidence is granted SELECT and INSERT and nothing else. Immutability is
-- then a property of the privilege set rather than of the code that writes it:
-- with no UPDATE privilege on any column, the application cannot rewrite a
-- stored observation, and it cannot take a row lock on one either.
GRANT SELECT, INSERT ON raw.raw_content TO marketops_app;
GRANT SELECT, INSERT ON raw.raw_logical_unit TO marketops_app;
GRANT SELECT, INSERT ON raw.raw_acquisition_observation TO marketops_app;

-- Run and checkpoint state moves only through the two SECURITY DEFINER
-- transition functions above. The application can watch both tables and can
-- change neither: with no INSERT or UPDATE privilege there is no direct-write
-- path around the fence, the lease, the state machine or the cursor CAS, for
-- well-behaved code and for an arbitrary SQL client alike.
GRANT SELECT ON ops.ingestion_run TO marketops_app;
GRANT SELECT ON ops.ingestion_checkpoint TO marketops_app;

GRANT EXECUTE ON FUNCTION ops.acknowledge_checkpoint(uuid, bigint, text, uuid, bigint, text)
    TO marketops_app;
GRANT EXECUTE ON FUNCTION platform.grant_call_authority(
    uuid, bigint, text, uuid, interval, text) TO marketops_app;

-- Deliberately not granted: DELETE anywhere, UPDATE on any raw table, and any
-- write on the run, the checkpoint or the decision evidence.
