-- The advertising case: what the queue reads, how it is ranked, and the
-- machinery that keeps it current.
--
-- Three ideas shape this file.
--
-- First, the rank cannot be bought. The lane comes first, Protection carries
-- four hard sub-tiers, and inside a sub-tier the order is a fixed sequence of
-- reason-coded comparators rather than a weighted sum. A commercial score may be
-- shown, and it is computed inside a band wide enough that it can never reach
-- across a lane or a sub-tier boundary. That band arithmetic is the same device
-- SLICE-V1-002 proved, widened by one level, and it is mirrored in SQL because
-- the read path re-derives the rank of the child a scoped viewer is allowed to
-- see rather than trusting the stored number.
--
-- Second, a case is identified by its cause, not by the calculation that found
-- it. Recalculating one cause a thousand times updates one case. That is a
-- partial unique index here rather than a check in a service, because replay and
-- concurrency are exactly the conditions a service-level check misses.
--
-- Third, the two profit axes are stored separately and are never summed,
-- averaged or otherwise collapsed. A case row carries the absolute Advertising
-- Contribution Profit and the Contribution Profit per advertising rouble as two
-- columns with two independent value states, because a single "efficiency
-- score" is precisely the artefact the Contract's non-compensating Pareto rule
-- exists to prevent.
--
-- No table in this file is part of a write path. The bid command, its outbox and
-- its readback arrive in a later migration; this one decides what is worth
-- doing and who owns it.

-- ---------------------------------------------------------------------------
-- Priority policy
-- ---------------------------------------------------------------------------

-- The weights behind the visible commercial score. They order work inside a
-- sub-tier; they can never move work between tiers, because the score is
-- clamped into a band before the tier offset is added.
--
-- confidence_weight is required to be <= 0 for the same reason it is in the
-- availability policy: uncertainty may lower a rank and must never raise one.
CREATE TABLE core.ad_priority_policy (
    id                     uuid           NOT NULL,
    organization_id        uuid           NOT NULL,
    policy_version         integer        NOT NULL,
    profit_loss_weight     numeric(9, 4)  NOT NULL,
    spend_exposure_weight  numeric(9, 4)  NOT NULL,
    critical_sales_weight  numeric(9, 4)  NOT NULL,
    recoverable_profit_weight numeric(9, 4) NOT NULL,
    evidence_maturity_weight numeric(9, 4) NOT NULL,
    age_weight             numeric(9, 4)  NOT NULL,
    confidence_weight      numeric(9, 4)  NOT NULL,
    owner_user_id          uuid           NOT NULL,
    reason                 text           NOT NULL,
    evidence_reference     text           NOT NULL,
    effective_from         timestamptz    NOT NULL,
    effective_to           timestamptz,
    status                 text           NOT NULL,
    created_at             timestamptz    NOT NULL,
    CONSTRAINT ad_priority_policy_pk PRIMARY KEY (id),
    CONSTRAINT ad_priority_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_priority_policy_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_priority_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_priority_policy_version_uq UNIQUE (organization_id, policy_version),
    CONSTRAINT ad_priority_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT ad_priority_policy_weights_ck
        CHECK (profit_loss_weight >= 0
            AND spend_exposure_weight >= 0
            AND critical_sales_weight >= 0
            AND recoverable_profit_weight >= 0
            AND evidence_maturity_weight >= 0
            AND age_weight >= 0),
    CONSTRAINT ad_priority_policy_confidence_ck CHECK (confidence_weight <= 0),
    CONSTRAINT ad_priority_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_priority_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_priority_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_priority_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_priority_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- Human response SLO profile
-- ---------------------------------------------------------------------------

-- Two stages, per lane, with staffed coverage. Acknowledgement is not action and
-- action is not outcome, so the profile carries a separate bound for each rather
-- than one "response time".
--
-- The coverage columns are what let the staffed clock pause without the exposure
-- disappearing: a paused Action SLO is still a case whose wall-clock age,
-- continuing spend and known exposure stay visible, and
-- out_of_coverage_visible_from_minutes is when that becomes an explicit
-- OUT_OF_COVERAGE_ACTIVE_HARM state rather than a quiet wait.
CREATE TABLE core.ad_human_slo_profile (
    id                                 uuid        NOT NULL,
    organization_id                    uuid        NOT NULL,
    policy_version                     integer     NOT NULL,
    lane                               text        NOT NULL,
    acknowledgement_minutes            integer     NOT NULL,
    action_minutes                     integer     NOT NULL,
    escalation_minutes                 integer     NOT NULL,
    staffed_coverage_enabled           boolean     NOT NULL,
    staffed_coverage_timezone          text,
    staffed_coverage_start_minute      integer,
    staffed_coverage_end_minute        integer,
    out_of_coverage_visible_from_minutes integer   NOT NULL,
    owner_user_id                      uuid        NOT NULL,
    reason                             text        NOT NULL,
    evidence_reference                 text        NOT NULL,
    effective_from                     timestamptz NOT NULL,
    effective_to                       timestamptz,
    status                             text        NOT NULL,
    created_at                         timestamptz NOT NULL,
    CONSTRAINT ad_human_slo_profile_pk PRIMARY KEY (id),
    CONSTRAINT ad_human_slo_profile_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_human_slo_profile_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_human_slo_profile_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_human_slo_profile_version_uq
        UNIQUE (organization_id, lane, policy_version),
    CONSTRAINT ad_human_slo_profile_version_ck CHECK (policy_version >= 1),
    CONSTRAINT ad_human_slo_profile_lane_ck
        CHECK (lane IN ('PROTECTION', 'DATA_REPAIR', 'OPTIMIZATION', 'WATCH')),
    CONSTRAINT ad_human_slo_profile_minutes_ck
        CHECK (acknowledgement_minutes >= 1
            AND action_minutes >= 1
            AND escalation_minutes >= 1
            AND out_of_coverage_visible_from_minutes >= 0),
    -- Acknowledgement precedes action, and escalation follows it. A profile that
    -- escalated before the action was due would make the breach meaningless.
    CONSTRAINT ad_human_slo_profile_order_ck
        CHECK (acknowledgement_minutes <= action_minutes
            AND action_minutes <= escalation_minutes),
    CONSTRAINT ad_human_slo_profile_coverage_shape_ck
        CHECK (NOT staffed_coverage_enabled
            OR (staffed_coverage_timezone IS NOT NULL
                AND staffed_coverage_start_minute IS NOT NULL
                AND staffed_coverage_end_minute IS NOT NULL)),
    CONSTRAINT ad_human_slo_profile_coverage_range_ck
        CHECK (staffed_coverage_start_minute IS NULL
            OR (staffed_coverage_start_minute BETWEEN 0 AND 1439
                AND staffed_coverage_end_minute BETWEEN 0 AND 1439)),
    CONSTRAINT ad_human_slo_profile_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_human_slo_profile_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_human_slo_profile_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_human_slo_profile_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_human_slo_profile_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            lane WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- The advertising case projection
-- ---------------------------------------------------------------------------

-- One row per atomic advertising case: the object, its lineage generation, its
-- affected set, and the independent business cause. `case_key` is the
-- deduplication identity — organization, object, lineage generation and cause —
-- and the partial unique index on it is what makes "one cause, one case" true
-- under replay and concurrency rather than merely intended.
--
-- The two profit axes are two columns with two value states. There is no third
-- column combining them, and there is deliberately nowhere to put one.
CREATE TABLE mart.ad_case (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    store_id                    uuid           NOT NULL,
    platform_code               text           NOT NULL,
    ad_native_object_id         uuid           NOT NULL,
    affected_set_id             uuid,
    semantic_profile_id         uuid           NOT NULL,
    lineage_generation          integer        NOT NULL,
    case_key                    text           NOT NULL,
    lane                        text           NOT NULL,
    protection_tier             text,
    cause_code                  text           NOT NULL,
    evidence_state              text           NOT NULL,
    confidence_state            text           NOT NULL,
    blocker_codes               text[]         NOT NULL DEFAULT '{}',
    -- Axis one: absolute Advertising Contribution Profit.
    contribution_profit_state   text           NOT NULL,
    contribution_profit_amount  numeric(18, 4),
    -- Axis two: Contribution Profit per official advertising rouble.
    profit_per_ad_rub_state     text           NOT NULL,
    profit_per_ad_rub_value     numeric(18, 6),
    profit_currency_code        text,
    official_spend_state        text           NOT NULL,
    official_spend_amount       numeric(18, 4),
    eligible_traffic_state      text           NOT NULL,
    eligible_traffic_count      bigint,
    ad_linked_conversion_state  text           NOT NULL,
    ad_linked_conversion_value  numeric(12, 8),
    ad_linked_conversion_stage  text,
    max_cpc_state               text           NOT NULL,
    max_cpc_amount              numeric(18, 4),
    attribution_gap_state       text           NOT NULL,
    attribution_gap_ratio       numeric(9, 6),
    current_bid_state           text           NOT NULL,
    current_bid_amount          numeric(18, 4),
    recoverable_profit_amount   numeric(18, 4),
    rank_score                  numeric(14, 4) NOT NULL,
    policy_version_digest       text           NOT NULL,
    bundle_id                   uuid,
    as_of                       timestamptz    NOT NULL,
    calculated_at               timestamptz    NOT NULL,
    calculation_kind            text           NOT NULL,
    calculation_id              uuid           NOT NULL,
    reconciliation_run_id       uuid,
    sustained_lane              text,
    sustained_cycles            integer        NOT NULL DEFAULT 0,
    sustained_since             timestamptz,
    created_at                  timestamptz    NOT NULL,
    updated_at                  timestamptz    NOT NULL,
    version                     bigint         NOT NULL DEFAULT 0,
    CONSTRAINT ad_case_pk PRIMARY KEY (id),
    CONSTRAINT ad_case_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_case_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_case_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_case_affected_set_fk
        FOREIGN KEY (affected_set_id, organization_id)
        REFERENCES core.ad_affected_set (id, organization_id),
    -- An object first observed before any affected-set resolution still needs
    -- a Data Repair Case and a governed responsibility Task. No controlled-write
    -- lane or unrelated defect may use absence as a substitute for a frozen set.
    CONSTRAINT ad_case_unresolved_affected_set_ck
        CHECK (affected_set_id IS NOT NULL OR
               (lane = 'DATA_REPAIR' AND cause_code = 'AFFECTED_SET_UNRESOLVED'
                AND 'AFFECTED_SET_NEVER_RESOLVED' = ANY(blocker_codes))),
    CONSTRAINT ad_case_semantic_profile_fk
        FOREIGN KEY (semantic_profile_id, platform_code)
        REFERENCES platform.ad_semantic_profile (id, platform_code),
    CONSTRAINT ad_case_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_case_lane_ck
        CHECK (lane IN ('PROTECTION', 'DATA_REPAIR', 'OPTIMIZATION', 'WATCH')),
    -- Protection is the only lane with hard sub-tiers, and it must have one.
    CONSTRAINT ad_case_protection_tier_ck
        CHECK ((lane = 'PROTECTION') = (protection_tier IS NOT NULL)),
    CONSTRAINT ad_case_protection_tier_value_ck
        CHECK (protection_tier IS NULL OR protection_tier IN ('P0', 'P1', 'P2', 'P3')),
    CONSTRAINT ad_case_cause_ck CHECK (cause_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT ad_case_evidence_state_ck
        CHECK (evidence_state IN
            ('CANONICAL_CONFIRMED', 'OPERATIONAL', 'PROVISIONAL_OR_ESTIMATED',
             'STALE', 'INCOMPLETE', 'CONFLICTED', 'UNKNOWN', 'NOT_AVAILABLE',
             'DATA_BLOCKED', 'POLICY_BLOCKED', 'PROFILE_UNRESOLVED',
             'BUNDLE_UNRESOLVED')),
    CONSTRAINT ad_case_confidence_ck
        CHECK (confidence_state IN ('HIGH', 'MEDIUM', 'LOW', 'UNUSABLE')),
    CONSTRAINT ad_case_blockers_ck
        CHECK (cardinality(blocker_codes) BETWEEN 0 AND 64
            AND array_position(blocker_codes, NULL) IS NULL),
    -- Every measure carries a value state, and a state of AVAILABLE is the only
    -- one that may carry a number. This is ADR-0011 applied uniformly: a missing
    -- measure is never rendered as zero, and a present one always says how much
    -- it may be trusted.
    CONSTRAINT ad_case_value_states_ck
        CHECK (contribution_profit_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')
            AND profit_per_ad_rub_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')
            AND official_spend_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')
            AND eligible_traffic_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')
            AND ad_linked_conversion_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')
            AND max_cpc_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')
            AND attribution_gap_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')
            AND current_bid_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')),
    CONSTRAINT ad_case_value_presence_ck
        CHECK ((contribution_profit_state = 'AVAILABLE') = (contribution_profit_amount IS NOT NULL)
            AND (profit_per_ad_rub_state = 'AVAILABLE') = (profit_per_ad_rub_value IS NOT NULL)
            AND (official_spend_state = 'AVAILABLE') = (official_spend_amount IS NOT NULL)
            AND (eligible_traffic_state = 'AVAILABLE') = (eligible_traffic_count IS NOT NULL)
            AND (ad_linked_conversion_state = 'AVAILABLE') = (ad_linked_conversion_value IS NOT NULL)
            AND (max_cpc_state = 'AVAILABLE') = (max_cpc_amount IS NOT NULL)
            AND (attribution_gap_state = 'AVAILABLE') = (attribution_gap_ratio IS NOT NULL)
            AND (current_bid_state = 'AVAILABLE') = (current_bid_amount IS NOT NULL)),
    -- A conversion the product may believe always names its stage, so a
    -- stage-mismatched Max CPC cannot be assembled downstream.
    CONSTRAINT ad_case_conversion_stage_ck
        CHECK ((ad_linked_conversion_state = 'AVAILABLE') = (ad_linked_conversion_stage IS NOT NULL)),
    CONSTRAINT ad_case_conversion_stage_value_ck
        CHECK (ad_linked_conversion_stage IS NULL
            OR ad_linked_conversion_stage IN
                ('CANONICAL_AD_LINKED_ORDER', 'CANONICAL_AD_LINKED_COMPLETED_SALE',
                 'CANONICAL_AD_LINKED_RETAINED_SALE')),
    -- Money needs a currency. One currency column serves every monetary measure
    -- on the row because they are all the store's currency by construction.
    CONSTRAINT ad_case_currency_presence_ck
        CHECK ((profit_currency_code IS NOT NULL)
            = (contribution_profit_amount IS NOT NULL
                OR official_spend_amount IS NOT NULL
                OR max_cpc_amount IS NOT NULL
                OR current_bid_amount IS NOT NULL
                OR recoverable_profit_amount IS NOT NULL)),
    CONSTRAINT ad_case_currency_ck
        CHECK (profit_currency_code IS NULL OR profit_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_case_traffic_ck
        CHECK (eligible_traffic_count IS NULL OR eligible_traffic_count >= 0),
    CONSTRAINT ad_case_conversion_range_ck
        CHECK (ad_linked_conversion_value IS NULL
            OR (ad_linked_conversion_value >= 0 AND ad_linked_conversion_value <= 1)),
    CONSTRAINT ad_case_gap_ck
        CHECK (attribution_gap_ratio IS NULL OR attribution_gap_ratio >= 0),
    CONSTRAINT ad_case_spend_ck
        CHECK (official_spend_amount IS NULL OR official_spend_amount >= 0),
    CONSTRAINT ad_case_bid_ck
        CHECK (current_bid_amount IS NULL OR current_bid_amount >= 0),
    CONSTRAINT ad_case_max_cpc_ck
        CHECK (max_cpc_amount IS NULL OR max_cpc_amount >= 0),
    CONSTRAINT ad_case_digest_ck CHECK (policy_version_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ad_case_key_ck CHECK (length(btrim(case_key)) BETWEEN 1 AND 512),
    -- The band arithmetic, restated as a bound. Four lanes and four Protection
    -- sub-tiers give seven bands of 100000 plus a commercial part below 100000.
    CONSTRAINT ad_case_rank_ck CHECK (rank_score BETWEEN 0 AND 1000000),
    CONSTRAINT ad_case_calculation_kind_ck
        CHECK (calculation_kind IN ('TARGETED', 'RECONCILIATION')),
    CONSTRAINT ad_case_generation_ck CHECK (lineage_generation >= 1),
    CONSTRAINT ad_case_sustained_ck
        CHECK ((sustained_lane IS NULL) = (sustained_since IS NULL)
            AND (sustained_lane IS NULL) = (sustained_cycles = 0)),
    CONSTRAINT ad_case_sustained_lane_ck
        CHECK (sustained_lane IS NULL
            OR sustained_lane IN ('PROTECTION', 'DATA_REPAIR', 'OPTIMIZATION', 'WATCH')),
    CONSTRAINT ad_case_sustained_cycles_ck CHECK (sustained_cycles >= 0)
);

-- One cause, one case. The key carries the lineage generation, so a rebuilt
-- advertising object produces a new case rather than silently inheriting the
-- history of the object it replaced.
CREATE UNIQUE INDEX ad_case_identity_uq
    ON mart.ad_case (organization_id, case_key);
CREATE INDEX ad_case_queue_ix
    ON mart.ad_case (organization_id, rank_score DESC, id);
CREATE INDEX ad_case_lane_ix
    ON mart.ad_case (organization_id, lane, calculated_at DESC);
CREATE INDEX ad_case_object_ix
    ON mart.ad_case (ad_native_object_id, calculated_at DESC);
CREATE INDEX ad_case_store_ix
    ON mart.ad_case (organization_id, store_id, lane);
CREATE INDEX ad_case_sustained_ix
    ON mart.ad_case (organization_id, sustained_lane, sustained_cycles)
    WHERE sustained_lane IS NOT NULL;

-- The per-variant diagnostic view of one case. These are children for reading,
-- never for executing: `basis` says whether a number was observed at this
-- variant or allocated to it, and an allocated number can support diagnosis and
-- nothing else. Append-only per calculation, so an older generation stays
-- readable but never renders.
CREATE TABLE mart.ad_case_variant_diagnostic (
    id                          uuid           NOT NULL,
    case_id                     uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    calculation_id              uuid           NOT NULL,
    product_variant_id          uuid           NOT NULL,
    platform_listing_variant_id uuid,
    basis                       text           NOT NULL,
    confidence_state            text           NOT NULL,
    spend_amount                numeric(18, 4),
    clicks                      bigint,
    contribution_profit_amount  numeric(18, 4),
    currency_code               text,
    sellability_state           text           NOT NULL,
    availability_state          text           NOT NULL,
    is_critical_sales_unit      boolean        NOT NULL DEFAULT false,
    created_at                  timestamptz    NOT NULL,
    CONSTRAINT ad_case_variant_diagnostic_pk PRIMARY KEY (id),
    CONSTRAINT ad_case_variant_diagnostic_case_fk
        FOREIGN KEY (case_id, organization_id)
        REFERENCES mart.ad_case (id, organization_id),
    CONSTRAINT ad_case_variant_diagnostic_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT ad_case_variant_diagnostic_listing_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT ad_case_variant_diagnostic_uq
        UNIQUE (calculation_id, product_variant_id),
    CONSTRAINT ad_case_variant_diagnostic_basis_ck
        CHECK (basis IN ('OFFICIAL_OBSERVATION', 'ESTIMATED_ALLOCATION')),
    CONSTRAINT ad_case_variant_diagnostic_confidence_ck
        CHECK (confidence_state IN
            ('CANONICAL_CONFIRMED', 'ESTIMATED_EXPLAINED', 'INCOMPLETE',
             'CONFLICTED', 'UNKNOWN')),
    -- An allocated number is an estimate and must say so. This is the row-level
    -- half of the rule; the API and the console carry the other half.
    CONSTRAINT ad_case_variant_diagnostic_estimate_ck
        CHECK (basis <> 'ESTIMATED_ALLOCATION' OR confidence_state = 'ESTIMATED_EXPLAINED'),
    CONSTRAINT ad_case_variant_diagnostic_official_ck
        CHECK (basis <> 'OFFICIAL_OBSERVATION' OR confidence_state <> 'ESTIMATED_EXPLAINED'),
    CONSTRAINT ad_case_variant_diagnostic_money_ck
        CHECK ((currency_code IS NOT NULL)
            = (spend_amount IS NOT NULL OR contribution_profit_amount IS NOT NULL)),
    CONSTRAINT ad_case_variant_diagnostic_currency_ck
        CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_case_variant_diagnostic_clicks_ck
        CHECK (clicks IS NULL OR clicks >= 0),
    CONSTRAINT ad_case_variant_diagnostic_spend_ck
        CHECK (spend_amount IS NULL OR spend_amount >= 0),
    CONSTRAINT ad_case_variant_diagnostic_sellability_ck
        CHECK (sellability_state IN ('SELLABLE', 'NOT_SELLABLE', 'UNKNOWN')),
    CONSTRAINT ad_case_variant_diagnostic_availability_ck
        CHECK (availability_state IN
            ('AVAILABLE', 'AT_RISK', 'UNAVAILABLE', 'UNKNOWN'))
);

CREATE INDEX ad_case_variant_diagnostic_case_ix
    ON mart.ad_case_variant_diagnostic (case_id, calculation_id);

-- The visible rank factors, one row per named term, always emitted even at
-- zero. A rank a person cannot audit is a rank a person cannot trust.
CREATE TABLE mart.ad_case_rank_factor (
    id             uuid           NOT NULL,
    case_id        uuid           NOT NULL,
    organization_id uuid          NOT NULL,
    calculation_id uuid           NOT NULL,
    factor_code    text           NOT NULL,
    factor_value   numeric(18, 4),
    factor_weight  numeric(9, 4)  NOT NULL,
    contribution   numeric(18, 4) NOT NULL,
    display_note   text,
    CONSTRAINT ad_case_rank_factor_pk PRIMARY KEY (id),
    CONSTRAINT ad_case_rank_factor_case_fk
        FOREIGN KEY (case_id, organization_id)
        REFERENCES mart.ad_case (id, organization_id),
    CONSTRAINT ad_case_rank_factor_uq UNIQUE (calculation_id, factor_code),
    CONSTRAINT ad_case_rank_factor_code_ck
        CHECK (factor_code IN (
            'CONFIRMED_PROFIT_LOSS_RATE', 'CRITICAL_SALES_EXPOSURE',
            'OFFICIAL_SPEND_EXPOSURE', 'RECOVERABLE_CONTRIBUTION_PROFIT',
            'EVIDENCE_MATURITY', 'CASE_AGE', 'CONFIDENCE_PENALTY', 'HUMAN_SLO_URGENCY',
            'BLOCKED_PROTECTION', 'BLAST_RADIUS', 'BLOCKED_WORK', 'DUAL_AXIS_GAP', 'DUAL_AXIS_PER_RUB_GAP', 'CRITICAL_SALES_HEADROOM')),
    CONSTRAINT ad_case_rank_factor_absent_ck
        CHECK (factor_value IS NOT NULL OR coalesce(display_note LIKE 'PRIORITY_POLICY_UNRESOLVED:%',false)),
    CONSTRAINT ad_case_rank_factor_note_ck
        CHECK (display_note IS NULL OR length(btrim(display_note)) BETWEEN 1 AND 256)
);

CREATE INDEX ad_case_rank_factor_calculation_ix
    ON mart.ad_case_rank_factor (calculation_id);

-- What the case was calculated from. At least one reference is mandatory, so a
-- conclusion with no traceable input cannot be persisted.
CREATE TABLE mart.ad_case_evidence (
    id                     uuid        NOT NULL,
    case_id                uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    calculation_id         uuid        NOT NULL,
    evidence_role          text        NOT NULL,
    provenance_id          uuid,
    metric_value_id        uuid,
    policy_reference_id    uuid,
    ad_object_fact_id      uuid,
    ad_linked_sale_event_id uuid,
    configuration_observation_id uuid,
    observed_at            timestamptz,
    note                   text,
    CONSTRAINT ad_case_evidence_pk PRIMARY KEY (id),
    CONSTRAINT ad_case_evidence_case_fk
        FOREIGN KEY (case_id, organization_id)
        REFERENCES mart.ad_case (id, organization_id),
    CONSTRAINT ad_case_evidence_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT ad_case_evidence_metric_fk
        FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value (id),
    CONSTRAINT ad_case_evidence_object_fact_fk
        FOREIGN KEY (ad_object_fact_id) REFERENCES ledger.ad_object_fact (id),
    CONSTRAINT ad_case_evidence_sale_event_fk
        FOREIGN KEY (ad_linked_sale_event_id)
        REFERENCES ledger.ad_linked_sale_event (id),
    CONSTRAINT ad_case_evidence_configuration_fk
        FOREIGN KEY (configuration_observation_id)
        REFERENCES core.ad_object_configuration_observation (id),
    CONSTRAINT ad_case_evidence_role_ck
        CHECK (evidence_role IN (
            'OFFICIAL_SPEND', 'OFFICIAL_TRAFFIC', 'PROVIDER_ATTRIBUTION',
            'AD_LINKED_SALE', 'COMPANY_SALES', 'PROFIT_ECONOMICS',
            'OBJECT_CONFIGURATION', 'AFFECTED_SET', 'MAPPING',
            'CONVERSION_DEFINITION', 'ALLOWABLE_CPA_DEFINITION',
            'FRESHNESS_PROFILE', 'QUALIFICATION_POLICY', 'PRIORITY_POLICY',
            'HUMAN_SLO_PROFILE', 'SEMANTIC_PROFILE', 'POLICY_BUNDLE')),
    CONSTRAINT ad_case_evidence_reference_ck
        CHECK (num_nonnulls(provenance_id, metric_value_id, policy_reference_id,
                            ad_object_fact_id, ad_linked_sale_event_id,
                            configuration_observation_id) >= 1),
    CONSTRAINT ad_case_evidence_note_ck
        CHECK (note IS NULL OR length(btrim(note)) BETWEEN 1 AND 512)
);

CREATE INDEX ad_case_evidence_calculation_ix
    ON mart.ad_case_evidence (calculation_id, evidence_role);

-- ---------------------------------------------------------------------------
-- Recalculation, reconciliation and the internal clock
-- ---------------------------------------------------------------------------

-- The targeted trigger queue. Coalescing keeps the earliest accepted instant so
-- a later fact cannot restart a running response clock; suppression makes
-- re-reading a feed boundary a no-op instead of a loop.
CREATE TABLE ops.ad_recalculation_request (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    ad_native_object_id uuid        NOT NULL,
    trigger_class       text        NOT NULL,
    trigger_reference   text        NOT NULL,
    fact_accepted_at    timestamptz NOT NULL,
    requested_at        timestamptz NOT NULL,
    state               text        NOT NULL,
    attempt_count       integer     NOT NULL DEFAULT 0,
    leased_until        timestamptz,
    lease_owner         text,
    started_at          timestamptz,
    completed_at        timestamptz,
    failure_code        text,
    correlation_id      text        NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ad_recalculation_request_pk PRIMARY KEY (id),
    CONSTRAINT ad_recalculation_request_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_recalculation_request_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_recalculation_request_state_ck
        CHECK (state IN ('PENDING', 'LEASED', 'COMPLETED', 'FAILED', 'ABANDONED')),
    -- The trigger classes the Contract enumerates. A class that is not here
    -- cannot enqueue work, which is how a new trigger stays a reviewed change.
    CONSTRAINT ad_recalculation_request_trigger_ck
        CHECK (trigger_class IN (
            'AD_CONFIGURATION', 'AD_SPEND_OR_TRAFFIC', 'PROVIDER_ATTRIBUTION',
            'PRODUCT_MAPPING_OR_AFFECTED_SET', 'SELLABILITY_OR_AVAILABILITY',
            'COMPANY_SALES_OR_RETURNS', 'SETTLEMENT_OR_ADJUSTMENT',
            'COST_OR_FEE', 'CONVERSION_OR_ALLOWABLE_CPA',
            'FRESHNESS_OR_QUALIFICATION_POLICY', 'TARGET_OR_OUTCOME_POLICY',
            'PRIORITY_OR_SLO_POLICY', 'LEASE_OR_EXPOSURE_POLICY',
            'POLICY_BUNDLE_LIFECYCLE', 'EXCEPTION_HOLD_KILL_OR_QUARANTINE',
            'PROVIDER_READBACK_OR_UNKNOWN', 'CRITICAL_SALES_OR_CONFOUNDER',
            'OUTCOME_MATURITY_OR_REGRESSION', 'MANUAL_REQUEST')),
    CONSTRAINT ad_recalculation_request_attempt_ck CHECK (attempt_count >= 0),
    CONSTRAINT ad_recalculation_request_lease_ck
        CHECK (num_nonnulls(lease_owner, leased_until) <> 1),
    CONSTRAINT ad_recalculation_request_leased_ck
        CHECK (state <> 'LEASED' OR lease_owner IS NOT NULL),
    CONSTRAINT ad_recalculation_request_completion_ck
        CHECK ((state IN ('COMPLETED', 'FAILED', 'ABANDONED')) = (completed_at IS NOT NULL)),
    CONSTRAINT ad_recalculation_request_failure_ck
        CHECK (state NOT IN ('FAILED', 'ABANDONED') OR failure_code IS NOT NULL),
    CONSTRAINT ad_recalculation_request_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128),
    CONSTRAINT ad_recalculation_request_reference_ck
        CHECK (length(btrim(trigger_reference)) BETWEEN 1 AND 256)
);

CREATE UNIQUE INDEX ad_recalculation_request_pending_uq
    ON ops.ad_recalculation_request (organization_id, ad_native_object_id)
    WHERE state IN ('PENDING', 'LEASED');
CREATE INDEX ad_recalculation_request_claim_ix
    ON ops.ad_recalculation_request (state, fact_accepted_at)
    WHERE state IN ('PENDING', 'LEASED');

-- The hourly full sweep. One run per organization at a time, enforced by a
-- partial unique index rather than a lock, because a second concurrent sweep
-- would make the targeted-equals-sweep property untestable.
CREATE TABLE ops.ad_reconciliation_run (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    as_of                  timestamptz NOT NULL,
    state                  text        NOT NULL,
    trigger_kind           text        NOT NULL,
    object_count           integer     NOT NULL DEFAULT 0,
    changed_case_count     integer     NOT NULL DEFAULT 0,
    repaired_count         integer     NOT NULL DEFAULT 0,
    expired_exception_count integer    NOT NULL DEFAULT 0,
    expired_approval_count integer     NOT NULL DEFAULT 0,
    released_reservation_count integer NOT NULL DEFAULT 0,
    failed_object_count    integer     NOT NULL DEFAULT 0,
    last_ad_native_object_id uuid,
    failure_code           text,
    started_at             timestamptz NOT NULL,
    completed_at           timestamptz,
    correlation_id         text        NOT NULL,
    CONSTRAINT ad_reconciliation_run_pk PRIMARY KEY (id),
    CONSTRAINT ad_reconciliation_run_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_reconciliation_run_state_ck
        CHECK (state IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ad_reconciliation_run_trigger_ck
        CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'RECOVERY')),
    CONSTRAINT ad_reconciliation_run_counts_ck
        CHECK (object_count >= 0 AND changed_case_count >= 0 AND repaired_count >= 0
            AND expired_exception_count >= 0 AND expired_approval_count >= 0
            AND released_reservation_count >= 0 AND failed_object_count >= 0),
    CONSTRAINT ad_reconciliation_run_completion_ck
        CHECK ((state IN ('COMPLETED', 'FAILED')) = (completed_at IS NOT NULL)),
    CONSTRAINT ad_reconciliation_run_failure_ck
        CHECK (state <> 'FAILED' OR failure_code IS NOT NULL),
    CONSTRAINT ad_reconciliation_run_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE UNIQUE INDEX ad_reconciliation_run_active_uq
    ON ops.ad_reconciliation_run (organization_id)
    WHERE state = 'RUNNING';
CREATE INDEX ad_reconciliation_run_recent_ix
    ON ops.ad_reconciliation_run (organization_id, started_at DESC);

-- Where the targeted scan has read to. A total key — instant, provenance and
-- item — so two facts accepted in the same microsecond cannot make the cursor
-- skip one, and the position can never rewind.
CREATE TABLE ops.ad_fact_cursor (
    feed_code               text        NOT NULL,
    position_at             timestamptz NOT NULL,
    position_provenance_id  uuid        NOT NULL,
    position_item_key       text        NOT NULL,
    last_scanned_at         timestamptz NOT NULL,
    scanned_count           bigint      NOT NULL DEFAULT 0,
    version                 bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ad_fact_cursor_pk PRIMARY KEY (feed_code),
    CONSTRAINT ad_fact_cursor_feed_ck
        CHECK (feed_code IN ('ADVERTISING_ACCEPTED_FACT')),
    CONSTRAINT ad_fact_cursor_count_ck CHECK (scanned_count >= 0)
);

-- The internal clock, measured separately from the source clock. The Contract's
-- SLO is about how fast MarketOps reacts to a fact it has accepted, and mixing
-- in how long the marketplace took to publish it would make the number
-- unactionable.
CREATE TABLE ops.ad_slo_observation (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    ad_native_object_id    uuid        NOT NULL,
    case_id                uuid,
    lane                   text        NOT NULL,
    path_kind              text        NOT NULL,
    source_event_time      timestamptz,
    source_updated_at      timestamptz,
    ingested_at            timestamptz,
    fact_accepted_at       timestamptz NOT NULL,
    calculated_at          timestamptz NOT NULL,
    case_updated_at        timestamptz,
    internal_latency_ms    bigint      NOT NULL,
    source_latency_ms      bigint,
    breached               boolean     NOT NULL,
    correlation_id         text        NOT NULL,
    CONSTRAINT ad_slo_observation_pk PRIMARY KEY (id),
    CONSTRAINT ad_slo_observation_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_slo_observation_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_slo_observation_case_fk
        FOREIGN KEY (case_id, organization_id)
        REFERENCES mart.ad_case (id, organization_id),
    CONSTRAINT ad_slo_observation_lane_ck
        CHECK (lane IN ('PROTECTION', 'DATA_REPAIR', 'OPTIMIZATION', 'WATCH')),
    CONSTRAINT ad_slo_observation_path_ck
        CHECK (path_kind IN ('TARGETED', 'RECONCILIATION')),
    CONSTRAINT ad_slo_observation_order_ck CHECK (calculated_at >= fact_accepted_at),
    CONSTRAINT ad_slo_observation_internal_ck CHECK (internal_latency_ms >= 0),
    -- A negative source latency would mean the marketplace published a fact
    -- after we accepted it, which is a clock defect rather than a measurement.
    -- It is recorded as absent rather than as zero.
    CONSTRAINT ad_slo_observation_source_ck
        CHECK (source_latency_ms IS NULL OR source_latency_ms >= 0),
    CONSTRAINT ad_slo_observation_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX ad_slo_observation_window_ix
    ON ops.ad_slo_observation (organization_id, calculated_at DESC);
CREATE INDEX ad_slo_observation_breach_ix
    ON ops.ad_slo_observation (organization_id, lane, calculated_at DESC)
    WHERE breached;

-- Durable correlation evidence for one pass through the loop. This is what
-- makes a dropped trigger, a suppressed duplicate or a failed sweep an
-- observable fact rather than an inference from absence.
CREATE TABLE ops.ad_trace_event (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    ad_native_object_id    uuid,
    path_kind              text        NOT NULL,
    stage_code             text        NOT NULL,
    status                 text        NOT NULL,
    correlation_id         text        NOT NULL,
    parent_correlation_id  text,
    subject_reference      text,
    detail                 jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at            timestamptz NOT NULL,
    CONSTRAINT ad_trace_event_pk PRIMARY KEY (id),
    CONSTRAINT ad_trace_event_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_trace_event_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_trace_event_path_ck
        CHECK (path_kind IN ('TARGETED', 'RECONCILIATION', 'OPERATIONS')),
    CONSTRAINT ad_trace_event_stage_ck
        CHECK (stage_code IN (
            'TARGET_DEDUP_QUEUED', 'TARGET_DEDUP_COALESCED', 'TARGET_DEDUP_SUPPRESSED',
            'CALCULATION_STARTED', 'EVIDENCE_AND_LANE_CALCULATED',
            'PROJECTION_WRITTEN', 'CASE_SYNCHRONIZED', 'AUTO_VERIFICATION',
            'SLO_RECORDED', 'SWEEP_STARTED', 'SWEEP_COMPLETED', 'SWEEP_FAILED',
            'BACKLOG_SNAPSHOT', 'EXCEPTION_EXPIRY_REVALIDATION',
            'APPROVAL_EXPIRY_SWEEP', 'RESERVATION_RELEASE_SWEEP',
            'OUTCOME_MATURITY_SWEEP')),
    CONSTRAINT ad_trace_event_status_ck
        CHECK (status IN ('STARTED', 'COMPLETED', 'SUPPRESSED', 'FAILED', 'OBSERVED')),
    CONSTRAINT ad_trace_event_detail_ck CHECK (jsonb_typeof(detail) = 'object'),
    CONSTRAINT ad_trace_event_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128),
    CONSTRAINT ad_trace_event_parent_ck
        CHECK (parent_correlation_id IS NULL
            OR length(btrim(parent_correlation_id)) BETWEEN 1 AND 128),
    CONSTRAINT ad_trace_event_subject_ck
        CHECK (subject_reference IS NULL
            OR length(btrim(subject_reference)) BETWEEN 1 AND 256)
);

CREATE INDEX ad_trace_event_correlation_ix
    ON ops.ad_trace_event (correlation_id, occurred_at);
CREATE INDEX ad_trace_event_recent_ix
    ON ops.ad_trace_event (organization_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Control routing and privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'ad_priority_policy', 'NO_ROUTE', NULL,
        'versioned intra-tier rank weights; cannot move work between lanes'),
    ('core', 'ad_human_slo_profile', 'NO_ROUTE', NULL,
        'versioned two-stage coverage-aware response profile; read by activation only'),
    ('mart', 'ad_case', 'NO_ROUTE', NULL,
        'rebuildable advertising case projection; no acquisition authority reads it'),
    ('mart', 'ad_case_variant_diagnostic', 'NO_ROUTE', NULL,
        'append-only per-variant diagnostic view; never separately executable'),
    ('mart', 'ad_case_rank_factor', 'NO_ROUTE', NULL,
        'append-only visible rank factors for one calculation'),
    ('mart', 'ad_case_evidence', 'NO_ROUTE', NULL,
        'append-only evidence references for one calculation'),
    ('ops', 'ad_recalculation_request', 'NO_ROUTE', NULL,
        'internal recalculation trigger queue; not an acquisition job'),
    ('ops', 'ad_reconciliation_run', 'NO_ROUTE', NULL,
        'internal full-sweep run record; performs no external call'),
    ('ops', 'ad_fact_cursor', 'NO_ROUTE', NULL,
        'internal accepted-fact scan position; performs no external call'),
    ('ops', 'ad_slo_observation', 'NO_ROUTE', NULL,
        'append-only internal latency evidence'),
    ('ops', 'ad_trace_event', 'NO_ROUTE', NULL,
        'durable internal correlation evidence; performs no external call');

GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_priority_policy TO marketops_app;
GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_human_slo_profile TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON mart.ad_case TO marketops_app;
GRANT SELECT, INSERT ON mart.ad_case_variant_diagnostic TO marketops_app;
GRANT SELECT, INSERT ON mart.ad_case_rank_factor TO marketops_app;
GRANT SELECT, INSERT ON mart.ad_case_evidence TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ad_recalculation_request TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ad_reconciliation_run TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ad_fact_cursor TO marketops_app;
GRANT SELECT, INSERT ON ops.ad_slo_observation TO marketops_app;
GRANT SELECT, INSERT ON ops.ad_trace_event TO marketops_app;

-- Qualification counts distinct, consecutive, complete policy windows; replaying
-- a targeted refresh cannot manufacture another sustained period.
CREATE TABLE mart.ad_qualification_period (
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    ad_native_object_id uuid NOT NULL,
    qualification_policy_id uuid NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    qualified boolean NOT NULL,
    evaluated_at timestamptz NOT NULL,
    PRIMARY KEY (organization_id, ad_native_object_id, qualification_policy_id, period_start, period_end),
    FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id),
    FOREIGN KEY (qualification_policy_id, organization_id) REFERENCES core.ad_optimization_qualification_policy(id, organization_id),
    CHECK (period_start < period_end)
);
GRANT SELECT, INSERT, UPDATE ON mart.ad_qualification_period TO marketops_app;

CREATE TABLE mart.ad_case_purpose_evidence (
    case_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    calculation_id uuid NOT NULL,
    decision_purpose text NOT NULL,
    evidence_kind text NOT NULL,
    freshness_profile_id uuid REFERENCES core.ad_freshness_profile(id),
    source_time timestamptz,
    accepted_at timestamptz,
    expires_at timestamptz,
    eligible boolean NOT NULL,
    reason_codes text[] NOT NULL,
    PRIMARY KEY (case_id, calculation_id, decision_purpose, evidence_kind),
    FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id),
    CHECK (NOT eligible OR (freshness_profile_id IS NOT NULL AND expires_at IS NOT NULL AND cardinality(reason_codes) = 0))
);
GRANT SELECT, INSERT ON mart.ad_case_purpose_evidence TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES ('mart','ad_qualification_period','NO_ROUTE',NULL,'rebuildable distinct-window qualification history'),
       ('mart','ad_case_purpose_evidence','NO_ROUTE',NULL,'purpose-bound actual evidence times and deadlines');
