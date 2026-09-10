-- The accepted calibration may explicitly require no cross-period tail. This
-- removes the old universal positive-tail requirement without editing any old
-- plan, package, decision or evidence. Missing calibration is still unresolved.
ALTER TABLE ops.lc_evaluation_plan DROP CONSTRAINT lc_evaluation_plan_window_ck;
ALTER TABLE ops.lc_evaluation_plan ADD CONSTRAINT lc_evaluation_plan_window_ck
    CHECK (cross_period_window_days BETWEEN 0 AND 3660);

-- Unknown/missing dimensions cannot prove safety. A known failure remains a
-- failure even when another dimension is not yet qualified.
CREATE OR REPLACE FUNCTION ops.lc_protection_verdict_of(p_vector jsonb)
RETURNS text
LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF p_vector IS NULL OR jsonb_typeof(p_vector) <> 'object' THEN
        RETURN 'UNDETERMINED';
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_each(p_vector) e WHERE e.value = '"FAIL"'::jsonb) THEN
        RETURN 'FAIL';
    END IF;
    IF NOT p_vector ?& ARRAY['DIRECT_CONTRIBUTION_PROFIT', 'LINKED_SCOPE_PROFIT',
            'OVERALL_RETURN_RATE', 'CRITICAL_VARIANT_RETURN', 'SUPPLY_COVERAGE'] THEN
        RETURN 'UNDETERMINED';
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_each(p_vector) e WHERE e.value IS DISTINCT FROM '"PASS"'::jsonb) THEN
        RETURN 'UNDETERMINED';
    END IF;
    RETURN 'PASS';
END
$$;
