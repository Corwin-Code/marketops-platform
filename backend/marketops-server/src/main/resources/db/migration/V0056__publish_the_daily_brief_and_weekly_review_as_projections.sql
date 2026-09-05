-- The daily brief and the weekly review, as projections that decide nothing.
--
-- Both are reports about work that already exists. They read canonical Cases,
-- Tasks, Metrics and Outcomes and link to them by identity; they raise no Task,
-- record no Approval, compute no Metric and grant no authority. The schema is
-- what makes that true rather than a convention somebody could drift from: every
-- item row points at exactly one canonical row by foreign key, and there is no
-- column here in which a brief could hold an opinion of its own.
--
-- Three properties the tables enforce.
--
-- A publication is a permanent record. It is written once, never updated and
-- never deleted, and a trigger refuses the attempt. Late facts do not correct a
-- published report; they produce a further publication that names the one it
-- supersedes, keeps its own `as_of`, its own source cutoff, its own calculation
-- and bundle versions, and says which facts arrived late. A reader can therefore
-- see both what was believed on the day and what is believed now, which is the
-- only way a decision taken on the earlier reading can be understood afterwards.
--
-- A section is emitted even when it is empty. A brief that silently omitted the
-- topics it had nothing to say about would be indistinguishable from one that
-- never looked, so a section carries a coverage state and, where it is not
-- complete, the codes explaining why. `NOT_AVAILABLE` is a legitimate answer and
-- is not a zero.
--
-- The set of sections is closed. The Contract names ten daily topics and twelve
-- weekly ones; they are check constraints here rather than prose in a service,
-- so a brief cannot quietly stop covering one.
--
--   MO044  BRIEF_PUBLICATION_IMMUTABLE

-- ---------------------------------------------------------------------------
-- The reporting calendar
-- ---------------------------------------------------------------------------

-- What "each configured operating day" means for one organization.
--
-- Owner-published, and deliberately not a constant in a scheduler: an operating
-- day, a cut time and a timezone are business facts, and a product that hard
-- coded them would be reporting on a day nobody chose. There is no Java writer;
-- the application role may read it and retire a version, and nothing else.
CREATE TABLE core.ad_reporting_calendar (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    policy_version              integer     NOT NULL,
    scope_kind                  text        NOT NULL,
    platform_code               text,
    store_ref_id                uuid,
    reporting_timezone          text        NOT NULL,
    -- Local minute of the day at which the facts are cut. Not the render time:
    -- a report rendered late still describes the cut it names.
    daily_cut_minute            integer     NOT NULL,
    operating_days              smallint[]  NOT NULL,
    weekly_cut_weekday          smallint    NOT NULL,
    weekly_cut_minute           integer     NOT NULL,
    -- How long after a period a late fact may still produce a revision. Beyond
    -- it the published reading stands and the correction is a fresh period's.
    late_revision_horizon_hours integer     NOT NULL,
    owner_user_id               uuid        NOT NULL,
    reason                      text        NOT NULL,
    evidence_reference          text        NOT NULL,
    effective_from              timestamptz NOT NULL,
    effective_to                timestamptz,
    status                      text        NOT NULL,
    created_at                  timestamptz NOT NULL,
    CONSTRAINT ad_reporting_calendar_pk PRIMARY KEY (id),
    CONSTRAINT ad_reporting_calendar_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_reporting_calendar_version_uq UNIQUE (organization_id, policy_version),
    CONSTRAINT ad_reporting_calendar_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_reporting_calendar_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT ad_reporting_calendar_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT ad_reporting_calendar_store_fk
        FOREIGN KEY (store_ref_id, organization_id)
        REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_reporting_calendar_version_ck CHECK (policy_version >= 1),
    CONSTRAINT ad_reporting_calendar_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT ad_reporting_calendar_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION'
                AND platform_code IS NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'PLATFORM'
                AND platform_code IS NOT NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'STORE'
                AND platform_code IS NOT NULL AND store_ref_id IS NOT NULL)),
    CONSTRAINT ad_reporting_calendar_timezone_ck
        CHECK (reporting_timezone ~ '^[A-Za-z]+/[A-Za-z_+-]+$'),
    CONSTRAINT ad_reporting_calendar_cut_ck
        CHECK (daily_cut_minute BETWEEN 0 AND 1439
            AND weekly_cut_minute BETWEEN 0 AND 1439),
    -- ISO weekday numbering, at least one operating day, no duplicates and no
    -- holes. A calendar with no operating day would silently publish nothing.
    CONSTRAINT ad_reporting_calendar_weekday_ck
        CHECK (weekly_cut_weekday BETWEEN 1 AND 7),
    CONSTRAINT ad_reporting_calendar_operating_days_ck
        CHECK (cardinality(operating_days) BETWEEN 1 AND 7
            AND array_position(operating_days, NULL) IS NULL
            AND operating_days <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::smallint[]),
    CONSTRAINT ad_reporting_calendar_horizon_ck
        CHECK (late_revision_horizon_hours BETWEEN 1 AND 8760),
    CONSTRAINT ad_reporting_calendar_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_reporting_calendar_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_reporting_calendar_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT ad_reporting_calendar_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ad_reporting_calendar_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_kind WITH =,
            coalesce(platform_code, '') WITH =,
            coalesce(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

-- ---------------------------------------------------------------------------
-- The publication
-- ---------------------------------------------------------------------------

-- One published report, and the lineage of everything published before it.
--
-- Daily and weekly share this table because they obey the same lineage rules and
-- a reader asking "what did we believe on the fourth" should not have to know
-- which cadence answered.
CREATE TABLE ops.ad_brief_publication (
    id                            uuid        NOT NULL,
    organization_id               uuid        NOT NULL,
    brief_kind                    text        NOT NULL,
    -- The human period this is about, in the calendar's own timezone.
    period_key                    text        NOT NULL,
    period_starts_at              timestamptz NOT NULL,
    period_ends_at                timestamptz NOT NULL,
    -- The instant the facts were cut, which is not the instant it was rendered.
    as_of                         timestamptz NOT NULL,
    calendar_policy_id            uuid        NOT NULL,
    calendar_policy_version       integer     NOT NULL,
    -- The source cutoff, copied rather than referenced. The cursor moves; a
    -- publication that pointed at it would silently change what it claimed to
    -- have read.
    cursor_feed_code              text        NOT NULL,
    cursor_position_at            timestamptz NOT NULL,
    cursor_position_item_key      text        NOT NULL,
    max_calculation_id            uuid,
    reconciliation_run_id         uuid,
    policy_version_digest         text        NOT NULL,
    -- Which authority versions were in force, frozen onto the row for the same
    -- reason: a bundle retired tomorrow must not change what a report said.
    bundle_version_snapshot       jsonb       NOT NULL,
    gap_codes                     text[]      NOT NULL DEFAULT '{}',
    revision_no                   integer     NOT NULL DEFAULT 1,
    supersedes_publication_id     uuid,
    revision_kind                 text        NOT NULL,
    adjustment_reason             text,
    late_fact_reference           text,
    content_digest                text        NOT NULL,
    published_at                  timestamptz NOT NULL,
    correlation_id                text        NOT NULL,
    CONSTRAINT ad_brief_publication_pk PRIMARY KEY (id),
    CONSTRAINT ad_brief_publication_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ad_brief_publication_id_kind_uq UNIQUE (id, revision_kind),
    CONSTRAINT ad_brief_publication_revision_uq
        UNIQUE (organization_id, brief_kind, period_key, revision_no),
    CONSTRAINT ad_brief_publication_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT ad_brief_publication_calendar_fk
        FOREIGN KEY (calendar_policy_id, organization_id)
        REFERENCES core.ad_reporting_calendar (id, organization_id),
    CONSTRAINT ad_brief_publication_supersedes_fk
        FOREIGN KEY (supersedes_publication_id) REFERENCES ops.ad_brief_publication (id),
    CONSTRAINT ad_brief_publication_run_fk
        FOREIGN KEY (reconciliation_run_id) REFERENCES ops.ad_reconciliation_run (id),
    CONSTRAINT ad_brief_publication_kind_ck
        CHECK (brief_kind IN ('DAILY_ACTION_BRIEF', 'WEEKLY_EVIDENCE_REVIEW')),
    CONSTRAINT ad_brief_publication_revision_kind_ck
        CHECK (revision_kind IN ('ORIGINAL', 'REVISION', 'DELTA')),
    CONSTRAINT ad_brief_publication_revision_ck CHECK (revision_no >= 1),
    -- A revision is not a correction in place. It names what it supersedes, why,
    -- and which late fact caused it; an original names none of those.
    CONSTRAINT ad_brief_publication_revision_shape_ck
        CHECK ((revision_kind IN ('REVISION', 'DELTA'))
            = (supersedes_publication_id IS NOT NULL
                AND revision_no > 1
                AND adjustment_reason IS NOT NULL
                AND late_fact_reference IS NOT NULL)),
    CONSTRAINT ad_brief_publication_first_revision_ck
        CHECK (revision_kind <> 'ORIGINAL' OR revision_no = 1),
    CONSTRAINT ad_brief_publication_period_ck CHECK (period_ends_at > period_starts_at),
    -- The cut is never after the publication. A report claiming facts from after
    -- it was written would be describing a future it could not have read.
    CONSTRAINT ad_brief_publication_as_of_ck CHECK (as_of <= published_at),
    CONSTRAINT ad_brief_publication_period_key_ck
        CHECK (length(btrim(period_key)) BETWEEN 1 AND 32),
    CONSTRAINT ad_brief_publication_cursor_feed_ck
        CHECK (cursor_feed_code IN ('ADVERTISING_ACCEPTED_FACT')),
    CONSTRAINT ad_brief_publication_cursor_item_ck
        CHECK (length(btrim(cursor_position_item_key)) BETWEEN 1 AND 256),
    CONSTRAINT ad_brief_publication_digest_ck
        CHECK (policy_version_digest ~ '^[0-9a-f]{64}$'
            AND content_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ad_brief_publication_bundle_snapshot_ck
        CHECK (jsonb_typeof(bundle_version_snapshot) = 'array'),
    CONSTRAINT ad_brief_publication_gaps_ck
        CHECK (cardinality(gap_codes) BETWEEN 0 AND 64
            AND array_position(gap_codes, NULL) IS NULL),
    CONSTRAINT ad_brief_publication_adjustment_ck
        CHECK (adjustment_reason IS NULL
            OR length(btrim(adjustment_reason)) BETWEEN 1 AND 1024),
    CONSTRAINT ad_brief_publication_late_fact_ck
        CHECK (late_fact_reference IS NULL
            OR length(btrim(late_fact_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_brief_publication_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);

CREATE INDEX ad_brief_publication_current_ix
    ON ops.ad_brief_publication (organization_id, brief_kind, period_starts_at DESC);
CREATE INDEX ad_brief_publication_revision_ix
    ON ops.ad_brief_publication (supersedes_publication_id)
    WHERE supersedes_publication_id IS NOT NULL;

CREATE FUNCTION ops.ad_brief_publication_is_immutable()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, ops, pg_temp
AS $$
BEGIN
    -- A published report is what somebody read on the day. Editing it would
    -- rewrite the basis of a decision already taken; a later reading is a new
    -- publication that says what changed.
    RAISE EXCEPTION 'a published brief is a permanent record'
        USING ERRCODE = 'MO044';
END;
$$;

CREATE TRIGGER ad_brief_publication_no_update
    BEFORE UPDATE OR DELETE ON ops.ad_brief_publication
    FOR EACH ROW EXECUTE FUNCTION ops.ad_brief_publication_is_immutable();

REVOKE ALL ON FUNCTION ops.ad_brief_publication_is_immutable() FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- The sections
-- ---------------------------------------------------------------------------

-- Every topic the Contract names, emitted whether or not it has anything in it.
--
-- A section that vanished when empty would make "we found nothing" and "we never
-- looked" the same page. The coverage state and the blocker codes are what tell
-- them apart, and NOT_AVAILABLE is a real answer rather than a zero.
CREATE TABLE mart.ad_brief_section (
    id              uuid    NOT NULL,
    publication_id  uuid    NOT NULL,
    organization_id uuid    NOT NULL,
    section_code    text    NOT NULL,
    ordinal         integer NOT NULL,
    item_count      integer NOT NULL DEFAULT 0,
    coverage_state  text    NOT NULL,
    blocker_codes   text[]  NOT NULL DEFAULT '{}',
    summary_note    text,
    CONSTRAINT ad_brief_section_pk PRIMARY KEY (id),
    CONSTRAINT ad_brief_section_publication_fk
        FOREIGN KEY (publication_id, organization_id)
        REFERENCES ops.ad_brief_publication (id, organization_id),
    CONSTRAINT ad_brief_section_code_uq UNIQUE (publication_id, section_code),
    CONSTRAINT ad_brief_section_ordinal_uq UNIQUE (publication_id, ordinal),
    CONSTRAINT ad_brief_section_ordinal_ck CHECK (ordinal >= 1),
    CONSTRAINT ad_brief_section_count_ck CHECK (item_count >= 0),
    -- The closed set. The daily topics and the weekly topics the Contract names,
    -- as a constraint rather than a list in a service somebody could shorten.
    CONSTRAINT ad_brief_section_code_ck
        CHECK (section_code IN (
            'DATA_HEALTH', 'IMMEDIATE_PROTECTION_AND_REGRESSION', 'DATA_REPAIR',
            'QUALIFIED_OPTIMIZATION', 'WATCH', 'HUMAN_RESPONSIBILITY',
            'APPROVALS_AND_EXCEPTIONS', 'EXECUTION_AND_AGGREGATE_EXPOSURE',
            'UNKNOWN_MISMATCH_AND_MANUAL_VERIFICATION', 'RECENT_OUTCOMES',
            'SHADOW_DECISION_REASONS', 'GOVERNED_ACTIONS',
            'CONFIGURATION_VERIFICATION', 'EARLY_GUARDS',
            'OPERATIONAL_AND_SETTLED_TRANSITIONS',
            'REGRESSION_QUARANTINE_AND_COMPENSATION', 'EXCEPTIONS',
            'SYSTEM_AND_HUMAN_SLO', 'AGGREGATE_EXPOSURE', 'POLICY_BUNDLE_MATURITY',
            'GATE_EVIDENCE', 'DEFERRED_RELEASE_OBLIGATIONS')),
    CONSTRAINT ad_brief_section_coverage_ck
        CHECK (coverage_state IN ('COMPLETE', 'PARTIAL', 'NOT_AVAILABLE', 'BLOCKED')),
    -- Anything short of complete says why. A partial section with no reason is
    -- a gap nobody can close.
    CONSTRAINT ad_brief_section_blocker_ck
        CHECK ((coverage_state = 'COMPLETE') = (cardinality(blocker_codes) = 0)),
    CONSTRAINT ad_brief_section_blocker_shape_ck
        CHECK (cardinality(blocker_codes) BETWEEN 0 AND 32
            AND array_position(blocker_codes, NULL) IS NULL),
    CONSTRAINT ad_brief_section_note_ck
        CHECK (summary_note IS NULL OR length(btrim(summary_note)) BETWEEN 1 AND 1024)
);

CREATE INDEX ad_brief_section_publication_ix
    ON mart.ad_brief_section (publication_id, ordinal);

-- ---------------------------------------------------------------------------
-- The items
-- ---------------------------------------------------------------------------

-- One line of a report, and the single canonical row it is about.
--
-- Exactly one reference is non-null. An item pointing at two authorities would
-- be the ambiguity that lets a reader treat the brief as the authority, and an
-- item pointing at none would be the brief asserting something of its own.
--
-- No item carries a bare number either: a value states whether it is available,
-- and an amount without a state is exactly the thing this product refuses
-- everywhere else.
CREATE TABLE mart.ad_brief_item (
    id                           uuid           NOT NULL,
    publication_id               uuid           NOT NULL,
    organization_id              uuid           NOT NULL,
    section_code                 text           NOT NULL,
    ordinal                      integer        NOT NULL,
    subject_kind                 text           NOT NULL,
    case_id                      uuid,
    work_task_id                 uuid,
    recommendation_id            uuid,
    outcome_observation_id       uuid,
    slo_observation_id           uuid,
    containment_id               uuid,
    reservation_id               uuid,
    bid_command_id               uuid,
    manual_packet_id             uuid,
    bundle_id                    uuid,
    metric_value_id              uuid,
    store_id                     uuid,
    lane                         text,
    protection_tier              text,
    cause_code                   text,
    value_state                  text           NOT NULL,
    numeric_value                numeric(20, 6),
    currency_code                text,
    evidence_state               text,
    confidence_state             text,
    blocker_codes                text[]         NOT NULL DEFAULT '{}',
    observed_at                  timestamptz,
    CONSTRAINT ad_brief_item_pk PRIMARY KEY (id),
    CONSTRAINT ad_brief_item_section_fk
        FOREIGN KEY (publication_id, section_code)
        REFERENCES mart.ad_brief_section (publication_id, section_code),
    CONSTRAINT ad_brief_item_case_fk
        FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case (id, organization_id),
    CONSTRAINT ad_brief_item_task_fk
        FOREIGN KEY (work_task_id) REFERENCES ops.work_task (id),
    CONSTRAINT ad_brief_item_recommendation_fk
        FOREIGN KEY (recommendation_id, organization_id)
        REFERENCES ops.recommendation (id, organization_id),
    CONSTRAINT ad_brief_item_outcome_fk
        FOREIGN KEY (outcome_observation_id) REFERENCES ops.ad_outcome_observation (id),
    CONSTRAINT ad_brief_item_slo_fk
        FOREIGN KEY (slo_observation_id) REFERENCES ops.ad_slo_observation (id),
    CONSTRAINT ad_brief_item_containment_fk
        FOREIGN KEY (containment_id, organization_id)
        REFERENCES ops.ad_containment (id, organization_id),
    CONSTRAINT ad_brief_item_reservation_fk
        FOREIGN KEY (reservation_id, organization_id)
        REFERENCES ops.ad_action_reservation (id, organization_id),
    CONSTRAINT ad_brief_item_command_fk
        FOREIGN KEY (bid_command_id, organization_id)
        REFERENCES ops.ad_bid_command (id, organization_id),
    CONSTRAINT ad_brief_item_packet_fk
        FOREIGN KEY (manual_packet_id, organization_id)
        REFERENCES ops.ad_manual_execution_packet (id, organization_id),
    CONSTRAINT ad_brief_item_bundle_fk
        FOREIGN KEY (bundle_id, organization_id)
        REFERENCES ops.ad_decision_policy_bundle (id, organization_id),
    CONSTRAINT ad_brief_item_metric_fk
        FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value (id),
    CONSTRAINT ad_brief_item_store_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT ad_brief_item_ordinal_uq UNIQUE (publication_id, section_code, ordinal),
    CONSTRAINT ad_brief_item_ordinal_ck CHECK (ordinal >= 1),
    -- Exactly one. Not "at least one": two references would be two authorities
    -- and a reader could not say which one the line was about.
    CONSTRAINT ad_brief_item_reference_ck
        CHECK (num_nonnulls(case_id, work_task_id, recommendation_id, outcome_observation_id,
                            slo_observation_id, containment_id, reservation_id, bid_command_id,
                            manual_packet_id, bundle_id, metric_value_id) = 1),
    CONSTRAINT ad_brief_item_subject_ck
        CHECK (subject_kind IN ('AD_CASE', 'WORK_TASK', 'RECOMMENDATION', 'OUTCOME_OBSERVATION',
                                'SLO_OBSERVATION', 'CONTAINMENT', 'RESERVATION', 'BID_COMMAND',
                                'MANUAL_PACKET', 'DECISION_BUNDLE', 'METRIC_VALUE')),
    CONSTRAINT ad_brief_item_value_ck
        CHECK (value_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED')),
    CONSTRAINT ad_brief_item_value_presence_ck
        CHECK ((value_state = 'AVAILABLE') = (numeric_value IS NOT NULL)),
    CONSTRAINT ad_brief_item_currency_ck
        CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ad_brief_item_blocker_ck
        CHECK (cardinality(blocker_codes) BETWEEN 0 AND 32
            AND array_position(blocker_codes, NULL) IS NULL)
);

CREATE INDEX ad_brief_item_publication_ix
    ON mart.ad_brief_item (publication_id, section_code, ordinal);
CREATE INDEX ad_brief_item_case_ix
    ON mart.ad_brief_item (case_id) WHERE case_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The delta
-- ---------------------------------------------------------------------------

-- What a revision changed, stated rather than left to be diffed.
--
-- A reader comparing two published bodies line by line would be reconstructing
-- an answer the producer already had. This table carries it: what was added,
-- what stopped applying, what was restated, and which late fact caused each.
CREATE TABLE mart.ad_brief_delta (
    id                        uuid           NOT NULL,
    publication_id            uuid           NOT NULL,
    organization_id           uuid           NOT NULL,
    -- Carried on the row so the constraint below can be a check rather than a
    -- trigger: a delta may only belong to a publication that is a revision.
    revision_kind             text           NOT NULL,
    supersedes_publication_id uuid           NOT NULL,
    section_code              text           NOT NULL,
    change_kind               text           NOT NULL,
    previous_item_id          uuid,
    current_item_id           uuid,
    previous_value_state      text,
    previous_numeric_value    numeric(20, 6),
    current_value_state       text,
    current_numeric_value     numeric(20, 6),
    late_fact_reference       text           NOT NULL,
    change_reason             text           NOT NULL,
    CONSTRAINT ad_brief_delta_pk PRIMARY KEY (id),
    CONSTRAINT ad_brief_delta_publication_fk
        FOREIGN KEY (publication_id, revision_kind)
        REFERENCES ops.ad_brief_publication (id, revision_kind),
    CONSTRAINT ad_brief_delta_supersedes_fk
        FOREIGN KEY (supersedes_publication_id) REFERENCES ops.ad_brief_publication (id),
    CONSTRAINT ad_brief_delta_previous_item_fk
        FOREIGN KEY (previous_item_id) REFERENCES mart.ad_brief_item (id),
    CONSTRAINT ad_brief_delta_current_item_fk
        FOREIGN KEY (current_item_id) REFERENCES mart.ad_brief_item (id),
    CONSTRAINT ad_brief_delta_revision_ck CHECK (revision_kind IN ('REVISION', 'DELTA')),
    CONSTRAINT ad_brief_delta_change_ck
        CHECK (change_kind IN ('ADDED', 'REMOVED', 'RESTATED')),
    CONSTRAINT ad_brief_delta_added_ck
        CHECK ((change_kind = 'ADDED') = (previous_item_id IS NULL)),
    CONSTRAINT ad_brief_delta_removed_ck
        CHECK ((change_kind = 'REMOVED') = (current_item_id IS NULL)),
    CONSTRAINT ad_brief_delta_value_ck
        CHECK ((previous_value_state IS NULL
                OR previous_value_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED'))
            AND (current_value_state IS NULL
                OR current_value_state IN ('AVAILABLE', 'NOT_AVAILABLE', 'UNDEFINED'))),
    CONSTRAINT ad_brief_delta_late_fact_ck
        CHECK (length(btrim(late_fact_reference)) BETWEEN 1 AND 512),
    CONSTRAINT ad_brief_delta_reason_ck
        CHECK (length(btrim(change_reason)) BETWEEN 1 AND 1024)
);

CREATE INDEX ad_brief_delta_publication_ix
    ON mart.ad_brief_delta (publication_id, section_code);

-- ---------------------------------------------------------------------------
-- Routes, grants and the trace vocabulary
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'ad_reporting_calendar', 'NO_ROUTE', NULL,
        'owner-published reporting calendar; read by the brief producer only'),
    ('ops', 'ad_brief_publication', 'NO_ROUTE', NULL,
        'append-only versioned brief publication and revision lineage'),
    ('mart', 'ad_brief_section', 'NO_ROUTE', NULL,
        'append-only rendered section skeleton of one publication'),
    ('mart', 'ad_brief_item', 'NO_ROUTE', NULL,
        'append-only canonical references of one publication; owns no authority'),
    ('mart', 'ad_brief_delta', 'NO_ROUTE', NULL,
        'append-only revision delta; describes what changed and decides nothing');

-- The calendar is owner-published: the application may read it and retire a
-- version, and may not write one.
GRANT SELECT, INSERT, UPDATE (status, effective_to) ON core.ad_reporting_calendar
    TO marketops_app;

-- Everything else is append-only, by the absence of any other grant.
GRANT SELECT, INSERT ON ops.ad_brief_publication TO marketops_app;
GRANT SELECT, INSERT ON mart.ad_brief_section TO marketops_app;
GRANT SELECT, INSERT ON mart.ad_brief_item TO marketops_app;
GRANT SELECT, INSERT ON mart.ad_brief_delta TO marketops_app;

-- A publication that did not happen should be visible as a missing run rather
-- than inferred from an absent report.
ALTER TABLE ops.ad_trace_event DROP CONSTRAINT ad_trace_event_stage_ck;
ALTER TABLE ops.ad_trace_event ADD CONSTRAINT ad_trace_event_stage_ck
    CHECK (stage_code IN (
        'TARGET_DEDUP_QUEUED', 'TARGET_DEDUP_COALESCED', 'TARGET_DEDUP_SUPPRESSED',
        'CALCULATION_STARTED', 'EVIDENCE_AND_LANE_CALCULATED',
        'PROJECTION_WRITTEN', 'CASE_SYNCHRONIZED', 'AUTO_VERIFICATION',
        'SLO_RECORDED', 'SWEEP_STARTED', 'SWEEP_COMPLETED', 'SWEEP_FAILED',
        'BACKLOG_SNAPSHOT', 'EXCEPTION_EXPIRY_REVALIDATION',
        'APPROVAL_EXPIRY_SWEEP', 'RESERVATION_RELEASE_SWEEP',
        'OUTCOME_MATURITY_SWEEP',
        'BRIEF_PUBLICATION_STARTED', 'BRIEF_PUBLICATION_COMPLETED',
        'BRIEF_PUBLICATION_SUPPRESSED', 'BRIEF_REVISION_PUBLISHED'));
