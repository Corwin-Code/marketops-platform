-- A formal result's bound is a signed improvement difference produced by the
-- frozen comparison. The absolute retained-visit ratio remains a separate fact.
ALTER TABLE ops.lc_node_result DROP CONSTRAINT lc_node_result_ratio_ck;
ALTER TABLE ops.lc_node_result ADD CONSTRAINT lc_node_result_ratio_ck CHECK (
    (primary_ratio IS NULL OR (primary_ratio>=0 AND primary_ratio<=1))
    AND (conservative_bound IS NULL OR (conservative_bound>=-1 AND conservative_bound<=1)));

COMMENT ON COLUMN ops.lc_node_result.primary_ratio IS
    'Absolute retained-purchase visit ratio from the exact target measurement.';
COMMENT ON COLUMN ops.lc_node_result.conservative_bound IS
    'Qualified lower bound for target-minus-reference improvement under the frozen method; signed.';

CREATE OR REPLACE FUNCTION ops.lc_node_evidence_matches_plan()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp
AS $$
DECLARE
    plan ops.lc_evaluation_plan%ROWTYPE;
    node jsonb;
    traffic jsonb;
    measured mart.lc_conversion_measurement%ROWTYPE;
    target_text text;
    lower_text text;
    lower_value numeric;
    evidence_from timestamptz;
    evidence_to timestamptz;
    expected_stop boolean;
BEGIN
    SELECT * INTO STRICT plan FROM ops.lc_evaluation_plan WHERE id=NEW.plan_id;
    SELECT entry INTO node
      FROM jsonb_array_elements(plan.formal_nodes) AS nodes(entry)
     WHERE entry->>'nodeCode'=NEW.node_code;
    IF NEW.evaluation_evidence IS NULL OR
       NEW.evaluation_evidence->>'planDigest' IS DISTINCT FROM plan.plan_digest OR
       NEW.evaluation_evidence->>'nodeCode' IS DISTINCT FROM NEW.node_code OR
       NEW.evaluation_evidence->>'requestedStage' IS DISTINCT FROM NEW.stage OR
       jsonb_typeof(NEW.evaluation_evidence->'qualificationGaps') IS DISTINCT FROM 'array' OR
       node IS NULL THEN
        RAISE EXCEPTION 'node evidence must retain its exact frozen plan, node, stage and qualification gaps'
            USING ERRCODE='MO093';
    END IF;

    IF node->>'method'='EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1' THEN
        traffic:=NEW.evaluation_evidence->'formalTrafficComparison';
        IF jsonb_typeof(traffic) IS DISTINCT FROM 'object' OR
           traffic->>'method' IS DISTINCT FROM node->>'method' OR
           traffic->>'referenceMeasurementId' IS DISTINCT FROM node#>>'{comparisonReference,referenceMeasurementId}' OR
           NEW.accepted_threshold IS DISTINCT FROM (node->>'threshold')::numeric OR
           NEW.maturity_reached IS DISTINCT FROM
             coalesce((NEW.evaluation_evidence->>'nodeWindowQualified')::boolean,false) THEN
            RAISE EXCEPTION 'formal outcome must consume the exact frozen method, threshold, reference and node admission'
                USING ERRCODE='MO093';
        END IF;

        target_text:=traffic->>'targetMeasurementId';
        IF NEW.measurement_id IS NULL THEN
            IF target_text IS NOT NULL OR NEW.primary_ratio IS NOT NULL OR NEW.conservative_bound IS NOT NULL
                    OR NEW.source_time IS NOT NULL OR NEW.maturity_reached THEN
                RAISE EXCEPTION 'an absent target measurement cannot carry measured facts or a formal bound'
                    USING ERRCODE='MO093';
            END IF;
        ELSE
            BEGIN
                IF target_text IS NULL OR target_text::uuid IS DISTINCT FROM NEW.measurement_id THEN
                    RAISE EXCEPTION 'formal evidence must name the inserted target measurement' USING ERRCODE='MO093';
                END IF;
            EXCEPTION WHEN invalid_text_representation THEN
                RAISE EXCEPTION 'formal evidence contains an invalid target measurement identity' USING ERRCODE='MO093';
            END;
            SELECT * INTO STRICT measured FROM mart.lc_conversion_measurement WHERE id=NEW.measurement_id;
            IF measured.organization_id IS DISTINCT FROM NEW.organization_id OR
               NOT EXISTS (SELECT 1 FROM ops.lc_action action
                 WHERE action.id=plan.action_id AND action.organization_id=NEW.organization_id
                   AND action.platform_listing_id=measured.platform_listing_id) OR
               NEW.primary_ratio IS DISTINCT FROM measured.primary_ratio OR
               NEW.source_time IS DISTINCT FROM measured.source_time OR
               NEW.evaluation_evidence->>'measurementId' IS DISTINCT FROM target_text THEN
                RAISE EXCEPTION 'formal result measurement facts or scope do not match the canonical measurement'
                    USING ERRCODE='MO093';
            END IF;
            BEGIN
                evidence_from:=(NEW.evaluation_evidence->>'frozenWindowStart')::timestamptz;
                evidence_to:=(NEW.evaluation_evidence->>'frozenWindowEnd')::timestamptz;
            EXCEPTION WHEN others THEN
                RAISE EXCEPTION 'formal evidence contains an invalid frozen window' USING ERRCODE='MO093';
            END;
            IF measured.window_start IS DISTINCT FROM evidence_from OR measured.window_end IS DISTINCT FROM evidence_to OR
               measured.retention_window_days IS DISTINCT FROM (node->>'maturityDays')::integer THEN
                RAISE EXCEPTION 'formal result measurement is outside the exact frozen node window'
                    USING ERRCODE='MO093';
            END IF;
        END IF;

        lower_text:=traffic->>'lowerDifference';
        IF lower_text IS NULL THEN
            IF NEW.conservative_bound IS NOT NULL THEN
                RAISE EXCEPTION 'an unqualified comparison cannot carry a conservative bound' USING ERRCODE='MO093';
            END IF;
        ELSE
            BEGIN
                lower_value:=lower_text::numeric;
            EXCEPTION WHEN others THEN
                RAISE EXCEPTION 'formal evidence contains an invalid lower improvement bound' USING ERRCODE='MO093';
            END;
            IF traffic->>'state' IS DISTINCT FROM 'QUALIFIED_FORMAL_COMPARISON' OR
               NEW.conservative_bound IS DISTINCT FROM lower_value THEN
                RAISE EXCEPTION 'the result bound must equal the qualified frozen-comparison lower bound'
                    USING ERRCODE='MO093';
            END IF;
        END IF;
        expected_stop:=coalesce(NEW.evaluation_evidence#>>'{futility,state}'='TRIGGERED',false);
        IF NEW.stop_triggered IS DISTINCT FROM expected_stop THEN
            RAISE EXCEPTION 'stop state must come from the frozen qualified futility rule' USING ERRCODE='MO093';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_node_evidence_matches_plan() FROM PUBLIC;
