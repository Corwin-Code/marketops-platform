-- Two workers must not reserve overlapping affected sets.
--
-- ops.ad_overlapping_reservation answers whether an overlap exists, and until
-- now the application asked it and then inserted. Between those two statements
-- another transaction can insert a reservation the first one never saw, and
-- both proceed. The window is small and the consequence is not: two
-- interventions changing advertising for the same product variants at the same
-- time, whose separate outcomes can no longer be attributed to either.
--
-- So taking a reservation becomes one statement that serializes on the
-- organization. The advisory lock is transaction-scoped and keyed by
-- organization, so two organizations never wait for each other and two workers
-- in one organization take reservations in a defined order.
--
-- Insert and update are then revoked from the application role, because a guard
-- that can be bypassed by the code it guards is a comment. Releasing is a
-- separate function for the same reason: the four release conditions are a
-- row-level check, and a service that could UPDATE the row directly could set
-- the flags and the state in one statement, which is the check answering to the
-- thing it checks.
--
-- Forward-only: no reservation row is altered.

CREATE FUNCTION ops.take_ad_action_reservation(
    p_id                        uuid,
    p_organization_id           uuid,
    p_ad_native_object_id       uuid,
    p_store_id                  uuid,
    p_affected_set_id           uuid,
    p_affected_set_digest       text,
    p_product_variant_ids       uuid[],
    p_intervention_kind         text,
    p_intervention_reference_id uuid,
    p_direction                 text,
    p_lane                      text,
    p_correlation_id            text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE
    blocking_id   uuid;
    blocking_lane text;
    existing_id   uuid;
BEGIN
    IF p_correlation_id IS NULL
        OR length(btrim(p_correlation_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'a correlation identifier is required' USING ERRCODE = 'MO097';
    END IF;

    -- Everything below happens in a defined order per organization.
    PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),
                                  hashtext(p_organization_id::text));

    -- Idempotent on the intervention. One decision holds one reservation, so a
    -- retried worker finds its own rather than taking a second.
    SELECT r.id INTO existing_id
      FROM ops.ad_action_reservation r
     WHERE r.organization_id = p_organization_id
       AND r.intervention_kind = p_intervention_kind
       AND r.intervention_reference_id = p_intervention_reference_id
       AND r.state = 'ACTIVE';
    IF existing_id IS NOT NULL THEN
        RETURN existing_id;
    END IF;

    SELECT o.reservation_id, o.lane INTO blocking_id, blocking_lane
      FROM ops.ad_overlapping_reservation(p_organization_id, p_product_variant_ids,
                                          p_ad_native_object_id) o
     LIMIT 1;
    IF blocking_id IS NOT NULL THEN
        RAISE EXCEPTION
            'an active % reservation already holds one of these product variants',
            blocking_lane USING ERRCODE = 'MO097';
    END IF;

    -- An object also holds at most one live reservation of its own, whatever the
    -- affected set says. Two decisions about one object are two decisions about
    -- one bid.
    IF EXISTS (SELECT 1 FROM ops.ad_action_reservation r
                WHERE r.organization_id = p_organization_id
                  AND r.ad_native_object_id = p_ad_native_object_id
                  AND r.state = 'ACTIVE') THEN
        RAISE EXCEPTION 'this advertising object already holds an active reservation'
            USING ERRCODE = 'MO097';
    END IF;

    INSERT INTO ops.ad_action_reservation (
        id, organization_id, ad_native_object_id, store_id, affected_set_id,
        affected_set_digest, product_variant_ids, intervention_kind,
        intervention_reference_id, direction, lane, state, reserved_at, correlation_id)
    VALUES (p_id, p_organization_id, p_ad_native_object_id, p_store_id, p_affected_set_id,
        p_affected_set_digest, p_product_variant_ids, p_intervention_kind,
        p_intervention_reference_id, p_direction, p_lane, 'ACTIVE',
        clock_timestamp(), p_correlation_id);
    RETURN p_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.take_ad_action_reservation(uuid, uuid, uuid, uuid, uuid, text,
    uuid[], text, uuid, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.take_ad_action_reservation(uuid, uuid, uuid, uuid, uuid, text,
    uuid[], text, uuid, text, text, text) TO marketops_app;

-- Recording one release condition. Each is observed separately and none of them
-- may be asserted by the same statement that releases, so that the row-level
-- check has something independent to check.
CREATE FUNCTION ops.observe_ad_reservation_condition(
    p_reservation_id uuid,
    p_condition      text,
    p_holds          boolean)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    IF p_condition NOT IN ('CONFIGURATION_RESOLVED', 'UNKNOWN_OR_MISMATCH_OPEN',
                           'EARLY_OBSERVATION_COMPLETE', 'REGRESSION_OPEN') THEN
        RAISE EXCEPTION 'unknown release condition %', p_condition USING ERRCODE = 'MO097';
    END IF;
    UPDATE ops.ad_action_reservation
       SET configuration_resolved = CASE WHEN p_condition = 'CONFIGURATION_RESOLVED'
                                         THEN p_holds ELSE configuration_resolved END,
           unknown_or_mismatch_open = CASE WHEN p_condition = 'UNKNOWN_OR_MISMATCH_OPEN'
                                           THEN p_holds ELSE unknown_or_mismatch_open END,
           early_observation_complete = CASE WHEN p_condition = 'EARLY_OBSERVATION_COMPLETE'
                                             THEN p_holds ELSE early_observation_complete END,
           regression_open = CASE WHEN p_condition = 'REGRESSION_OPEN'
                                  THEN p_holds ELSE regression_open END,
           version = version + 1
     WHERE id = p_reservation_id AND state = 'ACTIVE';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'no active reservation to observe' USING ERRCODE = 'MO097';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION ops.observe_ad_reservation_condition(uuid, text, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.observe_ad_reservation_condition(uuid, text, boolean)
    TO marketops_app;

-- Releasing. The four conditions are read from the row rather than passed in, so
-- a caller cannot release by asserting what it has not observed. The table's own
-- check refuses the update if any of them fails, which is the second reader of
-- the same fact and the one that cannot be argued with.
CREATE FUNCTION ops.release_ad_action_reservation(
    p_reservation_id uuid,
    p_reason         text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, pg_temp
AS $$
DECLARE
    row_state ops.ad_action_reservation%ROWTYPE;
BEGIN
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 1 AND 256 THEN
        RAISE EXCEPTION 'a release reason is required' USING ERRCODE = 'MO097';
    END IF;
    SELECT * INTO row_state FROM ops.ad_action_reservation
     WHERE id = p_reservation_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'no such reservation' USING ERRCODE = 'MO097';
    END IF;
    IF row_state.state = 'RELEASED' THEN
        RETURN false;
    END IF;
    IF NOT (row_state.configuration_resolved
            AND NOT row_state.unknown_or_mismatch_open
            AND row_state.early_observation_complete
            AND NOT row_state.regression_open) THEN
        RETURN false;
    END IF;
    UPDATE ops.ad_action_reservation
       SET state = 'RELEASED', released_at = clock_timestamp(), release_reason = p_reason,
           version = version + 1
     WHERE id = p_reservation_id;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION ops.release_ad_action_reservation(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.release_ad_action_reservation(uuid, text) TO marketops_app;

-- The functions are now the only route.
REVOKE INSERT, UPDATE ON ops.ad_action_reservation FROM marketops_app;
