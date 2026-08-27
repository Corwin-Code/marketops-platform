-- One name, one meaning.
--
-- Two adjacent columns were both called capability_code and meant different
-- things. In platform.platform_capability it is a registry code — lowercase,
-- shaped like every other registry identifier in that schema. In the two ops
-- tables it is the business action a person authorises, spelled the way
-- ops.policy_authorization.action_kind and ops.recommendation.action_kind
-- already spell it.
--
-- The collision is the sort that survives review and then costs an incident:
-- somebody joins the allowlist to the capability registry on the two columns
-- that share a name, gets no rows, and concludes the entity is not allowlisted.
-- Renaming the operational columns to action_kind leaves each vocabulary
-- unambiguous, and nothing that reads either one has to guess which it has.
--
-- Forward-only: the columns are renamed rather than dropped and recreated, so
-- no allowlist entry and no recorded switch movement loses its meaning, and the
-- write gate is replaced in the same migration so no moment exists in which it
-- reads a column that is not there.
--
-- The replacement also corrects how the gate builds its answer. The original
-- appended each reason with `reasons || 'CODE'`, and PostgreSQL resolves that
-- against array || array for an untyped literal, so the gate raised
-- "malformed array literal" the moment it actually had a reason to give. A gate
-- that only works while everything is permitted is worse than no gate: it
-- passes every test that checks the happy path and fails closed by crashing at
-- the one moment an operator needs an explanation. Every append is now
-- array_append, which has one meaning.

ALTER TABLE ops.pilot_allowlist_entry
    RENAME COLUMN capability_code TO action_kind;

ALTER TABLE ops.pilot_allowlist_entry
    RENAME CONSTRAINT pilot_allowlist_entry_capability_ck
        TO pilot_allowlist_entry_action_kind_ck;

ALTER INDEX ops.pilot_allowlist_entry_live_uq
    RENAME TO pilot_allowlist_entry_live_action_uq;

ALTER INDEX ops.pilot_allowlist_entry_lookup_ix
    RENAME TO pilot_allowlist_entry_action_lookup_ix;

ALTER TABLE ops.kill_switch_event
    RENAME COLUMN capability_code TO action_kind;

-- The gate is replaced rather than patched, so the body that reads the renamed
-- column and the rename itself commit together.
CREATE OR REPLACE FUNCTION ops.evaluate_price_write_gate(p_command_id uuid)
RETURNS text[]
LANGUAGE plpgsql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    command_row   record;
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

    RETURN reasons;
END;
$$;

-- Nothing named capability_code may remain outside the platform registry, so a
-- future migration that reintroduces the collision fails here rather than in an
-- incident.
DO $verify$
DECLARE
    offenders text;
BEGIN
    SELECT string_agg(table_schema || '.' || table_name, ', ')
      INTO offenders
      FROM information_schema.columns
     WHERE column_name = 'capability_code'
       AND table_schema <> 'platform';

    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'capability_code is a platform registry name; found it in %', offenders
            USING ERRCODE = 'MO004';
    END IF;
END;
$verify$;
