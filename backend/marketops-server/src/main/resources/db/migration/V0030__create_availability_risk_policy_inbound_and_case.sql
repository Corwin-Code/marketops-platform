-- Stockout and availability risk: the policies a risk is calculated from, the
-- inbound supply somebody attested to, the projection the queue reads, and the
-- accountable case the projection raises.
--
-- Three ideas run through the whole file.
--
-- First, safety is asymmetric. A channel observation is a statement about one
-- exact listing and mode and is actionable on its own. A company answer is a
-- statement about everything the organization owns, so it fails closed: it
-- cannot be safe while a material input is missing, stale, conflicted or not
-- proven distinct. The schema carries that asymmetry as separate child rows
-- with separate lanes and evidence states, never as one blended number.
--
-- Second, a policy that cannot be resolved is not a zero. Lead time, safety
-- days, demand-window selection, work activation and exception materiality are
-- all versioned and effective-dated, and the absence of a valid version is a
-- recorded blocked state rather than an implementation default.
--
-- Third, a case is identified by its cause. Recalculating the same cause a
-- thousand times updates one case; it does not raise a thousand tasks. That is
-- a partial unique index here rather than a check in a service, because replay
-- and concurrency are exactly the conditions a service-level check misses.
--
-- No table in this file is part of a platform write path. There is no stock
-- command, outbox, readback or target. production_write_enabled remains false.

-- ---------------------------------------------------------------------------
-- Business roles and action scopes
-- ---------------------------------------------------------------------------

-- The Contract names the roles that own each kind of availability failure.
-- They are seeded rather than administered, for the same reason the original
-- four are: widening a role has to be a migration a reviewer can see.
INSERT INTO iam.business_role (code, display_name, description, ordinal) VALUES
    ('MARKETPLACE_OPERATOR', 'Marketplace operator',
        'Restores channel availability for an exact listing, store and fulfillment mode.', 5),
    ('PRODUCT_PROCUREMENT', 'Product and procurement',
        'Owns inbound attestation and the lead-time and safety policy.', 6),
    ('TECH_DATA', 'Technical data',
        'Repairs stock, mapping, ownership and source defects.', 7),
    ('FINANCE_ANALYST', 'Finance analyst',
        'Resolves profit and cost-data blockers behind an availability decision.', 8),
    ('OPS_LEAD', 'Operations lead',
        'Approves bounded accepted risk and owns the operating response.', 9),
    ('RISK_AUTHORITY', 'Risk authority',
        'Owner-designated approver for critical, repeated or material accepted risk.', 10),
    ('AUDITOR', 'Auditor',
        'Reads availability risk, cases and decisions without changing any state.', 11);

INSERT INTO iam.action_scope (code, display_name, description, requires_step_up, ordinal) VALUES
    ('AVAILABILITY_VIEW', 'View availability risk',
        'Read the stockout and availability queue, its children and its evidence.', false, 11),
    ('INBOUND_ATTEST', 'Attest inbound supply',
        'Record, amend or cancel an evidence-backed inbound supply attestation.', false, 12),
    ('SUPPLY_POLICY_MANAGE', 'Manage supply policy',
        'Publish or retire lead-time, safety, demand, ownership and activation policy.', true, 13),
    ('AVAILABILITY_TASK_ACT', 'Act on an availability case',
        'Record structured action evidence against an accountable availability case.', false, 14),
    ('AVAILABILITY_EXCEPTION_REQUEST', 'Request accepted risk',
        'Request a scoped, expiring accepted exception against a calculated risk.', false, 15),
    ('AVAILABILITY_EXCEPTION_APPROVE', 'Approve accepted risk',
        'Decide a scoped, expiring accepted exception at the authorised lane.', true, 16);

-- The reviewed matrix. Read stays broad; attestation, policy and approval stay
-- with the roles the Contract makes accountable for them. AUDITOR receives no
-- mutating action at all.
INSERT INTO iam.business_role_action_scope (role_code, action_code)
SELECT role_code, action_code
  FROM (VALUES
    ('OWNER', 'AVAILABILITY_VIEW'),
    ('OWNER', 'INBOUND_ATTEST'),
    ('OWNER', 'SUPPLY_POLICY_MANAGE'),
    ('OWNER', 'AVAILABILITY_TASK_ACT'),
    ('OWNER', 'AVAILABILITY_EXCEPTION_REQUEST'),
    ('OWNER', 'AVAILABILITY_EXCEPTION_APPROVE'),
    ('OPERATIONS', 'AVAILABILITY_VIEW'),
    ('OPERATIONS', 'AVAILABILITY_TASK_ACT'),
    ('OPERATIONS', 'AVAILABILITY_EXCEPTION_REQUEST'),
    ('FINANCE', 'AVAILABILITY_VIEW'),
    ('MARKETPLACE_OPERATOR', 'DIAGNOSTIC_VIEW'),
    ('MARKETPLACE_OPERATOR', 'EVIDENCE_VIEW'),
    ('MARKETPLACE_OPERATOR', 'AVAILABILITY_VIEW'),
    ('MARKETPLACE_OPERATOR', 'AVAILABILITY_TASK_ACT'),
    ('MARKETPLACE_OPERATOR', 'AVAILABILITY_EXCEPTION_REQUEST'),
    ('PRODUCT_PROCUREMENT', 'DIAGNOSTIC_VIEW'),
    ('PRODUCT_PROCUREMENT', 'EVIDENCE_VIEW'),
    ('PRODUCT_PROCUREMENT', 'AVAILABILITY_VIEW'),
    ('PRODUCT_PROCUREMENT', 'AVAILABILITY_TASK_ACT'),
    ('PRODUCT_PROCUREMENT', 'AVAILABILITY_EXCEPTION_REQUEST'),
    ('PRODUCT_PROCUREMENT', 'INBOUND_ATTEST'),
    ('PRODUCT_PROCUREMENT', 'SUPPLY_POLICY_MANAGE'),
    ('TECH_DATA', 'DIAGNOSTIC_VIEW'),
    ('TECH_DATA', 'EVIDENCE_VIEW'),
    ('TECH_DATA', 'AVAILABILITY_VIEW'),
    ('TECH_DATA', 'AVAILABILITY_TASK_ACT'),
    ('TECH_DATA', 'MAPPING_RESOLVE'),
    ('FINANCE_ANALYST', 'DIAGNOSTIC_VIEW'),
    ('FINANCE_ANALYST', 'EVIDENCE_VIEW'),
    ('FINANCE_ANALYST', 'AVAILABILITY_VIEW'),
    ('FINANCE_ANALYST', 'AVAILABILITY_TASK_ACT'),
    ('FINANCE_ANALYST', 'INTERNAL_FACT_INTAKE'),
    ('OPS_LEAD', 'DIAGNOSTIC_VIEW'),
    ('OPS_LEAD', 'EVIDENCE_VIEW'),
    ('OPS_LEAD', 'AVAILABILITY_VIEW'),
    ('OPS_LEAD', 'AVAILABILITY_TASK_ACT'),
    ('OPS_LEAD', 'AVAILABILITY_EXCEPTION_REQUEST'),
    ('OPS_LEAD', 'AVAILABILITY_EXCEPTION_APPROVE'),
    ('OPS_LEAD', 'TASK_ASSIGN'),
    ('RISK_AUTHORITY', 'DIAGNOSTIC_VIEW'),
    ('RISK_AUTHORITY', 'EVIDENCE_VIEW'),
    ('RISK_AUTHORITY', 'AVAILABILITY_VIEW'),
    ('RISK_AUTHORITY', 'AVAILABILITY_EXCEPTION_APPROVE'),
    ('AUDITOR', 'DIAGNOSTIC_VIEW'),
    ('AUDITOR', 'EVIDENCE_VIEW'),
    ('AUDITOR', 'AVAILABILITY_VIEW')
  ) AS matrix(role_code, action_code);

-- The journal stays one authority. The new module records through it rather
-- than growing a second trail.
ALTER TABLE ops.metadata_audit_event
    DROP CONSTRAINT metadata_audit_event_source_domain_ck,
    ADD CONSTRAINT metadata_audit_event_source_domain_ck
        CHECK (source_domain IN (
            'organizationaccount', 'identityaccess',
            'marketplaceintegration', 'adminobservability',
            'productlisting', 'operatingfacts',
            'analyticsdecision', 'aicopilot', 'operationsworkflow',
            'availabilityrisk'));

-- ---------------------------------------------------------------------------
-- Supply ownership and physical distinctness
-- ---------------------------------------------------------------------------

-- Whether a platform-visible quantity is the same physical units as an internal
-- warehouse holds, or genuinely separate stock the company owns at the
-- platform.
--
-- This is the row that makes "do not double-count" enforceable. A seller-
-- fulfilled view that mirrors warehouse stock is the same goods seen twice and
-- must never be added; marketplace-held stock the company owns is additional
-- supply and may be. Absent a declaration the answer is UNKNOWN, and UNKNOWN
-- cannot produce a company-safe result — which is why there is no default row
-- and nothing is seeded.
CREATE TABLE core.supply_ownership_declaration (
    id                    uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    fulfillment_mode_code text        NOT NULL,
    distinctness          text        NOT NULL,
    mirrored_warehouse_id uuid,
    evidence_reference    text        NOT NULL,
    declared_by_user_id   uuid        NOT NULL,
    reason                text        NOT NULL,
    effective_from        timestamptz NOT NULL,
    effective_to          timestamptz,
    status                text        NOT NULL,
    policy_version        integer     NOT NULL,
    created_at            timestamptz NOT NULL,
    CONSTRAINT supply_ownership_declaration_pk PRIMARY KEY (id),
    CONSTRAINT supply_ownership_declaration_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT supply_ownership_declaration_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT supply_ownership_declaration_mode_fk
        FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode (code),
    CONSTRAINT supply_ownership_declaration_warehouse_fk
        FOREIGN KEY (mirrored_warehouse_id, organization_id)
        REFERENCES core.warehouse (id, organization_id),
    CONSTRAINT supply_ownership_declaration_user_fk
        FOREIGN KEY (declared_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT supply_ownership_declaration_distinctness_ck
        CHECK (distinctness IN ('MIRRORS_INTERNAL', 'PHYSICALLY_DISTINCT')),
    -- A mirror has to say what it mirrors. Distinct stock must not name a
    -- warehouse, or the two claims could be read as one.
    CONSTRAINT supply_ownership_declaration_mirror_ck
        CHECK ((distinctness = 'MIRRORS_INTERNAL') = (mirrored_warehouse_id IS NOT NULL)),
    CONSTRAINT supply_ownership_declaration_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT supply_ownership_declaration_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT supply_ownership_declaration_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT supply_ownership_declaration_version_ck CHECK (policy_version >= 1),
    CONSTRAINT supply_ownership_declaration_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT supply_ownership_declaration_no_overlap
        EXCLUDE USING gist (
            store_id WITH =,
            fulfillment_mode_code WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

CREATE INDEX supply_ownership_declaration_lookup_ix
    ON core.supply_ownership_declaration (organization_id, store_id, fulfillment_mode_code)
    WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Lead time and safety policy
-- ---------------------------------------------------------------------------

-- Canonical lead time and safety days, versioned, effective-dated and owned.
--
-- Resolution is exact scoped fallback: variant+supplier+route, then supplier or
-- product category, then the organization default. When nothing resolves the
-- answer is POLICY_BLOCKED. There is deliberately no seeded organization
-- default, because a seeded default is how "no policy" silently becomes "zero
-- lead time".
--
-- scope_key exists so the no-overlap constraint actually works. An EXCLUDE over
-- nullable scope columns would let two organization-wide versions coexist,
-- because NULL is never equal to NULL.
CREATE TABLE core.lead_time_safety_policy (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    scope_kind          text        NOT NULL,
    scope_precedence    integer     NOT NULL,
    product_variant_id  uuid,
    supplier_code       text,
    route_code          text,
    category_code       text,
    lead_time_days_min  integer     NOT NULL,
    lead_time_days_max  integer     NOT NULL,
    safety_days         integer     NOT NULL,
    owner_user_id       uuid        NOT NULL,
    reason              text        NOT NULL,
    evidence_reference  text        NOT NULL,
    last_reviewed_at    timestamptz NOT NULL,
    effective_from      timestamptz NOT NULL,
    effective_to        timestamptz,
    status              text        NOT NULL,
    policy_version      integer     NOT NULL,
    fallback_of_id      uuid,
    created_at          timestamptz NOT NULL,
    scope_key           text GENERATED ALWAYS AS (
        scope_kind
        || '|' || coalesce(product_variant_id::text, '-')
        || '|' || coalesce(supplier_code, '-')
        || '|' || coalesce(route_code, '-')
        || '|' || coalesce(category_code, '-')) STORED,
    CONSTRAINT lead_time_safety_policy_pk PRIMARY KEY (id),
    CONSTRAINT lead_time_safety_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lead_time_safety_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT lead_time_safety_policy_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT lead_time_safety_policy_owner_fk
        FOREIGN KEY (owner_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT lead_time_safety_policy_fallback_fk
        FOREIGN KEY (fallback_of_id, organization_id)
        REFERENCES core.lead_time_safety_policy (id, organization_id),
    CONSTRAINT lead_time_safety_policy_scope_ck
        CHECK (scope_kind IN (
            'VARIANT_SUPPLIER_ROUTE', 'SUPPLIER', 'PRODUCT_CATEGORY', 'ORGANIZATION')),
    -- Precedence is stored rather than derived so resolution order is a fact in
    -- the row, not a rule a query has to remember. Lower wins.
    CONSTRAINT lead_time_safety_policy_precedence_ck
        CHECK (scope_precedence = CASE scope_kind
            WHEN 'VARIANT_SUPPLIER_ROUTE' THEN 1
            WHEN 'SUPPLIER' THEN 2
            WHEN 'PRODUCT_CATEGORY' THEN 2
            ELSE 3 END),
    -- Each scope names exactly the identifiers it is scoped by, and no others.
    CONSTRAINT lead_time_safety_policy_scope_shape_ck CHECK (
        (scope_kind = 'VARIANT_SUPPLIER_ROUTE'
            AND product_variant_id IS NOT NULL AND supplier_code IS NOT NULL
            AND route_code IS NOT NULL AND category_code IS NULL)
     OR (scope_kind = 'SUPPLIER'
            AND product_variant_id IS NULL AND supplier_code IS NOT NULL
            AND route_code IS NULL AND category_code IS NULL)
     OR (scope_kind = 'PRODUCT_CATEGORY'
            AND product_variant_id IS NULL AND supplier_code IS NULL
            AND route_code IS NULL AND category_code IS NOT NULL)
     OR (scope_kind = 'ORGANIZATION'
            AND product_variant_id IS NULL AND supplier_code IS NULL
            AND route_code IS NULL AND category_code IS NULL)),
    CONSTRAINT lead_time_safety_policy_lead_ck
        CHECK (lead_time_days_min >= 0
           AND lead_time_days_max >= lead_time_days_min
           AND lead_time_days_max <= 3650),
    CONSTRAINT lead_time_safety_policy_safety_ck
        CHECK (safety_days >= 0 AND safety_days <= 3650),
    CONSTRAINT lead_time_safety_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT lead_time_safety_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT lead_time_safety_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT lead_time_safety_policy_supplier_ck
        CHECK (supplier_code IS NULL OR supplier_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT lead_time_safety_policy_route_ck
        CHECK (route_code IS NULL OR route_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT lead_time_safety_policy_category_ck
        CHECK (category_code IS NULL OR category_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT lead_time_safety_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT lead_time_safety_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    -- Two active versions of the same scope cannot overlap in time. This is the
    -- constraint that turns "overlapping or conflicting policy" from a
    -- calculation-time surprise into an insert-time refusal.
    CONSTRAINT lead_time_safety_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_key WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

CREATE INDEX lead_time_safety_policy_resolution_ix
    ON core.lead_time_safety_policy
       (organization_id, scope_precedence, effective_from DESC)
    WHERE status = 'ACTIVE';
CREATE INDEX lead_time_safety_policy_variant_ix
    ON core.lead_time_safety_policy (product_variant_id)
    WHERE product_variant_id IS NOT NULL AND status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Demand observation policy
-- ---------------------------------------------------------------------------

-- How the deterministic D7/D14/D30 decision is made.
--
-- The thresholds live in a row rather than in code so that a card can name the
-- exact version that produced its selected rate, and so that changing the
-- policy is a versioned, reviewable act that triggers recalculation. There is
-- no seeded default: with no active version the demand answer is
-- POLICY_BLOCKED, never a silently chosen window.
CREATE TABLE core.demand_observation_policy (
    id                          uuid          NOT NULL,
    organization_id             uuid          NOT NULL,
    minimum_sample_units        integer       NOT NULL,
    acceleration_ratio          numeric(6, 3) NOT NULL,
    deceleration_ratio          numeric(6, 3) NOT NULL,
    outlier_share_ratio         numeric(6, 3) NOT NULL,
    minimum_coverage_ratio      numeric(6, 3) NOT NULL,
    carry_forward_max_days      integer       NOT NULL,
    stock_freshness_max_minutes integer       NOT NULL,
    owner_user_id               uuid          NOT NULL,
    reason                      text          NOT NULL,
    evidence_reference          text          NOT NULL,
    effective_from              timestamptz   NOT NULL,
    effective_to                timestamptz,
    status                      text          NOT NULL,
    policy_version              integer       NOT NULL,
    created_at                  timestamptz   NOT NULL,
    CONSTRAINT demand_observation_policy_pk PRIMARY KEY (id),
    CONSTRAINT demand_observation_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT demand_observation_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT demand_observation_policy_owner_fk
        FOREIGN KEY (owner_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT demand_observation_policy_sample_ck
        CHECK (minimum_sample_units BETWEEN 1 AND 100000),
    -- Acceleration must be a rise and deceleration a fall, or the two rules
    -- could both fire on the same evidence.
    CONSTRAINT demand_observation_policy_acceleration_ck
        CHECK (acceleration_ratio > 1 AND acceleration_ratio <= 100),
    CONSTRAINT demand_observation_policy_deceleration_ck
        CHECK (deceleration_ratio > 0 AND deceleration_ratio < 1),
    -- The share of a window's units one day may contribute before the window is
    -- sent to outlier review rather than believed.
    CONSTRAINT demand_observation_policy_outlier_ck
        CHECK (outlier_share_ratio > 0 AND outlier_share_ratio <= 1),
    -- How much of a window must have been observable for it to count as
    -- evidence at all.
    CONSTRAINT demand_observation_policy_coverage_ck
        CHECK (minimum_coverage_ratio > 0 AND minimum_coverage_ratio <= 1),
    CONSTRAINT demand_observation_policy_carry_forward_ck
        CHECK (carry_forward_max_days BETWEEN 0 AND 365),
    CONSTRAINT demand_observation_policy_freshness_ck
        CHECK (stock_freshness_max_minutes BETWEEN 1 AND 43200),
    CONSTRAINT demand_observation_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT demand_observation_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT demand_observation_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT demand_observation_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT demand_observation_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT demand_observation_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- Work activation policy
-- ---------------------------------------------------------------------------

-- When a calculated lane becomes somebody's work.
--
-- CRITICAL always activates. HIGH activates only when it has been sustained for
-- the recorded number of evaluation cycles, so a single noisy evaluation does
-- not raise a task. WATCH never activates automatically. Blocked and review
-- lanes activate a cause-specific remediation task instead of misleading
-- restock work.
CREATE TABLE core.work_activation_policy (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    high_sustained_cycles       integer     NOT NULL,
    critical_action_sla_minutes integer     NOT NULL,
    high_action_sla_minutes     integer     NOT NULL,
    blocker_action_sla_minutes  integer     NOT NULL,
    outcome_sla_minutes         integer     NOT NULL,
    verification_window_minutes integer     NOT NULL,
    owner_user_id               uuid        NOT NULL,
    reason                      text        NOT NULL,
    evidence_reference          text        NOT NULL,
    effective_from              timestamptz NOT NULL,
    effective_to                timestamptz,
    status                      text        NOT NULL,
    policy_version              integer     NOT NULL,
    created_at                  timestamptz NOT NULL,
    CONSTRAINT work_activation_policy_pk PRIMARY KEY (id),
    CONSTRAINT work_activation_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT work_activation_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT work_activation_policy_owner_fk
        FOREIGN KEY (owner_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT work_activation_policy_sustained_ck
        CHECK (high_sustained_cycles BETWEEN 1 AND 100),
    CONSTRAINT work_activation_policy_critical_sla_ck
        CHECK (critical_action_sla_minutes BETWEEN 1 AND 43200),
    CONSTRAINT work_activation_policy_high_sla_ck
        CHECK (high_action_sla_minutes BETWEEN 1 AND 43200),
    CONSTRAINT work_activation_policy_blocker_sla_ck
        CHECK (blocker_action_sla_minutes BETWEEN 1 AND 43200),
    -- The outcome clock is separate from the action clock on purpose: recording
    -- an action is not evidence that the risk improved.
    CONSTRAINT work_activation_policy_outcome_sla_ck
        CHECK (outcome_sla_minutes BETWEEN 1 AND 129600),
    CONSTRAINT work_activation_policy_verification_ck
        CHECK (verification_window_minutes BETWEEN 1 AND 129600),
    CONSTRAINT work_activation_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT work_activation_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT work_activation_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT work_activation_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT work_activation_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT work_activation_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- Exception materiality policy
-- ---------------------------------------------------------------------------

-- What makes an accepted risk material enough to need a higher approver.
--
-- Absent a valid version there is no permissive default: an exception request
-- fails closed as EXCEPTION_AUTHORITY_BLOCKED and the ordinary risk stays
-- active. That is the whole point of the table.
CREATE TABLE core.exception_materiality_policy (
    id                        uuid           NOT NULL,
    organization_id           uuid           NOT NULL,
    currency_code             text           NOT NULL,
    material_profit_at_risk   numeric(18, 4) NOT NULL,
    material_duration_days    integer        NOT NULL,
    repeat_occurrence_count   integer        NOT NULL,
    repeat_lookback_days      integer        NOT NULL,
    max_exception_days        integer        NOT NULL,
    owner_user_id             uuid           NOT NULL,
    reason                    text           NOT NULL,
    evidence_reference        text           NOT NULL,
    effective_from            timestamptz    NOT NULL,
    effective_to              timestamptz,
    status                    text           NOT NULL,
    policy_version            integer        NOT NULL,
    created_at                timestamptz    NOT NULL,
    CONSTRAINT exception_materiality_policy_pk PRIMARY KEY (id),
    CONSTRAINT exception_materiality_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT exception_materiality_policy_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT exception_materiality_policy_owner_fk
        FOREIGN KEY (owner_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT exception_materiality_policy_currency_ck
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT exception_materiality_policy_amount_ck
        CHECK (material_profit_at_risk >= 0),
    CONSTRAINT exception_materiality_policy_duration_ck
        CHECK (material_duration_days BETWEEN 1 AND 3650),
    CONSTRAINT exception_materiality_policy_repeat_ck
        CHECK (repeat_occurrence_count BETWEEN 2 AND 100),
    CONSTRAINT exception_materiality_policy_lookback_ck
        CHECK (repeat_lookback_days BETWEEN 1 AND 3650),
    CONSTRAINT exception_materiality_policy_max_days_ck
        CHECK (max_exception_days BETWEEN 1 AND 3650),
    CONSTRAINT exception_materiality_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT exception_materiality_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT exception_materiality_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT exception_materiality_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT exception_materiality_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT exception_materiality_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&
        ) WHERE (status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- Inbound supply attestation
-- ---------------------------------------------------------------------------

-- Supply somebody accountable has said is on its way.
--
-- Inbound is not stock. It enters a projection only at its eligible arrival
-- window, and only while the attestation is fresh, unconflicted and in a
-- business status the policy accepts. A draft or a buyer's estimate is visible
-- and reduces nothing.
--
-- The header carries identity. Every change is a new version row, so an
-- amendment or a cancellation leaves the previous claim readable rather than
-- overwriting it.
CREATE TABLE core.inbound_supply_attestation (
    id                 uuid        NOT NULL,
    organization_id    uuid        NOT NULL,
    product_variant_id uuid        NOT NULL,
    external_reference text        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT inbound_supply_attestation_pk PRIMARY KEY (id),
    CONSTRAINT inbound_supply_attestation_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT inbound_supply_attestation_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    -- One live claim per external order or shipment reference. Two rows for the
    -- same shipment would be counted twice.
    CONSTRAINT inbound_supply_attestation_reference_uq
        UNIQUE (organization_id, product_variant_id, external_reference),
    CONSTRAINT inbound_supply_attestation_reference_ck
        CHECK (length(btrim(external_reference)) BETWEEN 1 AND 128)
);

CREATE INDEX inbound_supply_attestation_variant_ix
    ON core.inbound_supply_attestation (product_variant_id);

-- One attested state of one inbound claim. Append-only.
--
-- business_status is what decides whether the claim may reduce risk at all.
-- DRAFT and REQUESTED are visible and inert; SUPPLIER_CONFIRMED and IN_TRANSIT
-- may reduce risk while fresh and eligible; CANCELLED, OVERDUE, CONFLICTED and
-- UNKNOWN immediately stop providing safety, which is why they are states here
-- and not a deletion.
CREATE TABLE core.inbound_supply_attestation_version (
    id                    uuid        NOT NULL,
    attestation_id        uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    version_no            integer     NOT NULL,
    quantity              integer     NOT NULL,
    expected_arrival_from timestamptz NOT NULL,
    expected_arrival_to   timestamptz NOT NULL,
    business_status       text        NOT NULL,
    change_kind           text        NOT NULL,
    evidence_reference    text        NOT NULL,
    source_time           timestamptz,
    last_verified_at      timestamptz NOT NULL,
    attested_by_user_id   uuid        NOT NULL,
    reason                text,
    supersedes_version_id uuid,
    recorded_at           timestamptz NOT NULL,
    CONSTRAINT inbound_supply_attestation_version_pk PRIMARY KEY (id),
    CONSTRAINT inbound_supply_attestation_version_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT inbound_supply_attestation_version_header_fk
        FOREIGN KEY (attestation_id, organization_id)
        REFERENCES core.inbound_supply_attestation (id, organization_id),
    CONSTRAINT inbound_supply_attestation_version_user_fk
        FOREIGN KEY (attested_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT inbound_supply_attestation_version_supersedes_fk
        FOREIGN KEY (supersedes_version_id, organization_id)
        REFERENCES core.inbound_supply_attestation_version (id, organization_id),
    CONSTRAINT inbound_supply_attestation_version_no_uq
        UNIQUE (attestation_id, version_no),
    CONSTRAINT inbound_supply_attestation_version_no_ck CHECK (version_no >= 1),
    CONSTRAINT inbound_supply_attestation_version_quantity_ck
        CHECK (quantity > 0 AND quantity <= 100000000),
    CONSTRAINT inbound_supply_attestation_version_window_ck
        CHECK (expected_arrival_to >= expected_arrival_from),
    CONSTRAINT inbound_supply_attestation_version_status_ck
        CHECK (business_status IN (
            'DRAFT', 'REQUESTED', 'SUPPLIER_CONFIRMED', 'IN_TRANSIT',
            'RECEIVED', 'CANCELLED', 'OVERDUE', 'CONFLICTED', 'UNKNOWN')),
    CONSTRAINT inbound_supply_attestation_version_change_ck
        CHECK (change_kind IN ('CREATE', 'AMEND', 'CANCEL', 'STATUS_CHANGE', 'REVERIFY')),
    -- The first version creates; every later one supersedes exactly one.
    CONSTRAINT inbound_supply_attestation_version_chain_ck
        CHECK ((version_no = 1) = (supersedes_version_id IS NULL)),
    CONSTRAINT inbound_supply_attestation_version_create_ck
        CHECK ((change_kind = 'CREATE') = (version_no = 1)),
    CONSTRAINT inbound_supply_attestation_version_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT inbound_supply_attestation_version_reason_ck
        CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 1024),
    -- A cancellation has to say why. An unexplained cancellation is how supply
    -- quietly disappears.
    CONSTRAINT inbound_supply_attestation_version_cancel_reason_ck
        CHECK (change_kind <> 'CANCEL' OR reason IS NOT NULL)
);

CREATE INDEX inbound_supply_attestation_version_latest_ix
    ON core.inbound_supply_attestation_version (attestation_id, version_no DESC);
CREATE INDEX inbound_supply_attestation_version_arrival_ix
    ON core.inbound_supply_attestation_version (organization_id, expected_arrival_from);

-- ---------------------------------------------------------------------------
-- Risk projection
-- ---------------------------------------------------------------------------

-- One card per organization and internal variant.
--
-- The card is a projection: it is rebuildable from facts and policy versions,
-- so it is the one place in this file where a row is replaced rather than
-- appended. Case, action, verification and exception history live elsewhere and
-- are never rewritten by a rebuild.
--
-- triggering_child_* is not decoration. The Contract requires the parent to
-- always disclose which child produced its lane, so the answer is stored rather
-- than recomputed by whoever renders it.
CREATE TABLE mart.availability_risk_card (
    id                     uuid          NOT NULL,
    organization_id        uuid          NOT NULL,
    product_variant_id     uuid          NOT NULL,
    lane                   text          NOT NULL,
    triggering_child_id    uuid,
    rank_score             numeric(12, 4) NOT NULL,
    policy_version_digest  text          NOT NULL,
    as_of                  timestamptz   NOT NULL,
    calculated_at          timestamptz   NOT NULL,
    calculation_kind       text          NOT NULL,
    reconciliation_run_id  uuid,
    created_at             timestamptz   NOT NULL,
    updated_at             timestamptz   NOT NULL,
    version                bigint        NOT NULL DEFAULT 0,
    CONSTRAINT availability_risk_card_pk PRIMARY KEY (id),
    CONSTRAINT availability_risk_card_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT availability_risk_card_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    -- One card per variant. This is the parent identity the Contract fixes.
    CONSTRAINT availability_risk_card_identity_uq
        UNIQUE (organization_id, product_variant_id),
    CONSTRAINT availability_risk_card_lane_ck
        CHECK (lane IN ('HEALTHY', 'WATCH', 'HIGH', 'CRITICAL', 'REVIEW', 'UNRESOLVED')),
    CONSTRAINT availability_risk_card_kind_ck
        CHECK (calculation_kind IN ('TARGETED', 'RECONCILIATION')),
    CONSTRAINT availability_risk_card_digest_ck
        CHECK (policy_version_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT availability_risk_card_rank_ck
        CHECK (rank_score >= 0 AND rank_score <= 1000000),
    -- A healthy card has nothing to point at; anything else must name its
    -- triggering child.
    CONSTRAINT availability_risk_card_trigger_ck
        CHECK ((lane = 'HEALTHY') = (triggering_child_id IS NULL))
);

CREATE INDEX availability_risk_card_queue_ix
    ON mart.availability_risk_card (organization_id, rank_score DESC, product_variant_id);
CREATE INDEX availability_risk_card_lane_ix
    ON mart.availability_risk_card (organization_id, lane);

-- One independently governed child risk.
--
-- A channel child names an exact platform listing variant and fulfillment mode.
-- A company child names only the organization and the internal variant. They
-- are rows in one table because they are ranked together and rendered together,
-- but every rule that could let one clear the other is a constraint here.
CREATE TABLE mart.availability_risk_child (
    id                          uuid           NOT NULL,
    card_id                     uuid           NOT NULL,
    organization_id             uuid           NOT NULL,
    child_kind                  text           NOT NULL,
    store_id                    uuid,
    platform_listing_variant_id uuid,
    fulfillment_mode_code       text,
    lane                        text           NOT NULL,
    evidence_state              text           NOT NULL,
    confidence_state            text           NOT NULL,
    cause_code                  text           NOT NULL,
    available_units             integer,
    daily_demand_rate           numeric(14, 4),
    days_of_cover               numeric(10, 2),
    coverage_horizon_days       integer,
    projected_stockout_at       timestamptz,
    profit_lane                 text           NOT NULL,
    profit_at_risk_amount       numeric(18, 4),
    profit_at_risk_currency     text,
    demand_selection_reason     text           NOT NULL,
    conservative_proof          jsonb          NOT NULL,
    blocker_codes               text[]         NOT NULL DEFAULT '{}',
    calculation_id              uuid           NOT NULL,
    calculated_at               timestamptz    NOT NULL,
    created_at                  timestamptz    NOT NULL,
    updated_at                  timestamptz    NOT NULL,
    version                     bigint         NOT NULL DEFAULT 0,
    CONSTRAINT availability_risk_child_pk PRIMARY KEY (id),
    CONSTRAINT availability_risk_child_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT availability_risk_child_card_fk
        FOREIGN KEY (card_id, organization_id)
        REFERENCES mart.availability_risk_card (id, organization_id),
    CONSTRAINT availability_risk_child_store_fk
        FOREIGN KEY (store_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT availability_risk_child_listing_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT availability_risk_child_mode_fk
        FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode (code),
    CONSTRAINT availability_risk_child_kind_ck
        CHECK (child_kind IN ('CHANNEL', 'COMPANY')),
    -- A channel child is exactly its four identifiers; a company child has
    -- none of them. This is the constraint that stops a parent card from
    -- erasing platform listing and mode identity.
    CONSTRAINT availability_risk_child_identity_ck CHECK (
        (child_kind = 'CHANNEL'
            AND store_id IS NOT NULL
            AND platform_listing_variant_id IS NOT NULL
            AND fulfillment_mode_code IS NOT NULL)
     OR (child_kind = 'COMPANY'
            AND store_id IS NULL
            AND platform_listing_variant_id IS NULL
            AND fulfillment_mode_code IS NULL)),
    CONSTRAINT availability_risk_child_lane_ck
        CHECK (lane IN ('HEALTHY', 'WATCH', 'HIGH', 'CRITICAL', 'REVIEW', 'UNRESOLVED')),
    CONSTRAINT availability_risk_child_evidence_ck
        CHECK (evidence_state IN (
            'CONFIRMED', 'OPERATIONAL', 'PROVISIONAL', 'CARRIED_FORWARD',
            'DATA_BLOCKED', 'POLICY_BLOCKED', 'CONFLICTED', 'STALE', 'UNKNOWN')),
    CONSTRAINT availability_risk_child_confidence_ck
        CHECK (confidence_state IN ('HIGH', 'MEDIUM', 'LOW', 'UNUSABLE')),
    CONSTRAINT availability_risk_child_profit_lane_ck
        CHECK (profit_lane IN (
            'CONFIRMED_ELIGIBLE', 'OPERATIONAL_ELIGIBLE', 'PROVISIONAL',
            'PROFIT_DATA_BLOCKED', 'NOT_PROFITABLE', 'PROFIT_UNKNOWN')),
    -- A company child can never be HEALTHY on evidence that is not actually
    -- confirmed or operational. This is the no-false-safety rule, in the
    -- database rather than in a service that could be refactored around.
    CONSTRAINT availability_risk_child_company_safety_ck
        CHECK (child_kind <> 'COMPANY'
            OR lane <> 'HEALTHY'
            OR evidence_state IN ('CONFIRMED', 'OPERATIONAL')),
    -- A provisional answer must carry the proof that produced it.
    CONSTRAINT availability_risk_child_provisional_proof_ck
        CHECK (evidence_state <> 'PROVISIONAL'
            OR jsonb_array_length(coalesce(conservative_proof -> 'terms', '[]'::jsonb)) > 0),
    CONSTRAINT availability_risk_child_proof_shape_ck
        CHECK (jsonb_typeof(conservative_proof) = 'object'),
    CONSTRAINT availability_risk_child_currency_ck
        CHECK (profit_at_risk_currency IS NULL OR profit_at_risk_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT availability_risk_child_amount_currency_ck
        CHECK ((profit_at_risk_amount IS NULL) = (profit_at_risk_currency IS NULL)),
    CONSTRAINT availability_risk_child_units_ck
        CHECK (available_units IS NULL OR available_units >= 0),
    CONSTRAINT availability_risk_child_demand_ck
        CHECK (daily_demand_rate IS NULL OR daily_demand_rate >= 0),
    CONSTRAINT availability_risk_child_horizon_ck
        CHECK (coverage_horizon_days IS NULL OR coverage_horizon_days >= 0),
    CONSTRAINT availability_risk_child_cause_ck
        CHECK (cause_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT availability_risk_child_reason_ck
        CHECK (length(btrim(demand_selection_reason)) BETWEEN 1 AND 256)
);

-- One channel child per exact listing variant and mode; one company child per
-- card. Recalculating updates the row rather than adding a second one.
CREATE UNIQUE INDEX availability_risk_child_channel_uq
    ON mart.availability_risk_child (platform_listing_variant_id, fulfillment_mode_code)
    WHERE child_kind = 'CHANNEL';
CREATE UNIQUE INDEX availability_risk_child_company_uq
    ON mart.availability_risk_child (card_id)
    WHERE child_kind = 'COMPANY';
CREATE INDEX availability_risk_child_card_ix
    ON mart.availability_risk_child (card_id, child_kind);
CREATE INDEX availability_risk_child_lane_ix
    ON mart.availability_risk_child (organization_id, lane, calculated_at DESC);

-- The visible factors behind a rank. The UI shows these rather than an opaque
-- score, so they are stored per child rather than recomputed for display.
CREATE TABLE mart.availability_risk_factor (
    id              uuid           NOT NULL,
    child_id        uuid           NOT NULL,
    organization_id uuid           NOT NULL,
    calculation_id  uuid           NOT NULL,
    factor_code     text           NOT NULL,
    factor_value    numeric(18, 4) NOT NULL,
    factor_weight   numeric(9, 4)  NOT NULL,
    contribution    numeric(18, 4) NOT NULL,
    display_note    text           NOT NULL,
    CONSTRAINT availability_risk_factor_pk PRIMARY KEY (id),
    CONSTRAINT availability_risk_factor_child_fk
        FOREIGN KEY (child_id, organization_id)
        REFERENCES mart.availability_risk_child (id, organization_id),
    CONSTRAINT availability_risk_factor_generation_uq UNIQUE (calculation_id, factor_code),
    -- The closed set of factors the Contract permits a rank to use. A factor
    -- outside this list cannot influence order.
    CONSTRAINT availability_risk_factor_code_ck
        CHECK (factor_code IN (
            'TIME_TO_STOCKOUT', 'CONTRIBUTION_PROFIT_AT_RISK', 'SALES_VELOCITY',
            'LIFECYCLE_STRATEGY', 'CONFIDENCE_PENALTY')),
    CONSTRAINT availability_risk_factor_note_ck
        CHECK (length(btrim(display_note)) BETWEEN 1 AND 256)
);

-- What a child risk was derived from, so a reviewer can reconstruct it.
CREATE TABLE mart.availability_risk_evidence (
    id                  uuid NOT NULL,
    child_id            uuid NOT NULL,
    organization_id     uuid NOT NULL,
    calculation_id      uuid NOT NULL,
    evidence_role       text NOT NULL,
    provenance_id       uuid,
    metric_value_id     uuid,
    policy_reference_id uuid,
    attestation_version_id uuid,
    observed_at         timestamptz,
    note                text NOT NULL,
    CONSTRAINT availability_risk_evidence_pk PRIMARY KEY (id),
    CONSTRAINT availability_risk_evidence_child_fk
        FOREIGN KEY (child_id, organization_id)
        REFERENCES mart.availability_risk_child (id, organization_id),
    CONSTRAINT availability_risk_evidence_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT availability_risk_evidence_metric_fk
        FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value (id),
    CONSTRAINT availability_risk_evidence_attestation_fk
        FOREIGN KEY (attestation_version_id, organization_id)
        REFERENCES core.inbound_supply_attestation_version (id, organization_id),
    CONSTRAINT availability_risk_evidence_role_ck
        CHECK (evidence_role IN (
            'CHANNEL_STOCK', 'INTERNAL_STOCK', 'PLATFORM_OWNED_STOCK', 'INBOUND',
            'DEMAND', 'RETURN_QUALITY', 'SELLABILITY', 'PROFIT',
            'LEAD_TIME_POLICY', 'DEMAND_POLICY', 'OWNERSHIP_DECLARATION')),
    -- Evidence has to point at something. A row that references nothing is a
    -- claim without a source.
    CONSTRAINT availability_risk_evidence_reference_ck
        CHECK (num_nonnulls(provenance_id, metric_value_id, policy_reference_id,
                            attestation_version_id) >= 1),
    CONSTRAINT availability_risk_evidence_note_ck
        CHECK (length(btrim(note)) BETWEEN 1 AND 512)
);

CREATE INDEX availability_risk_evidence_generation_ix
    ON mart.availability_risk_evidence (calculation_id, evidence_role);

-- The D7/D14/D30 evidence behind one child's selected demand rate, including
-- how much of each window was actually observable.
CREATE TABLE mart.demand_window_observation (
    id                    uuid           NOT NULL,
    child_id              uuid           NOT NULL,
    organization_id       uuid           NOT NULL,
    calculation_id        uuid           NOT NULL,
    window_code           text           NOT NULL,
    period_start          timestamptz    NOT NULL,
    period_end            timestamptz    NOT NULL,
    completed_units       integer,
    daily_rate            numeric(14, 4),
    observed_days         numeric(8, 2)  NOT NULL,
    coverage_ratio        numeric(6, 3)  NOT NULL,
    sample_sufficient     boolean        NOT NULL,
    censored              boolean        NOT NULL,
    censoring_reason      text,
    outlier_share         numeric(6, 3),
    eligibility           text           NOT NULL,
    CONSTRAINT demand_window_observation_pk PRIMARY KEY (id),
    CONSTRAINT demand_window_observation_child_fk
        FOREIGN KEY (child_id, organization_id)
        REFERENCES mart.availability_risk_child (id, organization_id),
    CONSTRAINT demand_window_observation_generation_uq UNIQUE (calculation_id, window_code),
    CONSTRAINT demand_window_observation_window_ck
        CHECK (window_code IN ('D7', 'D14', 'D30')),
    CONSTRAINT demand_window_observation_period_ck CHECK (period_end > period_start),
    CONSTRAINT demand_window_observation_units_ck
        CHECK (completed_units IS NULL OR completed_units >= 0),
    CONSTRAINT demand_window_observation_rate_ck
        CHECK (daily_rate IS NULL OR daily_rate >= 0),
    CONSTRAINT demand_window_observation_coverage_ck
        CHECK (coverage_ratio >= 0 AND coverage_ratio <= 1),
    CONSTRAINT demand_window_observation_observed_ck CHECK (observed_days >= 0),
    -- A censored window has to say what censored it.
    CONSTRAINT demand_window_observation_censoring_ck
        CHECK (censored = (censoring_reason IS NOT NULL)),
    CONSTRAINT demand_window_observation_eligibility_ck
        CHECK (eligibility IN (
            'ELIGIBLE', 'LOW_SAMPLE', 'CENSORED', 'OUTLIER_REVIEW',
            'WINDOW_CONFLICT', 'DATA_BLOCKED')),
    CONSTRAINT demand_window_observation_reason_ck
        CHECK (censoring_reason IS NULL
            OR censoring_reason IN (
                'NOT_SELLABLE', 'NO_STOCK', 'SOURCE_STALE',
                'KNOWN_OUTAGE', 'PARTIAL_COVERAGE'))
);

-- ---------------------------------------------------------------------------
-- Recalculation and reconciliation
-- ---------------------------------------------------------------------------

-- A fact was accepted and something has to be recalculated because of it.
--
-- The row carries fact_accepted_at because that is where the internal SLO clock
-- starts. Measuring from when a worker happened to pick the work up would make
-- a backlog invisible, which is the one thing the clock exists to expose.
--
-- Pending requests for the same variant collapse onto one row: a hundred stock
-- observations in a minute are one recalculation, and the earliest
-- fact_accepted_at is the one the SLO is judged against.
CREATE TABLE ops.availability_recalculation_request (
    id                 uuid        NOT NULL,
    organization_id    uuid        NOT NULL,
    product_variant_id uuid        NOT NULL,
    trigger_class      text        NOT NULL,
    trigger_reference  text,
    fact_accepted_at   timestamptz NOT NULL,
    requested_at       timestamptz NOT NULL,
    state              text        NOT NULL,
    attempt_count      integer     NOT NULL DEFAULT 0,
    leased_until       timestamptz,
    lease_owner        text,
    started_at         timestamptz,
    completed_at       timestamptz,
    failure_code       text,
    correlation_id     text        NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT availability_recalculation_request_pk PRIMARY KEY (id),
    CONSTRAINT availability_recalculation_request_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT availability_recalculation_request_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT availability_recalculation_request_trigger_ck
        CHECK (trigger_class IN (
            'STOCK_OR_SELLABILITY', 'SALES_EVIDENCE', 'RETURN_EVIDENCE',
            'INBOUND_CHANGE', 'LEAD_TIME_POLICY', 'DEMAND_POLICY',
            'PROFIT_OR_LIFECYCLE', 'MAPPING_OR_OWNERSHIP', 'EXCEPTION_LIFECYCLE',
            'FRESHNESS_CHANGE', 'VERIFICATION_EVIDENCE', 'MANUAL_REQUEST')),
    CONSTRAINT availability_recalculation_request_state_ck
        CHECK (state IN ('PENDING', 'LEASED', 'COMPLETED', 'FAILED', 'ABANDONED')),
    CONSTRAINT availability_recalculation_request_attempt_ck
        CHECK (attempt_count >= 0 AND attempt_count <= 1000),
    CONSTRAINT availability_recalculation_request_lease_ck
        CHECK ((state = 'LEASED') = (leased_until IS NOT NULL AND lease_owner IS NOT NULL)),
    CONSTRAINT availability_recalculation_request_completion_ck
        CHECK ((state IN ('COMPLETED', 'FAILED', 'ABANDONED')) = (completed_at IS NOT NULL)),
    CONSTRAINT availability_recalculation_request_failure_ck
        CHECK (state <> 'FAILED' OR failure_code IS NOT NULL),
    CONSTRAINT availability_recalculation_request_reference_ck
        CHECK (trigger_reference IS NULL OR length(btrim(trigger_reference)) BETWEEN 1 AND 256),
    CONSTRAINT availability_recalculation_request_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

-- One pending request per variant. Concurrency and replay collapse onto this
-- index rather than onto a check a service performs and a second thread misses.
CREATE UNIQUE INDEX availability_recalculation_request_pending_uq
    ON ops.availability_recalculation_request (organization_id, product_variant_id)
    WHERE state IN ('PENDING', 'LEASED');
CREATE INDEX availability_recalculation_request_claim_ix
    ON ops.availability_recalculation_request (state, fact_accepted_at)
    WHERE state IN ('PENDING', 'LEASED');

-- One pass of the full portfolio reconciliation.
--
-- The sweep exists to catch what targeting missed: a dropped trigger, an
-- out-of-order fact, an expired inbound or exception, an interrupted worker. A
-- missed or failed cadence is an operator-visible incident, which is why the
-- run records its own outcome rather than only logging.
CREATE TABLE ops.availability_reconciliation_run (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    as_of                  timestamptz NOT NULL,
    state                  text        NOT NULL,
    trigger_kind           text        NOT NULL,
    variant_count          integer,
    changed_card_count     integer,
    repaired_count         integer,
    expired_inbound_count  integer,
    expired_exception_count integer,
    failure_code           text,
    started_at             timestamptz NOT NULL,
    completed_at           timestamptz,
    correlation_id         text        NOT NULL,
    CONSTRAINT availability_reconciliation_run_pk PRIMARY KEY (id),
    CONSTRAINT availability_reconciliation_run_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT availability_reconciliation_run_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT availability_reconciliation_run_state_ck
        CHECK (state IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT availability_reconciliation_run_trigger_ck
        CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'RECOVERY')),
    CONSTRAINT availability_reconciliation_run_completion_ck
        CHECK ((state IN ('COMPLETED', 'FAILED')) = (completed_at IS NOT NULL)),
    CONSTRAINT availability_reconciliation_run_failure_ck
        CHECK (state <> 'FAILED' OR failure_code IS NOT NULL),
    CONSTRAINT availability_reconciliation_run_counts_ck
        CHECK (coalesce(variant_count, 0) >= 0
           AND coalesce(changed_card_count, 0) >= 0
           AND coalesce(repaired_count, 0) >= 0
           AND coalesce(expired_inbound_count, 0) >= 0
           AND coalesce(expired_exception_count, 0) >= 0),
    CONSTRAINT availability_reconciliation_run_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

-- Only one sweep of an organization at a time. A second concurrent sweep would
-- make "targeted equals sweep" untestable.
CREATE UNIQUE INDEX availability_reconciliation_run_active_uq
    ON ops.availability_reconciliation_run (organization_id)
    WHERE state = 'RUNNING';
CREATE INDEX availability_reconciliation_run_recent_ix
    ON ops.availability_reconciliation_run (organization_id, started_at DESC);

ALTER TABLE mart.availability_risk_card
    ADD CONSTRAINT availability_risk_card_run_fk
        FOREIGN KEY (reconciliation_run_id, organization_id)
        REFERENCES ops.availability_reconciliation_run (id, organization_id);

-- The internal latency of one recalculation, kept as evidence rather than as a
-- metric that has already been aggregated away.
--
-- Source latency and MarketOps latency are separate columns because the
-- Contract requires them to be separately observable: a slow marketplace feed
-- and a slow worker are different incidents with different owners.
CREATE TABLE ops.availability_slo_observation (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    product_variant_id     uuid        NOT NULL,
    lane                   text        NOT NULL,
    path_kind              text        NOT NULL,
    source_event_time      timestamptz,
    source_updated_at      timestamptz,
    ingested_at            timestamptz,
    fact_accepted_at       timestamptz NOT NULL,
    risk_calculated_at     timestamptz NOT NULL,
    case_updated_at        timestamptz,
    internal_latency_ms    bigint      NOT NULL,
    source_latency_ms      bigint,
    breached               boolean     NOT NULL,
    correlation_id         text        NOT NULL,
    CONSTRAINT availability_slo_observation_pk PRIMARY KEY (id),
    CONSTRAINT availability_slo_observation_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT availability_slo_observation_lane_ck
        CHECK (lane IN ('HEALTHY', 'WATCH', 'HIGH', 'CRITICAL', 'REVIEW', 'UNRESOLVED')),
    CONSTRAINT availability_slo_observation_path_ck
        CHECK (path_kind IN ('TARGETED', 'RECONCILIATION')),
    CONSTRAINT availability_slo_observation_latency_ck
        CHECK (internal_latency_ms >= 0),
    CONSTRAINT availability_slo_observation_source_latency_ck
        CHECK (source_latency_ms IS NULL OR source_latency_ms >= 0),
    CONSTRAINT availability_slo_observation_order_ck
        CHECK (risk_calculated_at >= fact_accepted_at),
    CONSTRAINT availability_slo_observation_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX availability_slo_observation_window_ix
    ON ops.availability_slo_observation (organization_id, lane, risk_calculated_at DESC);
CREATE INDEX availability_slo_observation_breach_ix
    ON ops.availability_slo_observation (organization_id, risk_calculated_at DESC)
    WHERE breached;

-- ---------------------------------------------------------------------------
-- Accountable case
-- ---------------------------------------------------------------------------

-- One accountable case for one cause.
--
-- Identity is the cause, not the calculation. A card recalculated every minute
-- for a day is one case with a day of appended evidence, not fourteen hundred
-- tasks. The partial unique index below is what makes that true under
-- concurrency and replay; a service-level "does one already exist?" check is
-- exactly the shape of code that two threads defeat.
--
-- The state machine deliberately refuses to collapse. ACTION_RECORDED is not
-- success, VERIFYING is not success, and ACCEPTED_RISK is not success. Only
-- VERIFIED_SUCCESS is, and only fresh cause-specific outcome evidence produces
-- it.
CREATE TABLE ops.availability_case (
    id                      uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    card_id                 uuid        NOT NULL,
    child_id                uuid        NOT NULL,
    cause_code              text        NOT NULL,
    cause_key               text        NOT NULL,
    child_kind              text        NOT NULL,
    severity                text        NOT NULL,
    state                   text        NOT NULL,
    accountable_role_code   text        NOT NULL,
    assignee_user_id        uuid,
    action_due_at           timestamptz NOT NULL,
    outcome_due_at          timestamptz,
    action_recorded_at      timestamptz,
    verification_started_at timestamptz,
    verified_at             timestamptz,
    closed_at               timestamptz,
    closure_reason          text,
    reopen_count            integer     NOT NULL DEFAULT 0,
    escalation_level        integer     NOT NULL DEFAULT 0,
    activation_policy_id    uuid        NOT NULL,
    first_activated_at      timestamptz NOT NULL,
    last_evidence_at        timestamptz NOT NULL,
    correlation_id          text        NOT NULL,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    version                 bigint      NOT NULL DEFAULT 0,
    CONSTRAINT availability_case_pk PRIMARY KEY (id),
    CONSTRAINT availability_case_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT availability_case_card_fk
        FOREIGN KEY (card_id, organization_id)
        REFERENCES mart.availability_risk_card (id, organization_id),
    CONSTRAINT availability_case_child_fk
        FOREIGN KEY (child_id, organization_id)
        REFERENCES mart.availability_risk_child (id, organization_id),
    CONSTRAINT availability_case_assignee_fk
        FOREIGN KEY (assignee_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT availability_case_role_fk
        FOREIGN KEY (accountable_role_code) REFERENCES iam.business_role (code),
    CONSTRAINT availability_case_policy_fk
        FOREIGN KEY (activation_policy_id, organization_id)
        REFERENCES core.work_activation_policy (id, organization_id),
    CONSTRAINT availability_case_kind_ck CHECK (child_kind IN ('CHANNEL', 'COMPANY')),
    CONSTRAINT availability_case_cause_ck CHECK (cause_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT availability_case_cause_key_ck
        CHECK (length(btrim(cause_key)) BETWEEN 1 AND 256),
    CONSTRAINT availability_case_severity_ck
        CHECK (severity IN ('WATCH', 'HIGH', 'CRITICAL', 'REVIEW', 'UNRESOLVED')),
    CONSTRAINT availability_case_state_ck
        CHECK (state IN (
            'OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ACTION_RECORDED', 'VERIFYING',
            'VERIFIED_SUCCESS', 'REOPENED', 'ESCALATED', 'REWORK_REQUIRED',
            'ACCEPTED_RISK', 'CANCELLED')),
    CONSTRAINT availability_case_assignment_ck
        CHECK (state NOT IN ('ASSIGNED', 'IN_PROGRESS') OR assignee_user_id IS NOT NULL),
    -- Recording an action is a distinct, timestamped fact from verifying an
    -- outcome. A state that claims one cannot be missing the other's evidence.
    CONSTRAINT availability_case_action_ck
        CHECK (state NOT IN ('ACTION_RECORDED', 'VERIFYING', 'VERIFIED_SUCCESS')
            OR action_recorded_at IS NOT NULL),
    CONSTRAINT availability_case_verification_ck
        CHECK ((state = 'VERIFIED_SUCCESS') = (verified_at IS NOT NULL)),
    CONSTRAINT availability_case_closure_ck
        CHECK ((state IN ('VERIFIED_SUCCESS', 'CANCELLED'))
            = (closed_at IS NOT NULL AND closure_reason IS NOT NULL)),
    CONSTRAINT availability_case_reopen_ck
        CHECK (reopen_count >= 0 AND reopen_count <= 10000),
    CONSTRAINT availability_case_escalation_ck
        CHECK (escalation_level BETWEEN 0 AND 3),
    CONSTRAINT availability_case_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

-- One live case per cause. This is Contract invariant 23 as a database
-- guarantee: the same active cause cannot create a duplicate task.
CREATE UNIQUE INDEX availability_case_live_cause_uq
    ON ops.availability_case (organization_id, cause_key)
    WHERE state NOT IN ('VERIFIED_SUCCESS', 'CANCELLED');
CREATE INDEX availability_case_queue_ix
    ON ops.availability_case (organization_id, state, severity, action_due_at);
CREATE INDEX availability_case_assignee_ix
    ON ops.availability_case (assignee_user_id, state)
    WHERE assignee_user_id IS NOT NULL;
CREATE INDEX availability_case_child_ix ON ops.availability_case (child_id);
CREATE INDEX availability_case_outcome_due_ix
    ON ops.availability_case (outcome_due_at)
    WHERE state IN ('ACTION_RECORDED', 'VERIFYING');

-- Everything that ever happened to a case. Append-only.
--
-- A reopen appends rather than resets, so the history a reviewer needs to see
-- ("this is the fourth time this month") survives the reopen that produced it.
-- Structured action evidence lives here as jsonb with a required shape: a
-- free-text acknowledgement cannot satisfy the action stage, and this is where
-- that is enforced rather than in a validator somebody can bypass.
CREATE TABLE ops.availability_case_event (
    id                   uuid        NOT NULL,
    case_id              uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    sequence_no          integer     NOT NULL,
    event_kind           text        NOT NULL,
    from_state           text,
    to_state             text,
    action_kind          text,
    action_evidence      jsonb,
    verification_kind    text,
    verification_outcome text,
    actor_user_id        uuid,
    actor_role_code      text,
    reason               text        NOT NULL,
    evidence_reference   text,
    observed_at          timestamptz,
    occurred_at          timestamptz NOT NULL,
    correlation_id       text        NOT NULL,
    CONSTRAINT availability_case_event_pk PRIMARY KEY (id),
    CONSTRAINT availability_case_event_case_fk
        FOREIGN KEY (case_id, organization_id)
        REFERENCES ops.availability_case (id, organization_id),
    CONSTRAINT availability_case_event_actor_fk
        FOREIGN KEY (actor_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT availability_case_event_role_fk
        FOREIGN KEY (actor_role_code) REFERENCES iam.business_role (code),
    CONSTRAINT availability_case_event_sequence_uq UNIQUE (case_id, sequence_no),
    CONSTRAINT availability_case_event_sequence_ck CHECK (sequence_no >= 1),
    CONSTRAINT availability_case_event_kind_ck
        CHECK (event_kind IN (
            'ACTIVATED', 'EVIDENCE_APPENDED', 'ASSIGNED', 'SEVERITY_CHANGED',
            'ACTION_RECORDED', 'VERIFICATION_STARTED', 'VERIFICATION_OBSERVED',
            'VERIFIED_SUCCESS', 'REOPENED', 'ESCALATED', 'REWORK_REQUIRED',
            'EXCEPTION_APPLIED', 'EXCEPTION_INVALIDATED', 'CANCELLED')),
    -- The closed set of structured actions. Anything else is not an action.
    CONSTRAINT availability_case_event_action_kind_ck
        CHECK (action_kind IS NULL OR action_kind IN (
            'INBOUND_EVIDENCE_BOUND', 'CHANNEL_RESTORATION_REFERENCE',
            'DATA_OR_MAPPING_REPAIR', 'POLICY_VERSION_PUBLISHED',
            'QUALITY_DISPOSITION_RECORDED', 'OWNERSHIP_DECLARATION_PUBLISHED')),
    -- An action event must carry a structured action and its evidence. This is
    -- the constraint that makes "a free-text acknowledgement is insufficient"
    -- a property of the schema.
    CONSTRAINT availability_case_event_action_shape_ck
        CHECK (event_kind <> 'ACTION_RECORDED'
            OR (action_kind IS NOT NULL
                AND action_evidence IS NOT NULL
                AND jsonb_typeof(action_evidence) = 'object'
                AND action_evidence ? 'reference'
                AND evidence_reference IS NOT NULL
                AND actor_user_id IS NOT NULL)),
    CONSTRAINT availability_case_event_verification_kind_ck
        CHECK (verification_kind IS NULL OR verification_kind IN (
            'COMPANY_RISK_BELOW_THRESHOLD', 'CHANNEL_FRESH_AND_SELLABLE',
            'SOURCE_RECOVERED', 'UNIQUE_POLICY_RESOLVED',
            'QUALITY_DISPOSITION_RECOMPUTED')),
    CONSTRAINT availability_case_event_verification_outcome_ck
        CHECK (verification_outcome IS NULL OR verification_outcome IN (
            'VERIFIED', 'CONTINUING', 'FAILED', 'REGRESSED')),
    -- A verification observation must name what it verified, when it was
    -- observed, and how it came out. Freshness is the whole point of stage two.
    CONSTRAINT availability_case_event_verification_shape_ck
        CHECK (event_kind <> 'VERIFICATION_OBSERVED'
            OR (verification_kind IS NOT NULL
                AND verification_outcome IS NOT NULL
                AND observed_at IS NOT NULL)),
    CONSTRAINT availability_case_event_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT availability_case_event_evidence_ck
        CHECK (evidence_reference IS NULL
            OR length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT availability_case_event_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX availability_case_event_case_ix
    ON ops.availability_case_event (case_id, sequence_no DESC);
CREATE INDEX availability_case_event_kind_ix
    ON ops.availability_case_event (organization_id, event_kind, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Accepted exception
-- ---------------------------------------------------------------------------

-- A business decision to accept a calculated risk, for a while, on the record.
--
-- It disposes of the risk; it does not change it. The card keeps showing the
-- calculated lane alongside the acceptance and its expiry, and no exception
-- ever produces VERIFIED_SUCCESS. There is no state here that hides a risk from
-- monitoring, which is why expiry is mandatory and unbounded acceptance is not
-- representable.
CREATE TABLE ops.availability_accepted_exception (
    id                        uuid           NOT NULL,
    organization_id           uuid           NOT NULL,
    case_id                   uuid           NOT NULL,
    child_id                  uuid           NOT NULL,
    cause_code                text           NOT NULL,
    scope_kind                text           NOT NULL,
    scope_reference           text           NOT NULL,
    reason_code               text           NOT NULL,
    rationale                 text           NOT NULL,
    expected_consequence      text           NOT NULL,
    consequence_amount        numeric(18, 4),
    consequence_currency      text,
    evidence_reference        text           NOT NULL,
    requested_by_user_id      uuid           NOT NULL,
    requested_at              timestamptz    NOT NULL,
    decision_owner_role_code  text           NOT NULL,
    required_authority_level  text           NOT NULL,
    state                     text           NOT NULL,
    effective_from            timestamptz,
    expires_at                timestamptz,
    review_at                 timestamptz,
    invalidated_at            timestamptz,
    invalidation_reason       text,
    materiality_policy_id     uuid,
    policy_version            integer,
    occurrence_count          integer        NOT NULL DEFAULT 1,
    created_at                timestamptz    NOT NULL,
    updated_at                timestamptz    NOT NULL,
    version                   bigint         NOT NULL DEFAULT 0,
    CONSTRAINT availability_accepted_exception_pk PRIMARY KEY (id),
    CONSTRAINT availability_accepted_exception_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT availability_accepted_exception_case_fk
        FOREIGN KEY (case_id, organization_id)
        REFERENCES ops.availability_case (id, organization_id),
    CONSTRAINT availability_accepted_exception_child_fk
        FOREIGN KEY (child_id, organization_id)
        REFERENCES mart.availability_risk_child (id, organization_id),
    CONSTRAINT availability_accepted_exception_requester_fk
        FOREIGN KEY (requested_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT availability_accepted_exception_role_fk
        FOREIGN KEY (decision_owner_role_code) REFERENCES iam.business_role (code),
    CONSTRAINT availability_accepted_exception_policy_fk
        FOREIGN KEY (materiality_policy_id, organization_id)
        REFERENCES core.exception_materiality_policy (id, organization_id),
    CONSTRAINT availability_accepted_exception_cause_ck
        CHECK (cause_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT availability_accepted_exception_scope_ck
        CHECK (scope_kind IN ('CHILD', 'VARIANT', 'STORE', 'CHANNEL')),
    CONSTRAINT availability_accepted_exception_reason_code_ck
        CHECK (reason_code IN (
            'PLANNED_DISCONTINUATION', 'SEASONAL_PAUSE', 'SUPPLIER_OUTAGE_ACCEPTED',
            'COMMERCIALLY_IMMATERIAL', 'ALTERNATIVE_SUPPLY_ARRANGED',
            'KNOWN_DATA_LIMITATION_ACCEPTED')),
    CONSTRAINT availability_accepted_exception_authority_ck
        CHECK (required_authority_level IN ('DOMAIN_LEAD', 'OPS_LEAD', 'RISK_AUTHORITY')),
    CONSTRAINT availability_accepted_exception_state_ck
        CHECK (state IN (
            'REQUESTED', 'AUTHORITY_BLOCKED', 'ACTIVE', 'REJECTED',
            'EXPIRED', 'INVALIDATED', 'WITHDRAWN')),
    -- An active acceptance is always bounded in time. An acceptance without an
    -- expiry is a permanent hidden monitoring exclusion, which the Contract
    -- forbids outright.
    CONSTRAINT availability_accepted_exception_active_ck
        CHECK (state <> 'ACTIVE'
            OR (effective_from IS NOT NULL
                AND expires_at IS NOT NULL
                AND review_at IS NOT NULL
                AND expires_at > effective_from
                AND materiality_policy_id IS NOT NULL
                AND policy_version IS NOT NULL)),
    CONSTRAINT availability_accepted_exception_invalidation_ck
        CHECK ((state IN ('INVALIDATED', 'EXPIRED'))
            = (invalidated_at IS NOT NULL AND invalidation_reason IS NOT NULL)),
    CONSTRAINT availability_accepted_exception_currency_ck
        CHECK (consequence_currency IS NULL OR consequence_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT availability_accepted_exception_amount_ck
        CHECK ((consequence_amount IS NULL) = (consequence_currency IS NULL)),
    CONSTRAINT availability_accepted_exception_rationale_ck
        CHECK (length(btrim(rationale)) BETWEEN 1 AND 2048),
    CONSTRAINT availability_accepted_exception_consequence_ck
        CHECK (length(btrim(expected_consequence)) BETWEEN 1 AND 2048),
    CONSTRAINT availability_accepted_exception_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT availability_accepted_exception_scope_reference_ck
        CHECK (length(btrim(scope_reference)) BETWEEN 1 AND 256),
    CONSTRAINT availability_accepted_exception_occurrence_ck
        CHECK (occurrence_count >= 1)
);

-- One live acceptance per cause and scope. Two overlapping acceptances of the
-- same risk would make it unclear which expiry governs.
CREATE UNIQUE INDEX availability_accepted_exception_live_uq
    ON ops.availability_accepted_exception
       (organization_id, child_id, cause_code, scope_kind, scope_reference)
    WHERE state IN ('REQUESTED', 'AUTHORITY_BLOCKED', 'ACTIVE');
CREATE INDEX availability_accepted_exception_expiry_ix
    ON ops.availability_accepted_exception (expires_at)
    WHERE state = 'ACTIVE';
CREATE INDEX availability_accepted_exception_case_ix
    ON ops.availability_accepted_exception (case_id, state);

-- One recorded decision about one exception request. Append-only.
--
-- requester_is_approver is stored rather than derived so the separation rule is
-- evidence in the row: for a critical, repeated or material acceptance the
-- requester cannot be the sole final approver, and the check below refuses the
-- insert rather than trusting a service to have looked.
CREATE TABLE ops.availability_exception_decision (
    id                      uuid        NOT NULL,
    organization_id         uuid        NOT NULL,
    exception_id            uuid        NOT NULL,
    decision                text        NOT NULL,
    authority_level         text        NOT NULL,
    decided_by_user_id      uuid        NOT NULL,
    decided_by_role_code    text        NOT NULL,
    delegation_reference    text,
    requester_is_approver   boolean     NOT NULL,
    separation_required     boolean     NOT NULL,
    authenticated_at        timestamptz,
    step_up_satisfied       boolean     NOT NULL,
    reason                  text        NOT NULL,
    granted_effective_from  timestamptz,
    granted_expires_at      timestamptz,
    decided_at              timestamptz NOT NULL,
    correlation_id          text        NOT NULL,
    CONSTRAINT availability_exception_decision_pk PRIMARY KEY (id),
    CONSTRAINT availability_exception_decision_exception_fk
        FOREIGN KEY (exception_id, organization_id)
        REFERENCES ops.availability_accepted_exception (id, organization_id),
    CONSTRAINT availability_exception_decision_user_fk
        FOREIGN KEY (decided_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT availability_exception_decision_role_fk
        FOREIGN KEY (decided_by_role_code) REFERENCES iam.business_role (code),
    CONSTRAINT availability_exception_decision_decision_ck
        CHECK (decision IN ('APPROVED', 'REJECTED', 'AUTHORITY_BLOCKED')),
    CONSTRAINT availability_exception_decision_authority_ck
        CHECK (authority_level IN ('DOMAIN_LEAD', 'OPS_LEAD', 'RISK_AUTHORITY')),
    -- Requester separation, in the database. An approval that needed
    -- separation and was made by the requester cannot be stored at all.
    CONSTRAINT availability_exception_decision_separation_ck
        CHECK (NOT (decision = 'APPROVED'
                AND separation_required
                AND requester_is_approver)),
    -- Approving an acceptance is a step-up action, and an approval must grant a
    -- bounded period.
    CONSTRAINT availability_exception_decision_step_up_ck
        CHECK (decision <> 'APPROVED' OR (step_up_satisfied AND authenticated_at IS NOT NULL)),
    CONSTRAINT availability_exception_decision_grant_ck
        CHECK ((decision = 'APPROVED')
            = (granted_effective_from IS NOT NULL AND granted_expires_at IS NOT NULL)),
    CONSTRAINT availability_exception_decision_period_ck
        CHECK (granted_expires_at IS NULL OR granted_expires_at > granted_effective_from),
    CONSTRAINT availability_exception_decision_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT availability_exception_decision_delegation_ck
        CHECK (delegation_reference IS NULL
            OR length(btrim(delegation_reference)) BETWEEN 1 AND 256),
    CONSTRAINT availability_exception_decision_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

-- One standing authorization per exception. A second approval would be a
-- second, differently-bounded licence to ignore the same risk.
CREATE UNIQUE INDEX availability_exception_decision_authorization_uq
    ON ops.availability_exception_decision (exception_id)
    WHERE decision = 'APPROVED';
CREATE INDEX availability_exception_decision_exception_ix
    ON ops.availability_exception_decision (exception_id, decided_at DESC);

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Availability risk reads canonical facts and writes a projection, a case and a
-- decision record. Nothing here participates in acquisition call authority, and
-- nothing here is a platform write path: this Slice has no stock command,
-- outbox, adapter write or readback at all.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'supply_ownership_declaration', 'NO_ROUTE', NULL,
        'ownership and distinctness declaration; read by risk calculation only'),
    ('core', 'lead_time_safety_policy', 'NO_ROUTE', NULL,
        'versioned procurement policy; read by risk calculation only'),
    ('core', 'demand_observation_policy', 'NO_ROUTE', NULL,
        'versioned demand-window policy; read by risk calculation only'),
    ('core', 'work_activation_policy', 'NO_ROUTE', NULL,
        'versioned activation policy; read by case activation only'),
    ('core', 'exception_materiality_policy', 'NO_ROUTE', NULL,
        'versioned exception materiality thresholds; fails closed when absent'),
    ('core', 'inbound_supply_attestation', 'NO_ROUTE', NULL,
        'inbound claim identity; no acquisition authority reads it'),
    ('core', 'inbound_supply_attestation_version', 'NO_ROUTE', NULL,
        'append-only attested inbound state; no acquisition authority reads it'),
    ('mart', 'availability_risk_card', 'NO_ROUTE', NULL,
        'rebuildable parent projection; no acquisition authority reads it'),
    ('mart', 'availability_risk_child', 'NO_ROUTE', NULL,
        'rebuildable channel and company child projection'),
    ('mart', 'availability_risk_factor', 'NO_ROUTE', NULL,
        'append-only visible rank factors for one calculation'),
    ('mart', 'availability_risk_evidence', 'NO_ROUTE', NULL,
        'append-only evidence references for one calculation'),
    ('mart', 'demand_window_observation', 'NO_ROUTE', NULL,
        'append-only D7/D14/D30 coverage and censoring evidence'),
    ('ops', 'availability_recalculation_request', 'NO_ROUTE', NULL,
        'internal recalculation trigger queue; not an acquisition job'),
    ('ops', 'availability_reconciliation_run', 'NO_ROUTE', NULL,
        'internal full-sweep run record; performs no external call'),
    ('ops', 'availability_slo_observation', 'NO_ROUTE', NULL,
        'append-only internal latency evidence'),
    ('ops', 'availability_case', 'NO_ROUTE', NULL,
        'accountable cause-keyed case state; no acquisition authority reads it'),
    ('ops', 'availability_case_event', 'NO_ROUTE', NULL,
        'append-only case lifecycle, action and verification journal'),
    ('ops', 'availability_accepted_exception', 'NO_ROUTE', NULL,
        'bounded accepted-risk disposition; never changes a calculated lane'),
    ('ops', 'availability_exception_decision', 'NO_ROUTE', NULL,
        'append-only exception decision; consumed by no write gate');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Policies and declarations carry state and are retired by an update; nothing
-- rewrites a published version's values, because a new version is a new row.
GRANT SELECT, INSERT, UPDATE ON core.supply_ownership_declaration TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.lead_time_safety_policy TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.demand_observation_policy TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.work_activation_policy TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.exception_materiality_policy TO marketops_app;

-- An attestation is append-only: an amendment or a cancellation is a new
-- version row, so the claim that was believed yesterday stays readable.
GRANT SELECT, INSERT ON core.inbound_supply_attestation TO marketops_app;
GRANT SELECT, INSERT ON core.inbound_supply_attestation_version TO marketops_app;

-- The card and child rows are a projection and are rebuilt in place. Their
-- supporting detail is append-only per calculation, so a rebuild adds a
-- generation rather than erasing the evidence of the previous one.
GRANT SELECT, INSERT, UPDATE ON mart.availability_risk_card TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON mart.availability_risk_child TO marketops_app;
GRANT SELECT, INSERT ON mart.availability_risk_factor TO marketops_app;
GRANT SELECT, INSERT ON mart.availability_risk_evidence TO marketops_app;
GRANT SELECT, INSERT ON mart.demand_window_observation TO marketops_app;

-- Queue and run rows are leased and completed. Observations, case events and
-- decisions are permanent. No DELETE is granted anywhere: a case is closed by a
-- recorded transition, and a decision is not withdrawable by deletion.
GRANT SELECT, INSERT, UPDATE ON ops.availability_recalculation_request TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.availability_reconciliation_run TO marketops_app;
GRANT SELECT, INSERT ON ops.availability_slo_observation TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.availability_case TO marketops_app;
GRANT SELECT, INSERT ON ops.availability_case_event TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.availability_accepted_exception TO marketops_app;
GRANT SELECT, INSERT ON ops.availability_exception_decision TO marketops_app;
