-- Canonical metrics: the versioned definitions this product computes, the runs
-- that computed them, the values those runs produced, and the exact inputs each
-- value was derived from.
--
-- A canonical metric is deterministic, versioned and reproducible. The
-- definition set is seeded rather than administered because a metric whose
-- meaning can be edited at run time is not a definition, and because a stored
-- value has to be able to name the exact definition version that produced it
-- long after the definition has moved on.
--
-- Values are append-only and keyed by the digest of their inputs. Recomputing
-- from identical inputs therefore writes nothing new, and recomputing after a
-- late return or a settlement adjustment writes a new value beside the old one
-- rather than over it. The current value of a metric is the most recently
-- computed row, and every earlier answer stays readable next to the evidence it
-- was built from.
--
-- No model output reaches these tables. The AI boundary reads canonical values
-- and cites them; it cannot write one, and there is no privilege by which it
-- could.

-- ---------------------------------------------------------------------------
-- Definitions
-- ---------------------------------------------------------------------------

CREATE TABLE mart.metric_definition (
    metric_code        text    NOT NULL,
    definition_version integer NOT NULL,
    display_name       text    NOT NULL,
    unit_kind          text    NOT NULL,
    formula_statement  text    NOT NULL,
    domain             text    NOT NULL,
    owner_label        text    NOT NULL,
    status             text    NOT NULL,
    CONSTRAINT metric_definition_pk PRIMARY KEY (metric_code, definition_version),
    CONSTRAINT metric_definition_code_ck CHECK (metric_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT metric_definition_version_ck CHECK (definition_version > 0),
    CONSTRAINT metric_definition_unit_ck
        CHECK (unit_kind IN ('MONEY', 'RATIO', 'COUNT', 'DAYS')),
    CONSTRAINT metric_definition_domain_ck
        CHECK (domain IN ('FUNNEL', 'SALES', 'RETURNS', 'INVENTORY',
                          'ADVERTISING', 'COST', 'PROFIT', 'QUALITY')),
    CONSTRAINT metric_definition_status_ck CHECK (status IN ('ACTIVE', 'RETIRED'))
);

-- Exactly one live version per metric. A new version is added and the previous
-- one retired in the same migration, so a value can always resolve its
-- definition and no metric ever has two current meanings.
CREATE UNIQUE INDEX metric_definition_live_uq
    ON mart.metric_definition (metric_code)
    WHERE status = 'ACTIVE';

INSERT INTO mart.metric_definition
    (metric_code, definition_version, display_name, unit_kind, formula_statement,
     domain, owner_label, status) VALUES
    ('IMPRESSIONS', 1, 'Impressions', 'COUNT',
        'Sum of impressions over the window; NOT_AVAILABLE when no source publishes it.',
        'FUNNEL', 'analyticsdecision', 'ACTIVE'),
    ('CLICKS', 1, 'Clicks', 'COUNT',
        'Sum of clicks over the window; NOT_AVAILABLE when no source publishes it.',
        'FUNNEL', 'analyticsdecision', 'ACTIVE'),
    ('CLICK_THROUGH_RATE', 1, 'Click-through rate', 'RATIO',
        'CLICKS divided by IMPRESSIONS; undefined when impressions are absent or zero.',
        'FUNNEL', 'analyticsdecision', 'ACTIVE'),
    ('CONVERSION_RATE', 1, 'Conversion rate', 'RATIO',
        'COMPLETED_UNITS divided by the strongest available visit or click measure.',
        'FUNNEL', 'analyticsdecision', 'ACTIVE'),
    ('COMPLETED_UNITS', 1, 'Completed units', 'COUNT',
        'Sum of COMPLETED sale quantities over the window.',
        'SALES', 'analyticsdecision', 'ACTIVE'),
    ('COMPLETED_NET_SALES', 1, 'Completed net sales', 'MONEY',
        'Sum of COMPLETED net amounts over the window in the store currency.',
        'SALES', 'analyticsdecision', 'ACTIVE'),
    ('RETAINED_UNITS', 1, 'Retained units', 'COUNT',
        'Sum of RETAINED sale quantities for the requested retention window.',
        'SALES', 'analyticsdecision', 'ACTIVE'),
    ('RETAINED_NET_SALES', 1, 'Retained net sales', 'MONEY',
        'Sum of RETAINED net amounts for the requested retention window.',
        'SALES', 'analyticsdecision', 'ACTIVE'),
    ('SETTLED_NET_SALES', 1, 'Settled net sales', 'MONEY',
        'Sum of SETTLED net amounts over the window.',
        'SALES', 'analyticsdecision', 'ACTIVE'),
    ('RETURN_UNITS', 1, 'Returned units', 'COUNT',
        'Sum of return quantities over the window across all return kinds.',
        'RETURNS', 'analyticsdecision', 'ACTIVE'),
    ('RETURN_RATE', 1, 'Return rate', 'RATIO',
        'RETURN_UNITS divided by COMPLETED_UNITS; undefined when completed units are zero.',
        'RETURNS', 'analyticsdecision', 'ACTIVE'),
    ('PLATFORM_AVAILABLE_UNITS', 1, 'Platform available units', 'COUNT',
        'Latest observed available quantity per fulfillment mode, summed across modes.',
        'INVENTORY', 'analyticsdecision', 'ACTIVE'),
    ('INTERNAL_AVAILABLE_UNITS', 1, 'Internal available units', 'COUNT',
        'Latest internal on-hand quantity less reserved quantity across warehouses.',
        'INVENTORY', 'analyticsdecision', 'ACTIVE'),
    ('STOCK_COVER_DAYS', 1, 'Stock cover days', 'DAYS',
        'PLATFORM_AVAILABLE_UNITS divided by mean daily COMPLETED_UNITS over the window.',
        'INVENTORY', 'analyticsdecision', 'ACTIVE'),
    ('AD_SPEND', 1, 'Advertising spend', 'MONEY',
        'Sum of advertising spend attributable to the subject over the window.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('AD_COST_OF_SALE', 1, 'Advertising cost of sale', 'RATIO',
        'AD_SPEND divided by COMPLETED_NET_SALES; undefined when net sales are zero.',
        'ADVERTISING', 'analyticsdecision', 'ACTIVE'),
    ('UNIT_COST', 1, 'Unit purchase cost', 'MONEY',
        'Cost version in force at the window end for the mapped internal variant.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('PLATFORM_FEES', 1, 'Platform fees', 'MONEY',
        'Sum of platform fee amounts over the window excluding advertising.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('RETURN_LOSS', 1, 'Return loss', 'MONEY',
        'Sum of recorded return loss amounts over the window.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('VARIABLE_TAX_ESTIMATE', 1, 'Variable tax estimate', 'MONEY',
        'COMPLETED_NET_SALES multiplied by the finance input rate in force.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('OPERATIONAL_CONTRIBUTION_PROFIT', 1, 'Operational contribution profit', 'MONEY',
        'COMPLETED_NET_SALES less unit cost of completed units, platform fees, '
        || 'return loss, advertising spend and the variable tax estimate.',
        'PROFIT', 'analyticsdecision', 'ACTIVE'),
    ('SETTLED_CONTRIBUTION_PROFIT', 1, 'Settled contribution profit', 'MONEY',
        'SETTLED_NET_SALES less unit cost of settled units, settled platform fees, '
        || 'return loss, advertising spend and the variable tax estimate.',
        'PROFIT', 'analyticsdecision', 'ACTIVE'),
    ('CONTRIBUTION_MARGIN', 1, 'Contribution margin', 'RATIO',
        'OPERATIONAL_CONTRIBUTION_PROFIT divided by COMPLETED_NET_SALES.',
        'PROFIT', 'analyticsdecision', 'ACTIVE'),
    ('OBSERVED_SELLING_PRICE', 1, 'Observed selling price', 'MONEY',
        'The most recent observed price a buyer pays, preferring a discounted '
        || 'amount over a selling amount over a list amount.',
        'PROFIT', 'analyticsdecision', 'ACTIVE'),
    ('MINIMUM_PRICE', 1, 'Break-even unit price', 'MONEY',
        'Unit price at which unit contribution profit is exactly zero, given unit '
        || 'cost, the observed proportional fee rate, return loss per unit and the '
        || 'variable tax rate. A commercial floor above break-even is a policy '
        || 'decision applied by the guardrail, not part of this definition.',
        'PROFIT', 'analyticsdecision', 'ACTIVE'),
    ('DATA_COMPLETENESS', 1, 'Data completeness', 'RATIO',
        'Share of the profit definition inputs that resolved to a canonical value.',
        'QUALITY', 'analyticsdecision', 'ACTIVE');

-- ---------------------------------------------------------------------------
-- Calculation runs
-- ---------------------------------------------------------------------------

-- One execution of the metric engine over one scope. definition_set_digest
-- pins which definition versions were in force, so a value computed by an
-- earlier engine can be told apart from one computed after a definition change.
CREATE TABLE mart.calculation_run (
    id                    uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    trigger_kind          text        NOT NULL,
    scope_kind            text        NOT NULL,
    store_ref_id          uuid,
    window_code           text        NOT NULL,
    period_start          timestamptz NOT NULL,
    period_end            timestamptz NOT NULL,
    definition_set_digest text        NOT NULL,
    state                 text        NOT NULL,
    subject_count         integer,
    value_count           integer,
    failure_code          text,
    requested_by_user_id  uuid,
    started_at            timestamptz NOT NULL,
    completed_at          timestamptz,
    correlation_id        text        NOT NULL,
    CONSTRAINT calculation_run_pk PRIMARY KEY (id),
    CONSTRAINT calculation_run_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT calculation_run_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT calculation_run_user_fk
        FOREIGN KEY (requested_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT calculation_run_trigger_ck
        CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'LATE_DATA', 'BACKFILL')),
    CONSTRAINT calculation_run_scope_ck CHECK (scope_kind IN ('ORGANIZATION', 'STORE')),
    CONSTRAINT calculation_run_scope_matrix_ck
        CHECK ((scope_kind = 'STORE') = (store_ref_id IS NOT NULL)),
    CONSTRAINT calculation_run_window_ck CHECK (window_code IN ('D7', 'D14', 'D30')),
    CONSTRAINT calculation_run_period_ck CHECK (period_start < period_end),
    CONSTRAINT calculation_run_digest_ck CHECK (definition_set_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT calculation_run_state_ck
        CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT calculation_run_failure_ck
        CHECK ((state = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT calculation_run_completion_ck
        CHECK ((state = 'RUNNING') = (completed_at IS NULL)),
    CONSTRAINT calculation_run_manual_actor_ck
        CHECK (trigger_kind <> 'MANUAL' OR requested_by_user_id IS NOT NULL)
);

CREATE INDEX calculation_run_scope_ix
    ON mart.calculation_run (organization_id, window_code, started_at DESC);

-- ---------------------------------------------------------------------------
-- Values
-- ---------------------------------------------------------------------------

-- One canonical metric value for one subject, window and period.
--
-- value_state is the distinction the product turns on. AVAILABLE means a number
-- was computed; NOT_AVAILABLE means the source publishes nothing and the value
-- is deliberately absent; UNDEFINED means the definition has no answer for
-- these inputs, such as a ratio whose denominator is zero. None of the three is
-- ever represented as the number zero.
CREATE TABLE mart.metric_value (
    id                  uuid           NOT NULL,
    organization_id     uuid           NOT NULL,
    calculation_run_id  uuid           NOT NULL,
    metric_code         text           NOT NULL,
    definition_version  integer        NOT NULL,
    subject_kind        text           NOT NULL,
    subject_id          uuid           NOT NULL,
    window_code         text           NOT NULL,
    period_start        timestamptz    NOT NULL,
    period_end          timestamptz    NOT NULL,
    value_state         text           NOT NULL,
    numeric_value       numeric(24, 8),
    currency_code       text,
    confidence_state    text           NOT NULL,
    estimated           boolean        NOT NULL,
    oldest_source_time  timestamptz,
    freshness_seconds   bigint,
    input_digest        text           NOT NULL,
    computed_at         timestamptz    NOT NULL,
    CONSTRAINT metric_value_pk PRIMARY KEY (id),
    CONSTRAINT metric_value_run_fk
        FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run (id),
    CONSTRAINT metric_value_definition_fk
        FOREIGN KEY (metric_code, definition_version)
        REFERENCES mart.metric_definition (metric_code, definition_version),
    CONSTRAINT metric_value_subject_ck
        CHECK (subject_kind IN ('PRODUCT_VARIANT', 'PLATFORM_LISTING_VARIANT', 'STORE')),
    CONSTRAINT metric_value_window_ck CHECK (window_code IN ('D7', 'D14', 'D30')),
    CONSTRAINT metric_value_period_ck CHECK (period_start < period_end),
    CONSTRAINT metric_value_state_ck
        CHECK (value_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')),
    -- A number exists exactly when the value is available. An unavailable or
    -- undefined metric carries no number, which is what stops a downstream
    -- reader from treating absence as zero.
    CONSTRAINT metric_value_number_ck
        CHECK ((value_state = 'AVAILABLE') = (numeric_value IS NOT NULL)),
    CONSTRAINT metric_value_currency_ck
        CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT metric_value_confidence_ck
        CHECK (confidence_state IN (
            'CANONICAL_CONFIRMED', 'CANONICAL_PENDING_SETTLEMENT',
            'ESTIMATED_EXPLAINED', 'STALE', 'INCOMPLETE', 'CONFLICTED', 'UNKNOWN')),
    -- An estimated value must say so in its confidence state as well as its
    -- flag, so a reader that inspects only one of the two cannot be misled.
    CONSTRAINT metric_value_estimated_ck
        CHECK (estimated = (confidence_state = 'ESTIMATED_EXPLAINED')),
    CONSTRAINT metric_value_freshness_ck
        CHECK (freshness_seconds IS NULL OR freshness_seconds >= 0),
    CONSTRAINT metric_value_digest_ck CHECK (input_digest ~ '^[0-9a-f]{64}$')
);

-- Identical inputs produce one row. A recompute that changes nothing inserts
-- nothing, and a recompute after late data inserts a distinct row because its
-- input digest differs.
CREATE UNIQUE INDEX metric_value_reproducible_uq
    ON mart.metric_value (
        metric_code, definition_version, subject_kind, subject_id,
        window_code, period_start, period_end, input_digest);

CREATE INDEX metric_value_current_ix
    ON mart.metric_value (
        subject_kind, subject_id, metric_code, window_code, computed_at DESC);
CREATE INDEX metric_value_run_ix ON mart.metric_value (calculation_run_id);
CREATE INDEX metric_value_organization_ix
    ON mart.metric_value (organization_id, computed_at DESC);

-- Every fact a value was built from. This is what makes a metric drillable to
-- source evidence and what an AI Fact claim has to resolve against; a claim
-- naming a reference that is not here is rejected rather than displayed.
CREATE TABLE mart.metric_input_reference (
    id              uuid NOT NULL,
    metric_value_id uuid NOT NULL,
    reference_kind  text NOT NULL,
    reference_id    uuid NOT NULL,
    CONSTRAINT metric_input_reference_pk PRIMARY KEY (id),
    CONSTRAINT metric_input_reference_value_fk
        FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value (id),
    CONSTRAINT metric_input_reference_uq
        UNIQUE (metric_value_id, reference_kind, reference_id),
    CONSTRAINT metric_input_reference_kind_ck
        CHECK (reference_kind IN (
            'FACT_PROVENANCE', 'COST_VERSION', 'FINANCE_INPUT_VERSION',
            'METRIC_VALUE', 'LISTING_MAPPING'))
);

CREATE INDEX metric_input_reference_target_ix
    ON mart.metric_input_reference (reference_kind, reference_id);

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Metrics are computed from stored facts long after acquisition has finished.
-- No call authority reads a metric, and a recomputation must not cancel an
-- acquisition that is running while it happens.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('mart', 'metric_definition', 'NO_ROUTE', NULL,
        'metric vocabulary; no acquisition authority reads it'),
    ('mart', 'calculation_run', 'NO_ROUTE', NULL,
        'analytics execution state; no outbound call is authorised from it'),
    ('mart', 'metric_value', 'NO_ROUTE', NULL,
        'append-only computed value; no acquisition authority reads it'),
    ('mart', 'metric_input_reference', 'NO_ROUTE', NULL,
        'append-only evidence link; no acquisition authority reads it');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Definitions are read-only to the application: a running process cannot change
-- what a metric means. Runs are operational state and accept updates; values
-- and their input references are append-only, so no recomputation can restate
-- an answer somebody has already acted on.
GRANT SELECT ON mart.metric_definition TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON mart.calculation_run TO marketops_app;
GRANT SELECT, INSERT ON mart.metric_value TO marketops_app;
GRANT SELECT, INSERT ON mart.metric_input_reference TO marketops_app;
