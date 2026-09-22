-- No invented qualification for historical results. New execution records
-- retain exact method/window gaps and the frozen plan they consumed.
ALTER TABLE ops.lc_node_result ADD COLUMN evaluation_evidence jsonb;
ALTER TABLE ops.lc_node_result ADD CONSTRAINT lc_node_result_evaluation_evidence_ck
    CHECK (evaluation_evidence IS NULL OR jsonb_typeof(evaluation_evidence)='object');

CREATE FUNCTION ops.lc_node_evidence_matches_plan()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp
AS $$
DECLARE
    plan ops.lc_evaluation_plan%ROWTYPE;
BEGIN
    SELECT * INTO STRICT plan FROM ops.lc_evaluation_plan WHERE id=NEW.plan_id;
    IF NEW.evaluation_evidence IS NULL OR
       NEW.evaluation_evidence->>'planDigest' IS DISTINCT FROM plan.plan_digest OR
       NEW.evaluation_evidence->>'nodeCode' IS DISTINCT FROM NEW.node_code OR
       NEW.evaluation_evidence->>'requestedStage' IS DISTINCT FROM NEW.stage OR
       jsonb_typeof(NEW.evaluation_evidence->'qualificationGaps') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'node evidence must retain its exact frozen plan, node, stage and qualification gaps'
            USING ERRCODE='MO093';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_node_evidence_matches_plan() FROM PUBLIC;
CREATE TRIGGER lc_node_evidence_matches_plan BEFORE INSERT ON ops.lc_node_result
    FOR EACH ROW EXECUTE FUNCTION ops.lc_node_evidence_matches_plan();
