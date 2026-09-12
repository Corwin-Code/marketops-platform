-- Retain the existing queue as the sole claim authority; historical receipts are not fabricated.
ALTER TABLE ops.lc_recalculation_queue
    ADD COLUMN lease_generation bigint,
    ADD COLUMN leased_until timestamptz,
    ADD COLUMN health_result_id uuid REFERENCES mart.lc_listing_health(id),
    ADD CONSTRAINT lc_recalculation_lease_generation_ck CHECK (lease_generation IS NULL OR lease_generation > 0);

CREATE FUNCTION ops.lc_recalculation_result_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ops, mart AS $$
BEGIN
    IF TG_OP='UPDATE' AND OLD.lease_generation IS NOT NULL AND OLD.state IN ('FINISHED','FAILED') THEN
        RAISE EXCEPTION 'terminal recalculation receipt is immutable' USING ERRCODE='MO105';
    END IF;
    IF TG_OP='UPDATE' AND OLD.lease_generation IS NOT NULL
       AND (NEW.lease_generation IS NULL OR NEW.lease_generation<OLD.lease_generation) THEN
        RAISE EXCEPTION 'recalculation generation cannot be erased or rewound' USING ERRCODE='MO105';
    END IF;
    IF NEW.lease_generation IS NOT NULL THEN
        IF (NEW.state='RUNNING') <> (NEW.leased_until IS NOT NULL) THEN
            RAISE EXCEPTION 'recalculation lease shape mismatch' USING ERRCODE='MO105';
        END IF;
        IF NEW.state='FINISHED' AND NOT EXISTS (
            SELECT 1 FROM mart.lc_listing_health h
            JOIN mart.calculation_run r ON r.id=h.calculation_run_id
            WHERE h.id=NEW.health_result_id AND h.calculation_run_id=NEW.calculation_run_id
              AND h.organization_id=NEW.organization_id AND h.platform_listing_id=NEW.platform_listing_id
              AND r.organization_id=NEW.organization_id AND r.state='SUCCEEDED'
              AND h.computed_at>=NEW.started_at AND h.computed_at<=NEW.finished_at
        ) THEN
            RAISE EXCEPTION 'recalculation requires its actual listing result and calculation run' USING ERRCODE='MO105';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lc_recalculation_result_guard BEFORE INSERT OR UPDATE ON ops.lc_recalculation_queue
FOR EACH ROW EXECUTE FUNCTION ops.lc_recalculation_result_guard();

GRANT UPDATE (lease_generation, leased_until, health_result_id) ON ops.lc_recalculation_queue TO marketops_app;
