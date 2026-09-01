-- Add durable return-evidence, Case-SLA, delegation, trace and successor-index controls.
-- V0001--V0034 are immutable historical migrations; every repair is additive.

-- Sensitive availability reads share the immutable attributable journal used
-- by mutations and delegation decisions.
ALTER TABLE ops.metadata_audit_event
    DROP CONSTRAINT metadata_audit_event_action_ck,
    ADD CONSTRAINT metadata_audit_event_action_ck
        CHECK (action IN (
            'READ', 'CREATE', 'UPDATE', 'STATUS_CHANGE', 'GRANT', 'REVOKE',
            'VERIFICATION_CHANGE', 'DENIED',
            'IMPORT', 'MAPPING_DECISION', 'APPROVAL_DECISION',
            'POLICY_CHANGE', 'COMMAND_TRANSITION', 'KILL_SWITCH',
            'AI_INVOCATION', 'EXPORT'));

-- Freshness is business policy, never inferred from the age of the oldest event.
ALTER TABLE core.return_quality_policy
    ADD COLUMN evidence_freshness_max_minutes integer,
    ADD CONSTRAINT return_quality_policy_freshness_ck
        CHECK (evidence_freshness_max_minutes IS NULL
            OR evidence_freshness_max_minutes BETWEEN 1 AND 10080);

-- A report-coverage assertion is the sole authority for whether the D30
-- completed, retained, return and QC sources constitute a complete report.
-- Event rows remain official facts; they do not manufacture report completeness.
CREATE TABLE ledger.return_quality_evidence_snapshot (
    id                          uuid        NOT NULL,
    organization_id             uuid        NOT NULL,
    platform_listing_variant_id uuid        NOT NULL,
    report_window_start         timestamptz NOT NULL,
    report_window_end           timestamptz NOT NULL,
    completed_coverage          text        NOT NULL,
    retained_coverage           text        NOT NULL,
    return_coverage             text        NOT NULL,
    qc_coverage                 text        NOT NULL,
    completed_source_updated_at timestamptz,
    retained_source_updated_at  timestamptz,
    return_source_updated_at    timestamptz,
    qc_source_updated_at        timestamptz,
    evidence_reference          text        NOT NULL,
    accepted_at                 timestamptz NOT NULL,
    correlation_id              text        NOT NULL,
    supersedes_snapshot_id      uuid,
    CONSTRAINT return_quality_evidence_snapshot_pk PRIMARY KEY (id),
    CONSTRAINT return_quality_evidence_snapshot_id_org_uq
        UNIQUE (id, organization_id),
    CONSTRAINT return_quality_evidence_snapshot_org_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT return_quality_evidence_snapshot_listing_fk
        FOREIGN KEY (platform_listing_variant_id, organization_id)
        REFERENCES core.platform_listing_variant (id, organization_id),
    CONSTRAINT return_quality_evidence_snapshot_supersedes_fk
        FOREIGN KEY (supersedes_snapshot_id, organization_id)
        REFERENCES ledger.return_quality_evidence_snapshot (id, organization_id),
    CONSTRAINT return_quality_evidence_snapshot_window_ck
        CHECK (report_window_end > report_window_start),
    CONSTRAINT return_quality_evidence_snapshot_completed_ck
        CHECK (completed_coverage IN ('COMPLETE', 'INCOMPLETE', 'CONFLICTED')),
    CONSTRAINT return_quality_evidence_snapshot_retained_ck
        CHECK (retained_coverage IN ('COMPLETE', 'INCOMPLETE', 'CONFLICTED')),
    CONSTRAINT return_quality_evidence_snapshot_return_ck
        CHECK (return_coverage IN (
            'COMPLETE_ZERO', 'COMPLETE_OBSERVED', 'INCOMPLETE', 'CONFLICTED')),
    CONSTRAINT return_quality_evidence_snapshot_qc_ck
        CHECK (qc_coverage IN ('COMPLETE', 'INCOMPLETE', 'CONFLICTED')),
    CONSTRAINT return_quality_evidence_snapshot_timestamp_ck CHECK (
        (completed_coverage <> 'COMPLETE' OR completed_source_updated_at IS NOT NULL)
        AND (retained_coverage <> 'COMPLETE' OR retained_source_updated_at IS NOT NULL)
        AND (return_coverage NOT IN ('COMPLETE_ZERO', 'COMPLETE_OBSERVED')
             OR return_source_updated_at IS NOT NULL)
        AND (qc_coverage <> 'COMPLETE' OR qc_source_updated_at IS NOT NULL)),
    CONSTRAINT return_quality_evidence_snapshot_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT return_quality_evidence_snapshot_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128),
    CONSTRAINT return_quality_evidence_snapshot_supersedes_self_ck
        CHECK (supersedes_snapshot_id IS NULL OR supersedes_snapshot_id <> id)
);

CREATE UNIQUE INDEX return_quality_evidence_snapshot_current_uq
    ON ledger.return_quality_evidence_snapshot
        (organization_id, platform_listing_variant_id,
         report_window_start, report_window_end)
    WHERE supersedes_snapshot_id IS NULL;
CREATE UNIQUE INDEX return_quality_evidence_snapshot_successor_uq
    ON ledger.return_quality_evidence_snapshot (supersedes_snapshot_id)
    WHERE supersedes_snapshot_id IS NOT NULL;
CREATE INDEX return_quality_evidence_snapshot_lookup_ix
    ON ledger.return_quality_evidence_snapshot
        (platform_listing_variant_id, report_window_start, report_window_end, accepted_at DESC);

CREATE FUNCTION ledger.enforce_return_quality_evidence_successor()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    prior ledger.return_quality_evidence_snapshot%ROWTYPE;
BEGIN
    IF NEW.supersedes_snapshot_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO prior
      FROM ledger.return_quality_evidence_snapshot
     WHERE id = NEW.supersedes_snapshot_id
       AND organization_id = NEW.organization_id
     FOR UPDATE;
    IF NOT FOUND
       OR prior.platform_listing_variant_id <> NEW.platform_listing_variant_id
       OR prior.report_window_start <> NEW.report_window_start
       OR prior.report_window_end <> NEW.report_window_end
       OR prior.accepted_at >= NEW.accepted_at THEN
        RAISE EXCEPTION 'return-quality evidence successor changes identity or time order'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'return_quality_evidence_snapshot_successor_ck';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER return_quality_evidence_snapshot_successor_guard
    BEFORE INSERT ON ledger.return_quality_evidence_snapshot
    FOR EACH ROW EXECUTE FUNCTION ledger.enforce_return_quality_evidence_successor();

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note)
VALUES ('ledger', 'return_quality_evidence_snapshot', 'NO_ROUTE', NULL,
        'authoritative internal report-coverage evidence; performs no external call');

GRANT SELECT, INSERT ON ledger.return_quality_evidence_snapshot TO marketops_app;

-- Ordinary Action SLA time is preserved while, and only while, an exact
-- accepted-risk exception is active. The original deadline is historical fact;
-- the current deadline is deterministically rebased from the stored remainder.
ALTER TABLE ops.availability_case
    ADD COLUMN original_action_due_at timestamptz,
    ADD COLUMN action_sla_paused_at timestamptz,
    ADD COLUMN action_sla_remaining_ms bigint;
UPDATE ops.availability_case SET original_action_due_at = action_due_at;
ALTER TABLE ops.availability_case
    ALTER COLUMN original_action_due_at SET NOT NULL,
    ADD CONSTRAINT availability_case_action_sla_remaining_ck
        CHECK (action_sla_remaining_ms IS NULL OR action_sla_remaining_ms >= 0),
    ADD CONSTRAINT availability_case_action_sla_pause_shape_ck CHECK (
        (state = 'ACCEPTED_RISK')
        = (action_sla_paused_at IS NOT NULL AND action_sla_remaining_ms IS NOT NULL));

CREATE FUNCTION ops.default_availability_case_original_action_due_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.original_action_due_at IS NULL THEN
        NEW.original_action_due_at := NEW.action_due_at;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER availability_case_original_action_due_default
    BEFORE INSERT ON ops.availability_case
    FOR EACH ROW EXECUTE FUNCTION
        ops.default_availability_case_original_action_due_at();

-- Snapshot the exact facts an approval licensed. Revalidation compares like
-- with like instead of trying to reconstruct old severity/materiality later.
ALTER TABLE ops.availability_accepted_exception
    ADD COLUMN accepted_severity text,
    ADD COLUMN accepted_profit_at_risk_amount numeric(19,4),
    ADD COLUMN accepted_profit_at_risk_currency char(3),
    ADD COLUMN accepted_case_reopen_count integer,
    ADD CONSTRAINT availability_accepted_exception_accepted_severity_ck
        CHECK (accepted_severity IS NULL OR accepted_severity IN (
            'WATCH', 'HIGH', 'CRITICAL', 'REVIEW', 'UNRESOLVED')),
    ADD CONSTRAINT availability_accepted_exception_accepted_profit_ck CHECK (
        (accepted_profit_at_risk_amount IS NULL)
        = (accepted_profit_at_risk_currency IS NULL)),
    ADD CONSTRAINT availability_accepted_exception_accepted_reopen_ck
        CHECK (accepted_case_reopen_count IS NULL OR accepted_case_reopen_count >= 0);

-- A named, effective-dated delegation is an authority record rather than an
-- unvalidated string supplied with a decision. Revocation is explicit and the
-- original grant remains immutable history.
CREATE TABLE ops.availability_exception_delegation (
    id                    uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    delegation_reference  text        NOT NULL,
    delegate_user_id      uuid        NOT NULL,
    delegated_role_code   text        NOT NULL,
    granted_by_user_id    uuid        NOT NULL,
    granted_by_role_code  text        NOT NULL,
    effective_from        timestamptz NOT NULL,
    effective_to          timestamptz NOT NULL,
    evidence_reference    text        NOT NULL,
    granted_at            timestamptz NOT NULL,
    revoked_at            timestamptz,
    revoked_by_user_id    uuid,
    revocation_reason     text,
    correlation_id        text        NOT NULL,
    CONSTRAINT availability_exception_delegation_pk PRIMARY KEY (id),
    CONSTRAINT availability_exception_delegation_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT availability_exception_delegation_reference_uq
        UNIQUE (organization_id, delegation_reference),
    CONSTRAINT availability_exception_delegation_org_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT availability_exception_delegation_delegate_fk
        FOREIGN KEY (delegate_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT availability_exception_delegation_grantor_fk
        FOREIGN KEY (granted_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT availability_exception_delegation_revoker_fk
        FOREIGN KEY (revoked_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT availability_exception_delegation_role_fk
        FOREIGN KEY (delegated_role_code) REFERENCES iam.business_role (code),
    CONSTRAINT availability_exception_delegation_grantor_role_fk
        FOREIGN KEY (granted_by_role_code) REFERENCES iam.business_role (code),
    CONSTRAINT availability_exception_delegation_period_ck
        CHECK (effective_to > effective_from),
    CONSTRAINT availability_exception_delegation_reference_ck
        CHECK (length(btrim(delegation_reference)) BETWEEN 1 AND 256),
    CONSTRAINT availability_exception_delegation_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT availability_exception_delegation_revoke_ck CHECK (
        (revoked_at IS NULL AND revoked_by_user_id IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by_user_id IS NOT NULL
            AND length(btrim(revocation_reason)) BETWEEN 1 AND 1024)),
    CONSTRAINT availability_exception_delegation_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128)
);
CREATE INDEX availability_exception_delegation_live_ix
    ON ops.availability_exception_delegation
        (organization_id, delegate_user_id, delegated_role_code,
         effective_from, effective_to)
    WHERE revoked_at IS NULL;

CREATE FUNCTION ops.enforce_availability_exception_delegation_revocation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'availability exception delegation is already revoked';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.delegation_reference IS DISTINCT FROM OLD.delegation_reference
       OR NEW.delegate_user_id IS DISTINCT FROM OLD.delegate_user_id
       OR NEW.delegated_role_code IS DISTINCT FROM OLD.delegated_role_code
       OR NEW.granted_by_user_id IS DISTINCT FROM OLD.granted_by_user_id
       OR NEW.granted_by_role_code IS DISTINCT FROM OLD.granted_by_role_code
       OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
       OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
       OR NEW.evidence_reference IS DISTINCT FROM OLD.evidence_reference
       OR NEW.granted_at IS DISTINCT FROM OLD.granted_at
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'availability exception delegation grant is immutable';
    END IF;
    IF NEW.revoked_at IS NULL OR NEW.revoked_by_user_id IS NULL
       OR NEW.revocation_reason IS NULL THEN
        RAISE EXCEPTION 'availability exception delegation update must be a revocation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER availability_exception_delegation_revocation_guard
    BEFORE UPDATE ON ops.availability_exception_delegation
    FOR EACH ROW EXECUTE FUNCTION
        ops.enforce_availability_exception_delegation_revocation();

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note)
VALUES ('ops', 'availability_exception_delegation', 'NO_ROUTE', NULL,
        'internal accepted-risk decision delegation; performs no external call');

GRANT SELECT, INSERT, UPDATE ON ops.availability_exception_delegation TO marketops_app;

-- Every accepted-fact read excludes a row once a successor names it.  The
-- parent-side foreign-key indexes do not support that inverse lookup; without
-- these indexes the exact 5,000-variant calculation path repeatedly scans the
-- full fact ledger for each NOT EXISTS anti-join.
CREATE INDEX listing_price_observation_supersedes_ix
    ON core.listing_price_observation (supersedes_fact_id)
    WHERE supersedes_fact_id IS NOT NULL;
CREATE INDEX listing_stock_observation_supersedes_ix
    ON core.listing_stock_observation (supersedes_fact_id)
    WHERE supersedes_fact_id IS NOT NULL;
CREATE INDEX listing_health_observation_supersedes_ix
    ON core.listing_health_observation (supersedes_fact_id)
    WHERE supersedes_fact_id IS NOT NULL;
CREATE INDEX listing_traffic_observation_supersedes_ix
    ON core.listing_traffic_observation (supersedes_fact_id)
    WHERE supersedes_fact_id IS NOT NULL;
CREATE INDEX sales_fact_supersedes_ix
    ON ledger.sales_fact (supersedes_fact_id)
    WHERE supersedes_fact_id IS NOT NULL;
CREATE INDEX return_fact_supersedes_ix
    ON ledger.return_fact (supersedes_fact_id)
    WHERE supersedes_fact_id IS NOT NULL;
CREATE INDEX finance_fee_fact_supersedes_ix
    ON ledger.finance_fee_fact (supersedes_fact_id)
    WHERE supersedes_fact_id IS NOT NULL;
CREATE INDEX ad_spend_fact_supersedes_ix
    ON ledger.ad_spend_fact (supersedes_fact_id)
    WHERE supersedes_fact_id IS NOT NULL;

-- Durable relational trace evidence. Structured log messages remain useful for
-- diagnosis, but do not satisfy correlation continuity by themselves.
CREATE TABLE ops.availability_trace_event (
    id                    uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    product_variant_id    uuid,
    path_kind             text        NOT NULL,
    stage_code            text        NOT NULL,
    status                text        NOT NULL,
    correlation_id        text        NOT NULL,
    parent_correlation_id text,
    subject_reference     text,
    detail                jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at           timestamptz NOT NULL,
    CONSTRAINT availability_trace_event_pk PRIMARY KEY (id),
    CONSTRAINT availability_trace_event_org_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT availability_trace_event_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT availability_trace_event_path_ck
        CHECK (path_kind IN ('TARGETED', 'RECONCILIATION', 'OPERATIONS')),
    CONSTRAINT availability_trace_event_stage_ck CHECK (stage_code IN (
        'TARGET_DEDUP_QUEUED', 'TARGET_DEDUP_COALESCED', 'TARGET_DEDUP_SUPPRESSED',
        'TARGETED_PROCESS_STARTED',
        'CALCULATION_STARTED', 'EVIDENCE_AND_RISK_CALCULATED', 'PROJECTION_WRITTEN',
        'CASE_SYNCHRONIZED', 'AUTO_VERIFICATION', 'SLO_RECORDED',
        'SWEEP_STARTED', 'BACKLOG_SNAPSHOT', 'EXCEPTION_EXPIRY_REVALIDATION',
        'SWEEP_COMPLETED', 'SWEEP_FAILED')),
    CONSTRAINT availability_trace_event_status_ck
        CHECK (status IN ('STARTED', 'COMPLETED', 'SUPPRESSED', 'FAILED', 'OBSERVED')),
    CONSTRAINT availability_trace_event_correlation_ck
        CHECK (length(btrim(correlation_id)) BETWEEN 1 AND 128),
    CONSTRAINT availability_trace_event_parent_ck
        CHECK (parent_correlation_id IS NULL
            OR length(btrim(parent_correlation_id)) BETWEEN 1 AND 128),
    CONSTRAINT availability_trace_event_subject_ck
        CHECK (subject_reference IS NULL
            OR length(btrim(subject_reference)) BETWEEN 1 AND 512),
    CONSTRAINT availability_trace_event_detail_ck CHECK (jsonb_typeof(detail) = 'object')
);
CREATE INDEX availability_trace_event_correlation_ix
    ON ops.availability_trace_event (correlation_id, occurred_at, stage_code);
CREATE INDEX availability_trace_event_parent_ix
    ON ops.availability_trace_event (parent_correlation_id, occurred_at)
    WHERE parent_correlation_id IS NOT NULL;
CREATE INDEX availability_trace_event_operations_ix
    ON ops.availability_trace_event (organization_id, path_kind, occurred_at DESC);

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note)
VALUES ('ops', 'availability_trace_event', 'NO_ROUTE', NULL,
        'durable internal correlation evidence; performs no external call');
GRANT SELECT, INSERT ON ops.availability_trace_event TO marketops_app;
