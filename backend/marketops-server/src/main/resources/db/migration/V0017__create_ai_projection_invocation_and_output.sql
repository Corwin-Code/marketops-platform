-- The AI boundary: which providers may be called, which fields may leave, what
-- was actually invoked, and what came back after validation.
--
-- The structure encodes the limit rather than describing it. A field that is
-- not in the seeded allowlist has no row that could carry it. A provider whose
-- eligibility is unrecorded cannot reach a state the gateway accepts. A model's
-- factual claim is stored only as a reference to a canonical metric value, so a
-- fabricated identifier fails a foreign key instead of reaching a screen.
--
-- Nothing here is canonical truth. These tables record what a model was asked
-- and what it answered; the answer becomes actionable only after deterministic
-- validation, and it can never become a metric, a policy or a command.

-- ---------------------------------------------------------------------------
-- Provider eligibility
-- ---------------------------------------------------------------------------

-- An external model provider this deployment may call. Contractual and
-- data-processing eligibility for the operating business is external evidence,
-- so no provider is seeded and an unverified provider is refused by the
-- gateway. Replacing a provider is a recorded decision, not a redeploy.
CREATE TABLE ops.ai_provider (
    id                    uuid        NOT NULL,
    provider_code         text        NOT NULL,
    display_name          text        NOT NULL,
    service_region_label  text,
    invocation_url        text,
    request_template      text,
    response_pointer      text,
    auth_header_name      text,
    auth_value_template   text,
    request_timeout_ms    integer     NOT NULL DEFAULT 60000,
    eligibility_state     text        NOT NULL,
    last_verified_at      timestamptz,
    evidence_ref          text,
    verified_source_title text,
    owner_label           text        NOT NULL,
    status                text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ai_provider_pk PRIMARY KEY (id),
    CONSTRAINT ai_provider_code_uq UNIQUE (provider_code),
    CONSTRAINT ai_provider_code_ck
        CHECK (provider_code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    CONSTRAINT ai_provider_eligibility_ck
        CHECK (eligibility_state IN ('UNKNOWN', 'UNVERIFIED', 'VERIFIED')),
    CONSTRAINT ai_provider_provenance_ck
        CHECK (eligibility_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    CONSTRAINT ai_provider_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    -- The wire shape is the provider's fact, recorded rather than coded. A
    -- plain http endpoint would let a network position impersonate a provider,
    -- and an unrecorded shape would mean a guess compiled into a release.
    CONSTRAINT ai_provider_invocation_url_ck
        CHECK (invocation_url IS NULL
            OR invocation_url ~ '^https://[a-z0-9][a-z0-9.-]{0,252}(:[0-9]{2,5})?(/[A-Za-z0-9._~-]{1,64}){0,8}$'),
    CONSTRAINT ai_provider_response_pointer_ck
        CHECK (response_pointer IS NULL OR response_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$'),
    CONSTRAINT ai_provider_auth_header_ck
        CHECK (auth_header_name IS NULL OR auth_header_name ~ '^[A-Za-z][A-Za-z0-9-]{0,63}$'),
    CONSTRAINT ai_provider_auth_template_ck
        CHECK (auth_value_template IS NULL
            OR auth_value_template ~ '^[!-~ ]{0,32}\{value\}[!-~ ]{0,32}$'),
    CONSTRAINT ai_provider_timeout_ck
        CHECK (request_timeout_ms BETWEEN 1000 AND 300000),
    -- A provider becomes usable only once every part of the call is recorded.
    -- A verified contract with an unrecorded endpoint is not a callable
    -- provider; it is a contract nobody can act on.
    CONSTRAINT ai_provider_active_readiness_ck
        CHECK (status <> 'ACTIVE'
            OR (eligibility_state = 'VERIFIED'
                AND invocation_url IS NOT NULL
                AND request_template IS NOT NULL
                AND response_pointer IS NOT NULL
                AND auth_header_name IS NOT NULL
                AND auth_value_template IS NOT NULL))
);

-- A model offered by one provider. The credential the gateway resolves is named
-- by opaque reference only; no column here can hold key material.
CREATE TABLE ops.ai_model (
    id                    uuid        NOT NULL,
    provider_id           uuid        NOT NULL,
    model_code            text        NOT NULL,
    display_name          text        NOT NULL,
    secret_reference      text        NOT NULL,
    max_context_tokens    integer,
    status                text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ai_model_pk PRIMARY KEY (id),
    CONSTRAINT ai_model_provider_fk FOREIGN KEY (provider_id) REFERENCES ops.ai_provider (id),
    CONSTRAINT ai_model_code_uq UNIQUE (provider_id, model_code),
    CONSTRAINT ai_model_code_ck
        CHECK (model_code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    -- The same opaque reference shape the marketplace credential registry uses.
    -- A value that looked like a token would be refused by its own format.
    CONSTRAINT ai_model_secret_reference_ck
        CHECK (secret_reference
            ~ '^secret-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,4}$'),
    CONSTRAINT ai_model_context_ck
        CHECK (max_context_tokens IS NULL OR max_context_tokens > 0),
    CONSTRAINT ai_model_status_ck CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE INDEX ai_model_provider_ix ON ops.ai_model (provider_id, status);

-- ---------------------------------------------------------------------------
-- Approved data projection
-- ---------------------------------------------------------------------------

CREATE TABLE ops.ai_projection_definition (
    projection_code    text    NOT NULL,
    projection_version integer NOT NULL,
    purpose            text    NOT NULL,
    retention_policy   text    NOT NULL,
    owner_label        text    NOT NULL,
    status             text    NOT NULL,
    CONSTRAINT ai_projection_definition_pk PRIMARY KEY (projection_code, projection_version),
    CONSTRAINT ai_projection_definition_code_ck
        CHECK (projection_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT ai_projection_definition_version_ck CHECK (projection_version > 0),
    CONSTRAINT ai_projection_definition_retention_ck
        CHECK (retention_policy IN ('NO_PROVIDER_RETENTION', 'PROVIDER_RETENTION_AGREED')),
    CONSTRAINT ai_projection_definition_status_ck CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX ai_projection_definition_live_uq
    ON ops.ai_projection_definition (projection_code)
    WHERE status = 'ACTIVE';

INSERT INTO ops.ai_projection_definition
    (projection_code, projection_version, purpose, retention_policy, owner_label, status) VALUES
    ('SKU_GROWTH_PROFIT_DIAGNOSIS', 1,
        'Cross-domain root-cause analysis and price recommendation for one '
        || 'mapped listing variant over one metric window.',
        'NO_PROVIDER_RETENTION', 'aicopilot', 'ACTIVE');

-- The complete set of values a projection may carry. Enforcement reads this
-- table, so widening what leaves the system is a migration under review rather
-- than a code change inside a prompt builder.
--
-- Every field is either a canonical metric, a deterministic finding, or an
-- opaque internal identifier. No buyer attribute, no free source text and no
-- credential-shaped value has a row here, which is why the negative tests can
-- assert the absence structurally rather than by inspecting a prompt string.
CREATE TABLE ops.ai_projection_field (
    projection_code     text NOT NULL,
    projection_version  integer NOT NULL,
    field_path          text NOT NULL,
    data_classification text NOT NULL,
    CONSTRAINT ai_projection_field_pk
        PRIMARY KEY (projection_code, projection_version, field_path),
    CONSTRAINT ai_projection_field_definition_fk
        FOREIGN KEY (projection_code, projection_version)
        REFERENCES ops.ai_projection_definition (projection_code, projection_version),
    CONSTRAINT ai_projection_field_path_ck
        CHECK (field_path ~ '^[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*)*$'),
    CONSTRAINT ai_projection_field_classification_ck
        CHECK (data_classification IN (
            'OPAQUE_IDENTIFIER', 'CANONICAL_METRIC', 'DETERMINISTIC_FINDING',
            'OPERATING_ATTRIBUTE'))
);

INSERT INTO ops.ai_projection_field
    (projection_code, projection_version, field_path, data_classification)
SELECT 'SKU_GROWTH_PROFIT_DIAGNOSIS', 1, field_path, data_classification
  FROM (VALUES
    ('subject.subjectRef', 'OPAQUE_IDENTIFIER'),
    ('subject.platformCode', 'OPERATING_ATTRIBUTE'),
    ('subject.storeRef', 'OPAQUE_IDENTIFIER'),
    ('subject.lifecycleObjective', 'OPERATING_ATTRIBUTE'),
    ('subject.currencyCode', 'OPERATING_ATTRIBUTE'),
    ('window.windowCode', 'OPERATING_ATTRIBUTE'),
    ('window.periodStart', 'OPERATING_ATTRIBUTE'),
    ('window.periodEnd', 'OPERATING_ATTRIBUTE'),
    ('metrics.metricCode', 'CANONICAL_METRIC'),
    ('metrics.valueRef', 'OPAQUE_IDENTIFIER'),
    ('metrics.valueState', 'CANONICAL_METRIC'),
    ('metrics.numericValue', 'CANONICAL_METRIC'),
    ('metrics.currencyCode', 'CANONICAL_METRIC'),
    ('metrics.confidenceState', 'CANONICAL_METRIC'),
    ('metrics.freshnessSeconds', 'CANONICAL_METRIC'),
    ('metrics.definitionVersion', 'CANONICAL_METRIC'),
    ('findings.ruleCode', 'DETERMINISTIC_FINDING'),
    ('findings.outcome', 'DETERMINISTIC_FINDING'),
    ('findings.severity', 'DETERMINISTIC_FINDING'),
    ('findings.declineReason', 'DETERMINISTIC_FINDING'),
    ('guardrails.reasonCode', 'DETERMINISTIC_FINDING'),
    ('guardrails.outcome', 'DETERMINISTIC_FINDING')
  ) AS fields(field_path, data_classification);

-- ---------------------------------------------------------------------------
-- Invocation
-- ---------------------------------------------------------------------------

-- One call to a model, recorded whether or not it succeeded. The projection and
-- prompt versions are stored so an answer can be re-examined against exactly
-- what was sent, and request_digest identifies that payload without keeping it.
CREATE TABLE ops.ai_invocation (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    projection_code      text        NOT NULL,
    projection_version   integer     NOT NULL,
    prompt_template_code text        NOT NULL,
    prompt_version       integer     NOT NULL,
    model_id             uuid,
    subject_kind         text        NOT NULL,
    subject_id           uuid        NOT NULL,
    window_code          text        NOT NULL,
    request_digest       text        NOT NULL,
    state                text        NOT NULL,
    failure_code         text,
    degraded             boolean     NOT NULL,
    requested_by_user_id uuid,
    started_at           timestamptz NOT NULL,
    completed_at         timestamptz,
    latency_ms           integer,
    correlation_id       text        NOT NULL,
    CONSTRAINT ai_invocation_pk PRIMARY KEY (id),
    CONSTRAINT ai_invocation_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ai_invocation_projection_fk
        FOREIGN KEY (projection_code, projection_version)
        REFERENCES ops.ai_projection_definition (projection_code, projection_version),
    CONSTRAINT ai_invocation_model_fk FOREIGN KEY (model_id) REFERENCES ops.ai_model (id),
    CONSTRAINT ai_invocation_user_fk
        FOREIGN KEY (requested_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT ai_invocation_subject_ck
        CHECK (subject_kind IN ('PRODUCT_VARIANT', 'PLATFORM_LISTING_VARIANT', 'STORE')),
    CONSTRAINT ai_invocation_window_ck CHECK (window_code IN ('D7', 'D14', 'D30')),
    CONSTRAINT ai_invocation_digest_ck CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ai_invocation_state_ck
        CHECK (state IN ('PREPARED', 'DISPATCHED', 'SUCCEEDED',
                         'OUTPUT_REJECTED', 'PROVIDER_FAILED', 'REFUSED')),
    CONSTRAINT ai_invocation_failure_ck
        CHECK (state NOT IN ('OUTPUT_REJECTED', 'PROVIDER_FAILED', 'REFUSED')
            OR failure_code IS NOT NULL),
    -- A refused call never reached a provider, so it cannot name a model. Every
    -- call that was dispatched must name the model it was dispatched to.
    CONSTRAINT ai_invocation_model_presence_ck
        CHECK (CASE WHEN state IN ('PREPARED', 'REFUSED') THEN model_id IS NULL
                    ELSE model_id IS NOT NULL END),
    CONSTRAINT ai_invocation_latency_ck CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ai_invocation_completion_ck
        CHECK ((state IN ('PREPARED', 'DISPATCHED')) = (completed_at IS NULL))
);

CREATE INDEX ai_invocation_subject_ix
    ON ops.ai_invocation (subject_kind, subject_id, started_at DESC);
CREATE INDEX ai_invocation_state_ix
    ON ops.ai_invocation (organization_id, state, started_at DESC);

-- ---------------------------------------------------------------------------
-- Structured output
-- ---------------------------------------------------------------------------

-- One statement from a model, classified by what kind of statement it is. The
-- four kinds are stored separately rather than as prose so that a reader, and
-- every downstream gate, can tell a restated canonical fact from a hypothesis
-- and from a proposal.
CREATE TABLE ops.ai_output_claim (
    id               uuid    NOT NULL,
    invocation_id    uuid    NOT NULL,
    ordinal          integer NOT NULL,
    claim_kind       text    NOT NULL,
    statement        text    NOT NULL,
    payload          jsonb   NOT NULL,
    confidence_label text,
    validation_state text    NOT NULL,
    rejection_code   text,
    CONSTRAINT ai_output_claim_pk PRIMARY KEY (id),
    CONSTRAINT ai_output_claim_invocation_fk
        FOREIGN KEY (invocation_id) REFERENCES ops.ai_invocation (id),
    CONSTRAINT ai_output_claim_ordinal_uq UNIQUE (invocation_id, claim_kind, ordinal),
    CONSTRAINT ai_output_claim_ordinal_ck CHECK (ordinal > 0),
    CONSTRAINT ai_output_claim_kind_ck
        CHECK (claim_kind IN ('FACT', 'INFERENCE', 'RECOMMENDATION', 'UNKNOWN')),
    CONSTRAINT ai_output_claim_statement_ck
        CHECK (length(btrim(statement)) BETWEEN 1 AND 2000),
    CONSTRAINT ai_output_claim_payload_ck CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ai_output_claim_confidence_ck
        CHECK (confidence_label IS NULL
            OR confidence_label IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ai_output_claim_validation_ck
        CHECK (validation_state IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT ai_output_claim_rejection_ck
        CHECK ((validation_state = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT ai_output_claim_rejection_values_ck
        CHECK (rejection_code IS NULL
            OR rejection_code IN (
                'SCHEMA_INVALID', 'UNKNOWN_FIELD', 'EVIDENCE_REFERENCE_UNRESOLVED',
                'EVIDENCE_REFERENCE_MISSING', 'METRIC_NOT_RECOGNISED',
                'DERIVED_CALCULATION_NOT_PRODUCTIZED', 'CAPABILITY_NOT_RECOGNISED',
                'STATEMENT_TOO_LONG', 'INSTRUCTION_LIKE_CONTENT'))
);

CREATE INDEX ai_output_claim_invocation_ix
    ON ops.ai_output_claim (invocation_id, claim_kind, ordinal);

-- The canonical values a claim cites. The foreign key is the guarantee: a model
-- that invents a metric value identifier cannot have that citation stored, so
-- an ungrounded fact is refused at the boundary rather than displayed with a
-- reference nobody can open.
CREATE TABLE ops.ai_claim_evidence (
    id              uuid NOT NULL,
    claim_id        uuid NOT NULL,
    metric_value_id uuid,
    finding_id      uuid,
    CONSTRAINT ai_claim_evidence_pk PRIMARY KEY (id),
    CONSTRAINT ai_claim_evidence_claim_fk
        FOREIGN KEY (claim_id) REFERENCES ops.ai_output_claim (id),
    CONSTRAINT ai_claim_evidence_metric_fk
        FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value (id),
    CONSTRAINT ai_claim_evidence_finding_fk
        FOREIGN KEY (finding_id) REFERENCES mart.diagnosis_finding (id),
    CONSTRAINT ai_claim_evidence_one_target_ck
        CHECK (num_nonnulls(metric_value_id, finding_id) = 1),
    CONSTRAINT ai_claim_evidence_uq UNIQUE (claim_id, metric_value_id, finding_id)
);

CREATE INDEX ai_claim_evidence_claim_ix ON ops.ai_claim_evidence (claim_id);

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- The model gateway is a separate outbound boundary with its own eligibility
-- registry and its own credential resolution. It is not a marketplace
-- acquisition, so it neither consumes nor invalidates a call authority.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'ai_provider', 'NO_ROUTE', NULL,
        'model provider eligibility; not a marketplace acquisition control fact'),
    ('ops', 'ai_model', 'NO_ROUTE', NULL,
        'model registry; not a marketplace acquisition control fact'),
    ('ops', 'ai_projection_definition', 'NO_ROUTE', NULL,
        'projection contract; no acquisition authority reads it'),
    ('ops', 'ai_projection_field', 'NO_ROUTE', NULL,
        'field allowlist; no acquisition authority reads it'),
    ('ops', 'ai_invocation', 'NO_ROUTE', NULL,
        'model call state; no marketplace call is authorised from it'),
    ('ops', 'ai_output_claim', 'NO_ROUTE', NULL,
        'append-only model output; no acquisition authority reads it'),
    ('ops', 'ai_claim_evidence', 'NO_ROUTE', NULL,
        'append-only evidence link; no acquisition authority reads it');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Projection definitions and their field allowlist are read-only to the
-- application: what may leave this system cannot be widened by a running
-- process. Provider and model registries accept evidence-aware maintenance.
-- Invocations carry state; claims and their evidence are append-only.
GRANT SELECT ON ops.ai_projection_definition TO marketops_app;
GRANT SELECT ON ops.ai_projection_field TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ai_provider TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ai_model TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON ops.ai_invocation TO marketops_app;
GRANT SELECT, INSERT ON ops.ai_output_claim TO marketops_app;
GRANT SELECT, INSERT ON ops.ai_claim_evidence TO marketops_app;
