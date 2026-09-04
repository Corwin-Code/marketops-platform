-- The advertising write gate could not return a reason without crashing.
--
-- Every refusal was appended with `reasons := reasons || 'SOME_REASON'`. In
-- PostgreSQL that expression is ambiguous: `anyarray || anyelement` and
-- `anyarray || anyarray` both match, the untyped literal resolves to the array
-- form, and the server then tries to parse SOME_REASON as an array literal and
-- raises `malformed array literal`.
--
-- So the gate raised instead of refusing, for every reason it has. Nothing
-- caught it because nothing had ever evaluated the gate against a command that
-- existed: no advertising command can be created while no advertising
-- capability is verified, which is the structural unreachability this Slice is
-- built on. The first fixture command to reach the gate found it.
--
-- A crash where a refusal belongs is the worst shape this could take. The
-- caller sees an exception rather than a list of reasons, so a worker cannot
-- tell "the gate says no, for these reasons" from "the gate is broken" — and a
-- retry loop written against the first reading would hammer a fault.
--
-- The price gate has always used array_append and was never affected. This
-- makes the advertising one match it, with the same 29 reasons in the same
-- order.
--
-- One other never-executed line goes with it. The mapping-conflict check read
-- `conflict.status`; core.mapping_conflict has no such column, it has `state`.
-- Both faults were invisible for the same reason — the gate had never been put
-- a command that existed — and both turn a refusal into an exception, which is
-- the one thing a gate must never do.
--
-- Forward-only: the function is replaced, no data is touched.

CREATE OR REPLACE FUNCTION ops.evaluate_ad_bid_write_gate(p_command_id uuid)
RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, platform, iam, pg_temp
AS $$
DECLARE
    command      ops.ad_bid_command%ROWTYPE;
    envelope     core.ad_exposure_envelope%ROWTYPE;
    reasons      text[] := '{}';
    containment  text[];
    overlapping  uuid;
    active_count integer;
    unresolved   integer;
    cumulative   numeric;
BEGIN
    SELECT * INTO command FROM ops.ad_bid_command WHERE id = p_command_id;
    IF NOT FOUND THEN
        RETURN ARRAY['COMMAND_NOT_FOUND'];
    END IF;

    IF NOT ops.ad_bid_command_authority_matches(p_command_id) THEN
        reasons := array_append(reasons, 'COMMAND_AUTHORITY_MISMATCH');
    END IF;

    -- Capability verification, per platform and per store. Ozon evidence never
    -- authorizes Wildberries, which is why the subject status is checked for
    -- this store rather than for the platform.
    IF NOT EXISTS (
        SELECT 1 FROM platform.platform_capability cap
         WHERE cap.id = command.capability_id
           AND cap.capability_code = 'ad-bid-change'
           AND cap.read_write_class = 'WRITE'
           AND cap.verification_state = 'VERIFIED'
           AND cap.status = 'ACTIVE'
           AND cap.deprecated_at IS NULL) THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_VERIFIED');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM platform.capability_subject_status s
         WHERE s.capability_id = command.capability_id
           AND s.store_id = command.store_id
           AND s.availability = 'AVAILABLE') THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_AVAILABLE_FOR_STORE');
    END IF;

    -- The feature flag, at its own scopes. Missing is off, at every scope.
    IF NOT EXISTS (
        SELECT 1 FROM platform.feature_flag f
         WHERE f.flag_code = 'ad-bid-change-write'
           AND f.scope_kind = 'CAPABILITY' AND f.state = 'ENABLED') THEN
        reasons := array_append(reasons, 'CAPABILITY_SWITCH_DISABLED');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM platform.feature_flag f
         WHERE f.flag_code = 'ad-bid-change-write'
           AND f.scope_kind = 'GLOBAL' AND f.state = 'ENABLED') THEN
        reasons := array_append(reasons, 'GLOBAL_SWITCH_DISABLED');
    END IF;
    IF EXISTS (
        SELECT 1 FROM platform.feature_flag f
         WHERE f.flag_code = 'ad-bid-change-write'
           AND f.scope_kind IN ('PLATFORM', 'MARKETPLACE_ACCOUNT', 'STORE')
           AND f.state = 'DISABLED') THEN
        reasons := array_append(reasons, 'SCOPED_SWITCH_DISABLED');
    END IF;

    -- The entity allowlist. A Pilot that enabled a capability without naming the
    -- objects it may touch would be an unbounded Pilot.
    IF NOT EXISTS (
        SELECT 1 FROM ops.pilot_allowlist_entry entry
         WHERE entry.organization_id = command.organization_id
           AND entry.action_kind = 'AD_BID_CHANGE'
           AND entry.ad_native_object_id = command.ad_native_object_id
           AND entry.status = 'ACTIVE'
           AND entry.valid_from <= statement_timestamp()
           AND (entry.valid_until IS NULL OR entry.valid_until > statement_timestamp())) THEN
        reasons := array_append(reasons, 'ENTITY_NOT_ALLOWLISTED');
    END IF;

    -- Approval and its lease. Expiry is checked here and again at every later
    -- point, because waiting never extends it.
    IF NOT EXISTS (
        SELECT 1 FROM ops.approval_decision a
         WHERE a.id = command.approval_decision_id
           AND a.recommendation_id = command.recommendation_id
           AND a.decision IN ('APPROVED', 'POLICY_AUTHORIZED')
           AND a.scope_expires_at > statement_timestamp()) THEN
        reasons := array_append(reasons, 'AUTHORIZATION_INVALID_OR_EXPIRED');
    END IF;
    IF command.approval_expires_at <= statement_timestamp() THEN
        reasons := array_append(reasons, 'APPROVAL_LEASE_EXPIRED');
    END IF;

    -- The recommendation must still be the live one for this object and action.
    IF NOT EXISTS (
        SELECT 1 FROM ops.recommendation r
         WHERE r.id = command.recommendation_id
           AND r.state IN ('APPROVED', 'POLICY_AUTHORIZED', 'COMMAND_CREATED',
                           'EXECUTION_TRACKING')
           AND r.valid_until > statement_timestamp()) THEN
        reasons := array_append(reasons, 'RECOMMENDATION_STALE');
    END IF;

    -- The affected set has not moved under the approval.
    IF NOT EXISTS (
        SELECT 1 FROM core.ad_affected_set a
         WHERE a.organization_id = command.organization_id
           AND a.ad_native_object_id = command.ad_native_object_id
           AND a.affected_set_digest = command.affected_set_digest
           AND a.resolution_state = 'COMPLETE') THEN
        reasons := array_append(reasons, 'AFFECTED_SET_DIGEST_CHANGED');
    END IF;

    -- Mapping health for every variant this object promotes. An unresolved or
    -- conflicted mapping means we do not know whose sales this bid affects.
    IF EXISTS (
        SELECT 1 FROM core.ad_affected_set a
         CROSS JOIN LATERAL unnest(a.product_variant_ids) AS variant(id)
         WHERE a.organization_id = command.organization_id
           AND a.ad_native_object_id = command.ad_native_object_id
           AND a.affected_set_digest = command.affected_set_digest
           AND NOT EXISTS (
               SELECT 1 FROM core.listing_mapping m
                WHERE m.organization_id = a.organization_id
                  AND m.product_variant_id = variant.id
                  AND m.status = 'ACTIVE'
                  AND m.effective_from <= statement_timestamp()
                  AND (m.effective_to IS NULL OR m.effective_to > statement_timestamp()))) THEN
        reasons := array_append(reasons, 'MAPPING_UNRESOLVED');
    END IF;
    IF EXISTS (
        SELECT 1 FROM core.ad_affected_set a
         CROSS JOIN LATERAL unnest(a.product_variant_ids) AS variant(id)
          JOIN core.listing_mapping m
            ON m.organization_id = a.organization_id
           AND m.product_variant_id = variant.id
          JOIN core.mapping_conflict conflict
            ON conflict.platform_listing_variant_id = m.platform_listing_variant_id
           AND conflict.state = 'OPEN'
         WHERE a.organization_id = command.organization_id
           AND a.ad_native_object_id = command.ad_native_object_id
           AND a.affected_set_digest = command.affected_set_digest) THEN
        reasons := array_append(reasons, 'MAPPING_CONFLICT_OPEN');
    END IF;

    -- A passing execution guardrail bound to the same authority snapshot.
    IF NOT EXISTS (
        SELECT 1 FROM ops.guardrail_evaluation g
         WHERE g.recommendation_id = command.recommendation_id
           AND g.organization_id = command.organization_id
           AND g.purpose = 'EXECUTION'
           AND g.outcome = 'PASS') THEN
        reasons := array_append(reasons, 'GUARDRAIL_NOT_PASSED');
    END IF;

    -- The bundle: unique, complete, active, validated and covering this scope.
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_decision_policy_bundle b
         WHERE b.id = command.bundle_id
           AND b.status = 'ACTIVE'
           AND b.validation_state = 'VALIDATED'
           AND b.effective_from <= statement_timestamp()
           AND (b.effective_to IS NULL OR b.effective_to > statement_timestamp())) THEN
        reasons := array_append(reasons, 'BUNDLE_UNRESOLVED');
    ELSIF NOT EXISTS (
        SELECT 1 FROM ops.ad_decision_policy_bundle b
          JOIN core.ad_native_object obj
            ON obj.id = command.ad_native_object_id
           AND obj.organization_id = command.organization_id
         WHERE b.id = command.bundle_id
           AND b.store_id = command.store_id
           AND b.direction = command.direction
           AND b.candidate_basis = command.candidate_basis
           AND b.native_object_kind = obj.native_object_kind
           AND b.capability_code = 'ad-bid-change') THEN
        reasons := array_append(reasons, 'BUNDLE_SCOPE_EXCEEDED');
    END IF;

    -- Direction and candidate basis have to be the ones the bundle enabled.
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_decision_policy_bundle b
         WHERE b.id = command.bundle_id AND b.direction = command.direction) THEN
        reasons := array_append(reasons, 'DIRECTION_NOT_ENABLED');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_decision_policy_bundle b
         WHERE b.id = command.bundle_id AND b.candidate_basis = command.candidate_basis) THEN
        reasons := array_append(reasons, 'CANDIDATE_BASIS_NOT_ENABLED');
    END IF;

    -- The Ordinary route exists only where a promotion record says so, and this
    -- Slice creates none. An ordinary-routed command therefore always refuses.
    IF command.materiality_route = 'ORDINARY_IMPACT' THEN
        reasons := array_append(reasons, 'ORDINARY_ROUTE_NOT_PROMOTED');
    END IF;
    IF command.materiality_route = 'MATERIALITY_UNRESOLVED' THEN
        reasons := array_append(reasons, 'MATERIALITY_UNRESOLVED');
    END IF;

    -- Containment, at every scope it can be held at.
    containment := ops.ad_active_containment(
        command.organization_id, command.ad_native_object_id, command.store_id,
        command.platform_code, 'ad-bid-change', command.affected_set_digest);
    IF 'KILL_SWITCH_ACTIVE' = ANY(containment) THEN
        reasons := array_append(reasons, 'KILL_SWITCH_ACTIVE');
    END IF;
    IF cardinality(containment) > 0
        AND NOT (containment = ARRAY['KILL_SWITCH_ACTIVE']) THEN
        reasons := array_append(reasons, 'QUARANTINE_ACTIVE');
    END IF;

    -- Reservation: this command must hold one, and nothing else may overlap it.
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_action_reservation res
         WHERE res.id = command.reservation_id
           AND res.state = 'ACTIVE'
           AND res.ad_native_object_id = command.ad_native_object_id) THEN
        reasons := array_append(reasons, 'RESERVATION_CONFLICT');
    ELSE
        SELECT o.reservation_id INTO overlapping
          FROM ops.ad_action_reservation res
         CROSS JOIN LATERAL ops.ad_overlapping_reservation(
             command.organization_id, res.product_variant_ids, command.ad_native_object_id) AS o
         WHERE res.id = command.reservation_id
         LIMIT 1;
        IF overlapping IS NOT NULL THEN
            reasons := array_append(reasons, 'RESERVATION_CONFLICT');
        END IF;
    END IF;

    -- The aggregate envelope. Every axis is checked independently; there is no
    -- point in this function where one axis's slack is added to another's.
    SELECT * INTO envelope
      FROM core.ad_exposure_envelope e
     WHERE e.organization_id = command.organization_id
       AND e.status IN ('ACTIVE', 'RETIRED')
       AND e.effective_from <= statement_timestamp()
       AND (e.effective_to IS NULL OR e.effective_to > statement_timestamp())
     ORDER BY CASE e.scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
              e.effective_from DESC
     LIMIT 1;
    IF NOT FOUND THEN
        reasons := array_append(reasons, 'AGGREGATE_ENVELOPE_UNRESOLVED');
    ELSE
        SELECT count(*) INTO active_count
          FROM ops.ad_action_reservation res
         WHERE res.organization_id = command.organization_id AND res.state = 'ACTIVE';
        -- Ordinary work may not consume the reserved recovery headroom. A
        -- compensation may, which is what the headroom is for.
        IF command.direction <> 'EXACT_PRIOR_BID_COMPENSATION'
            AND active_count > envelope.max_active_interventions
                               - envelope.reserved_recovery_headroom_count THEN
            reasons := array_append(reasons, 'AGGREGATE_ENVELOPE_BLOCKED');
        ELSIF active_count > envelope.max_active_interventions THEN
            reasons := array_append(reasons, 'AGGREGATE_ENVELOPE_BLOCKED');
        END IF;

        SELECT count(*) INTO unresolved
          FROM ops.ad_bid_command other
         WHERE other.organization_id = command.organization_id
           AND other.state IN ('UNKNOWN_REQUIRES_READBACK', 'READBACK_MISMATCH',
                               'LATER_CHANGE_OR_MISMATCH_INVESTIGATION', 'MANUAL_RESOLUTION');
        IF unresolved > envelope.max_unresolved_transmitted_writes THEN
            reasons := array_append(reasons, 'AGGREGATE_ENVELOPE_BLOCKED');
        END IF;

        SELECT coalesce(sum(abs(other.target_bid_amount - other.prior_bid_amount)), 0)
          INTO cumulative
          FROM ops.ad_bid_command other
         WHERE other.organization_id = command.organization_id
           AND other.created_at > statement_timestamp()
                                  - make_interval(hours => envelope.cumulative_window_hours);
        IF cumulative + abs(command.target_bid_amount - command.prior_bid_amount)
                > envelope.max_cumulative_bid_change_amount THEN
            reasons := array_append(reasons, 'AGGREGATE_ENVELOPE_BLOCKED');
        END IF;
    END IF;

    RETURN reasons;
END;
$$;
REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_write_gate(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.evaluate_ad_bid_write_gate(uuid) TO marketops_app;
