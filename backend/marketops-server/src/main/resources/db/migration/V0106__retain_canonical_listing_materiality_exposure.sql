-- Exact basis of preparation-time exposure. Historic caller-derived classifications are not backfilled.
ALTER TABLE ops.lc_action ADD COLUMN materiality_evidence jsonb;
CREATE FUNCTION ops.lc_materiality_evidence_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.materiality_evidence IS DISTINCT FROM OLD.materiality_evidence THEN
  RAISE EXCEPTION 'prepared materiality evidence is immutable' USING ERRCODE='MO106';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER lc_materiality_evidence_immutable BEFORE UPDATE ON ops.lc_action
 FOR EACH ROW EXECUTE FUNCTION ops.lc_materiality_evidence_immutable();

-- A valid calibration is not proof that current exposure exists. Keep the exact
-- package for review while preserving the separate unresolved-stays-draft gate.
ALTER TABLE ops.lc_action DROP CONSTRAINT lc_action_materiality_axes_ck;
ALTER TABLE ops.lc_action ADD CONSTRAINT lc_action_materiality_axes_ck CHECK (
 (calibration_package_id IS NULL)=(calibration_version IS NULL)
 AND ((materiality_route='MATERIALITY_UNRESOLVED'
       AND content_axis_material IS NULL AND exposure_axis_material IS NULL)
   OR (materiality_route<>'MATERIALITY_UNRESOLVED'
       AND content_axis_material IS NOT NULL AND exposure_axis_material IS NOT NULL
       AND calibration_package_id IS NOT NULL AND calibration_version IS NOT NULL
       AND (materiality_route='MATERIAL_IMPACT')=(content_axis_material OR exposure_axis_material))));
