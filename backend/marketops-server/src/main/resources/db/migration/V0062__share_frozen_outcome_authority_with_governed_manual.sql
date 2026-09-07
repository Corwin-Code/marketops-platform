-- One evaluation authority for API and governed human actions. Manual actions
-- never acquire a Provider command merely to participate in outcome evidence.
ALTER TABLE ops.ad_outcome_baseline ADD CONSTRAINT ad_outcome_manual_proposal_fk
    FOREIGN KEY(manual_proposal_id) REFERENCES ops.ad_manual_proposal(id);
ALTER TABLE ops.ad_outcome_observation ALTER COLUMN command_id DROP NOT NULL;
ALTER TABLE ops.ad_outcome_observation ADD COLUMN manual_packet_id uuid REFERENCES ops.ad_manual_execution_packet(id);
ALTER TABLE ops.ad_outcome_observation ADD CONSTRAINT ad_outcome_one_action_anchor_ck
    CHECK((command_id IS NULL)<>(manual_packet_id IS NULL));
ALTER TABLE ops.ad_outcome_observation ADD CONSTRAINT ad_manual_outcome_revision_uq
    UNIQUE(manual_packet_id,outcome_stage,revision_no);
CREATE INDEX ad_outcome_manual_packet_ix ON ops.ad_outcome_observation(manual_packet_id,evaluated_at DESC)
    WHERE manual_packet_id IS NOT NULL;

CREATE OR REPLACE FUNCTION ops.validate_frozen_ad_outcome_observation() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
DECLARE baseline ops.ad_outcome_baseline%ROWTYPE; frozen_stage ops.ad_outcome_stage_baseline%ROWTYPE;
    prior ops.ad_outcome_observation%ROWTYPE; landed timestamptz; stage_code text;
BEGIN
    IF NEW.command_id IS NOT NULL THEN
        SELECT b.* INTO baseline FROM ops.ad_bid_command c JOIN ops.ad_outcome_baseline b ON b.id=c.outcome_baseline_id
            WHERE c.id=NEW.command_id AND c.organization_id=NEW.organization_id;
        SELECT min(observed_at) INTO landed FROM ops.ad_bid_command_readback
            WHERE command_id=NEW.command_id AND match_state='MATCHES_TARGET';
    ELSE
        SELECT b.* INTO baseline FROM ops.ad_manual_execution_packet p JOIN ops.ad_outcome_baseline b ON b.id=p.outcome_baseline_id
            WHERE p.id=NEW.manual_packet_id AND p.organization_id=NEW.organization_id
              AND b.manual_proposal_id=p.proposal_id AND b.prepared_at<=p.execution_started_at;
        SELECT proof.observed_at INTO landed FROM ops.ad_manual_execution_packet p
            JOIN ops.ad_manual_configuration_verification proof ON proof.id=p.current_proof_id
            WHERE p.id=NEW.manual_packet_id AND p.state='MANUAL_CONFIGURATION_VERIFIED' AND proof.proves_configuration;
    END IF;
    IF baseline.id IS NULL OR landed IS NULL OR baseline.ad_native_object_id<>NEW.ad_native_object_id
      OR baseline.affected_set_digest<>NEW.affected_set_digest OR baseline.outcome_policy_id<>NEW.outcome_policy_id
      OR baseline.outcome_policy_version<>NEW.outcome_policy_version THEN
        RAISE EXCEPTION 'observation must use the exact sealed pre-action baseline and proven action' USING ERRCODE='MO099';
    END IF;
    stage_code=replace(NEW.outcome_stage,'_REVISED','');
    SELECT * INTO frozen_stage FROM ops.ad_outcome_stage_baseline WHERE outcome_baseline_id=baseline.id AND stage=stage_code;
    IF frozen_stage.outcome_baseline_id IS NULL
      OR NEW.window_starts_at<>landed+make_interval(mins=>(baseline.plan_snapshot->>'observationStartsMinutes')::integer)
      OR NEW.window_ends_at<>NEW.window_starts_at+make_interval(hours=>frozen_stage.window_hours)
      OR (NEW.evaluated_at<NEW.window_ends_at AND NEW.verdict<>'NOT_YET_EVALUABLE')
      OR NEW.baseline_metric_state IS DISTINCT FROM frozen_stage.snapshot#>>'{profit,absoluteProfit,valueState}'
      OR NEW.baseline_metric_value IS DISTINCT FROM (frozen_stage.snapshot#>>'{profit,absoluteProfit,value}')::numeric THEN
        RAISE EXCEPTION 'observation must respect the frozen window and baseline value' USING ERRCODE='MO099';
    END IF;
    IF NEW.supersedes_observation_id IS NOT NULL THEN
        SELECT * INTO prior FROM ops.ad_outcome_observation WHERE id=NEW.supersedes_observation_id;
        IF NOT FOUND OR prior.command_id IS DISTINCT FROM NEW.command_id OR prior.manual_packet_id IS DISTINCT FROM NEW.manual_packet_id
          OR replace(prior.outcome_stage,'_REVISED','')<>stage_code OR prior.revision_no+1<>NEW.revision_no
          OR NEW.evaluated_at<prior.evaluated_at
          OR EXISTS(SELECT 1 FROM ops.ad_outcome_observation WHERE supersedes_observation_id=prior.id) THEN
            RAISE EXCEPTION 'revision must append to the exact same action and stage lineage' USING ERRCODE='MO099';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ops.ad_outcome_action_anchor_is_immutable() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF NEW.outcome_baseline_id IS DISTINCT FROM OLD.outcome_baseline_id THEN
        RAISE EXCEPTION 'a reviewed action cannot replace its frozen baseline' USING ERRCODE='MO099';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ad_command_outcome_anchor_immutable BEFORE UPDATE OF outcome_baseline_id ON ops.ad_bid_command
FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_action_anchor_is_immutable();
CREATE TRIGGER ad_manual_outcome_anchor_immutable BEFORE UPDATE OF outcome_baseline_id ON ops.ad_manual_execution_packet
FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_action_anchor_is_immutable();
