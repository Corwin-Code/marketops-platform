-- Deterministic diagnosis: the ordered rule set that reads canonical metrics
-- and the findings it produces.
--
-- Rules run in a fixed order and the order carries meaning. DATA_BLOCKED is
-- first because a subject whose inputs are missing, stale or conflicting cannot
-- be diagnosed at all; when it triggers, every later rule records DECLINED
-- rather than a verdict. A system that answered "conversion is low" from
-- incomplete data would be confidently wrong at the moment somebody is deciding
-- a price.
--
-- A finding is never a model opinion. Each one names the rule version that
-- produced it and the exact canonical metric values it read, so the same inputs
-- always produce the same finding and any finding can be re-derived from stored
-- evidence.

-- ---------------------------------------------------------------------------
-- Rules
-- ---------------------------------------------------------------------------

CREATE TABLE mart.diagnosis_rule (
    rule_code        text    NOT NULL,
    rule_version     integer NOT NULL,
    ordinal          integer NOT NULL,
    display_name     text    NOT NULL,
    statement        text    NOT NULL,
    default_severity text    NOT NULL,
    blocks_execution boolean NOT NULL,
    status           text    NOT NULL,
    CONSTRAINT diagnosis_rule_pk PRIMARY KEY (rule_code, rule_version),
    CONSTRAINT diagnosis_rule_code_ck CHECK (rule_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT diagnosis_rule_version_ck CHECK (rule_version > 0),
    CONSTRAINT diagnosis_rule_ordinal_ck CHECK (ordinal > 0),
    CONSTRAINT diagnosis_rule_severity_ck
        CHECK (default_severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT diagnosis_rule_status_ck CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX diagnosis_rule_live_uq
    ON mart.diagnosis_rule (rule_code)
    WHERE status = 'ACTIVE';

-- One live rule per position keeps the evaluation order total and unambiguous.
CREATE UNIQUE INDEX diagnosis_rule_order_uq
    ON mart.diagnosis_rule (ordinal)
    WHERE status = 'ACTIVE';

INSERT INTO mart.diagnosis_rule
    (rule_code, rule_version, ordinal, display_name, statement,
     default_severity, blocks_execution, status) VALUES
    ('DATA_BLOCKED', 1, 1, 'Data blocked',
        'Mapping is unresolved, a required profit input is missing, or the '
        || 'freshest input is older than the domain freshness target. Later '
        || 'rules are declined for this subject.',
        'CRITICAL', true, 'ACTIVE'),
    ('NEGATIVE_MARGIN', 1, 2, 'Negative margin',
        'Operational contribution profit is available and not positive.',
        'CRITICAL', true, 'ACTIVE'),
    ('STOCKOUT_RISK', 1, 3, 'Stockout risk',
        'Platform available units are zero, or stock cover days are below the '
        || 'configured safety horizon while sales continue.',
        'CRITICAL', true, 'ACTIVE'),
    ('HIGH_RETURN', 1, 4, 'High return rate',
        'Return rate is available and exceeds the configured threshold over a '
        || 'window with enough completed units to be meaningful.',
        'WARNING', false, 'ACTIVE'),
    ('LOW_IMPRESSION', 1, 5, 'Low impressions',
        'Impressions are available and fall below the configured floor for the '
        || 'window, indicating an exposure problem rather than a listing one.',
        'WARNING', false, 'ACTIVE'),
    ('LOW_CLICK_THROUGH', 1, 6, 'Low click-through rate',
        'Impressions are sufficient and click-through rate is below the '
        || 'configured floor.',
        'WARNING', false, 'ACTIVE'),
    ('LOW_CONVERSION', 1, 7, 'Low conversion',
        'Clicks or visits are sufficient and conversion rate is below the '
        || 'configured floor.',
        'WARNING', false, 'ACTIVE'),
    ('ADVERTISING_INEFFICIENT', 1, 8, 'Advertising inefficient',
        'Advertising cost of sale is available and exceeds the configured '
        || 'ceiling over the window.',
        'WARNING', false, 'ACTIVE'),
    ('PRICE_BELOW_MINIMUM', 1, 9, 'Price below break-even',
        'The observed selling price is below the computed break-even price, so '
        || 'every further unit sold at it loses money. A commercial floor above '
        || 'break-even is applied separately by the guardrail.',
        'CRITICAL', true, 'ACTIVE');

-- The metrics a rule reads. Recorded rather than implied so a rule that
-- declines for want of an input can name which input was missing.
CREATE TABLE mart.diagnosis_rule_input (
    rule_code    text    NOT NULL,
    rule_version integer NOT NULL,
    metric_code  text    NOT NULL,
    requirement  text    NOT NULL,
    CONSTRAINT diagnosis_rule_input_pk PRIMARY KEY (rule_code, rule_version, metric_code),
    CONSTRAINT diagnosis_rule_input_rule_fk
        FOREIGN KEY (rule_code, rule_version)
        REFERENCES mart.diagnosis_rule (rule_code, rule_version),
    CONSTRAINT diagnosis_rule_input_requirement_ck
        CHECK (requirement IN ('REQUIRED', 'OPTIONAL'))
);

INSERT INTO mart.diagnosis_rule_input (rule_code, rule_version, metric_code, requirement)
SELECT rule_code, 1, metric_code, requirement
  FROM (VALUES
    ('DATA_BLOCKED', 'DATA_COMPLETENESS', 'REQUIRED'),
    ('NEGATIVE_MARGIN', 'OPERATIONAL_CONTRIBUTION_PROFIT', 'REQUIRED'),
    ('NEGATIVE_MARGIN', 'CONTRIBUTION_MARGIN', 'OPTIONAL'),
    ('STOCKOUT_RISK', 'PLATFORM_AVAILABLE_UNITS', 'REQUIRED'),
    ('STOCKOUT_RISK', 'STOCK_COVER_DAYS', 'OPTIONAL'),
    ('STOCKOUT_RISK', 'INTERNAL_AVAILABLE_UNITS', 'OPTIONAL'),
    ('HIGH_RETURN', 'RETURN_RATE', 'REQUIRED'),
    ('HIGH_RETURN', 'COMPLETED_UNITS', 'REQUIRED'),
    ('LOW_IMPRESSION', 'IMPRESSIONS', 'REQUIRED'),
    ('LOW_CLICK_THROUGH', 'CLICK_THROUGH_RATE', 'REQUIRED'),
    ('LOW_CLICK_THROUGH', 'IMPRESSIONS', 'REQUIRED'),
    ('LOW_CONVERSION', 'CONVERSION_RATE', 'REQUIRED'),
    ('LOW_CONVERSION', 'CLICKS', 'OPTIONAL'),
    ('ADVERTISING_INEFFICIENT', 'AD_COST_OF_SALE', 'REQUIRED'),
    ('ADVERTISING_INEFFICIENT', 'AD_SPEND', 'REQUIRED'),
    ('PRICE_BELOW_MINIMUM', 'MINIMUM_PRICE', 'REQUIRED'),
    ('PRICE_BELOW_MINIMUM', 'OBSERVED_SELLING_PRICE', 'REQUIRED')
  ) AS inputs(rule_code, metric_code, requirement);

-- ---------------------------------------------------------------------------
-- Findings
-- ---------------------------------------------------------------------------

-- What one rule concluded about one subject for one window. DECLINED is a
-- first-class outcome and is stored, not omitted: an operator has to be able to
-- see that a rule could not answer, and why, rather than reading silence as a
-- clean bill of health.
CREATE TABLE mart.diagnosis_finding (
    id                 uuid        NOT NULL,
    organization_id    uuid        NOT NULL,
    calculation_run_id uuid        NOT NULL,
    rule_code          text        NOT NULL,
    rule_version       integer     NOT NULL,
    subject_kind       text        NOT NULL,
    subject_id         uuid        NOT NULL,
    window_code        text        NOT NULL,
    period_start       timestamptz NOT NULL,
    period_end         timestamptz NOT NULL,
    outcome            text        NOT NULL,
    severity           text,
    decline_reason     text,
    detail             jsonb       NOT NULL,
    input_digest       text        NOT NULL,
    evaluated_at       timestamptz NOT NULL,
    CONSTRAINT diagnosis_finding_pk PRIMARY KEY (id),
    CONSTRAINT diagnosis_finding_run_fk
        FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run (id),
    CONSTRAINT diagnosis_finding_rule_fk
        FOREIGN KEY (rule_code, rule_version)
        REFERENCES mart.diagnosis_rule (rule_code, rule_version),
    CONSTRAINT diagnosis_finding_subject_ck
        CHECK (subject_kind IN ('PRODUCT_VARIANT', 'PLATFORM_LISTING_VARIANT', 'STORE')),
    CONSTRAINT diagnosis_finding_window_ck CHECK (window_code IN ('D7', 'D14', 'D30')),
    CONSTRAINT diagnosis_finding_period_ck CHECK (period_start < period_end),
    CONSTRAINT diagnosis_finding_outcome_ck
        CHECK (outcome IN ('TRIGGERED', 'CLEAR', 'DECLINED')),
    CONSTRAINT diagnosis_finding_severity_ck
        CHECK ((outcome = 'TRIGGERED') = (severity IS NOT NULL)),
    CONSTRAINT diagnosis_finding_severity_values_ck
        CHECK (severity IS NULL OR severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT diagnosis_finding_decline_ck
        CHECK ((outcome = 'DECLINED') = (decline_reason IS NOT NULL)),
    CONSTRAINT diagnosis_finding_decline_values_ck
        CHECK (decline_reason IS NULL
            OR decline_reason IN (
                'BLOCKED_BY_EARLIER_RULE', 'REQUIRED_METRIC_UNAVAILABLE',
                'REQUIRED_METRIC_UNDEFINED', 'MAPPING_UNRESOLVED',
                'THRESHOLD_NOT_CONFIGURED', 'INSUFFICIENT_SAMPLE')),
    CONSTRAINT diagnosis_finding_detail_ck CHECK (jsonb_typeof(detail) = 'object'),
    CONSTRAINT diagnosis_finding_digest_ck CHECK (input_digest ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX diagnosis_finding_reproducible_uq
    ON mart.diagnosis_finding (
        rule_code, rule_version, subject_kind, subject_id,
        window_code, period_start, period_end, input_digest);

CREATE INDEX diagnosis_finding_subject_ix
    ON mart.diagnosis_finding (subject_kind, subject_id, window_code, evaluated_at DESC);
CREATE INDEX diagnosis_finding_queue_ix
    ON mart.diagnosis_finding (organization_id, outcome, severity, evaluated_at DESC);

-- The exact canonical values a finding read. A finding whose evidence cannot be
-- reopened is an assertion, and an assertion is not a diagnosis.
CREATE TABLE mart.diagnosis_finding_input (
    id              uuid NOT NULL,
    finding_id      uuid NOT NULL,
    metric_value_id uuid NOT NULL,
    role            text NOT NULL,
    CONSTRAINT diagnosis_finding_input_pk PRIMARY KEY (id),
    CONSTRAINT diagnosis_finding_input_finding_fk
        FOREIGN KEY (finding_id) REFERENCES mart.diagnosis_finding (id),
    CONSTRAINT diagnosis_finding_input_value_fk
        FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value (id),
    CONSTRAINT diagnosis_finding_input_uq UNIQUE (finding_id, metric_value_id),
    CONSTRAINT diagnosis_finding_input_role_ck
        CHECK (role IN ('SUBJECT', 'THRESHOLD_COMPARISON', 'SUPPORTING'))
);

CREATE INDEX diagnosis_finding_input_value_ix
    ON mart.diagnosis_finding_input (metric_value_id);

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('mart', 'diagnosis_rule', 'NO_ROUTE', NULL,
        'diagnosis vocabulary; no acquisition authority reads it'),
    ('mart', 'diagnosis_rule_input', 'NO_ROUTE', NULL,
        'declared rule inputs; no acquisition authority reads it'),
    ('mart', 'diagnosis_finding', 'NO_ROUTE', NULL,
        'append-only deterministic verdict; no acquisition authority reads it'),
    ('mart', 'diagnosis_finding_input', 'NO_ROUTE', NULL,
        'append-only evidence link; no acquisition authority reads it');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Rules are read-only to the application, so a threshold set cannot become a
-- rule set. Findings and their inputs are append-only for the same reason
-- metric values are: an earlier answer that somebody acted on stays readable.
GRANT SELECT ON mart.diagnosis_rule TO marketops_app;
GRANT SELECT ON mart.diagnosis_rule_input TO marketops_app;
GRANT SELECT, INSERT ON mart.diagnosis_finding TO marketops_app;
GRANT SELECT, INSERT ON mart.diagnosis_finding_input TO marketops_app;
