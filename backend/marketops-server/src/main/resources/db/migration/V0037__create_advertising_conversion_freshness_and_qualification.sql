-- The canonical advertising economics, and the evidence rules that decide which
-- purpose each fact is good enough for.
--
-- The Contract's hardest requirement is in this file, and it is a requirement
-- about arithmetic that must never be allowed to happen. Company total sales
-- divided by advertising clicks is not a conversion rate. Order-stage Allowable
-- CPA multiplied by retained-stage conversion is not an economic ceiling. The
-- same cancellation loss counted in both the numerator and the denominator is
-- not conservatism. Each of those is one plausible line of code away, and each
-- produces a bid that looks justified and is not.
--
-- So conversion is not a number here. It is a versioned definition that names
-- its sale stage, its traffic denominator, its linkage basis and its coverage,
-- and a value that cannot be produced without all four. Allowable CPA carries
-- the same stage, and the check constraint that binds them is the reason a
-- stage mismatch is unrepresentable rather than merely discouraged.
--
-- Freshness is the second idea. It is not a time-to-live. A profile is keyed by
-- evidence kind, by platform scope and by decision purpose, and it answers a
-- different question for each: a newly ingested old fact is not fresh, and a
-- mature thirty-day cohort is not stale because its business period is old.
-- Purpose monotonicity — a write purpose may never accept weaker evidence than
-- the recommendation purpose that feeds it — is a constraint on the profile set
-- rather than a convention in a service, because a convention is exactly what a
-- refactor loses.
--
-- Optimization qualification is the third. An immature, unsustained or
-- immaterial signal is a Watch, not an opportunity, and the eleven dimensions
-- the Contract lists are columns here so that "qualified" has one meaning.
--
-- Nothing in this file writes to a platform. It decides what may be believed.

-- ---------------------------------------------------------------------------
-- Canonical advertising metric definitions
-- ---------------------------------------------------------------------------

-- The advertising metrics the Slice calculates. Read-only to the application:
-- a metric whose definition could be edited at runtime is not a definition.
--
-- The three canonical conversions are separate codes rather than one code with
-- a stage parameter, because a stage parameter is a thing a caller can get
-- wrong and a code is a thing a query either asks for or does not.
-- PROVIDER_ATTRIBUTED_CONVERSION sits beside them and is deliberately named for
-- what it is: an observation the marketplace made, never company truth.
INSERT INTO mart.metric_definition
    (metric_code, definition_version, display_name, unit_kind, formula_statement,
     domain, owner_label, status) VALUES
    ('AD_ELIGIBLE_TRAFFIC', 1, 'Eligible advertising traffic', 'COUNT',
        'The official traffic denominator the applicable Conversion Definition names for this object and window.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('PROVIDER_ATTRIBUTED_CONVERSION', 1, 'Provider-attributed conversion', 'RATIO',
        'Provider-attributed orders divided by the provider traffic denominator; a marketplace observation only, never canonical.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('AD_LINKED_ORDER_CONVERSION', 1, 'Ad-linked order conversion', 'RATIO',
        'Deterministically ad-linked company Orders divided by eligible advertising traffic for the same object, window and complete affected set.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('AD_LINKED_COMPLETED_SALE_CONVERSION', 1, 'Ad-linked completed-sale conversion', 'RATIO',
        'Deterministically ad-linked Completed Sales divided by eligible advertising traffic for the same object, window and complete affected set.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('AD_LINKED_RETAINED_SALE_CONVERSION', 1, 'Ad-linked retained-sale conversion', 'RATIO',
        'Deterministically ad-linked Retained Sales divided by eligible advertising traffic for the same object, window and complete affected set.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('AD_ATTRIBUTION_GAP_RATIO', 1, 'Provider-to-canonical attribution gap', 'RATIO',
        'Absolute difference between provider-attributed and canonical ad-linked sale events, over the canonical count; UNDEFINED when either side is absent.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('ALLOWABLE_CPA', 1, 'Allowable cost per acquisition', 'MONEY',
        'The contribution a single ad-linked sale event of the stated stage may spend before it stops paying; carries the same stage as the conversion it is multiplied with.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('MAX_CPC', 1, 'Maximum cost per click', 'MONEY',
        'Stage-consistent Allowable CPA multiplied by the stage-consistent ad-linked conversion rate; an economic ceiling, never an automatic bid target.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('ADVERTISING_CONTRIBUTION_PROFIT', 1, 'Advertising contribution profit', 'MONEY',
        'Attributable net sales less COGS, commission and variable fees, fulfillment, return loss, promotion cost, variable tax and the official AD_SPEND for the same object and window.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('CONTRIBUTION_PROFIT_PER_AD_RUB', 1, 'Contribution profit per advertising rouble', 'RATIO',
        'ADVERTISING_CONTRIBUTION_PROFIT divided by the existing AD_SPEND metric; UNDEFINED when spend is absent or zero, never zero when spend is unreported.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE');

-- ---------------------------------------------------------------------------
-- The advertising conversion definition
-- ---------------------------------------------------------------------------

-- One versioned answer to "what counts as a conversion for this object". The
-- stage is explicit, the traffic denominator is explicit, and the linkage basis
-- is explicit — and `linkage_basis` is what makes the forbidden shortcut
-- unrepresentable. COMPANY_TOTAL_OVER_CLICKS is not in the vocabulary, so the
-- product cannot record a value derived that way even by accident.
CREATE TABLE core.ad_conversion_definition (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    definition_version          integer     NOT NULL,
    scope_kind                  text        NOT NULL,
    platform_code               text,
    store_ref_id                uuid,
    sale_stage                  text        NOT NULL,
    traffic_denominator_kind    text        NOT NULL,
    linkage_basis               text        NOT NULL,
    minimum_linkage_coverage_ratio numeric(6, 5) NOT NULL,
    minimum_affected_set_coverage_ratio numeric(6, 5) NOT NULL,
    minimum_sample_events       integer     NOT NULL,
    maximum_attribution_gap_ratio numeric(6, 5) NOT NULL,
    observation_window_days     integer     NOT NULL,
    owner_user_id               uuid        NOT NULL,
    reason                      text        NOT NULL,
    evidence_reference          text        NOT NULL,
    effective_from              timestamptz NOT NULL,
    effective_to                timestamptz,
    status                      text        NOT NULL,
    created_at                  timestamptz NOT NULL,
    CONSTRAINT ad_conversion_definition_pk PRIMARY KEY (id),
    CONSTRAINT ad_conversion_definition_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_conversion_definition_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_conversion_definition_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_conversion_definition_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_conversion_definition_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_conversion_definition_version_uq
        UNIQUE (organization_id, definition_version),
    CONSTRAINT ad_conversion_definition_version_ck CHECK (definition_version >= 1),
    CONSTRAINT ad_conversion_definition_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT ad_conversion_definition_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION'
                AND platform_code IS NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'PLATFORM'
                AND platform_code IS NOT NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'STORE'
                AND platform_code IS NOT NULL AND store_ref_id IS NOT NULL)),
    -- The four canonical stages the Contract names. PROVIDER_NATIVE_OBSERVATION
    -- is present so a definition can describe the marketplace's own number, and
    -- every consumer of a write-grade purpose refuses it by stage rather than
    -- by convention.
    CONSTRAINT ad_conversion_definition_stage_ck
        CHECK (sale_stage IN
            ('PROVIDER_NATIVE_OBSERVATION', 'CANONICAL_AD_LINKED_ORDER',
             'CANONICAL_AD_LINKED_COMPLETED_SALE', 'CANONICAL_AD_LINKED_RETAINED_SALE')),
    CONSTRAINT ad_conversion_definition_traffic_ck
        CHECK (traffic_denominator_kind IN ('CLICKS', 'VIEWS', 'IMPRESSIONS')),
    -- The vocabulary deliberately has no member meaning "company totals divided
    -- by clicks". A conversion the product may believe is one whose numerator
    -- is deterministically tied to this object or a governed scope containing it.
    CONSTRAINT ad_conversion_definition_linkage_ck
        CHECK (linkage_basis IN
            ('DETERMINISTIC_OBJECT_LINKAGE', 'DETERMINISTIC_GOVERNED_SCOPE_LINKAGE',
             'PROVIDER_REPORTED_LINKAGE')),
    -- A provider-reported linkage can only describe the provider's own stage,
    -- and a canonical stage can never rest on it.
    CONSTRAINT ad_conversion_definition_linkage_stage_ck
        CHECK ((linkage_basis = 'PROVIDER_REPORTED_LINKAGE')
            = (sale_stage = 'PROVIDER_NATIVE_OBSERVATION')),
    CONSTRAINT ad_conversion_definition_coverage_ck
        CHECK (minimum_linkage_coverage_ratio > 0
            AND minimum_linkage_coverage_ratio <= 1
            AND minimum_affected_set_coverage_ratio > 0
            AND minimum_affected_set_coverage_ratio <= 1),
    CONSTRAINT ad_conversion_definition_gap_ck
        CHECK (maximum_attribution_gap_ratio >= 0 AND maximum_attribution_gap_ratio <= 1),
    CONSTRAINT ad_conversion_definition_sample_ck CHECK (minimum_sample_events >= 1),
    CONSTRAINT ad_conversion_definition_window_ck
        CHECK (observation_window_days BETWEEN 1 AND 365),
    CONSTRAINT ad_conversion_definition_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_conversion_definition_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_conversion_definition_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_conversion_definition_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_conversion_definition_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            sale_stage WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE INDEX ad_conversion_definition_resolve_ix
    ON core.ad_conversion_definition
       (organization_id, sale_stage, effective_from DESC);

-- The economic ceiling, versioned and stage-bound. The stage column exists so
-- the check below can exist: an Allowable CPA is only ever multiplied by a
-- conversion of the same stage, and recording one without saying which sale
-- event it prices is not permitted.
CREATE TABLE core.ad_allowable_cpa_definition (
    id                     uuid           NOT NULL,
    organization_id        uuid           NOT NULL,
    definition_version     integer        NOT NULL,
    scope_kind             text           NOT NULL,
    platform_code          text,
    store_ref_id           uuid,
    product_variant_ref_id uuid,
    sale_stage             text           NOT NULL,
    currency_code          text           NOT NULL,
    contribution_basis     text           NOT NULL,
    target_contribution_retention_ratio numeric(6, 5) NOT NULL,
    return_loss_treatment  text           NOT NULL,
    owner_user_id          uuid           NOT NULL,
    reason                 text           NOT NULL,
    evidence_reference     text           NOT NULL,
    effective_from         timestamptz    NOT NULL,
    effective_to           timestamptz,
    status                 text           NOT NULL,
    created_at             timestamptz    NOT NULL,
    CONSTRAINT ad_allowable_cpa_definition_pk PRIMARY KEY (id),
    CONSTRAINT ad_allowable_cpa_definition_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_allowable_cpa_definition_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_allowable_cpa_definition_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_allowable_cpa_definition_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_allowable_cpa_definition_variant_fk
        FOREIGN KEY (product_variant_ref_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT ad_allowable_cpa_definition_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_allowable_cpa_definition_version_uq
        UNIQUE (organization_id, definition_version),
    CONSTRAINT ad_allowable_cpa_definition_version_ck CHECK (definition_version >= 1),
    CONSTRAINT ad_allowable_cpa_definition_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE', 'PRODUCT_VARIANT')),
    CONSTRAINT ad_allowable_cpa_definition_scope_shape_ck
        CHECK (num_nonnulls(platform_code, store_ref_id, product_variant_ref_id) =
            CASE scope_kind
                WHEN 'ORGANIZATION' THEN 0
                WHEN 'PLATFORM' THEN 1
                WHEN 'STORE' THEN 2
                ELSE 1
            END),
    -- Only the three canonical stages. An Allowable CPA cannot price a
    -- provider's own observation, because that observation is not a company
    -- sale event and has no contribution attached to it.
    CONSTRAINT ad_allowable_cpa_definition_stage_ck
        CHECK (sale_stage IN
            ('CANONICAL_AD_LINKED_ORDER', 'CANONICAL_AD_LINKED_COMPLETED_SALE',
             'CANONICAL_AD_LINKED_RETAINED_SALE')),
    CONSTRAINT ad_allowable_cpa_definition_basis_ck
        CHECK (contribution_basis IN
            ('OPERATIONAL_CONTRIBUTION', 'SETTLED_CONTRIBUTION')),
    -- Return, cancellation and refusal loss is either already inside the
    -- contribution basis for this stage or applied once on top of it. Saying
    -- which is mandatory, because the way to count it twice is to leave it
    -- unsaid.
    CONSTRAINT ad_allowable_cpa_definition_return_ck
        CHECK (return_loss_treatment IN
            ('INCLUDED_IN_STAGE_CONTRIBUTION', 'APPLIED_ONCE_ON_TOP')),
    -- A retained-stage sale has already survived the return window, so applying
    -- return loss again would deduct it twice.
    CONSTRAINT ad_allowable_cpa_definition_retained_ck
        CHECK (sale_stage <> 'CANONICAL_AD_LINKED_RETAINED_SALE'
            OR return_loss_treatment = 'INCLUDED_IN_STAGE_CONTRIBUTION'),
    CONSTRAINT ad_allowable_cpa_definition_retention_ck
        CHECK (target_contribution_retention_ratio > 0
            AND target_contribution_retention_ratio <= 1),
    CONSTRAINT ad_allowable_cpa_definition_currency_ck
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_allowable_cpa_definition_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_allowable_cpa_definition_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_allowable_cpa_definition_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_allowable_cpa_definition_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_allowable_cpa_definition_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, product_variant_ref_id,
                     '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            sale_stage WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE INDEX ad_allowable_cpa_definition_resolve_ix
    ON core.ad_allowable_cpa_definition
       (organization_id, sale_stage, effective_from DESC);

-- ---------------------------------------------------------------------------
-- Deterministic ad-linked sale events
-- ---------------------------------------------------------------------------

-- The numerator, one row per linked sale event, with the reason we believe the
-- link. Nothing here identifies a buyer: the linkage bases are all structural
-- or platform-reported, and there is deliberately no column that could carry a
-- name, a phone number or an address. That is Contract §6.7 and the reason the
-- table stores a listing variant rather than an order party.
--
-- The event is append-only and carries its own stage, so a Retained event and
-- the Completed event it grew out of are two rows, not one row mutated. A
-- cancellation is a superseding row, which is what lets a late correction
-- change a conversion without rewriting the observation it corrected.
CREATE TABLE ledger.ad_linked_sale_event (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    provenance_id               uuid           NOT NULL,
    ad_native_object_id         uuid           NOT NULL,
    affected_set_id             uuid           NOT NULL,
    platform_listing_variant_id uuid           NOT NULL,
    conversion_definition_id    uuid           NOT NULL,
    sale_stage                  text           NOT NULL,
    linkage_basis               text           NOT NULL,
    linkage_evidence_ref        text           NOT NULL,
    event_count                 integer        NOT NULL,
    net_sales_amount            numeric(18, 4),
    currency_code               text,
    occurred_at                 timestamptz    NOT NULL,
    period_start                timestamptz    NOT NULL,
    period_end                  timestamptz    NOT NULL,
    source_time                 timestamptz    NOT NULL,
    supersedes_event_id         uuid,
    adjustment_kind             text,
    recorded_at                 timestamptz    NOT NULL,
    CONSTRAINT ad_linked_sale_event_pk PRIMARY KEY (id),
    CONSTRAINT ad_linked_sale_event_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT ad_linked_sale_event_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_linked_sale_event_affected_set_fk
        FOREIGN KEY (affected_set_id, organization_id)
        REFERENCES core.ad_affected_set (id, organization_id),
    CONSTRAINT ad_linked_sale_event_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT ad_linked_sale_event_definition_fk
        FOREIGN KEY (conversion_definition_id, organization_id)
        REFERENCES core.ad_conversion_definition (id, organization_id),
    CONSTRAINT ad_linked_sale_event_supersedes_fk
        FOREIGN KEY (supersedes_event_id) REFERENCES ledger.ad_linked_sale_event (id),
    CONSTRAINT ad_linked_sale_event_stage_ck
        CHECK (sale_stage IN
            ('CANONICAL_AD_LINKED_ORDER', 'CANONICAL_AD_LINKED_COMPLETED_SALE',
             'CANONICAL_AD_LINKED_RETAINED_SALE')),
    CONSTRAINT ad_linked_sale_event_linkage_ck
        CHECK (linkage_basis IN
            ('DETERMINISTIC_OBJECT_LINKAGE', 'DETERMINISTIC_GOVERNED_SCOPE_LINKAGE')),
    CONSTRAINT ad_linked_sale_event_count_ck CHECK (event_count >= 1),
    CONSTRAINT ad_linked_sale_event_money_ck
        CHECK ((net_sales_amount IS NULL) = (currency_code IS NULL)),
    CONSTRAINT ad_linked_sale_event_currency_ck
        CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_linked_sale_event_period_ck CHECK (period_start < period_end),
    CONSTRAINT ad_linked_sale_event_occurred_ck
        CHECK (occurred_at >= period_start AND occurred_at < period_end),
    CONSTRAINT ad_linked_sale_event_evidence_ck
        CHECK (length(btrim(linkage_evidence_ref)) BETWEEN 1 AND 512),
    CONSTRAINT ad_linked_sale_event_adjustment_ck
        CHECK (adjustment_kind IS NULL
            OR adjustment_kind IN ('LATE_ARRIVAL', 'CORRECTION', 'REVERSAL')),
    CONSTRAINT ad_linked_sale_event_correction_ck
        CHECK (adjustment_kind IS NULL OR supersedes_event_id IS NOT NULL)
);

CREATE INDEX ad_linked_sale_event_object_ix
    ON ledger.ad_linked_sale_event
       (ad_native_object_id, sale_stage, period_start DESC);
CREATE INDEX ad_linked_sale_event_variant_ix
    ON ledger.ad_linked_sale_event (platform_listing_variant_id, occurred_at DESC);
CREATE INDEX ad_linked_sale_event_supersedes_ix
    ON ledger.ad_linked_sale_event (supersedes_event_id)
    WHERE supersedes_event_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Purpose-specific freshness
-- ---------------------------------------------------------------------------

-- Evidence kind, platform scope, decision purpose. Three keys, because the
-- answer genuinely differs along all three: an official spend report and a
-- company settlement age at different rates, Ozon and Wildberries publish and
-- correct on different schedules, and a queue observation and a bid write are
-- allowed to believe very different things.
--
-- The columns that make this more than a TTL are the last four.
-- `accepted_fact_max_age_minutes` is what stops a back-fill from looking
-- current. `expected_publication_lag_minutes` and
-- `correction_window_minutes` are what let a mature cohort stay usable while a
-- still-correcting one does not. `requires_window_complete` is what refuses a
-- partial reporting window for a purpose that cannot tolerate one.
CREATE TABLE core.ad_freshness_profile (
    id                              uuid        NOT NULL,
    organization_id                 uuid        NOT NULL,
    profile_version                 integer     NOT NULL,
    evidence_kind                   text        NOT NULL,
    decision_purpose                text        NOT NULL,
    scope_kind                      text        NOT NULL,
    platform_code                   text,
    store_ref_id                    uuid,
    semantic_profile_id             uuid,
    source_max_age_minutes          integer,
    accepted_fact_max_age_minutes   integer,
    expected_publication_lag_minutes integer    NOT NULL,
    correction_window_minutes       integer     NOT NULL,
    requires_window_complete        boolean     NOT NULL,
    requires_correction_window_closed boolean   NOT NULL,
    minimum_coverage_ratio          numeric(6, 5),
    minimum_confidence_state        text        NOT NULL,
    provider_incident_blocks        boolean     NOT NULL,
    owner_user_id                   uuid        NOT NULL,
    reason                          text        NOT NULL,
    evidence_reference              text        NOT NULL,
    effective_from                  timestamptz NOT NULL,
    effective_to                    timestamptz,
    status                          text        NOT NULL,
    created_at                      timestamptz NOT NULL,
    CONSTRAINT ad_freshness_profile_pk PRIMARY KEY (id),
    CONSTRAINT ad_freshness_profile_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_freshness_profile_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_freshness_profile_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_freshness_profile_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_freshness_profile_semantic_fk
        FOREIGN KEY (semantic_profile_id)
        REFERENCES platform.ad_semantic_profile (id),
    CONSTRAINT ad_freshness_profile_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_freshness_profile_version_uq
        UNIQUE (organization_id, evidence_kind, decision_purpose, profile_version),
    CONSTRAINT ad_freshness_profile_version_ck CHECK (profile_version >= 1),
    CONSTRAINT ad_freshness_profile_evidence_kind_ck
        CHECK (evidence_kind IN (
            'AD_OBJECT_CONFIGURATION', 'OFFICIAL_AD_SPEND', 'OFFICIAL_AD_TRAFFIC',
            'PROVIDER_ATTRIBUTION', 'AD_LINKED_SALE_EVENT', 'COMPANY_ORDER',
            'COMPANY_COMPLETED_SALE', 'COMPANY_RETAINED_SALE', 'SETTLEMENT',
            'COST_AND_FEE', 'PRODUCT_MAPPING', 'AFFECTED_SET',
            'SELLABILITY', 'AVAILABILITY', 'CAPABILITY_EVIDENCE')),
    -- The ten decision purposes the Contract names, in the order the
    -- monotonicity rule reads them.
    CONSTRAINT ad_freshness_profile_purpose_ck
        CHECK (decision_purpose IN (
            'QUEUE_OBSERVATION', 'TASK_ACTIVATION',
            'PROTECTION_RECOMMENDATION', 'OPTIMIZATION_RECOMMENDATION',
            'PROTECTION_BID_WRITE', 'OPTIMIZATION_BID_WRITE',
            'EXACT_COMPENSATION', 'EARLY_COMPLETED_SALES_OUTCOME',
            'FINAL_RETAINED_SALES_OUTCOME', 'SETTLED_FINANCIAL_OUTCOME')),
    CONSTRAINT ad_freshness_profile_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE', 'SEMANTIC_PROFILE')),
    CONSTRAINT ad_freshness_profile_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION'
                AND platform_code IS NULL AND store_ref_id IS NULL
                AND semantic_profile_id IS NULL)
            OR (scope_kind = 'PLATFORM'
                AND platform_code IS NOT NULL AND store_ref_id IS NULL
                AND semantic_profile_id IS NULL)
            OR (scope_kind = 'STORE'
                AND platform_code IS NOT NULL AND store_ref_id IS NOT NULL
                AND semantic_profile_id IS NULL)
            OR (scope_kind = 'SEMANTIC_PROFILE'
                AND platform_code IS NOT NULL AND semantic_profile_id IS NOT NULL)),
    CONSTRAINT ad_freshness_profile_source_age_ck
        CHECK (source_max_age_minutes IS NULL
            OR source_max_age_minutes BETWEEN 1 AND 525600),
    CONSTRAINT ad_freshness_profile_accepted_age_ck
        CHECK (accepted_fact_max_age_minutes IS NULL
            OR accepted_fact_max_age_minutes BETWEEN 1 AND 525600),
    -- A profile that bounds neither the source age nor the accepted age bounds
    -- nothing, which is not a freshness rule.
    CONSTRAINT ad_freshness_profile_bound_ck
        CHECK (num_nonnulls(source_max_age_minutes, accepted_fact_max_age_minutes) >= 1),
    CONSTRAINT ad_freshness_profile_lag_ck
        CHECK (expected_publication_lag_minutes BETWEEN 0 AND 525600),
    CONSTRAINT ad_freshness_profile_correction_ck
        CHECK (correction_window_minutes BETWEEN 0 AND 525600),
    CONSTRAINT ad_freshness_profile_coverage_ck
        CHECK (minimum_coverage_ratio IS NULL
            OR (minimum_coverage_ratio > 0 AND minimum_coverage_ratio <= 1)),
    CONSTRAINT ad_freshness_profile_confidence_ck
        CHECK (minimum_confidence_state IN
            ('CANONICAL_CONFIRMED', 'CANONICAL_PENDING_SETTLEMENT',
             'ESTIMATED_EXPLAINED', 'UNKNOWN')),
    -- A write purpose may never accept an estimate. This is the floor the
    -- monotonicity rule sits above, and it is a constraint rather than a
    -- service check because a service check is what a refactor loses.
    CONSTRAINT ad_freshness_profile_write_confidence_ck
        CHECK (decision_purpose NOT IN
                ('PROTECTION_BID_WRITE', 'OPTIMIZATION_BID_WRITE', 'EXACT_COMPENSATION')
            OR minimum_confidence_state IN
                ('CANONICAL_CONFIRMED', 'CANONICAL_PENDING_SETTLEMENT')),
    -- A write or a settled outcome cannot rest on a reporting window the
    -- marketplace has not finished publishing.
    CONSTRAINT ad_freshness_profile_write_window_ck
        CHECK (decision_purpose NOT IN
                ('OPTIMIZATION_BID_WRITE', 'SETTLED_FINANCIAL_OUTCOME')
            OR requires_window_complete),
    CONSTRAINT ad_freshness_profile_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_freshness_profile_evidence_ref_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_freshness_profile_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_freshness_profile_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_freshness_profile_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            evidence_kind WITH =,
            decision_purpose WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, semantic_profile_id,
                     '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE INDEX ad_freshness_profile_resolve_ix
    ON core.ad_freshness_profile
       (organization_id, evidence_kind, decision_purpose, effective_from DESC);

-- ---------------------------------------------------------------------------
-- Optimization qualification
-- ---------------------------------------------------------------------------

-- What separates an opportunity from a Watch. Every column is one of the
-- eleven dimensions the Contract enumerates, so "qualified" has exactly one
-- meaning and a missing dimension is a missing policy rather than a silent
-- default.
--
-- `purpose_tier` is how the monotonic order WATCH <= OPTIMIZATION_TASK <=
-- OPTIMIZATION_RECOMMENDATION <= OPTIMIZATION_BID_WRITE is expressed as data:
-- four rows for one scope, each strictly harder than the last, checked by
-- core.ad_qualification_tier_is_monotonic below.
CREATE TABLE core.ad_optimization_qualification_policy (
    id                              uuid           NOT NULL,
    organization_id                 uuid           NOT NULL,
    policy_version                  integer        NOT NULL,
    purpose_tier                    text           NOT NULL,
    scope_kind                      text           NOT NULL,
    platform_code                   text,
    store_ref_id                    uuid,
    eligible_observation_window_days integer       NOT NULL,
    minimum_source_coverage_ratio   numeric(6, 5)  NOT NULL,
    minimum_affected_set_coverage_ratio numeric(6, 5) NOT NULL,
    minimum_traffic_denominator     bigint         NOT NULL,
    minimum_completed_sale_events   integer        NOT NULL,
    minimum_retained_sale_events    integer        NOT NULL,
    minimum_spend_amount            numeric(18, 4) NOT NULL,
    currency_code                   text           NOT NULL,
    minimum_sustained_periods       integer        NOT NULL,
    minimum_recoverable_amount      numeric(18, 4) NOT NULL,
    requires_correction_window_closed boolean      NOT NULL,
    requires_comparable_baseline    boolean        NOT NULL,
    minimum_confidence_state        text           NOT NULL,
    boundary_inclusive              boolean        NOT NULL,
    owner_user_id                   uuid           NOT NULL,
    reason                          text           NOT NULL,
    evidence_reference              text           NOT NULL,
    effective_from                  timestamptz    NOT NULL,
    effective_to                    timestamptz,
    status                          text           NOT NULL,
    created_at                      timestamptz    NOT NULL,
    CONSTRAINT ad_optimization_qualification_policy_pk PRIMARY KEY (id),
    CONSTRAINT ad_optimization_qualification_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_optimization_qualification_policy_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_optimization_qualification_policy_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_optimization_qualification_policy_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_optimization_qualification_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_optimization_qualification_policy_version_uq
        UNIQUE (organization_id, purpose_tier, policy_version),
    CONSTRAINT ad_optimization_qualification_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT ad_optimization_qualification_policy_tier_ck
        CHECK (purpose_tier IN
            ('WATCH', 'OPTIMIZATION_TASK', 'OPTIMIZATION_RECOMMENDATION',
             'OPTIMIZATION_BID_WRITE')),
    CONSTRAINT ad_optimization_qualification_policy_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT ad_optimization_qualification_policy_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION'
                AND platform_code IS NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'PLATFORM'
                AND platform_code IS NOT NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'STORE'
                AND platform_code IS NOT NULL AND store_ref_id IS NOT NULL)),
    CONSTRAINT ad_optimization_qualification_policy_window_ck
        CHECK (eligible_observation_window_days BETWEEN 1 AND 365),
    CONSTRAINT ad_optimization_qualification_policy_coverage_ck
        CHECK (minimum_source_coverage_ratio > 0
            AND minimum_source_coverage_ratio <= 1
            AND minimum_affected_set_coverage_ratio > 0
            AND minimum_affected_set_coverage_ratio <= 1),
    CONSTRAINT ad_optimization_qualification_policy_traffic_ck
        CHECK (minimum_traffic_denominator >= 0),
    CONSTRAINT ad_optimization_qualification_policy_events_ck
        CHECK (minimum_completed_sale_events >= 0 AND minimum_retained_sale_events >= 0),
    CONSTRAINT ad_optimization_qualification_policy_amounts_ck
        CHECK (minimum_spend_amount >= 0 AND minimum_recoverable_amount >= 0),
    CONSTRAINT ad_optimization_qualification_policy_periods_ck
        CHECK (minimum_sustained_periods >= 1),
    CONSTRAINT ad_optimization_qualification_policy_currency_ck
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_optimization_qualification_policy_confidence_ck
        CHECK (minimum_confidence_state IN
            ('CANONICAL_CONFIRMED', 'CANONICAL_PENDING_SETTLEMENT',
             'ESTIMATED_EXPLAINED', 'UNKNOWN')),
    -- The write tier cannot qualify on an estimate, cannot skip the correction
    -- window and cannot proceed without a comparable baseline. These are the
    -- floors; the monotonicity check makes the tiers above them consistent.
    CONSTRAINT ad_optimization_qualification_policy_write_floor_ck
        CHECK (purpose_tier <> 'OPTIMIZATION_BID_WRITE'
            OR (minimum_confidence_state IN
                    ('CANONICAL_CONFIRMED', 'CANONICAL_PENDING_SETTLEMENT')
                AND requires_correction_window_closed
                AND requires_comparable_baseline
                AND minimum_completed_sale_events >= 1
                AND minimum_traffic_denominator >= 1)),
    CONSTRAINT ad_optimization_qualification_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_optimization_qualification_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_optimization_qualification_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_optimization_qualification_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_optimization_qualification_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            purpose_tier WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE INDEX ad_optimization_qualification_policy_resolve_ix
    ON core.ad_optimization_qualification_policy
       (organization_id, purpose_tier, effective_from DESC);

-- ---------------------------------------------------------------------------
-- Monotonicity, as a function the Bundle validation calls
-- ---------------------------------------------------------------------------

-- WATCH <= OPTIMIZATION_TASK <= OPTIMIZATION_RECOMMENDATION <=
-- OPTIMIZATION_BID_WRITE, on every dimension that can be ordered.
--
-- This is a function rather than a table constraint because the property is
-- about four rows at once, and a row-level CHECK cannot see its siblings. It is
-- STABLE and called by the Policy Bundle's whole-combination validation, which
-- is the only place where the four tiers are known to be the set that will be
-- used together.
CREATE FUNCTION core.ad_qualification_tier_is_monotonic(
    p_organization_id uuid,
    p_scope_kind      text,
    p_platform_code   text,
    p_store_ref_id    uuid,
    p_at              timestamptz)
RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, core, pg_temp
AS $$
    WITH tiers AS (
        SELECT p.purpose_tier,
               CASE p.purpose_tier
                   WHEN 'WATCH' THEN 1
                   WHEN 'OPTIMIZATION_TASK' THEN 2
                   WHEN 'OPTIMIZATION_RECOMMENDATION' THEN 3
                   ELSE 4
               END AS rank,
               p.minimum_source_coverage_ratio,
               p.minimum_affected_set_coverage_ratio,
               p.minimum_traffic_denominator,
               p.minimum_completed_sale_events,
               p.minimum_retained_sale_events,
               p.minimum_spend_amount,
               p.minimum_sustained_periods,
               p.minimum_recoverable_amount,
               CASE p.minimum_confidence_state
                   WHEN 'UNKNOWN' THEN 1
                   WHEN 'ESTIMATED_EXPLAINED' THEN 2
                   WHEN 'CANONICAL_PENDING_SETTLEMENT' THEN 3
                   ELSE 4
               END AS confidence_rank,
               p.requires_correction_window_closed::integer AS correction_rank,
               p.requires_comparable_baseline::integer AS baseline_rank
          FROM core.ad_optimization_qualification_policy AS p
         WHERE p.organization_id = p_organization_id
           AND p.scope_kind = p_scope_kind
           AND p.platform_code IS NOT DISTINCT FROM p_platform_code
           AND p.store_ref_id IS NOT DISTINCT FROM p_store_ref_id
           AND p.status IN ('ACTIVE', 'RETIRED')
           AND p.effective_from <= p_at
           AND (p.effective_to IS NULL OR p.effective_to > p_at)
    )
    SELECT count(*) = 4
       AND bool_and(
               lower_tier.minimum_source_coverage_ratio
                   <= higher_tier.minimum_source_coverage_ratio
           AND lower_tier.minimum_affected_set_coverage_ratio
                   <= higher_tier.minimum_affected_set_coverage_ratio
           AND lower_tier.minimum_traffic_denominator
                   <= higher_tier.minimum_traffic_denominator
           AND lower_tier.minimum_completed_sale_events
                   <= higher_tier.minimum_completed_sale_events
           AND lower_tier.minimum_retained_sale_events
                   <= higher_tier.minimum_retained_sale_events
           AND lower_tier.minimum_spend_amount <= higher_tier.minimum_spend_amount
           AND lower_tier.minimum_sustained_periods
                   <= higher_tier.minimum_sustained_periods
           AND lower_tier.minimum_recoverable_amount
                   <= higher_tier.minimum_recoverable_amount
           AND lower_tier.confidence_rank <= higher_tier.confidence_rank
           AND lower_tier.correction_rank <= higher_tier.correction_rank
           AND lower_tier.baseline_rank <= higher_tier.baseline_rank)
      FROM tiers AS lower_tier
      JOIN tiers AS higher_tier ON higher_tier.rank = lower_tier.rank + 1
$$;

REVOKE ALL ON FUNCTION core.ad_qualification_tier_is_monotonic(uuid, text, text, uuid, timestamptz)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.ad_qualification_tier_is_monotonic(uuid, text, text, uuid, timestamptz)
    TO marketops_app;

-- The same idea for Freshness: a write purpose may never accept evidence a
-- recommendation purpose would refuse. Returns the purposes that violate the
-- order, so a Bundle validation failure can say which pair is wrong rather than
-- only that something is.
CREATE FUNCTION core.ad_freshness_purpose_violations(
    p_organization_id uuid,
    p_at              timestamptz)
RETURNS text[]
LANGUAGE sql STABLE
SET search_path = pg_catalog, core, pg_temp
AS $$
    WITH live AS (
        SELECT f.evidence_kind,
               f.decision_purpose,
               coalesce(f.source_max_age_minutes, 2147483647) AS source_age,
               coalesce(f.accepted_fact_max_age_minutes, 2147483647) AS accepted_age,
               f.requires_window_complete::integer AS window_rank,
               f.requires_correction_window_closed::integer AS correction_rank,
               CASE f.minimum_confidence_state
                   WHEN 'UNKNOWN' THEN 1
                   WHEN 'ESTIMATED_EXPLAINED' THEN 2
                   WHEN 'CANONICAL_PENDING_SETTLEMENT' THEN 3
                   ELSE 4
               END AS confidence_rank
          FROM core.ad_freshness_profile AS f
         WHERE f.organization_id = p_organization_id
           AND f.status IN ('ACTIVE', 'RETIRED')
           AND f.effective_from <= p_at
           AND (f.effective_to IS NULL OR f.effective_to > p_at)
    ),
    -- A write purpose and the recommendation purpose that feeds it. The write
    -- side must be at least as strict on every dimension.
    ordered AS (
        SELECT * FROM (VALUES
            ('PROTECTION_RECOMMENDATION', 'PROTECTION_BID_WRITE'),
            ('OPTIMIZATION_RECOMMENDATION', 'OPTIMIZATION_BID_WRITE'),
            ('QUEUE_OBSERVATION', 'TASK_ACTIVATION'),
            ('TASK_ACTIVATION', 'PROTECTION_RECOMMENDATION'),
            ('TASK_ACTIVATION', 'OPTIMIZATION_RECOMMENDATION'),
            ('PROTECTION_BID_WRITE', 'EXACT_COMPENSATION')
        ) AS pairs(weaker_purpose, stronger_purpose)
    )
    SELECT coalesce(
        array_agg(DISTINCT
            ordered.weaker_purpose || '>' || ordered.stronger_purpose
            ORDER BY ordered.weaker_purpose || '>' || ordered.stronger_purpose),
        '{}')
      FROM ordered
      JOIN live AS weaker
        ON weaker.decision_purpose = ordered.weaker_purpose
      JOIN live AS stronger
        ON stronger.decision_purpose = ordered.stronger_purpose
       AND stronger.evidence_kind = weaker.evidence_kind
     WHERE stronger.source_age > weaker.source_age
        OR stronger.accepted_age > weaker.accepted_age
        OR stronger.window_rank < weaker.window_rank
        OR stronger.correction_rank < weaker.correction_rank
        OR stronger.confidence_rank < weaker.confidence_rank
$$;

REVOKE ALL ON FUNCTION core.ad_freshness_purpose_violations(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.ad_freshness_purpose_violations(uuid, timestamptz)
    TO marketops_app;

-- ---------------------------------------------------------------------------
-- Control routing and privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'ad_conversion_definition', 'NO_ROUTE', NULL,
        'versioned canonical conversion definition; read by metric calculation only'),
    ('core', 'ad_allowable_cpa_definition', 'NO_ROUTE', NULL,
        'versioned stage-bound economic ceiling; read by metric calculation only'),
    ('core', 'ad_freshness_profile', 'NO_ROUTE', NULL,
        'versioned purpose-specific freshness rules; fails closed when absent'),
    ('core', 'ad_optimization_qualification_policy', 'NO_ROUTE', NULL,
        'versioned purpose-tiered optimization qualification; fails closed when absent'),
    ('ledger', 'ad_linked_sale_event', 'NO_ROUTE', NULL,
        'append-only deterministic ad-linked sale events; carries no buyer identity');

-- Definitions and policies are published through the application, so it may
-- insert and may retire; it may never delete, and a version is immutable once
-- written except for the retirement columns.
GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_conversion_definition TO marketops_app;
GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_allowable_cpa_definition TO marketops_app;
GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_freshness_profile TO marketops_app;
GRANT SELECT, INSERT, UPDATE (status, effective_to)
    ON core.ad_optimization_qualification_policy TO marketops_app;
GRANT SELECT, INSERT ON ledger.ad_linked_sale_event TO marketops_app;
