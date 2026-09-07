-- A value alone cannot certify the object, actual observation time or completeness.
-- Existing evidence rows remain immutable. New evidence is appended through the
-- same governed manual workflow and never creates a Provider command or Outcome.
-- Historical interventions can both become uncertain after a legitimate release.
-- Keep one unstarted instruction, preserve the old issue-time exclusion of other
-- unresolved packets, and let the unchanged shared reservation serialize execution.
DROP INDEX ops.ad_manual_execution_packet_live_uq;
CREATE UNIQUE INDEX ad_manual_execution_packet_live_uq ON ops.ad_manual_execution_packet(ad_native_object_id)
 WHERE execution_started_at IS NULL AND state IN('MANUAL_PACKET_ISSUED','ACTION_REPORTED_CONFIGURATION_UNVERIFIED','MANUAL_EXECUTION_UNCERTAIN');
CREATE FUNCTION ops.guard_ad_manual_packet_issue() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
BEGIN
 IF NEW.state='MANUAL_PACKET_ISSUED' AND NEW.execution_started_at IS NULL THEN
  PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(NEW.organization_id::text));
  IF EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet other
    WHERE other.ad_native_object_id=NEW.ad_native_object_id AND other.id<>NEW.id
     AND other.state IN('MANUAL_PACKET_ISSUED','ACTION_REPORTED_CONFIGURATION_UNVERIFIED','MANUAL_EXECUTION_UNCERTAIN')) THEN
   RAISE EXCEPTION 'another issued or unresolved manual packet prevents a new instruction' USING ERRCODE='23505';
  END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.guard_ad_manual_packet_issue() FROM PUBLIC,marketops_app;
CREATE TRIGGER ad_manual_packet_issue_guard BEFORE INSERT OR UPDATE OF state,ad_native_object_id,execution_started_at
 ON ops.ad_manual_execution_packet FOR EACH ROW EXECUTE FUNCTION ops.guard_ad_manual_packet_issue();

ALTER TABLE ops.ad_manual_configuration_verification
 ADD COLUMN independent_observation jsonb,
 ADD CONSTRAINT ad_manual_independent_observation_shape_ck
 CHECK(independent_observation IS NULL OR jsonb_typeof(independent_observation)='object');
ALTER TABLE ops.ad_manual_configuration_verification DROP CONSTRAINT ad_manual_configuration_verification_grade_ck;
ALTER TABLE ops.ad_manual_configuration_verification ADD CONSTRAINT ad_manual_configuration_verification_grade_ck
 CHECK(evidence_grade IN ('OFFICIAL_API_READBACK','OFFICIAL_CONFIGURATION_EXPORT',
   'INDEPENDENT_MANUAL_VERIFICATION','EXECUTOR_SELF_REPORT','UNVERIFIED_MANUAL_EVIDENCE'));
ALTER TABLE ops.ad_manual_configuration_verification DROP CONSTRAINT ad_manual_configuration_verification_independent_ck;
ALTER TABLE ops.ad_manual_configuration_verification ADD CONSTRAINT ad_manual_configuration_verification_independent_ck
 CHECK(evidence_grade NOT IN ('INDEPENDENT_MANUAL_VERIFICATION','UNVERIFIED_MANUAL_EVIDENCE')
   OR (verifier_user_id IS NOT NULL AND verifier_user_id<>executor_user_id));
-- NOT VALID preserves old immutable bare-value rows, while new inserts cannot
-- manufacture an independent grade without the actual observation envelope.
ALTER TABLE ops.ad_manual_configuration_verification ADD CONSTRAINT ad_manual_independent_envelope_required_ck
 CHECK(evidence_grade NOT IN ('INDEPENDENT_MANUAL_VERIFICATION','UNVERIFIED_MANUAL_EVIDENCE')
   OR independent_observation IS NOT NULL) NOT VALID;

-- Qualification is durable at observation/acceptance time. A later mature
-- Outcome does not re-age a lawful historical landing against today's clock.
-- Old bare-value independent history stays readable but grants no new authority.
CREATE FUNCTION ops.ad_manual_observation_is_qualified(p_observation uuid) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog,ops,core,pg_temp AS $$
 SELECT coalesce((SELECT v.proves_configuration AND v.conflict_state='NONE'
   AND v.observed_at>=packet.execution_started_at AND v.recorded_at>=v.observed_at
   AND (v.evidence_grade IN ('OFFICIAL_API_READBACK','OFFICIAL_CONFIGURATION_EXPORT')
       AND v.configuration_observation_id IS NOT NULL
     OR v.evidence_grade='INDEPENDENT_MANUAL_VERIFICATION'
       AND v.verifier_user_id IS NOT NULL AND v.verifier_user_id<>v.executor_user_id
       AND v.independent_observation->>'evidenceSource'='DIRECT_OFFICIAL_CONSOLE'
       AND v.independent_observation->>'completeness'='COMPLETE'
       AND v.independent_observation->'directObservationAttested'='true'::jsonb
       AND v.independent_observation->>'observedAt'=to_char(v.observed_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
       AND v.independent_observation->>'observedValue'=v.observed_value
       AND v.independent_observation->>'exactNativeObjectId'=packet.ad_native_object_id::text
       AND v.independent_observation->>'semanticProfileId'=packet.semantic_profile_id::text
       AND v.independent_observation->>'exactFieldPath'=v.observed_field_path
       AND v.observed_field_path=CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN 'targetBid'
         WHEN 'AD_BUDGET_CHANGE' THEN 'targetBudget' ELSE 'targetStatus' END
       AND v.independent_observation->>'evidenceReference'=v.evidence_reference
       AND length(btrim(v.evidence_reference)) BETWEEN 1 AND 512
       AND policy.organization_id=packet.organization_id AND policy.store_id=packet.store_id
       AND policy.semantic_profile_id=packet.semantic_profile_id AND policy.action_kind=packet.action_kind
       AND policy.verification_mode='INDEPENDENT_OR_OFFICIAL'
       AND packet.verification_plan->>'policyId'=policy.id::text
       AND packet.verification_plan->>'policyVersion'=policy.policy_version::text
       AND packet.authority_snapshot->'policy'=to_jsonb(policy)
       AND policy.approved_at<=v.observed_at AND policy.effective_from<=v.observed_at
       AND v.recorded_at<policy.effective_to
       AND v.recorded_at-v.observed_at<=make_interval(secs=>policy.configuration_max_age_seconds))
 FROM ops.ad_manual_configuration_verification v
 JOIN ops.ad_manual_execution_packet packet ON packet.id=v.packet_id AND packet.organization_id=v.organization_id
 LEFT JOIN core.ad_manual_policy policy ON policy.id=packet.manual_policy_id
 WHERE v.id=p_observation),false)
$$;
REVOKE ALL ON FUNCTION ops.ad_manual_observation_is_qualified(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_manual_observation_is_qualified(uuid) TO marketops_app;

-- These private helpers project append-only observations through one ordering
-- rule. An older proof cannot erase a later unknown, and an older weak report
-- cannot downgrade an already qualified newer direct/official observation.
CREATE FUNCTION ops.ad_manual_has_later_unresolved(p_packet uuid,p_observed timestamptz) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog,ops,core,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet packet
 WHERE packet.id=p_packet AND (
   EXISTS(SELECT 1 FROM core.ad_object_configuration_observation c
    WHERE c.organization_id=packet.organization_id AND c.ad_native_object_id=packet.ad_native_object_id
     AND c.observed_at>=p_observed AND (c.semantic_profile_id<>packet.semantic_profile_id
      OR CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN c.observed_bid_amount::text
        WHEN 'AD_BUDGET_CHANGE' THEN c.observed_budget_amount::text ELSE c.native_status_raw END IS NULL
      OR CASE WHEN packet.action_kind IN ('AD_BID_CHANGE','AD_BUDGET_CHANGE')
        THEN CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN c.observed_bid_amount ELSE c.observed_budget_amount END
          <>(packet.intended_state->>CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN 'targetBid' ELSE 'targetBudget' END)::numeric
        ELSE c.native_status_raw<>packet.intended_state->>'targetStatus' END))
   OR EXISTS(SELECT 1 FROM ops.ad_manual_configuration_verification v WHERE v.packet_id=packet.id
     AND v.evidence_grade<>'EXECUTOR_SELF_REPORT' AND v.observed_at>=p_observed
     AND (v.conflict_state<>'NONE' OR NOT ops.ad_manual_observation_is_qualified(v.id))
     AND NOT (v.evidence_grade='UNVERIFIED_MANUAL_EVIDENCE' AND EXISTS(
       SELECT 1 FROM ops.ad_manual_configuration_verification current
       WHERE current.id=packet.current_proof_id AND ops.ad_manual_observation_is_qualified(current.id)
        AND current.observed_at>=v.observed_at)))))
$$;
REVOKE ALL ON FUNCTION ops.ad_manual_has_later_unresolved(uuid,timestamptz) FROM PUBLIC,marketops_app;

-- Only this governed manual path may reopen its original reservation. It never
-- takes another intervention's hold or labels uncertainty an Outcome regression.
CREATE FUNCTION ops.reconcile_ad_manual_configuration_reservation(p_packet uuid,p_evidence text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE packet ops.ad_manual_execution_packet%ROWTYPE; held ops.ad_action_reservation%ROWTYPE;
 proven boolean;
BEGIN
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=p_packet;
 IF packet.id IS NULL OR packet.reservation_id IS NULL THEN
  RAISE EXCEPTION 'started manual reservation is required' USING ERRCODE='MO097'; END IF;
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(packet.organization_id::text));
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=p_packet FOR UPDATE;
 SELECT * INTO held FROM ops.ad_action_reservation WHERE id=packet.reservation_id FOR UPDATE;
 IF held.id IS NULL OR held.organization_id<>packet.organization_id
  OR held.intervention_kind<>'CONFIRMED_MANUAL_PACKET' OR held.intervention_reference_id<>packet.id THEN
  RAISE EXCEPTION 'exact original manual reservation is required' USING ERRCODE='MO097'; END IF;
 proven:=packet.state='MANUAL_CONFIGURATION_VERIFIED' AND ops.ad_manual_observation_is_qualified(packet.current_proof_id);
 IF held.state='ACTIVE' THEN
  UPDATE ops.ad_action_reservation SET configuration_resolved=proven,unknown_or_mismatch_open=NOT proven,
    version=version+1 WHERE id=held.id
    AND (configuration_resolved IS DISTINCT FROM proven OR unknown_or_mismatch_open IS DISTINCT FROM NOT proven);
 ELSIF NOT proven THEN
  IF NOT EXISTS(SELECT 1 FROM ops.ad_action_reservation other WHERE other.organization_id=held.organization_id
    AND other.id<>held.id AND other.state='ACTIVE'
    AND (other.ad_native_object_id=held.ad_native_object_id OR other.product_variant_ids && held.product_variant_ids)) THEN
   UPDATE ops.ad_action_reservation SET state='ACTIVE',released_at=NULL,release_reason=NULL,
     configuration_resolved=false,unknown_or_mismatch_open=true,early_observation_complete=false,
     version=version+1 WHERE id=held.id;
  ELSE
   -- Existing exact affected-set containment also covers intersecting variants.
   -- Its established trigger invalidates execution authority and marks in-flight
   -- work uncertain, while preserving every canonical observation and hold ID.
   IF NOT EXISTS(SELECT 1 FROM ops.ad_containment c WHERE c.organization_id=packet.organization_id
     AND c.containment_kind='EMERGENCY_ENTITY_HOLD' AND c.scope_kind='AFFECTED_SET'
     AND c.cause_class='EXECUTION_INTEGRITY' AND c.affected_set_digest=packet.affected_set_digest
     AND c.activated_by_trigger='AD_MANUAL_CONFIGURATION_UNCERTAIN' AND c.state<>'REENABLED') THEN
    INSERT INTO ops.ad_containment(id,organization_id,containment_kind,scope_kind,platform_code,store_id,
      ad_native_object_id,affected_set_digest,capability_code,cause_class,reason,evidence_reference,
      activated_by_trigger,activated_at,state,correlation_id,created_at,updated_at,review_owner_user_id)
    VALUES(gen_random_uuid(),packet.organization_id,'EMERGENCY_ENTITY_HOLD','AFFECTED_SET',packet.platform_code,packet.store_id,
      packet.ad_native_object_id,packet.affected_set_digest,'ad-bid-change','EXECUTION_INTEGRITY',
      'Later manual configuration uncertainty overlaps a newer intervention; preserve both factual histories',p_evidence,
      'AD_MANUAL_CONFIGURATION_UNCERTAIN',clock_timestamp(),'ACTIVE','manual:'||packet.id,
      clock_timestamp(),clock_timestamp(),packet.endorser_user_id);
   END IF;
  END IF;
 END IF;
END $$;
REVOKE ALL ON FUNCTION ops.reconcile_ad_manual_configuration_reservation(uuid,text) FROM PUBLIC,marketops_app;

CREATE FUNCTION ops.apply_ad_manual_observation(p_observation uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE packet ops.ad_manual_execution_packet%ROWTYPE; evidence ops.ad_manual_configuration_verification%ROWTYPE;
 current ops.ad_manual_configuration_verification%ROWTYPE; proven boolean;
BEGIN
 SELECT * INTO evidence FROM ops.ad_manual_configuration_verification WHERE id=p_observation;
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=evidence.packet_id;
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(packet.organization_id::text));
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=evidence.packet_id FOR UPDATE;
 SELECT * INTO current FROM ops.ad_manual_configuration_verification WHERE id=packet.current_proof_id
   AND ops.ad_manual_observation_is_qualified(id);
 IF current.id IS NOT NULL AND (evidence.observed_at<current.observed_at
   OR evidence.observed_at=current.observed_at AND evidence.evidence_grade='UNVERIFIED_MANUAL_EVIDENCE') THEN
  -- The new row is retained, with its true weaker grade. Only revision metadata
  -- advances; the newer current proof and reservation remain unchanged.
  UPDATE ops.ad_manual_execution_packet SET updated_at=evidence.recorded_at,version=version+1 WHERE id=packet.id;
  RETURN;
 END IF;
 proven:=ops.ad_manual_observation_is_qualified(evidence.id);
 UPDATE ops.ad_manual_execution_packet SET state=CASE WHEN proven THEN 'MANUAL_CONFIGURATION_VERIFIED'
   WHEN evidence.conflict_state<>'NONE' THEN 'MANUAL_EXECUTION_UNCERTAIN' ELSE 'ACTION_REPORTED_CONFIGURATION_UNVERIFIED' END,
   current_proof_id=CASE WHEN proven THEN evidence.id ELSE NULL END,updated_at=evidence.recorded_at,version=version+1
   WHERE id=packet.id;
 PERFORM ops.reconcile_ad_manual_configuration_reservation(packet.id,'manual-observation:'||evidence.id);
END $$;
REVOKE ALL ON FUNCTION ops.apply_ad_manual_observation(uuid) FROM PUBLIC,marketops_app;

-- The predecessor is retained for the unchanged trusted REPORT/OFFICIAL paths,
-- but is no longer callable by the application or PUBLIC. No legacy value-only
-- INDEPENDENT invocation can be used to bypass the new envelope.
ALTER FUNCTION ops.record_ad_manual_observation(uuid,uuid,bigint,text,text,uuid,text)
 RENAME TO record_ad_manual_observation_legacy;
REVOKE ALL ON FUNCTION ops.record_ad_manual_observation_legacy(uuid,uuid,bigint,text,text,uuid,text)
 FROM PUBLIC,marketops_app;
CREATE OR REPLACE FUNCTION ops.record_ad_manual_observation_legacy(p_id uuid,p_packet uuid,p_expected bigint,
 p_kind text,p_observed_value text,p_configuration uuid,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,iam,platform,raw,pg_temp AS $$
DECLARE packet ops.ad_manual_execution_packet%ROWTYPE; policy core.ad_manual_policy%ROWTYPE;
 g iam.ad_invocation_grant%ROWTYPE; cfg core.ad_object_configuration_observation%ROWTYPE;
 expected_value text; actual_value text; field_path text; grade text; observed timestamptz;
 proven boolean; conflicted boolean; verifier uuid;
BEGIN
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=p_packet;
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(packet.organization_id::text));
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=p_packet FOR UPDATE;
 IF p_kind IS NULL OR p_kind NOT IN ('REPORT','OFFICIAL') THEN RAISE EXCEPTION 'unknown manual observation workflow' USING ERRCODE='MO097'; END IF;
 g:=ops.consume_ad_control_invocation(p_proof,CASE WHEN p_kind='REPORT' THEN 'MANUAL_EXECUTION_REPORT' ELSE 'MANUAL_INDEPENDENT_VERIFY' END,p_packet,p_packet);
 IF packet.id IS NULL OR packet.version IS DISTINCT FROM p_expected OR g.organization_id<>packet.organization_id
 OR packet.execution_started_at IS NULL OR packet.executor_user_id IS NULL OR packet.reservation_id IS NULL
 OR packet.state NOT IN ('MANUAL_EXECUTION_IN_PROGRESS','ACTION_REPORTED_CONFIGURATION_UNVERIFIED',
   'MANUAL_CONFIGURATION_VERIFIED','MANUAL_EXECUTION_UNCERTAIN') THEN
   RAISE EXCEPTION 'an actual started manual intervention and current revision are required' USING ERRCODE='MO097'; END IF;
 SELECT * INTO policy FROM core.ad_manual_policy WHERE id=packet.manual_policy_id;
 field_path:=CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN 'targetBid' WHEN 'AD_BUDGET_CHANGE' THEN 'targetBudget' ELSE 'targetStatus' END;
 expected_value:=packet.intended_state->>field_path;
 IF p_kind='REPORT' THEN
   IF g.actor_user_id<>packet.executor_user_id OR packet.state='MANUAL_CONFIGURATION_VERIFIED'
    OR p_observed_value IS NOT NULL OR p_configuration IS NOT NULL
    OR NOT ops.ad_manual_actor_scoped(g.actor_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,
      'MARKETPLACE_OPERATOR','ADVERTISING_MANUAL_EXECUTE') THEN
     RAISE EXCEPTION 'only the scoped executor may report the issued exact action' USING ERRCODE='MO064'; END IF;
   grade:='EXECUTOR_SELF_REPORT'; actual_value:=expected_value; observed:=clock_timestamp(); verifier:=NULL;
 ELSE
   IF g.actor_user_id=packet.executor_user_id OR NOT (
      ops.ad_manual_actor_scoped(g.actor_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,'TECH_DATA','ADVERTISING_MANUAL_VERIFY')
      OR ops.ad_manual_actor_scoped(g.actor_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,'OPS_LEAD','ADVERTISING_MANUAL_VERIFY')) THEN
     RAISE EXCEPTION 'independent explicitly scoped verifier required' USING ERRCODE='MO064'; END IF;
   verifier:=g.actor_user_id;
     IF p_observed_value IS NOT NULL OR p_configuration IS NULL THEN
       RAISE EXCEPTION 'official verification requires a canonical observation identity only' USING ERRCODE='MO097'; END IF;
     SELECT c.* INTO cfg FROM core.ad_object_configuration_observation c
     JOIN core.fact_provenance provenance ON provenance.id=c.provenance_id AND provenance.organization_id=c.organization_id
     JOIN raw.raw_acquisition_observation observation ON observation.id=provenance.raw_observation_id AND observation.outcome_class='SUCCESS_BYTES'
     JOIN raw.raw_logical_unit unit ON unit.id=observation.logical_unit_id
     JOIN platform.ingestion_job job ON job.id=unit.job_id AND job.organization_id=c.organization_id
     JOIN core.store st ON st.id=packet.store_id AND st.marketplace_account_id=job.marketplace_account_id
     WHERE c.id=p_configuration AND c.organization_id=packet.organization_id AND c.ad_native_object_id=packet.ad_native_object_id
       AND c.semantic_profile_id=packet.semantic_profile_id AND job.platform_code=packet.platform_code
       AND provenance.source_kind='MARKETPLACE_RAW' AND provenance.source_time IS NOT NULL
       AND c.evidence_grade IN ('OFFICIAL_API_READBACK','OFFICIAL_CONFIGURATION_EXPORT')
       AND c.observed_at>=packet.execution_started_at AND c.observed_at<=clock_timestamp()
       AND c.observed_at>clock_timestamp()-make_interval(secs=>policy.configuration_max_age_seconds)
       AND NOT EXISTS(SELECT 1 FROM core.ad_object_configuration_observation n
         WHERE n.ad_native_object_id=c.ad_native_object_id AND (n.supersedes_observation_id=c.id OR n.observed_at>c.observed_at));
     IF cfg.id IS NULL THEN RAISE EXCEPTION 'trusted current official source observation is absent' USING ERRCODE='MO097'; END IF;
     grade:=cfg.evidence_grade; observed:=cfg.observed_at;
     actual_value:=CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN cfg.observed_bid_amount::text
       WHEN 'AD_BUDGET_CHANGE' THEN cfg.observed_budget_amount::text ELSE cfg.native_status_raw END;
     IF actual_value IS NULL THEN RAISE EXCEPTION 'official native field is unknown' USING ERRCODE='MO097'; END IF;
 END IF;
 conflicted:=(p_kind='OFFICIAL' AND ops.ad_manual_has_later_unresolved(packet.id,observed)) OR CASE WHEN packet.action_kind IN ('AD_BID_CHANGE','AD_BUDGET_CHANGE')
   THEN actual_value::numeric<>expected_value::numeric ELSE actual_value<>expected_value END;
 proven:=p_kind<>'REPORT' AND NOT conflicted;
 INSERT INTO ops.ad_manual_configuration_verification(id,organization_id,packet_id,evidence_grade,executor_user_id,
   verifier_user_id,observed_field_path,observed_value,observed_at,evidence_reference,conflict_state,proves_configuration,
   recorded_at,correlation_id,configuration_observation_id)
 VALUES(p_id,packet.organization_id,p_packet,grade,packet.executor_user_id,verifier,field_path,actual_value,observed,
   CASE WHEN p_configuration IS NULL THEN 'manual-attestation:'||p_id ELSE 'configuration-observation:'||p_configuration END,
   CASE WHEN conflicted THEN 'CONFLICTED' ELSE 'NONE' END,proven,clock_timestamp(),'manual:'||p_packet,p_configuration);
 PERFORM ops.apply_ad_manual_observation(p_id);
 -- Configuration proof is never an early sales observation or an Outcome.
 RETURN p_id;
END $$;
CREATE FUNCTION ops.record_ad_manual_observation(p_id uuid,p_packet uuid,p_expected bigint,
 p_kind text,p_observed_value text,p_configuration uuid,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
BEGIN
 IF p_kind IS NULL OR p_kind NOT IN ('REPORT','OFFICIAL') THEN
  RAISE EXCEPTION 'independent manual evidence requires the complete observation envelope' USING ERRCODE='MO097';
 END IF;
 RETURN ops.record_ad_manual_observation_legacy(p_id,p_packet,p_expected,p_kind,p_observed_value,p_configuration,p_proof);
END $$;
REVOKE ALL ON FUNCTION ops.record_ad_manual_observation(uuid,uuid,bigint,text,text,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_ad_manual_observation(uuid,uuid,bigint,text,text,uuid,text) TO marketops_app;

CREATE FUNCTION ops.record_ad_manual_independent_observation(p_id uuid,p_packet uuid,p_expected bigint,
 p_observation jsonb,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,iam,platform,pg_temp AS $$
DECLARE packet ops.ad_manual_execution_packet%ROWTYPE; policy core.ad_manual_policy%ROWTYPE;
 g iam.ad_invocation_grant%ROWTYPE; observed timestamptz; accepted timestamptz:=clock_timestamp();
 field_path text; actual_value text; expected_value text; grade text; proven boolean; conflicted boolean;
 later_unknown boolean; envelope jsonb;
BEGIN
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=p_packet;
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(packet.organization_id::text));
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=p_packet FOR UPDATE;
 g:=ops.consume_ad_control_invocation(p_proof,'MANUAL_INDEPENDENT_VERIFY',p_packet,p_packet);
 accepted:=clock_timestamp();
 IF packet.id IS NULL OR packet.version IS DISTINCT FROM p_expected OR g.organization_id<>packet.organization_id
  OR packet.execution_started_at IS NULL OR packet.executor_user_id IS NULL OR packet.reservation_id IS NULL
  OR packet.state NOT IN ('MANUAL_EXECUTION_IN_PROGRESS','ACTION_REPORTED_CONFIGURATION_UNVERIFIED',
    'MANUAL_CONFIGURATION_VERIFIED','MANUAL_EXECUTION_UNCERTAIN') THEN
  RAISE EXCEPTION 'an actual started manual intervention and current revision are required' USING ERRCODE='23514'; END IF;
 IF g.actor_user_id=packet.executor_user_id OR NOT (
    ops.ad_manual_actor_scoped(g.actor_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,'TECH_DATA','ADVERTISING_MANUAL_VERIFY')
    OR ops.ad_manual_actor_scoped(g.actor_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,'OPS_LEAD','ADVERTISING_MANUAL_VERIFY'))
   OR NOT EXISTS(SELECT 1 FROM core.ad_affected_set a WHERE a.id=packet.affected_set_id
     AND a.organization_id=packet.organization_id AND a.resolution_state='COMPLETE'
     AND a.affected_set_digest=packet.affected_set_digest AND cardinality(a.product_variant_ids)>0) THEN
  RAISE EXCEPTION 'independent explicitly scoped verifier and complete packet scope required' USING ERRCODE='MO064'; END IF;
 IF p_observation IS NULL OR jsonb_typeof(p_observation)<>'object' THEN
  RAISE EXCEPTION 'an actual observation object is required' USING ERRCODE='23514'; END IF;
 IF NOT p_observation ?& ARRAY['observedValue','observedAt','evidenceSource','completeness','exactNativeObjectId',
    'exactFieldPath','semanticProfileId','evidenceReference','directObservationAttested']
  OR EXISTS(SELECT 1 FROM jsonb_object_keys(p_observation) k WHERE k NOT IN ('observedValue','observedAt','evidenceSource',
    'completeness','exactNativeObjectId','exactFieldPath','semanticProfileId','evidenceReference','directObservationAttested'))
  OR jsonb_typeof(p_observation->'observedValue') IS DISTINCT FROM 'string'
  OR jsonb_typeof(p_observation->'observedAt') IS DISTINCT FROM 'string'
  OR (p_observation->>'observedAt') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$'
  OR jsonb_typeof(p_observation->'evidenceReference') IS DISTINCT FROM 'string'
  OR jsonb_typeof(p_observation->'directObservationAttested') IS DISTINCT FROM 'boolean'
  OR p_observation->>'evidenceSource' IS NULL OR p_observation->>'evidenceSource' NOT IN ('DIRECT_OFFICIAL_CONSOLE','SCREENSHOT')
  OR p_observation->>'completeness' IS NULL OR p_observation->>'completeness' NOT IN ('COMPLETE','INCOMPLETE') THEN
  RAISE EXCEPTION 'typed actual observation metadata is required; no completeness or time default exists' USING ERRCODE='23514'; END IF;
 BEGIN observed:=(p_observation->>'observedAt')::timestamptz;
 EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN
  RAISE EXCEPTION 'actual observation time is invalid' USING ERRCODE='23514'; END;
 SELECT * INTO policy FROM core.ad_manual_policy WHERE id=packet.manual_policy_id;
 field_path:=CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN 'targetBid'
   WHEN 'AD_BUDGET_CHANGE' THEN 'targetBudget' ELSE 'targetStatus' END;
 actual_value:=p_observation->>'observedValue'; expected_value:=packet.intended_state->>field_path;
 IF observed IS NULL OR NOT isfinite(observed) OR observed<packet.execution_started_at OR observed>accepted
  OR policy.id IS NULL OR policy.organization_id<>packet.organization_id OR policy.store_id<>packet.store_id
  OR policy.semantic_profile_id<>packet.semantic_profile_id OR policy.action_kind<>packet.action_kind
  OR policy.approved_at>observed OR policy.effective_from>observed OR policy.effective_to<=accepted
  OR accepted-observed>make_interval(secs=>policy.configuration_max_age_seconds)
  OR packet.verification_plan->>'policyId' IS DISTINCT FROM policy.id::text
  OR packet.verification_plan->>'policyVersion' IS DISTINCT FROM policy.policy_version::text
  OR packet.authority_snapshot->'policy' IS DISTINCT FROM to_jsonb(policy)
  OR p_observation->>'exactNativeObjectId' IS DISTINCT FROM packet.ad_native_object_id::text
  OR p_observation->>'semanticProfileId' IS DISTINCT FROM packet.semantic_profile_id::text
  OR p_observation->>'exactFieldPath' IS DISTINCT FROM field_path
  OR length(btrim(actual_value)) NOT BETWEEN 1 AND 128
  OR length(btrim(p_observation->>'evidenceReference')) NOT BETWEEN 1 AND 512
  OR expected_value IS NULL
  OR (packet.action_kind IN ('AD_BID_CHANGE','AD_BUDGET_CHANGE') AND actual_value !~ '^[0-9]+(\.[0-9]{1,4})?$')
  OR (packet.action_kind='AD_STATUS_CHANGE' AND actual_value !~ '^[A-Za-z0-9_ -]{1,128}$')
  OR NOT EXISTS(SELECT 1 FROM core.ad_native_object o JOIN platform.ad_semantic_profile s ON s.id=o.semantic_profile_id
    WHERE o.id=packet.ad_native_object_id AND o.organization_id=packet.organization_id AND o.store_id=packet.store_id
      AND o.platform_code=packet.platform_code AND o.semantic_profile_id=packet.semantic_profile_id
      AND to_jsonb(o)->'lineage_generation'=packet.authority_snapshot#>'{object,lineage_generation}'
      AND to_jsonb(o)->'native_object_key'=packet.authority_snapshot#>'{object,native_object_key}'
      AND to_jsonb(o)->'native_campaign_key'=packet.authority_snapshot#>'{object,native_campaign_key}'
      AND to_jsonb(o)->'native_object_kind'=packet.authority_snapshot#>'{object,native_object_kind}'
      AND to_jsonb(s)=packet.authority_snapshot->'semanticProfile'
      AND s.platform_code=packet.platform_code AND s.status='ACTIVE' AND s.created_at<=observed
      AND (s.effective_to IS NULL OR s.effective_to>accepted)) THEN
  RAISE EXCEPTION 'actual observation time, immutable evidence policy or exact packet metadata is not applicable' USING ERRCODE='23514'; END IF;
 -- A newer unknown/conflicting observation cannot be cleared by reporting an
 -- older favorable value. No caller timestamp is replaced with server time.
 later_unknown:=ops.ad_manual_has_later_unresolved(packet.id,observed);
 conflicted:=later_unknown OR CASE WHEN packet.action_kind IN ('AD_BID_CHANGE','AD_BUDGET_CHANGE')
   THEN actual_value::numeric<>expected_value::numeric ELSE actual_value<>expected_value END;
 proven:=p_observation->>'evidenceSource'='DIRECT_OFFICIAL_CONSOLE'
   AND p_observation->>'completeness'='COMPLETE' AND p_observation->'directObservationAttested'='true'::jsonb
   AND policy.verification_mode='INDEPENDENT_OR_OFFICIAL' AND NOT conflicted;
 grade:=CASE WHEN p_observation->>'evidenceSource'='DIRECT_OFFICIAL_CONSOLE'
   AND p_observation->>'completeness'='COMPLETE' AND p_observation->'directObservationAttested'='true'::jsonb
   AND policy.verification_mode='INDEPENDENT_OR_OFFICIAL' THEN 'INDEPENDENT_MANUAL_VERIFICATION'
   ELSE 'UNVERIFIED_MANUAL_EVIDENCE' END;
 envelope:=p_observation||jsonb_build_object('observedAt',to_char(observed AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
 INSERT INTO ops.ad_manual_configuration_verification(id,organization_id,packet_id,evidence_grade,executor_user_id,
   verifier_user_id,observed_field_path,observed_value,observed_at,evidence_reference,conflict_state,proves_configuration,
   recorded_at,correlation_id,independent_observation)
 VALUES(p_id,packet.organization_id,packet.id,grade,packet.executor_user_id,g.actor_user_id,field_path,actual_value,
   observed,p_observation->>'evidenceReference',CASE WHEN conflicted THEN 'CONFLICTED' ELSE 'NONE' END,proven,
   accepted,'manual:'||packet.id,envelope);
 PERFORM ops.apply_ad_manual_observation(p_id);
 RETURN p_id;
END $$;
REVOKE ALL ON FUNCTION ops.record_ad_manual_independent_observation(uuid,uuid,bigint,jsonb,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_ad_manual_independent_observation(uuid,uuid,bigint,jsonb,text) TO marketops_app;

-- A delayed canonical row remains canonical history, but only a same-time or
-- later mismatch can invalidate the current configuration proof. Reconciliation
-- cannot roll back that fact merely because its old reservation was released.
CREATE OR REPLACE FUNCTION ops.invalidate_manual_proof_on_later_configuration() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE packet ops.ad_manual_execution_packet%ROWTYPE; actual text; expected text;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(NEW.organization_id::text));
 FOR packet IN SELECT p.* FROM ops.ad_manual_execution_packet p
   LEFT JOIN ops.ad_manual_configuration_verification proof ON proof.id=p.current_proof_id
   WHERE p.organization_id=NEW.organization_id AND p.ad_native_object_id=NEW.ad_native_object_id
    AND p.execution_started_at IS NOT NULL AND p.state='MANUAL_CONFIGURATION_VERIFIED'
    AND NEW.observed_at>=coalesce(proof.observed_at,p.execution_started_at) FOR UPDATE OF p
 LOOP
  actual:=CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN NEW.observed_bid_amount::text
    WHEN 'AD_BUDGET_CHANGE' THEN NEW.observed_budget_amount::text ELSE NEW.native_status_raw END;
  expected:=packet.intended_state->>CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN 'targetBid'
    WHEN 'AD_BUDGET_CHANGE' THEN 'targetBudget' ELSE 'targetStatus' END;
  IF NEW.semantic_profile_id IS DISTINCT FROM packet.semantic_profile_id OR actual IS NULL
    OR (CASE WHEN packet.action_kind IN ('AD_BID_CHANGE','AD_BUDGET_CHANGE')
      THEN actual::numeric<>expected::numeric ELSE actual<>expected END) THEN
   UPDATE ops.ad_manual_execution_packet SET state='MANUAL_EXECUTION_UNCERTAIN',current_proof_id=NULL,
     updated_at=clock_timestamp(),version=version+1 WHERE id=packet.id;
   PERFORM ops.reconcile_ad_manual_configuration_reservation(packet.id,'configuration-observation:'||NEW.id);
  END IF;
 END LOOP;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.invalidate_manual_proof_on_later_configuration() FROM PUBLIC;

-- Preserve the existing shared authorities and all their unrelated checks.
-- Only the manual proof predicate is strengthened; historical rows are untouched.
CREATE OR REPLACE FUNCTION ops.ad_exposure_snapshot(p_org uuid,p_store uuid,p_direction text)
RETURNS jsonb LANGUAGE plpgsql STABLE
SET search_path=pg_catalog,ops,core,ledger,pg_temp AS $$
DECLARE e core.ad_exposure_envelope%ROWTYPE; reasons text[]:='{}'; all_reasons text[]:='{}'; found_envelope boolean:=false;
 envelopes jsonb:='[]';
 active_count integer; unresolved integer; cumulative numeric; associated numeric; associated_boundary_reports integer;
 sales_total numeric; affected_sales numeric; sales_known boolean; variants uuid[]; stores uuid[]; objects uuid[];
BEGIN
 FOR e IN SELECT env.* FROM core.ad_exposure_envelope env
 JOIN core.store subject_store ON subject_store.id=p_store
 JOIN core.marketplace_account acc ON acc.id=subject_store.marketplace_account_id
 WHERE env.organization_id=p_org AND env.status='ACTIVE'
 AND env.effective_from<=statement_timestamp() AND (env.effective_to IS NULL OR env.effective_to>statement_timestamp())
 AND (env.scope_kind='ORGANIZATION' OR env.scope_kind='PLATFORM' AND env.platform_code=acc.platform_code
      OR env.scope_kind='STORE' AND env.store_ref_id=p_store)
 LOOP
  found_envelope:=true; reasons:='{}'; associated:=NULL; associated_boundary_reports:=NULL; sales_total:=NULL; affected_sales:=NULL;
  SELECT array_agg(st.id) INTO stores FROM core.store st
   JOIN core.marketplace_account account ON account.id=st.marketplace_account_id
   WHERE st.organization_id=p_org AND (e.scope_kind='ORGANIZATION'
    OR e.scope_kind='PLATFORM' AND account.platform_code=e.platform_code
    OR e.scope_kind='STORE' AND st.id=e.store_ref_id);
  SELECT count(*),array_agg(DISTINCT r.ad_native_object_id) INTO active_count,objects
   FROM ops.ad_action_reservation r WHERE r.organization_id=p_org AND r.state='ACTIVE' AND r.store_id=ANY(stores);
  SELECT array_agg(DISTINCT v) INTO variants FROM ops.ad_action_reservation r
   CROSS JOIN LATERAL unnest(r.product_variant_ids) v
   WHERE r.organization_id=p_org AND r.state='ACTIVE' AND r.store_id=ANY(stores);
  IF active_count>e.max_active_interventions THEN reasons:=array_append(reasons,'ACTIVE_INTERVENTIONS'); END IF;
  IF p_direction<>'EXACT_PRIOR_BID_COMPENSATION' AND active_count>e.max_active_interventions-e.reserved_recovery_headroom_count
   THEN reasons:=array_append(reasons,'RECOVERY_HEADROOM'); END IF;
  SELECT count(*) INTO unresolved FROM ops.ad_action_reservation r
   WHERE r.organization_id=p_org AND r.store_id=ANY(stores) AND r.state='ACTIVE'
   AND (r.unknown_or_mismatch_open OR EXISTS(SELECT 1 FROM ops.ad_bid_command c
     WHERE c.reservation_id=r.id AND c.state IN ('EXECUTING','PLATFORM_PENDING','READBACK_PENDING',
       'UNKNOWN_REQUIRES_READBACK','READBACK_MISMATCH','LATER_CHANGE_OR_MISMATCH_INVESTIGATION','MANUAL_RESOLUTION','COMPENSATION_PENDING'))
    OR EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet packet WHERE packet.id=r.intervention_reference_id
     AND r.intervention_kind='CONFIRMED_MANUAL_PACKET' AND (packet.state IN('MANUAL_PACKET_ISSUED',
      'MANUAL_EXECUTION_IN_PROGRESS','ACTION_REPORTED_CONFIGURATION_UNVERIFIED','MANUAL_EXECUTION_UNCERTAIN')
      OR NOT ops.ad_manual_observation_is_qualified(packet.current_proof_id))));
  IF unresolved>e.max_unresolved_transmitted_writes THEN reasons:=array_append(reasons,'UNRESOLVED_TRANSMITTED_WRITES'); END IF;
  IF EXISTS(SELECT 1 FROM ops.ad_action_reservation r JOIN ops.ad_bid_candidate c ON c.id=r.intervention_reference_id
    WHERE r.organization_id=p_org AND r.store_id=ANY(stores)
    AND r.reserved_at>statement_timestamp()-make_interval(hours=>e.cumulative_window_hours)
    AND (c.currency_code<>e.currency_code OR c.bid_unit_code NOT IN ('CURRENCY_MAJOR','CURRENCY_MINOR'))) THEN
   reasons:=array_append(reasons,'CUMULATIVE_BID_CHANGE_UNRESOLVED'); END IF;
  -- Count each intervention once, whether or not its command has been created.
  SELECT coalesce(sum(abs(c.provider_normalized_amount-c.current_bid_amount) / CASE c.bid_unit_code WHEN 'CURRENCY_MINOR' THEN 100 ELSE 1 END),0) INTO cumulative
   FROM ops.ad_action_reservation r JOIN ops.ad_bid_candidate c ON c.id=r.intervention_reference_id
   WHERE r.organization_id=p_org AND r.store_id=ANY(stores)
   AND r.reserved_at>statement_timestamp()-make_interval(hours=>e.cumulative_window_hours);
  SELECT cumulative+coalesce(sum(abs((p.intended_state->>'targetBid')::numeric-c.observed_bid_amount) / CASE c.bid_unit_code WHEN 'CURRENCY_MINOR' THEN 100 ELSE 1 END),0)
   INTO cumulative FROM ops.ad_action_reservation r
   JOIN ops.ad_manual_execution_packet p ON p.id=r.intervention_reference_id
   JOIN core.ad_object_configuration_observation c ON c.id=p.observed_configuration_id
   WHERE r.organization_id=p_org AND r.store_id=ANY(stores)
   AND r.reserved_at>statement_timestamp()-make_interval(hours=>e.cumulative_window_hours)
   AND p.intended_state->>'targetBid' ~ '^[0-9]+(\.[0-9]+)?$';
  -- Exact restoration is another bid change inside the same reservation.
  -- Count it once across approval and dispatch, including a completed restore.
  SELECT cumulative+coalesce(sum(abs(c.target_bid_amount-c.prior_bid_amount)
    /CASE c.bid_unit_code WHEN 'CURRENCY_MINOR' THEN 100 ELSE 1 END),0) INTO cumulative
   FROM ops.ad_bid_command c WHERE c.organization_id=p_org AND c.store_id=ANY(stores)
   AND (EXISTS(SELECT 1 FROM ops.ad_compensation_authorization approved WHERE approved.command_id=c.id
     AND approved.approved_at>statement_timestamp()-make_interval(hours=>e.cumulative_window_hours))
    OR EXISTS(SELECT 1 FROM ops.ad_bid_command_attempt restore WHERE restore.command_id=c.id AND restore.purpose='RESTORE'
      AND restore.started_at>statement_timestamp()-make_interval(hours=>e.cumulative_window_hours)));
  IF cumulative>e.max_cumulative_bid_change_amount THEN reasons:=array_append(reasons,'CUMULATIVE_BID_CHANGE'); END IF;
  IF e.retained_window_days IS NULL OR e.measurement_window_hours IS NULL THEN
   reasons:=array_append(reasons,'EXPOSURE_MEASUREMENT_POLICY_ABSENT'); END IF;
  -- Official spend uses complete intersecting accepted reports once per native
  -- row. A report crossing the left measurement boundary contributes its whole
  -- amount conservatively: no prorating or subtraction invents a smaller spend.
  -- A period reaching into the future cannot prove a current official amount.
  IF EXISTS(SELECT 1 FROM ledger.ad_object_fact f WHERE f.ad_native_object_id=ANY(objects)
     AND f.recorded_at<=statement_timestamp() AND f.source_time<=statement_timestamp()
     AND f.period_start<statement_timestamp()
     AND f.period_end>statement_timestamp()-make_interval(hours=>e.measurement_window_hours)
     AND NOT EXISTS(SELECT 1 FROM ledger.ad_object_fact n WHERE n.supersedes_fact_id=f.id AND n.recorded_at<=statement_timestamp())
     AND (f.spend_amount IS NULL OR f.currency_code<>e.currency_code OR NOT f.report_window_complete OR f.correction_window_open
      OR f.period_end>statement_timestamp()))
   OR EXISTS(SELECT 1 FROM ledger.ad_object_fact first JOIN ledger.ad_object_fact second
     ON second.ad_native_object_id=first.ad_native_object_id AND second.id>first.id
     AND tstzrange(first.period_start,first.period_end,'[)') && tstzrange(second.period_start,second.period_end,'[)')
     WHERE first.ad_native_object_id=ANY(objects)
     AND first.recorded_at<=statement_timestamp() AND first.source_time<=statement_timestamp()
     AND second.recorded_at<=statement_timestamp() AND second.source_time<=statement_timestamp()
     AND first.period_start<statement_timestamp() AND second.period_start<statement_timestamp()
     AND first.period_end>statement_timestamp()-make_interval(hours=>e.measurement_window_hours)
     AND second.period_end>statement_timestamp()-make_interval(hours=>e.measurement_window_hours)
     AND NOT EXISTS(SELECT 1 FROM ledger.ad_object_fact n WHERE n.supersedes_fact_id IN(first.id,second.id) AND n.recorded_at<=statement_timestamp()))
   OR EXISTS(SELECT 1 FROM unnest(objects) object_id WHERE NOT EXISTS(
    SELECT 1 FROM ledger.ad_object_fact f WHERE f.ad_native_object_id=object_id
    AND f.recorded_at<=statement_timestamp() AND f.source_time<=statement_timestamp()
    AND f.period_start<statement_timestamp()
     AND f.period_end>statement_timestamp()-make_interval(hours=>e.measurement_window_hours))) THEN
    reasons:=array_append(reasons,'ASSOCIATED_SPEND_UNRESOLVED');
  ELSE
   SELECT coalesce(sum(f.spend_amount),0),count(*) FILTER(WHERE
      f.period_start<statement_timestamp()-make_interval(hours=>e.measurement_window_hours))
     INTO associated,associated_boundary_reports FROM ledger.ad_object_fact f
    WHERE f.organization_id=p_org AND f.ad_native_object_id=ANY(objects)
    AND f.recorded_at<=statement_timestamp() AND f.source_time<=statement_timestamp()
    AND f.period_start<statement_timestamp()
     AND f.period_end>statement_timestamp()-make_interval(hours=>e.measurement_window_hours)
    AND NOT EXISTS(SELECT 1 FROM ledger.ad_object_fact n WHERE n.supersedes_fact_id=f.id AND n.recorded_at<=statement_timestamp());
   IF associated>e.max_associated_spend_amount THEN reasons:=array_append(reasons,'ASSOCIATED_SPEND'); END IF;
  END IF;
  -- The envelope's company denominator and affected numerator use the same
  -- accepted Retained cohort and one exact measurement window. Coverage is
  -- required for every known company listing, including units without facts.
  WITH scope_units AS (
   SELECT variant.id listing_id,listing.store_id,listing.platform_code
   FROM core.platform_listing_variant variant
   JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id
   WHERE variant.organization_id=p_org AND listing.store_id=ANY(stores)
    AND variant.first_seen_at<=statement_timestamp()
    AND (variant.status='OBSERVED' OR EXISTS(SELECT 1 FROM ledger.sales_fact fact
     WHERE fact.platform_listing_variant_id=variant.id AND fact.sale_stage='RETAINED'
      AND fact.occurred_at>=statement_timestamp()-make_interval(hours=>e.measurement_window_hours)
      AND fact.occurred_at<statement_timestamp()))
  ), current_facts AS (
   SELECT fact.* FROM ledger.sales_fact fact
   JOIN core.fact_provenance accepted ON accepted.id=fact.provenance_id
   WHERE fact.organization_id=p_org AND fact.store_id=ANY(stores)
    AND fact.sale_stage='RETAINED' AND fact.retention_window_days=e.retained_window_days
    AND fact.occurred_at>=statement_timestamp()-make_interval(hours=>e.measurement_window_hours)
    AND fact.occurred_at<statement_timestamp() AND accepted.ingestion_time<=statement_timestamp()
    AND (accepted.source_time IS NULL OR accepted.source_time<=statement_timestamp())
    AND NOT EXISTS(SELECT 1 FROM ledger.sales_fact successor
     JOIN core.fact_provenance accepted_successor ON accepted_successor.id=successor.provenance_id
     WHERE successor.supersedes_fact_id=fact.id AND accepted_successor.ingestion_time<=statement_timestamp()
      AND (accepted_successor.source_time IS NULL OR accepted_successor.source_time<=statement_timestamp()))
  ), per_unit AS (
   SELECT unit.listing_id,profile.id profile_id,coverage.id coverage_id,
    profile.source_max_age_minutes,profile.accepted_fact_max_age_minutes,
    coverage.completed_coverage,coverage.retained_coverage,coverage.return_coverage,coverage.qc_coverage,
    coverage.completed_source_updated_at,coverage.retained_source_updated_at,
    coverage.return_source_updated_at,coverage.qc_source_updated_at,coverage.accepted_at,
    fact.count,fact.amount,fact.affected_amount,fact.valid,
    profile.provider_incident_blocks AND EXISTS(SELECT 1 FROM platform.ad_provider_incident incident
      WHERE incident.organization_id=p_org AND incident.platform_code=unit.platform_code
       AND (incident.store_id IS NULL OR incident.store_id=unit.store_id)
       AND incident.incident_open AND incident.observed_at<=statement_timestamp()
       AND (incident.valid_until IS NULL OR incident.valid_until>statement_timestamp())) incident_blocks
   FROM scope_units unit
   LEFT JOIN LATERAL (
    SELECT eligible.* FROM core.ad_freshness_profile eligible
    WHERE eligible.organization_id=p_org AND eligible.evidence_kind='COMPANY_RETAINED_SALE'
     AND eligible.decision_purpose='FINAL_RETAINED_SALES_OUTCOME' AND eligible.status='ACTIVE'
     AND eligible.effective_from<=statement_timestamp()
     AND (eligible.effective_to IS NULL OR eligible.effective_to>statement_timestamp())
     AND (eligible.scope_kind='ORGANIZATION' OR eligible.scope_kind='PLATFORM' AND eligible.platform_code=unit.platform_code
       OR eligible.scope_kind='STORE' AND eligible.store_ref_id=unit.store_id)
     AND NOT EXISTS(SELECT 1 FROM core.ad_freshness_profile other WHERE other.id<>eligible.id
      AND other.organization_id=p_org AND other.evidence_kind=eligible.evidence_kind AND other.decision_purpose=eligible.decision_purpose
      AND other.status='ACTIVE' AND other.effective_from<=statement_timestamp()
      AND (other.effective_to IS NULL OR other.effective_to>statement_timestamp())
      AND (other.scope_kind='ORGANIZATION' OR other.scope_kind='PLATFORM' AND other.platform_code=unit.platform_code
        OR other.scope_kind='STORE' AND other.store_ref_id=unit.store_id)
      AND CASE other.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END
       <=CASE eligible.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END)
   ) profile ON true
   LEFT JOIN LATERAL (
    SELECT report.* FROM ledger.return_quality_evidence_snapshot report
    WHERE report.organization_id=p_org AND report.platform_listing_variant_id=unit.listing_id
     AND report.report_window_start<=statement_timestamp()-make_interval(hours=>e.measurement_window_hours)
     AND report.report_window_end>=statement_timestamp() AND report.accepted_at<=statement_timestamp()
     AND NOT EXISTS(SELECT 1 FROM ledger.return_quality_evidence_snapshot successor
      WHERE successor.supersedes_snapshot_id=report.id AND successor.accepted_at<=statement_timestamp())
    ORDER BY report.accepted_at DESC,report.id DESC LIMIT 1
   ) coverage ON true
   LEFT JOIN LATERAL (
    SELECT count(*) count,sum(f.net_amount) amount,
     sum(f.net_amount) FILTER(WHERE EXISTS(SELECT 1 FROM core.listing_mapping mapping
      WHERE mapping.platform_listing_variant_id=f.platform_listing_variant_id AND mapping.product_variant_id=ANY(variants)
       AND mapping.status IN('ACTIVE','ENDED') AND mapping.effective_from<=f.occurred_at
       AND (mapping.effective_to IS NULL OR mapping.effective_to>f.occurred_at))) affected_amount,
     bool_and(f.net_amount IS NOT NULL AND f.net_amount>=0 AND f.currency_code=e.currency_code
      AND (SELECT count(*) FROM current_facts duplicate WHERE duplicate.platform_listing_variant_id=f.platform_listing_variant_id
       AND duplicate.native_order_key=f.native_order_key AND duplicate.native_line_key IS NOT DISTINCT FROM f.native_line_key)=1
      AND (SELECT count(*) FROM core.listing_mapping mapping WHERE mapping.platform_listing_variant_id=f.platform_listing_variant_id
       AND mapping.status IN('ACTIVE','ENDED') AND mapping.effective_from<=f.occurred_at
       AND (mapping.effective_to IS NULL OR mapping.effective_to>f.occurred_at))=1) valid
    FROM current_facts f WHERE f.platform_listing_variant_id=unit.listing_id
   ) fact ON true
  ), complete_units AS (
   SELECT *, profile_id IS NOT NULL AND coverage_id IS NOT NULL AND NOT incident_blocks
    AND completed_coverage='COMPLETE' AND retained_coverage='COMPLETE' AND qc_coverage='COMPLETE'
    AND return_coverage IN('COMPLETE_ZERO','COMPLETE_OBSERVED')
    AND accepted_at<=statement_timestamp()
    AND (accepted_fact_max_age_minutes IS NULL OR accepted_at>=statement_timestamp()-make_interval(mins=>accepted_fact_max_age_minutes))
    AND NOT EXISTS(SELECT 1 FROM unnest(ARRAY[completed_source_updated_at,retained_source_updated_at,
     return_source_updated_at,qc_source_updated_at]) updated WHERE updated IS NULL OR updated>statement_timestamp()
      OR source_max_age_minutes IS NOT NULL AND updated<statement_timestamp()-make_interval(mins=>source_max_age_minutes))
    AND (count=0 OR valid) complete
   FROM per_unit
  )
  SELECT count(*)>0 AND bool_and(complete)
    AND NOT EXISTS(SELECT 1 FROM unnest(stores) scope_store WHERE NOT EXISTS(SELECT 1 FROM scope_units unit WHERE unit.store_id=scope_store)),
   CASE WHEN count(*)>0 AND bool_and(complete) THEN sum(coalesce(amount,0)) END,
   CASE WHEN count(*)>0 AND bool_and(complete) THEN sum(coalesce(affected_amount,0)) END
  INTO sales_known,sales_total,affected_sales FROM complete_units;
  IF sales_known IS NOT TRUE THEN sales_total:=NULL; affected_sales:=NULL; END IF;
  IF sales_known IS NOT TRUE OR sales_total IS NULL OR sales_total<=0
   OR EXISTS(SELECT 1 FROM unnest(variants) member WHERE NOT EXISTS(SELECT 1 FROM core.listing_mapping mapping
     JOIN core.platform_listing_variant variant ON variant.id=mapping.platform_listing_variant_id
     JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id
     WHERE mapping.product_variant_id=member AND mapping.organization_id=p_org AND listing.store_id=ANY(stores)
      AND mapping.status='ACTIVE' AND mapping.effective_from<=statement_timestamp()
      AND (mapping.effective_to IS NULL OR mapping.effective_to>statement_timestamp()))) THEN
   reasons:=array_append(reasons,'RETAINED_SALES_SHARE_UNRESOLVED');
  ELSIF affected_sales/sales_total>e.max_affected_retained_sales_share THEN
   reasons:=array_append(reasons,'AFFECTED_RETAINED_SALES_SHARE'); END IF;
  envelopes:=envelopes || jsonb_build_array(jsonb_build_object('envelopeId',e.id,'policyVersion',e.policy_version,
   'scopeKind',e.scope_kind,'platformCode',e.platform_code,'storeId',e.store_ref_id,'currencyCode',e.currency_code,
   'measurementWindowHours',e.measurement_window_hours,'retainedWindowDays',e.retained_window_days,
   'axes',jsonb_build_object(
    'activeInterventions',jsonb_build_object('usage',active_count,'limit',e.max_active_interventions,'state',CASE WHEN 'ACTIVE_INTERVENTIONS'=ANY(reasons) THEN 'EXCEEDED' ELSE 'AVAILABLE' END),
    'associatedOfficialSpend',jsonb_build_object('usage',CASE WHEN 'ASSOCIATED_SPEND_UNRESOLVED'=ANY(reasons) THEN NULL ELSE associated END,'limit',e.max_associated_spend_amount,'aggregationBasis','COMPLETE_INTERSECTING_OFFICIAL_REPORT_AMOUNTS','conservativeBoundaryReportCount',associated_boundary_reports,'unit',e.currency_code||'_MAJOR','state',CASE WHEN 'ASSOCIATED_SPEND_UNRESOLVED'=ANY(reasons) THEN 'UNKNOWN' WHEN 'ASSOCIATED_SPEND'=ANY(reasons) THEN 'EXCEEDED' ELSE 'AVAILABLE' END),
    'affectedRetainedSalesShare',jsonb_build_object('usage',CASE WHEN sales_total>0 AND NOT 'RETAINED_SALES_SHARE_UNRESOLVED'=ANY(reasons) THEN affected_sales/sales_total END,'companySales',sales_total,'affectedSales',affected_sales,'limit',e.max_affected_retained_sales_share,'state',CASE WHEN 'RETAINED_SALES_SHARE_UNRESOLVED'=ANY(reasons) THEN 'UNKNOWN' WHEN 'AFFECTED_RETAINED_SALES_SHARE'=ANY(reasons) THEN 'EXCEEDED' ELSE 'AVAILABLE' END),
    'cumulativeBidChangeMajor',jsonb_build_object('usage',CASE WHEN 'CUMULATIVE_BID_CHANGE_UNRESOLVED'=ANY(reasons) THEN NULL ELSE cumulative END,'limit',e.max_cumulative_bid_change_amount,'windowHours',e.cumulative_window_hours,'unit',e.currency_code||'_MAJOR','state',CASE WHEN 'CUMULATIVE_BID_CHANGE_UNRESOLVED'=ANY(reasons) THEN 'UNKNOWN' WHEN 'CUMULATIVE_BID_CHANGE'=ANY(reasons) THEN 'EXCEEDED' ELSE 'AVAILABLE' END),
    'unresolvedTransmittedWrites',jsonb_build_object('usage',unresolved,'limit',e.max_unresolved_transmitted_writes,'state',CASE WHEN 'UNRESOLVED_TRANSMITTED_WRITES'=ANY(reasons) THEN 'EXCEEDED' ELSE 'AVAILABLE' END),
    'reservedRecoveryHeadroom',jsonb_build_object('available',e.max_active_interventions-active_count,'reserved',e.reserved_recovery_headroom_count,'state',CASE WHEN 'RECOVERY_HEADROOM'=ANY(reasons) THEN 'EXCEEDED' ELSE 'AVAILABLE' END)),
   'reasons',to_jsonb(reasons)));
  all_reasons:=all_reasons || reasons;
 END LOOP;
 reasons:=all_reasons;
 IF NOT found_envelope THEN reasons:=array_append(reasons,'AGGREGATE_ENVELOPE_UNRESOLVED'); END IF;
 IF cardinality(reasons)>0 AND NOT 'AGGREGATE_ENVELOPE_UNRESOLVED'=ANY(reasons) THEN
  reasons:=array_append(reasons,'AGGREGATE_ENVELOPE_BLOCKED'); END IF;
 RETURN jsonb_build_object('envelopes',envelopes,'reasons',to_jsonb(ARRAY(SELECT DISTINCT reason FROM unnest(reasons) reason ORDER BY reason)));
END $$;

CREATE OR REPLACE FUNCTION ops.release_ad_action_reservation(p_reservation_id uuid,p_reason text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE held ops.ad_action_reservation%ROWTYPE; c ops.ad_bid_command%ROWTYPE; packet ops.ad_manual_execution_packet%ROWTYPE;
 configuration_known boolean:=false; unresolved boolean:=true; early_complete boolean:=false; regressed boolean:=true;
 baseline_id uuid; platform_code text;
BEGIN
 SELECT * INTO held FROM ops.ad_action_reservation WHERE id=p_reservation_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'no such reservation' USING ERRCODE='MO097'; END IF;
 IF held.state='RELEASED' THEN RETURN false; END IF;
 IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 1 AND 256 THEN
  RAISE EXCEPTION 'release reason required' USING ERRCODE='MO097'; END IF;
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(held.organization_id::text));
 SELECT * INTO c FROM ops.ad_bid_command WHERE reservation_id=held.id;
 IF c.id IS NOT NULL THEN
  baseline_id:=c.outcome_baseline_id; platform_code:=c.platform_code;
  configuration_known:=c.state IN ('READBACK_MATCHED','COMPENSATED') AND EXISTS(
   SELECT 1 FROM ops.ad_bid_command_readback rb WHERE rb.command_id=c.id
   AND rb.match_state=CASE WHEN c.state='COMPENSATED' THEN 'MATCHES_PRIOR' ELSE 'MATCHES_TARGET' END
   AND rb.bid_unit_code=c.bid_unit_code AND rb.currency_code=c.currency_code
   AND rb.observed_bid=CASE WHEN c.state='COMPENSATED' THEN c.prior_bid_amount ELSE c.target_bid_amount END);
  unresolved:=c.state NOT IN ('READBACK_MATCHED','COMPENSATED');
 ELSE
  SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE reservation_id=held.id;
  baseline_id:=packet.outcome_baseline_id; platform_code:=packet.platform_code;
  configuration_known:=packet.state='MANUAL_CONFIGURATION_VERIFIED' AND EXISTS(
   SELECT 1 FROM ops.ad_manual_configuration_verification proof WHERE proof.id=packet.current_proof_id
   AND proof.packet_id=packet.id AND ops.ad_manual_observation_is_qualified(proof.id) AND proof.conflict_state='NONE'
   AND proof.evidence_grade<>'EXECUTOR_SELF_REPORT');
  unresolved:=NOT coalesce(configuration_known,false);
 END IF;
 IF baseline_id IS NOT NULL THEN
  early_complete:=EXISTS(SELECT 1 FROM ops.ad_outcome_observation o
   WHERE (o.command_id=c.id OR o.manual_packet_id=packet.id)
   AND o.outcome_stage IN ('OPERATIONAL','OPERATIONAL_REVISED') AND o.guard_state='NOT_APPLICABLE'
   AND o.verdict IN ('IMPROVED','UNCHANGED') AND o.evaluated_at>=o.window_ends_at
   AND EXISTS(SELECT 1 FROM ops.ad_outcome_axes axes WHERE axes.observation_id=o.id
     AND axes.outcome_baseline_id=baseline_id AND axes.sales_preservation_verdict='PRESERVED')
   AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_critical_unit unit WHERE unit.outcome_baseline_id=baseline_id
     AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_critical_guard guard WHERE guard.observation_id=o.id
       AND guard.outcome_baseline_id=unit.outcome_baseline_id AND guard.product_variant_id=unit.product_variant_id
       AND guard.listing_variant_id=unit.listing_variant_id AND guard.guard_state='PASS'))
   AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation n WHERE n.supersedes_observation_id=o.id));
  regressed:=EXISTS(SELECT 1 FROM ops.ad_outcome_observation o
   WHERE (o.command_id=c.id OR o.manual_packet_id=packet.id) AND o.verdict='REGRESSED'
   AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation n WHERE n.supersedes_observation_id=o.id))
   OR cardinality(ops.ad_active_containment(held.organization_id,held.ad_native_object_id,held.store_id,platform_code,
        'ad-bid-change',held.affected_set_digest))>0;
 END IF;
 UPDATE ops.ad_action_reservation SET configuration_resolved=coalesce(configuration_known,false),unknown_or_mismatch_open=unresolved,
 early_observation_complete=early_complete,regression_open=regressed,version=version+1 WHERE id=held.id;
 IF NOT coalesce(configuration_known,false) OR unresolved OR NOT early_complete OR regressed THEN RETURN false; END IF;
 UPDATE ops.ad_action_reservation SET state='RELEASED',released_at=clock_timestamp(),release_reason=p_reason,
 version=version+1 WHERE id=held.id;
 RETURN true;
END $$;

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
            WHERE p.id=NEW.manual_packet_id AND p.state='MANUAL_CONFIGURATION_VERIFIED'
              AND ops.ad_manual_observation_is_qualified(proof.id)
              AND proof.observed_at<=NEW.evaluated_at AND proof.recorded_at<=NEW.evaluated_at;
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

CREATE OR REPLACE FUNCTION ops.schedule_ad_outcome_maturity() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops AS $$
DECLARE baseline ops.ad_outcome_baseline; landed timestamptz; stage ops.ad_outcome_stage_baseline;
BEGIN
    IF TG_TABLE_NAME='ad_bid_command_readback' THEN
        IF NEW.match_state<>'MATCHES_TARGET' THEN RETURN NEW; END IF;
        SELECT b.* INTO baseline FROM ops.ad_bid_command c JOIN ops.ad_outcome_baseline b ON b.id=c.outcome_baseline_id
            WHERE c.id=NEW.command_id;landed:=NEW.observed_at;
    ELSE
        IF NOT ops.ad_manual_observation_is_qualified(NEW.id) THEN RETURN NEW; END IF;
        SELECT b.* INTO baseline FROM ops.ad_manual_execution_packet p JOIN ops.ad_outcome_baseline b ON b.id=p.outcome_baseline_id
            WHERE p.id=NEW.packet_id;landed:=NEW.observed_at;
    END IF;
    IF baseline.id IS NULL THEN RETURN NEW; END IF;
    IF (CASE WHEN jsonb_typeof(baseline.plan_snapshot->'observationStartsMinutes')='number'
      THEN (baseline.plan_snapshot->>'observationStartsMinutes')::numeric NOT BETWEEN 0 AND 2147483647
        OR trunc((baseline.plan_snapshot->>'observationStartsMinutes')::numeric)<>(baseline.plan_snapshot->>'observationStartsMinutes')::numeric
      ELSE true END) THEN
        -- Preserve the external configuration fact. Missing local planning authority
        -- blocks outcome scheduling and is visible as an incident, never a zero deadline.
        INSERT INTO ops.ad_trace_event(id,organization_id,ad_native_object_id,path_kind,stage_code,status,
          correlation_id,subject_reference,detail,occurred_at)
        VALUES(gen_random_uuid(),baseline.organization_id,baseline.ad_native_object_id,'OPERATIONS','OUTCOME_MATURITY_SWEEP','FAILED',
          'outcome-deadline:'||gen_random_uuid(),baseline.id::text,
          '{"reason":"AD_OUTCOME_PLAN_DEADLINE_UNRESOLVED"}',clock_timestamp());
        RETURN NEW;
    END IF;
    FOR stage IN SELECT * FROM ops.ad_outcome_stage_baseline WHERE outcome_baseline_id=baseline.id LOOP
        INSERT INTO ops.ad_recalculation_due(organization_id,ad_native_object_id,trigger_class,source_reference,due_at)
        VALUES(baseline.organization_id,baseline.ad_native_object_id,'OUTCOME_MATURITY_OR_REGRESSION',
            'outcome:'||baseline.id||':'||stage.stage,
            landed+make_interval(mins=>(baseline.plan_snapshot->>'observationStartsMinutes')::integer,hours=>stage.window_hours))
        ON CONFLICT DO NOTHING;
    END LOOP;
    RETURN NEW;
END $$;
