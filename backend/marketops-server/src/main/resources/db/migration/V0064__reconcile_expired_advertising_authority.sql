-- An expired approval remains immutable; its revocation is append-only.
-- No release, compensation, transmission or renewed authority is implied.
-- Advertising has independently accountable causes and a finite set of inert
-- candidate choices. The older object/action uniqueness remains exact for all
-- other domains; execution exclusivity remains owned by reservations/commands.
DROP INDEX ops.recommendation_live_uq;
CREATE UNIQUE INDEX recommendation_live_uq ON ops.recommendation(subject_kind,subject_id,action_kind)
 WHERE action_kind NOT IN('ADVERTISING_REVIEW','AD_BID_CHANGE')
  AND state IN('DRAFT','VALIDATED','READY_FOR_REVIEW','TASK_ONLY','APPROVED','POLICY_AUTHORIZED',
              'COMMAND_CREATED','EXECUTION_TRACKING','OUTCOME_OBSERVATION');
CREATE UNIQUE INDEX ad_responsibility_recommendation_case_uq
 ON ops.recommendation(organization_id,(proposed_parameters->>'caseId'))
 WHERE action_kind='ADVERTISING_REVIEW';
CREATE UNIQUE INDEX ad_bid_recommendation_live_candidate_uq
 ON ops.recommendation(organization_id,(proposed_parameters->>'candidateId'))
 WHERE action_kind='AD_BID_CHANGE'
  AND state IN('DRAFT','VALIDATED','READY_FOR_REVIEW','TASK_ONLY','APPROVED','POLICY_AUTHORIZED',
              'COMMAND_CREATED','EXECUTION_TRACKING','OUTCOME_OBSERVATION');

CREATE FUNCTION ops.expire_ad_action_authority(p_organization uuid,p_as_of timestamptz)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE changed integer;
BEGIN
 IF p_as_of IS NULL OR p_as_of>clock_timestamp() THEN
  RAISE EXCEPTION 'expiry reconciliation requires a current or past observation' USING ERRCODE='MO092';
 END IF;
 INSERT INTO ops.ad_authority_invalidation(organization_id,authorization_id,cause_reference,cause_code,invalidated_at)
 SELECT a.organization_id,a.id,a.id,'SEALED_AUTHORIZATION_EXPIRED',p_as_of
 FROM ops.ad_action_authorization a
 WHERE a.organization_id=p_organization AND a.expires_at<=p_as_of
  AND NOT EXISTS(SELECT 1 FROM ops.ad_authority_invalidation i WHERE i.authorization_id=a.id)
 ON CONFLICT DO NOTHING;
 GET DIAGNOSTICS changed=ROW_COUNT;
 RETURN changed;
END $$;
REVOKE ALL ON FUNCTION ops.expire_ad_action_authority(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.expire_ad_action_authority(uuid,timestamptz) TO marketops_app;

GRANT UPDATE(slo_profile_id,slo_profile_version,calendar_id,calendar_version,acknowledgement_due_at,
 action_due_at,escalation_due_at,next_staffed_response_at,coverage_state,profile_snapshot)
 ON ops.ad_case_responsibility TO marketops_app;

-- A scope/cause recurrence or changed Owner authority cannot be erased by restoring
-- the former row before the next reconciliation/read. The same journal also bounds
-- the historical Action pause and does not reset the Case's first-raised time.
CREATE FUNCTION ops.record_ad_exception_case_boundary() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$ BEGIN
 IF ROW(OLD.superseded_at,OLD.cause_code,OLD.lane,OLD.semantic_profile_id,OLD.affected_set_id,
        OLD.policy_version_digest,OLD.bundle_id)
  IS DISTINCT FROM ROW(NEW.superseded_at,NEW.cause_code,NEW.lane,NEW.semantic_profile_id,NEW.affected_set_id,
        NEW.policy_version_digest,NEW.bundle_id) THEN
  INSERT INTO ops.ad_exception_authority_change(exception_id,source_table,source_id)
  SELECT id,'mart.ad_case',OLD.id FROM ops.ad_accepted_exception
   WHERE case_id=OLD.id AND state IN('REQUESTED','ENDORSED','ACTIVE');
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.record_ad_exception_case_boundary() FROM PUBLIC;
CREATE TRIGGER ad_exception_case_boundary AFTER UPDATE ON mart.ad_case
 FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_case_boundary();

CREATE FUNCTION ops.record_ad_exception_policy_boundary() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$ BEGIN
 IF to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW) THEN
  INSERT INTO ops.ad_exception_authority_change(exception_id,source_table,source_id)
  SELECT x.id,TG_TABLE_SCHEMA||'.'||TG_TABLE_NAME,OLD.id FROM ops.ad_accepted_exception x
   LEFT JOIN ops.ad_decision_policy_bundle b ON b.id=x.bundle_id
   WHERE ((to_jsonb(OLD)->>'organization_id') IS NULL
       OR x.organization_id=(to_jsonb(OLD)->>'organization_id')::uuid)
     AND x.state IN('REQUESTED','ENDORSED','ACTIVE')
    AND (x.bundle_id=OLD.id OR x.semantic_profile_id=OLD.id
       OR EXISTS(SELECT 1 FROM jsonb_each_text(to_jsonb(b)) field WHERE field.value=OLD.id::text)
       OR TG_TABLE_NAME='ad_freshness_profile');
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.record_ad_exception_policy_boundary() FROM PUBLIC;
DO $$ DECLARE relation text; BEGIN
 FOREACH relation IN ARRAY ARRAY['core.ad_conversion_definition','core.ad_allowable_cpa_definition',
  'core.ad_optimization_qualification_policy','core.ad_bid_target_policy','core.ad_outcome_policy',
  'core.ad_priority_policy','core.ad_human_slo_profile','core.ad_approval_lease_policy','core.ad_exposure_envelope',
  'core.ad_materiality_policy','core.ad_freshness_profile','platform.ad_semantic_profile','ops.ad_decision_policy_bundle'] LOOP
  EXECUTE format('CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON %s FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary()',relation);
 END LOOP;
END $$;

-- Materiality is one current, reason-coded assessment at every decision gate.
-- Promotion is only an eligibility envelope; it cannot erase any hard axis.
CREATE FUNCTION ops.ad_materiality_assessment(p_bundle uuid,p_candidate uuid)
RETURNS jsonb LANGUAGE plpgsql STABLE
SET search_path=pg_catalog,ops,core,mart,platform,pg_temp AS $$
DECLARE b ops.ad_decision_policy_bundle%ROWTYPE; c ops.ad_bid_candidate%ROWTYPE;
 k mart.ad_case%ROWTYPE; m core.ad_materiality_policy%ROWTYPE; affected core.ad_affected_set%ROWTYPE;
 baseline_id uuid; delta numeric; relative_delta numeric; cumulative numeric; critical_amount numeric;
 critical_count integer; critical_complete boolean; stores uuid[]; reasons text[]:='{}'; axes jsonb;
 lifecycle_ok boolean; eligible_promotion boolean; unresolved_intervention boolean;
BEGIN
 SELECT * INTO b FROM ops.ad_decision_policy_bundle WHERE id=p_bundle;
 SELECT * INTO c FROM ops.ad_bid_candidate WHERE id=p_candidate;
 SELECT * INTO k FROM mart.ad_case WHERE id=c.case_id;
 SELECT * INTO m FROM core.ad_materiality_policy WHERE id=b.materiality_policy_id;
 SELECT * INTO affected FROM core.ad_affected_set WHERE id=k.affected_set_id;
 IF b.id IS NULL OR c.id IS NULL OR k.id IS NULL OR m.id IS NULL OR m.status<>'ACTIVE'
  OR m.effective_from>statement_timestamp() OR m.effective_to<=statement_timestamp()
  OR b.organization_id<>c.organization_id OR b.store_id<>k.store_id OR b.direction<>c.direction
  OR b.candidate_basis<>c.candidate_basis OR b.semantic_profile_id<>c.semantic_profile_id
  OR m.organization_id<>c.organization_id OR m.currency_code<>c.currency_code
  OR NOT (m.scope_kind='ORGANIZATION' OR m.scope_kind='PLATFORM' AND m.platform_code=b.platform_code
    OR m.scope_kind='STORE' AND m.store_ref_id=k.store_id AND m.platform_code=b.platform_code)
  OR c.bid_unit_code NOT IN('CURRENCY_MAJOR','CURRENCY_MINOR') OR c.current_bid_amount<=0
  OR affected.id IS NULL OR affected.resolution_state<>'COMPLETE'
  OR affected.affected_set_digest<>c.affected_set_digest THEN
  RETURN jsonb_build_object('route','MATERIALITY_UNRESOLVED','reasons',jsonb_build_array('MATERIALITY_AUTHORITY_OR_SCOPE_UNRESOLVED'));
 END IF;
 delta:=abs(c.provider_normalized_amount-c.current_bid_amount)/CASE c.bid_unit_code WHEN 'CURRENCY_MINOR' THEN 100 ELSE 1 END;
 relative_delta:=abs(c.provider_normalized_amount-c.current_bid_amount)/c.current_bid_amount;
 SELECT array_agg(store.id) INTO stores FROM core.store store
 JOIN core.marketplace_account account ON account.id=store.marketplace_account_id
 WHERE store.organization_id=c.organization_id AND (m.scope_kind='ORGANIZATION'
  OR m.scope_kind='PLATFORM' AND account.platform_code=m.platform_code
  OR m.scope_kind='STORE' AND store.id=m.store_ref_id);
 WITH changes AS (
  SELECT abs(candidate.provider_normalized_amount-candidate.current_bid_amount)
    /CASE candidate.bid_unit_code WHEN 'CURRENCY_MINOR' THEN 100 ELSE 1 END AS amount,
    candidate.currency_code=m.currency_code AND candidate.bid_unit_code IN('CURRENCY_MAJOR','CURRENCY_MINOR') AS known
  FROM ops.ad_action_reservation r JOIN ops.ad_bid_candidate candidate ON candidate.id=r.intervention_reference_id
  WHERE r.organization_id=c.organization_id AND r.store_id=ANY(stores) AND candidate.id<>c.id
    AND r.reserved_at>statement_timestamp()-make_interval(hours=>m.material_cumulative_window_hours)
  UNION ALL
  SELECT CASE WHEN packet.intended_state->>'targetBid' ~ '^[0-9]+(\.[0-9]+)?$'
    THEN abs((packet.intended_state->>'targetBid')::numeric-configuration.observed_bid_amount)
      /CASE configuration.bid_unit_code WHEN 'CURRENCY_MINOR' THEN 100 ELSE 1 END END,
    configuration.bid_currency_code=m.currency_code AND configuration.bid_unit_code IN('CURRENCY_MAJOR','CURRENCY_MINOR')
      AND packet.intended_state->>'targetBid' ~ '^[0-9]+(\.[0-9]+)?$'
  FROM ops.ad_action_reservation r JOIN ops.ad_manual_execution_packet packet ON packet.id=r.intervention_reference_id
  JOIN core.ad_object_configuration_observation configuration ON configuration.id=packet.observed_configuration_id
  WHERE r.organization_id=c.organization_id AND r.store_id=ANY(stores)
    AND r.reserved_at>statement_timestamp()-make_interval(hours=>m.material_cumulative_window_hours)
  UNION ALL
  SELECT abs(command.target_bid_amount-command.prior_bid_amount)/CASE command.bid_unit_code WHEN 'CURRENCY_MINOR' THEN 100 ELSE 1 END,
    command.currency_code=m.currency_code AND command.bid_unit_code IN('CURRENCY_MAJOR','CURRENCY_MINOR')
  FROM ops.ad_bid_command command WHERE command.organization_id=c.organization_id AND command.store_id=ANY(stores)
   AND (EXISTS(SELECT 1 FROM ops.ad_compensation_authorization approval WHERE approval.command_id=command.id
      AND approval.approved_at>statement_timestamp()-make_interval(hours=>m.material_cumulative_window_hours))
    OR EXISTS(SELECT 1 FROM ops.ad_bid_command_attempt attempt WHERE attempt.command_id=command.id AND attempt.purpose='RESTORE'
      AND attempt.started_at>statement_timestamp()-make_interval(hours=>m.material_cumulative_window_hours)))
 ) SELECT delta+coalesce(sum(amount),0),coalesce(bool_or(known IS NOT TRUE OR amount IS NULL),false)
 INTO cumulative,unresolved_intervention FROM changes;
 SELECT selected.outcome_baseline_id INTO baseline_id FROM ops.ad_candidate_selection selected WHERE selected.candidate_id=c.id;
 SELECT count(*) INTO critical_count FROM core.ad_outcome_critical_unit_rule rule
 WHERE rule.outcome_policy_id=b.outcome_policy_id AND rule.product_variant_id=ANY(affected.product_variant_ids)
   AND (rule.store_id IS NULL OR rule.store_id=k.store_id);
 SELECT policy.critical_unit_definition_complete INTO critical_complete FROM core.ad_outcome_policy policy WHERE policy.id=b.outcome_policy_id;
 IF critical_count=0 AND critical_complete IS TRUE THEN critical_amount:=0;
 ELSE
  SELECT sum((unit#>>'{sales,value}')::numeric) INTO critical_amount
  FROM ops.ad_outcome_stage_baseline stage CROSS JOIN LATERAL jsonb_array_elements(stage.snapshot->'units') unit
  WHERE stage.outcome_baseline_id=baseline_id AND stage.stage='OPERATIONAL'
    AND unit#>>'{unit,ruleId}' IS NOT NULL
  HAVING count(*)=critical_count AND bool_and(unit#>>'{sales,value}' IS NOT NULL);
 END IF;
 -- Each variant uses the existing scoped commercial-policy lifecycle authority.
 SELECT bool_and(choices.n=1 AND (b.lifecycle_scope='ALL' OR choices.lifecycle=b.lifecycle_scope)) INTO lifecycle_ok
 FROM unnest(affected.product_variant_ids) variant CROSS JOIN LATERAL (
  WITH applicable AS (SELECT policy.*,dense_rank() OVER(ORDER BY CASE policy.scope_kind
    WHEN 'PRODUCT_VARIANT' THEN 1 WHEN 'STORE' THEN 2 WHEN 'PLATFORM' THEN 3 ELSE 4 END) precedence
    FROM ops.commercial_policy policy WHERE policy.organization_id=c.organization_id AND policy.status='ACTIVE'
     AND policy.effective_from<=statement_timestamp() AND (policy.effective_to IS NULL OR policy.effective_to>statement_timestamp())
     AND (policy.scope_kind='ORGANIZATION' OR policy.scope_kind='PLATFORM' AND policy.platform_code=b.platform_code
      OR policy.scope_kind='STORE' AND policy.store_ref_id=k.store_id
      OR policy.scope_kind='PRODUCT_VARIANT' AND policy.product_variant_ref_id=variant))
  SELECT count(*) n,min(lifecycle_objective) lifecycle FROM applicable WHERE precedence=1
 ) choices;
 IF delta>m.ordinary_nonzero_envelope_amount OR relative_delta>m.ordinary_relative_envelope_ratio THEN reasons:=array_append(reasons,'ORDINARY_ENVELOPE_EXCEEDED'); END IF;
 IF delta>=m.material_absolute_change_amount THEN reasons:=array_append(reasons,'ABSOLUTE_BID_CHANGE'); END IF;
 IF relative_delta>=m.material_relative_change_ratio THEN reasons:=array_append(reasons,'RELATIVE_BID_CHANGE'); END IF;
 IF k.official_spend_amount IS NULL OR k.official_spend_state<>'AVAILABLE' THEN reasons:=array_append(reasons,'OFFICIAL_SPEND_UNKNOWN');
 ELSIF k.official_spend_amount>=m.material_spend_exposure_amount THEN reasons:=array_append(reasons,'OFFICIAL_SPEND_EXPOSURE'); END IF;
 IF cardinality(affected.product_variant_ids)>=m.material_affected_variant_count THEN reasons:=array_append(reasons,'AFFECTED_VARIANT_EXPOSURE'); END IF;
 IF critical_count>0 THEN reasons:=array_append(reasons,'FIXED_CRITICAL_PROTECTED_SALES_EXPOSURE'); END IF;
 IF critical_complete IS NOT TRUE OR critical_amount IS NULL THEN reasons:=array_append(reasons,'CRITICAL_SALES_EXPOSURE_UNKNOWN');
 ELSIF critical_amount>=m.material_critical_sales_amount THEN reasons:=array_append(reasons,'CRITICAL_SALES_AMOUNT'); END IF;
 IF unresolved_intervention THEN reasons:=array_append(reasons,'CUMULATIVE_CHANGE_UNKNOWN');
 ELSIF cumulative>=m.material_cumulative_change_amount THEN reasons:=array_append(reasons,'CUMULATIVE_BID_CHANGE'); END IF;
 IF lifecycle_ok IS NOT TRUE THEN reasons:=array_append(reasons,'LIFECYCLE_OR_GOVERNED_COHORT_UNRESOLVED'); END IF;
 IF c.direction='EXACT_PRIOR_BID_COMPENSATION' THEN reasons:=array_append(reasons,'FIXED_COMPENSATION'); END IF;
 IF EXISTS(SELECT 1 FROM mart.ad_case current_case WHERE current_case.ad_native_object_id=c.ad_native_object_id
    AND current_case.superseded_at IS NULL AND (current_case.protection_tier='P0' OR current_case.cause_code='ACTION_OUTCOME_REGRESSION'))
  THEN reasons:=array_append(reasons,'FIXED_REGRESSION_OR_UNKNOWN_EXECUTION'); END IF;
 IF EXISTS(SELECT 1 FROM ops.ad_containment hold WHERE hold.organization_id=c.organization_id AND hold.state='ACTIVE'
    AND (hold.platform_code IS NULL OR hold.platform_code=b.platform_code)
    AND (hold.marketplace_account_id IS NULL OR hold.marketplace_account_id=b.marketplace_account_id)
    AND (hold.store_id IS NULL OR hold.store_id=b.store_id)
    AND (hold.ad_native_object_id IS NULL OR hold.ad_native_object_id=c.ad_native_object_id)
    AND (hold.capability_code IS NULL OR hold.capability_code=b.capability_code))
  THEN reasons:=array_append(reasons,'FIXED_QUARANTINE_OR_KILL'); END IF;
 IF k.evidence_state NOT IN('CANONICAL_CONFIRMED','OPERATIONAL') OR cardinality(k.blocker_codes)>0
  THEN reasons:=array_append(reasons,'FIXED_UNKNOWN_DECISION_EVIDENCE'); END IF;
 eligible_promotion:=ops.ad_ordinary_promotion_covers(b.id,c.ad_native_object_id,delta);
 IF NOT eligible_promotion THEN reasons:=array_append(reasons,'EXACT_ORDINARY_PROMOTION_ABSENT'); END IF;
 axes:=jsonb_build_object('absoluteBidChange',jsonb_build_object('value',delta,'threshold',m.material_absolute_change_amount,'unit',m.currency_code||'_MAJOR'),
  'relativeBidChange',jsonb_build_object('value',relative_delta,'threshold',m.material_relative_change_ratio),
  'officialSpendExposure',jsonb_build_object('value',k.official_spend_amount,'threshold',m.material_spend_exposure_amount),
  'affectedVariants',jsonb_build_object('value',cardinality(affected.product_variant_ids),'threshold',m.material_affected_variant_count),
  'criticalSalesExposure',jsonb_build_object('value',critical_amount,'requiredUnitCount',critical_count,'threshold',m.material_critical_sales_amount),
  'cumulativeBidChange',jsonb_build_object('value',CASE WHEN unresolved_intervention THEN NULL ELSE cumulative END,'threshold',m.material_cumulative_change_amount,'windowHours',m.material_cumulative_window_hours),
  'lifecycleAndCohort',jsonb_build_object('scope',b.lifecycle_scope,'complete',lifecycle_ok,'exactPromotionCovered',eligible_promotion),
  'direction',c.direction);
 RETURN jsonb_build_object('route',CASE WHEN cardinality(reasons)=0 THEN 'ORDINARY_IMPACT' ELSE 'MATERIAL_IMPACT' END,
  'policyId',m.id,'policyVersion',m.policy_version,'candidateId',c.id,'bundleId',b.id,
  'ordinaryEnvelope',jsonb_build_object('absolute',m.ordinary_nonzero_envelope_amount,'relative',m.ordinary_relative_envelope_ratio),
  'axes',axes,'reasons',to_jsonb(reasons));
END $$;
REVOKE ALL ON FUNCTION ops.ad_materiality_assessment(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_materiality_assessment(uuid,uuid) TO marketops_app;
