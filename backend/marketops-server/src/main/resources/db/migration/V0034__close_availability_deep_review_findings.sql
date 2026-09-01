-- SLICE-V1-002 frozen finding-set closure. Forward-only: no historical migration
-- is edited, and every new authority remains internal/no-route.

-- A timestamp alone is not a total accepted-fact cursor.
ALTER TABLE ops.availability_fact_cursor
    ADD COLUMN position_provenance_id uuid NOT NULL
        DEFAULT '00000000-0000-0000-0000-000000000000',
    ADD COLUMN position_item_key text NOT NULL DEFAULT '';
ALTER TABLE ops.availability_fact_cursor
    ADD CONSTRAINT availability_fact_cursor_item_key_ck
        CHECK (length(position_item_key) <= 192);

-- Product is an independent row-scope dimension beside store/action.
ALTER TABLE iam.user_scope_grant
    ADD COLUMN product_variant_ref_id uuid;
ALTER TABLE iam.user_scope_grant
    DROP CONSTRAINT user_scope_grant_one_resource_ck,
    ADD CONSTRAINT user_scope_grant_one_resource_ck
        CHECK (num_nonnulls(
            organization_ref_id, legal_entity_ref_id, marketplace_account_ref_id,
            store_ref_id, warehouse_ref_id, product_variant_ref_id) = 1),
    ADD CONSTRAINT user_scope_grant_product_variant_ref_fk
        FOREIGN KEY (product_variant_ref_id, organization_id)
        REFERENCES core.product_variant (id, organization_id);
DROP INDEX iam.user_scope_grant_active_uq;
CREATE UNIQUE INDEX user_scope_grant_active_uq
    ON iam.user_scope_grant (
        user_id, action_code, organization_ref_id, legal_entity_ref_id,
        marketplace_account_ref_id, store_ref_id, warehouse_ref_id,
        product_variant_ref_id)
    NULLS NOT DISTINCT
    WHERE status = 'ACTIVE';
CREATE INDEX user_scope_grant_product_variant_ix
    ON iam.user_scope_grant (product_variant_ref_id, action_code)
    WHERE product_variant_ref_id IS NOT NULL AND status = 'ACTIVE';

-- Live company supply must carry every material inventory state. Existing rows
-- stay null and therefore fail closed until an attributable source republishes.
ALTER TABLE core.internal_stock_snapshot
    ADD COLUMN quantity_quality_locked integer,
    ADD COLUMN quantity_damaged integer,
    ADD COLUMN quantity_written_off integer,
    ADD COLUMN sellable text,
    ADD COLUMN return_reentry_id uuid,
    ADD CONSTRAINT internal_stock_snapshot_quality_locked_ck
        CHECK (quantity_quality_locked IS NULL OR quantity_quality_locked >= 0),
    ADD CONSTRAINT internal_stock_snapshot_damaged_ck
        CHECK (quantity_damaged IS NULL OR quantity_damaged >= 0),
    ADD CONSTRAINT internal_stock_snapshot_written_off_ck
        CHECK (quantity_written_off IS NULL OR quantity_written_off >= 0),
    ADD CONSTRAINT internal_stock_snapshot_unavailable_total_ck
        CHECK (coalesce(quantity_reserved, 0)
             + coalesce(quantity_quality_locked, 0)
             + coalesce(quantity_damaged, 0)
             + coalesce(quantity_written_off, 0) <= quantity_on_hand),
    ADD CONSTRAINT internal_stock_snapshot_sellable_ck
        CHECK (sellable IS NULL OR sellable IN ('YES', 'NO', 'UNKNOWN'));

-- Returned goods are not stock while in transport or QC. Only a terminal,
-- attributable ledger re-entry may be linked from a later stock snapshot.
CREATE TABLE ledger.return_inventory_transition (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    return_fact_id         uuid        NOT NULL,
    product_variant_id     uuid        NOT NULL,
    warehouse_id           uuid,
    state                  text        NOT NULL,
    quantity               integer     NOT NULL,
    quality_disposition    text,
    evidence_reference     text        NOT NULL,
    actor_user_id          uuid        NOT NULL,
    occurred_at            timestamptz NOT NULL,
    recorded_at            timestamptz NOT NULL,
    supersedes_transition_id uuid,
    CONSTRAINT return_inventory_transition_pk PRIMARY KEY (id),
    CONSTRAINT return_inventory_transition_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT return_inventory_transition_return_fk
        FOREIGN KEY (return_fact_id) REFERENCES ledger.return_fact (id),
    CONSTRAINT return_inventory_transition_variant_fk
        FOREIGN KEY (product_variant_id, organization_id)
        REFERENCES core.product_variant (id, organization_id),
    CONSTRAINT return_inventory_transition_warehouse_fk
        FOREIGN KEY (warehouse_id, organization_id)
        REFERENCES core.warehouse (id, organization_id),
    CONSTRAINT return_inventory_transition_actor_fk
        FOREIGN KEY (actor_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT return_inventory_transition_supersedes_fk
        FOREIGN KEY (supersedes_transition_id, organization_id)
        REFERENCES ledger.return_inventory_transition (id, organization_id),
    CONSTRAINT return_inventory_transition_state_ck
        CHECK (state IN ('IN_TRANSIT', 'AWAITING_QC', 'QC_REJECTED',
                         'WRITTEN_OFF', 'REENTERED_AVAILABLE')),
    CONSTRAINT return_inventory_transition_quantity_ck CHECK (quantity > 0),
    CONSTRAINT return_inventory_transition_reentry_ck
        CHECK ((state = 'REENTERED_AVAILABLE')
            = (warehouse_id IS NOT NULL AND quality_disposition = 'RESELLABLE')),
    CONSTRAINT return_inventory_transition_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512)
);
ALTER TABLE core.internal_stock_snapshot
    ADD CONSTRAINT internal_stock_snapshot_return_reentry_fk
        FOREIGN KEY (return_reentry_id, organization_id)
        REFERENCES ledger.return_inventory_transition (id, organization_id);
CREATE UNIQUE INDEX return_inventory_transition_current_uq
    ON ledger.return_inventory_transition (return_fact_id)
    WHERE state = 'REENTERED_AVAILABLE';
CREATE UNIQUE INDEX return_inventory_transition_chain_start_uq
    ON ledger.return_inventory_transition (return_fact_id)
    WHERE supersedes_transition_id IS NULL;
CREATE UNIQUE INDEX return_inventory_transition_successor_uq
    ON ledger.return_inventory_transition (supersedes_transition_id)
    WHERE supersedes_transition_id IS NOT NULL;

CREATE FUNCTION ledger.enforce_return_inventory_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    prior ledger.return_inventory_transition%ROWTYPE;
    source_return ledger.return_fact%ROWTYPE;
    mapped_variant_id uuid;
BEGIN
    SELECT * INTO source_return FROM ledger.return_fact WHERE id = NEW.return_fact_id;
    SELECT mapping.product_variant_id
      INTO mapped_variant_id
      FROM core.listing_mapping AS mapping
     WHERE mapping.organization_id = source_return.organization_id
       AND mapping.platform_listing_variant_id = source_return.platform_listing_variant_id
       AND mapping.status = 'ACTIVE'
       AND mapping.effective_from <= NEW.occurred_at
       AND (mapping.effective_to IS NULL OR mapping.effective_to > NEW.occurred_at)
     ORDER BY mapping.effective_from DESC
     LIMIT 1;
    IF NOT FOUND
       OR source_return.organization_id <> NEW.organization_id
       OR source_return.return_kind = 'CANCELLATION'
       OR NEW.quantity > source_return.quantity
       OR NEW.occurred_at < source_return.occurred_at
       OR mapped_variant_id IS NULL
       OR mapped_variant_id <> NEW.product_variant_id THEN
        RAISE EXCEPTION 'invalid return source for inventory transition'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'return_inventory_transition_source_ck';
    END IF;

    IF NEW.supersedes_transition_id IS NULL THEN
        IF NEW.state NOT IN ('IN_TRANSIT', 'AWAITING_QC') THEN
            RAISE EXCEPTION 'invalid initial returned-inventory state'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'return_inventory_transition_sequence_ck';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO prior
      FROM ledger.return_inventory_transition
     WHERE id = NEW.supersedes_transition_id
       AND organization_id = NEW.organization_id;
    IF NOT FOUND
       OR prior.return_fact_id <> NEW.return_fact_id
       OR prior.product_variant_id <> NEW.product_variant_id
       OR prior.quantity <> NEW.quantity
       OR NEW.occurred_at < prior.occurred_at
       OR NOT (CASE prior.state
            WHEN 'IN_TRANSIT' THEN NEW.state IN ('AWAITING_QC', 'WRITTEN_OFF')
            WHEN 'AWAITING_QC' THEN NEW.state IN
                ('QC_REJECTED', 'WRITTEN_OFF', 'REENTERED_AVAILABLE')
            ELSE false
          END) THEN
        RAISE EXCEPTION 'invalid returned-inventory state transition'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'return_inventory_transition_sequence_ck';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER return_inventory_transition_guard
    BEFORE INSERT ON ledger.return_inventory_transition
    FOR EACH ROW
    EXECUTE FUNCTION ledger.enforce_return_inventory_transition();

CREATE FUNCTION ledger.enforce_internal_stock_return_reentry()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    linked_transition ledger.return_inventory_transition%ROWTYPE;
BEGIN
    IF NEW.return_reentry_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT *
      INTO linked_transition
      FROM ledger.return_inventory_transition
     WHERE id = NEW.return_reentry_id
       AND organization_id = NEW.organization_id;

    IF NOT FOUND
       OR linked_transition.state <> 'REENTERED_AVAILABLE'
       OR linked_transition.quality_disposition <> 'RESELLABLE'
       OR linked_transition.warehouse_id <> NEW.warehouse_id
       OR linked_transition.product_variant_id <> NEW.product_variant_id
       OR NEW.observed_at < linked_transition.occurred_at THEN
        RAISE EXCEPTION 'invalid returned-inventory re-entry link'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'internal_stock_snapshot_return_reentry_state_ck';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER internal_stock_snapshot_return_reentry_guard
    BEFORE INSERT OR UPDATE OF return_reentry_id, organization_id,
        warehouse_id, product_variant_id
    ON core.internal_stock_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION ledger.enforce_internal_stock_return_reentry();

-- Queue ordering and return-quality are business policy authorities, not code
-- constants. Absence is represented and blocks their respective decisions.
CREATE TABLE core.availability_priority_policy (
    id                 uuid          NOT NULL,
    organization_id    uuid          NOT NULL,
    policy_version     integer       NOT NULL,
    time_weight        numeric(9,4)  NOT NULL,
    profit_weight      numeric(9,4)  NOT NULL,
    velocity_weight    numeric(9,4)  NOT NULL,
    lifecycle_weight   numeric(9,4)  NOT NULL,
    confidence_weight  numeric(9,4)  NOT NULL,
    owner_user_id      uuid          NOT NULL,
    reason             text          NOT NULL,
    evidence_reference text          NOT NULL,
    effective_from     timestamptz   NOT NULL,
    effective_to       timestamptz,
    status             text          NOT NULL,
    created_at         timestamptz   NOT NULL,
    CONSTRAINT availability_priority_policy_pk PRIMARY KEY (id),
    CONSTRAINT availability_priority_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT availability_priority_policy_org_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT availability_priority_policy_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT availability_priority_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT availability_priority_policy_weights_ck
        CHECK (time_weight >= 0 AND profit_weight >= 0 AND velocity_weight >= 0
           AND lifecycle_weight >= 0 AND confidence_weight <= 0),
    CONSTRAINT availability_priority_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT availability_priority_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT availability_priority_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT availability_priority_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT availability_priority_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE TABLE core.return_quality_policy (
    id                          uuid         NOT NULL,
    organization_id             uuid         NOT NULL,
    policy_version              integer      NOT NULL,
    maximum_return_ratio        numeric(6,5) NOT NULL,
    minimum_retention_ratio     numeric(6,5) NOT NULL,
    maximum_defect_return_ratio numeric(6,5) NOT NULL,
    owner_user_id               uuid         NOT NULL,
    reason                      text         NOT NULL,
    evidence_reference          text         NOT NULL,
    effective_from              timestamptz  NOT NULL,
    effective_to                timestamptz,
    status                      text         NOT NULL,
    created_at                  timestamptz  NOT NULL,
    CONSTRAINT return_quality_policy_pk PRIMARY KEY (id),
    CONSTRAINT return_quality_policy_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT return_quality_policy_org_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT return_quality_policy_owner_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT return_quality_policy_ratio_ck CHECK (
        maximum_return_ratio BETWEEN 0 AND 1
        AND minimum_retention_ratio BETWEEN 0 AND 1
        AND maximum_defect_return_ratio BETWEEN 0 AND 1),
    CONSTRAINT return_quality_policy_reason_ck
        CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    CONSTRAINT return_quality_policy_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT return_quality_policy_version_ck CHECK (policy_version >= 1),
    CONSTRAINT return_quality_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    CONSTRAINT return_quality_policy_interval_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT return_quality_policy_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

-- Policy publication is attributable within the same tenant, and its version
-- number is unique inside the exact scope whose history it describes.
ALTER TABLE core.supply_ownership_declaration
    DROP CONSTRAINT supply_ownership_declaration_status_ck,
    ADD CONSTRAINT supply_ownership_declaration_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    ADD CONSTRAINT supply_ownership_declaration_user_org_fk
        FOREIGN KEY (declared_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id);
ALTER TABLE core.lead_time_safety_policy
    DROP CONSTRAINT lead_time_safety_policy_status_ck,
    ADD CONSTRAINT lead_time_safety_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    ADD CONSTRAINT lead_time_safety_policy_owner_org_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id);
ALTER TABLE core.demand_observation_policy
    DROP CONSTRAINT demand_observation_policy_status_ck,
    ADD CONSTRAINT demand_observation_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    ADD CONSTRAINT demand_observation_policy_owner_org_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id);
ALTER TABLE core.work_activation_policy
    DROP CONSTRAINT work_activation_policy_status_ck,
    ADD CONSTRAINT work_activation_policy_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED', 'CANCELLED')),
    ADD CONSTRAINT work_activation_policy_owner_org_fk
        FOREIGN KEY (owner_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id);
CREATE UNIQUE INDEX supply_ownership_declaration_version_uq
    ON core.supply_ownership_declaration
        (organization_id, store_id, fulfillment_mode_code, policy_version);
CREATE UNIQUE INDEX lead_time_safety_policy_version_uq
    ON core.lead_time_safety_policy (organization_id, scope_key, policy_version);
CREATE UNIQUE INDEX demand_observation_policy_version_uq
    ON core.demand_observation_policy (organization_id, policy_version);
CREATE UNIQUE INDEX work_activation_policy_version_uq
    ON core.work_activation_policy (organization_id, policy_version);
CREATE UNIQUE INDEX availability_priority_policy_version_uq
    ON core.availability_priority_policy (organization_id, policy_version);
CREATE UNIQUE INDEX return_quality_policy_version_uq
    ON core.return_quality_policy (organization_id, policy_version);

UPDATE iam.action_scope
   SET description = 'Publish or retire lead-time, safety, demand, ownership, activation, priority and return-quality policy.'
 WHERE code = 'SUPPLY_POLICY_MANAGE';

CREATE FUNCTION core.enforce_availability_policy_version_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- PostgreSQL presents a stored generated column as NULL in NEW before it
    -- recomputes it. scope_key is derived wholly from immutable columns, so it
    -- is deliberately excluded alongside the two controlled lifecycle fields.
    IF (to_jsonb(NEW) - 'status' - 'effective_to' - 'scope_key')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'effective_to' - 'scope_key') THEN
        RAISE EXCEPTION 'published availability policy version is immutable'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'availability_policy_version_immutable_ck';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER supply_ownership_declaration_immutable
    BEFORE UPDATE ON core.supply_ownership_declaration FOR EACH ROW
    EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();
CREATE TRIGGER lead_time_safety_policy_immutable
    BEFORE UPDATE ON core.lead_time_safety_policy FOR EACH ROW
    EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();
CREATE TRIGGER demand_observation_policy_immutable
    BEFORE UPDATE ON core.demand_observation_policy FOR EACH ROW
    EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();
CREATE TRIGGER work_activation_policy_immutable
    BEFORE UPDATE ON core.work_activation_policy FOR EACH ROW
    EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();
CREATE TRIGGER availability_priority_policy_immutable
    BEFORE UPDATE ON core.availability_priority_policy FOR EACH ROW
    EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();
CREATE TRIGGER return_quality_policy_immutable
    BEFORE UPDATE ON core.return_quality_policy FOR EACH ROW
    EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();

-- A full sweep is keyset-paged and records durable progress and item failures.
ALTER TABLE ops.availability_reconciliation_run
    ADD COLUMN last_product_variant_id uuid,
    ADD COLUMN failed_variant_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT availability_reconciliation_run_failed_count_ck
        CHECK (failed_variant_count >= 0);

-- Bind exception, case, child and human actors relationally within one tenant.
ALTER TABLE ops.availability_case
    ADD CONSTRAINT availability_case_id_child_org_uq
        UNIQUE (id, child_id, organization_id);
ALTER TABLE ops.availability_accepted_exception
    ADD COLUMN accepted_risk_digest text,
    ADD CONSTRAINT availability_accepted_exception_risk_digest_ck
        CHECK (accepted_risk_digest IS NULL OR accepted_risk_digest ~ '^[0-9a-f]{32}$'),
    DROP CONSTRAINT availability_accepted_exception_case_fk,
    ADD CONSTRAINT availability_accepted_exception_case_child_fk
        FOREIGN KEY (case_id, child_id, organization_id)
        REFERENCES ops.availability_case (id, child_id, organization_id),
    ADD CONSTRAINT availability_accepted_exception_requester_org_fk
        FOREIGN KEY (requested_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id);
ALTER TABLE ops.availability_exception_decision
    ADD CONSTRAINT availability_exception_decision_user_org_fk
        FOREIGN KEY (decided_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id);
ALTER TABLE core.inbound_supply_attestation_version
    ADD CONSTRAINT inbound_supply_attestation_version_user_org_fk
        FOREIGN KEY (attested_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id);

ALTER TABLE mart.availability_risk_evidence
    DROP CONSTRAINT availability_risk_evidence_role_ck,
    ADD CONSTRAINT availability_risk_evidence_role_ck
        CHECK (evidence_role IN (
            'CHANNEL_STOCK', 'INTERNAL_STOCK', 'PLATFORM_OWNED_STOCK', 'INBOUND',
            'DEMAND', 'RETURN_QUALITY', 'SELLABILITY', 'PROFIT',
            'LEAD_TIME_POLICY', 'DEMAND_POLICY', 'PRIORITY_POLICY',
            'RETURN_QUALITY_POLICY', 'OWNERSHIP_DECLARATION'));

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ledger', 'return_inventory_transition', 'NO_ROUTE', NULL,
        'internal returned-stock QC and ledger re-entry; performs no external call'),
    ('core', 'availability_priority_policy', 'NO_ROUTE', NULL,
        'internal effective-dated queue-order authority; performs no external call'),
    ('core', 'return_quality_policy', 'NO_ROUTE', NULL,
        'internal return/retention/QC guardrail authority; performs no external call');

GRANT SELECT, INSERT ON ledger.return_inventory_transition TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.availability_priority_policy TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON core.return_quality_policy TO marketops_app;
