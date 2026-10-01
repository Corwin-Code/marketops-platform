-- V0031: the engine corrections before the first real price write (W1, Owner decisions 2026-10-01).
--
-- The Owner authorized the platform's write capabilities on 2026-10-01. Before a price-change
-- capability is registered, four things in the price command chain have to change:
--
-- 1. An operator can close a command whose write gate never opened. Until now PENDING could only move
--    to LEASED, so such a command held the listing's one live command slot forever.
-- 2. An operator can ask for another readback after a mismatch or a manual takeover. Ozon may apply a
--    price a little after it accepted it; the write itself is still never repeated, only observed.
-- 3. The write gate refuses a listing in a seller promotion, and one whose crossed-out price would not
--    stay above the new price. Ozon's import sets the price ceiling without promotions, while a
--    proposal's prices are buyer prices after the seller's promotions; the two agree only for a listing
--    in no promotion (Owner decision: W1 changes only those). Ozon refuses a price at or above the
--    crossed-out price (official OpenAPI, POST /v1/product/import/prices, checked 2026-10-01).
-- 4. ops.price_write_readiness tells, before any command exists, what stands between a listing and a
--    price write. An approval creates a command only when nothing does; otherwise it only records the
--    decision and the price is changed by hand, so no command waits in PENDING for a gate that is shut.

INSERT INTO ops.price_command_transition (from_state, to_state, requires_lease, releases_lease, note) VALUES
    ('PENDING', 'FAILED_FINAL', false, false, 'an operator closed a command whose write gate never opened'),
    ('READBACK_MISMATCH', 'UNKNOWN_REQUIRES_READBACK', false, false,
     'an operator asked to read the platform again; the write itself is never repeated'),
    ('MANUAL_RESOLUTION', 'UNKNOWN_REQUIRES_READBACK', false, false,
     'an operator asked to read the platform again; the write itself is never repeated');

-- The write gate, unchanged except for the two W1 reasons at its end.
CREATE OR REPLACE FUNCTION ops.evaluate_price_write_gate(p_command_id uuid) RETURNS text[]
    LANGUAGE plpgsql STABLE
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
    command_row   record;
    newest_price  record;
    reasons       text[] := ARRAY[]::text[];
    now_instant   timestamptz := clock_timestamp();
BEGIN
    SELECT command.id, command.organization_id, command.store_id,
           command.platform_code, command.capability_id,
           command.platform_listing_variant_id, command.recommendation_id,
           command.approval_decision_id, command.target_price, command.prior_price,
           store.marketplace_account_id
      INTO command_row
      FROM ops.price_command AS command
      JOIN core.store AS store ON store.id = command.store_id
     WHERE command.id = p_command_id;

    IF command_row.id IS NULL THEN
        RETURN ARRAY['COMMAND_NOT_FOUND'];
    END IF;

    IF NOT ops.price_command_authority_matches(p_command_id) THEN
        reasons := array_append(reasons, 'COMMAND_AUTHORITY_MISMATCH');
    END IF;

    -- The capability itself must be verified against recorded evidence and
    -- available for this exact store.
    PERFORM 1
      FROM platform.platform_capability AS capability
     WHERE capability.id = command_row.capability_id
       AND capability.status = 'ACTIVE'
       AND capability.deprecated_at IS NULL
       AND capability.verification_state = 'VERIFIED';
    IF NOT FOUND
    THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_VERIFIED');
    END IF;

    PERFORM 1
      FROM platform.capability_subject_status AS subject
     WHERE subject.capability_id = command_row.capability_id
       AND subject.store_id = command_row.store_id
       AND subject.availability = 'AVAILABLE';
    IF NOT FOUND
    THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_AVAILABLE_FOR_STORE');
    END IF;

    -- The capability switch must be explicitly on, and no wider scope may be
    -- switched off. A missing flag is off, so an unconfigured scope blocks.
    PERFORM 1
      FROM platform.feature_flag AS flag
     WHERE flag.flag_code = 'price-change-write'
       AND flag.scope_kind = 'CAPABILITY'
       AND flag.capability_id = command_row.capability_id
       AND flag.status = 'ACTIVE'
       AND flag.state = 'ENABLED';
    IF NOT FOUND
    THEN
        reasons := array_append(reasons, 'CAPABILITY_SWITCH_DISABLED');
    END IF;

    PERFORM 1
      FROM platform.feature_flag AS flag
     WHERE flag.flag_code = 'price-change-write'
       AND flag.scope_kind = 'GLOBAL'
       AND flag.status = 'ACTIVE'
       AND flag.state = 'ENABLED';
    IF NOT FOUND
    THEN
        reasons := array_append(reasons, 'GLOBAL_SWITCH_DISABLED');
    END IF;

    IF EXISTS (
        SELECT 1
          FROM platform.feature_flag AS flag
         WHERE flag.flag_code = 'price-change-write'
           AND flag.status = 'ACTIVE'
           AND flag.state = 'DISABLED'
           AND ((flag.scope_kind = 'PLATFORM'
                    AND flag.platform_code = command_row.platform_code)
                OR (flag.scope_kind = 'MARKETPLACE_ACCOUNT'
                    AND flag.marketplace_account_id = command_row.marketplace_account_id)
                OR (flag.scope_kind = 'STORE'
                    AND flag.store_id = command_row.store_id)))
    THEN
        reasons := array_append(reasons, 'SCOPED_SWITCH_DISABLED');
    END IF;

    -- The entity must be on the positive allowlist at this instant.
    PERFORM 1
      FROM ops.pilot_allowlist_entry AS entry
     WHERE entry.action_kind = 'PRICE_CHANGE'
       AND entry.status = 'ACTIVE'
       AND entry.store_id = command_row.store_id
       AND (entry.platform_listing_variant_id IS NULL
            OR entry.platform_listing_variant_id
                = command_row.platform_listing_variant_id)
       AND entry.valid_from <= now_instant
       AND entry.valid_until > now_instant;
    IF NOT FOUND
    THEN
        reasons := array_append(reasons, 'ENTITY_NOT_ALLOWLISTED');
    END IF;

    -- The authorization must still stand and still cover this exact proposal.
    PERFORM 1
      FROM ops.approval_decision AS decision
      JOIN ops.recommendation AS proposal ON proposal.id = decision.recommendation_id
     WHERE decision.id = command_row.approval_decision_id
       AND decision.recommendation_id = command_row.recommendation_id
       AND decision.decision IN ('APPROVED', 'POLICY_AUTHORIZED')
       AND decision.scope_expires_at > now_instant
       AND decision.entity_version_digest = proposal.entity_version_digest;
    IF NOT FOUND
    THEN
        reasons := array_append(reasons, 'AUTHORIZATION_INVALID_OR_EXPIRED');
    END IF;

    PERFORM 1
      FROM ops.recommendation AS proposal
     WHERE proposal.id = command_row.recommendation_id
       AND proposal.state IN ('APPROVED', 'POLICY_AUTHORIZED',
                              'COMMAND_CREATED', 'EXECUTION_TRACKING')
       AND proposal.valid_until > now_instant;
    IF NOT FOUND
    THEN
        reasons := array_append(reasons, 'RECOMMENDATION_STALE');
    END IF;

    -- The mapping the profit case rests on must still resolve.
    PERFORM 1
      FROM core.listing_mapping AS mapping
     WHERE mapping.platform_listing_variant_id
               = command_row.platform_listing_variant_id
       AND mapping.status = 'ACTIVE'
       AND mapping.effective_from <= now_instant
       AND (mapping.effective_to IS NULL OR mapping.effective_to > now_instant);
    IF NOT FOUND
    THEN
        reasons := array_append(reasons, 'MAPPING_UNRESOLVED');
    END IF;

    IF EXISTS (
        SELECT 1
          FROM core.mapping_conflict AS conflict
         WHERE conflict.platform_listing_variant_id
                   = command_row.platform_listing_variant_id
           AND conflict.state = 'OPEN')
    THEN
        reasons := array_append(reasons, 'MAPPING_CONFLICT_OPEN');
    END IF;

    -- The deterministic guardrail must have passed for execution specifically.
    PERFORM 1
      FROM ops.guardrail_evaluation AS evaluation
     WHERE evaluation.recommendation_id = command_row.recommendation_id
       AND evaluation.purpose = 'EXECUTION'
       AND evaluation.outcome = 'PASS';
    IF NOT FOUND
    THEN
        reasons := array_append(reasons, 'GUARDRAIL_NOT_PASSED');
    END IF;

    -- W1 (Owner decision 2026-10-01): Ozon's import sets the price ceiling without promotions, while a
    -- proposal's prices are buyer prices after the seller's promotions. They are the same price only for
    -- a listing in no seller promotion; for one in a promotion the write would land below the target.
    -- The crossed-out price must also stay above the new price, or the platform refuses the change.
    SELECT price.discount_price, price.selling_price, price.list_price
      INTO newest_price
      FROM core.listing_price_observation AS price
     WHERE price.platform_listing_variant_id = command_row.platform_listing_variant_id
       AND NOT EXISTS (SELECT 1 FROM core.listing_price_observation AS later
                        WHERE later.supersedes_fact_id = price.id)
     ORDER BY price.observed_at DESC, price.id DESC
     LIMIT 1;
    IF FOUND THEN
        IF newest_price.discount_price IS NOT NULL AND newest_price.selling_price IS NOT NULL
            AND newest_price.discount_price <> newest_price.selling_price
        THEN
            reasons := array_append(reasons, 'LISTING_IN_SELLER_PROMOTION');
        END IF;
        IF newest_price.list_price IS NOT NULL AND newest_price.list_price > 0
            AND newest_price.list_price <= command_row.target_price
        THEN
            reasons := array_append(reasons, 'CROSSED_OUT_PRICE_NOT_ABOVE_TARGET');
        END IF;
    END IF;

    RETURN reasons;
END;
$$;


-- What stands between one listing variant (or, without one, the store) and a price write before any
-- command exists: the parts of ops.evaluate_price_write_gate that depend only on the store, the listing
-- and the switches. An empty answer means a command created now would find the gate open on these.
CREATE FUNCTION ops.price_write_readiness(p_store_id uuid, p_variant_id uuid) RETURNS text[]
    LANGUAGE plpgsql STABLE
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
    store_row       record;
    capability_row  record;
    newest_price    record;
    reasons         text[] := ARRAY[]::text[];
    now_instant     timestamptz := clock_timestamp();
BEGIN
    SELECT store.id, store.marketplace_account_id, account.platform_code
      INTO store_row
      FROM core.store AS store
      JOIN core.marketplace_account AS account ON account.id = store.marketplace_account_id
     WHERE store.id = p_store_id;
    IF NOT FOUND THEN
        RETURN ARRAY['STORE_NOT_FOUND'];
    END IF;

    SELECT capability.id, capability.verification_state
      INTO capability_row
      FROM platform.platform_capability AS capability
     WHERE capability.platform_code = store_row.platform_code
       AND capability.capability_code = 'price-change'
       AND capability.read_write_class = 'WRITE'
       AND capability.status = 'ACTIVE'
       AND capability.deprecated_at IS NULL;
    IF NOT FOUND THEN
        RETURN ARRAY['CAPABILITY_NOT_REGISTERED'];
    END IF;
    IF capability_row.verification_state <> 'VERIFIED' THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_VERIFIED');
    END IF;

    PERFORM 1
      FROM platform.capability_subject_status AS subject
     WHERE subject.capability_id = capability_row.id
       AND subject.store_id = p_store_id
       AND subject.availability = 'AVAILABLE';
    IF NOT FOUND THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_AVAILABLE_FOR_STORE');
    END IF;

    PERFORM 1
      FROM platform.feature_flag AS flag
     WHERE flag.flag_code = 'price-change-write'
       AND flag.scope_kind = 'CAPABILITY'
       AND flag.capability_id = capability_row.id
       AND flag.status = 'ACTIVE'
       AND flag.state = 'ENABLED';
    IF NOT FOUND THEN
        reasons := array_append(reasons, 'CAPABILITY_SWITCH_DISABLED');
    END IF;

    PERFORM 1
      FROM platform.feature_flag AS flag
     WHERE flag.flag_code = 'price-change-write'
       AND flag.scope_kind = 'GLOBAL'
       AND flag.status = 'ACTIVE'
       AND flag.state = 'ENABLED';
    IF NOT FOUND THEN
        reasons := array_append(reasons, 'GLOBAL_SWITCH_DISABLED');
    END IF;

    IF EXISTS (
        SELECT 1
          FROM platform.feature_flag AS flag
         WHERE flag.flag_code = 'price-change-write'
           AND flag.status = 'ACTIVE'
           AND flag.state = 'DISABLED'
           AND ((flag.scope_kind = 'PLATFORM' AND flag.platform_code = store_row.platform_code)
                OR (flag.scope_kind = 'MARKETPLACE_ACCOUNT'
                    AND flag.marketplace_account_id = store_row.marketplace_account_id)
                OR (flag.scope_kind = 'STORE' AND flag.store_id = p_store_id)))
    THEN
        reasons := array_append(reasons, 'SCOPED_SWITCH_DISABLED');
    END IF;

    IF p_variant_id IS NOT NULL THEN
        PERFORM 1
          FROM ops.pilot_allowlist_entry AS entry
         WHERE entry.action_kind = 'PRICE_CHANGE'
           AND entry.status = 'ACTIVE'
           AND entry.store_id = p_store_id
           AND (entry.platform_listing_variant_id IS NULL
                OR entry.platform_listing_variant_id = p_variant_id)
           AND entry.valid_from <= now_instant
           AND entry.valid_until > now_instant;
        IF NOT FOUND THEN
            reasons := array_append(reasons, 'ENTITY_NOT_ALLOWLISTED');
        END IF;

        SELECT price.discount_price, price.selling_price
          INTO newest_price
          FROM core.listing_price_observation AS price
         WHERE price.platform_listing_variant_id = p_variant_id
           AND NOT EXISTS (SELECT 1 FROM core.listing_price_observation AS later
                            WHERE later.supersedes_fact_id = price.id)
         ORDER BY price.observed_at DESC, price.id DESC
         LIMIT 1;
        IF FOUND AND newest_price.discount_price IS NOT NULL AND newest_price.selling_price IS NOT NULL
            AND newest_price.discount_price <> newest_price.selling_price
        THEN
            reasons := array_append(reasons, 'LISTING_IN_SELLER_PROMOTION');
        END IF;
    END IF;

    RETURN reasons;
END;
$$;

REVOKE ALL ON FUNCTION ops.price_write_readiness(p_store_id uuid, p_variant_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.price_write_readiness(p_store_id uuid, p_variant_id uuid) TO marketops_app;
