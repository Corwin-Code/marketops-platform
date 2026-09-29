-- V0016: let an operator resolve an ingestion run that came to rest BLOCKED.
--
-- A run stops BLOCKED when a page could not be classified: an answer of unknown state, schema
-- drift, an unreadable body or an invalid configuration. BLOCKED keeps the job's single live-run
-- slot (ingestion_run_live_uq), and ops.transition_ingestion_run only moves a run held under a live
-- lease, which a blocked run no longer has; so nothing could ever take a blocked run anywhere, and
-- its job could never run again. Found on the first run of a newly registered endpoint whose
-- outbound destination rule was missing (2026-09-29).
--
-- ops.resolve_blocked_ingestion_run moves a BLOCKED run, and nothing else:
--   * RETRY: to RETRY_WAIT, claimable at once (or when the endpoint's quota window reopens), for a
--     cause somebody fixed; the retry counts against the run's claim budget like any other;
--   * CLOSE: to FAILED_TERMINAL with failure code CLOSED_BY_OPERATOR, freeing the job.
-- The operator and the reason are recorded by the maintenance route's audit.

CREATE FUNCTION ops.resolve_blocked_ingestion_run(p_run_id uuid, p_resolution text) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
    run_row  record;
    to_state text;
BEGIN
    IF p_resolution IS NULL OR p_resolution NOT IN ('RETRY', 'CLOSE') THEN
        RAISE EXCEPTION 'a blocked run is resolved by RETRY or CLOSE' USING ERRCODE = 'MO041';
    END IF;

    SELECT run.id, run.state, run.lease_owner
      INTO run_row
      FROM ops.ingestion_run AS run
     WHERE run.id = p_run_id
       FOR UPDATE OF run;

    IF run_row.id IS NULL THEN
        RAISE EXCEPTION 'run % does not exist', p_run_id USING ERRCODE = 'MO040';
    END IF;
    IF run_row.state <> 'BLOCKED' OR run_row.lease_owner IS NOT NULL THEN
        RAISE EXCEPTION 'run % is not blocked', p_run_id USING ERRCODE = 'MO041';
    END IF;

    to_state := CASE p_resolution WHEN 'RETRY' THEN 'RETRY_WAIT' ELSE 'FAILED_TERMINAL' END;

    UPDATE ops.ingestion_run AS run
       SET state = to_state,
           next_attempt_at = CASE WHEN to_state = 'RETRY_WAIT'
               THEN GREATEST(clock_timestamp(),
                   (SELECT quota.blocked_until FROM platform.ingestion_job job
                       JOIN ops.endpoint_quota_window quota ON quota.endpoint_id = job.endpoint_id
                      WHERE job.id = run.job_id))
               ELSE NULL END,
           failure_code = CASE WHEN to_state = 'FAILED_TERMINAL' THEN 'CLOSED_BY_OPERATOR' ELSE NULL END,
           updated_at = clock_timestamp()
     WHERE run.id = p_run_id;

    RETURN to_state;
END;
$$;

REVOKE ALL ON FUNCTION ops.resolve_blocked_ingestion_run(p_run_id uuid, p_resolution text) FROM PUBLIC;

GRANT ALL ON FUNCTION ops.resolve_blocked_ingestion_run(p_run_id uuid, p_resolution text) TO marketops_app;
