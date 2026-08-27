-- Two things the write path needs before a crash can be survived: an attempt
-- record that outlives the call it describes, and a way for a command whose
-- worker died to become an operator's problem rather than a stuck row.
--
-- An attempt is written before the platform is called and completed after, so a
-- process that dies mid-call still leaves evidence that a call was started. The
-- completion is granted at column level and permitted exactly once by the
-- trigger below, which keeps the guarantee that a finished attempt is immutable
-- while allowing the one transition that turns a started call into a recorded
-- outcome.
--
-- Lease recovery moves through the same reviewed transition set as everything
-- else. A command whose worker vanished mid-call goes to
-- UNKNOWN_REQUIRES_READBACK, because the call may have reached the marketplace
-- and may have changed a real price; a command that was only claimed goes back
-- to PENDING, because nothing was called. Neither is a new path: both edges
-- already exist in ops.price_command_transition.
--
-- Error conditions raised here:
--
--   MO037  PRICE_COMMAND_ATTEMPT_ALREADY_COMPLETED
--   MO038  PRICE_COMMAND_COMPENSATION_WITHOUT_READBACK

-- ---------------------------------------------------------------------------
-- Attempt completion
-- ---------------------------------------------------------------------------

-- A completed attempt is permanent. Allowing it to be rewritten would make the
-- record of what was called and what came back editable, which is the record a
-- disputed price change is settled by.
CREATE FUNCTION ops.price_command_attempt_completes_once()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.outcome_class <> 'IN_FLIGHT' THEN
        RAISE EXCEPTION 'price command attempt % is already completed', OLD.id
            USING ERRCODE = 'MO037';
    END IF;

    IF NEW.outcome_class = 'IN_FLIGHT' THEN
        RAISE EXCEPTION 'completing price command attempt % must classify the answer',
            OLD.id
            USING ERRCODE = 'MO037';
    END IF;

    -- Everything that identifies the call is fixed at the moment it was made.
    IF NEW.id <> OLD.id
        OR NEW.command_id <> OLD.command_id
        OR NEW.attempt_no <> OLD.attempt_no
        OR NEW.purpose <> OLD.purpose
        OR NEW.fence_token <> OLD.fence_token
        OR NEW.lease_owner <> OLD.lease_owner
        OR NEW.started_at <> OLD.started_at
        OR NEW.correlation_id <> OLD.correlation_id
    THEN
        RAISE EXCEPTION 'the identity of price command attempt % cannot change', OLD.id
            USING ERRCODE = 'MO037';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER price_command_attempt_completes_once_bu
    BEFORE UPDATE ON ops.price_command_attempt
    FOR EACH ROW EXECUTE FUNCTION ops.price_command_attempt_completes_once();

GRANT UPDATE (completed_at, outcome_class, native_status, native_task_key,
              raw_observation_id, error_code)
    ON ops.price_command_attempt TO marketops_app;

-- ---------------------------------------------------------------------------
-- Lease recovery
-- ---------------------------------------------------------------------------

-- Hand back every command whose worker stopped holding it.
--
-- The function only ever acts on a lease that has already expired, and it moves
-- each command along an edge that is in the reviewed transition set. It grants
-- no authority: recovering a command makes it claimable again, and the write
-- gate is evaluated afresh when a worker next claims it.
CREATE FUNCTION ops.recover_expired_price_command_leases()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    recovered integer := 0;
    moved     integer;
BEGIN
    -- Claimed but never called: nothing reached the platform, so the command
    -- returns to the queue exactly as it was.
    WITH expired AS (
        SELECT command.id
          FROM ops.price_command AS command
         WHERE command.state = 'LEASED'
           AND command.lease_expires_at IS NOT NULL
           AND command.lease_expires_at <= clock_timestamp()
           FOR UPDATE OF command SKIP LOCKED
    )
    UPDATE ops.price_command AS command
       SET state = 'PENDING', lease_owner = NULL, lease_expires_at = NULL,
           next_attempt_at = clock_timestamp(), updated_at = clock_timestamp()
      FROM expired
     WHERE command.id = expired.id;
    GET DIAGNOSTICS moved = ROW_COUNT;
    recovered := recovered + moved;

    -- A call may have been made. Whether the marketplace applied it is exactly
    -- what nobody knows, which is the state this moves to and the reason there
    -- is no path from it back to executing.
    WITH expired AS (
        SELECT command.id
          FROM ops.price_command AS command
         WHERE command.state IN ('EXECUTING', 'PLATFORM_PENDING', 'READBACK_PENDING')
           AND command.lease_expires_at IS NOT NULL
           AND command.lease_expires_at <= clock_timestamp()
           FOR UPDATE OF command SKIP LOCKED
    )
    UPDATE ops.price_command AS command
       SET state = 'UNKNOWN_REQUIRES_READBACK', lease_owner = NULL,
           lease_expires_at = NULL, next_attempt_at = NULL,
           updated_at = clock_timestamp()
      FROM expired
     WHERE command.id = expired.id;
    GET DIAGNOSTICS moved = ROW_COUNT;
    recovered := recovered + moved;

    -- A restore that lost its worker goes to a person. Re-running it
    -- automatically would repeat a write whose effect nobody has observed.
    WITH expired AS (
        SELECT command.id
          FROM ops.price_command AS command
         WHERE command.state = 'COMPENSATION_PENDING'
           AND command.lease_expires_at IS NOT NULL
           AND command.lease_expires_at <= clock_timestamp()
           FOR UPDATE OF command SKIP LOCKED
    )
    UPDATE ops.price_command AS command
       SET state = 'MANUAL_RESOLUTION', lease_owner = NULL, lease_expires_at = NULL,
           next_attempt_at = NULL, updated_at = clock_timestamp()
      FROM expired
     WHERE command.id = expired.id;
    GET DIAGNOSTICS moved = ROW_COUNT;
    recovered := recovered + moved;

    RETURN recovered;
END;
$$;

REVOKE ALL ON FUNCTION ops.recover_expired_price_command_leases() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.recover_expired_price_command_leases() TO marketops_app;

-- ---------------------------------------------------------------------------
-- Compensation leasing
-- ---------------------------------------------------------------------------

-- Claim a command that an operator authorised a restore for.
--
-- A restore is a platform write, so it needs the same fencing an apply does: a
-- lease and a fresh fence token, checked on the transition that records the
-- outcome. It also re-evaluates the write gate, because every reason a write
-- may not happen — a thrown switch, a withdrawn allowlist entry, an expired
-- authorization — applies to putting a price back just as it applies to
-- changing it.
--
-- The state does not move. COMPENSATION_PENDING is where an authorised restore
-- waits, and leasing it is a claim on the work rather than a step in the
-- lifecycle, which keeps the reviewed transition set exactly as it is.
CREATE FUNCTION ops.lease_price_compensation(
    p_command_id    uuid,
    p_lease_owner   text,
    p_lease_seconds integer)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    command_row  record;
    gate_reasons text[];
    new_fence    bigint;
BEGIN
    IF p_lease_owner IS NULL OR length(btrim(p_lease_owner)) = 0 THEN
        RAISE EXCEPTION 'a lease owner is required'
            USING ERRCODE = 'MO035';
    END IF;
    IF p_lease_seconds IS NULL OR p_lease_seconds <= 0 OR p_lease_seconds > 900 THEN
        RAISE EXCEPTION 'lease duration must be between 1 and 900 seconds'
            USING ERRCODE = 'MO035';
    END IF;

    SELECT command.id, command.state, command.lease_expires_at
      INTO command_row
      FROM ops.price_command AS command
     WHERE command.id = p_command_id
       FOR UPDATE OF command;

    IF command_row.id IS NULL THEN
        RAISE EXCEPTION 'price command % does not exist', p_command_id
            USING ERRCODE = 'MO030';
    END IF;

    IF command_row.state <> 'COMPENSATION_PENDING' THEN
        RAISE EXCEPTION 'price command % is not awaiting a restore; it is %',
            p_command_id, command_row.state
            USING ERRCODE = 'MO031';
    END IF;

    IF command_row.lease_expires_at IS NOT NULL
        AND command_row.lease_expires_at > clock_timestamp() THEN
        RAISE EXCEPTION 'price command % is already held', p_command_id
            USING ERRCODE = 'MO035';
    END IF;

    gate_reasons := ops.evaluate_price_write_gate(p_command_id);
    IF cardinality(gate_reasons) > 0 THEN
        RAISE EXCEPTION 'price write gate closed for command %: %',
            p_command_id, array_to_string(gate_reasons, ',')
            USING ERRCODE = 'MO032';
    END IF;

    UPDATE ops.price_command AS command
       SET fence_token = command.fence_token + 1,
           attempt_no = command.attempt_no + 1,
           lease_owner = p_lease_owner,
           lease_expires_at = clock_timestamp() + make_interval(secs => p_lease_seconds),
           updated_at = clock_timestamp()
     WHERE command.id = p_command_id
       AND command.state = 'COMPENSATION_PENDING'
    RETURNING command.fence_token INTO new_fence;

    IF new_fence IS NULL THEN
        RAISE EXCEPTION 'price command % changed while being leased', p_command_id
            USING ERRCODE = 'MO030';
    END IF;

    RETURN new_fence;
END;
$$;

REVOKE ALL ON FUNCTION ops.lease_price_compensation(uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_price_compensation(uuid, text, integer)
    TO marketops_app;

-- ---------------------------------------------------------------------------
-- A restore is only complete when the previous value was observed
-- ---------------------------------------------------------------------------
-- The same rule that stops an accepted write from counting as a success stops
-- an accepted restore from counting as a compensation. Platform acceptance is
-- not the price being back; only a readback that observed the prior value says
-- that, and without one the command is not compensated.
CREATE FUNCTION ops.price_command_compensation_is_observed()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    latest_match text;
BEGIN
    IF NEW.state <> 'COMPENSATED' OR OLD.state = 'COMPENSATED' THEN
        RETURN NEW;
    END IF;

    SELECT readback.match_state INTO latest_match
      FROM ops.price_command_readback AS readback
     WHERE readback.command_id = NEW.id
     ORDER BY readback.observed_at DESC, readback.id DESC
     LIMIT 1;

    IF latest_match IS DISTINCT FROM 'MATCHES_PRIOR' THEN
        RAISE EXCEPTION
            'price command % cannot be compensated: the latest readback is %',
            NEW.id, coalesce(latest_match, 'absent')
            USING ERRCODE = 'MO038',
                  HINT = 'observe the restored value before claiming compensation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER price_command_compensation_is_observed_bu
    BEFORE UPDATE ON ops.price_command
    FOR EACH ROW EXECUTE FUNCTION ops.price_command_compensation_is_observed();

-- ---------------------------------------------------------------------------
-- Recovery uses only reviewed edges
-- ---------------------------------------------------------------------------
-- The three moves above are asserted against the transition table itself, so a
-- future change that removed one of those edges would fail this migration's own
-- expectation rather than leave recovery writing an unreviewed transition.
DO $verify$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(expected.from_state || '->' || expected.to_state, ', ')
      INTO missing
      FROM (VALUES
        ('LEASED', 'PENDING'),
        ('EXECUTING', 'UNKNOWN_REQUIRES_READBACK'),
        ('PLATFORM_PENDING', 'UNKNOWN_REQUIRES_READBACK'),
        ('READBACK_PENDING', 'UNKNOWN_REQUIRES_READBACK'),
        ('COMPENSATION_PENDING', 'MANUAL_RESOLUTION')
      ) AS expected(from_state, to_state)
     WHERE NOT EXISTS (
        SELECT 1 FROM ops.price_command_transition AS allowed
         WHERE allowed.from_state = expected.from_state
           AND allowed.to_state = expected.to_state);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'lease recovery would use unreviewed transitions: %', missing
            USING ERRCODE = 'MO031';
    END IF;
END;
$verify$;
