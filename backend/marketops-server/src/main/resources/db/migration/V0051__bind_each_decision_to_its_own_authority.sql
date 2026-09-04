-- An advertising decision must be bound to the advertising authority.
--
-- ops.approval_decision and ops.guardrail_evaluation are shared by both
-- write-capable actions, and both have carried an unconditional BEFORE INSERT
-- trigger that calls ops.price_authority_snapshot. There is no branch on
-- action_kind and no WHEN clause, so an AD_BID_CHANGE guardrail was compared
-- against a price authority document and refused with MO032 "guardrail inputs
-- changed" every single time.
--
-- The consequence was total rather than partial. No advertising guardrail could
-- be recorded, so no execution PASS could exist, so ops.create_ad_bid_command
-- could never satisfy its own gate, so no advertising command could ever be
-- created. The whole controlled-write path was unreachable for a reason nobody
-- had written down.
--
-- The price snapshot did not fail loudly for an advertising subject either,
-- which is why this survived: ops.price_authority_snapshot LEFT JOINs the
-- listing variant, so an advertising subject yields a document that is
-- structurally valid, carries the right organization, and describes nothing.
--
-- So the binder dispatches on the action the recommendation actually names. A
-- price change is bound to the price authority and an advertising bid change to
-- the advertising authority, and neither can be bound to the other's.
--
-- Forward-only: the function is replaced under a new name and the three
-- triggers are re-pointed. No row is rewritten and no earlier migration is
-- edited.

-- ---------------------------------------------------------------------------
-- What "the facts have not moved" means for an advertising decision
-- ---------------------------------------------------------------------------

-- The price path digests the canonical metric values the case rests on.
-- Advertising cannot: mart.metric_value.subject_kind admits PRODUCT_VARIANT,
-- PLATFORM_LISTING_VARIANT and STORE, and an advertising object is none of
-- them. Reading metrics for an advertising subject therefore returns nothing,
-- and a digest of nothing is the same constant for every object — a check that
-- would pass forever and mean nothing.
--
-- So the advertising identity is taken over the facts an advertising decision
-- actually rests on: which object at which lineage generation, whether it is
-- still proven independently controllable, the exact current configuration
-- observation and the bid it recorded, the affected set, and the candidate with
-- the policy version that bounded it. If any of those moves, the approval no
-- longer describes the world it was given for.
--
-- Defined once, here, because the proposal, the approval and the write gate all
-- have to agree on it. Java reads it rather than recomputing it, so there is no
-- second definition to drift.
CREATE FUNCTION ops.ad_entity_version_digest(
    p_ad_native_object_id uuid,
    p_candidate_id        uuid)
RETURNS text
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
    SELECT encode(sha256(convert_to(concat_ws(chr(31),
        obj.id::text,
        obj.lineage_key,
        obj.lineage_generation::text,
        obj.control_granularity_state,
        obj.semantic_profile_id::text,
        obj.status,
        coalesce(conf.id::text, ''),
        coalesce(conf.observed_bid_amount::text, ''),
        coalesce(conf.bid_currency_code, ''),
        coalesce(conf.bid_unit_code, ''),
        coalesce(conf.observed_status, ''),
        coalesce(cand.id::text, ''),
        coalesce(cand.affected_set_digest, ''),
        coalesce(cand.direction, ''),
        coalesce(cand.candidate_basis, ''),
        coalesce(cand.current_bid_amount::text, ''),
        coalesce(cand.provider_normalized_amount::text, ''),
        coalesce(cand.target_policy_id::text, ''),
        coalesce(cand.target_policy_version::text, '')), 'UTF8')), 'hex')
      FROM core.ad_native_object obj
      LEFT JOIN LATERAL (
            SELECT c.* FROM core.ad_object_configuration_observation c
             WHERE c.ad_native_object_id = obj.id
               AND c.organization_id = obj.organization_id
               AND NOT EXISTS (SELECT 1 FROM core.ad_object_configuration_observation later
                                WHERE later.supersedes_observation_id = c.id)
             ORDER BY c.observed_at DESC, c.id DESC LIMIT 1) conf ON true
      LEFT JOIN ops.ad_bid_candidate cand
        ON cand.id = p_candidate_id AND cand.organization_id = obj.organization_id
     WHERE obj.id = p_ad_native_object_id
$$;
REVOKE ALL ON FUNCTION ops.ad_entity_version_digest(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_entity_version_digest(uuid, uuid) TO marketops_app;

-- The advertising authority now exposes that identity under the same key the
-- price authority uses, so the approval binder below reads identically for
-- either action rather than branching twice.
CREATE OR REPLACE FUNCTION ops.ad_bid_authority_snapshot(p_recommendation_id uuid)
RETURNS jsonb
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, core, platform, mart, pg_temp
AS $$
    SELECT jsonb_build_object(
        'proposal', jsonb_build_object(
            'id', r.id, 'organizationId', r.organization_id, 'storeId', r.store_id,
            'subjectKind', r.subject_kind, 'subjectId', r.subject_id,
            'actionKind', r.action_kind, 'parameters', r.proposed_parameters,
            'risk', r.risk_label, 'window', r.window_code,
            'validUntil', r.valid_until, 'entityDigest', r.entity_version_digest),
        'platformCode', obj.platform_code,
        'nativeObjectKey', obj.native_object_key,
        'nativeCampaignKey', obj.native_campaign_key,
        'nativeObjectKind', obj.native_object_kind,
        'lineageKey', obj.lineage_key,
        'lineageGeneration', obj.lineage_generation,
        'controlGranularityState', obj.control_granularity_state,
        'biddingMode', obj.bidding_mode,
        'semanticProfileId', obj.semantic_profile_id,
        'currentEntityDigest', ops.ad_entity_version_digest(obj.id, cd_id.value),
        'affectedSet', affected.item,
        'currentConfiguration', config.item,
        'candidate', candidate.item)
      FROM ops.recommendation r
      LEFT JOIN LATERAL (
          SELECT CASE WHEN ops.ad_bid_parameter_contract_is_valid(r.proposed_parameters)
                      THEN (r.proposed_parameters ->> 'candidateId')::uuid END AS value
      ) cd_id ON true
      LEFT JOIN core.ad_native_object obj
        ON obj.id = r.subject_id AND obj.organization_id = r.organization_id
       AND r.subject_kind = 'AD_NATIVE_OBJECT'
      LEFT JOIN LATERAL (
          SELECT jsonb_build_object('id', a.id, 'digest', a.affected_set_digest,
                     'resolution', a.resolution_state,
                     'variantIds', to_jsonb(a.product_variant_ids)) AS item
            FROM core.ad_affected_set a
           WHERE a.ad_native_object_id = obj.id AND a.organization_id = r.organization_id
           ORDER BY a.resolved_at DESC, a.id DESC LIMIT 1
      ) affected ON true
      LEFT JOIN LATERAL (
          SELECT jsonb_build_object('id', c.id, 'bid', c.observed_bid_amount,
                     'currency', c.bid_currency_code, 'unit', c.bid_unit_code,
                     'status', c.observed_status, 'grade', c.evidence_grade,
                     'generation', c.lineage_generation,
                     'observedAt', c.observed_at) AS item
            FROM core.ad_object_configuration_observation c
           WHERE c.ad_native_object_id = obj.id AND c.organization_id = r.organization_id
             AND NOT EXISTS (SELECT 1 FROM core.ad_object_configuration_observation later
                              WHERE later.supersedes_observation_id = c.id)
           ORDER BY c.observed_at DESC, c.id DESC LIMIT 1
      ) config ON true
      LEFT JOIN LATERAL (
          SELECT jsonb_build_object('id', cd.id, 'direction', cd.direction,
                     'basis', cd.candidate_basis, 'target', cd.provider_normalized_amount,
                     'currency', cd.currency_code, 'unit', cd.bid_unit_code,
                     'digest', cd.affected_set_digest,
                     'targetPolicyId', cd.target_policy_id,
                     'maxCpc', cd.max_cpc_amount) AS item
            FROM ops.ad_bid_candidate cd
           WHERE cd.organization_id = r.organization_id
             AND cd.id = CASE WHEN ops.ad_bid_parameter_contract_is_valid(r.proposed_parameters)
                              THEN (r.proposed_parameters ->> 'candidateId')::uuid END
      ) candidate ON true
     WHERE r.id = p_recommendation_id
$$;

-- The current definition is V0029's, not V0020's. This replacement is built
-- from the one actually in force: V0029 added the as-of comparison, the
-- staleness predicate and the fulfilment-mode assignment, and a replacement
-- based on the original body would have silently reverted all three. It did, and
-- the ninety price-path cases said so.
CREATE OR REPLACE FUNCTION ops.bind_price_authority_snapshot()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE snapshot jsonb;
        evaluated_snapshot jsonb;
        decided_action text;
BEGIN
    SELECT r.action_kind INTO decided_action
      FROM ops.recommendation r WHERE r.id = NEW.recommendation_id;
    IF decided_action IS NULL THEN
        RAISE EXCEPTION 'decision names no recommendation' USING ERRCODE = 'MO032';
    END IF;

    -- One authority per action, chosen from the action itself rather than from
    -- the table the row is going into. Both tables carry both actions.
    IF decided_action = 'AD_BID_CHANGE' THEN
        SELECT ops.ad_bid_authority_snapshot(NEW.recommendation_id) INTO snapshot;
        IF snapshot IS NULL OR snapshot #>> '{proposal,organizationId}'
                IS DISTINCT FROM NEW.organization_id::text THEN
            RAISE EXCEPTION 'recommendation ownership does not match' USING ERRCODE = 'MO032';
        END IF;
        IF TG_TABLE_NAME = 'guardrail_evaluation' THEN
            -- The advertising authority is a stable description of recorded
            -- facts rather than a window-dependent computation, so there is no
            -- separate as-of document to compare against: the caller captured
            -- this and it must still be this.
            IF NEW.authority_snapshot IS DISTINCT FROM snapshot THEN
                RAISE EXCEPTION 'guardrail inputs changed' USING ERRCODE = 'MO032';
            END IF;
        ELSIF TG_TABLE_NAME = 'approval_decision' THEN
            IF NEW.decision IN ('APPROVED', 'POLICY_AUTHORIZED') THEN
                IF NEW.entity_version_digest IS DISTINCT FROM snapshot ->> 'currentEntityDigest'
                   OR NEW.entity_version_digest
                       IS DISTINCT FROM snapshot #>> '{proposal,entityDigest}'
                   OR NOT EXISTS (
                       SELECT 1 FROM ops.guardrail_evaluation g
                        WHERE g.recommendation_id = NEW.recommendation_id
                          AND g.organization_id = NEW.organization_id
                          AND g.purpose = 'APPROVAL' AND g.outcome = 'PASS'
                          AND g.authority_snapshot = snapshot) THEN
                    RAISE EXCEPTION 'approval has no matching current guardrail authority'
                        USING ERRCODE = 'MO032';
                END IF;
            END IF;
            NEW.authority_snapshot := snapshot;
        ELSE
            NEW.authority_snapshot := snapshot;
        END IF;
        RETURN NEW;
    END IF;

    -- Everything below is V0029's price path, unchanged.
    SELECT ops.price_authority_snapshot(NEW.recommendation_id) INTO snapshot;
    IF snapshot IS NULL OR snapshot #>> '{proposal,organizationId}'
            IS DISTINCT FROM NEW.organization_id::text THEN
        RAISE EXCEPTION 'recommendation ownership does not match' USING ERRCODE = 'MO032';
    END IF;
    IF TG_TABLE_NAME = 'guardrail_evaluation' THEN
        SELECT ops.price_authority_snapshot(NEW.recommendation_id, NEW.evaluated_at)
          INTO evaluated_snapshot;
        IF NEW.authority_snapshot IS DISTINCT FROM evaluated_snapshot THEN
            RAISE EXCEPTION 'guardrail inputs do not match evaluation as-of authority'
                USING ERRCODE = 'MO032';
        END IF;
        IF NEW.outcome = 'PASS'
           AND (NEW.authority_snapshot IS DISTINCT FROM snapshot
                OR NOT ops.r2_price_authority_is_current(
                    NEW.authority_snapshot, statement_timestamp())) THEN
            RAISE EXCEPTION 'guardrail authority is stale or incomplete'
                USING ERRCODE = 'MO032';
        END IF;
    ELSIF TG_TABLE_NAME = 'approval_decision' THEN
        IF NEW.decision IN ('APPROVED', 'POLICY_AUTHORIZED') THEN
            IF NOT ops.r2_price_authority_is_current(snapshot, statement_timestamp())
               OR NEW.entity_version_digest IS DISTINCT FROM snapshot ->> 'currentEntityDigest'
               OR NEW.entity_version_digest IS DISTINCT FROM snapshot #>> '{proposal,entityDigest}'
               OR NOT EXISTS (
                   SELECT 1 FROM ops.guardrail_evaluation g
                    WHERE g.recommendation_id = NEW.recommendation_id
                      AND g.organization_id = NEW.organization_id
                      AND g.purpose = 'APPROVAL' AND g.outcome = 'PASS'
                      AND g.authority_snapshot = snapshot) THEN
                RAISE EXCEPTION 'approval has no matching current guardrail authority'
                    USING ERRCODE = 'MO032';
            END IF;
        END IF;
        NEW.authority_snapshot := snapshot;
    ELSE
        IF NOT ops.r2_price_authority_is_current(snapshot, statement_timestamp()) THEN
            RAISE EXCEPTION 'command authority is stale or incomplete'
                USING ERRCODE = 'MO032';
        END IF;
        NEW.authority_snapshot := snapshot;
        NEW.fulfillment_mode_code := snapshot #>> '{economics,fulfillmentModeCode}';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.bind_price_authority_snapshot() FROM PUBLIC;
