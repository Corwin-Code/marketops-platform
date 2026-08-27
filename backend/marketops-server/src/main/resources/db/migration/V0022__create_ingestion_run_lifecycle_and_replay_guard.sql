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
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN max_claims integer NOT NULL DEFAULT 4 CHECK (max_claims BETWEEN 1 AND 11),
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
    p_window_to   timestamptz,
    p_max_claims  integer DEFAULT 4)
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
            attempt_no, last_call_seq, run_kind, window_from, window_to, max_claims,
            created_at, updated_at)
        VALUES (p_run_id, p_job_id, 'QUEUED', 1, NULL, NULL,
            0, 0, p_run_kind, p_window_from, p_window_to, p_max_claims,
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

REVOKE ALL ON FUNCTION ops.enqueue_ingestion_run(uuid, uuid, text, timestamptz, timestamptz, integer)
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

    SELECT run.id, run.state, run.lease_expires_at, run.next_attempt_at, run.attempt_no, run.max_claims
      INTO run_row
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       FOR UPDATE OF run;

    IF run_row.id IS NULL THEN
        RAISE EXCEPTION 'run % does not exist', p_run_id USING ERRCODE = 'MO040';
    END IF;

    IF NOT (run_row.state='QUEUED' OR (run_row.state='RETRY_WAIT' AND run_row.next_attempt_at <= clock_timestamp())
            OR (run_row.state IN ('LEASED', 'RUNNING')
                AND run_row.lease_expires_at <= clock_timestamp()))
    THEN
        RAISE EXCEPTION 'run % is not claimable from state %', p_run_id, run_row.state
            USING ERRCODE = 'MO040';
    END IF;

    -- A stopped worker consumes a claim too. Persist exhaustion without raising
    -- an exception, which would roll back the terminal recovery transition.
    IF run_row.attempt_no >= run_row.max_claims THEN
        UPDATE ops.ingestion_run SET state='FAILED_TERMINAL', failure_code='RETRY_BUDGET_EXHAUSTED',
            lease_owner=NULL, lease_expires_at=NULL, next_attempt_at=NULL, updated_at=clock_timestamp()
         WHERE id=p_run_id;
        RETURN NULL;
    END IF;

    UPDATE ops.ingestion_run AS run
       SET state = 'LEASED', next_attempt_at = NULL,
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
    p_failure_code         text,
    p_retry_delay_seconds  integer DEFAULT 120)
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
    IF p_to_state='RETRY_WAIT' AND (p_retry_delay_seconds IS NULL OR p_retry_delay_seconds < 1 OR p_retry_delay_seconds > 3600) THEN
        RAISE EXCEPTION 'retry delay is outside its bound' USING ERRCODE='MO040';
    END IF;
    SELECT run.id, run.state, run.fence_token, run.lease_owner, run.lease_expires_at,
           run.attempt_no, run.max_claims
      INTO run_row
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       FOR UPDATE OF run;

    IF run_row.id IS NULL THEN
        RAISE EXCEPTION 'run % does not exist', p_run_id USING ERRCODE = 'MO040';
    END IF;

    IF p_to_state='RETRY_WAIT' AND run_row.attempt_no >= run_row.max_claims THEN
        p_to_state := 'FAILED_TERMINAL';
        p_failure_code := 'RETRY_BUDGET_EXHAUSTED';
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
           next_attempt_at = CASE WHEN p_to_state='RETRY_WAIT'
               THEN GREATEST(clock_timestamp()+make_interval(secs=>p_retry_delay_seconds),
                   (SELECT quota.blocked_until FROM platform.ingestion_job job
                       JOIN ops.endpoint_quota_window quota ON quota.endpoint_id=job.endpoint_id WHERE job.id=run.job_id))
               ELSE NULL END,
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
    uuid, bigint, text, text, integer, text, integer) FROM PUBLIC;

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
    uuid, uuid, text, timestamptz, timestamptz, integer) TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.claim_ingestion_run(uuid, text, integer) TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.transition_ingestion_run(
    uuid, bigint, text, text, integer, text, integer) TO marketops_app;
GRANT EXECUTE ON FUNCTION ops.renew_ingestion_run_lease(uuid, bigint, text, integer)
    TO marketops_app;

-- One database bucket per endpoint is shared by every process and account.
-- Missing or invalid limits grant no calls; memory and row growth are bounded by endpoint count.
CREATE TABLE ops.endpoint_quota_window (
    endpoint_id uuid PRIMARY KEY REFERENCES platform.platform_endpoint(id),
    window_started_at timestamptz NOT NULL,
    blocked_until timestamptz,
    used_calls integer NOT NULL CHECK (used_calls > 0)
);
GRANT SELECT ON ops.endpoint_quota_window TO marketops_app;
CREATE FUNCTION platform.reserve_endpoint_quota(p_endpoint uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE published_limit integer; permitted integer; window_start timestamptz;
BEGIN
    SELECT rate_limit_per_minute INTO published_limit FROM platform.platform_endpoint
     WHERE id=p_endpoint AND status='ACTIVE' AND verification_state='VERIFIED';
    IF published_limit IS NULL OR published_limit <= 0 OR published_limit > 60000 THEN RETURN false; END IF;
    window_start := clock_timestamp();
    INSERT INTO ops.endpoint_quota_window(endpoint_id,window_started_at,used_calls)
      VALUES(p_endpoint,window_start,1)
      ON CONFLICT(endpoint_id) DO UPDATE SET window_started_at=
        CASE WHEN ops.endpoint_quota_window.window_started_at <= EXCLUDED.window_started_at-interval '1 minute'
             THEN EXCLUDED.window_started_at ELSE ops.endpoint_quota_window.window_started_at END,
        used_calls=CASE WHEN ops.endpoint_quota_window.window_started_at <= EXCLUDED.window_started_at-interval '1 minute'
                       THEN 1 ELSE ops.endpoint_quota_window.used_calls+1 END
      WHERE (ops.endpoint_quota_window.blocked_until IS NULL OR ops.endpoint_quota_window.blocked_until<=EXCLUDED.window_started_at)
       AND (ops.endpoint_quota_window.window_started_at <= EXCLUDED.window_started_at-interval '1 minute'
         OR (ops.endpoint_quota_window.window_started_at <= EXCLUDED.window_started_at
             AND ops.endpoint_quota_window.used_calls < published_limit))
      RETURNING used_calls INTO permitted;
    RETURN permitted IS NOT NULL;
END $$;
REVOKE ALL ON FUNCTION platform.reserve_endpoint_quota(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.reserve_endpoint_quota(uuid) TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
    VALUES ('ops','endpoint_quota_window','NO_ROUTE',NULL,'Internal bounded quota; only reserve_endpoint_quota mutates it');

ALTER TABLE raw.raw_acquisition_observation ADD COLUMN response_complete boolean NOT NULL DEFAULT true,
    ADD COLUMN transport_failure_code text,
    ADD COLUMN authority_decision_id uuid REFERENCES ops.authorization_decision_evidence(id),
    ADD COLUMN response_headers jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(response_headers)='object'),
    ADD COLUMN pagination_outcome text NOT NULL DEFAULT 'UNASSESSED'
        CHECK (pagination_outcome IN ('UNASSESSED','END','NEXT','UNKNOWN_RESULT','SCHEMA_DRIFT','UNREADABLE','CONFIG_INVALID','RETRY_LATER'));

-- Provider backpressure shares the same durable bucket as call admission.
CREATE FUNCTION platform.defer_endpoint_quota(p_endpoint uuid,p_status integer,p_headers jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE retry_after text; delay_seconds integer := 60;
BEGIN
    IF p_status NOT IN (429,503) THEN RETURN; END IF;
    retry_after := p_headers->>'retry-after';
    BEGIN
        IF retry_after ~ '^[0-9]{1,9}$' THEN
            delay_seconds := LEAST(86400,GREATEST(1,retry_after::bigint));
        ELSIF retry_after ~ '^[A-Za-z]{3}, [0-9]{2} [A-Za-z]{3} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT$' THEN
            delay_seconds := LEAST(86400,GREATEST(1,ceil(extract(epoch FROM (retry_after::timestamptz-clock_timestamp())))::bigint));
        END IF;
    EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow OR numeric_value_out_of_range THEN
        delay_seconds := 60;
    END;
    UPDATE ops.endpoint_quota_window SET blocked_until=GREATEST(blocked_until,
            clock_timestamp()+make_interval(secs=>delay_seconds)) WHERE endpoint_id=p_endpoint;
END $$;
REVOKE ALL ON FUNCTION platform.defer_endpoint_quota(uuid,integer,jsonb) FROM PUBLIC;

CREATE FUNCTION raw.bind_acquisition_receipt() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE endpoint uuid; header record;
BEGIN
    IF NEW.outcome_class='SUCCESS_BYTES' AND NOT NEW.response_complete THEN
        RAISE EXCEPTION 'partial bytes cannot prove acquisition success' USING ERRCODE='MO009';
    END IF;
    FOR header IN SELECT key,value FROM jsonb_each_text(NEW.response_headers) LOOP
        IF header.key NOT IN ('content-type','retry-after','x-ratelimit-remaining','x-ratelimit-reset','x-ratelimit-limit')
           OR length(header.value)>256 OR header.value ~ '[[:cntrl:]]' THEN
            RAISE EXCEPTION 'unsafe acquisition response metadata' USING ERRCODE='MO009';
        END IF;
    END LOOP;
    IF NEW.authority_decision_id IS NOT NULL THEN
        SELECT decision.endpoint_id INTO endpoint FROM ops.authorization_decision_evidence decision
          JOIN raw.raw_logical_unit unit ON unit.id=NEW.logical_unit_id
          JOIN platform.ingestion_job job ON job.id=decision.job_id
         WHERE decision.id=NEW.authority_decision_id AND decision.run_id=NEW.run_id AND decision.call_seq=NEW.call_seq
           AND unit.job_id=decision.job_id AND unit.marketplace_account_id=job.marketplace_account_id;
        IF endpoint IS NULL THEN
            RAISE EXCEPTION 'acquisition receipt does not match its committed authority' USING ERRCODE='MO009';
        END IF;
        IF NEW.native_status ~ '^HTTP [0-9]{3}$' THEN
            PERFORM platform.defer_endpoint_quota(endpoint,substring(NEW.native_status FROM 6)::integer,NEW.response_headers);
        END IF;
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION raw.bind_acquisition_receipt() FROM PUBLIC;
CREATE TRIGGER acquisition_receipt_integrity BEFORE INSERT ON raw.raw_acquisition_observation
    FOR EACH ROW EXECUTE FUNCTION raw.bind_acquisition_receipt();

-- Checkpoints require both immutable bytes and an explicit successful pagination assessment.
CREATE OR REPLACE FUNCTION ops.acknowledge_checkpoint(
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
       AND observation.response_complete AND observation.outcome_class='SUCCESS_BYTES'
       AND observation.pagination_outcome IN ('END','NEXT')
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
              AND observation.response_complete AND observation.outcome_class='SUCCESS_BYTES'
              AND observation.pagination_outcome IN ('END','NEXT')
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
