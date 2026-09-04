-- Containment: what stops two governed actions colliding, what bounds them in
-- aggregate, what stops everything at once, and the single record that says a
-- production decision scope is coherent enough to act on.
--
-- Four ideas.
--
-- First, an action reserves the thing it acts on. An advertising object drives a
-- set of variants, and two governed interventions overlapping that set at once
-- make the outcome of both unattributable — not merely noisy, unattributable,
-- because neither can be compared against a baseline the other did not disturb.
-- A recommendation reserves nothing; only something that actually changed, or
-- is about to change, the real advertising environment does. A governed manual
-- packet counts, because the marketplace does not know or care which of our
-- code paths moved the bid.
--
-- Second, the aggregate envelope has axes and no total. Six independent bounds,
-- every one hard, and no arithmetic anywhere that could let a low number on one
-- pay for a high number on another. Protection cannot net against Optimization.
-- Reserved recovery headroom cannot be borrowed. Unknown and Mismatch keep
-- consuming capacity until somebody resolves them factually, because an
-- unresolved write is an unbounded liability, not a finished one.
--
-- Third, stopping is asymmetric. Several roles can stop; nobody who can stop can
-- restart alone. A reenablement is never time-based: it needs a classified root
-- cause, resolved unknowns, replaced authorities, reconciled results, current
-- capability evidence, an exact new scope, an endorsement and an Owner approval.
--
-- Fourth, twelve interdependent policy versions become production authority
-- together or not at all. Publishing a new freshness profile changes nothing
-- about what may be written; activating a Bundle that references it does. The
-- Bundle references without re-owning, and its activation is refused unless the
-- whole combination validates.
--
-- Nothing here transmits. This migration decides what may be attempted.

-- ---------------------------------------------------------------------------
-- Affected-set action observation reservation
-- ---------------------------------------------------------------------------

-- One live reservation per advertising object, and an overlap test against the
-- variant set rather than the object, because two different campaigns promoting
-- the same hero SKU collide even though their object ids differ.
--
-- released_at is set only when every release condition is met, and the check
-- below makes the conditions part of the row rather than part of a service:
-- provider or manual state resolved, no unknown or mismatch, the first required
-- early observation complete, and no unresolved action-associated regression.
CREATE TABLE ops.ad_action_reservation (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    ad_native_object_id         uuid        NOT NULL,
    store_id                    uuid        NOT NULL,
    affected_set_id             uuid        NOT NULL,
    affected_set_digest         text        NOT NULL,
    product_variant_ids         uuid[]      NOT NULL,
    intervention_kind           text        NOT NULL,
    intervention_reference_id   uuid        NOT NULL,
    direction                   text,
    lane                        text        NOT NULL,
    state                       text        NOT NULL,
    configuration_resolved      boolean     NOT NULL DEFAULT false,
    unknown_or_mismatch_open    boolean     NOT NULL DEFAULT false,
    early_observation_complete  boolean     NOT NULL DEFAULT false,
    regression_open             boolean     NOT NULL DEFAULT false,
    reserved_at                 timestamptz NOT NULL,
    released_at                 timestamptz,
    release_reason              text,
    correlation_id              text        NOT NULL,
    version                     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ad_action_reservation_pk PRIMARY KEY (id),
    CONSTRAINT ad_action_reservation_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_action_reservation_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_action_reservation_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_action_reservation_affected_set_fk
        FOREIGN KEY (affected_set_id, organization_id)
        REFERENCES core.ad_affected_set (id, organization_id),
    CONSTRAINT ad_action_reservation_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_action_reservation_digest_ck
        CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    -- Only things that change the real advertising environment reserve. A
    -- recommendation, a watch or a data-repair task is not one of them.
    CONSTRAINT ad_action_reservation_kind_ck
        CHECK (intervention_kind IN
            ('CONTROLLED_AD_BID_CHANGE', 'CONFIRMED_MANUAL_PACKET',
             'EXACT_PRIOR_BID_COMPENSATION')),
    CONSTRAINT ad_action_reservation_direction_ck
        CHECK (direction IS NULL OR direction IN
            ('PROTECTION_DECREASE', 'OPTIMIZATION_INCREASE', 'EXACT_PRIOR_BID_COMPENSATION')),
    CONSTRAINT ad_action_reservation_lane_ck
        CHECK (lane IN ('PROTECTION', 'DATA_REPAIR', 'OPTIMIZATION', 'WATCH')),
    CONSTRAINT ad_action_reservation_state_ck
        CHECK (state IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ad_action_reservation_variants_ck
        CHECK (cardinality(product_variant_ids) BETWEEN 1 AND 4096
            AND array_position(product_variant_ids, NULL) IS NULL),
    -- The four release conditions, as a row-level fact. A service that decided
    -- to release early would have to write a row that says the conditions were
    -- met, which is a lie somebody can find rather than a branch nobody reads.
    CONSTRAINT ad_action_reservation_release_ck
        CHECK ((state = 'RELEASED')
            = (released_at IS NOT NULL AND release_reason IS NOT NULL)),
    CONSTRAINT ad_action_reservation_release_conditions_ck
        CHECK (state <> 'RELEASED'
            OR (configuration_resolved
                AND NOT unknown_or_mismatch_open
                AND early_observation_complete
                AND NOT regression_open)),
    CONSTRAINT ad_action_reservation_release_order_ck
        CHECK (released_at IS NULL OR released_at >= reserved_at),
    CONSTRAINT ad_action_reservation_release_reason_ck
        CHECK (release_reason IS NULL
            OR length(btrim(release_reason)) BETWEEN 1 AND 256),
    CONSTRAINT ad_action_reservation_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE UNIQUE INDEX ad_action_reservation_live_object_uq
    ON ops.ad_action_reservation (ad_native_object_id)
    WHERE state = 'ACTIVE';
CREATE INDEX ad_action_reservation_live_store_ix
    ON ops.ad_action_reservation (organization_id, store_id)
    WHERE state = 'ACTIVE';
-- The overlap test reads this. A GIN index on the variant array is what makes
-- "does any active reservation touch any of these variants" answerable without
-- scanning every live intervention.
CREATE INDEX ad_action_reservation_variants_ix
    ON ops.ad_action_reservation USING gin (product_variant_ids)
    WHERE state = 'ACTIVE';

-- Whether a proposed action's affected set overlaps a live one, and if so which.
-- Protection and Regression take deterministic precedence over ordinary
-- Optimization, so the caller receives the blocking reservation and can say
-- which lane holds it rather than only that something does.
CREATE FUNCTION ops.ad_overlapping_reservation(
    p_organization_id uuid,
    p_variant_ids     uuid[],
    p_exclude_object  uuid)
RETURNS TABLE (reservation_id uuid, lane text, intervention_kind text)
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, pg_temp
AS $$
    SELECT r.id, r.lane, r.intervention_kind
      FROM ops.ad_action_reservation AS r
     WHERE r.organization_id = p_organization_id
       AND r.state = 'ACTIVE'
       AND (p_exclude_object IS NULL OR r.ad_native_object_id <> p_exclude_object)
       AND r.product_variant_ids && p_variant_ids
     ORDER BY CASE r.lane WHEN 'PROTECTION' THEN 0 WHEN 'DATA_REPAIR' THEN 1
                          WHEN 'OPTIMIZATION' THEN 2 ELSE 3 END,
              r.reserved_at
$$;
REVOKE ALL ON FUNCTION ops.ad_overlapping_reservation(uuid, uuid[], uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_overlapping_reservation(uuid, uuid[], uuid) TO marketops_app;

-- ---------------------------------------------------------------------------
-- The aggregate exposure envelope
-- ---------------------------------------------------------------------------

-- Six axes, every one hard, no total. reserved_recovery_headroom_count is the
-- capacity ordinary actions may never consume: if every slot is spent on new
-- optimizations, an incident has nowhere to put its compensations.
CREATE TABLE core.ad_exposure_envelope (
    id                                   uuid           NOT NULL,
    organization_id                      uuid           NOT NULL,
    policy_version                       integer        NOT NULL,
    scope_kind                           text           NOT NULL,
    platform_code                        text,
    store_ref_id                         uuid,
    currency_code                        text           NOT NULL,
    max_active_interventions             integer        NOT NULL,
    max_affected_retained_sales_share    numeric(6, 5)  NOT NULL,
    max_associated_spend_amount          numeric(18, 4) NOT NULL,
    max_cumulative_bid_change_amount     numeric(18, 4) NOT NULL,
    cumulative_window_hours              integer        NOT NULL,
    max_unresolved_transmitted_writes    integer        NOT NULL,
    reserved_recovery_headroom_count     integer        NOT NULL,
    owner_user_id                        uuid           NOT NULL,
    reason                               text           NOT NULL,
    evidence_reference                   text           NOT NULL,
    effective_from                       timestamptz    NOT NULL,
    effective_to                         timestamptz,
    status                               text           NOT NULL,
    created_at                           timestamptz    NOT NULL,
    CONSTRAINT ad_exposure_envelope_pk PRIMARY KEY (id),
    CONSTRAINT ad_exposure_envelope_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_exposure_envelope_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_exposure_envelope_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_exposure_envelope_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_exposure_envelope_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_exposure_envelope_version_uq UNIQUE (organization_id, policy_version),
    CONSTRAINT ad_exposure_envelope_version_ck CHECK (policy_version >= 1),
    CONSTRAINT ad_exposure_envelope_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT ad_exposure_envelope_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION'
                AND platform_code IS NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'PLATFORM'
                AND platform_code IS NOT NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'STORE'
                AND platform_code IS NOT NULL AND store_ref_id IS NOT NULL)),
    CONSTRAINT ad_exposure_envelope_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_exposure_envelope_bounds_ck
        CHECK (max_active_interventions >= 1
            AND max_affected_retained_sales_share > 0
            AND max_affected_retained_sales_share <= 1
            AND max_associated_spend_amount >= 0
            AND max_cumulative_bid_change_amount >= 0
            AND max_unresolved_transmitted_writes >= 0
            AND reserved_recovery_headroom_count >= 1),
    CONSTRAINT ad_exposure_envelope_window_ck
        CHECK (cumulative_window_hours BETWEEN 1 AND 8760),
    -- Recovery headroom is reserved out of the same pool. An envelope whose
    -- headroom equalled or exceeded its capacity would leave no room for
    -- ordinary work at all, and one with none would leave no room for recovery.
    CONSTRAINT ad_exposure_envelope_headroom_ck
        CHECK (reserved_recovery_headroom_count < max_active_interventions),
    CONSTRAINT ad_exposure_envelope_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_exposure_envelope_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_exposure_envelope_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_exposure_envelope_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_exposure_envelope_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- Quarantine, hold and kill
-- ---------------------------------------------------------------------------

-- One table for every scope at which advertising execution can be stopped,
-- because a person under pressure should not have to choose between five
-- different stop buttons that behave differently.
--
-- activated_at is the authoritative instant. Everything that had not begun
-- transmitting by then is prevented; everything that had is resolved factually.
CREATE TABLE ops.ad_containment (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    containment_kind            text        NOT NULL,
    scope_kind                  text        NOT NULL,
    platform_code               text,
    marketplace_account_id      uuid,
    store_id                    uuid,
    ad_native_object_id         uuid,
    affected_set_digest         text,
    capability_code             text,
    authority_version_reference text,
    cause_class                 text        NOT NULL,
    reason                      text        NOT NULL,
    evidence_reference          text        NOT NULL,
    activated_by_user_id        uuid,
    activated_by_trigger        text,
    activated_at                timestamptz NOT NULL,
    state                       text        NOT NULL,
    root_cause_classified       boolean     NOT NULL DEFAULT false,
    unknowns_resolved           boolean     NOT NULL DEFAULT false,
    authorities_replaced        boolean     NOT NULL DEFAULT false,
    results_reconciled          boolean     NOT NULL DEFAULT false,
    capability_evidence_current boolean     NOT NULL DEFAULT false,
    security_attestation_present boolean    NOT NULL DEFAULT false,
    endorsed_by_user_id         uuid,
    approved_by_user_id         uuid,
    reenabled_scope             jsonb,
    reenabled_at                timestamptz,
    correlation_id              text        NOT NULL,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    version                     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ad_containment_pk PRIMARY KEY (id),
    CONSTRAINT ad_containment_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_containment_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_containment_account_fk
        FOREIGN KEY (marketplace_account_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT ad_containment_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_containment_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_containment_activator_fk
        FOREIGN KEY (activated_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_containment_endorser_fk
        FOREIGN KEY (endorsed_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_containment_approver_fk
        FOREIGN KEY (approved_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_containment_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_containment_kind_ck
        CHECK (containment_kind IN
            ('EMERGENCY_ENTITY_HOLD', 'ACTION_OUTCOME_QUARANTINE',
             'AUTHORITY_VERSION_QUARANTINE', 'CAPABILITY_QUARANTINED',
             'KILL_SWITCH_ACTIVE')),
    CONSTRAINT ad_containment_scope_ck
        CHECK (scope_kind IN
            ('ENTITY', 'AFFECTED_SET', 'AUTHORITY_VERSION',
             'PLATFORM_STORE_CAPABILITY', 'PLATFORM_ACCOUNT_CAPABILITY')),
    CONSTRAINT ad_containment_scope_shape_ck
        CHECK ((scope_kind = 'ENTITY' AND ad_native_object_id IS NOT NULL)
            OR (scope_kind = 'AFFECTED_SET' AND affected_set_digest IS NOT NULL)
            OR (scope_kind = 'AUTHORITY_VERSION' AND authority_version_reference IS NOT NULL)
            OR (scope_kind = 'PLATFORM_STORE_CAPABILITY'
                AND platform_code IS NOT NULL AND store_id IS NOT NULL
                AND capability_code IS NOT NULL)
            OR (scope_kind = 'PLATFORM_ACCOUNT_CAPABILITY'
                AND platform_code IS NOT NULL AND marketplace_account_id IS NOT NULL
                AND capability_code IS NOT NULL)),
    CONSTRAINT ad_containment_cause_ck
        CHECK (cause_class IN
            ('BUSINESS_HARM', 'OUTCOME_REGRESSION', 'EXECUTION_INTEGRITY',
             'AUTHORITY_VERSION_INVALID', 'PROVIDER_OR_READBACK_DEFECT',
             'CREDENTIAL_OR_SECURITY')),
    CONSTRAINT ad_containment_state_ck
        CHECK (state IN ('ACTIVE', 'REENABLEMENT_REVIEW', 'REENABLED')),
    -- A stop is attributable to a person or to a deterministic trigger, and to
    -- exactly one of them. AI inference is neither and can activate nothing.
    CONSTRAINT ad_containment_activator_ck
        CHECK (num_nonnulls(activated_by_user_id, activated_by_trigger) = 1),
    CONSTRAINT ad_containment_trigger_ck
        CHECK (activated_by_trigger IS NULL
            OR activated_by_trigger ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    -- Reenablement is never time-based and never unilateral. Every condition
    -- must hold, an endorser and an approver must both be present, and neither
    -- may be the person who activated the stop.
    CONSTRAINT ad_containment_reenablement_ck
        CHECK (state <> 'REENABLED'
            OR (root_cause_classified
                AND unknowns_resolved
                AND authorities_replaced
                AND results_reconciled
                AND capability_evidence_current
                AND endorsed_by_user_id IS NOT NULL
                AND approved_by_user_id IS NOT NULL
                AND reenabled_scope IS NOT NULL
                AND reenabled_at IS NOT NULL)),
    CONSTRAINT ad_containment_separation_ck
        CHECK (activated_by_user_id IS NULL
            OR (endorsed_by_user_id IS DISTINCT FROM activated_by_user_id
                AND approved_by_user_id IS DISTINCT FROM activated_by_user_id)),
    CONSTRAINT ad_containment_approval_separation_ck
        CHECK (approved_by_user_id IS NULL
            OR endorsed_by_user_id IS NULL
            OR approved_by_user_id <> endorsed_by_user_id),
    -- A technical or security cause additionally needs a platform or security
    -- attestation before anything restarts.
    CONSTRAINT ad_containment_attestation_ck
        CHECK (state <> 'REENABLED'
            OR cause_class NOT IN ('EXECUTION_INTEGRITY', 'PROVIDER_OR_READBACK_DEFECT',
                                   'CREDENTIAL_OR_SECURITY')
            OR security_attestation_present),
    CONSTRAINT ad_containment_scope_json_ck
        CHECK (reenabled_scope IS NULL OR jsonb_typeof(reenabled_scope) = 'object'),
    CONSTRAINT ad_containment_digest_ck
        CHECK (affected_set_digest IS NULL OR affected_set_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ad_containment_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_containment_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_containment_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX ad_containment_active_ix
    ON ops.ad_containment (organization_id, containment_kind, scope_kind)
    WHERE state <> 'REENABLED';
CREATE INDEX ad_containment_object_ix
    ON ops.ad_containment (ad_native_object_id)
    WHERE state <> 'REENABLED' AND ad_native_object_id IS NOT NULL;
CREATE INDEX ad_containment_capability_ix
    ON ops.ad_containment (organization_id, platform_code, capability_code)
    WHERE state <> 'REENABLED' AND capability_code IS NOT NULL;

-- Whether anything currently stops this object. Returns the containment kinds
-- so a refusal names what is holding it rather than saying only "blocked".
CREATE FUNCTION ops.ad_active_containment(
    p_organization_id uuid,
    p_object_id       uuid,
    p_store_id        uuid,
    p_platform_code   text,
    p_capability_code text,
    p_affected_digest text)
RETURNS text[]
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
    SELECT coalesce(array_agg(DISTINCT c.containment_kind ORDER BY c.containment_kind), '{}')
      FROM ops.ad_containment AS c
     WHERE c.organization_id = p_organization_id
       AND c.state <> 'REENABLED'
       AND (c.ad_native_object_id = p_object_id
            OR c.affected_set_digest = p_affected_digest
            OR (c.scope_kind = 'PLATFORM_STORE_CAPABILITY'
                AND c.platform_code = p_platform_code
                AND c.store_id = p_store_id
                AND c.capability_code = p_capability_code)
            OR (c.scope_kind = 'PLATFORM_ACCOUNT_CAPABILITY'
                AND c.platform_code = p_platform_code
                AND c.capability_code = p_capability_code
                AND c.marketplace_account_id =
                    (SELECT s.marketplace_account_id FROM core.store s WHERE s.id = p_store_id)))
$$;
REVOKE ALL ON FUNCTION ops.ad_active_containment(uuid, uuid, uuid, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_active_containment(uuid, uuid, uuid, text, text, text)
    TO marketops_app;

-- ---------------------------------------------------------------------------
-- The advertising decision policy bundle
-- ---------------------------------------------------------------------------

-- Twelve versioned authorities become production write authority together, or
-- not at all. The bundle references without re-owning: each column is a foreign
-- key to a version that some other table already governs, so activating a
-- bundle cannot change what a freshness profile means, only whether this scope
-- may act on it.
--
-- Scope is exact and narrow on purpose. A bundle names one organization, one
-- platform, one store, one capability, one direction, one candidate basis and
-- one native object kind, because Gate E enabled scope must never be broader
-- than the evidence that justified it, and a bundle that spanned several
-- directions would be a bundle whose evidence spanned none of them completely.
CREATE TABLE ops.ad_decision_policy_bundle (
    id                              uuid        NOT NULL,
    organization_id                 uuid        NOT NULL,
    bundle_version                  integer     NOT NULL,
    platform_code                   text        NOT NULL,
    marketplace_account_id          uuid        NOT NULL,
    store_id                        uuid        NOT NULL,
    capability_code                 text        NOT NULL,
    direction                       text        NOT NULL,
    candidate_basis                 text        NOT NULL,
    native_object_kind              text        NOT NULL,
    lifecycle_scope                 text        NOT NULL,
    semantic_profile_id             uuid        NOT NULL,
    conversion_definition_id        uuid        NOT NULL,
    allowable_cpa_definition_id     uuid        NOT NULL,
    qualification_policy_id         uuid        NOT NULL,
    target_policy_id                uuid        NOT NULL,
    outcome_policy_id               uuid,
    priority_policy_id              uuid        NOT NULL,
    human_slo_profile_id            uuid        NOT NULL,
    approval_lease_policy_id        uuid        NOT NULL,
    exposure_envelope_id            uuid        NOT NULL,
    materiality_policy_id           uuid        NOT NULL,
    ordinary_promotion_id           uuid,
    validation_state                text        NOT NULL,
    validation_failure_codes        text[]      NOT NULL DEFAULT '{}',
    activated_by_user_id            uuid,
    endorsed_by_user_id             uuid,
    approved_by_user_id             uuid,
    security_attestation_present    boolean     NOT NULL DEFAULT false,
    gate_scope_reference            text,
    effective_from                  timestamptz NOT NULL,
    effective_to                    timestamptz,
    status                          text        NOT NULL,
    reason                          text        NOT NULL,
    evidence_reference              text        NOT NULL,
    correlation_id                  text        NOT NULL,
    created_at                      timestamptz NOT NULL,
    updated_at                      timestamptz NOT NULL,
    version                         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ad_decision_policy_bundle_pk PRIMARY KEY (id),
    CONSTRAINT ad_decision_policy_bundle_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_decision_policy_bundle_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_decision_policy_bundle_account_fk
        FOREIGN KEY (marketplace_account_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_semantic_fk
        FOREIGN KEY (semantic_profile_id, platform_code)
        REFERENCES platform.ad_semantic_profile (id, platform_code),
    CONSTRAINT ad_decision_policy_bundle_conversion_fk
        FOREIGN KEY (conversion_definition_id, organization_id)
        REFERENCES core.ad_conversion_definition (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_cpa_fk
        FOREIGN KEY (allowable_cpa_definition_id, organization_id)
        REFERENCES core.ad_allowable_cpa_definition (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_qualification_fk
        FOREIGN KEY (qualification_policy_id, organization_id)
        REFERENCES core.ad_optimization_qualification_policy (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_target_fk
        FOREIGN KEY (target_policy_id, organization_id)
        REFERENCES core.ad_bid_target_policy (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_priority_fk
        FOREIGN KEY (priority_policy_id, organization_id)
        REFERENCES core.ad_priority_policy (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_slo_fk
        FOREIGN KEY (human_slo_profile_id, organization_id)
        REFERENCES core.ad_human_slo_profile (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_lease_fk
        FOREIGN KEY (approval_lease_policy_id, organization_id)
        REFERENCES core.ad_approval_lease_policy (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_exposure_fk
        FOREIGN KEY (exposure_envelope_id, organization_id)
        REFERENCES core.ad_exposure_envelope (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_materiality_fk
        FOREIGN KEY (materiality_policy_id, organization_id)
        REFERENCES core.ad_materiality_policy (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_activator_fk
        FOREIGN KEY (activated_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_endorser_fk
        FOREIGN KEY (endorsed_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_approver_fk
        FOREIGN KEY (approved_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_decision_policy_bundle_version_uq
        UNIQUE (organization_id, store_id, capability_code, direction,
                candidate_basis, native_object_kind, bundle_version),
    CONSTRAINT ad_decision_policy_bundle_version_ck CHECK (bundle_version >= 1),
    CONSTRAINT ad_decision_policy_bundle_capability_ck
        CHECK (capability_code = 'ad-bid-change'),
    CONSTRAINT ad_decision_policy_bundle_direction_ck
        CHECK (direction IN
            ('PROTECTION_DECREASE', 'OPTIMIZATION_INCREASE', 'EXACT_PRIOR_BID_COMPENSATION')),
    CONSTRAINT ad_decision_policy_bundle_basis_ck
        CHECK (candidate_basis IN ('MAX_CPC_BOUNDED', 'CAUSE_BOUND_PROTECTION_STEP')),
    CONSTRAINT ad_decision_policy_bundle_object_kind_ck
        CHECK (native_object_kind IN
            ('CAMPAIGN', 'AD_GROUP', 'TARGET', 'KEYWORD', 'PLACEMENT')),
    CONSTRAINT ad_decision_policy_bundle_lifecycle_ck
        CHECK (lifecycle_scope IN ('ALL', 'HERO', 'GROWTH', 'MATURE', 'REPAIR', 'EXIT')),
    CONSTRAINT ad_decision_policy_bundle_validation_ck
        CHECK (validation_state IN ('PENDING', 'VALIDATED', 'REJECTED')),
    CONSTRAINT ad_decision_policy_bundle_failures_ck
        CHECK ((validation_state = 'VALIDATED') = (cardinality(validation_failure_codes) = 0)),
    CONSTRAINT ad_decision_policy_bundle_status_ck
        CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED', 'REVOKED')),
    -- A bundle becomes production authority only when the whole combination
    -- validated and three different people agreed. An unvalidated ACTIVE bundle
    -- is the exact failure mode the whole-combination rule exists to prevent.
    CONSTRAINT ad_decision_policy_bundle_activation_ck
        CHECK (status <> 'ACTIVE'
            OR (validation_state = 'VALIDATED'
                AND activated_by_user_id IS NOT NULL
                AND endorsed_by_user_id IS NOT NULL
                AND approved_by_user_id IS NOT NULL
                AND gate_scope_reference IS NOT NULL)),
    CONSTRAINT ad_decision_policy_bundle_separation_ck
        CHECK (endorsed_by_user_id IS NULL OR approved_by_user_id IS NULL
            OR (endorsed_by_user_id <> approved_by_user_id
                AND endorsed_by_user_id IS DISTINCT FROM activated_by_user_id
                AND approved_by_user_id IS DISTINCT FROM activated_by_user_id)),
    -- An increase can only be bundled with the basis that supports it, which is
    -- the same rule the target policy carries, restated where the scope is
    -- decided so the two cannot disagree.
    CONSTRAINT ad_decision_policy_bundle_increase_basis_ck
        CHECK (direction <> 'OPTIMIZATION_INCREASE' OR candidate_basis = 'MAX_CPC_BOUNDED'),
    -- An outcome policy is required for any direction that can claim a business
    -- result. Compensation restores a number and claims none.
    CONSTRAINT ad_decision_policy_bundle_outcome_ck
        CHECK (direction = 'EXACT_PRIOR_BID_COMPENSATION' OR outcome_policy_id IS NOT NULL),
    CONSTRAINT ad_decision_policy_bundle_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_decision_policy_bundle_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_decision_policy_bundle_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_decision_policy_bundle_gate_ck
        CHECK (gate_scope_reference IS NULL
            OR length(btrim(gate_scope_reference)) BETWEEN 1 AND 256),
    CONSTRAINT ad_decision_policy_bundle_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128),
    -- One active bundle per exact decision scope and period. Two would make
    -- "the unique complete active bundle" a question with two answers, and the
    -- write path would have to choose.
    CONSTRAINT ad_decision_policy_bundle_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            store_id WITH =,
            capability_code WITH =,
            direction WITH =,
            candidate_basis WITH =,
            native_object_kind WITH =,
            lifecycle_scope WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE INDEX ad_decision_policy_bundle_resolve_ix
    ON ops.ad_decision_policy_bundle
       (organization_id, store_id, direction, candidate_basis, effective_from DESC)
    WHERE status = 'ACTIVE';

-- The whole-combination validation. It returns the failure codes rather than a
-- boolean so an activation refusal can say which of the twelve references is
-- inconsistent, and it is deliberately a function rather than a set of
-- constraints because every check here is about several rows at once.
CREATE FUNCTION ops.ad_bundle_validation_failures(p_bundle_id uuid)
RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, platform, pg_temp
AS $$
DECLARE
    bundle            ops.ad_decision_policy_bundle%ROWTYPE;
    conversion        core.ad_conversion_definition%ROWTYPE;
    cpa               core.ad_allowable_cpa_definition%ROWTYPE;
    target            core.ad_bid_target_policy%ROWTYPE;
    profile           platform.ad_semantic_profile%ROWTYPE;
    failures          text[] := '{}';
    freshness_gaps    text[];
BEGIN
    SELECT * INTO bundle FROM ops.ad_decision_policy_bundle WHERE id = p_bundle_id;
    IF NOT FOUND THEN
        RETURN ARRAY['BUNDLE_NOT_FOUND'];
    END IF;

    SELECT * INTO conversion FROM core.ad_conversion_definition
        WHERE id = bundle.conversion_definition_id;
    SELECT * INTO cpa FROM core.ad_allowable_cpa_definition
        WHERE id = bundle.allowable_cpa_definition_id;
    SELECT * INTO target FROM core.ad_bid_target_policy
        WHERE id = bundle.target_policy_id;
    SELECT * INTO profile FROM platform.ad_semantic_profile
        WHERE id = bundle.semantic_profile_id;

    -- Every referenced version must still be in force at the bundle's own start.
    IF conversion.status NOT IN ('ACTIVE', 'RETIRED')
        OR conversion.effective_from > bundle.effective_from
        OR (conversion.effective_to IS NOT NULL
            AND conversion.effective_to <= bundle.effective_from) THEN
        failures := failures || 'CONVERSION_DEFINITION_NOT_IN_FORCE';
    END IF;
    IF cpa.status NOT IN ('ACTIVE', 'RETIRED')
        OR cpa.effective_from > bundle.effective_from
        OR (cpa.effective_to IS NOT NULL AND cpa.effective_to <= bundle.effective_from) THEN
        failures := failures || 'ALLOWABLE_CPA_DEFINITION_NOT_IN_FORCE';
    END IF;
    IF target.status NOT IN ('ACTIVE', 'RETIRED')
        OR target.effective_from > bundle.effective_from
        OR (target.effective_to IS NOT NULL AND target.effective_to <= bundle.effective_from) THEN
        failures := failures || 'TARGET_POLICY_NOT_IN_FORCE';
    END IF;

    -- The stage rule, checked where the two definitions meet. This is the
    -- combination that produces a Max CPC, and a mismatch here is the one the
    -- Contract cares most about.
    IF conversion.sale_stage IS DISTINCT FROM cpa.sale_stage THEN
        failures := failures || 'CONVERSION_AND_ALLOWABLE_CPA_STAGE_MISMATCH';
    END IF;
    IF conversion.sale_stage = 'PROVIDER_NATIVE_OBSERVATION' THEN
        failures := failures || 'CONVERSION_STAGE_IS_PROVIDER_OBSERVATION';
    END IF;

    -- The target policy must be the one this bundle's scope actually describes.
    IF target.direction IS DISTINCT FROM bundle.direction
        OR target.candidate_basis IS DISTINCT FROM bundle.candidate_basis
        OR target.native_object_kind IS DISTINCT FROM bundle.native_object_kind THEN
        failures := failures || 'TARGET_POLICY_SCOPE_MISMATCH';
    END IF;

    -- Provider semantics must be able to express an exact bid at all. An
    -- unverified profile is permitted for Shadow and diagnosis; a bundle that
    -- authorizes a write is not one of those.
    IF profile.verification_state <> 'VERIFIED'
        OR profile.source_maturity = 'SYNTHETIC_FIXTURE'
        OR NOT profile.bid_field_present
        OR profile.bid_step IS NULL
        OR profile.bid_precision IS NULL
        OR profile.readback_semantics IN ('NOT_AVAILABLE', 'UNKNOWN') THEN
        failures := failures || 'SEMANTIC_PROFILE_NOT_WRITE_CAPABLE';
    END IF;

    -- Purpose monotonicity, delegated to the authority that owns it.
    freshness_gaps := core.ad_freshness_purpose_violations(
        bundle.organization_id, bundle.effective_from);
    IF cardinality(freshness_gaps) > 0 THEN
        failures := failures || 'FRESHNESS_PURPOSE_MONOTONICITY_VIOLATED';
    END IF;
    IF NOT core.ad_qualification_tier_is_monotonic(
            bundle.organization_id, 'ORGANIZATION', NULL, NULL, bundle.effective_from) THEN
        failures := failures || 'QUALIFICATION_TIER_MONOTONICITY_VIOLATED';
    END IF;

    -- An ordinary route only exists where a promotion record says it does. This
    -- Slice creates no promotion record, so a bundle claiming one fails here.
    IF bundle.ordinary_promotion_id IS NOT NULL THEN
        failures := failures || 'ORDINARY_ROUTE_PROMOTION_NOT_RECOGNISED';
    END IF;

    RETURN failures;
END;
$$;
REVOKE ALL ON FUNCTION ops.ad_bundle_validation_failures(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_bundle_validation_failures(uuid) TO marketops_app;

-- An ACTIVE bundle must have validated, and the validation is re-run here rather
-- than trusted from the column, so a row that was edited into ACTIVE without
-- revalidating is refused at the moment it is written.
CREATE FUNCTION ops.ad_bundle_activation_is_validated()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, core, platform, pg_temp
AS $$
DECLARE failures text[];
BEGIN
    IF NEW.status <> 'ACTIVE' THEN
        RETURN NEW;
    END IF;
    failures := ops.ad_bundle_validation_failures(NEW.id);
    IF cardinality(failures) > 0 THEN
        RAISE EXCEPTION 'advertising decision policy bundle is not coherent: %',
            array_to_string(failures, ',') USING ERRCODE = 'MO099';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION ops.ad_bundle_activation_is_validated() FROM PUBLIC;

CREATE TRIGGER ad_decision_policy_bundle_activation_bu
    AFTER INSERT OR UPDATE ON ops.ad_decision_policy_bundle
    FOR EACH ROW EXECUTE FUNCTION ops.ad_bundle_activation_is_validated();

-- ---------------------------------------------------------------------------
-- Control routing and privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'ad_action_reservation', 'NO_ROUTE', NULL,
        'affected-set observation reservation; blocks overlap, transmits nothing'),
    ('core', 'ad_exposure_envelope', 'NO_ROUTE', NULL,
        'versioned non-compensating aggregate exposure bounds with reserved recovery headroom'),
    ('ops', 'ad_containment', 'NO_ROUTE', NULL,
        'hold, quarantine and kill state with cause-specific multi-party reenablement'),
    ('ops', 'ad_decision_policy_bundle', 'NO_ROUTE', NULL,
        'scope-bound whole-combination-validated activation record; re-owns no domain fact');

GRANT SELECT, INSERT, UPDATE ON ops.ad_action_reservation TO marketops_app;
GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_exposure_envelope TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ad_containment TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ad_decision_policy_bundle TO marketops_app;
