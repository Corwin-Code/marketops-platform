-- Turning an approval into a command.
--
-- This is the only way an ops.ad_bid_command row comes into existence, and it is
-- a SECURITY DEFINER function because the application role has no INSERT on that
-- table. Everything a command needs to be true is checked here, once, in one
-- transaction, against the row versions that existed at that instant.
--
-- The function is idempotent on the recommendation. One approval produces one
-- command however many times somebody clicks, because the idempotency key is
-- derived from the recommendation rather than generated, and a second call
-- returns the command the first one made.
--
-- Two things are deliberately checked here rather than left to the gate. The
-- actor must hold AD_BID_CHANGE_APPROVE at a scope that covers this store,
-- because a command created by somebody who could not approve it would be a
-- command whose approval nobody gave. And the reservation must already exist and
-- be held by this object, because a command without a reservation is a command
-- that could execute beside another one on the same variants.
--
-- The write gate still runs at lease and again at transmission. This function
-- creating a row does not mean the row may ever be sent.

CREATE FUNCTION ops.create_ad_bid_command(
    p_recommendation_id uuid,
    p_expected_version  bigint,
    p_actor_id          uuid,
    p_reservation_id    uuid,
    p_bundle_id         uuid,
    p_approval_expires_at timestamptz,
    p_correlation_id    text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, platform, iam, pg_temp
AS $$
DECLARE
    recommendation ops.recommendation%ROWTYPE;
    candidate      ops.ad_bid_candidate%ROWTYPE;
    object_row     core.ad_native_object%ROWTYPE;
    configuration  core.ad_object_configuration_observation%ROWTYPE;
    capability_id  uuid;
    approval_id    uuid;
    existing_id    uuid;
    command_id     uuid;
    materiality    text;
    envelope       core.ad_materiality_policy%ROWTYPE;
    change_amount  numeric(18, 4);
BEGIN
    IF p_correlation_id IS NULL
        OR length(btrim(p_correlation_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'a correlation identifier is required' USING ERRCODE = 'MO092';
    END IF;

    SELECT * INTO recommendation FROM ops.recommendation
     WHERE id = p_recommendation_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'recommendation does not exist' USING ERRCODE = 'MO090';
    END IF;
    IF recommendation.version <> p_expected_version THEN
        RAISE EXCEPTION 'the recommendation changed since it was read'
            USING ERRCODE = 'MO090';
    END IF;

    -- Idempotent on the recommendation. One approval, one command, however many
    -- times the button is pressed.
    SELECT id INTO existing_id FROM ops.ad_bid_command
     WHERE recommendation_id = recommendation.id;
    IF existing_id IS NOT NULL THEN
        RETURN existing_id;
    END IF;

    IF recommendation.action_kind <> 'AD_BID_CHANGE'
        OR recommendation.subject_kind <> 'AD_NATIVE_OBJECT'
        OR recommendation.state NOT IN ('APPROVED', 'POLICY_AUTHORIZED')
        OR recommendation.valid_until <= statement_timestamp()
        OR NOT ops.ad_bid_parameter_contract_is_valid(recommendation.proposed_parameters) THEN
        RAISE EXCEPTION 'the recommendation is not an approved, current bid change'
            USING ERRCODE = 'MO092';
    END IF;

    -- The person creating the command must be able to approve one. A command
    -- created by somebody who could not approve it would carry an approval
    -- nobody with the authority gave.
    IF NOT EXISTS (
        SELECT 1
          FROM iam.user_role_assignment role
          JOIN iam.business_role_action_scope matrix ON matrix.role_code = role.role_code
          JOIN iam.user_scope_grant grant_row
            ON grant_row.user_id = role.user_id
           AND grant_row.action_code = 'AD_BID_CHANGE_APPROVE'
          JOIN core.store store ON store.id = recommendation.store_id
          JOIN core.marketplace_account account ON account.id = store.marketplace_account_id
         WHERE role.user_id = p_actor_id
           AND role.status = 'ACTIVE'
           AND role.effective_from <= statement_timestamp()
           AND (role.effective_to IS NULL OR role.effective_to > statement_timestamp())
           AND matrix.action_code = 'AD_BID_CHANGE_APPROVE'
           AND grant_row.status = 'ACTIVE'
           AND grant_row.effective_from <= statement_timestamp()
           AND (grant_row.effective_to IS NULL OR grant_row.effective_to > statement_timestamp())
           AND (grant_row.organization_ref_id = recommendation.organization_id
                OR grant_row.store_ref_id = store.id
                OR grant_row.marketplace_account_ref_id = account.id
                OR grant_row.legal_entity_ref_id = account.legal_entity_id)) THEN
        RAISE EXCEPTION 'the actor cannot approve an advertising bid change here'
            USING ERRCODE = 'MO092';
    END IF;

    SELECT * INTO candidate FROM ops.ad_bid_candidate
     WHERE id = (recommendation.proposed_parameters ->> 'candidateId')::uuid
       AND organization_id = recommendation.organization_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'the approved candidate does not exist' USING ERRCODE = 'MO092';
    END IF;
    IF candidate.direction <> (recommendation.proposed_parameters ->> 'direction')
        OR candidate.provider_normalized_amount
            <> (recommendation.proposed_parameters ->> 'targetBid')::numeric THEN
        RAISE EXCEPTION 'the approval does not describe the candidate it names'
            USING ERRCODE = 'MO092';
    END IF;

    SELECT * INTO object_row FROM core.ad_native_object
     WHERE id = recommendation.subject_id AND organization_id = recommendation.organization_id;
    IF NOT FOUND OR object_row.control_granularity_state <> 'PROVEN_INDEPENDENT'
        OR object_row.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'the advertising object is not independently controllable'
            USING ERRCODE = 'MO092';
    END IF;

    SELECT * INTO configuration FROM core.ad_object_configuration_observation c
     WHERE c.ad_native_object_id = object_row.id
       AND c.organization_id = object_row.organization_id
       AND NOT EXISTS (SELECT 1 FROM core.ad_object_configuration_observation later
                        WHERE later.supersedes_observation_id = c.id)
     ORDER BY c.observed_at DESC, c.id DESC LIMIT 1;
    IF NOT FOUND OR configuration.observed_bid_amount IS NULL THEN
        RAISE EXCEPTION 'the current bid is not observed, so no change can be exact'
            USING ERRCODE = 'MO092';
    END IF;
    IF configuration.observed_bid_amount <> candidate.current_bid_amount THEN
        RAISE EXCEPTION 'the bid moved since the candidate was generated'
            USING ERRCODE = 'MO090';
    END IF;

    -- The reservation must already be held by this object. A command without one
    -- could execute beside another action on the same variants.
    IF NOT EXISTS (
        SELECT 1 FROM ops.ad_action_reservation r
         WHERE r.id = p_reservation_id
           AND r.organization_id = recommendation.organization_id
           AND r.ad_native_object_id = object_row.id
           AND r.state = 'ACTIVE'
           AND r.affected_set_digest = candidate.affected_set_digest) THEN
        RAISE EXCEPTION 'no active reservation is held for this affected set'
            USING ERRCODE = 'MO097';
    END IF;

    SELECT a.id INTO approval_id FROM ops.approval_decision a
     WHERE a.recommendation_id = recommendation.id
       AND a.decision IN ('APPROVED', 'POLICY_AUTHORIZED')
       AND a.scope_expires_at > statement_timestamp()
     ORDER BY a.decided_at DESC LIMIT 1;
    IF approval_id IS NULL THEN
        RAISE EXCEPTION 'no current approval covers this recommendation'
            USING ERRCODE = 'MO092';
    END IF;

    SELECT c.id INTO capability_id FROM platform.platform_capability c
     WHERE c.platform_code = object_row.platform_code
       AND c.capability_code = 'ad-bid-change'
       AND c.read_write_class = 'WRITE'
       AND c.status = 'ACTIVE'
       AND c.verification_state = 'VERIFIED'
       AND c.deprecated_at IS NULL;
    IF capability_id IS NULL THEN
        -- This is the structural refusal. Until an advertising capability is
        -- independently verified for this platform, no command can exist at all,
        -- which is a stronger statement than a gate that would have refused it.
        RAISE EXCEPTION 'no verified advertising write capability exists for this platform'
            USING ERRCODE = 'MO092';
    END IF;

    -- Materiality. The initial ordinary envelope is zero, so every nonzero
    -- change is Material; the policy is read rather than assumed so widening it
    -- later stays a reviewed data change.
    change_amount := abs(candidate.provider_normalized_amount - candidate.current_bid_amount);
    SELECT * INTO envelope FROM core.ad_materiality_policy m
     WHERE m.organization_id = recommendation.organization_id
       AND m.status IN ('ACTIVE', 'RETIRED')
       AND m.effective_from <= statement_timestamp()
       AND (m.effective_to IS NULL OR m.effective_to > statement_timestamp())
     ORDER BY CASE m.scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
              m.effective_from DESC
     LIMIT 1;
    IF NOT FOUND THEN
        materiality := 'MATERIALITY_UNRESOLVED';
    ELSIF change_amount > envelope.ordinary_nonzero_envelope_amount THEN
        materiality := 'MATERIAL_IMPACT';
    ELSE
        materiality := 'ORDINARY_IMPACT';
    END IF;
    IF materiality = 'MATERIALITY_UNRESOLVED' THEN
        RAISE EXCEPTION 'materiality cannot be resolved, so no command may be created'
            USING ERRCODE = 'MO092';
    END IF;

    command_id := gen_random_uuid();
    INSERT INTO ops.ad_bid_command (
        id, organization_id, recommendation_id, approval_decision_id, store_id,
        ad_native_object_id, platform_code, capability_id, semantic_profile_id,
        candidate_id, bundle_id, reservation_id, idempotency_key, currency_code,
        bid_unit_code, direction, candidate_basis, materiality_route,
        prior_bid_amount, target_bid_amount, prior_configuration_id, affected_set_digest,
        lineage_generation, entity_version_digest, authority_snapshot,
        approval_expires_at, state, retry_budget_remaining, next_attempt_at,
        created_at, updated_at)
    VALUES (
        command_id, recommendation.organization_id, recommendation.id, approval_id,
        recommendation.store_id, object_row.id, object_row.platform_code, capability_id,
        object_row.semantic_profile_id, candidate.id, p_bundle_id, p_reservation_id,
        'abc-' || recommendation.id::text, candidate.currency_code, candidate.bid_unit_code,
        candidate.direction, candidate.candidate_basis, materiality,
        candidate.current_bid_amount, candidate.provider_normalized_amount,
        configuration.id, candidate.affected_set_digest, object_row.lineage_generation,
        recommendation.entity_version_digest, '{}'::jsonb,
        p_approval_expires_at, 'PENDING', 3, clock_timestamp(),
        clock_timestamp(), clock_timestamp());

    -- The authority snapshot is stamped by the bind trigger. Re-deriving it here
    -- and comparing is what proves nothing moved between the checks above and
    -- the row that was written.
    IF NOT ops.ad_bid_command_authority_matches(command_id) THEN
        RAISE EXCEPTION 'the command does not describe the decision it was approved for'
            USING ERRCODE = 'MO092';
    END IF;

    INSERT INTO ops.metadata_audit_event (
        id, actor_type, actor_id, source_domain, action, entity_type, entity_id,
        change_summary, reason, correlation_id)
    VALUES (gen_random_uuid(), 'OPERATOR', p_actor_id::text, 'marketplaceintegration',
        'COMMAND_TRANSITION', 'ad-bid-command', command_id,
        jsonb_build_object('direction', jsonb_build_object('previous', NULL,
            'current', candidate.direction)),
        'advertising bid command created from an approved candidate', p_correlation_id);

    RETURN command_id;
END;
$$;
REVOKE ALL ON FUNCTION ops.create_ad_bid_command(uuid, bigint, uuid, uuid, uuid, timestamptz, text)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.create_ad_bid_command(uuid, bigint, uuid, uuid, uuid, timestamptz, text)
    TO marketops_app;
