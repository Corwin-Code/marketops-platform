-- Preserve historical launches without inventing their transaction identity.
ALTER TABLE ops.lc_launch ADD COLUMN created_transaction_id bigint;
ALTER TABLE ops.lc_launch ALTER COLUMN created_transaction_id SET DEFAULT txid_current();

-- Retain the original creation checks and scope access even on idempotent reads.
CREATE OR REPLACE FUNCTION ops.create_lc_description_command(
    p_action_id uuid, p_actor_id uuid, p_expected_version bigint, p_correlation_id text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, platform, iam, pg_temp
AS $$
DECLARE
    action      ops.lc_action%ROWTYPE;
    binding     ops.lc_action_binding%ROWTYPE;
    launch      ops.lc_launch%ROWTYPE;
    approval    ops.approval_decision%ROWTYPE;
    listing     core.platform_listing%ROWTYPE;
    observation core.lc_description_observation%ROWTYPE;
    capability  uuid;
    existing    uuid;
    command_id  uuid;
    rule        text;
    bounds      jsonb;
    gaps        text[];
BEGIN
    IF p_correlation_id IS NULL OR length(btrim(p_correlation_id)) NOT BETWEEN 1 AND 128 THEN
        RAISE EXCEPTION 'a correlation identifier is required' USING ERRCODE = 'MO092';
    END IF;
    SELECT * INTO action FROM ops.lc_action WHERE id = p_action_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'action does not exist' USING ERRCODE = 'MO090'; END IF;
    IF action.version <> p_expected_version THEN
        RAISE EXCEPTION 'the action changed since it was read' USING ERRCODE = 'MO090';
    END IF;
    IF NOT ops.lc_actor_holds_action(p_actor_id, action.organization_id, action.store_id, 'LISTING_ACTION_LAUNCH') THEN
        RAISE EXCEPTION 'the actor cannot access the description execution here' USING ERRCODE = 'MO092';
    END IF;
    SELECT id INTO existing FROM ops.lc_description_command WHERE action_id = p_action_id;
    IF existing IS NOT NULL THEN RETURN existing; END IF;
    SELECT * INTO launch FROM ops.lc_launch WHERE action_id=p_action_id;
    IF NOT FOUND OR launch.created_transaction_id IS DISTINCT FROM txid_current()
        OR launch.launched_by_user_id<>p_actor_id THEN
        RAISE EXCEPTION 'a new description command is created only in its authenticated launch transaction'
            USING ERRCODE='MO092';
    END IF;

    IF action.action_kind <> 'LISTING_DESCRIPTION_CHANGE' OR action.execution_path <> 'API'
        OR action.state <> 'LAUNCHED' THEN
        RAISE EXCEPTION 'only a launched description change on the API path becomes a command'
            USING ERRCODE = 'MO092';
    END IF;
    IF NOT ops.lc_actor_holds_action(p_actor_id, action.organization_id, action.store_id, 'LISTING_ACTION_LAUNCH') THEN
        RAISE EXCEPTION 'the actor cannot create a description command here' USING ERRCODE = 'MO092';
    END IF;
    IF action.kiz_marked_declared IS NULL THEN
        RAISE EXCEPTION 'the marking declaration is stated before a description is written'
            USING ERRCODE = 'MO092';
    END IF;
    gaps := ops.lc_binding_gaps(p_action_id);
    IF cardinality(gaps) > 0 THEN
        RAISE EXCEPTION 'the binding no longer applies: %', array_to_string(gaps, ',') USING ERRCODE = 'MO092';
    END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id = p_action_id;
    SELECT * INTO launch FROM ops.lc_launch WHERE action_id = p_action_id;
    SELECT * INTO approval FROM ops.approval_decision WHERE id = binding.approval_decision_id;
    SELECT * INTO listing FROM core.platform_listing WHERE id = action.platform_listing_id;
    SELECT * INTO observation FROM core.lc_description_observation
     WHERE id = action.current_description_observation_id;
    IF observation.text_digest IS DISTINCT FROM binding.current_text_digest THEN
        RAISE EXCEPTION 'the captured prior text is not the bound current text' USING ERRCODE = 'MO092';
    END IF;

    -- The capability must be described. Being verified is the gate's question,
    -- asked at lease and again before any socket; here a command that could
    -- never name a capability is refused rather than created.
    SELECT cap.id INTO capability FROM platform.platform_capability cap
     WHERE cap.platform_code = listing.platform_code AND cap.capability_code = 'listing-description-change';
    IF capability IS NULL THEN
        RAISE EXCEPTION 'no description write capability is described for this platform'
            USING ERRCODE = 'MO092';
    END IF;

    SELECT v.value_text INTO rule FROM core.lc_calibration_value v
     WHERE v.package_id = binding.calibration_package_id AND v.category_code = 'REPRESENTATION_EQUIVALENCE_RULE';
    SELECT v.value_json INTO bounds FROM core.lc_calibration_value v
     WHERE v.package_id = binding.calibration_package_id AND v.category_code = 'DESCRIPTION_LENGTH_RULE';
    IF rule IS NULL OR bounds IS NULL OR (bounds ->> 'min') IS NULL OR (bounds ->> 'max') IS NULL THEN
        RAISE EXCEPTION 'the calibration package does not state the equivalence rule and length bound'
            USING ERRCODE = 'MO092';
    END IF;

    command_id := gen_random_uuid();
    INSERT INTO ops.lc_description_command (
        id, organization_id, recommendation_id, approval_decision_id, action_id, launch_id, binding_id,
        store_id, platform_listing_id, platform_code, capability_id, idempotency_key,
        prior_text, prior_text_digest, target_text, target_text_digest, kiz_marked_declared,
        equivalence_rule, length_bound_min, length_bound_max, affected_set_digest,
        approval_expires_at, state, retry_budget_remaining, created_at, updated_at)
    VALUES (command_id, action.organization_id, action.recommendation_id, approval.id, action.id,
        launch.id, binding.id, action.store_id, action.platform_listing_id, listing.platform_code,
        capability, 'lcd-' || replace(action.id::text, '-', ''),
        nullif(observation.description_text, ''), observation.text_digest,
        action.target_text, action.target_text_digest, action.kiz_marked_declared,
        rule, (bounds ->> 'min')::integer, (bounds ->> 'max')::integer, action.affected_set_digest,
        binding.expires_at, 'PENDING', 3, clock_timestamp(), clock_timestamp());
    RETURN command_id;
END;
$$;

-- Retain every original launch gate/axis check; enqueue before committing.
CREATE OR REPLACE FUNCTION ops.acquire_lc_launch_allowance(
    p_launch_id uuid, p_action uuid, p_actor uuid, p_proof text, p_requested jsonb)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, iam, pg_temp
AS $$
DECLARE
    action      ops.lc_action%ROWTYPE;
    binding     ops.lc_action_binding%ROWTYPE;
    plan_row    ops.lc_evaluation_plan%ROWTYPE;
    grant_row   iam.ad_invocation_grant%ROWTYPE;
    gaps        text[];
    allowance   ops.lc_exposure_allowance%ROWTYPE;
    occupied    numeric(18, 4);
    requested   numeric(18, 4);
    short       text[] := '{}';
    axes_seen   integer := 0;
    occupations uuid[] := '{}';
    occupation_id uuid;
    command_id uuid;
    now_at      timestamptz := clock_timestamp();
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = p_action FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'action does not exist' USING ERRCODE = 'MO090'; END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id = p_action;
    IF NOT FOUND THEN RAISE EXCEPTION 'the action has no binding' USING ERRCODE = 'MO092'; END IF;
    grant_row := ops.consume_ad_control_invocation(p_proof, 'LISTING_ACTION_LAUNCH',
        action.recommendation_id, binding.approval_decision_id);
    IF grant_row.actor_user_id <> p_actor OR grant_row.organization_id <> action.organization_id THEN
        RAISE EXCEPTION 'the invocation proof belongs to another person' USING ERRCODE = 'MO092';
    END IF;
    IF NOT ops.lc_actor_holds_action(p_actor, action.organization_id, action.store_id, 'LISTING_ACTION_LAUNCH') THEN
        RAISE EXCEPTION 'the actor cannot launch a listing action here' USING ERRCODE = 'MO092';
    END IF;
    IF action.state NOT IN ('APPROVED', 'APPROVED_NOT_LAUNCHABLE') THEN
        RAISE EXCEPTION 'only an approved action is launched' USING ERRCODE = 'MO091';
    END IF;
    gaps := ops.lc_binding_gaps(p_action);
    IF cardinality(gaps) > 0 THEN
        RAISE EXCEPTION 'the binding no longer applies: %', array_to_string(gaps, ',') USING ERRCODE = 'MO092';
    END IF;
    SELECT * INTO plan_row FROM ops.lc_evaluation_plan WHERE action_id = p_action;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'an evaluation plan is frozen before launch' USING ERRCODE = 'MO092';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mart.lc_listing_health h
                    WHERE h.platform_listing_id = action.platform_listing_id
                      AND h.health_version = (SELECT max(latest.health_version) FROM mart.lc_listing_health latest
                                              WHERE latest.platform_listing_id = action.platform_listing_id)
                      AND h.necessary_state = 'PASS') THEN
        RAISE EXCEPTION 'Listing Health necessary conditions are not passed' USING ERRCODE = 'MO092';
    END IF;
    IF ops.lc_scope_contained(action.organization_id, action.platform_listing_id) THEN
        RAISE EXCEPTION 'the scope is contained' USING ERRCODE = 'MO092';
    END IF;

    -- Every axis, independently, under one advisory lock per allowance row.
    FOR allowance IN SELECT * FROM ops.lc_allowances_for(action.organization_id, action.platform_listing_id, now_at) LOOP
        axes_seen := axes_seen + 1;
        PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_allowance'), hashtext(allowance.id::text));
        requested := CASE allowance.axis_code
            WHEN 'CONCURRENT_LISTINGS' THEN 1
            WHEN 'AFFECTED_VARIANTS' THEN (SELECT cardinality(s.platform_listing_variant_ids)
                                             FROM core.lc_affected_set s WHERE s.id = action.affected_set_id)
            ELSE nullif(p_requested ->> allowance.axis_code, '')::numeric END;
        IF requested IS NULL THEN
            short := array_append(short, allowance.axis_code || ':REQUEST_UNSTATED');
            CONTINUE;
        END IF;
        SELECT coalesce(sum(o.occupied_value), 0) INTO occupied
          FROM ops.lc_exposure_occupation o
         WHERE o.allowance_id = allowance.id AND o.state <> 'RELEASED';
        IF occupied + requested > allowance.limit_value - allowance.reserve_value THEN
            short := array_append(short, allowance.axis_code);
        END IF;
    END LOOP;
    IF axes_seen = 0 THEN
        short := array_append(short, 'ALLOWANCE_UNRESOLVED');
    END IF;
    IF cardinality(short) > 0 THEN
        UPDATE ops.lc_action SET state = 'APPROVED_NOT_LAUNCHABLE', updated_at = now_at, version = version + 1
         WHERE id = p_action AND state = 'APPROVED';
        RETURN jsonb_build_object('launched', false, 'insufficientAxes', to_jsonb(short));
    END IF;

    FOR allowance IN SELECT * FROM ops.lc_allowances_for(action.organization_id, action.platform_listing_id, now_at) LOOP
        requested := CASE allowance.axis_code
            WHEN 'CONCURRENT_LISTINGS' THEN 1
            WHEN 'AFFECTED_VARIANTS' THEN (SELECT cardinality(s.platform_listing_variant_ids)
                                             FROM core.lc_affected_set s WHERE s.id = action.affected_set_id)
            ELSE (p_requested ->> allowance.axis_code)::numeric END;
        occupation_id := gen_random_uuid();
        INSERT INTO ops.lc_exposure_occupation (id, organization_id, allowance_id, action_id, axis_code,
            requested_value, occupied_value, state, acquired_at)
        VALUES (occupation_id, action.organization_id, allowance.id, p_action, allowance.axis_code,
            requested, requested, 'ACQUIRED', now_at);
        occupations := array_append(occupations, occupation_id);
    END LOOP;

    INSERT INTO ops.lc_launch (id, organization_id, action_id, binding_id, plan_id, launched_by_user_id,
        launched_at, proof_hash)
    VALUES (p_launch_id, action.organization_id, p_action, binding.id, plan_row.id, p_actor, now_at,
        grant_row.proof_hash);
    UPDATE ops.lc_action SET state = 'LAUNCHED', updated_at = now_at, version = version + 1
     WHERE id = p_action;
    IF action.execution_path='API' THEN
        command_id:=ops.create_lc_description_command(p_action,p_actor,action.version+1,'lc-launch:'||p_launch_id::text);
    END IF;
    RETURN jsonb_build_object('launched', true, 'launchId', p_launch_id,
        'occupationIds', to_jsonb(occupations),'commandId',command_id);
END;
$$;

CREATE FUNCTION ops.lc_launch_and_command_are_atomic()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp
AS $$
DECLARE action ops.lc_action%ROWTYPE; commands integer;
BEGIN
    SELECT * INTO STRICT action FROM ops.lc_action WHERE id=NEW.action_id;
    SELECT count(*) INTO commands FROM ops.lc_description_command c
        WHERE c.action_id=action.id AND c.launch_id=NEW.id AND c.binding_id=NEW.binding_id
          AND c.organization_id=NEW.organization_id;
    IF NEW.created_transaction_id IS DISTINCT FROM txid_current()
        OR (action.execution_path='API' AND commands<>1)
        OR (action.execution_path='MANUAL' AND EXISTS(SELECT 1 FROM ops.lc_description_command c WHERE c.action_id=action.id)) THEN
        RAISE EXCEPTION 'launch and its exact API command must commit together; manual launches create none'
            USING ERRCODE='MO092';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_launch_and_command_are_atomic() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER lc_launch_and_command_are_atomic AFTER INSERT ON ops.lc_launch
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ops.lc_launch_and_command_are_atomic();
