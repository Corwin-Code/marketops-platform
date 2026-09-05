-- Governed human execution has its own business eligibility. It never enables
-- an API capability, modifies a Semantic Profile, or creates an Outbox row.
CREATE TABLE core.ad_manual_policy (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES core.organization(id),
    store_id uuid NOT NULL, semantic_profile_id uuid NOT NULL REFERENCES platform.ad_semantic_profile(id),
    policy_version integer NOT NULL CHECK(policy_version>0), cause_code text NOT NULL,
    outcome_policy_id uuid NOT NULL REFERENCES core.ad_outcome_policy(id),
    action_kind text NOT NULL CHECK(action_kind IN ('AD_BID_CHANGE','AD_BUDGET_CHANGE','AD_STATUS_CHANGE')),
    candidate_basis text, target_budget numeric(18,4), target_status text,
    currency_code text NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
    verification_mode text NOT NULL CHECK(verification_mode IN ('INDEPENDENT_OR_OFFICIAL','OFFICIAL_ONLY')),
    configuration_max_age_seconds integer NOT NULL CHECK(configuration_max_age_seconds BETWEEN 60 AND 86400),
    packet_lease_seconds integer NOT NULL CHECK(packet_lease_seconds BETWEEN 60 AND 3600),
    effective_from timestamptz NOT NULL, effective_to timestamptz NOT NULL CHECK(effective_to>effective_from),
    approved_by_user_id uuid NOT NULL, approved_at timestamptz NOT NULL,
    evidence_reference text NOT NULL CHECK(length(evidence_reference) BETWEEN 1 AND 512),
    FOREIGN KEY(store_id,organization_id) REFERENCES core.store(id,organization_id),
    FOREIGN KEY(approved_by_user_id,organization_id) REFERENCES iam.user_account(id,organization_id),
    UNIQUE(organization_id,store_id,cause_code,action_kind,policy_version),
    CHECK((action_kind='AD_BID_CHANGE' AND candidate_basis IS NOT NULL AND target_budget IS NULL AND target_status IS NULL)
       OR (action_kind='AD_BUDGET_CHANGE' AND target_budget>=0 AND candidate_basis IS NULL AND target_status IS NULL)
       OR (action_kind='AD_STATUS_CHANGE' AND length(btrim(target_status)) BETWEEN 1 AND 128 AND candidate_basis IS NULL AND target_budget IS NULL))
);
CREATE TABLE ops.ad_manual_proposal (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL, case_id uuid NOT NULL,
    responsibility_recommendation_id uuid NOT NULL REFERENCES ops.recommendation(id),
    policy_id uuid NOT NULL REFERENCES core.ad_manual_policy(id),
    candidate_id uuid REFERENCES ops.ad_bid_candidate(id),
    ad_native_object_id uuid NOT NULL, store_id uuid NOT NULL, affected_set_id uuid NOT NULL,
    affected_set_digest text NOT NULL, observed_configuration_id uuid NOT NULL,
    action_kind text NOT NULL, intended_state jsonb NOT NULL CHECK(jsonb_typeof(intended_state)='object'),
    authority_snapshot jsonb NOT NULL, generated_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    FOREIGN KEY(case_id,organization_id) REFERENCES mart.ad_case(id,organization_id),
    FOREIGN KEY(ad_native_object_id,organization_id) REFERENCES core.ad_native_object(id,organization_id),
    FOREIGN KEY(store_id,organization_id) REFERENCES core.store(id,organization_id),
    FOREIGN KEY(affected_set_id,organization_id) REFERENCES core.ad_affected_set(id,organization_id),
    FOREIGN KEY(observed_configuration_id,organization_id) REFERENCES core.ad_object_configuration_observation(id,organization_id),
    CHECK(expires_at>generated_at)
);
ALTER TABLE ops.ad_manual_execution_packet
    ADD COLUMN proposal_id uuid UNIQUE REFERENCES ops.ad_manual_proposal(id),
    ADD COLUMN manual_policy_id uuid REFERENCES core.ad_manual_policy(id),
    ADD COLUMN executor_user_id uuid REFERENCES iam.user_account(id),
    ADD COLUMN execution_started_at timestamptz,
    ADD COLUMN reservation_id uuid REFERENCES ops.ad_action_reservation(id),
    ADD COLUMN current_proof_id uuid REFERENCES ops.ad_manual_configuration_verification(id),
    ADD COLUMN authority_snapshot jsonb,
    ADD COLUMN outcome_baseline_id uuid REFERENCES ops.ad_outcome_baseline(id);
ALTER TABLE core.ad_object_configuration_observation
    ADD COLUMN observed_budget_amount numeric(18,4) CHECK(observed_budget_amount>=0);
ALTER TABLE ops.ad_manual_execution_packet DROP CONSTRAINT ad_manual_execution_packet_state_ck;
ALTER TABLE ops.ad_manual_execution_packet ADD CONSTRAINT ad_manual_execution_packet_state_ck CHECK(state IN (
    'MANUAL_PACKET_DRAFT','MANUAL_PACKET_ENDORSED','MANUAL_PACKET_ISSUED','MANUAL_EXECUTION_IN_PROGRESS',
    'MANUAL_PACKET_REVOKED','ACTION_REPORTED_CONFIGURATION_UNVERIFIED','MANUAL_CONFIGURATION_VERIFIED',
    'MANUAL_EXECUTION_UNCERTAIN','MANUAL_PACKET_EXPIRED'));
ALTER TABLE ops.ad_manual_configuration_verification
    ADD COLUMN configuration_observation_id uuid REFERENCES core.ad_object_configuration_observation(id);
CREATE TRIGGER ad_manual_policy_immutable BEFORE UPDATE OR DELETE ON core.ad_manual_policy
    FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
CREATE TRIGGER ad_manual_proposal_immutable BEFORE UPDATE OR DELETE ON ops.ad_manual_proposal
    FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
CREATE TRIGGER ad_manual_verification_immutable BEFORE UPDATE OR DELETE ON ops.ad_manual_configuration_verification
    FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
REVOKE ALL ON core.ad_manual_policy,ops.ad_manual_proposal FROM PUBLIC,marketops_app;
REVOKE INSERT,UPDATE,DELETE ON ops.ad_manual_execution_packet,ops.ad_manual_configuration_verification FROM marketops_app;
GRANT SELECT ON core.ad_manual_policy,ops.ad_manual_proposal TO marketops_app;

CREATE FUNCTION ops.ad_manual_actor_scoped(p_actor uuid,p_org uuid,p_store uuid,p_set uuid,p_role text,p_action text)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM iam.user_account u WHERE u.id=p_actor AND u.organization_id=p_org AND u.status='ACTIVE')
 AND ops.ad_actor_has_role_scope(p_actor,p_org,p_store,p_role,p_action)
 AND (p_set IS NULL OR NOT EXISTS(
   SELECT 1 FROM core.ad_affected_set a CROSS JOIN LATERAL unnest(a.product_variant_ids) member
   WHERE a.id=p_set AND NOT EXISTS(SELECT 1 FROM iam.user_scope_grant g
     WHERE g.user_id=p_actor AND g.organization_id=p_org AND g.action_code=p_action AND g.status='ACTIVE'
     AND g.effective_from<=statement_timestamp() AND (g.effective_to IS NULL OR g.effective_to>statement_timestamp())
     AND (g.organization_ref_id=p_org OR g.product_variant_ref_id=member))))
$$;
REVOKE ALL ON FUNCTION ops.ad_manual_actor_scoped(uuid,uuid,uuid,uuid,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_manual_actor_scoped(uuid,uuid,uuid,uuid,text,text) TO marketops_app;

CREATE FUNCTION ops.publish_ad_manual_policy(p_content jsonb,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,iam,platform,pg_temp AS $$
DECLARE policy core.ad_manual_policy%ROWTYPE; grant_row iam.ad_invocation_grant%ROWTYPE;
BEGIN
 policy:=jsonb_populate_record(NULL::core.ad_manual_policy,p_content);
 grant_row:=ops.consume_ad_control_invocation(p_proof,'MANUAL_POLICY_PUBLISH',policy.id,policy.store_id);
 IF policy.organization_id<>grant_row.organization_id OR NOT ops.ad_manual_actor_scoped(grant_row.actor_user_id,
   policy.organization_id,policy.store_id,NULL,'OWNER','ADVERTISING_POLICY_MANAGE') THEN
   RAISE EXCEPTION 'manual business policy requires scoped Owner' USING ERRCODE='MO064'; END IF;
 IF NOT EXISTS(SELECT 1 FROM core.store s JOIN core.marketplace_account a ON a.id=s.marketplace_account_id
   JOIN platform.ad_semantic_profile p ON p.id=policy.semantic_profile_id AND p.platform_code=a.platform_code
   WHERE s.id=policy.store_id AND s.organization_id=policy.organization_id AND p.status='ACTIVE') THEN
   RAISE EXCEPTION 'manual policy native profile does not belong to Store' USING ERRCODE='MO097'; END IF;
 IF EXISTS(SELECT 1 FROM core.ad_manual_policy p WHERE p.organization_id=policy.organization_id
   AND p.store_id=policy.store_id AND p.cause_code=policy.cause_code AND p.action_kind=policy.action_kind
   AND tstzrange(p.effective_from,p.effective_to,'[)') && tstzrange(policy.effective_from,policy.effective_to,'[)')) THEN
   RAISE EXCEPTION 'manual policy scope overlaps an existing immutable version' USING ERRCODE='MO097'; END IF;
 IF policy.action_kind='AD_STATUS_CHANGE' AND NOT EXISTS(SELECT 1 FROM platform.ad_semantic_profile p
   WHERE p.id=policy.semantic_profile_id AND p.status_semantics->>policy.target_status IN ('RUNNING','PAUSED','STOPPED')) THEN
   RAISE EXCEPTION 'native status is not declared in the exact profile' USING ERRCODE='MO097'; END IF;
 policy.approved_by_user_id:=grant_row.actor_user_id; policy.approved_at:=clock_timestamp();
 INSERT INTO core.ad_manual_policy SELECT policy.*;
 RETURN policy.id;
END $$;

CREATE FUNCTION ops.ad_manual_snapshot(p_case uuid,p_policy uuid,p_configuration uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog,ops,core,mart,platform,pg_temp AS $$
 SELECT jsonb_build_object('policy',to_jsonb(p),'caseId',c.id,'calculationId',c.calculation_id,
   'causeCode',c.cause_code,'policyVersionDigest',c.policy_version_digest,'affectedSet',to_jsonb(a),
   'object',to_jsonb(o),'semanticProfile',to_jsonb(s),'configuration',to_jsonb(cfg),
   'outcomePolicy',(SELECT to_jsonb(outcome) FROM core.ad_outcome_policy outcome WHERE outcome.id=p.outcome_policy_id))
 FROM mart.ad_case c JOIN core.ad_native_object o ON o.id=c.ad_native_object_id
 JOIN core.ad_affected_set a ON a.id=c.affected_set_id JOIN core.ad_manual_policy p ON p.id=p_policy
 JOIN platform.ad_semantic_profile s ON s.id=o.semantic_profile_id
 JOIN core.ad_object_configuration_observation cfg ON cfg.id=p_configuration
 WHERE c.id=p_case
$$;

CREATE FUNCTION ops.generate_ad_manual_proposal(p_id uuid,p_case uuid,p_policy uuid,p_candidate uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,mart,platform,pg_temp AS $$
DECLARE kase mart.ad_case%ROWTYPE; policy core.ad_manual_policy%ROWTYPE;
 cfg core.ad_object_configuration_observation%ROWTYPE; candidate ops.ad_bid_candidate%ROWTYPE;
 task_recommendation ops.recommendation%ROWTYPE; target jsonb; deadline timestamptz;
BEGIN
 SELECT * INTO kase FROM mart.ad_case WHERE id=p_case AND superseded_at IS NULL FOR SHARE;
 SELECT * INTO policy FROM core.ad_manual_policy WHERE id=p_policy;
 IF kase.id IS NULL OR policy.id IS NULL OR policy.organization_id<>kase.organization_id
 OR policy.store_id<>kase.store_id OR policy.semantic_profile_id<>kase.semantic_profile_id
 OR policy.cause_code<>kase.cause_code OR clock_timestamp()<policy.effective_from OR clock_timestamp()>=policy.effective_to
 OR NOT EXISTS(SELECT 1 FROM core.ad_affected_set a WHERE a.id=kase.affected_set_id AND a.resolution_state='COMPLETE'
   AND cardinality(a.product_variant_ids)>0 AND NOT EXISTS(SELECT 1 FROM core.ad_affected_set n
     WHERE n.ad_native_object_id=a.ad_native_object_id AND n.resolved_at>a.resolved_at)) THEN
   RAISE EXCEPTION 'manual proposal policy or complete current case is unresolved' USING ERRCODE='MO097'; END IF;
 SELECT r.* INTO task_recommendation FROM ops.recommendation r WHERE r.organization_id=kase.organization_id
 AND r.action_kind='ADVERTISING_REVIEW' AND r.subject_kind='AD_NATIVE_OBJECT' AND r.subject_id=kase.ad_native_object_id
 AND r.proposed_parameters->>'caseId'=kase.id::text AND r.state='TASK_ONLY' ORDER BY r.created_at DESC LIMIT 1;
 IF task_recommendation.id IS NULL THEN RAISE EXCEPTION 'canonical responsibility recommendation required' USING ERRCODE='MO097'; END IF;
 SELECT * INTO cfg FROM core.ad_object_configuration_observation c WHERE c.organization_id=kase.organization_id
 AND c.ad_native_object_id=kase.ad_native_object_id AND c.semantic_profile_id=kase.semantic_profile_id
 AND c.evidence_grade IN ('OFFICIAL_API_READBACK','OFFICIAL_CONFIGURATION_EXPORT')
 AND EXISTS(SELECT 1 FROM core.fact_provenance provenance
   JOIN raw.raw_acquisition_observation observation ON observation.id=provenance.raw_observation_id AND observation.outcome_class='SUCCESS_BYTES'
   JOIN raw.raw_logical_unit unit ON unit.id=observation.logical_unit_id
   JOIN platform.ingestion_job job ON job.id=unit.job_id AND job.organization_id=c.organization_id
   JOIN core.store st ON st.id=kase.store_id AND st.marketplace_account_id=job.marketplace_account_id
   WHERE provenance.id=c.provenance_id AND provenance.organization_id=c.organization_id
     AND provenance.source_kind='MARKETPLACE_RAW' AND provenance.source_time IS NOT NULL
     AND job.platform_code=(SELECT platform_code FROM core.ad_native_object WHERE id=c.ad_native_object_id))
 AND c.observed_at<=clock_timestamp() AND c.observed_at>clock_timestamp()-make_interval(secs=>policy.configuration_max_age_seconds)
 AND NOT EXISTS(SELECT 1 FROM core.ad_object_configuration_observation n WHERE n.supersedes_observation_id=c.id)
 ORDER BY c.observed_at DESC,c.id LIMIT 1;
 IF cfg.id IS NULL THEN RAISE EXCEPTION 'current observed configuration required' USING ERRCODE='MO097'; END IF;
 IF policy.action_kind='AD_BID_CHANGE' THEN
   SELECT * INTO candidate FROM ops.ad_bid_candidate WHERE id=p_candidate AND case_id=kase.id
   AND organization_id=kase.organization_id AND candidate_basis=policy.candidate_basis
   AND current_bid_amount=cfg.observed_bid_amount AND bid_unit_code=cfg.bid_unit_code AND currency_code=cfg.bid_currency_code
   AND affected_set_digest=(SELECT affected_set_digest FROM core.ad_affected_set WHERE id=kase.affected_set_id);
   IF candidate.id IS NULL THEN RAISE EXCEPTION 'exact finite system bid candidate required' USING ERRCODE='MO097'; END IF;
   target:=jsonb_build_object('targetBid',candidate.provider_normalized_amount,'currentBid',cfg.observed_bid_amount,
     'currencyCode',candidate.currency_code,'bidUnitCode',candidate.bid_unit_code,'candidateId',candidate.id,'direction',candidate.direction);
 ELSIF policy.action_kind='AD_BUDGET_CHANGE' THEN
   IF p_candidate IS NOT NULL OR cfg.observed_budget_amount IS NULL THEN RAISE EXCEPTION 'budget proposal requires exact observed budget and no bid candidate' USING ERRCODE='MO097'; END IF;
   target:=jsonb_build_object('targetBudget',policy.target_budget,'currentBudget',cfg.observed_budget_amount,'currencyCode',policy.currency_code,
     'direction',CASE WHEN policy.target_budget<cfg.observed_budget_amount THEN 'PROTECTION_DECREASE' ELSE 'OPTIMIZATION_INCREASE' END);
 ELSE
   IF p_candidate IS NOT NULL THEN RAISE EXCEPTION 'status proposal has no bid candidate' USING ERRCODE='MO097'; END IF;
   IF cfg.native_status_raw IS NULL OR cfg.observed_status='UNKNOWN' THEN
     RAISE EXCEPTION 'current native status is unresolved' USING ERRCODE='MO097'; END IF;
   target:=jsonb_build_object('targetStatus',policy.target_status,'currentStatus',cfg.native_status_raw,
     'direction',CASE WHEN (SELECT status_semantics->>policy.target_status FROM platform.ad_semantic_profile WHERE id=policy.semantic_profile_id)
       IN ('PAUSED','STOPPED') THEN 'PROTECTION_DECREASE' ELSE 'OPTIMIZATION_INCREASE' END);
 END IF;
 deadline:=least(policy.effective_to,task_recommendation.valid_until,
   cfg.observed_at+make_interval(secs=>policy.configuration_max_age_seconds),clock_timestamp()+make_interval(secs=>policy.packet_lease_seconds));
 INSERT INTO ops.ad_manual_proposal(id,organization_id,case_id,responsibility_recommendation_id,policy_id,candidate_id,
   ad_native_object_id,store_id,affected_set_id,affected_set_digest,observed_configuration_id,action_kind,intended_state,
   authority_snapshot,generated_at,expires_at)
 VALUES(p_id,kase.organization_id,kase.id,task_recommendation.id,policy.id,p_candidate,kase.ad_native_object_id,kase.store_id,
   kase.affected_set_id,(SELECT affected_set_digest FROM core.ad_affected_set WHERE id=kase.affected_set_id),cfg.id,policy.action_kind,
   target,ops.ad_manual_snapshot(kase.id,policy.id,cfg.id),clock_timestamp(),deadline);
 RETURN p_id;
END $$;

CREATE FUNCTION ops.ad_manual_proposal_current(p_proposal uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,mart,pg_temp AS $$
 SELECT p.expires_at>statement_timestamp() AND p.authority_snapshot=ops.ad_manual_snapshot(p.case_id,p.policy_id,p.observed_configuration_id)
 AND EXISTS(SELECT 1 FROM mart.ad_case c WHERE c.id=p.case_id AND c.superseded_at IS NULL)
 AND EXISTS(SELECT 1 FROM ops.recommendation r WHERE r.id=p.responsibility_recommendation_id AND r.state='TASK_ONLY' AND r.valid_until>statement_timestamp())
 AND NOT EXISTS(SELECT 1 FROM core.ad_object_configuration_observation n WHERE n.ad_native_object_id=p.ad_native_object_id
   AND n.observed_at>(SELECT observed_at FROM core.ad_object_configuration_observation WHERE id=p.observed_configuration_id))
 AND NOT EXISTS(SELECT 1 FROM core.ad_affected_set n WHERE n.ad_native_object_id=p.ad_native_object_id
   AND n.resolved_at>(SELECT resolved_at FROM core.ad_affected_set WHERE id=p.affected_set_id))
 FROM ops.ad_manual_proposal p WHERE p.id=p_proposal
$$;

CREATE FUNCTION ops.select_ad_manual_packet(p_packet uuid,p_proposal uuid,p_baseline uuid,p_reason text,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,mart,platform,iam,pg_temp AS $$
DECLARE proposal ops.ad_manual_proposal%ROWTYPE; g iam.ad_invocation_grant%ROWTYPE;
BEGIN
 SELECT * INTO proposal FROM ops.ad_manual_proposal WHERE id=p_proposal;
 g:=ops.consume_ad_control_invocation(p_proof,'MANUAL_PACKET_SELECT',p_proposal,p_packet);
 IF proposal.id IS NULL OR g.organization_id<>proposal.organization_id OR NOT coalesce(ops.ad_manual_proposal_current(p_proposal),false)
 OR NOT ops.ad_manual_actor_scoped(g.actor_user_id,proposal.organization_id,proposal.store_id,proposal.affected_set_id,
   'MARKETPLACE_OPERATOR','ADVERTISING_TASK_ACT') OR NOT EXISTS(
   SELECT 1 FROM ops.ad_outcome_baseline b JOIN core.ad_manual_policy policy ON policy.id=proposal.policy_id
   WHERE b.id=p_baseline AND b.organization_id=proposal.organization_id AND b.manual_proposal_id=proposal.id
   AND b.ad_native_object_id=proposal.ad_native_object_id AND b.affected_set_id=proposal.affected_set_id
   AND b.affected_set_digest=proposal.affected_set_digest AND b.outcome_policy_id=policy.outcome_policy_id
   AND b.case_calculation_id=(SELECT calculation_id FROM mart.ad_case WHERE id=proposal.case_id)
   AND b.state='COMPLETE' AND b.valid_until>clock_timestamp()
   AND ops.ad_outcome_baseline_is_canonical(b.id,clock_timestamp()) IS TRUE) THEN RAISE EXCEPTION 'manual selection scope or authority denied' USING ERRCODE='MO064'; END IF;
 INSERT INTO ops.ad_manual_execution_packet(id,organization_id,case_id,ad_native_object_id,store_id,platform_code,
   affected_set_id,affected_set_digest,semantic_profile_id,action_kind,observed_configuration_id,intended_state,
   reason,evidence_reference,maker_user_id,expected_impact,verification_plan,state,issued_at,expires_at,correlation_id,
   created_at,updated_at,proposal_id,manual_policy_id,authority_snapshot,outcome_baseline_id)
 SELECT p_packet,proposal.organization_id,proposal.case_id,proposal.ad_native_object_id,proposal.store_id,o.platform_code,
   proposal.affected_set_id,proposal.affected_set_digest,o.semantic_profile_id,proposal.action_kind,proposal.observed_configuration_id,
   proposal.intended_state,p_reason,'manual-proposal:'||proposal.id,g.actor_user_id,
   jsonb_build_object('responsibilityRecommendationId',proposal.responsibility_recommendation_id,'state','OUTCOME_UNPROVEN'),
   jsonb_build_object('evidenceGrade','INDEPENDENT_OR_OFFICIAL','mode',policy.verification_mode,'policyId',policy.id,
     'policyVersion',policy.policy_version,'apiProfileState',profile.verification_state),
   'MANUAL_PACKET_DRAFT',clock_timestamp(),proposal.expires_at,'manual:'||p_packet,clock_timestamp(),clock_timestamp(),
   proposal.id,policy.id,proposal.authority_snapshot,p_baseline
 FROM core.ad_native_object o JOIN core.ad_manual_policy policy ON policy.id=proposal.policy_id
 JOIN platform.ad_semantic_profile profile ON profile.id=o.semantic_profile_id WHERE o.id=proposal.ad_native_object_id;
 RETURN p_packet;
END $$;

CREATE FUNCTION ops.decide_ad_manual_packet(p_packet uuid,p_expected bigint,p_approve boolean,p_proof text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
DECLARE packet ops.ad_manual_execution_packet%ROWTYPE; g iam.ad_invocation_grant%ROWTYPE; role_code text; action_code text;
BEGIN
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=p_packet FOR UPDATE;
 g:=ops.consume_ad_control_invocation(p_proof,CASE WHEN p_approve THEN 'MANUAL_PACKET_APPROVE' ELSE 'MANUAL_PACKET_ENDORSE' END,p_packet,p_packet);
 role_code:=CASE WHEN p_approve THEN 'OWNER' ELSE 'OPS_LEAD' END;
 action_code:=CASE WHEN p_approve THEN 'ADVERTISING_MANUAL_APPROVE' ELSE 'ADVERTISING_MANUAL_ENDORSE' END;
 IF packet.id IS NULL OR packet.version<>p_expected OR g.organization_id<>packet.organization_id
 OR g.actor_user_id=packet.maker_user_id OR (p_approve AND g.actor_user_id=packet.endorser_user_id)
 OR packet.state<>(CASE WHEN p_approve THEN 'MANUAL_PACKET_ENDORSED' ELSE 'MANUAL_PACKET_DRAFT' END)
 OR NOT coalesce(ops.ad_manual_proposal_current(packet.proposal_id),false)
 OR NOT EXISTS(SELECT 1 FROM ops.ad_outcome_baseline b WHERE b.id=packet.outcome_baseline_id AND b.manual_proposal_id=packet.proposal_id AND b.state='COMPLETE' AND b.valid_until>clock_timestamp()
   AND ops.ad_outcome_baseline_is_canonical(b.id,clock_timestamp()) IS TRUE)
 OR NOT ops.ad_manual_actor_scoped(g.actor_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,role_code,action_code)
 OR NOT ops.ad_manual_actor_scoped(g.actor_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,
   role_code,'ADVERTISING_DECISION_EVIDENCE_VIEW') THEN RAISE EXCEPTION 'manual approval scope, independence or authority denied' USING ERRCODE='MO064'; END IF;
 UPDATE ops.ad_manual_execution_packet SET state=CASE WHEN p_approve THEN 'MANUAL_PACKET_ISSUED' ELSE 'MANUAL_PACKET_ENDORSED' END,
   endorser_user_id=CASE WHEN p_approve THEN endorser_user_id ELSE g.actor_user_id END,
   approver_user_id=CASE WHEN p_approve THEN g.actor_user_id ELSE NULL END,
   updated_at=clock_timestamp(),version=version+1 WHERE id=p_packet;
END $$;

CREATE FUNCTION ops.start_ad_manual_execution(p_packet uuid,p_expected bigint,p_proof text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,mart,iam,pg_temp AS $$
DECLARE packet ops.ad_manual_execution_packet%ROWTYPE; g iam.ad_invocation_grant%ROWTYPE; reservation uuid;
 variants uuid[]; lane text;
BEGIN
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=p_packet FOR UPDATE;
 g:=ops.consume_ad_control_invocation(p_proof,'MANUAL_EXECUTION_START',p_packet,p_packet);
 IF packet.id IS NULL OR packet.version<>p_expected OR g.organization_id<>packet.organization_id
 OR packet.state<>'MANUAL_PACKET_ISSUED' OR packet.approver_user_id IS NULL OR packet.endorser_user_id IS NULL
 OR NOT coalesce(ops.ad_manual_proposal_current(packet.proposal_id),false)
 OR NOT EXISTS(SELECT 1 FROM ops.ad_outcome_baseline b WHERE b.id=packet.outcome_baseline_id AND b.manual_proposal_id=packet.proposal_id AND b.state='COMPLETE' AND b.valid_until>clock_timestamp()
   AND ops.ad_outcome_baseline_is_canonical(b.id,clock_timestamp()) IS TRUE)
 OR NOT ops.ad_manual_actor_scoped(g.actor_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,
   'MARKETPLACE_OPERATOR','ADVERTISING_MANUAL_EXECUTE')
 OR NOT ops.ad_manual_actor_scoped(packet.endorser_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,
   'OPS_LEAD','ADVERTISING_MANUAL_ENDORSE')
 OR NOT ops.ad_manual_actor_scoped(packet.approver_user_id,packet.organization_id,packet.store_id,packet.affected_set_id,
   'OWNER','ADVERTISING_MANUAL_APPROVE') THEN RAISE EXCEPTION 'manual execution authority or live scope denied' USING ERRCODE='MO064'; END IF;
 IF cardinality(ops.ad_active_containment(packet.organization_id,packet.ad_native_object_id,packet.store_id,
   packet.platform_code,'ad-bid-change',packet.affected_set_digest))>0 THEN RAISE EXCEPTION 'manual execution is contained' USING ERRCODE='MO097'; END IF;
 SELECT a.product_variant_ids,c.lane INTO variants,lane FROM core.ad_affected_set a JOIN mart.ad_case c ON c.affected_set_id=a.id WHERE c.id=packet.case_id;
 reservation:=ops.take_ad_action_reservation(gen_random_uuid(),packet.organization_id,packet.ad_native_object_id,
   packet.store_id,packet.affected_set_id,packet.affected_set_digest,variants,'CONFIRMED_MANUAL_PACKET',packet.id,
   coalesce(packet.intended_state->>'direction','PROTECTION_DECREASE'),lane,'manual:'||packet.id);
 UPDATE ops.ad_manual_execution_packet SET executor_user_id=g.actor_user_id,execution_started_at=clock_timestamp(),
   reservation_id=reservation,state='MANUAL_EXECUTION_IN_PROGRESS',updated_at=clock_timestamp(),version=version+1 WHERE id=p_packet;
END $$;

INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note) VALUES
 ('core','ad_manual_policy','NO_ROUTE',NULL,'immutable Owner manual business plan; no Provider capability'),
 ('ops','ad_manual_proposal','NO_ROUTE',NULL,'deterministic canonical manual proposal; no execution authority');
REVOKE ALL ON FUNCTION ops.publish_ad_manual_policy(jsonb,text),ops.ad_manual_snapshot(uuid,uuid,uuid),
 ops.generate_ad_manual_proposal(uuid,uuid,uuid,uuid),ops.ad_manual_proposal_current(uuid),
 ops.select_ad_manual_packet(uuid,uuid,uuid,text,text),ops.decide_ad_manual_packet(uuid,bigint,boolean,text),
 ops.start_ad_manual_execution(uuid,bigint,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.publish_ad_manual_policy(jsonb,text),ops.generate_ad_manual_proposal(uuid,uuid,uuid,uuid),
 ops.ad_manual_proposal_current(uuid),ops.select_ad_manual_packet(uuid,uuid,uuid,text,text),
 ops.decide_ad_manual_packet(uuid,bigint,boolean,text),ops.start_ad_manual_execution(uuid,bigint,text) TO marketops_app;

-- The verification endpoint selects a workflow, never a caller-named grade.
-- Official proof must resolve an actual successful Raw observation belonging
-- to the same Organization/account/platform, and the current canonical native
-- configuration row. A string saying "official" is never evidence.
CREATE FUNCTION ops.record_ad_manual_observation(p_id uuid,p_packet uuid,p_expected bigint,
 p_kind text,p_observed_value text,p_configuration uuid,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,iam,platform,raw,pg_temp AS $$
DECLARE packet ops.ad_manual_execution_packet%ROWTYPE; policy core.ad_manual_policy%ROWTYPE;
 g iam.ad_invocation_grant%ROWTYPE; cfg core.ad_object_configuration_observation%ROWTYPE;
 expected_value text; actual_value text; field_path text; grade text; observed timestamptz;
 proven boolean; conflicted boolean; verifier uuid;
BEGIN
 SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=p_packet FOR UPDATE;
 IF p_kind NOT IN ('REPORT','INDEPENDENT','OFFICIAL') THEN RAISE EXCEPTION 'unknown manual observation workflow' USING ERRCODE='MO097'; END IF;
 g:=ops.consume_ad_control_invocation(p_proof,CASE WHEN p_kind='REPORT' THEN 'MANUAL_EXECUTION_REPORT' ELSE 'MANUAL_INDEPENDENT_VERIFY' END,p_packet,p_packet);
 IF packet.id IS NULL OR packet.version<>p_expected OR g.organization_id<>packet.organization_id
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
   IF p_kind='INDEPENDENT' THEN
     IF policy.verification_mode='OFFICIAL_ONLY' OR p_configuration IS NOT NULL
       OR p_observed_value IS NULL OR length(p_observed_value)>128
       OR (packet.action_kind IN ('AD_BID_CHANGE','AD_BUDGET_CHANGE') AND p_observed_value !~ '^[0-9]+(\.[0-9]{1,4})?$')
       OR (packet.action_kind='AD_STATUS_CHANGE' AND p_observed_value !~ '^[A-Za-z0-9_ -]{1,128}$') THEN
       RAISE EXCEPTION 'typed independent observation does not satisfy the immutable plan' USING ERRCODE='MO097'; END IF;
     grade:='INDEPENDENT_MANUAL_VERIFICATION'; actual_value:=p_observed_value; observed:=clock_timestamp();
   ELSE
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
 END IF;
 conflicted:=CASE WHEN packet.action_kind IN ('AD_BID_CHANGE','AD_BUDGET_CHANGE')
   THEN actual_value::numeric<>expected_value::numeric ELSE actual_value<>expected_value END;
 proven:=p_kind<>'REPORT' AND NOT conflicted;
 INSERT INTO ops.ad_manual_configuration_verification(id,organization_id,packet_id,evidence_grade,executor_user_id,
   verifier_user_id,observed_field_path,observed_value,observed_at,evidence_reference,conflict_state,proves_configuration,
   recorded_at,correlation_id,configuration_observation_id)
 VALUES(p_id,packet.organization_id,p_packet,grade,packet.executor_user_id,verifier,field_path,actual_value,observed,
   CASE WHEN p_configuration IS NULL THEN 'manual-attestation:'||p_id ELSE 'configuration-observation:'||p_configuration END,
   CASE WHEN conflicted THEN 'CONFLICTED' ELSE 'NONE' END,proven,clock_timestamp(),'manual:'||p_packet,p_configuration);
 UPDATE ops.ad_manual_execution_packet SET state=CASE WHEN proven THEN 'MANUAL_CONFIGURATION_VERIFIED'
   WHEN conflicted THEN 'MANUAL_EXECUTION_UNCERTAIN' ELSE 'ACTION_REPORTED_CONFIGURATION_UNVERIFIED' END,
   current_proof_id=CASE WHEN proven THEN p_id ELSE NULL END,updated_at=clock_timestamp(),version=version+1 WHERE id=p_packet;
 PERFORM ops.observe_ad_reservation_condition(packet.reservation_id,'CONFIGURATION_RESOLVED',proven);
 PERFORM ops.observe_ad_reservation_condition(packet.reservation_id,'UNKNOWN_OR_MISMATCH_OPEN',NOT proven);
 -- Configuration proof is never an early sales observation or an Outcome.
 RETURN p_id;
END $$;
REVOKE ALL ON FUNCTION ops.record_ad_manual_observation(uuid,uuid,bigint,text,text,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_ad_manual_observation(uuid,uuid,bigint,text,text,uuid,text) TO marketops_app;

CREATE FUNCTION ops.invalidate_manual_proof_on_later_configuration() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE packet ops.ad_manual_execution_packet%ROWTYPE; actual text; expected text;
BEGIN
 FOR packet IN SELECT * FROM ops.ad_manual_execution_packet WHERE organization_id=NEW.organization_id
   AND ad_native_object_id=NEW.ad_native_object_id AND execution_started_at IS NOT NULL
   AND state='MANUAL_CONFIGURATION_VERIFIED' AND NEW.observed_at>=execution_started_at FOR UPDATE
 LOOP
   actual:=CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN NEW.observed_bid_amount::text
     WHEN 'AD_BUDGET_CHANGE' THEN NEW.observed_budget_amount::text ELSE NEW.native_status_raw END;
   expected:=packet.intended_state->>CASE packet.action_kind WHEN 'AD_BID_CHANGE' THEN 'targetBid'
     WHEN 'AD_BUDGET_CHANGE' THEN 'targetBudget' ELSE 'targetStatus' END;
   IF actual IS NULL OR (CASE WHEN packet.action_kind IN ('AD_BID_CHANGE','AD_BUDGET_CHANGE')
     THEN actual::numeric<>expected::numeric ELSE actual<>expected END) THEN
     UPDATE ops.ad_manual_execution_packet SET state='MANUAL_EXECUTION_UNCERTAIN',current_proof_id=NULL,
       updated_at=clock_timestamp(),version=version+1 WHERE id=packet.id;
     PERFORM ops.observe_ad_reservation_condition(packet.reservation_id,'CONFIGURATION_RESOLVED',false);
     PERFORM ops.observe_ad_reservation_condition(packet.reservation_id,'UNKNOWN_OR_MISMATCH_OPEN',true);
   END IF;
 END LOOP;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.invalidate_manual_proof_on_later_configuration() FROM PUBLIC;
CREATE TRIGGER ad_manual_later_configuration AFTER INSERT ON core.ad_object_configuration_observation
 FOR EACH ROW EXECUTE FUNCTION ops.invalidate_manual_proof_on_later_configuration();

-- Expiry is a server-time sweep; in-flight work retains its reservation and evidence.
CREATE FUNCTION ops.expire_ad_manual_packets() RETURNS integer LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE changed integer;
BEGIN
 UPDATE ops.ad_manual_execution_packet SET state='MANUAL_PACKET_EXPIRED',updated_at=clock_timestamp(),version=version+1
 WHERE state IN ('MANUAL_PACKET_DRAFT','MANUAL_PACKET_ENDORSED','MANUAL_PACKET_ISSUED') AND expires_at<=clock_timestamp();
 GET DIAGNOSTICS changed=ROW_COUNT; RETURN changed;
END $$;
REVOKE ALL ON FUNCTION ops.expire_ad_manual_packets() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.expire_ad_manual_packets() TO marketops_app;
