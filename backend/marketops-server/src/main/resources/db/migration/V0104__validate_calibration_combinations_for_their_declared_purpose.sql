-- Root 011: package completeness follows its stated purpose, not all catalog rows.
-- Values remain Owner accepted. This migration supplies no values or execution authority.
CREATE FUNCTION core.lc_calibration_required_categories(p_purpose text) RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT CASE WHEN p_purpose NOT IN ('LISTING_CONVERSION','DESCRIPTION_CORRECTION','BOUNDED_EXPLORATION','PROMOTION')
   OR p_purpose IS NULL THEN NULL ELSE ARRAY(
 SELECT code FROM unnest(ARRAY[
   'MATERIAL_IMPROVEMENT_BOUND','NON_WORSENING_PROFIT_BOUND','NON_WORSENING_RETURN_BOUND','CRITICAL_GROUP_RULE',
   'DEMAND_SCENARIO_SET','FRESHNESS_RULE','RESPONSIBILITY_SLO','RESPONSIBILITY_COVERAGE',
   'ORDINARY_TRIGGER_CONTENT','MATERIAL_TRIGGER_CONTENT','ORDINARY_TRIGGER_EXPOSURE','MATERIAL_TRIGGER_EXPOSURE',
   'APPROVAL_VALIDITY','REPRESENTATION_EQUIVALENCE_RULE','ALLOWANCE_AXES','ALLOWANCE_RESERVE',
   'FORMAL_NODES','STOP_RULE','CROSS_PERIOD_WINDOW','DESCRIPTION_LENGTH_RULE']) WITH ORDINALITY c(code,position)
 WHERE NOT (p_purpose='LISTING_CONVERSION' AND code='DEMAND_SCENARIO_SET')
   AND NOT (p_purpose='PROMOTION' AND code IN ('REPRESENTATION_EQUIVALENCE_RULE','DESCRIPTION_LENGTH_RULE'))
   AND NOT (p_purpose='DESCRIPTION_CORRECTION' AND code IN
       ('MATERIAL_IMPROVEMENT_BOUND','DEMAND_SCENARIO_SET','FORMAL_NODES','STOP_RULE'))
   AND NOT (p_purpose='BOUNDED_EXPLORATION' AND code IN ('MATERIAL_IMPROVEMENT_BOUND','FORMAL_NODES'))
 ORDER BY position) END
$$;
REVOKE ALL ON FUNCTION core.lc_calibration_required_categories(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_calibration_required_categories(text) TO marketops_app;

CREATE OR REPLACE FUNCTION core.lc_calibration_package_failures(p_package uuid) RETURNS text[]
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 WITH required AS (SELECT core.lc_calibration_required_categories(p.purpose_code) codes
   FROM core.lc_calibration_package p WHERE p.id=p_package)
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM required WHERE codes IS NOT NULL)
   THEN ARRAY['CALIBRATION_PURPOSE_UNRESOLVED'] ELSE
   ARRAY(SELECT code FROM required,unnest(codes) WITH ORDINALITY c(code,position)
     WHERE NOT EXISTS(SELECT 1 FROM core.lc_calibration_value v WHERE v.package_id=p_package AND v.category_code=c.code)
     ORDER BY position) END
$$;
