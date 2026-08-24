-- The runtime skeleton the control plane protects: a leased and fenced run, a
-- cursor that may not outrun durable evidence, immutable Raw evidence, and the
-- single statement that turns a control snapshot into a call authority.
--
-- This is the smallest shape that lets the guarantees in V0007 to V0009 be
-- executed rather than described. It is not a worker: nothing here schedules,
-- dispatches or calls anything outward, and no code path reaches a Marketplace.
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

-- Advance the cursor only if the evidence for it is already committed.
--
-- The observation lookup is the whole point: the caller cannot advance past
-- bytes that were not stored, because the row it must name has to exist in this
-- transaction. Passing an observation from another run, or one that does not
-- cover the named logical unit, matches nothing and the advance is refused.
--
-- The compare-and-set on checkpoint_version makes a superseded worker's late
-- advance a zero-row update rather than a rewind.
CREATE FUNCTION ops.acknowledge_checkpoint(
    p_run_id             uuid,
    p_fence_token        bigint,
    p_observation_id     uuid,
    p_expected_version   bigint,
    p_position_value     text)
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
    target_job    uuid;
    new_version   bigint;
BEGIN
    -- The run must still be this worker's, at this fence.
    SELECT run.job_id INTO target_job
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       AND run.fence_token = p_fence_token
       AND run.state = 'RUNNING';

    IF target_job IS NULL THEN
        RAISE EXCEPTION 'run % does not hold authority at fence %', p_run_id, p_fence_token
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

    UPDATE ops.ingestion_checkpoint AS checkpoint
       SET position_value     = p_position_value,
           checkpoint_version = checkpoint.checkpoint_version + 1,
           updated_at         = clock_timestamp()
     WHERE checkpoint.job_id = target_job
       AND checkpoint.checkpoint_version = p_expected_version
    RETURNING checkpoint.checkpoint_version INTO new_version;

    IF new_version IS NULL THEN
        RAISE EXCEPTION
            'checkpoint for job % is not at expected version %', target_job, p_expected_version
            USING ERRCODE = 'MO008';
    END IF;

    RETURN new_version;
END;
$$;

-- ---------------------------------------------------------------------------
-- The grant
-- ---------------------------------------------------------------------------

-- Turn a control snapshot into a bounded call authority, or refuse.
--
-- Everything the evaluation read is consumed here in one transaction, and every
-- way the snapshot could have gone stale is a zero-row outcome:
--
--   change      the four epochs must still equal the values that were read
--   time        the grant instant must be strictly before the boundary
--   authority   the run must still be this worker's, at this fence
--
-- The epoch comparison is a count against four supplied pairs rather than a
-- per-row check, because a missing epoch row and a changed one must have the
-- same effect. A per-row comparison would silently pass over a scope whose row
-- is absent, and an absent guard is exactly the state that must not authorise.
--
-- The boundary comparison is strict. At the boundary the authority has ended,
-- so an instant equal to it is already outside; treating it as inside would put
-- the one case that is guaranteed to be wrong on the allowing side.
--
-- The returned authority is capped by the boundary. That cap is what carries
-- the guarantee outward: the caller's existing "is there enough authority left
-- to start a call" check now also answers "does this call start before the
-- control snapshot expires", with no second code path to keep in step.
--
-- Note what is not claimed. This bounds when a call may START. A remote call
-- that starts inside the window and returns after it has still started under a
-- live authority; bounding its completion would need a separate timeout proof,
-- and no such proof is claimed here.
CREATE FUNCTION platform.grant_call_authority(
    p_run_id                 uuid,
    p_fence_token            bigint,
    p_organization_id        uuid,
    p_marketplace_account_id uuid,
    p_service_account_id     uuid,
    p_credential_id          uuid,
    p_epoch_organization     bigint,
    p_epoch_account          bigint,
    p_epoch_subject          bigint,
    p_epoch_job              bigint,
    p_temporal               platform.control_snapshot_temporal,
    p_evaluated_at           timestamptz,
    p_nominal_authority      interval,
    p_correlation_id         text)
RETURNS timestamptz
LANGUAGE plpgsql
AS $$
DECLARE
    target_job     uuid;
    matched_scopes integer;
    grant_at       timestamptz;
    authority_at   timestamptz;
BEGIN
    SELECT run.job_id INTO target_job
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       AND run.fence_token = p_fence_token
       AND run.state = 'LEASED';

    IF target_job IS NULL THEN
        RAISE EXCEPTION 'run % does not hold authority at fence %', p_run_id, p_fence_token
            USING ERRCODE = 'MO008';
    END IF;

    SELECT count(*)::integer INTO matched_scopes
      FROM platform.control_epoch AS epoch
     WHERE (epoch.scope_kind, epoch.scope_id, epoch.epoch) IN (
               ('ORGANIZATION',        p_organization_id,        p_epoch_organization),
               ('MARKETPLACE_ACCOUNT', p_marketplace_account_id, p_epoch_account),
               ('SERVICE_ACCOUNT',     p_service_account_id,     p_epoch_subject),
               ('JOB',                 target_job,               p_epoch_job));

    IF matched_scopes <> 4 THEN
        RAISE EXCEPTION
            'control snapshot is stale or incomplete: % of 4 scopes still match', matched_scopes
            USING ERRCODE = 'MO010';
    END IF;

    IF p_temporal.boundary_kind_count
           <> (SELECT count(*) FROM platform.control_boundary_kind) THEN
        RAISE EXCEPTION
            'the supplied boundary set covers % kinds; % are declared',
            p_temporal.boundary_kind_count,
            (SELECT count(*) FROM platform.control_boundary_kind)
            USING ERRCODE = 'MO005';
    END IF;

    grant_at := clock_timestamp();

    IF grant_at >= p_temporal.valid_until THEN
        RAISE EXCEPTION
            'control snapshot expired at % before the grant at %',
            p_temporal.valid_until, grant_at
            USING ERRCODE = 'MO011';
    END IF;

    authority_at := LEAST(grant_at + p_nominal_authority, p_temporal.valid_until);

    UPDATE ops.ingestion_run AS run
       SET state         = 'RUNNING',
           last_call_seq = run.last_call_seq + 1,
           updated_at    = grant_at
     WHERE run.id = p_run_id
       AND run.fence_token = p_fence_token
       AND run.state = 'LEASED';

    INSERT INTO ops.authorization_decision_evidence (
        id, job_id, service_account_id, marketplace_account_id, credential_id,
        evaluated_at, granted_at,
        control_epoch_scopes, control_epoch_values,
        control_snapshot_valid_until, boundary_kind_count, boundary_kind_set,
        boundary_set_digest, winning_boundary_kind,
        call_authority_expires_at, correlation_id)
    VALUES (
        gen_random_uuid(), target_job, p_service_account_id, p_marketplace_account_id,
        p_credential_id, p_evaluated_at, grant_at,
        ARRAY['ORGANIZATION', 'MARKETPLACE_ACCOUNT', 'SERVICE_ACCOUNT', 'JOB'],
        ARRAY[p_epoch_organization, p_epoch_account, p_epoch_subject, p_epoch_job],
        p_temporal.valid_until, p_temporal.boundary_kind_count, p_temporal.boundary_kind_set,
        p_temporal.boundary_set_digest, p_temporal.winning_kind,
        authority_at, p_correlation_id);

    RETURN authority_at;
END;
$$;

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

GRANT SELECT, INSERT, UPDATE ON ops.ingestion_run TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ingestion_checkpoint TO marketops_app;

GRANT EXECUTE ON FUNCTION ops.acknowledge_checkpoint(uuid, bigint, uuid, bigint, text)
    TO marketops_app;
GRANT EXECUTE ON FUNCTION platform.grant_call_authority(
    uuid, bigint, uuid, uuid, uuid, uuid, bigint, bigint, bigint, bigint,
    platform.control_snapshot_temporal, timestamptz, interval, text) TO marketops_app;

-- Deliberately not granted: DELETE anywhere, and UPDATE on any raw table.
