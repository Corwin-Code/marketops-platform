-- Controls apply to their actual native/account/store/capability consumers.
-- Another capability cannot lend enablement, and an unrelated scoped kill
-- switch cannot freeze this command. Global controls remain global.
CREATE OR REPLACE FUNCTION ops.evaluate_lc_description_write_gate(p_command_id uuid)
RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, platform, iam, pg_temp
AS $$
DECLARE
    command ops.lc_description_command%ROWTYPE;
    reasons text[] := '{}';
    gaps    text[];
    latest  text;
BEGIN
    SELECT * INTO command FROM ops.lc_description_command WHERE id = p_command_id;
    IF NOT FOUND THEN RETURN ARRAY['COMMAND_NOT_FOUND']; END IF;

    IF NOT EXISTS (SELECT 1 FROM platform.platform_capability cap
                    WHERE cap.id = command.capability_id
                      AND cap.capability_code = 'listing-description-change'
                      AND cap.read_write_class = 'WRITE'
                      AND cap.verification_state = 'VERIFIED'
                      AND cap.status = 'ACTIVE' AND cap.deprecated_at IS NULL) THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_VERIFIED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.capability_subject_status s
                    WHERE s.capability_id = command.capability_id
                      AND s.store_id = command.store_id AND s.availability = 'AVAILABLE') THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_AVAILABLE_FOR_STORE');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.feature_flag f
                    WHERE f.flag_code = 'listing-description-write'
                      AND f.scope_kind = 'CAPABILITY' AND f.capability_id = command.capability_id
                      AND f.flag_kind = 'WRITE_CAPABILITY' AND f.status = 'ACTIVE' AND f.state = 'ENABLED') THEN
        reasons := array_append(reasons, 'CAPABILITY_SWITCH_DISABLED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.feature_flag f
                    WHERE f.flag_code = 'listing-description-write'
                      AND f.scope_kind = 'GLOBAL' AND f.flag_kind = 'WRITE_CAPABILITY'
                      AND f.status = 'ACTIVE' AND f.state = 'ENABLED') THEN
        reasons := array_append(reasons, 'GLOBAL_SWITCH_DISABLED');
    END IF;
    IF EXISTS (SELECT 1 FROM platform.feature_flag f
                WHERE f.flag_code = 'listing-description-write'
                  AND f.status = 'ACTIVE' AND f.state = 'DISABLED'
                  AND ((f.scope_kind = 'PLATFORM' AND f.platform_code = command.platform_code)
                    OR (f.scope_kind = 'MARKETPLACE_ACCOUNT' AND f.marketplace_account_id =
                        (SELECT st.marketplace_account_id FROM core.store st WHERE st.id = command.store_id))
                    OR (f.scope_kind = 'STORE' AND f.store_id = command.store_id))) THEN
        reasons := array_append(reasons, 'SCOPED_SWITCH_DISABLED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.pilot_allowlist_entry entry
                    WHERE entry.organization_id = command.organization_id
                      AND entry.action_kind = 'LISTING_DESCRIPTION_CHANGE'
                      AND entry.platform_listing_id = command.platform_listing_id
                      AND entry.status = 'ACTIVE'
                      AND entry.valid_from <= statement_timestamp()
                      AND (entry.valid_until IS NULL OR entry.valid_until > statement_timestamp())) THEN
        reasons := array_append(reasons, 'ENTITY_NOT_ALLOWLISTED');
    END IF;
    -- A real write needs an active, exact-head-bound gate authority that names
    -- this listing and has production writes enabled. None exists.
    IF NOT EXISTS (SELECT 1 FROM ops.lc_gate_authority g
                    WHERE g.organization_id = command.organization_id
                      AND g.store_id = command.store_id
                      AND g.status = 'ACTIVE' AND g.production_write_enabled
                      AND command.platform_listing_id = ANY (g.platform_listing_ids)
                      AND g.valid_from <= statement_timestamp()
                      AND g.valid_until > statement_timestamp()) THEN
        reasons := array_append(reasons, 'PRODUCTION_WRITE_DISABLED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.approval_decision a
                    WHERE a.id = command.approval_decision_id
                      AND a.recommendation_id = command.recommendation_id
                      AND a.decision = 'APPROVED'
                      AND a.scope_expires_at > statement_timestamp()) THEN
        reasons := array_append(reasons, 'AUTHORIZATION_INVALID_OR_EXPIRED');
    END IF;
    IF command.approval_expires_at <= statement_timestamp() THEN
        reasons := array_append(reasons, 'APPROVAL_LEASE_EXPIRED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_action a
                    WHERE a.id = command.action_id AND a.state = 'LAUNCHED') THEN
        reasons := array_append(reasons, 'ACTION_NOT_LAUNCHED');
    END IF;
    gaps := ops.lc_binding_gaps(command.action_id);
    IF cardinality(gaps) > 0 THEN
        reasons := reasons || gaps;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_exposure_occupation o
                    WHERE o.action_id = command.action_id AND o.state <> 'RELEASED') THEN
        reasons := array_append(reasons, 'ALLOWANCE_NOT_OCCUPIED');
    END IF;
    IF ops.lc_scope_contained(command.organization_id, command.platform_listing_id) THEN
        reasons := array_append(reasons, 'SCOPE_CONTAINED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.guardrail_evaluation g
                    JOIN ops.lc_action_binding b ON b.action_id = command.action_id
                   WHERE g.recommendation_id = command.recommendation_id
                     AND g.purpose = 'EXECUTION' AND g.outcome = 'PASS'
                     AND g.lc_calibration_package_id = b.calibration_package_id
                     AND g.lc_calibration_version = b.calibration_version) THEN
        reasons := array_append(reasons, 'EXECUTION_PASS_MISSING');
    END IF;
    -- The verified operation names the description attribute it changes, so
    -- the request cannot be a whole-card import. Without it, nothing is sent.
    IF NOT EXISTS (SELECT 1 FROM platform.capability_operation o
                    JOIN platform.platform_endpoint e ON e.id = o.endpoint_id
                   WHERE o.capability_id = command.capability_id AND o.operation = 'APPLY'
                     AND o.status = 'ACTIVE' AND o.verification_state = 'VERIFIED'
                     AND (coalesce(e.path_template, '') || coalesce(e.query_template, '')
                          || o.request_template) LIKE '%{descriptionAttributeKey}%') THEN
        reasons := array_append(reasons, 'NON_TARGET_FIELD_RISK');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_action a
                    WHERE a.id = command.action_id AND a.kiz_marked_declared IS NOT NULL) THEN
        reasons := array_append(reasons, 'KIZ_MARKED_UNDECLARED');
    END IF;
    IF length(command.target_text) NOT BETWEEN command.length_bound_min AND command.length_bound_max THEN
        reasons := array_append(reasons, 'TEXT_LENGTH_OUT_OF_BOUNDS');
    END IF;
    SELECT o.text_digest INTO latest FROM core.lc_description_observation o
     WHERE o.platform_listing_id = command.platform_listing_id
     ORDER BY o.observed_at DESC, o.acquired_at DESC LIMIT 1;
    IF latest IS DISTINCT FROM command.prior_text_digest
        AND command.state IN ('PENDING', 'LEASED', 'RETRY_WAIT') THEN
        reasons := array_append(reasons, 'PRIOR_TEXT_MOVED');
    END IF;
    RETURN reasons;
END;
$$;
