-- What a bid change may be, who has to agree to it, and the governed path a
-- person uses when no verified API exists.
--
-- Three things in this file are the difference between a bounded change and an
-- unbounded one.
--
-- First, the target is chosen before anyone approves it. A finite deterministic
-- candidate set is generated from a versioned policy, normalized to the
-- provider's own unit and step, and frozen. The operator selects or rejects a
-- generated candidate; there is no column anywhere that could hold a free-typed
-- number, which is why "the operator typed a different value" is not a failure
-- mode this system has.
--
-- Second, materiality is multi-axis and non-compensating. Any hard trigger is
-- sufficient, and low exposure on one axis cannot offset a breach on another.
-- The initial ordinary nonzero envelope is zero, which is expressed here as a
-- policy whose ordinary band is empty rather than as a constant in code, so
-- widening it later is a reviewed data change with an owner and an effective
-- period.
--
-- Third, the manual path is complete and is not a hidden API. A Manual Execution
-- Packet binds everything a controlled command binds, and its configuration
-- evidence is graded with no promotion between grades: an executor's own report
-- stays ACTION_REPORTED_CONFIGURATION_UNVERIFIED no matter how confident it
-- sounds, and an independent human verification proves the console state it
-- actually observed and never API idempotency or exact application time.
--
-- A confirmed or uncertain manual action consumes the same affected-set
-- reservation and the same aggregate exposure as a controlled write, because
-- the real advertising environment changed either way. That reservation lives
-- in a later migration; this one records the packet and its evidence.

-- ---------------------------------------------------------------------------
-- The bid target policy
-- ---------------------------------------------------------------------------

-- The versioned authority behind every candidate. It separates three things the
-- Contract insists are separate: the write-grade economic ceiling, the
-- direction-specific change envelope, and the finite candidate set the operator
-- may choose from.
--
-- cause_bound_step_enabled is the switch behind CAUSE_BOUND_PROTECTION_STEP. It
-- is off unless a policy owner turned it on, because a bounded protection step
-- taken without a Max CPC proves nothing about profitability and must never be
-- available by default.
CREATE TABLE core.ad_bid_target_policy (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    policy_version              integer        NOT NULL,
    scope_kind                  text           NOT NULL,
    platform_code               text,
    store_ref_id                uuid,
    native_object_kind          text           NOT NULL,
    direction                   text           NOT NULL,
    candidate_basis             text           NOT NULL,
    candidate_count             integer        NOT NULL,
    max_relative_change_ratio   numeric(6, 5)  NOT NULL,
    max_absolute_change_amount  numeric(18, 4) NOT NULL,
    currency_code               text           NOT NULL,
    ceiling_headroom_ratio      numeric(6, 5),
    cause_bound_step_enabled    boolean        NOT NULL DEFAULT false,
    cause_bound_step_ratio      numeric(6, 5),
    cause_bound_causes          text[]         NOT NULL DEFAULT '{}',
    owner_user_id               uuid           NOT NULL,
    reason                      text           NOT NULL,
    evidence_reference          text           NOT NULL,
    effective_from              timestamptz    NOT NULL,
    effective_to                timestamptz,
    status                      text           NOT NULL,
    created_at                  timestamptz    NOT NULL,
    CONSTRAINT ad_bid_target_policy_pk PRIMARY KEY (id),
    CONSTRAINT ad_bid_target_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_bid_target_policy_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_bid_target_policy_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_bid_target_policy_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_bid_target_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_bid_target_policy_version_uq
        UNIQUE (organization_id, direction, candidate_basis, native_object_kind, policy_version),
    CONSTRAINT ad_bid_target_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT ad_bid_target_policy_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT ad_bid_target_policy_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION'
                AND platform_code IS NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'PLATFORM'
                AND platform_code IS NOT NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'STORE'
                AND platform_code IS NOT NULL AND store_ref_id IS NOT NULL)),
    CONSTRAINT ad_bid_target_policy_object_kind_ck
        CHECK (native_object_kind IN
            ('CAMPAIGN', 'AD_GROUP', 'TARGET', 'KEYWORD', 'PLACEMENT')),
    CONSTRAINT ad_bid_target_policy_direction_ck
        CHECK (direction IN
            ('PROTECTION_DECREASE', 'OPTIMIZATION_INCREASE', 'EXACT_PRIOR_BID_COMPENSATION')),
    CONSTRAINT ad_bid_target_policy_basis_ck
        CHECK (candidate_basis IN ('MAX_CPC_BOUNDED', 'CAUSE_BOUND_PROTECTION_STEP')),
    -- An increase can only ever rest on a real economic ceiling. A bounded
    -- protection step exists because no ceiling could be computed, and using it
    -- to justify spending more would be exactly backwards.
    CONSTRAINT ad_bid_target_policy_increase_basis_ck
        CHECK (direction <> 'OPTIMIZATION_INCREASE' OR candidate_basis = 'MAX_CPC_BOUNDED'),
    -- Compensation restores a captured number and generates no candidates at all.
    CONSTRAINT ad_bid_target_policy_compensation_ck
        CHECK (direction <> 'EXACT_PRIOR_BID_COMPENSATION' OR candidate_count = 0),
    CONSTRAINT ad_bid_target_policy_candidate_count_ck
        CHECK (candidate_count BETWEEN 0 AND 8),
    CONSTRAINT ad_bid_target_policy_change_bounds_ck
        CHECK (max_relative_change_ratio > 0 AND max_relative_change_ratio <= 1
            AND max_absolute_change_amount > 0),
    CONSTRAINT ad_bid_target_policy_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_bid_target_policy_headroom_ck
        CHECK (ceiling_headroom_ratio IS NULL
            OR (ceiling_headroom_ratio > 0 AND ceiling_headroom_ratio <= 1)),
    -- A MAX_CPC_BOUNDED policy must say how far below the ceiling a candidate
    -- stops. Without it "bounded" would mean "equal to the ceiling", which
    -- leaves no margin for the estimate error the ceiling itself carries.
    CONSTRAINT ad_bid_target_policy_bounded_headroom_ck
        CHECK (candidate_basis <> 'MAX_CPC_BOUNDED'
            OR direction = 'EXACT_PRIOR_BID_COMPENSATION'
            OR ceiling_headroom_ratio IS NOT NULL),
    -- The cause-bound step is a decrease, is off unless enabled, and must name
    -- the exact versioned causes it answers.
    CONSTRAINT ad_bid_target_policy_cause_bound_direction_ck
        CHECK (candidate_basis <> 'CAUSE_BOUND_PROTECTION_STEP'
            OR direction = 'PROTECTION_DECREASE'),
    CONSTRAINT ad_bid_target_policy_cause_bound_shape_ck
        CHECK (NOT cause_bound_step_enabled
            OR (candidate_basis = 'CAUSE_BOUND_PROTECTION_STEP'
                AND cause_bound_step_ratio IS NOT NULL
                AND cardinality(cause_bound_causes) >= 1)),
    CONSTRAINT ad_bid_target_policy_cause_bound_ratio_ck
        CHECK (cause_bound_step_ratio IS NULL
            OR (cause_bound_step_ratio > 0 AND cause_bound_step_ratio < 1)),
    CONSTRAINT ad_bid_target_policy_causes_ck
        CHECK (cardinality(cause_bound_causes) BETWEEN 0 AND 16
            AND array_position(cause_bound_causes, NULL) IS NULL),
    CONSTRAINT ad_bid_target_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_bid_target_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_bid_target_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_bid_target_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_bid_target_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            native_object_kind WITH =,
            direction WITH =,
            candidate_basis WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE INDEX ad_bid_target_policy_resolve_ix
    ON core.ad_bid_target_policy
       (organization_id, direction, candidate_basis, effective_from DESC);

-- ---------------------------------------------------------------------------
-- Materiality
-- ---------------------------------------------------------------------------

-- Multi-axis and non-compensating. Every threshold is a hard trigger on its own
-- axis; there is deliberately no weighting between them and no total to
-- compare against, because a total is how a large exposure on one axis gets
-- paid for by a small one on another.
--
-- ordinary_nonzero_envelope_amount is the Contract's initial zero made
-- explicit. A policy may set it to zero and thereby make every nonzero command
-- Material, which is the initial operating state, and the check below refuses a
-- nonzero envelope unless an Ordinary-route promotion exists to justify it.
CREATE TABLE core.ad_materiality_policy (
    id                                  uuid           NOT NULL,
    organization_id                     uuid           NOT NULL,
    policy_version                      integer        NOT NULL,
    scope_kind                          text           NOT NULL,
    platform_code                       text,
    store_ref_id                        uuid,
    currency_code                       text           NOT NULL,
    ordinary_nonzero_envelope_amount    numeric(18, 4) NOT NULL,
    ordinary_relative_envelope_ratio    numeric(6, 5)  NOT NULL,
    material_absolute_change_amount     numeric(18, 4) NOT NULL,
    material_relative_change_ratio      numeric(6, 5)  NOT NULL,
    material_spend_exposure_amount      numeric(18, 4) NOT NULL,
    material_affected_variant_count     integer        NOT NULL,
    material_critical_sales_amount      numeric(18, 4) NOT NULL,
    material_cumulative_change_amount   numeric(18, 4) NOT NULL,
    material_cumulative_window_hours    integer        NOT NULL,
    regression_always_material          boolean        NOT NULL DEFAULT true,
    quarantine_always_material          boolean        NOT NULL DEFAULT true,
    unknown_state_always_material       boolean        NOT NULL DEFAULT true,
    compensation_always_material        boolean        NOT NULL DEFAULT true,
    owner_user_id                       uuid           NOT NULL,
    reason                              text           NOT NULL,
    evidence_reference                  text           NOT NULL,
    effective_from                      timestamptz    NOT NULL,
    effective_to                        timestamptz,
    status                              text           NOT NULL,
    created_at                          timestamptz    NOT NULL,
    CONSTRAINT ad_materiality_policy_pk PRIMARY KEY (id),
    CONSTRAINT ad_materiality_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_materiality_policy_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_materiality_policy_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_materiality_policy_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_materiality_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_materiality_policy_version_uq UNIQUE (organization_id, policy_version),
    CONSTRAINT ad_materiality_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT ad_materiality_policy_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT ad_materiality_policy_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION'
                AND platform_code IS NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'PLATFORM'
                AND platform_code IS NOT NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'STORE'
                AND platform_code IS NOT NULL AND store_ref_id IS NOT NULL)),
    CONSTRAINT ad_materiality_policy_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_materiality_policy_envelope_ck
        CHECK (ordinary_nonzero_envelope_amount >= 0
            AND ordinary_relative_envelope_ratio >= 0
            AND ordinary_relative_envelope_ratio <= 1),
    CONSTRAINT ad_materiality_policy_thresholds_ck
        CHECK (material_absolute_change_amount > 0
            AND material_relative_change_ratio > 0
            AND material_relative_change_ratio <= 1
            AND material_spend_exposure_amount >= 0
            AND material_affected_variant_count >= 1
            AND material_critical_sales_amount >= 0
            AND material_cumulative_change_amount > 0),
    CONSTRAINT ad_materiality_policy_window_ck
        CHECK (material_cumulative_window_hours BETWEEN 1 AND 8760),
    -- An ordinary envelope cannot be wider than the material threshold it sits
    -- below, or the two would overlap and a command could be both.
    CONSTRAINT ad_materiality_policy_envelope_below_material_ck
        CHECK (ordinary_nonzero_envelope_amount < material_absolute_change_amount
            AND ordinary_relative_envelope_ratio < material_relative_change_ratio),
    -- The four fixed triggers stay on. A policy that could turn off "a
    -- regression is always material" would be a policy that could approve a
    -- change into a known-broken state on an operations lead's signature alone.
    CONSTRAINT ad_materiality_policy_fixed_triggers_ck
        CHECK (regression_always_material
            AND quarantine_always_material
            AND unknown_state_always_material
            AND compensation_always_material),
    CONSTRAINT ad_materiality_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_materiality_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_materiality_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_materiality_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_materiality_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- Approval lease
-- ---------------------------------------------------------------------------

-- How long an executable approval stays executable. The Contract's rule is that
-- the effective expiry is the earliest of several bounds, and this table holds
-- only the scoped lease bound; the others come from the evidence, the policy
-- versions, the credential and the gate, and the earliest is taken at every
-- recheck point.
--
-- renewable is a column so it can be read, and a check so it can only ever be
-- false. An approval that could be extended would let a change approved against
-- Tuesday's evidence execute against Thursday's.
CREATE TABLE core.ad_approval_lease_policy (
    id                       uuid        NOT NULL,
    organization_id          uuid        NOT NULL,
    policy_version           integer     NOT NULL,
    scope_kind               text        NOT NULL,
    platform_code            text,
    store_ref_id             uuid,
    direction                text        NOT NULL,
    lease_seconds            integer     NOT NULL,
    material_lease_seconds   integer     NOT NULL,
    renewable                boolean     NOT NULL DEFAULT false,
    owner_user_id            uuid        NOT NULL,
    reason                   text        NOT NULL,
    evidence_reference       text        NOT NULL,
    effective_from           timestamptz NOT NULL,
    effective_to             timestamptz,
    status                   text        NOT NULL,
    created_at               timestamptz NOT NULL,
    CONSTRAINT ad_approval_lease_policy_pk PRIMARY KEY (id),
    CONSTRAINT ad_approval_lease_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_approval_lease_policy_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_approval_lease_policy_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_approval_lease_policy_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_approval_lease_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_approval_lease_policy_version_uq
        UNIQUE (organization_id, direction, policy_version),
    CONSTRAINT ad_approval_lease_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT ad_approval_lease_policy_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT ad_approval_lease_policy_direction_ck
        CHECK (direction IN
            ('PROTECTION_DECREASE', 'OPTIMIZATION_INCREASE', 'EXACT_PRIOR_BID_COMPENSATION')),
    -- Fifteen minutes to eight hours. Shorter than a shift so an approval cannot
    -- outlive the person who gave it; longer than a page load so an operator is
    -- not fighting the clock.
    CONSTRAINT ad_approval_lease_policy_seconds_ck
        CHECK (lease_seconds BETWEEN 900 AND 28800
            AND material_lease_seconds BETWEEN 900 AND 28800),
    -- A material change gets no more time than an ordinary one.
    CONSTRAINT ad_approval_lease_policy_material_ck
        CHECK (material_lease_seconds <= lease_seconds),
    CONSTRAINT ad_approval_lease_policy_renewable_ck CHECK (NOT renewable),
    CONSTRAINT ad_approval_lease_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_approval_lease_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_approval_lease_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_approval_lease_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_approval_lease_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            direction WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- The generated candidate set
-- ---------------------------------------------------------------------------

-- Append-only, one row per generated candidate, bound to the exact case, target
-- policy version, semantic profile and affected-set digest they were generated
-- against. An operator selects one of these by id; there is no path that accepts
-- a value.
--
-- provider_normalized_amount is the number that would actually be sent. It is
-- computed here, before Preview and before Approval, precisely so an adapter can
-- never round at runtime: the approved value and the transmitted value are the
-- same row.
CREATE TABLE ops.ad_bid_candidate (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    case_id                     uuid           NOT NULL,
    ad_native_object_id         uuid           NOT NULL,
    affected_set_digest         text           NOT NULL,
    target_policy_id            uuid           NOT NULL,
    target_policy_version       integer        NOT NULL,
    semantic_profile_id         uuid           NOT NULL,
    direction                   text           NOT NULL,
    candidate_basis             text           NOT NULL,
    ordinal                     integer        NOT NULL,
    current_bid_amount          numeric(18, 4) NOT NULL,
    requested_amount            numeric(18, 4) NOT NULL,
    provider_normalized_amount  numeric(18, 4) NOT NULL,
    currency_code               text           NOT NULL,
    bid_unit_code               text           NOT NULL,
    max_cpc_amount              numeric(18, 4),
    max_cpc_absence_reason      text,
    cause_code                  text,
    generated_at                timestamptz    NOT NULL,
    correlation_id              text           NOT NULL,
    CONSTRAINT ad_bid_candidate_pk PRIMARY KEY (id),
    CONSTRAINT ad_bid_candidate_case_fk
        FOREIGN KEY (case_id, organization_id)
        REFERENCES mart.ad_case (id, organization_id),
    CONSTRAINT ad_bid_candidate_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_bid_candidate_policy_fk
        FOREIGN KEY (target_policy_id, organization_id)
        REFERENCES core.ad_bid_target_policy (id, organization_id),
    CONSTRAINT ad_bid_candidate_profile_fk
        FOREIGN KEY (semantic_profile_id)
        REFERENCES platform.ad_semantic_profile (id),
    CONSTRAINT ad_bid_candidate_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_bid_candidate_ordinal_uq UNIQUE (case_id, direction, ordinal),
    CONSTRAINT ad_bid_candidate_digest_ck CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ad_bid_candidate_direction_ck
        CHECK (direction IN
            ('PROTECTION_DECREASE', 'OPTIMIZATION_INCREASE', 'EXACT_PRIOR_BID_COMPENSATION')),
    CONSTRAINT ad_bid_candidate_basis_ck
        CHECK (candidate_basis IN ('MAX_CPC_BOUNDED', 'CAUSE_BOUND_PROTECTION_STEP')),
    CONSTRAINT ad_bid_candidate_ordinal_ck CHECK (ordinal BETWEEN 1 AND 8),
    CONSTRAINT ad_bid_candidate_amounts_ck
        CHECK (current_bid_amount >= 0 AND requested_amount >= 0
            AND provider_normalized_amount >= 0),
    -- A candidate that does not change anything is not a candidate.
    CONSTRAINT ad_bid_candidate_change_ck
        CHECK (provider_normalized_amount <> current_bid_amount),
    -- The direction on the row and the direction of the arithmetic must agree,
    -- so a decrease cannot be recorded as an increase by a caller that got the
    -- sign wrong.
    CONSTRAINT ad_bid_candidate_direction_agrees_ck
        CHECK ((direction = 'OPTIMIZATION_INCREASE')
                = (provider_normalized_amount > current_bid_amount)
            OR direction = 'EXACT_PRIOR_BID_COMPENSATION'),
    CONSTRAINT ad_bid_candidate_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_bid_candidate_unit_ck
        CHECK (bid_unit_code IN ('CURRENCY_MAJOR', 'CURRENCY_MINOR')),
    -- A MAX_CPC_BOUNDED candidate carries the ceiling it was bounded by; a
    -- cause-bound one carries the exact cause it answers and the reason no
    -- ceiling existed. Neither may be silent about why it is the number it is.
    CONSTRAINT ad_bid_candidate_bounded_shape_ck
        CHECK (candidate_basis <> 'MAX_CPC_BOUNDED'
            OR (max_cpc_amount IS NOT NULL AND max_cpc_absence_reason IS NULL)),
    CONSTRAINT ad_bid_candidate_cause_bound_shape_ck
        CHECK (candidate_basis <> 'CAUSE_BOUND_PROTECTION_STEP'
            OR (max_cpc_amount IS NULL
                AND max_cpc_absence_reason IS NOT NULL
                AND cause_code IS NOT NULL
                AND direction = 'PROTECTION_DECREASE')),
    -- An increase may never exceed the ceiling it claims to respect.
    CONSTRAINT ad_bid_candidate_ceiling_ck
        CHECK (direction <> 'OPTIMIZATION_INCREASE'
            OR (max_cpc_amount IS NOT NULL
                AND provider_normalized_amount <= max_cpc_amount)),
    CONSTRAINT ad_bid_candidate_absence_reason_ck
        CHECK (max_cpc_absence_reason IS NULL
            OR max_cpc_absence_reason IN
                ('STAGE_MISMATCH', 'CONVERSION_NOT_WRITE_GRADE',
                 'ALLOWABLE_CPA_UNRESOLVED', 'CONVERSION_ZERO')),
    CONSTRAINT ad_bid_candidate_cause_shape_ck
        CHECK (cause_code IS NULL OR cause_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT ad_bid_candidate_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX ad_bid_candidate_case_ix
    ON ops.ad_bid_candidate (case_id, generated_at DESC);

-- ---------------------------------------------------------------------------
-- The governed manual execution packet
-- ---------------------------------------------------------------------------

-- Everything a controlled command binds, for an action a person performs in the
-- marketplace's own console. action_kind is wider than AD_BID_CHANGE on purpose:
-- the Contract permits a governed manual Budget, Pause/Resume or other modelled
-- action where MarketOps has a deterministic recommendation and the actor has
-- legal platform authority. What it does not permit is any of those becoming an
-- API path, which is why none of them appears in the capability registry.
CREATE TABLE ops.ad_manual_execution_packet (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    case_id                     uuid           NOT NULL,
    ad_native_object_id         uuid           NOT NULL,
    store_id                    uuid           NOT NULL,
    platform_code               text           NOT NULL,
    affected_set_id             uuid           NOT NULL,
    affected_set_digest         text           NOT NULL,
    semantic_profile_id         uuid           NOT NULL,
    action_kind                 text           NOT NULL,
    observed_configuration_id   uuid           NOT NULL,
    intended_state              jsonb          NOT NULL,
    reason                      text           NOT NULL,
    evidence_reference          text           NOT NULL,
    guardrail_evaluation_id     uuid,
    blocker_codes               text[]         NOT NULL DEFAULT '{}',
    maker_user_id               uuid           NOT NULL,
    endorser_user_id            uuid,
    approver_user_id            uuid,
    bundle_id                   uuid,
    expected_impact             jsonb          NOT NULL,
    verification_plan           jsonb          NOT NULL,
    state                       text           NOT NULL,
    issued_at                   timestamptz    NOT NULL,
    expires_at                  timestamptz    NOT NULL,
    revoked_at                  timestamptz,
    revoked_reason              text,
    correlation_id              text           NOT NULL,
    created_at                  timestamptz    NOT NULL,
    updated_at                  timestamptz    NOT NULL,
    version                     bigint         NOT NULL DEFAULT 0,
    CONSTRAINT ad_manual_execution_packet_pk PRIMARY KEY (id),
    CONSTRAINT ad_manual_execution_packet_case_fk
        FOREIGN KEY (case_id, organization_id)
        REFERENCES mart.ad_case (id, organization_id),
    CONSTRAINT ad_manual_execution_packet_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_manual_execution_packet_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_manual_execution_packet_affected_set_fk
        FOREIGN KEY (affected_set_id, organization_id)
        REFERENCES core.ad_affected_set (id, organization_id),
    CONSTRAINT ad_manual_execution_packet_profile_fk
        FOREIGN KEY (semantic_profile_id, platform_code)
        REFERENCES platform.ad_semantic_profile (id, platform_code),
    CONSTRAINT ad_manual_execution_packet_configuration_fk
        FOREIGN KEY (observed_configuration_id, organization_id)
        REFERENCES core.ad_object_configuration_observation (id, organization_id),
    CONSTRAINT ad_manual_execution_packet_maker_fk
        FOREIGN KEY (maker_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_manual_execution_packet_endorser_fk
        FOREIGN KEY (endorser_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_manual_execution_packet_approver_fk
        FOREIGN KEY (approver_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_manual_execution_packet_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_manual_execution_packet_digest_ck
        CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    -- The modelled manual actions. AD_BID_CHANGE is here because a person may
    -- lawfully perform it by hand when no verified API exists; the others are
    -- here because the Contract permits governed manual execution of them and
    -- forbids an API path for them.
    CONSTRAINT ad_manual_execution_packet_action_ck
        CHECK (action_kind IN
            ('AD_BID_CHANGE', 'AD_BUDGET_CHANGE', 'AD_STATUS_CHANGE')),
    CONSTRAINT ad_manual_execution_packet_state_ck
        CHECK (state IN
            ('MANUAL_PACKET_ISSUED', 'MANUAL_PACKET_REVOKED',
             'ACTION_REPORTED_CONFIGURATION_UNVERIFIED',
             'MANUAL_CONFIGURATION_VERIFIED', 'MANUAL_EXECUTION_UNCERTAIN',
             'MANUAL_PACKET_EXPIRED')),
    CONSTRAINT ad_manual_execution_packet_json_ck
        CHECK (jsonb_typeof(intended_state) = 'object'
            AND jsonb_typeof(expected_impact) = 'object'
            AND jsonb_typeof(verification_plan) = 'object'),
    -- A packet without a verification plan is a request to change something
    -- with no way of ever knowing whether it changed.
    CONSTRAINT ad_manual_execution_packet_verification_ck
        CHECK (verification_plan ? 'evidenceGrade'),
    CONSTRAINT ad_manual_execution_packet_expiry_ck CHECK (expires_at > issued_at),
    CONSTRAINT ad_manual_execution_packet_revoked_ck
        CHECK ((state = 'MANUAL_PACKET_REVOKED')
            = (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL)),
    -- The maker never endorses or approves their own packet. This is the manual
    -- half of the same separation the controlled command enforces.
    CONSTRAINT ad_manual_execution_packet_separation_ck
        CHECK ((endorser_user_id IS NULL OR endorser_user_id <> maker_user_id)
            AND (approver_user_id IS NULL OR approver_user_id <> maker_user_id)
            AND (approver_user_id IS NULL OR endorser_user_id IS NULL
                 OR approver_user_id <> endorser_user_id)),
    CONSTRAINT ad_manual_execution_packet_blockers_ck
        CHECK (cardinality(blocker_codes) BETWEEN 0 AND 64
            AND array_position(blocker_codes, NULL) IS NULL),
    CONSTRAINT ad_manual_execution_packet_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_manual_execution_packet_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_manual_execution_packet_revoked_reason_ck
        CHECK (revoked_reason IS NULL
            OR length(btrim(revoked_reason)) BETWEEN 1 AND 512),
    CONSTRAINT ad_manual_execution_packet_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

-- One live packet per object. A second concurrent manual instruction for the
-- same advertising object is exactly the ambiguity the reservation exists to
-- prevent.
CREATE UNIQUE INDEX ad_manual_execution_packet_live_uq
    ON ops.ad_manual_execution_packet (ad_native_object_id)
    WHERE state IN ('MANUAL_PACKET_ISSUED', 'ACTION_REPORTED_CONFIGURATION_UNVERIFIED',
                    'MANUAL_EXECUTION_UNCERTAIN');
CREATE INDEX ad_manual_execution_packet_case_ix
    ON ops.ad_manual_execution_packet (case_id, issued_at DESC);

-- What somebody actually saw. The grade is the whole content of this table: an
-- executor's self-report and an independent verifier's observation are both
-- recorded, and only the second is ever treated as evidence about configuration.
--
-- verifier_user_id <> executor_user_id is the check that makes "independent"
-- mean independent rather than aspirational.
CREATE TABLE ops.ad_manual_configuration_verification (
    id                       uuid           NOT NULL,
    organization_id          uuid           NOT NULL,
    packet_id                uuid           NOT NULL,
    evidence_grade           text           NOT NULL,
    executor_user_id         uuid           NOT NULL,
    verifier_user_id         uuid,
    observed_field_path      text           NOT NULL,
    observed_value           text           NOT NULL,
    observed_at              timestamptz    NOT NULL,
    evidence_reference       text           NOT NULL,
    conflict_state           text           NOT NULL,
    proves_configuration     boolean        NOT NULL,
    recorded_at              timestamptz    NOT NULL,
    correlation_id           text           NOT NULL,
    CONSTRAINT ad_manual_configuration_verification_pk PRIMARY KEY (id),
    CONSTRAINT ad_manual_configuration_verification_packet_fk
        FOREIGN KEY (packet_id, organization_id)
        REFERENCES ops.ad_manual_execution_packet (id, organization_id),
    CONSTRAINT ad_manual_configuration_verification_executor_fk
        FOREIGN KEY (executor_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_manual_configuration_verification_verifier_fk
        FOREIGN KEY (verifier_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_manual_configuration_verification_grade_ck
        CHECK (evidence_grade IN
            ('OFFICIAL_API_READBACK', 'OFFICIAL_CONFIGURATION_EXPORT',
             'INDEPENDENT_MANUAL_VERIFICATION', 'EXECUTOR_SELF_REPORT')),
    -- Independence is a fact about two different people, not a claim.
    CONSTRAINT ad_manual_configuration_verification_independent_ck
        CHECK (evidence_grade <> 'INDEPENDENT_MANUAL_VERIFICATION'
            OR (verifier_user_id IS NOT NULL AND verifier_user_id <> executor_user_id)),
    -- A self-report has no verifier and proves nothing about configuration. This
    -- is the promotion the Contract forbids, refused in the schema so no service
    -- can perform it.
    CONSTRAINT ad_manual_configuration_verification_self_report_ck
        CHECK (evidence_grade <> 'EXECUTOR_SELF_REPORT'
            OR (verifier_user_id IS NULL AND NOT proves_configuration)),
    CONSTRAINT ad_manual_configuration_verification_proof_ck
        CHECK (NOT proves_configuration
            OR evidence_grade IN ('OFFICIAL_API_READBACK', 'OFFICIAL_CONFIGURATION_EXPORT',
                                  'INDEPENDENT_MANUAL_VERIFICATION')),
    -- A conflicted or superseded observation proves nothing either.
    CONSTRAINT ad_manual_configuration_verification_conflict_ck
        CHECK (conflict_state IN ('NONE', 'CONFLICTED', 'SUPERSEDED_BY_LATER_CHANGE')),
    CONSTRAINT ad_manual_configuration_verification_conflict_proof_ck
        CHECK (conflict_state = 'NONE' OR NOT proves_configuration),
    CONSTRAINT ad_manual_configuration_verification_field_ck
        CHECK (length(btrim(observed_field_path)) BETWEEN 1 AND 256),
    CONSTRAINT ad_manual_configuration_verification_value_ck
        CHECK (length(btrim(observed_value)) BETWEEN 1 AND 512),
    CONSTRAINT ad_manual_configuration_verification_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_manual_configuration_verification_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX ad_manual_configuration_verification_packet_ix
    ON ops.ad_manual_configuration_verification (packet_id, observed_at DESC);

-- ---------------------------------------------------------------------------
-- Control routing and privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'ad_bid_target_policy', 'NO_ROUTE', NULL,
        'versioned deterministic candidate authority; generates targets, transmits nothing'),
    ('core', 'ad_materiality_policy', 'NO_ROUTE', NULL,
        'versioned multi-axis non-compensating materiality thresholds'),
    ('core', 'ad_approval_lease_policy', 'NO_ROUTE', NULL,
        'versioned non-renewable approval lease bound; one of several expiry bounds'),
    ('ops', 'ad_bid_candidate', 'NO_ROUTE', NULL,
        'append-only generated candidate set; provider-normalized before approval'),
    ('ops', 'ad_manual_execution_packet', 'NO_ROUTE', NULL,
        'governed human execution instruction; never creates a Provider API path'),
    ('ops', 'ad_manual_configuration_verification', 'NO_ROUTE', NULL,
        'append-only graded configuration evidence; grades never promote');

GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_bid_target_policy TO marketops_app;
GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_materiality_policy TO marketops_app;
GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_approval_lease_policy TO marketops_app;
GRANT SELECT, INSERT ON ops.ad_bid_candidate TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ad_manual_execution_packet TO marketops_app;
GRANT SELECT, INSERT ON ops.ad_manual_configuration_verification TO marketops_app;
