-- Keep the original digest/evidence untouched. New bindings can use this
-- reproducible PostgreSQL JSONB digest independently of the legacy Java JSON
-- property order. This adds no retrospective source or method qualification.
ALTER TABLE mart.lc_measurement_lineage ADD COLUMN canonical_input_digest text;
UPDATE mart.lc_measurement_lineage
    SET canonical_input_digest=encode(sha256(convert_to(inputs::text,'UTF8')),'hex');
ALTER TABLE mart.lc_measurement_lineage ALTER COLUMN canonical_input_digest SET NOT NULL;
ALTER TABLE mart.lc_measurement_lineage ADD CONSTRAINT lc_measurement_lineage_canonical_digest_ck
    CHECK (canonical_input_digest=encode(sha256(convert_to(inputs::text,'UTF8')),'hex'));

CREATE FUNCTION mart.lc_measurement_lineage_scope_guard()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,mart,core,pg_temp
AS $$
DECLARE
    measurement mart.lc_conversion_measurement%ROWTYPE;
    coverage core.lc_measurement_coverage%ROWTYPE;
BEGIN
    IF NEW.canonical_input_digest IS NOT NULL AND NEW.canonical_input_digest <>
            encode(sha256(convert_to(NEW.inputs::text,'UTF8')),'hex') THEN
        RAISE EXCEPTION 'measurement canonical input digest does not match retained inputs' USING ERRCODE='MO093';
    END IF;
    NEW.canonical_input_digest:=encode(sha256(convert_to(NEW.inputs::text,'UTF8')),'hex');
    SELECT * INTO STRICT measurement FROM mart.lc_conversion_measurement WHERE id=NEW.measurement_id;
    IF NEW.coverage_id IS NULL THEN
        IF measurement.path_qualified THEN
            RAISE EXCEPTION 'qualified measurement lineage requires its exact source coverage' USING ERRCODE='MO093';
        END IF;
    ELSE
        SELECT * INTO STRICT coverage FROM core.lc_measurement_coverage WHERE id=NEW.coverage_id;
        IF coverage.organization_id<>measurement.organization_id
            OR coverage.platform_listing_id<>measurement.platform_listing_id
            OR coverage.evidence_path<>measurement.evidence_path
            OR coverage.window_start<>measurement.window_start
            OR coverage.window_end<>measurement.window_end
            OR coverage.retention_window_days<>measurement.retention_window_days
            OR coverage.recorded_at>measurement.computed_at THEN
            RAISE EXCEPTION 'measurement lineage coverage identity, period or maturity definition mismatch' USING ERRCODE='MO092';
        END IF;
    END IF;
    IF NEW.recorded_at<measurement.computed_at THEN
        RAISE EXCEPTION 'measurement lineage cannot precede its computation' USING ERRCODE='MO093';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION mart.lc_measurement_lineage_scope_guard() FROM PUBLIC;
CREATE TRIGGER lc_measurement_lineage_scope_guard BEFORE INSERT ON mart.lc_measurement_lineage
    FOR EACH ROW EXECUTE FUNCTION mart.lc_measurement_lineage_scope_guard();
