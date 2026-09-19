-- Exact purpose follows the existing immutable Recommendation and calibration binding.
-- No historical purpose is invented, no review/launch gate is relaxed, and no write is enabled.
CREATE FUNCTION ops.guard_lc_declared_purpose() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,core AS $$
DECLARE purpose text; package_purpose text;
BEGIN
 IF TG_TABLE_NAME='recommendation' THEN
   IF TG_OP='UPDATE' AND NEW.proposed_parameters->'purposeCode' IS DISTINCT FROM OLD.proposed_parameters->'purposeCode' THEN
     RAISE EXCEPTION 'Listing purpose cannot change after proposal' USING ERRCODE='MO090';
   END IF;
   purpose:=NEW.proposed_parameters->>'purposeCode';
   IF (TG_OP='INSERT' AND purpose IS NULL)
     OR (purpose IS NOT NULL AND (jsonb_typeof(NEW.proposed_parameters->'purposeCode')<>'string'
     OR purpose NOT IN ('LISTING_CONVERSION','DESCRIPTION_CORRECTION','BOUNDED_EXPLORATION','PROMOTION')
     OR (NEW.action_kind='LISTING_DESCRIPTION_CHANGE' AND purpose='PROMOTION')
     OR (NEW.action_kind='LISTING_PROMOTION_ACTION' AND purpose NOT IN ('PROMOTION','BOUNDED_EXPLORATION'))
     OR (NEW.proposed_parameters ? 'restoresCommandId' AND purpose IS DISTINCT FROM 'DESCRIPTION_CORRECTION')
     OR (purpose='BOUNDED_EXPLORATION' AND NEW.proposed_parameters->>'executionPath' IS DISTINCT FROM 'MANUAL'))) THEN
     RAISE EXCEPTION 'Listing purpose does not match its declared path and action' USING ERRCODE='23514';
   END IF;
 ELSE
   SELECT r.proposed_parameters->>'purposeCode' INTO purpose FROM ops.recommendation r WHERE r.id=NEW.recommendation_id;
   SELECT p.purpose_code INTO package_purpose FROM core.lc_calibration_package p WHERE p.id=NEW.calibration_package_id;
   IF purpose IS NOT NULL AND NEW.calibration_package_id IS NOT NULL AND purpose IS DISTINCT FROM package_purpose THEN
     RAISE EXCEPTION 'Listing Action must use its declared purpose calibration' USING ERRCODE='23514';
   END IF;
   IF NEW.restores_command_id IS NOT NULL AND purpose IS DISTINCT FROM 'DESCRIPTION_CORRECTION' THEN
     RAISE EXCEPTION 'an exact restoration is a new description-correction action' USING ERRCODE='MO094';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER lc_recommendation_declared_purpose BEFORE INSERT OR UPDATE ON ops.recommendation
FOR EACH ROW WHEN (NEW.action_kind IN ('LISTING_DESCRIPTION_CHANGE','LISTING_PROMOTION_ACTION'))
EXECUTE FUNCTION ops.guard_lc_declared_purpose();
CREATE TRIGGER lc_action_declared_purpose BEFORE INSERT OR UPDATE ON ops.lc_action
FOR EACH ROW EXECUTE FUNCTION ops.guard_lc_declared_purpose();
REVOKE ALL ON FUNCTION ops.guard_lc_declared_purpose() FROM PUBLIC;
