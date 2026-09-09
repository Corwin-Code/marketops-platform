-- SLICE-V1-004: the facts a listing conversion decision rests on, and the
-- Listing Health projection that reads them.
--
-- Every fact here is an observation with provenance, a source time and an
-- acquisition time kept apart. Nothing is inferred across the management side
-- and the customer side: what the platform holds as the description and what a
-- customer was shown are two observations, each with its own evidence grade.
-- Visits are individual, de-duplicable rows, so retained-visit conversion can be
-- computed as a set ratio rather than read from a platform label; an official
-- aggregate is readable, and it qualifies as evidence only under a summary
-- equivalence profile the Owner has published as proven.
--
-- Forward-only: new tables only.

-- ---------------------------------------------------------------------------
-- The complete affected set of a listing
-- ---------------------------------------------------------------------------

CREATE TABLE core.lc_affected_set (
    id                           uuid        NOT NULL,
    organization_id              uuid        NOT NULL,
    platform_listing_id          uuid        NOT NULL,
    affected_set_digest          text        NOT NULL,
    platform_listing_variant_ids uuid[]      NOT NULL,
    product_variant_ids          uuid[]      NOT NULL,
    resolution_state             text        NOT NULL,
    unresolved_reason_codes      text[]      NOT NULL DEFAULT '{}',
    resolved_at                  timestamptz NOT NULL,
    created_at                   timestamptz NOT NULL,
    CONSTRAINT lc_affected_set_pk PRIMARY KEY (id),
    CONSTRAINT lc_affected_set_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_affected_set_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_affected_set_digest_uq UNIQUE (platform_listing_id, affected_set_digest),
    CONSTRAINT lc_affected_set_digest_ck CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT lc_affected_set_resolution_ck
        CHECK (resolution_state IN ('COMPLETE', 'INCOMPLETE', 'CONFLICTED')),
    CONSTRAINT lc_affected_set_listing_variants_ck
        CHECK (cardinality(platform_listing_variant_ids) BETWEEN 0 AND 4096
            AND array_position(platform_listing_variant_ids, NULL) IS NULL),
    CONSTRAINT lc_affected_set_product_variants_ck
        CHECK (cardinality(product_variant_ids) BETWEEN 0 AND 4096
            AND array_position(product_variant_ids, NULL) IS NULL),
    CONSTRAINT lc_affected_set_reasons_ck
        CHECK (cardinality(unresolved_reason_codes) BETWEEN 0 AND 32
            AND array_position(unresolved_reason_codes, NULL) IS NULL),
    -- A complete set names at least one variant and leaves nothing unexplained;
    -- an incomplete or conflicted one says why, so "we do not know" and "we
    -- forgot" are different states.
    CONSTRAINT lc_affected_set_complete_ck
        CHECK (resolution_state <> 'COMPLETE'
            OR (cardinality(platform_listing_variant_ids) >= 1
                AND cardinality(unresolved_reason_codes) = 0)),
    CONSTRAINT lc_affected_set_incomplete_ck
        CHECK (resolution_state = 'COMPLETE' OR cardinality(unresolved_reason_codes) >= 1)
);

CREATE INDEX lc_affected_set_listing_ix
    ON core.lc_affected_set (platform_listing_id, resolved_at DESC);

-- The digest of what the platform currently says a listing consists of: every
-- observed variant, by identity and native key, in a canonical order. A UI
-- selection cannot narrow it because it is never computed from a selection.
CREATE FUNCTION core.lc_listing_affected_set_digest(p_listing uuid)
RETURNS text
LANGUAGE sql STABLE
SET search_path = pg_catalog, core, pg_temp
AS $$
    SELECT encode(sha256(convert_to(
        coalesce((SELECT string_agg(v.id::text || ':' || v.native_variant_key, chr(31)
                                    ORDER BY v.id)
                    FROM core.platform_listing_variant v
                   WHERE v.platform_listing_id = p_listing AND v.status = 'OBSERVED'), ''),
        'UTF8')), 'hex')
$$;
REVOKE ALL ON FUNCTION core.lc_listing_affected_set_digest(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_listing_affected_set_digest(uuid) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Description facts: management side and customer side, separately
-- ---------------------------------------------------------------------------

CREATE TABLE core.lc_description_observation (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    provenance_id       uuid        NOT NULL,
    platform_listing_id uuid        NOT NULL,
    source_fact_key     text        NOT NULL,
    observed_at         timestamptz NOT NULL,
    acquired_at         timestamptz NOT NULL,
    description_text    text        NOT NULL,
    text_digest         text        NOT NULL,
    language_code       text        NOT NULL,
    kiz_marked_declared boolean,
    native_version_token text,
    supersedes_fact_id  uuid,
    CONSTRAINT lc_description_observation_pk PRIMARY KEY (id),
    CONSTRAINT lc_description_observation_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_description_observation_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT lc_description_observation_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_description_observation_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES core.lc_description_observation (id),
    CONSTRAINT lc_description_observation_source_key_uq
        UNIQUE (organization_id, source_fact_key),
    CONSTRAINT lc_description_observation_digest_ck
        CHECK (text_digest = encode(sha256(convert_to(description_text, 'UTF8')), 'hex')),
    CONSTRAINT lc_description_observation_language_ck
        CHECK (language_code ~ '^[a-z]{2}(-[A-Z]{2})?$'),
    CONSTRAINT lc_description_observation_length_ck
        CHECK (length(description_text) <= 65536),
    -- A new acquisition never refreshes an old source time.
    CONSTRAINT lc_description_observation_times_ck CHECK (acquired_at >= observed_at),
    CONSTRAINT lc_description_observation_token_ck
        CHECK (native_version_token IS NULL OR length(btrim(native_version_token)) BETWEEN 1 AND 256)
);

CREATE INDEX lc_description_observation_listing_ix
    ON core.lc_description_observation (platform_listing_id, observed_at DESC);

CREATE TABLE core.lc_display_observation (
    id                    uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    provenance_id         uuid        NOT NULL,
    platform_listing_id   uuid        NOT NULL,
    source_fact_key       text        NOT NULL,
    observed_at           timestamptz NOT NULL,
    acquired_at           timestamptz NOT NULL,
    evidence_grade        text        NOT NULL,
    observer_user_id      uuid,
    display_state         text        NOT NULL,
    displayed_text_digest text,
    displayed_text        text,
    evidence_reference    text        NOT NULL,
    CONSTRAINT lc_display_observation_pk PRIMARY KEY (id),
    CONSTRAINT lc_display_observation_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_display_observation_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT lc_display_observation_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_display_observation_observer_fk
        FOREIGN KEY (observer_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT lc_display_observation_source_key_uq UNIQUE (organization_id, source_fact_key),
    CONSTRAINT lc_display_observation_grade_ck
        CHECK (evidence_grade IN ('OFFICIAL_EVIDENCE', 'INDEPENDENT_HUMAN')),
    -- A human observation names the human. Official evidence names its
    -- reference and no person, because nobody's word is what it rests on.
    CONSTRAINT lc_display_observation_observer_ck
        CHECK ((evidence_grade = 'INDEPENDENT_HUMAN') = (observer_user_id IS NOT NULL)),
    CONSTRAINT lc_display_observation_state_ck
        CHECK (display_state IN ('DISPLAYED', 'NOT_DISPLAYED', 'UNKNOWN')),
    CONSTRAINT lc_display_observation_digest_ck
        CHECK ((displayed_text IS NULL AND displayed_text_digest IS NULL)
            OR displayed_text_digest = encode(sha256(convert_to(displayed_text, 'UTF8')), 'hex')),
    CONSTRAINT lc_display_observation_displayed_ck
        CHECK (display_state <> 'DISPLAYED' OR displayed_text_digest IS NOT NULL),
    CONSTRAINT lc_display_observation_times_ck CHECK (acquired_at >= observed_at),
    CONSTRAINT lc_display_observation_reference_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512)
);

CREATE INDEX lc_display_observation_listing_ix
    ON core.lc_display_observation (platform_listing_id, observed_at DESC);

-- ---------------------------------------------------------------------------
-- Visits and their retained purchases
-- ---------------------------------------------------------------------------

CREATE TABLE core.lc_visit_fact (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    provenance_id               uuid        NOT NULL,
    store_id                    uuid        NOT NULL,
    platform_listing_id         uuid        NOT NULL,
    platform_listing_variant_id uuid,
    source_fact_key             text        NOT NULL,
    visit_key                   text        NOT NULL,
    visited_at                  timestamptz NOT NULL,
    acquired_at                 timestamptz NOT NULL,
    sellable_at_visit           text        NOT NULL,
    source_channel              text        NOT NULL,
    key_group_code              text,
    supersedes_fact_id          uuid,
    CONSTRAINT lc_visit_fact_pk PRIMARY KEY (id),
    CONSTRAINT lc_visit_fact_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_visit_fact_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT lc_visit_fact_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_visit_fact_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_visit_fact_variant_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT lc_visit_fact_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES core.lc_visit_fact (id),
    CONSTRAINT lc_visit_fact_source_key_uq UNIQUE (organization_id, source_fact_key),
    -- One visit is one row. A visit that appears twice in a source is the same
    -- visit, which is what makes the denominator a set.
    CONSTRAINT lc_visit_fact_visit_key_uq UNIQUE (platform_listing_id, visit_key),
    CONSTRAINT lc_visit_fact_visit_key_ck CHECK (length(btrim(visit_key)) BETWEEN 1 AND 128),
    CONSTRAINT lc_visit_fact_sellable_ck CHECK (sellable_at_visit IN ('YES', 'NO', 'UNKNOWN')),
    CONSTRAINT lc_visit_fact_channel_ck
        CHECK (source_channel IN ('ADVERTISING', 'ORGANIC', 'UNKNOWN')),
    CONSTRAINT lc_visit_fact_key_group_ck
        CHECK (key_group_code IS NULL OR key_group_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT lc_visit_fact_times_ck CHECK (acquired_at >= visited_at)
);

CREATE INDEX lc_visit_fact_listing_ix ON core.lc_visit_fact (platform_listing_id, visited_at);

CREATE TABLE core.lc_visit_purchase_link (
    id             uuid        NOT NULL,
    organization_id uuid       NOT NULL,
    provenance_id  uuid        NOT NULL,
    visit_fact_id  uuid        NOT NULL,
    sales_fact_id  uuid        NOT NULL,
    link_basis     text        NOT NULL,
    linked_at      timestamptz NOT NULL,
    CONSTRAINT lc_visit_purchase_link_pk PRIMARY KEY (id),
    CONSTRAINT lc_visit_purchase_link_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT lc_visit_purchase_link_visit_fk
        FOREIGN KEY (visit_fact_id, organization_id)
        REFERENCES core.lc_visit_fact (id, organization_id),
    CONSTRAINT lc_visit_purchase_link_sale_fk
        FOREIGN KEY (sales_fact_id) REFERENCES ledger.sales_fact (id),
    CONSTRAINT lc_visit_purchase_link_uq UNIQUE (visit_fact_id, sales_fact_id),
    CONSTRAINT lc_visit_purchase_link_basis_ck
        CHECK (link_basis IN ('OFFICIAL_ATTRIBUTION', 'NATIVE_ORDER_KEY', 'MANUAL_ENTRY'))
);

CREATE INDEX lc_visit_purchase_link_sale_ix ON core.lc_visit_purchase_link (sales_fact_id);

-- ---------------------------------------------------------------------------
-- Official aggregates and the proof they are equivalent to the detail definition
-- ---------------------------------------------------------------------------

-- Owner-published. A profile that is PROVEN carries evidence that the
-- platform's aggregate counts the same numerator, the same denominator, with
-- the same time attribution, the same maturity and the same revision behaviour
-- as the detail definition. Anything less leaves the aggregate readable and
-- unqualified. No Java writes this table.
CREATE TABLE core.lc_summary_equivalence_profile (
    id                       uuid        NOT NULL,
    organization_id          uuid        NOT NULL,
    platform_code            text        NOT NULL,
    summary_kind             text        NOT NULL,
    profile_version          integer     NOT NULL,
    proof_state              text        NOT NULL,
    covers_numerator         boolean     NOT NULL,
    covers_denominator       boolean     NOT NULL,
    covers_time_attribution  boolean     NOT NULL,
    covers_maturity          boolean     NOT NULL,
    covers_revision          boolean     NOT NULL,
    evidence_reference       text,
    published_by_user_id     uuid        NOT NULL,
    published_at             timestamptz NOT NULL,
    effective_from           timestamptz NOT NULL,
    effective_to             timestamptz,
    status                   text        NOT NULL,
    CONSTRAINT lc_summary_equivalence_profile_pk PRIMARY KEY (id),
    CONSTRAINT lc_summary_equivalence_profile_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_summary_equivalence_profile_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT lc_summary_equivalence_profile_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT lc_summary_equivalence_profile_publisher_fk
        FOREIGN KEY (published_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_summary_equivalence_profile_version_uq
        UNIQUE (organization_id, platform_code, summary_kind, profile_version),
    CONSTRAINT lc_summary_equivalence_profile_kind_ck
        CHECK (summary_kind ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT lc_summary_equivalence_profile_version_ck CHECK (profile_version >= 1),
    CONSTRAINT lc_summary_equivalence_profile_proof_ck
        CHECK (proof_state IN ('PROVEN', 'UNPROVEN', 'REFUTED')),
    -- Proven means all five, with evidence. A partial proof is unproven.
    CONSTRAINT lc_summary_equivalence_profile_proven_ck
        CHECK (proof_state <> 'PROVEN'
            OR (covers_numerator AND covers_denominator AND covers_time_attribution
                AND covers_maturity AND covers_revision AND evidence_reference IS NOT NULL)),
    CONSTRAINT lc_summary_equivalence_profile_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT lc_summary_equivalence_profile_range_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT lc_summary_equivalence_profile_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            platform_code WITH =,
            summary_kind WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE TABLE core.lc_official_summary_observation (
    id                        uuid        NOT NULL,
    organization_id           uuid        NOT NULL,
    provenance_id             uuid        NOT NULL,
    store_id                  uuid        NOT NULL,
    platform_listing_id       uuid        NOT NULL,
    source_fact_key           text        NOT NULL,
    summary_kind              text        NOT NULL,
    period_start              timestamptz NOT NULL,
    period_end                timestamptz NOT NULL,
    reported_visits           bigint,
    reported_retained_purchases bigint,
    reported_conversion_label text,
    observed_at               timestamptz NOT NULL,
    acquired_at               timestamptz NOT NULL,
    supersedes_fact_id        uuid,
    CONSTRAINT lc_official_summary_observation_pk PRIMARY KEY (id),
    CONSTRAINT lc_official_summary_observation_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT lc_official_summary_observation_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_official_summary_observation_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_official_summary_observation_supersedes_fk
        FOREIGN KEY (supersedes_fact_id) REFERENCES core.lc_official_summary_observation (id),
    CONSTRAINT lc_official_summary_observation_source_key_uq
        UNIQUE (organization_id, source_fact_key),
    CONSTRAINT lc_official_summary_observation_kind_ck
        CHECK (summary_kind ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT lc_official_summary_observation_period_ck CHECK (period_start < period_end),
    CONSTRAINT lc_official_summary_observation_counts_ck
        CHECK ((reported_visits IS NULL OR reported_visits >= 0)
            AND (reported_retained_purchases IS NULL OR reported_retained_purchases >= 0)),
    -- The platform's label is kept as a label. It is text on purpose: nothing
    -- can divide by it, and nothing can mistake it for the numerator.
    CONSTRAINT lc_official_summary_observation_label_ck
        CHECK (reported_conversion_label IS NULL
            OR length(btrim(reported_conversion_label)) BETWEEN 1 AND 128),
    CONSTRAINT lc_official_summary_observation_times_ck CHECK (acquired_at >= observed_at)
);

CREATE INDEX lc_official_summary_observation_listing_ix
    ON core.lc_official_summary_observation (platform_listing_id, period_start DESC);

-- ---------------------------------------------------------------------------
-- Retained-visit conversion measurements
-- ---------------------------------------------------------------------------

-- One measurement per listing, window, evidence path and definition version.
-- The ratio is a set ratio. A visit with several retained purchases counts once
-- in the numerator, the numerator is bounded by the denominator, and a zero
-- denominator is UNDEFINED rather than zero.
CREATE TABLE mart.lc_conversion_measurement (
    id                        uuid           NOT NULL,
    organization_id           uuid           NOT NULL,
    store_id                  uuid           NOT NULL,
    platform_listing_id       uuid           NOT NULL,
    calculation_run_id        uuid           NOT NULL,
    definition_version        integer        NOT NULL,
    window_start              timestamptz    NOT NULL,
    window_end                timestamptz    NOT NULL,
    retention_window_days     integer        NOT NULL,
    evidence_path             text           NOT NULL,
    path_qualified            boolean        NOT NULL,
    qualification_reason_codes text[]        NOT NULL DEFAULT '{}',
    visit_count               bigint,
    retained_purchase_visit_count bigint,
    primary_ratio             numeric(9, 6),
    ratio_state               text           NOT NULL,
    maturity_reached          boolean        NOT NULL,
    source_stratified         boolean        NOT NULL,
    sellable_split            jsonb          NOT NULL DEFAULT '{}'::jsonb,
    excluded_transition_days  date[]         NOT NULL DEFAULT '{}',
    source_time               timestamptz,
    acquisition_time          timestamptz,
    computed_at               timestamptz    NOT NULL,
    CONSTRAINT lc_conversion_measurement_pk PRIMARY KEY (id),
    CONSTRAINT lc_conversion_measurement_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_conversion_measurement_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_conversion_measurement_run_fk
        FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run (id),
    CONSTRAINT lc_conversion_measurement_version_ck CHECK (definition_version >= 1),
    CONSTRAINT lc_conversion_measurement_window_ck CHECK (window_start < window_end),
    CONSTRAINT lc_conversion_measurement_retention_ck CHECK (retention_window_days IN (7, 14, 30)),
    CONSTRAINT lc_conversion_measurement_path_ck
        CHECK (evidence_path IN ('DETAIL', 'OFFICIAL_SUMMARY')),
    CONSTRAINT lc_conversion_measurement_counts_ck
        CHECK ((visit_count IS NULL OR visit_count >= 0)
            AND (retained_purchase_visit_count IS NULL OR retained_purchase_visit_count >= 0)
            AND (visit_count IS NULL OR retained_purchase_visit_count IS NULL
                 OR retained_purchase_visit_count <= visit_count)),
    CONSTRAINT lc_conversion_measurement_state_ck
        CHECK (ratio_state IN ('DEFINED', 'UNDEFINED', 'NOT_AVAILABLE')),
    CONSTRAINT lc_conversion_measurement_ratio_ck
        CHECK ((ratio_state = 'DEFINED') = (primary_ratio IS NOT NULL)),
    CONSTRAINT lc_conversion_measurement_ratio_bounds_ck
        CHECK (primary_ratio IS NULL OR (primary_ratio >= 0 AND primary_ratio <= 1)),
    CONSTRAINT lc_conversion_measurement_defined_ck
        CHECK (ratio_state <> 'DEFINED'
            OR (visit_count > 0 AND retained_purchase_visit_count IS NOT NULL
                AND path_qualified AND maturity_reached)),
    CONSTRAINT lc_conversion_measurement_undefined_ck
        CHECK (ratio_state <> 'UNDEFINED' OR visit_count = 0),
    CONSTRAINT lc_conversion_measurement_split_ck CHECK (jsonb_typeof(sellable_split) = 'object'),
    CONSTRAINT lc_conversion_measurement_reasons_ck
        CHECK (path_qualified = (cardinality(qualification_reason_codes) = 0))
);

CREATE INDEX lc_conversion_measurement_listing_ix
    ON mart.lc_conversion_measurement (platform_listing_id, window_start DESC, definition_version DESC);

-- ---------------------------------------------------------------------------
-- Listing Health: three layers and no score
-- ---------------------------------------------------------------------------

CREATE TABLE mart.lc_listing_health (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    store_id             uuid        NOT NULL,
    platform_listing_id  uuid        NOT NULL,
    calculation_run_id   uuid        NOT NULL,
    affected_set_id      uuid        NOT NULL,
    health_version       integer     NOT NULL,
    necessary_conditions jsonb       NOT NULL,
    necessary_state      text        NOT NULL,
    eligibility          jsonb       NOT NULL,
    opportunities        jsonb       NOT NULL,
    definition_digest    text        NOT NULL,
    source_time          timestamptz,
    acquisition_time     timestamptz,
    computed_at          timestamptz NOT NULL,
    CONSTRAINT lc_listing_health_pk PRIMARY KEY (id),
    CONSTRAINT lc_listing_health_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_listing_health_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_listing_health_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_listing_health_run_fk
        FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run (id),
    CONSTRAINT lc_listing_health_affected_set_fk
        FOREIGN KEY (affected_set_id, organization_id)
        REFERENCES core.lc_affected_set (id, organization_id),
    CONSTRAINT lc_listing_health_version_uq UNIQUE (platform_listing_id, health_version),
    CONSTRAINT lc_listing_health_version_ck CHECK (health_version >= 1),
    CONSTRAINT lc_listing_health_conditions_ck CHECK (jsonb_typeof(necessary_conditions) = 'array'),
    -- Unknown is neither healthy nor unhealthy. A hard problem fails the layer
    -- on its own; nothing in another layer offsets it.
    CONSTRAINT lc_listing_health_necessary_ck
        CHECK (necessary_state IN ('PASS', 'FAIL', 'UNKNOWN')),
    CONSTRAINT lc_listing_health_eligibility_ck
        CHECK (jsonb_typeof(eligibility) = 'object'
            AND eligibility ? 'MEASUREMENT' AND eligibility ? 'PROTECTION' AND eligibility ? 'EVALUATION'
            AND (eligibility ->> 'MEASUREMENT') IN ('ELIGIBLE', 'INELIGIBLE', 'UNKNOWN')
            AND (eligibility ->> 'PROTECTION') IN ('ELIGIBLE', 'INELIGIBLE', 'UNKNOWN')
            AND (eligibility ->> 'EVALUATION') IN ('ELIGIBLE', 'INELIGIBLE', 'UNKNOWN')),
    CONSTRAINT lc_listing_health_opportunities_ck CHECK (jsonb_typeof(opportunities) = 'array'),
    CONSTRAINT lc_listing_health_digest_ck CHECK (definition_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX lc_listing_health_queue_ix
    ON mart.lc_listing_health (store_id, necessary_state, computed_at DESC);

-- Customer feedback themes from official evidence, read by Listing Health as an
-- opportunity input. A theme is a count with provenance, never a verdict.
CREATE TABLE mart.lc_feedback_theme (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    provenance_id       uuid        NOT NULL,
    platform_listing_id uuid        NOT NULL,
    source_fact_key     text        NOT NULL,
    period_start        timestamptz NOT NULL,
    period_end          timestamptz NOT NULL,
    theme_code          text        NOT NULL,
    mention_count       integer     NOT NULL,
    evidence_grade      text        NOT NULL,
    observed_at         timestamptz NOT NULL,
    acquired_at         timestamptz NOT NULL,
    CONSTRAINT lc_feedback_theme_pk PRIMARY KEY (id),
    CONSTRAINT lc_feedback_theme_provenance_fk
        FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance (id),
    CONSTRAINT lc_feedback_theme_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_feedback_theme_source_key_uq UNIQUE (organization_id, source_fact_key),
    CONSTRAINT lc_feedback_theme_period_ck CHECK (period_start < period_end),
    CONSTRAINT lc_feedback_theme_code_ck CHECK (theme_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT lc_feedback_theme_count_ck CHECK (mention_count >= 0),
    CONSTRAINT lc_feedback_theme_grade_ck
        CHECK (evidence_grade IN ('OFFICIAL_EVIDENCE', 'INDEPENDENT_HUMAN')),
    CONSTRAINT lc_feedback_theme_times_ck CHECK (acquired_at >= observed_at)
);

CREATE INDEX lc_feedback_theme_listing_ix
    ON mart.lc_feedback_theme (platform_listing_id, period_start DESC);

-- Cross-domain collaboration reuses the workflow Task. This row records the
-- handover, the returned evidence or the dependency re-evaluation against that
-- Task; it writes no inventory or finance fact.
CREATE TABLE ops.lc_collaboration_link (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    platform_listing_id uuid        NOT NULL,
    task_id             uuid        NOT NULL,
    link_kind           text        NOT NULL,
    target_domain       text        NOT NULL,
    evidence_reference  text        NOT NULL,
    recorded_by_user_id uuid        NOT NULL,
    source_time         timestamptz,
    recorded_at         timestamptz NOT NULL,
    CONSTRAINT lc_collaboration_link_pk PRIMARY KEY (id),
    CONSTRAINT lc_collaboration_link_listing_fk
        FOREIGN KEY (platform_listing_id, organization_id)
        REFERENCES core.platform_listing (id, organization_id),
    CONSTRAINT lc_collaboration_link_task_fk FOREIGN KEY (task_id) REFERENCES ops.work_task (id),
    CONSTRAINT lc_collaboration_link_user_fk
        FOREIGN KEY (recorded_by_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT lc_collaboration_link_kind_ck
        CHECK (link_kind IN ('HANDOVER', 'EVIDENCE_RETURN', 'DEPENDENCY_REEVALUATION')),
    CONSTRAINT lc_collaboration_link_domain_ck
        CHECK (target_domain IN ('INVENTORY', 'FINANCE', 'PRODUCT', 'ADVERTISING', 'CONTENT')),
    CONSTRAINT lc_collaboration_link_reference_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512)
);

CREATE INDEX lc_collaboration_link_task_ix ON ops.lc_collaboration_link (task_id, recorded_at);

-- ---------------------------------------------------------------------------
-- Route inventory and privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'lc_affected_set', 'NO_ROUTE', NULL,
        'frozen complete affected set of a listing; written by the listing conversion module'),
    ('core', 'lc_description_observation', 'NO_ROUTE', NULL,
        'management-side description fact with provenance; append-only'),
    ('core', 'lc_display_observation', 'NO_ROUTE', NULL,
        'customer-side display fact with evidence grade; append-only'),
    ('core', 'lc_visit_fact', 'NO_ROUTE', NULL,
        'one qualified visit; the denominator of retained-visit conversion'),
    ('core', 'lc_visit_purchase_link', 'NO_ROUTE', NULL,
        'visit to sales fact link with its basis; append-only'),
    ('core', 'lc_summary_equivalence_profile', 'NO_ROUTE', NULL,
        'owner-published proof that an official aggregate matches the detail definition'),
    ('core', 'lc_official_summary_observation', 'NO_ROUTE', NULL,
        'official aggregate for a period; readable, qualified only under a proven profile'),
    ('mart', 'lc_conversion_measurement', 'NO_ROUTE', NULL,
        'retained-visit conversion set ratio per listing, window and evidence path'),
    ('mart', 'lc_listing_health', 'NO_ROUTE', NULL,
        'Listing Health projection in three layers with no total score'),
    ('mart', 'lc_feedback_theme', 'NO_ROUTE', NULL,
        'customer feedback theme counts from official evidence'),
    ('ops', 'lc_collaboration_link', 'NO_ROUTE', NULL,
        'cross-domain handover, evidence return and dependency re-evaluation against a Task');

GRANT SELECT, INSERT ON core.lc_affected_set TO marketops_app;
GRANT SELECT, INSERT ON core.lc_description_observation TO marketops_app;
GRANT SELECT, INSERT ON core.lc_display_observation TO marketops_app;
GRANT SELECT, INSERT ON core.lc_visit_fact TO marketops_app;
GRANT SELECT, INSERT ON core.lc_visit_purchase_link TO marketops_app;
GRANT SELECT ON core.lc_summary_equivalence_profile TO marketops_app;
GRANT SELECT, INSERT ON core.lc_official_summary_observation TO marketops_app;
GRANT SELECT, INSERT ON mart.lc_conversion_measurement TO marketops_app;
GRANT SELECT, INSERT ON mart.lc_listing_health TO marketops_app;
GRANT SELECT, INSERT ON mart.lc_feedback_theme TO marketops_app;
GRANT SELECT, INSERT ON ops.lc_collaboration_link TO marketops_app;
