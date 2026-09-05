-- Finance review is a linked Shared WorkTask responsibility. The canonical
-- observation remains the only authority deciding an advertising outcome.
CREATE FUNCTION ops.ad_settled_review_context(p_observation uuid)
RETURNS TABLE(organization_id uuid,case_id uuid,ad_native_object_id uuid,store_id uuid,
    outcome_baseline_id uuid,affected_set_digest text,product_variant_ids uuid[],
    action_id uuid,action_kind text,primary_task_id uuid,recommendation_id uuid,
    observed_at timestamptz,reason_code text,correlation_id text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,mart,pg_temp AS $$
 SELECT o.organization_id,k.id,k.ad_native_object_id,k.store_id,b.id,b.affected_set_digest,b.product_variant_ids,
    coalesce(o.command_id,o.manual_packet_id),CASE WHEN o.command_id IS NULL THEN 'MANUAL_PACKET' ELSE 'COMMAND' END,
    responsibility.task_id,responsibility.recommendation_id,o.evaluated_at,
    CASE WHEN o.verdict='REGRESSED' THEN 'SETTLED_REGRESSION' ELSE 'SETTLED_CONTRADICTS_OPERATIONAL_SUCCESS' END,
    o.correlation_id
 FROM ops.ad_outcome_observation o JOIN ops.ad_outcome_axes axes ON axes.observation_id=o.id
 JOIN ops.ad_outcome_baseline b ON b.id=axes.outcome_baseline_id AND b.organization_id=o.organization_id
 LEFT JOIN ops.ad_bid_command command ON command.id=o.command_id AND command.outcome_baseline_id=b.id
 LEFT JOIN ops.ad_manual_execution_packet packet ON packet.id=o.manual_packet_id AND packet.outcome_baseline_id=b.id
 LEFT JOIN ops.ad_bid_candidate candidate ON candidate.id=b.candidate_id
 LEFT JOIN ops.ad_manual_proposal proposal ON proposal.id=b.manual_proposal_id
 JOIN mart.ad_case k ON k.id=coalesce(candidate.case_id,proposal.case_id) AND k.organization_id=o.organization_id
 JOIN ops.ad_case_responsibility responsibility ON responsibility.case_id=k.id
 WHERE o.id=p_observation AND o.outcome_stage IN('SETTLED','SETTLED_REVISED')
   AND (command.id IS NOT NULL OR packet.id IS NOT NULL)
   AND b.ad_native_object_id=o.ad_native_object_id AND b.affected_set_digest=o.affected_set_digest
   AND ops.ad_outcome_baseline_is_attested(b.id)
   AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation next WHERE next.supersedes_observation_id=o.id)
   AND (o.verdict='REGRESSED' OR (
      o.verdict='UNCHANGED' AND axes.dual_axis_verdict='NO_MATERIAL_IMPROVEMENT'
      AND EXISTS(SELECT 1 FROM ops.ad_outcome_observation operational
       JOIN ops.ad_outcome_axes operational_axes ON operational_axes.observation_id=operational.id
       WHERE operational.command_id IS NOT DISTINCT FROM o.command_id
         AND operational.manual_packet_id IS NOT DISTINCT FROM o.manual_packet_id
         AND operational_axes.outcome_baseline_id=b.id
         AND operational.outcome_stage IN('RETAINED','RETAINED_REVISED')
         AND operational.evaluated_at<=o.evaluated_at AND operational.verdict='IMPROVED'
         AND operational_axes.dual_axis_verdict='VERIFIED_EFFICIENCY_SUCCESS'
         AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation next WHERE next.supersedes_observation_id=operational.id))))
$$;
REVOKE ALL ON FUNCTION ops.ad_settled_review_context(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_settled_review_context(uuid) TO marketops_app;

CREATE TABLE ops.ad_outcome_review_responsibility (
    task_id uuid PRIMARY KEY REFERENCES ops.work_task(id),
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    case_id uuid NOT NULL REFERENCES mart.ad_case(id),
    primary_task_id uuid NOT NULL REFERENCES ops.work_task(id),
    outcome_baseline_id uuid NOT NULL REFERENCES ops.ad_outcome_baseline(id),
    action_id uuid NOT NULL,
    action_kind text NOT NULL CHECK(action_kind IN('COMMAND','MANUAL_PACKET')),
    required_role_code text NOT NULL DEFAULT 'FINANCE_ANALYST' CHECK(required_role_code='FINANCE_ANALYST'),
    first_observation_id uuid NOT NULL REFERENCES ops.ad_outcome_observation(id),
    first_raised_at timestamptz NOT NULL,
    CONSTRAINT ad_review_task_distinct_ck CHECK(task_id<>primary_task_id),
    UNIQUE(action_kind,action_id,required_role_code)
);
CREATE TABLE ops.ad_outcome_review_observation (
    task_id uuid NOT NULL REFERENCES ops.ad_outcome_review_responsibility(task_id),
    observation_id uuid NOT NULL REFERENCES ops.ad_outcome_observation(id),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY(task_id,observation_id)
);
CREATE FUNCTION ops.validate_ad_outcome_review_binding() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE context record; binding ops.ad_outcome_review_responsibility%ROWTYPE; observation uuid;
BEGIN
 IF TG_TABLE_NAME='ad_outcome_review_responsibility' THEN observation:=NEW.first_observation_id;
 ELSE observation:=NEW.observation_id; END IF;
 SELECT * INTO context FROM ops.ad_settled_review_context(observation);
 IF NOT FOUND THEN RAISE EXCEPTION 'current canonical Settled contradiction required' USING ERRCODE='MO099'; END IF;
 IF TG_TABLE_NAME='ad_outcome_review_responsibility' THEN
  IF NEW.organization_id<>context.organization_id OR NEW.case_id<>context.case_id
    OR NEW.primary_task_id<>context.primary_task_id OR NEW.outcome_baseline_id<>context.outcome_baseline_id
    OR NEW.action_id<>context.action_id OR NEW.action_kind<>context.action_kind
    OR NEW.first_raised_at<>context.observed_at
    OR NOT EXISTS(SELECT 1 FROM ops.work_task t WHERE t.id=NEW.task_id
      AND t.organization_id=context.organization_id AND t.recommendation_id=context.recommendation_id) THEN
   RAISE EXCEPTION 'review must bind the same action, case, scope and Shared Task' USING ERRCODE='MO099'; END IF;
 ELSE
  SELECT * INTO binding FROM ops.ad_outcome_review_responsibility WHERE task_id=NEW.task_id;
  IF binding.action_id IS DISTINCT FROM context.action_id OR binding.action_kind IS DISTINCT FROM context.action_kind
    OR binding.outcome_baseline_id IS DISTINCT FROM context.outcome_baseline_id THEN
   RAISE EXCEPTION 'review revision must retain its exact action lineage' USING ERRCODE='MO099'; END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.validate_ad_outcome_review_binding() FROM PUBLIC;
CREATE TRIGGER ad_outcome_review_binding BEFORE INSERT ON ops.ad_outcome_review_responsibility
FOR EACH ROW EXECUTE FUNCTION ops.validate_ad_outcome_review_binding();
CREATE TRIGGER ad_outcome_review_observation_binding BEFORE INSERT ON ops.ad_outcome_review_observation
FOR EACH ROW EXECUTE FUNCTION ops.validate_ad_outcome_review_binding();
CREATE TRIGGER ad_outcome_review_binding_immutable BEFORE UPDATE OR DELETE ON ops.ad_outcome_review_responsibility
FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
CREATE TRIGGER ad_outcome_review_observation_immutable BEFORE UPDATE OR DELETE ON ops.ad_outcome_review_observation
FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
REVOKE ALL ON ops.ad_outcome_review_responsibility,ops.ad_outcome_review_observation FROM PUBLIC,marketops_app;
GRANT SELECT,INSERT ON ops.ad_outcome_review_responsibility,ops.ad_outcome_review_observation TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES('ops','ad_outcome_review_responsibility','NO_ROUTE',NULL,'Shared Task binding for canonical Settled Finance review'),
      ('ops','ad_outcome_review_observation','NO_ROUTE',NULL,'append-only canonical observation links on one Finance task');
