-- The run lifecycle the acquisition worker drives, and the guarantee that a
-- replay downloads nothing.
--
-- V0010 gave the run its authority: a lease, a fence token, a cursor that
-- cannot outrun committed evidence and a grant primitive that issues one call
-- at a time. It deliberately gave the application no way to create or move a
-- run, because a state machine reachable by direct UPDATE is not a state
-- machine. This migration adds the transitions, each as a locking function that
-- rechecks the lease and the fence, so the application still holds no write
-- privilege on the run itself.
--
-- The replay guard is structural rather than procedural. A replay reprocesses
-- bytes that are already in custody; if it could reach a marketplace it would
-- not be a replay, it would be a second acquisition with a misleading name. The
-- trigger below refuses any advance of a replay run's call sequence, so the
-- guarantee holds against the grant primitive itself and not only against the
-- worker that normally calls it.
--
-- Error conditions raised here:
--
--   MO040  RUN_NOT_CLAIMABLE
--   MO041  RUN_TRANSITION_NOT_ALLOWED
--   MO042  REPLAY_RUN_CANNOT_ACQUIRE
--   MO043  RUN_JOB_NOT_ACTIVE

-- ---------------------------------------------------------------------------
-- What a job acquires, and why a run exists
-- ---------------------------------------------------------------------------

-- The dataset a job reads. The normalizer dispatches on this, so a job whose
-- dataset nobody recorded produces Raw evidence and no canonical facts, which
-- is visible rather than silent.
ALTER TABLE platform.ingestion_job
    ADD COLUMN dataset_kind text NOT NULL DEFAULT 'UNKNOWN',
    ADD CONSTRAINT ingestion_job_dataset_kind_ck
        CHECK (dataset_kind IN (
            'LISTING', 'LISTING_HEALTH', 'PRICE', 'STOCK', 'TRAFFIC',
            'SALES', 'RETURNS', 'FINANCE', 'ADVERTISING', 'UNKNOWN'));

-- The store a job's facts belong to.
--
-- A marketplace account can carry more than one store, and a price or a sale is
-- a fact about one of them. The composite foreign key pins the store to the
-- job's own account, so a job cannot attribute a fact to a store belonging to
-- somebody else's account. It stays nullable because a job may be registered
-- before its store is decided; normalization refuses a job without one rather
-- than guessing which store a fact belongs to.
ALTER TABLE platform.ingestion_job
    ADD COLUMN store_id uuid,
    ADD CONSTRAINT ingestion_job_store_account_fk
        FOREIGN KEY (store_id, marketplace_account_id)
        REFERENCES core.store (id, marketplace_account_id);

CREATE INDEX ingestion_job_store_ix
    ON platform.ingestion_job (store_id, dataset_kind)
    WHERE store_id IS NOT NULL;

-- Why this run exists. A scheduled and a manual run behave identically; a
-- backfill states an explicit window; a replay reprocesses stored evidence and
-- may not acquire at all.
ALTER TABLE ops.ingestion_run
    ADD COLUMN run_kind text NOT NULL DEFAULT 'SCHEDULED',
    ADD COLUMN window_from timestamptz,
    ADD COLUMN window_to timestamptz,
    ADD COLUMN failure_code text,
    ADD CONSTRAINT ingestion_run_kind_ck
        CHECK (run_kind IN ('SCHEDULED', 'MANUAL', 'BACKFILL', 'REPLAY')),
    ADD CONSTRAINT ingestion_run_window_ck
        CHECK (num_nonnulls(window_from, window_to) <> 1
            AND (window_from IS NULL OR window_from < window_to)),
    -- A bounded backfill must say what it is bounded to. An unbounded backfill
    -- is an unplanned full re-read of a source.
    ADD CONSTRAINT ingestion_run_backfill_window_ck
        CHECK (run_kind <> 'BACKFILL' OR window_from IS NOT NULL),
    ADD CONSTRAINT ingestion_run_failure_ck
        CHECK (state <> 'FAILED_TERMINAL' OR failure_code IS NOT NULL);

CREATE INDEX ingestion_run_claimable_ix
    ON ops.ingestion_run (state, updated_at)
    WHERE state IN ('QUEUED', 'RETRY_WAIT');

-- ---------------------------------------------------------------------------
-- Replay guard
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.replay_run_makes_no_call()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.run_kind = 'REPLAY' AND NEW.last_call_seq > OLD.last_call_seq THEN
        RAISE EXCEPTION 'replay run % may not acquire from a marketplace', OLD.id
            USING ERRCODE = 'MO042',
                  HINT = 'a replay reprocesses stored bytes and downloads nothing';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ingestion_run_replay_guard_bu
    BEFORE UPDATE ON ops.ingestion_run
    FOR EACH ROW
    EXECUTE FUNCTION ops.replay_run_makes_no_call();

-- ---------------------------------------------------------------------------
-- Transitions
-- ---------------------------------------------------------------------------

-- Create a run for a job, or refuse because one is already live.
--
-- The partial unique index on live runs is what makes "one live run per job"
-- true; this function turns the resulting constraint violation into a stable
-- refusal, and it also refuses a job that is not active, so a paused job cannot
-- be started by a scheduler that has not noticed.
CREATE FUNCTION ops.enqueue_ingestion_run(
    p_run_id      uuid,
    p_job_id      uuid,
    p_run_kind    text,
    p_window_from timestamptz,
    p_window_to   timestamptz)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    job_state text;
BEGIN
    SELECT job.status INTO job_state
      FROM platform.ingestion_job AS job
     WHERE job.id = p_job_id;

    IF job_state IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'job % is not active', p_job_id
            USING ERRCODE = 'MO043';
    END IF;

    BEGIN
        INSERT INTO ops.ingestion_run (
            id, job_id, state, fence_token, lease_owner, lease_expires_at,
            attempt_no, last_call_seq, run_kind, window_from, window_to,
            created_at, updated_at)
        VALUES (p_run_id, p_job_id, 'QUEUED', 1, NULL, NULL,
            0, 0, p_run_kind, p_window_from, p_window_to,
            clock_timestamp(), clock_timestamp());
    EXCEPTION WHEN unique_violation THEN
        RAISE EXCEPTION 'job % already has a live run', p_job_id
            USING ERRCODE = 'MO040';
    END;

    -- A job acquires nothing until it has a cursor to advance, so the
    -- checkpoint is created with the run rather than by a separate step that
    -- could be forgotten.
    INSERT INTO ops.ingestion_checkpoint (job_id, strategy, position_value,
                                          checkpoint_version, updated_at)
    SELECT p_job_id, endpoint.pagination_model, NULL, 0, clock_timestamp()
      FROM platform.ingestion_job AS job
      JOIN platform.platform_endpoint AS endpoint ON endpoint.id = job.endpoint_id
     WHERE job.id = p_job_id
    ON CONFLICT (job_id) DO NOTHING;

    RETURN p_run_id;
END;
$$;

REVOKE ALL ON FUNCTION ops.enqueue_ingestion_run(uuid, uuid, text, timestamptz, timestamptz)
    FROM PUBLIC;

-- Claim a run for one worker and return the fence token it must carry.
--
-- A run whose lease has expired is claimable again: the previous holder's fence
-- token is now lower than the row's, so its later writes match no row. That is
-- the whole takeover protocol, and it needs no coordination beyond this row.
CREATE FUNCTION ops.claim_ingestion_run(
    p_run_id        uuid,
    p_lease_owner   text,
    p_lease_seconds integer)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    run_row   record;
    new_fence bigint;
BEGIN
    IF p_lease_owner IS NULL OR length(btrim(p_lease_owner)) = 0 THEN
        RAISE EXCEPTION 'a lease owner is required' USING ERRCODE = 'MO040';
    END IF;
    IF p_lease_seconds IS NULL OR p_lease_seconds <= 0 OR p_lease_seconds > 3600 THEN
        RAISE EXCEPTION 'lease duration must be between 1 and 3600 seconds'
            USING ERRCODE = 'MO040';
    END IF;

    SELECT run.id, run.state, run.lease_expires_at
      INTO run_row
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       FOR UPDATE OF run;

    IF run_row.id IS NULL THEN
        RAISE EXCEPTION 'run % does not exist', p_run_id USING ERRCODE = 'MO040';
    END IF;

    IF NOT (run_row.state IN ('QUEUED', 'RETRY_WAIT')
            OR (run_row.state IN ('LEASED', 'RUNNING')
                AND run_row.lease_expires_at <= clock_timestamp()))
    THEN
        RAISE EXCEPTION 'run % is not claimable from state %', p_run_id, run_row.state
            USING ERRCODE = 'MO040';
    END IF;

    UPDATE ops.ingestion_run AS run
       SET state = 'LEASED',
           fence_token = run.fence_token + 1,
           attempt_no = run.attempt_no + 1,
           lease_owner = p_lease_owner,
           lease_expires_at = clock_timestamp() + make_interval(secs => p_lease_seconds),
           updated_at = clock_timestamp()
     WHERE run.id = p_run_id
       AND run.state = run_row.state
    RETURNING run.fence_token INTO new_fence;

    IF new_fence IS NULL THEN
        RAISE EXCEPTION 'run % changed while being claimed', p_run_id
            USING ERRCODE = 'MO040';
    END IF;
    RETURN new_fence;
END;
$$;

REVOKE ALL ON FUNCTION ops.claim_ingestion_run(uuid, text, integer) FROM PUBLIC;

-- Move a claimed run between its working states, or refuse.
--
-- The lease and the fence are rechecked here as well as at claim time, because
-- a worker that stalled long enough for its lease to lapse must not be able to
-- resume as if nothing happened. Terminal states release the lease so the row
-- stops being claimable.
CREATE FUNCTION ops.transition_ingestion_run(
    p_run_id               uuid,
    p_expected_fence       bigint,
    p_expected_lease_owner text,
    p_to_state             text,
    p_lease_seconds        integer,
    p_failure_code         text)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    run_row       record;
    allowed       boolean;
    releases      boolean;
    updated_state text;
BEGIN
    SELECT run.id, run.state, run.fence_token, run.lease_owner, run.lease_expires_at
      INTO run_row
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       FOR UPDATE OF run;

    IF run_row.id IS NULL THEN
        RAISE EXCEPTION 'run % does not exist', p_run_id USING ERRCODE = 'MO040';
    END IF;

    allowed := (run_row.state, p_to_state) IN (
        ('LEASED', 'RUNNING'),
        ('LEASED', 'QUEUED'),
        ('RUNNING', 'SUCCEEDED'),
        ('RUNNING', 'RETRY_WAIT'),
        ('RUNNING', 'BLOCKED'),
        ('RUNNING', 'FAILED_TERMINAL'),
        ('LEASED', 'FAILED_TERMINAL'),
        ('BLOCKED', 'RETRY_WAIT'),
        ('BLOCKED', 'FAILED_TERMINAL'));

    IF NOT allowed THEN
        RAISE EXCEPTION 'run % may not move from % to %',
            p_run_id, run_row.state, p_to_state
            USING ERRCODE = 'MO041';
    END IF;

    IF run_row.fence_token <> p_expected_fence
        OR run_row.lease_owner IS DISTINCT FROM p_expected_lease_owner
        OR run_row.lease_expires_at IS NULL
        OR run_row.lease_expires_at <= clock_timestamp()
    THEN
        RAISE EXCEPTION 'run % is not held by % at fence % with a live lease',
            p_run_id, p_expected_lease_owner, p_expected_fence
            USING ERRCODE = 'MO040';
    END IF;

    IF p_to_state = 'FAILED_TERMINAL'
        AND (p_failure_code IS NULL OR length(btrim(p_failure_code)) = 0)
    THEN
        RAISE EXCEPTION 'a terminal failure of run % must name a reason', p_run_id
            USING ERRCODE = 'MO041';
    END IF;

    releases := p_to_state IN ('QUEUED', 'RETRY_WAIT', 'BLOCKED',
                               'SUCCEEDED', 'FAILED_TERMINAL');

    UPDATE ops.ingestion_run AS run
       SET state = p_to_state,
           lease_owner = CASE WHEN releases THEN NULL ELSE run.lease_owner END,
           lease_expires_at = CASE
                                  WHEN releases THEN NULL
                                  WHEN p_lease_seconds IS NULL THEN run.lease_expires_at
                                  ELSE clock_timestamp()
                                       + make_interval(secs => p_lease_seconds)
                              END,
           failure_code = CASE WHEN p_to_state = 'FAILED_TERMINAL'
                                   THEN p_failure_code ELSE NULL END,
           updated_at = clock_timestamp()
     WHERE run.id = p_run_id
       AND run.state = run_row.state
       AND run.fence_token = p_expected_fence
    RETURNING run.state INTO updated_state;

    IF updated_state IS NULL THEN
        RAISE EXCEPTION 'run % changed while transitioning to %', p_run_id, p_to_state
            USING ERRCODE = 'MO040';
    END IF;
    RETURN updated_state;
END;
$$;

REVOKE ALL ON FUNCTION ops.transition_ingestion_run(
    uuid, bigint, text, text, integer, text) FROM PUBLIC;

-- Extend a live lease without changing state.
--
-- A worker that is making progress renews rather than letting its lease lapse
-- and being taken over mid-page. Renewal requires the same fence and owner as
-- every other authoritative write, so a superseded worker cannot extend a lease
-- it no longer holds.
CREATE FUNCTION ops.renew_ingestion_run_lease(
    p_run_id               uuid,
    p_expected_fence       bigint,
    p_expected_lease_owner text,
    p_lease_seconds        integer)
RETURNS timestamptz
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    renewed timestamptz;
BEGIN
    IF p_lease_seconds IS NULL OR p_lease_seconds <= 0 OR p_lease_seconds > 3600 THEN
        RAISE EXCEPTION 'lease duration must be between 1 and 3600 seconds'
            USING ERRCODE = 'MO040';
    END IF;

    UPDATE ops.ingestion_run AS run
       SET lease_expires_at = clock_timestamp() + make_interval(secs => p_lease_seconds),
           updated_at = clock_timestamp()
     WHERE run.id = p_run_id
       AND run.fence_token = p_expected_fence
       AND run.lease_owner = p_expected_lease_owner
       AND run.state IN ('LEASED', 'RUNNING')
       AND run.lease_expires_at > clock_timestamp()
    RETURNING run.lease_expires_at INTO renewed;

    IF renewed IS NULL THEN
        RAISE EXCEPTION 'run % is not held by % at fence % with a live lease',
            p_run_id, p_expected_lease_owner, p_expected_fence
            USING ERRCODE = 'MO040';
    END IF;
    RETURN renewed;
END;
$$;

REVOKE ALL ON FUNCTION ops.renew_ingestion_run_lease(uuid, bigint, text, integer)
    FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- The application still holds no INSERT or UPDATE on the run. Every transition
-- goes through a function above, so the lease, the fence, the reviewed
-- transition set and the replay guard cannot be bypassed by any SQL client
-- connecting as this role.
GRANT EXECUTE ON FUNCTION ops.enqueue_ingestion_run(
    uuid, uuid, text, timestamptz, timestamptz) TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.claim_ingestion_run(uuid, text, integer) TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.transition_ingestion_run(
    uuid, bigint, text, text, integer, text) TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.renew_ingestion_run_lease(uuid, bigint, text, integer)
    TO marketops_app;
