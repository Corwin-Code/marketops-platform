-- Advertising identity and the official facts a decision is allowed to rest on.
--
-- Four ideas run through this file, and every constraint below is one of them
-- made unavoidable.
--
-- First, the unit of control is the object the platform actually lets you bid
-- on, not the SKU we would prefer to reason about. A campaign, an ad group, a
-- target or a keyword routinely drives several internal variants at once, and
-- no marketplace offers a way to bid on a share of one. So the native object is
-- the identity, the complete affected variant set travels with it as an ordered
-- array plus a digest, and an object whose independent controllability has not
-- been proven carries that as a recorded fact rather than an assumption.
--
-- Second, the platform's own attribution is an observation, not our sales. The
-- official object fact below carries spend, traffic and provider-attributed
-- orders and revenue side by side, and nothing in this file lets one become the
-- other. A missing measure is NULL, which the product reads as NOT_AVAILABLE.
-- It is never zero, because zero spend and unreported spend lead to opposite
-- decisions.
--
-- Third, platform semantics are versioned and may be unverified without being
-- absent. Ozon and Wildberries are not forced into symmetry: each carries its
-- own profile version describing object kinds, bidding mode, bid unit,
-- precision, step, bounds, status and error classes, idempotency, propagation,
-- readback and correction behaviour. A profile whose source maturity is a
-- synthetic fixture can never be VERIFIED — that is a check constraint, so a
-- fixture cannot be promoted into evidence by an UPDATE.
--
-- Fourth, corrections are facts. Official spend and traffic are restated by
-- both marketplaces after the fact, so the object fact table is append-only
-- with an explicit supersession link and an adjustment kind, exactly as the
-- cross-domain operating facts already are. A restatement is a new row that
-- triggers attributable recalculation; it never edits history.
--
-- No table in this file is part of a platform write path. There is no command,
-- outbox, readback target or bid write here. production_write_enabled remains
-- false and every advertising Provider capability remains unverified.

-- ---------------------------------------------------------------------------
-- Action scopes for the advertising operating loop
-- ---------------------------------------------------------------------------

-- Seeded rather than administered, for the same reason the availability scopes
-- were: widening what a role may do has to be a migration a reviewer can see.
-- No new role is invented. The Contract's decision owners are the existing
-- OPS_LEAD, MARKETPLACE_OPERATOR, OWNER, FINANCE_ANALYST, RISK_AUTHORITY and
-- AUDITOR, and the platform/security authority is the existing kill-switch
-- operator.
INSERT INTO iam.action_scope (code, display_name, description, requires_step_up, ordinal) VALUES
    ('ADVERTISING_VIEW', 'View advertising efficiency',
        'Read the advertising control queue, its cases, evidence and outcomes.', false, 17),
    ('ADVERTISING_TASK_ACT', 'Act on an advertising case',
        'Record structured action evidence against an accountable advertising case.', false, 18),
    ('ADVERTISING_EXCEPTION_REQUEST', 'Request advertising accepted risk',
        'Request a scoped, expiring accepted exception against a calculated advertising risk.', false, 19),
    ('AD_BID_CHANGE_ENDORSE', 'Endorse a bid change',
        'Give the distinct operational endorsement a bid change requires before final approval.', true, 20),
    ('AD_BID_CHANGE_APPROVE', 'Approve a bid change',
        'Give the final per-command approval for an exact, bounded advertising bid change.', true, 21),
    ('ADVERTISING_POLICY_MANAGE', 'Manage advertising policy',
        'Publish or retire advertising freshness, qualification, target, outcome, priority, SLO, lease and exposure policy.', true, 22);

-- The reviewed matrix. Reading stays broad. Acting on a case belongs to the
-- operator and the lead. Endorsement and final approval are deliberately
-- separated so one person cannot hold both halves of the Maker-Checker chain:
-- the Operations Lead endorses, the Owner approves, and no role below them
-- carries either. AUDITOR receives no mutating action at all.
INSERT INTO iam.business_role_action_scope (role_code, action_code)
SELECT role_code, action_code
  FROM (VALUES
    ('OWNER', 'ADVERTISING_VIEW'),
    ('OWNER', 'ADVERTISING_TASK_ACT'),
    ('OWNER', 'ADVERTISING_EXCEPTION_REQUEST'),
    ('OWNER', 'AD_BID_CHANGE_ENDORSE'),
    ('OWNER', 'AD_BID_CHANGE_APPROVE'),
    ('OWNER', 'ADVERTISING_POLICY_MANAGE'),
    ('OPS_LEAD', 'ADVERTISING_VIEW'),
    ('OPS_LEAD', 'ADVERTISING_TASK_ACT'),
    ('OPS_LEAD', 'ADVERTISING_EXCEPTION_REQUEST'),
    ('OPS_LEAD', 'AD_BID_CHANGE_ENDORSE'),
    ('OPS_LEAD', 'ADVERTISING_POLICY_MANAGE'),
    ('MARKETPLACE_OPERATOR', 'ADVERTISING_VIEW'),
    ('MARKETPLACE_OPERATOR', 'ADVERTISING_TASK_ACT'),
    ('MARKETPLACE_OPERATOR', 'ADVERTISING_EXCEPTION_REQUEST'),
    -- Data Repair routes to the technical-data owner, so that role can see and
    -- act on an advertising case without ever touching the approval chain.
    ('TECH_DATA', 'ADVERTISING_VIEW'),
    ('TECH_DATA', 'ADVERTISING_TASK_ACT'),
    ('FINANCE_ANALYST', 'ADVERTISING_VIEW'),
    ('FINANCE_ANALYST', 'ADVERTISING_TASK_ACT'),
    ('FINANCE', 'ADVERTISING_VIEW'),
    ('RISK_AUTHORITY', 'ADVERTISING_VIEW'),
    ('OPERATIONS', 'ADVERTISING_VIEW'),
    ('OPERATIONS', 'ADVERTISING_TASK_ACT'),
    ('OPERATIONS', 'ADVERTISING_EXCEPTION_REQUEST'),
    ('AUDITOR', 'ADVERTISING_VIEW')
  ) AS matrix(role_code, action_code);

-- ---------------------------------------------------------------------------
-- The advertising module becomes an audit source domain
-- ---------------------------------------------------------------------------

ALTER TABLE ops.metadata_audit_event
    DROP CONSTRAINT metadata_audit_event_source_domain_ck;
ALTER TABLE ops.metadata_audit_event
    ADD CONSTRAINT metadata_audit_event_source_domain_ck
    CHECK (source_domain IN (
        'organizationaccount', 'identityaccess', 'marketplaceintegration',
        'adminobservability', 'productlisting', 'operatingfacts',
        'analyticsdecision', 'aicopilot', 'operationsworkflow',
        'availabilityrisk', 'advertisingefficiency'));

-- ---------------------------------------------------------------------------
-- Platform-native advertising semantics
-- ---------------------------------------------------------------------------

-- One versioned description of what an advertising object means on one
-- platform. This is the type that keeps Ozon and Wildberries from being forced
-- into a false symmetry: they carry different object hierarchies, different bid
-- units and steps, different status vocabularies and different correction
-- behaviour, and the product reads all of that from here rather than from a
-- branch in an adapter.
--
-- Unknown is representable on purpose. A profile may be ACTIVE and describe
-- honestly that the bidding mode, the step or the readback behaviour is not
-- known, and every purpose that consumes the unknown field then fails closed.
-- What a profile may not do is claim verification it does not have.
CREATE TABLE platform.ad_semantic_profile (
    id                     uuid           NOT NULL,
    platform_code          text           NOT NULL,
    profile_version        integer        NOT NULL,
    native_object_kind     text           NOT NULL,
    control_level          text           NOT NULL,
    bidding_mode           text           NOT NULL,
    bid_field_present      boolean        NOT NULL,
    bid_currency_code      text,
    bid_unit_code          text           NOT NULL,
    bid_precision          integer,
    bid_step               numeric(18, 4),
    bid_minimum            numeric(18, 4),
    bid_maximum            numeric(18, 4),
    status_semantics       jsonb          NOT NULL DEFAULT '{}'::jsonb,
    error_class_semantics  jsonb          NOT NULL DEFAULT '{}'::jsonb,
    quota_semantics        jsonb          NOT NULL DEFAULT '{}'::jsonb,
    idempotency_semantics  text           NOT NULL,
    propagation_semantics  text           NOT NULL,
    readback_semantics     text           NOT NULL,
    correction_behaviour   text           NOT NULL,
    source_maturity        text           NOT NULL,
    verification_state     text           NOT NULL,
    last_verified_at       timestamptz,
    evidence_ref           text,
    verified_source_title  text,
    owner_label            text           NOT NULL,
    status                 text           NOT NULL,
    created_at             timestamptz    NOT NULL,
    updated_at             timestamptz    NOT NULL,
    version                bigint         NOT NULL DEFAULT 0,
    CONSTRAINT ad_semantic_profile_pk PRIMARY KEY (id),
    CONSTRAINT ad_semantic_profile_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_semantic_profile_identity_uq
        UNIQUE (platform_code, native_object_kind, profile_version),
    CONSTRAINT ad_semantic_profile_id_platform_uq UNIQUE (id, platform_code),
    CONSTRAINT ad_semantic_profile_version_ck CHECK (profile_version >= 1),
    CONSTRAINT ad_semantic_profile_object_kind_ck
        CHECK (native_object_kind IN
            ('CAMPAIGN', 'AD_GROUP', 'TARGET', 'KEYWORD', 'PLACEMENT', 'UNKNOWN')),
    CONSTRAINT ad_semantic_profile_control_level_ck
        CHECK (control_level IN
            ('CAMPAIGN', 'AD_GROUP', 'TARGET', 'KEYWORD', 'PLACEMENT', 'UNKNOWN')),
    CONSTRAINT ad_semantic_profile_bidding_mode_ck
        CHECK (bidding_mode IN ('MANUAL_BID', 'AUTO_BID', 'MIXED', 'UNKNOWN')),
    CONSTRAINT ad_semantic_profile_bid_unit_ck
        CHECK (bid_unit_code IN ('CURRENCY_MAJOR', 'CURRENCY_MINOR', 'UNKNOWN')),
    CONSTRAINT ad_semantic_profile_idempotency_ck
        CHECK (idempotency_semantics IN
            ('VERIFIED_NATIVE_KEY', 'NO_VERIFIED_IDEMPOTENCY',
             'EXPLICIT_NOT_APPLIED_EVIDENCE', 'UNKNOWN')),
    CONSTRAINT ad_semantic_profile_propagation_ck
        CHECK (propagation_semantics IN
            ('SYNCHRONOUS', 'EVENTUAL_BOUNDED', 'EVENTUAL_UNBOUNDED', 'UNKNOWN')),
    CONSTRAINT ad_semantic_profile_readback_ck
        CHECK (readback_semantics IN
            ('EXACT_FIELD', 'DERIVED_FIELD', 'NOT_AVAILABLE', 'UNKNOWN')),
    CONSTRAINT ad_semantic_profile_correction_ck
        CHECK (correction_behaviour IN
            ('APPEND_ONLY_CORRECTION', 'IN_PLACE_RESTATEMENT', 'UNKNOWN')),
    CONSTRAINT ad_semantic_profile_maturity_ck
        CHECK (source_maturity IN
            ('OFFICIAL_VERIFIED', 'OFFICIAL_UNVERIFIED', 'SYNTHETIC_FIXTURE')),
    CONSTRAINT ad_semantic_profile_verification_ck
        CHECK (verification_state IN ('UNKNOWN', 'UNVERIFIED', 'VERIFIED')),
    CONSTRAINT ad_semantic_profile_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    -- A synthetic fixture is never evidence about a real marketplace. Without
    -- this the engineering profiles shipped for tests could be promoted to
    -- VERIFIED by an UPDATE and silently open a write path.
    CONSTRAINT ad_semantic_profile_fixture_ck
        CHECK (source_maturity <> 'SYNTHETIC_FIXTURE' OR verification_state <> 'VERIFIED'),
    -- Verification has to point at what was verified and when.
    CONSTRAINT ad_semantic_profile_provenance_ck
        CHECK (verification_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    -- A verified profile cannot leave the fields a bid decision consumes unknown.
    CONSTRAINT ad_semantic_profile_verified_bid_ck
        CHECK (verification_state <> 'VERIFIED'
            OR NOT bid_field_present
            OR (bid_currency_code IS NOT NULL
                AND bid_unit_code <> 'UNKNOWN'
                AND bid_precision IS NOT NULL
                AND bid_step IS NOT NULL
                AND bid_minimum IS NOT NULL
                AND bid_maximum IS NOT NULL
                AND bidding_mode <> 'UNKNOWN'
                AND readback_semantics NOT IN ('NOT_AVAILABLE', 'UNKNOWN'))),
    CONSTRAINT ad_semantic_profile_currency_ck
        CHECK (bid_currency_code IS NULL OR bid_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_semantic_profile_precision_ck
        CHECK (bid_precision IS NULL OR bid_precision BETWEEN 0 AND 4),
    CONSTRAINT ad_semantic_profile_step_ck CHECK (bid_step IS NULL OR bid_step > 0),
    CONSTRAINT ad_semantic_profile_bounds_ck
        CHECK (bid_minimum IS NULL OR bid_maximum IS NULL OR bid_minimum <= bid_maximum),
    CONSTRAINT ad_semantic_profile_minimum_ck CHECK (bid_minimum IS NULL OR bid_minimum >= 0),
    CONSTRAINT ad_semantic_profile_semantics_object_ck
        CHECK (jsonb_typeof(status_semantics) = 'object'
            AND jsonb_typeof(error_class_semantics) = 'object'
            AND jsonb_typeof(quota_semantics) = 'object'),
    CONSTRAINT ad_semantic_profile_owner_ck
        CHECK (length(btrim(owner_label)) BETWEEN 1 AND 128),
    CONSTRAINT ad_semantic_profile_evidence_ck
        CHECK (evidence_ref IS NULL OR length(btrim(evidence_ref)) BETWEEN 1 AND 512)
);

CREATE UNIQUE INDEX ad_semantic_profile_live_uq
    ON platform.ad_semantic_profile (platform_code, native_object_kind)
    WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- The native advertising object
-- ---------------------------------------------------------------------------

-- One row per advertising object we have actually observed on a platform,
-- inside one store. `control_granularity_state` is the load-bearing column: a
-- decision that would write a bid is only reachable when the platform's own
-- evidence proves this object can be controlled independently. Anything else is
-- diagnosable and permanently write-ineligible, which is a visible business
-- state rather than a silent gap.
--
-- Lineage is carried explicitly. Marketplaces rebuild, re-key and re-mode
-- advertising objects, and when they do the old executable decision assets must
-- die. `lineage_key` is the identity a continuity rule may reason about;
-- `lineage_generation` increments whenever the platform's own evidence shows the
-- object was rebuilt, so an old Preview bound to generation 3 cannot be executed
-- against generation 4.
CREATE TABLE core.ad_native_object (
    id                         uuid        NOT NULL,
    organization_id            uuid        NOT NULL,
    store_id                   uuid        NOT NULL,
    platform_code              text        NOT NULL,
    semantic_profile_id        uuid        NOT NULL,
    native_object_kind         text        NOT NULL,
    native_object_key          text        NOT NULL,
    native_campaign_key        text        NOT NULL,
    native_parent_key          text,
    native_object_name         text,
    bidding_mode               text        NOT NULL,
    control_granularity_state  text        NOT NULL,
    control_evidence_ref       text,
    lineage_key                text        NOT NULL,
    lineage_generation         integer     NOT NULL DEFAULT 1,
    observation_state          text        NOT NULL,
    first_observed_at          timestamptz NOT NULL,
    last_observed_at           timestamptz NOT NULL,
    status                     text        NOT NULL,
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    version                    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ad_native_object_pk PRIMARY KEY (id),
    CONSTRAINT ad_native_object_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_native_object_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_native_object_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_native_object_profile_fk
        FOREIGN KEY (semantic_profile_id, platform_code)
        REFERENCES platform.ad_semantic_profile (id, platform_code),
    CONSTRAINT ad_native_object_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_native_object_identity_uq
        UNIQUE (organization_id, store_id, native_object_kind, native_object_key),
    CONSTRAINT ad_native_object_kind_ck
        CHECK (native_object_kind IN
            ('CAMPAIGN', 'AD_GROUP', 'TARGET', 'KEYWORD', 'PLACEMENT', 'UNKNOWN')),
    CONSTRAINT ad_native_object_bidding_mode_ck
        CHECK (bidding_mode IN ('MANUAL_BID', 'AUTO_BID', 'MIXED', 'UNKNOWN')),
    -- PROVEN_INDEPENDENT is the only state a controlled write may ever consume.
    CONSTRAINT ad_native_object_control_ck
        CHECK (control_granularity_state IN
            ('PROVEN_INDEPENDENT', 'NOT_INDEPENDENTLY_CONTROLLABLE', 'UNKNOWN')),
    CONSTRAINT ad_native_object_control_evidence_ck
        CHECK (control_granularity_state <> 'PROVEN_INDEPENDENT'
            OR control_evidence_ref IS NOT NULL),
    CONSTRAINT ad_native_object_observation_ck
        CHECK (observation_state IN ('OBSERVED', 'NOT_OBSERVED', 'CONFLICTED', 'UNKNOWN')),
    CONSTRAINT ad_native_object_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ad_native_object_generation_ck CHECK (lineage_generation >= 1),
    CONSTRAINT ad_native_object_key_ck
        CHECK (length(btrim(native_object_key)) BETWEEN 1 AND 256),
    CONSTRAINT ad_native_object_campaign_key_ck
        CHECK (length(btrim(native_campaign_key)) BETWEEN 1 AND 256),
    CONSTRAINT ad_native_object_parent_key_ck
        CHECK (native_parent_key IS NULL
            OR length(btrim(native_parent_key)) BETWEEN 1 AND 256),
    CONSTRAINT ad_native_object_name_ck
        CHECK (native_object_name IS NULL
            OR length(btrim(native_object_name)) BETWEEN 1 AND 512),
    CONSTRAINT ad_native_object_lineage_key_ck
        CHECK (length(btrim(lineage_key)) BETWEEN 1 AND 256),
    CONSTRAINT ad_native_object_observed_order_ck
        CHECK (first_observed_at <= last_observed_at)
);

CREATE INDEX ad_native_object_store_ix
    ON core.ad_native_object (organization_id, store_id, native_object_kind);
CREATE INDEX ad_native_object_campaign_ix
    ON core.ad_native_object (organization_id, native_campaign_key);
CREATE INDEX ad_native_object_lineage_ix
    ON core.ad_native_object (organization_id, lineage_key, lineage_generation DESC);

-- The campaign / ad-group / target / keyword / SKU relationships the Contract
-- requires to stay queryable. This table describes structure that was observed;
-- it deliberately grants no control granularity of its own, so answering "which
-- keywords sit under this campaign" can never be mistaken for "these keywords
-- are separately biddable".
CREATE TABLE core.ad_object_relationship (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    parent_object_id            uuid        NOT NULL,
    child_object_id             uuid,
    platform_listing_variant_id uuid,
    relationship_kind           text        NOT NULL,
    observed_at                 timestamptz NOT NULL,
    provenance_id               uuid        NOT NULL,
    status                      text        NOT NULL,
    created_at                  timestamptz NOT NULL,
    CONSTRAINT ad_object_relationship_pk PRIMARY KEY (id),
    CONSTRAINT ad_object_relationship_parent_fk
        FOREIGN KEY (parent_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_object_relationship_child_fk
        FOREIGN KEY (child_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_object_relationship_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT ad_object_relationship_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT ad_object_relationship_kind_ck
        CHECK (relationship_kind IN ('CONTAINS_OBJECT', 'PROMOTES_LISTING_VARIANT')),
    CONSTRAINT ad_object_relationship_status_ck
        CHECK (status IN ('ACTIVE', 'ENDED')),
    -- Exactly one target: a structural edge or a promoted listing, never both.
    CONSTRAINT ad_object_relationship_target_ck
        CHECK (num_nonnulls(child_object_id, platform_listing_variant_id) = 1),
    CONSTRAINT ad_object_relationship_shape_ck
        CHECK ((relationship_kind = 'CONTAINS_OBJECT') = (child_object_id IS NOT NULL))
);

CREATE UNIQUE INDEX ad_object_relationship_live_uq
    ON core.ad_object_relationship
       (parent_object_id, relationship_kind,
        coalesce(child_object_id, platform_listing_variant_id))
    WHERE status = 'ACTIVE';
CREATE INDEX ad_object_relationship_variant_ix
    ON core.ad_object_relationship (platform_listing_variant_id)
    WHERE platform_listing_variant_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The complete affected set
-- ---------------------------------------------------------------------------

-- Append-only. A different membership is a different row with a different
-- digest, never an edit, because the digest is what an Impact Preview, an
-- Approval, a Command and an Outcome Evaluation Plan freeze. If the set could be
-- edited in place, an approval could silently come to mean something else
-- between the moment it was given and the moment it was executed.
--
-- `resolution_state` distinguishes "we resolved every variant this object
-- promotes" from "we could not". An INCOMPLETE or CONFLICTED set is a first
-- class business state: the case stays visible and diagnosable, and every
-- write-grade purpose that consumes the affected set fails closed.
CREATE TABLE core.ad_affected_set (
    id                           uuid        NOT NULL,
    organization_id              uuid        NOT NULL,
    ad_native_object_id          uuid        NOT NULL,
    affected_set_digest          text        NOT NULL,
    product_variant_ids          uuid[]      NOT NULL,
    platform_listing_variant_ids uuid[]      NOT NULL,
    resolution_state             text        NOT NULL,
    unresolved_reason_codes      text[]      NOT NULL DEFAULT '{}',
    resolved_at                  timestamptz NOT NULL,
    created_at                   timestamptz NOT NULL,
    CONSTRAINT ad_affected_set_pk PRIMARY KEY (id),
    CONSTRAINT ad_affected_set_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_affected_set_digest_uq
        UNIQUE (ad_native_object_id, affected_set_digest),
    CONSTRAINT ad_affected_set_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_affected_set_digest_ck
        CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ad_affected_set_resolution_ck
        CHECK (resolution_state IN ('COMPLETE', 'INCOMPLETE', 'CONFLICTED', 'UNRESOLVED')),
    CONSTRAINT ad_affected_set_variants_ck
        CHECK (cardinality(product_variant_ids) BETWEEN 0 AND 4096
            AND array_position(product_variant_ids, NULL) IS NULL),
    CONSTRAINT ad_affected_set_listings_ck
        CHECK (cardinality(platform_listing_variant_ids) BETWEEN 0 AND 4096
            AND array_position(platform_listing_variant_ids, NULL) IS NULL),
    CONSTRAINT ad_affected_set_reasons_ck
        CHECK (cardinality(unresolved_reason_codes) BETWEEN 0 AND 32
            AND array_position(unresolved_reason_codes, NULL) IS NULL),
    -- A complete set has at least one member and nothing left unexplained; an
    -- incomplete one must say why, so "we do not know" and "we forgot" are
    -- different states.
    CONSTRAINT ad_affected_set_complete_ck
        CHECK (resolution_state <> 'COMPLETE'
            OR (cardinality(product_variant_ids) >= 1
                AND cardinality(unresolved_reason_codes) = 0)),
    CONSTRAINT ad_affected_set_incomplete_ck
        CHECK (resolution_state = 'COMPLETE'
            OR cardinality(unresolved_reason_codes) >= 1)
);

CREATE INDEX ad_affected_set_object_ix
    ON core.ad_affected_set (ad_native_object_id, resolved_at DESC);

-- ---------------------------------------------------------------------------
-- Observed configuration
-- ---------------------------------------------------------------------------

-- What the platform said this object's configuration was, when we looked. This
-- is the row a Preview reads for "current Bid", the row a Readback compares
-- against, and the row that proves a later external change happened. It is
-- append-only with a supersession link for exactly the reason the fact tables
-- are: an observation that could be rewritten proves nothing.
--
-- `observed_bid_amount` is nullable and its absence is not zero. An object whose
-- bid the platform does not report is not an object bidding nothing; it is an
-- object whose bid we cannot see, and every write-grade purpose that consumes it
-- fails closed.
CREATE TABLE core.ad_object_configuration_observation (
    id                    uuid           NOT NULL,
    organization_id       uuid           NOT NULL,
    ad_native_object_id   uuid           NOT NULL,
    provenance_id         uuid           NOT NULL,
    semantic_profile_id   uuid           NOT NULL,
    lineage_generation    integer        NOT NULL,
    observed_bid_amount   numeric(18, 4),
    bid_currency_code     text,
    bid_unit_code         text           NOT NULL,
    observed_status       text           NOT NULL,
    native_status_raw     text,
    observed_bidding_mode text           NOT NULL,
    evidence_grade        text           NOT NULL,
    observed_at           timestamptz    NOT NULL,
    source_time           timestamptz    NOT NULL,
    supersedes_observation_id uuid,
    created_at            timestamptz    NOT NULL,
    CONSTRAINT ad_object_configuration_observation_pk PRIMARY KEY (id),
    CONSTRAINT ad_object_configuration_observation_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_object_configuration_observation_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT ad_object_configuration_observation_profile_fk
        FOREIGN KEY (semantic_profile_id)
        REFERENCES platform.ad_semantic_profile (id),
    CONSTRAINT ad_object_configuration_observation_supersedes_fk
        FOREIGN KEY (supersedes_observation_id)
        REFERENCES core.ad_object_configuration_observation (id),
    CONSTRAINT ad_object_configuration_observation_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_object_configuration_observation_status_ck
        CHECK (observed_status IN
            ('RUNNING', 'PAUSED', 'STOPPED', 'ARCHIVED', 'UNKNOWN')),
    CONSTRAINT ad_object_configuration_observation_mode_ck
        CHECK (observed_bidding_mode IN ('MANUAL_BID', 'AUTO_BID', 'MIXED', 'UNKNOWN')),
    CONSTRAINT ad_object_configuration_observation_unit_ck
        CHECK (bid_unit_code IN ('CURRENCY_MAJOR', 'CURRENCY_MINOR', 'UNKNOWN')),
    -- The Contract's configuration evidence hierarchy, as data. Nothing in the
    -- product promotes a lower grade to a higher one.
    CONSTRAINT ad_object_configuration_observation_grade_ck
        CHECK (evidence_grade IN
            ('OFFICIAL_API_READBACK', 'OFFICIAL_CONFIGURATION_EXPORT',
             'INDEPENDENT_MANUAL_VERIFICATION', 'EXECUTOR_SELF_REPORT')),
    CONSTRAINT ad_object_configuration_observation_currency_ck
        CHECK (bid_currency_code IS NULL OR bid_currency_code ~ '^[A-Z]{3}$'),
    -- A bid amount and its currency travel together or not at all.
    CONSTRAINT ad_object_configuration_observation_money_ck
        CHECK ((observed_bid_amount IS NULL) = (bid_currency_code IS NULL)),
    CONSTRAINT ad_object_configuration_observation_amount_ck
        CHECK (observed_bid_amount IS NULL OR observed_bid_amount >= 0),
    CONSTRAINT ad_object_configuration_observation_generation_ck
        CHECK (lineage_generation >= 1),
    CONSTRAINT ad_object_configuration_observation_raw_ck
        CHECK (native_status_raw IS NULL
            OR length(btrim(native_status_raw)) BETWEEN 1 AND 256)
);

CREATE INDEX ad_object_configuration_observation_current_ix
    ON core.ad_object_configuration_observation
       (ad_native_object_id, observed_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- Official object-level advertising facts
-- ---------------------------------------------------------------------------

-- Spend, traffic and provider-attributed outcomes at the object the platform
-- lets us control. `ledger.ad_spend_fact` already carries the per-listing view,
-- which for most marketplaces is an allocation rather than an observation; this
-- table is the object-level official authority, and the two are never summed
-- into one number.
--
-- Every measure is nullable, and every one of them means NOT_AVAILABLE when it
-- is NULL. That distinction is the whole point: an object with zero clicks is
-- an object nobody clicked, and an object with unreported clicks is an object we
-- cannot compute a conversion rate for. Collapsing them would let a reporting
-- outage look like a performance collapse.
--
-- `attribution_window_code` and `attribution_model_native` travel with the
-- provider-attributed columns so a comparison between two windows or two models
-- can be refused rather than silently performed.
CREATE TABLE ledger.ad_object_fact (
    id                        uuid           NOT NULL,
    organization_id           uuid           NOT NULL,
    provenance_id             uuid           NOT NULL,
    ad_native_object_id       uuid           NOT NULL,
    store_id                  uuid           NOT NULL,
    source_fact_key           text           NOT NULL,
    period_start              timestamptz    NOT NULL,
    period_end                timestamptz    NOT NULL,
    currency_code             text           NOT NULL,
    spend_amount              numeric(18, 4),
    impressions               bigint,
    views                     bigint,
    clicks                    bigint,
    provider_attributed_orders   bigint,
    provider_attributed_units    bigint,
    provider_attributed_revenue  numeric(18, 4),
    attribution_window_code   text,
    attribution_model_native  text,
    report_window_complete    boolean        NOT NULL,
    correction_window_open    boolean        NOT NULL,
    adjustment_kind           text,
    supersedes_fact_id        uuid,
    source_time               timestamptz    NOT NULL,
    recorded_at               timestamptz    NOT NULL,
    CONSTRAINT ad_object_fact_pk PRIMARY KEY (id),
    CONSTRAINT ad_object_fact_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT ad_object_fact_object_fk
        FOREIGN KEY (ad_native_object_id, organization_id)
        REFERENCES core.ad_native_object (id, organization_id),
    CONSTRAINT ad_object_fact_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_object_fact_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.ad_object_fact (id),
    CONSTRAINT ad_object_fact_source_key_uq UNIQUE (organization_id, source_fact_key),
    CONSTRAINT ad_object_fact_period_ck CHECK (period_start < period_end),
    CONSTRAINT ad_object_fact_currency_ck CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_object_fact_spend_ck CHECK (spend_amount IS NULL OR spend_amount >= 0),
    CONSTRAINT ad_object_fact_impressions_ck
        CHECK (impressions IS NULL OR impressions >= 0),
    CONSTRAINT ad_object_fact_views_ck CHECK (views IS NULL OR views >= 0),
    CONSTRAINT ad_object_fact_clicks_ck CHECK (clicks IS NULL OR clicks >= 0),
    CONSTRAINT ad_object_fact_orders_ck
        CHECK (provider_attributed_orders IS NULL OR provider_attributed_orders >= 0),
    CONSTRAINT ad_object_fact_units_ck
        CHECK (provider_attributed_units IS NULL OR provider_attributed_units >= 0),
    CONSTRAINT ad_object_fact_revenue_ck
        CHECK (provider_attributed_revenue IS NULL OR provider_attributed_revenue >= 0),
    -- A provider-attributed number without its window and model cannot be
    -- compared with anything, so it may not be recorded alone.
    CONSTRAINT ad_object_fact_attribution_ck
        CHECK ((provider_attributed_orders IS NULL
                AND provider_attributed_units IS NULL
                AND provider_attributed_revenue IS NULL)
            OR (attribution_window_code IS NOT NULL
                AND attribution_model_native IS NOT NULL)),
    CONSTRAINT ad_object_fact_window_code_ck
        CHECK (attribution_window_code IS NULL
            OR length(btrim(attribution_window_code)) BETWEEN 1 AND 64),
    CONSTRAINT ad_object_fact_model_ck
        CHECK (attribution_model_native IS NULL
            OR length(btrim(attribution_model_native)) BETWEEN 1 AND 128),
    CONSTRAINT ad_object_fact_adjustment_ck
        CHECK (adjustment_kind IS NULL
            OR adjustment_kind IN ('LATE_ARRIVAL', 'CORRECTION', 'REVERSAL')),
    -- A correction supersedes something. A first observation does not.
    CONSTRAINT ad_object_fact_correction_ck
        CHECK (adjustment_kind IS NULL OR supersedes_fact_id IS NOT NULL),
    CONSTRAINT ad_object_fact_source_key_shape_ck
        CHECK (length(btrim(source_fact_key)) BETWEEN 1 AND 256)
);

CREATE INDEX ad_object_fact_object_period_ix
    ON ledger.ad_object_fact (ad_native_object_id, period_start DESC, period_end DESC);
CREATE INDEX ad_object_fact_store_ix
    ON ledger.ad_object_fact (organization_id, store_id, period_start DESC);
CREATE INDEX ad_object_fact_recorded_ix
    ON ledger.ad_object_fact (organization_id, recorded_at DESC);
-- Finding the live row for a period means finding the one nothing supersedes.
CREATE INDEX ad_object_fact_supersedes_ix
    ON ledger.ad_object_fact (supersedes_fact_id)
    WHERE supersedes_fact_id IS NOT NULL;

-- A per-listing view of object spend, and an honest statement of how it was
-- derived. The Contract permits an estimated SKU allocation to be shown for
-- diagnosis only, and only when its method, coverage and confidence are
-- explicit. This table is where "explicit" lives: an allocation row must carry
-- its method and its coverage ratio, and `basis` is what every consumer reads
-- before deciding whether the number may support anything.
CREATE TABLE ledger.ad_object_listing_allocation (
    id                          uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    ad_object_fact_id           uuid           NOT NULL,
    platform_listing_variant_id uuid           NOT NULL,
    basis                       text           NOT NULL,
    allocation_method_code      text,
    allocation_coverage_ratio   numeric(6, 5),
    confidence_state            text           NOT NULL,
    currency_code               text           NOT NULL,
    allocated_spend_amount      numeric(18, 4),
    allocated_clicks            bigint,
    created_at                  timestamptz    NOT NULL,
    CONSTRAINT ad_object_listing_allocation_pk PRIMARY KEY (id),
    CONSTRAINT ad_object_listing_allocation_fact_fk
        FOREIGN KEY (ad_object_fact_id) REFERENCES ledger.ad_object_fact (id),
    CONSTRAINT ad_object_listing_allocation_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT ad_object_listing_allocation_uq
        UNIQUE (ad_object_fact_id, platform_listing_variant_id),
    CONSTRAINT ad_object_listing_allocation_basis_ck
        CHECK (basis IN ('OFFICIAL_OBSERVATION', 'ESTIMATED_ALLOCATION')),
    CONSTRAINT ad_object_listing_allocation_confidence_ck
        CHECK (confidence_state IN
            ('CANONICAL_CONFIRMED', 'ESTIMATED_EXPLAINED', 'INCOMPLETE',
             'CONFLICTED', 'UNKNOWN')),
    -- An estimate that cannot say how it was made, how much it covers, or that
    -- it is an estimate, is not admissible even for diagnosis.
    CONSTRAINT ad_object_listing_allocation_estimate_ck
        CHECK (basis <> 'ESTIMATED_ALLOCATION'
            OR (allocation_method_code IS NOT NULL
                AND allocation_coverage_ratio IS NOT NULL
                AND confidence_state = 'ESTIMATED_EXPLAINED')),
    -- Conversely an official observation is not an estimate and may not borrow
    -- the estimate's confidence label.
    CONSTRAINT ad_object_listing_allocation_official_ck
        CHECK (basis <> 'OFFICIAL_OBSERVATION'
            OR (allocation_method_code IS NULL
                AND confidence_state <> 'ESTIMATED_EXPLAINED')),
    CONSTRAINT ad_object_listing_allocation_currency_ck
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_object_listing_allocation_coverage_ck
        CHECK (allocation_coverage_ratio IS NULL
            OR (allocation_coverage_ratio > 0 AND allocation_coverage_ratio <= 1)),
    CONSTRAINT ad_object_listing_allocation_spend_ck
        CHECK (allocated_spend_amount IS NULL OR allocated_spend_amount >= 0),
    CONSTRAINT ad_object_listing_allocation_clicks_ck
        CHECK (allocated_clicks IS NULL OR allocated_clicks >= 0),
    CONSTRAINT ad_object_listing_allocation_method_ck
        CHECK (allocation_method_code IS NULL
            OR allocation_method_code ~ '^[A-Z][A-Z0-9_]{1,62}$')
);

CREATE INDEX ad_object_listing_allocation_variant_ix
    ON ledger.ad_object_listing_allocation
       (platform_listing_variant_id, ad_object_fact_id);

-- ---------------------------------------------------------------------------
-- Control routing and privileges
-- ---------------------------------------------------------------------------

-- Every table declares its routing. `ad_semantic_profile` is a platform-scoped
-- registry fact like `platform_api_profile`, so it fans out to the platform's
-- ingestion jobs; the rest are business facts and projections that no
-- acquisition authority reads.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('platform', 'ad_semantic_profile', 'PLATFORM_FANOUT', 'JOB',
        'versioned platform-native advertising semantics; acquisition shape depends on it'),
    ('core', 'ad_native_object', 'NO_ROUTE', NULL,
        'observed advertising object identity and lineage; no acquisition authority reads it'),
    ('core', 'ad_object_relationship', 'NO_ROUTE', NULL,
        'observed structural and promoted-listing edges; grants no control granularity'),
    ('core', 'ad_affected_set', 'NO_ROUTE', NULL,
        'append-only complete affected-variant set and digest'),
    ('core', 'ad_object_configuration_observation', 'NO_ROUTE', NULL,
        'append-only observed advertising configuration; no acquisition authority reads it'),
    ('ledger', 'ad_object_fact', 'NO_ROUTE', NULL,
        'append-only official object spend, traffic and provider attribution'),
    ('ledger', 'ad_object_listing_allocation', 'NO_ROUTE', NULL,
        'per-listing observation or explicitly labelled estimated allocation');

DO $generate$
DECLARE
    routed          record;
    routed_tables   integer;
    routed_triggers integer;
BEGIN
    -- One PLATFORM_FANOUT table is added here. Its three statement triggers are
    -- generated with the same body shape V0008 and V0021 use, so an edit to a
    -- semantic profile advances the epochs of every ingestion job on that
    -- platform and an in-flight acquisition run loses its authority.
    FOR routed IN
        SELECT 'platform'::text AS schema_name, 'ad_semantic_profile'::text AS table_name
    LOOP
        EXECUTE format($body$
            CREATE FUNCTION platform.%1$s_advance_control_epoch_insert()
            RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
            SET search_path = pg_catalog, platform, core, pg_temp
            AS $fn$
            DECLARE guarded text[]; scopes platform.control_scope[];
            BEGIN
                WITH rel AS (SELECT * FROM n)
                SELECT array_agg(DISTINCT rel.platform_code) INTO guarded FROM rel;
                IF guarded IS NOT NULL THEN
                    PERFORM platform.acquire_platform_job_set_guard(guarded);
                END IF;
                WITH rel AS (SELECT * FROM n)
                SELECT array_agg(DISTINCT mapped.scope) INTO scopes
                  FROM (SELECT ROW('JOB', job.id)::platform.control_scope AS scope
                          FROM rel JOIN platform.ingestion_job AS job
                            ON job.platform_code = rel.platform_code) AS mapped(scope);
                IF scopes IS NOT NULL THEN PERFORM platform.advance_control_epochs(scopes); END IF;
                RETURN NULL;
            END;
            $fn$;
        $body$, routed.table_name);

        EXECUTE format($body$
            CREATE FUNCTION platform.%1$s_advance_control_epoch_update()
            RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
            SET search_path = pg_catalog, platform, core, pg_temp
            AS $fn$
            DECLARE guarded text[]; scopes platform.control_scope[];
            BEGIN
                WITH rel AS (SELECT * FROM n UNION ALL SELECT * FROM o)
                SELECT array_agg(DISTINCT rel.platform_code) INTO guarded FROM rel;
                IF guarded IS NOT NULL THEN
                    PERFORM platform.acquire_platform_job_set_guard(guarded);
                END IF;
                WITH rel AS (SELECT * FROM n UNION ALL SELECT * FROM o)
                SELECT array_agg(DISTINCT mapped.scope) INTO scopes
                  FROM (SELECT ROW('JOB', job.id)::platform.control_scope AS scope
                          FROM rel JOIN platform.ingestion_job AS job
                            ON job.platform_code = rel.platform_code) AS mapped(scope);
                IF scopes IS NOT NULL THEN PERFORM platform.advance_control_epochs(scopes); END IF;
                RETURN NULL;
            END;
            $fn$;
        $body$, routed.table_name);

        EXECUTE format($body$
            CREATE FUNCTION platform.%1$s_advance_control_epoch_delete()
            RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
            SET search_path = pg_catalog, platform, core, pg_temp
            AS $fn$
            DECLARE guarded text[]; scopes platform.control_scope[];
            BEGIN
                WITH rel AS (SELECT * FROM o)
                SELECT array_agg(DISTINCT rel.platform_code) INTO guarded FROM rel;
                IF guarded IS NOT NULL THEN
                    PERFORM platform.acquire_platform_job_set_guard(guarded);
                END IF;
                WITH rel AS (SELECT * FROM o)
                SELECT array_agg(DISTINCT mapped.scope) INTO scopes
                  FROM (SELECT ROW('JOB', job.id)::platform.control_scope AS scope
                          FROM rel JOIN platform.ingestion_job AS job
                            ON job.platform_code = rel.platform_code) AS mapped(scope);
                IF scopes IS NOT NULL THEN PERFORM platform.advance_control_epochs(scopes); END IF;
                RETURN NULL;
            END;
            $fn$;
        $body$, routed.table_name);

        EXECUTE format(
            'CREATE TRIGGER %1$s_control_epoch_ai AFTER INSERT ON platform.%1$s '
            'REFERENCING NEW TABLE AS n FOR EACH STATEMENT '
            'EXECUTE FUNCTION platform.%1$s_advance_control_epoch_insert()',
            routed.table_name);
        EXECUTE format(
            'CREATE TRIGGER %1$s_control_epoch_au AFTER UPDATE ON platform.%1$s '
            'REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT '
            'EXECUTE FUNCTION platform.%1$s_advance_control_epoch_update()',
            routed.table_name);
        EXECUTE format(
            'CREATE TRIGGER %1$s_control_epoch_ad AFTER DELETE ON platform.%1$s '
            'REFERENCING OLD TABLE AS o FOR EACH STATEMENT '
            'EXECUTE FUNCTION platform.%1$s_advance_control_epoch_delete()',
            routed.table_name);
    END LOOP;

    SELECT count(*) INTO routed_tables
      FROM platform.control_route_inventory
     WHERE route_kind <> 'NO_ROUTE';
    SELECT count(*) INTO routed_triggers
      FROM pg_trigger
     WHERE NOT tgisinternal AND tgname LIKE '%\_control\_epoch\_a%';
    IF routed_triggers <> routed_tables * 3 THEN
        RAISE EXCEPTION
            'control route inventory and triggers disagree: % routed tables, % triggers',
            routed_tables, routed_triggers USING ERRCODE = 'MO004';
    END IF;
END;
$generate$;

-- The application role reads every advertising fact and appends the ones the
-- ingestion path produces. It never deletes, and it never updates a fact or an
-- observation, because a corrected number is a new row.
GRANT SELECT ON platform.ad_semantic_profile TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.ad_native_object TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.ad_object_relationship TO marketops_app;
GRANT SELECT, INSERT ON core.ad_affected_set TO marketops_app;
GRANT SELECT, INSERT ON core.ad_object_configuration_observation TO marketops_app;
GRANT SELECT, INSERT ON ledger.ad_object_fact TO marketops_app;
GRANT SELECT, INSERT ON ledger.ad_object_listing_allocation TO marketops_app;
