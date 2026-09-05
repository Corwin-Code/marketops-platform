-- R1: immutable authorization, one-use authenticated invocation, exact gate scope,
-- append-only invalidation and six-axis reservation admission. No Gate is activated.
-- A profile may have an explicitly published finite authority period. Null
-- retains the existing status-governed period; it is never a fabricated expiry.
ALTER TABLE platform.ad_semantic_profile ADD COLUMN effective_to timestamptz,
 ADD CONSTRAINT ad_semantic_profile_effective_period_ck
 CHECK(effective_to IS NULL OR effective_to>created_at);

CREATE TABLE iam.ad_invocation_grant (
    proof_hash text PRIMARY KEY CHECK (proof_hash ~ '^[0-9a-f]{64}$'),
    actor_user_id uuid NOT NULL REFERENCES iam.user_account(id),
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    identity_provider_id uuid NOT NULL,
    subject_digest text NOT NULL,
    session_digest text NOT NULL,
    authenticated_at timestamptz NOT NULL,
    step_up_valid_until timestamptz NOT NULL,
    recommendation_id uuid NOT NULL,
    approval_decision_id uuid NOT NULL,
    backend_pid integer NOT NULL,
    transaction_id bigint NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '30 seconds')
);
REVOKE ALL ON iam.ad_invocation_grant FROM PUBLIC, marketops_app;
CREATE FUNCTION iam.issue_ad_invocation_grant(p_proof_hash text, p_actor uuid, p_org uuid,
    p_provider uuid, p_subject text, p_session text, p_authenticated timestamptz,
    p_step_up_until timestamptz, p_recommendation uuid, p_approval uuid,
    p_backend integer, p_transaction bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, iam, pg_temp AS $$
BEGIN
    IF session_user = 'marketops_app' OR p_step_up_until <= clock_timestamp()
       OR p_authenticated > clock_timestamp() OR p_subject !~ '^[0-9a-f]{64}$'
       OR p_session !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'trusted current authentication required' USING ERRCODE = 'MO092';
    END IF;
    INSERT INTO iam.ad_invocation_grant(proof_hash,actor_user_id,organization_id,
        identity_provider_id,subject_digest,session_digest,authenticated_at,
        step_up_valid_until,recommendation_id,approval_decision_id,backend_pid,
        transaction_id,expires_at)
    VALUES(p_proof_hash,p_actor,p_org,p_provider,p_subject,p_session,p_authenticated,
        p_step_up_until,p_recommendation,p_approval,p_backend,p_transaction,
        least(clock_timestamp()+interval '25 seconds',p_step_up_until));
END $$;
REVOKE ALL ON FUNCTION iam.issue_ad_invocation_grant(text,uuid,uuid,uuid,text,text,
    timestamptz,timestamptz,uuid,uuid,integer,bigint) FROM PUBLIC, marketops_app;
-- Role provisioning belongs to the separate identity boundary bootstrap. The
-- normal migrator is NOCREATEROLE; absence is an intentional disabled condition.
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'marketops_identity_issuer') THEN
        GRANT USAGE ON SCHEMA iam TO marketops_identity_issuer;
        GRANT EXECUTE ON FUNCTION iam.issue_ad_invocation_grant(text,uuid,uuid,uuid,text,text,
            timestamptz,timestamptz,uuid,uuid,integer,bigint) TO marketops_identity_issuer;
    END IF;
END $$;

-- Credential metadata deliberately remains UNVERIFIED under its historic
-- invariant. A separately reviewed, exact-purpose evidence record authorizes
-- use; this migration provisions no credential, secret or active attestation.
CREATE TABLE platform.ad_write_credential_attestation (
 id uuid PRIMARY KEY, credential_id uuid NOT NULL REFERENCES platform.credential_metadata(id),
 organization_id uuid NOT NULL REFERENCES core.organization(id),
 marketplace_account_id uuid NOT NULL, store_ids uuid[] NOT NULL CHECK(cardinality(store_ids)>0),
 verifier_user_id uuid NOT NULL REFERENCES iam.user_account(id),
 evidence_reference text NOT NULL CHECK(length(btrim(evidence_reference))>0),
 verified_at timestamptz NOT NULL, valid_until timestamptz NOT NULL CHECK(valid_until>verified_at),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','VERIFIED','REVOKED')),
 FOREIGN KEY(marketplace_account_id,organization_id) REFERENCES core.marketplace_account(id,organization_id)
);
REVOKE ALL ON platform.ad_write_credential_attestation FROM PUBLIC,marketops_app;
GRANT SELECT ON platform.ad_write_credential_attestation TO marketops_app;
CREATE FUNCTION ops.ad_credential_authority_expiry(p_credential uuid,p_store uuid) RETURNS timestamptz
LANGUAGE sql STABLE SET search_path=pg_catalog,platform,core,pg_temp AS $$
 SELECT min(least(e.valid_until,c.expires_at)) FROM platform.credential_metadata c
 JOIN platform.ad_write_credential_attestation e ON e.credential_id=c.id
 JOIN core.store st ON st.id=p_store AND st.organization_id=c.organization_id
 WHERE c.id=p_credential AND c.status='ACTIVE' AND c.purpose_code='ADS_WRITE'
 AND c.effective_from<=statement_timestamp() AND c.expires_at>statement_timestamp()
 AND e.organization_id=c.organization_id AND e.marketplace_account_id=c.marketplace_account_id
 AND st.marketplace_account_id=c.marketplace_account_id AND p_store=ANY(e.store_ids)
 AND e.status='VERIFIED' AND e.verified_at<=statement_timestamp() AND e.valid_until>statement_timestamp()
 AND (c.scope_mode='ACCOUNT' OR EXISTS(SELECT 1 FROM platform.credential_store_scope cs
 WHERE cs.credential_id=c.id AND cs.store_id=p_store))
$$;
REVOKE ALL ON FUNCTION ops.ad_credential_authority_expiry(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_credential_authority_expiry(uuid,uuid) TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
 VALUES('platform','ad_write_credential_attestation','NO_ROUTE',NULL,'inactive exact advertising credential verification evidence; no secret provisioning');

CREATE FUNCTION ops.ad_nonnegative_numeric(p_value text) RETURNS numeric LANGUAGE sql IMMUTABLE
SET search_path=pg_catalog,pg_temp AS $$
 SELECT CASE WHEN p_value ~ '^[0-9]+(\.[0-9]+)?$' AND length(p_value)<=40 THEN p_value::numeric END
$$;
REVOKE ALL ON FUNCTION ops.ad_nonnegative_numeric(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_nonnegative_numeric(text) TO marketops_app;

CREATE TABLE ops.ad_gate_authority (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    gate_kind text NOT NULL CHECK (gate_kind IN ('GATE_EV','GATE_E')),
    platform_code text NOT NULL REFERENCES core.marketplace_platform(code),
    marketplace_account_id uuid NOT NULL,
    store_id uuid NOT NULL,
    capability_code text NOT NULL CHECK (capability_code='ad-bid-change'),
    native_object_ids uuid[] NOT NULL CHECK (cardinality(native_object_ids)>0),
    direction text NOT NULL CHECK (direction IN ('PROTECTION_DECREASE','OPTIMIZATION_INCREASE','EXACT_PRIOR_BID_COMPENSATION')),
    candidate_basis text NOT NULL,
    bundle_id uuid NOT NULL REFERENCES ops.ad_decision_policy_bundle(id),
    exact_head_sha text NOT NULL CHECK (exact_head_sha ~ '^[0-9a-f]{40}$'),
    exact_tree_sha text NOT NULL CHECK (exact_tree_sha ~ '^[0-9a-f]{40}$'),
    owner_user_id uuid NOT NULL REFERENCES iam.user_account(id),
    approved_at timestamptz NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL CHECK (valid_until > valid_from),
    max_commands integer NOT NULL CHECK (max_commands>0),
    max_bid_change_amount numeric(18,4) NOT NULL CHECK (max_bid_change_amount>=0),
    currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    stop_conditions jsonb NOT NULL CHECK (jsonb_typeof(stop_conditions)='array' AND jsonb_array_length(stop_conditions)>0),
    evidence_reference text NOT NULL,
    controller_verdict_reference text NOT NULL,
    security_attestation_reference text NOT NULL,
    restoration_plan_reference text NOT NULL,
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','REVOKED','EXPIRED')),
    production_write_enabled boolean NOT NULL DEFAULT false,
    exact_object_values jsonb NOT NULL CHECK(jsonb_typeof(exact_object_values)='object'),
    release_evidence_reference text NOT NULL,
    shadow_evidence_reference text NOT NULL,
    adoption_evidence_reference text NOT NULL,
    execution_evidence_reference text NOT NULL,
    early_safety_evidence_reference text NOT NULL,
    operating_coverage_reference text NOT NULL,
    demonstrated_object_ids uuid[] NOT NULL,
    predecessor_gate_ev_id uuid REFERENCES ops.ad_gate_authority(id),
    CHECK(native_object_ids <@ demonstrated_object_ids),
    CHECK(gate_kind<>'GATE_E' OR predecessor_gate_ev_id IS NOT NULL),
    CHECK (NOT production_write_enabled OR status='ACTIVE'),
    FOREIGN KEY(store_id,organization_id) REFERENCES core.store(id,organization_id),
    FOREIGN KEY(marketplace_account_id,organization_id) REFERENCES core.marketplace_account(id,organization_id)
);
CREATE TABLE ops.ad_ordinary_promotion (
    id uuid PRIMARY KEY, gate_authority_id uuid NOT NULL REFERENCES ops.ad_gate_authority(id),
    bundle_id uuid NOT NULL REFERENCES ops.ad_decision_policy_bundle(id),
    material_envelope_amount numeric(18,4) NOT NULL CHECK(material_envelope_amount>0),
    shadow_evidence_reference text NOT NULL, pilot_evidence_reference text NOT NULL,
    matured_outcome_reference text NOT NULL, rollback_evidence_reference text NOT NULL,
    owner_approval_reference text NOT NULL, valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL CHECK(valid_until>valid_from),
    status text NOT NULL CHECK(status IN ('DRAFT','ACTIVE','REVOKED')),
    CONSTRAINT ad_ordinary_promotion_evidence_present CHECK (
        nullif(btrim(shadow_evidence_reference),'') IS NOT NULL
        AND nullif(btrim(pilot_evidence_reference),'') IS NOT NULL
        AND nullif(btrim(matured_outcome_reference),'') IS NOT NULL
        AND nullif(btrim(rollback_evidence_reference),'') IS NOT NULL
        AND nullif(btrim(owner_approval_reference),'') IS NOT NULL)
);
ALTER TABLE ops.ad_decision_policy_bundle ADD COLUMN gate_authority_id uuid REFERENCES ops.ad_gate_authority(id);
-- Initial immutable Bundle content and its reciprocal promotion are inserted
-- atomically. A missing promotion still fails the transaction at commit.
ALTER TABLE ops.ad_decision_policy_bundle ADD CONSTRAINT ad_bundle_ordinary_promotion_fk
    FOREIGN KEY (ordinary_promotion_id) REFERENCES ops.ad_ordinary_promotion(id)
    DEFERRABLE INITIALLY DEFERRED;
REVOKE ALL ON ops.ad_gate_authority,ops.ad_ordinary_promotion FROM PUBLIC,marketops_app;
GRANT SELECT ON ops.ad_gate_authority,ops.ad_ordinary_promotion TO marketops_app;

CREATE FUNCTION ops.ad_bundle_authority_snapshot(p_bundle uuid)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = pg_catalog,ops,core,platform,pg_temp AS $$
 SELECT jsonb_build_object('bundle',to_jsonb(b),'semantic',(SELECT to_jsonb(x) FROM platform.ad_semantic_profile x WHERE x.id=b.semantic_profile_id),
 'conversion',(SELECT to_jsonb(x) FROM core.ad_conversion_definition x WHERE x.id=b.conversion_definition_id),
 'allowableCpa',(SELECT to_jsonb(x) FROM core.ad_allowable_cpa_definition x WHERE x.id=b.allowable_cpa_definition_id),
 'qualification',(SELECT to_jsonb(x) FROM core.ad_optimization_qualification_policy x WHERE x.id=b.qualification_policy_id),
 'target',(SELECT to_jsonb(x) FROM core.ad_bid_target_policy x WHERE x.id=b.target_policy_id),
 'outcome',(SELECT to_jsonb(x) FROM core.ad_outcome_policy x WHERE x.id=b.outcome_policy_id),
 'priority',(SELECT to_jsonb(x) FROM core.ad_priority_policy x WHERE x.id=b.priority_policy_id),
 'humanSlo',(SELECT to_jsonb(x) FROM core.ad_human_slo_profile x WHERE x.id=b.human_slo_profile_id),
 'lease',(SELECT to_jsonb(x) FROM core.ad_approval_lease_policy x WHERE x.id=b.approval_lease_policy_id),
 'exposure',(SELECT to_jsonb(x) FROM core.ad_exposure_envelope x WHERE x.id=b.exposure_envelope_id),
 'materiality',(SELECT to_jsonb(x) FROM core.ad_materiality_policy x WHERE x.id=b.materiality_policy_id),
 'freshness',(SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb)
   FROM core.ad_freshness_profile x WHERE x.organization_id=b.organization_id AND x.status='ACTIVE'
   AND (x.scope_kind='ORGANIZATION' OR x.scope_kind='PLATFORM' AND x.platform_code=b.platform_code
        OR x.scope_kind='STORE' AND x.store_ref_id=b.store_id)
   AND (x.semantic_profile_id IS NULL OR x.semantic_profile_id=b.semantic_profile_id)),
 'gate',(SELECT to_jsonb(x) FROM ops.ad_gate_authority x WHERE x.id=b.gate_authority_id),
 'promotion',(SELECT to_jsonb(x) FROM ops.ad_ordinary_promotion x WHERE x.id=b.ordinary_promotion_id))
 FROM ops.ad_decision_policy_bundle b WHERE b.id=p_bundle
$$;
REVOKE ALL ON FUNCTION ops.ad_bundle_authority_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_bundle_authority_snapshot(uuid) TO marketops_app;

CREATE TABLE ops.ad_action_authorization (
    id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES core.organization(id),
    recommendation_id uuid NOT NULL UNIQUE REFERENCES ops.recommendation(id),
    approval_decision_id uuid NOT NULL UNIQUE REFERENCES ops.approval_decision(id),
    candidate_id uuid NOT NULL REFERENCES ops.ad_bid_candidate(id),
    outcome_baseline_id uuid NOT NULL, -- FK added when the frozen baseline table is created in V0059.
    bundle_id uuid NOT NULL REFERENCES ops.ad_decision_policy_bundle(id), bundle_version integer NOT NULL,
    maker_user_id uuid NOT NULL REFERENCES iam.user_account(id),
    endorser_user_id uuid NOT NULL REFERENCES iam.user_account(id),
    final_approver_user_id uuid NOT NULL REFERENCES iam.user_account(id),
    final_approved_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    bounds jsonb NOT NULL, authority_snapshot jsonb NOT NULL,
    materiality_route text NOT NULL CHECK(materiality_route IN ('MATERIAL_IMPACT','ORDINARY_IMPACT')),
    CHECK (maker_user_id<>endorser_user_id AND maker_user_id<>final_approver_user_id
           AND (materiality_route='ORDINARY_IMPACT' OR endorser_user_id<>final_approver_user_id)), CHECK(expires_at>final_approved_at)
);
CREATE TABLE ops.ad_authority_invalidation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL,
    authorization_id uuid NOT NULL REFERENCES ops.ad_action_authorization(id),
    cause_reference uuid NOT NULL, cause_code text NOT NULL,
    invalidated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(authorization_id,cause_reference)
);
REVOKE ALL ON ops.ad_action_authorization,ops.ad_authority_invalidation FROM PUBLIC,marketops_app;
GRANT SELECT ON ops.ad_action_authorization,ops.ad_authority_invalidation TO marketops_app;
CREATE FUNCTION ops.ad_control_history_is_immutable() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$ BEGIN
 RAISE EXCEPTION 'advertising authority history is immutable' USING ERRCODE='MO096';
END $$;
REVOKE ALL ON FUNCTION ops.ad_control_history_is_immutable() FROM PUBLIC;
CREATE TRIGGER ad_action_authorization_immutable BEFORE UPDATE OR DELETE ON ops.ad_action_authorization
 FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
CREATE TRIGGER ad_authority_invalidation_immutable BEFORE UPDATE OR DELETE ON ops.ad_authority_invalidation
 FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE FUNCTION ops.ad_required_action_evidence_kinds(p_basis text,p_cause text) RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
 SELECT CASE WHEN p_basis='MAX_CPC_BOUNDED' THEN ARRAY['OFFICIAL_AD_SPEND','OFFICIAL_AD_TRAFFIC',
   'AD_LINKED_SALE_EVENT','COST_AND_FEE','AD_OBJECT_CONFIGURATION','AFFECTED_SET']
 WHEN p_basis='CAUSE_BOUND_PROTECTION_STEP' AND p_cause='PROMOTED_VARIANT_NOT_SELLABLE'
 THEN ARRAY['OFFICIAL_AD_SPEND','AD_OBJECT_CONFIGURATION','AFFECTED_SET','SELLABILITY']
 WHEN p_basis='CAUSE_BOUND_PROTECTION_STEP' AND p_cause='PROMOTED_VARIANT_UNAVAILABLE'
 THEN ARRAY['OFFICIAL_AD_SPEND','AD_OBJECT_CONFIGURATION','AFFECTED_SET','AVAILABILITY'] END
$$;
REVOKE ALL ON FUNCTION ops.ad_required_action_evidence_kinds(text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_required_action_evidence_kinds(text,text) TO marketops_app;

CREATE FUNCTION ops.ad_action_blockers(p_basis text,p_cause text,p_blockers text[]) RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
 SELECT ARRAY(SELECT blocker FROM unnest(coalesce(p_blockers,ARRAY['BLOCKER_PROJECTION_MISSING'])) blocker
 WHERE NOT coalesce((p_basis='CAUSE_BOUND_PROTECTION_STEP'
 AND p_cause IN('PROMOTED_VARIANT_NOT_SELLABLE','PROMOTED_VARIANT_UNAVAILABLE')
 AND (blocker=ANY(ARRAY['AD_LINKED_QUANTITY_LINEAGE_UNAVAILABLE','MIXED_OR_UNRESOLVED_SALES_CURRENCY',
   'AD_LINKED_CONVERSION_NOT_WRITE_GRADE','PROVIDER_TO_CANONICAL_ATTRIBUTION_GAP_MATERIAL'])
 OR blocker~'^(LINE_ECONOMICS_OR_MAPPING_UNRESOLVED|LINE_COST_COMPONENT_UNAVAILABLE):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')),false))
$$;
REVOKE ALL ON FUNCTION ops.ad_action_blockers(text,text,text[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_action_blockers(text,text,text[]) TO marketops_app;

CREATE FUNCTION ops.ad_actor_covers_affected_set(p_actor uuid,p_org uuid,p_set uuid,p_action text)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,core,iam,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM core.ad_affected_set affected
 WHERE affected.id=p_set AND affected.organization_id=p_org AND affected.resolution_state='COMPLETE'
 AND cardinality(affected.product_variant_ids)>0 AND NOT EXISTS(
  SELECT 1 FROM unnest(affected.product_variant_ids) member WHERE NOT EXISTS(
   SELECT 1 FROM iam.user_scope_grant grant_row WHERE grant_row.user_id=p_actor
   AND grant_row.organization_id=p_org AND grant_row.action_code=p_action AND grant_row.status='ACTIVE'
   AND grant_row.effective_from<=statement_timestamp()
   AND (grant_row.effective_to IS NULL OR grant_row.effective_to>statement_timestamp())
   AND (grant_row.organization_ref_id=p_org OR grant_row.product_variant_ref_id=member))))
$$;
REVOKE ALL ON FUNCTION ops.ad_actor_covers_affected_set(uuid,uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_actor_covers_affected_set(uuid,uuid,uuid,text) TO marketops_app;

CREATE FUNCTION ops.seal_ad_action_authorization(p_recommendation uuid,p_approval uuid,p_baseline uuid,p_proof text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,core,platform,iam,pg_temp AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; r ops.recommendation%ROWTYPE;
 a ops.approval_decision%ROWTYPE; b ops.ad_decision_policy_bundle%ROWTYPE;
 s ops.ad_candidate_selection%ROWTYPE; e ops.ad_candidate_endorsement%ROWTYPE;
 lease core.ad_approval_lease_policy%ROWTYPE; cfg core.ad_object_configuration_observation%ROWTYPE;
 candidate ops.ad_bid_candidate%ROWTYPE; kase mart.ad_case%ROWTYPE;
 deadline timestamptz; policy_end timestamptz; evidence_end timestamptz;
 snapshot jsonb; identity uuid; bounds jsonb; ordinary boolean; materiality text;
 credential_end timestamptz; baseline record; required_kinds text[];
BEGIN
 SELECT * INTO g FROM iam.ad_invocation_grant
  WHERE proof_hash=encode(sha256(convert_to(p_proof,'UTF8')),'hex') FOR UPDATE;
 IF NOT FOUND OR g.consumed_at IS NOT NULL OR g.expires_at<=clock_timestamp() OR g.purpose<>'FINAL_APPROVAL'
 OR g.recommendation_id<>p_recommendation OR g.approval_decision_id<>p_approval
 OR g.backend_pid<>pg_backend_pid() OR g.transaction_id<>txid_current()
 OR g.step_up_valid_until<=clock_timestamp()
 OR NOT EXISTS(SELECT 1 FROM iam.user_account actor JOIN iam.identity_provider provider ON provider.id=actor.identity_provider_id
 WHERE actor.id=g.actor_user_id AND actor.organization_id=g.organization_id AND actor.identity_provider_id=g.identity_provider_id
 AND actor.status='ACTIVE' AND actor.credentials_valid_from<=g.authenticated_at
 AND provider.status='ACTIVE' AND provider.verification_state='VERIFIED') THEN
  RAISE EXCEPTION 'one-use transaction-bound authenticated invocation required' USING ERRCODE='MO092'; END IF;
 SELECT * INTO r FROM ops.recommendation WHERE id=p_recommendation FOR UPDATE;
 SELECT * INTO a FROM ops.approval_decision WHERE id=p_approval;
 SELECT * INTO s FROM ops.ad_candidate_selection WHERE recommendation_id=p_recommendation;
 SELECT * INTO e FROM ops.ad_candidate_endorsement WHERE recommendation_id=p_recommendation;
 SELECT * INTO b FROM ops.ad_decision_policy_bundle WHERE id=s.bundle_id;
 SELECT * INTO candidate FROM ops.ad_bid_candidate WHERE id=s.candidate_id;
 SELECT * INTO kase FROM mart.ad_case WHERE id=candidate.case_id;
 SELECT * INTO lease FROM core.ad_approval_lease_policy WHERE id=b.approval_lease_policy_id;
 IF cardinality(ops.ad_action_blockers(candidate.candidate_basis,candidate.cause_code,kase.blocker_codes))>0 THEN
  RAISE EXCEPTION 'current action-specific evidence blockers remain unresolved' USING ERRCODE='MO092'; END IF;
 IF r.action_kind<>'AD_BID_CHANGE' OR a.decision<>'APPROVED'
 OR a.recommendation_id<>r.id OR a.decided_by_user_id IS DISTINCT FROM g.actor_user_id
 OR r.organization_id<>g.organization_id OR NOT a.step_up_satisfied
 OR s.id IS NULL OR e.id IS NULL OR e.selection_id<>s.id
 OR s.outcome_baseline_id IS DISTINCT FROM p_baseline
 OR s.maker_user_id=g.actor_user_id
 OR b.id IS NULL OR b.status<>'ACTIVE' OR b.bundle_version<>s.bundle_version
 OR lease.id IS NULL OR lease.status<>'ACTIVE'
 OR candidate.id IS DISTINCT FROM (r.proposed_parameters->>'candidateId')::uuid THEN
  RAISE EXCEPTION 'final approval identity or sealed selection is invalid' USING ERRCODE='MO092'; END IF;
 SELECT * INTO baseline FROM ops.ad_outcome_baseline frozen WHERE frozen.id=p_baseline
 AND frozen.organization_id=r.organization_id AND frozen.candidate_id=candidate.id
 AND frozen.ad_native_object_id=r.subject_id AND frozen.case_calculation_id=kase.calculation_id
 AND frozen.policy_version_digest=kase.policy_version_digest
 AND frozen.affected_set_id=kase.affected_set_id AND frozen.affected_set_digest=candidate.affected_set_digest
 AND frozen.outcome_policy_id=b.outcome_policy_id AND frozen.state='COMPLETE'
 AND frozen.prepared_at<=a.decided_at AND frozen.valid_until>a.decided_at;
 IF NOT FOUND OR ops.ad_outcome_baseline_is_canonical(p_baseline,a.decided_at) IS NOT TRUE THEN
  RAISE EXCEPTION 'exact canonical approved frozen Outcome baseline required' USING ERRCODE='MO099'; END IF;
 IF cardinality(ops.ad_action_isolation_failures(baseline.affected_set_id,baseline.id,clock_timestamp()))>0 THEN
  RAISE EXCEPTION 'known cross-domain intervention prevents isolated final approval' USING ERRCODE='MO099'; END IF;
 materiality:=ops.ad_materiality_assessment(b.id,candidate.id)->>'route';
 IF materiality IS NULL OR materiality NOT IN('ORDINARY_IMPACT','MATERIAL_IMPACT') THEN
  RAISE EXCEPTION 'all current materiality axes must resolve before final approval' USING ERRCODE='MO092'; END IF;
 ordinary:=materiality='ORDINARY_IMPACT';
 IF (NOT ordinary AND e.endorser_user_id=g.actor_user_id)
 OR NOT ops.ad_actor_has_role_scope(g.actor_user_id,r.organization_id,r.store_id,
    CASE WHEN ordinary THEN 'OPS_LEAD' ELSE 'OWNER' END,'AD_BID_CHANGE_APPROVE')
 OR NOT ops.ad_actor_covers_affected_set(g.actor_user_id,r.organization_id,kase.affected_set_id,'AD_BID_CHANGE_APPROVE')
 OR NOT ops.ad_actor_has_role_scope(e.endorser_user_id,r.organization_id,r.store_id,'OPS_LEAD','AD_BID_CHANGE_ENDORSE')
 OR NOT ops.ad_actor_covers_affected_set(e.endorser_user_id,r.organization_id,kase.affected_set_id,'AD_BID_CHANGE_ENDORSE') THEN
  RAISE EXCEPTION 'current exact Material/Ordinary scoped final approval required' USING ERRCODE='MO092'; END IF;
 SELECT min(ops.ad_credential_authority_expiry(cm.id,r.store_id)) INTO credential_end FROM platform.credential_metadata cm
 WHERE cm.organization_id=r.organization_id AND cm.marketplace_account_id=b.marketplace_account_id
 AND cm.purpose_code='ADS_WRITE' AND cm.status='ACTIVE'
 AND cm.effective_from<=a.decided_at AND (cm.scope_mode='ACCOUNT' OR EXISTS(
 SELECT 1 FROM platform.credential_store_scope cs WHERE cs.credential_id=cm.id AND cs.store_id=r.store_id));
 IF credential_end IS NULL OR credential_end<=clock_timestamp()
 OR NOT EXISTS(SELECT 1 FROM ops.ad_gate_authority gate WHERE gate.id=b.gate_authority_id
  AND gate.status='ACTIVE' AND gate.valid_from<=clock_timestamp() AND gate.valid_until>clock_timestamp()
  AND gate.bundle_id=b.id AND gate.store_id=r.store_id AND r.subject_id=ANY(gate.native_object_ids)
  AND gate.direction=candidate.direction AND gate.candidate_basis=candidate.candidate_basis) THEN
  RAISE EXCEPTION 'credential and exact Gate validity are required approval bounds' USING ERRCODE='MO092'; END IF;
 SELECT * INTO cfg FROM core.ad_object_configuration_observation c
 WHERE c.ad_native_object_id=r.subject_id AND c.organization_id=r.organization_id
 AND NOT EXISTS(SELECT 1 FROM core.ad_object_configuration_observation n WHERE n.supersedes_observation_id=c.id)
 ORDER BY c.observed_at DESC,c.id DESC LIMIT 1;
 snapshot:=ops.ad_bundle_authority_snapshot(b.id);
 IF s.authority_snapshot IS DISTINCT FROM jsonb_build_object('bid',ops.ad_bid_authority_snapshot(r.id),'bundle',snapshot)
 OR e.authority_snapshot IS DISTINCT FROM jsonb_build_object('bid',ops.ad_bid_authority_snapshot(r.id),'bundle',snapshot) THEN
  RAISE EXCEPTION 'human selection/endorsement authority changed' USING ERRCODE='MO092'; END IF;
 -- Every component contributes its finite end, including all required purposes.
 SELECT min((value->>'effective_to')::timestamptz) INTO policy_end
 FROM jsonb_each(snapshot) WHERE jsonb_typeof(value)='object';
 required_kinds:=ops.ad_required_action_evidence_kinds(candidate.candidate_basis,candidate.cause_code);
 IF required_kinds IS NULL THEN RAISE EXCEPTION 'unresolved cause-specific action evidence policy' USING ERRCODE='MO092'; END IF;
 SELECT min(least(evidence.expires_at,freshness.effective_to)) INTO evidence_end
 FROM mart.ad_case_purpose_evidence evidence
 JOIN core.ad_freshness_profile freshness ON freshness.id=evidence.freshness_profile_id
 WHERE evidence.case_id=candidate.case_id AND evidence.calculation_id=kase.calculation_id
 AND evidence.evidence_kind=ANY(required_kinds)
 AND evidence.decision_purpose=CASE WHEN candidate.direction='OPTIMIZATION_INCREASE'
     THEN 'OPTIMIZATION_BID_WRITE' ELSE 'PROTECTION_BID_WRITE' END
 HAVING count(DISTINCT evidence.evidence_kind)=cardinality(required_kinds) AND bool_and(evidence.eligible)
    AND count(evidence.expires_at)=count(*)
    AND bool_and(freshness.status='ACTIVE' AND freshness.effective_from<=a.decided_at
      AND (freshness.effective_to IS NULL OR freshness.effective_to>clock_timestamp()));
 snapshot:=snapshot || jsonb_build_object('decisionEvidence',
   (SELECT jsonb_agg(to_jsonb(evidence) ORDER BY evidence.evidence_kind)
    FROM mart.ad_case_purpose_evidence evidence WHERE evidence.case_id=candidate.case_id
    AND evidence.evidence_kind=ANY(required_kinds) AND evidence.calculation_id=kase.calculation_id
    AND evidence.decision_purpose=CASE WHEN candidate.direction='OPTIMIZATION_INCREASE'
     THEN 'OPTIMIZATION_BID_WRITE' ELSE 'PROTECTION_BID_WRITE' END));
 IF evidence_end IS NULL OR cfg.id IS NULL THEN
  RAISE EXCEPTION 'all evidence purpose expiry bounds required' USING ERRCODE='MO092'; END IF;
 deadline:=least(a.decided_at+make_interval(secs=>least(lease.lease_seconds,lease.material_lease_seconds)),
   r.valid_until,a.scope_expires_at,policy_end,evidence_end,baseline.valid_until,
   (snapshot#>>'{gate,valid_until}')::timestamptz,
   (SELECT min(ops.ad_credential_authority_expiry(cm.id,r.store_id)) FROM platform.credential_metadata cm
    WHERE cm.organization_id=r.organization_id AND cm.marketplace_account_id=b.marketplace_account_id
    AND cm.purpose_code='ADS_WRITE' AND cm.status='ACTIVE'
    AND cm.effective_from<=a.decided_at AND (cm.scope_mode='ACCOUNT' OR EXISTS(
      SELECT 1 FROM platform.credential_store_scope cs WHERE cs.credential_id=cm.id AND cs.store_id=r.store_id))),
   (SELECT min(sg.effective_to) FROM iam.user_scope_grant sg WHERE sg.user_id IN(g.actor_user_id,e.endorser_user_id,s.maker_user_id)
     AND sg.action_code IN('AD_BID_CHANGE_APPROVE','AD_BID_CHANGE_ENDORSE','ADVERTISING_TASK_ACT') AND sg.status='ACTIVE'),
   (SELECT min(role.effective_to) FROM iam.user_role_assignment role WHERE role.user_id IN(g.actor_user_id,e.endorser_user_id,s.maker_user_id)
     AND role.role_code IN('OWNER','OPS_LEAD','MARKETPLACE_OPERATOR') AND role.status='ACTIVE'));
 bounds:=jsonb_build_object('finalApprovedAt',a.decided_at,'leaseSeconds',least(lease.lease_seconds,lease.material_lease_seconds),
   'recommendation',r.valid_until,'ownerSelected',a.scope_expires_at,'policyPeriod',policy_end,
   'outcomeBaseline',baseline.valid_until,'requiredEvidenceKinds',to_jsonb(required_kinds),'requiredEvidence',evidence_end,'credentialValidity',credential_end,'gateWindow',snapshot#>'{gate,valid_until}');
 IF deadline<=clock_timestamp() THEN RAISE EXCEPTION 'approval is already expired' USING ERRCODE='MO092'; END IF;
 identity:=gen_random_uuid();
 INSERT INTO ops.ad_action_authorization VALUES(identity,r.organization_id,r.id,a.id,candidate.id,baseline.id,
   b.id,b.bundle_version,s.maker_user_id,e.endorser_user_id,g.actor_user_id,a.decided_at,deadline,bounds,snapshot,
 materiality);
 UPDATE iam.ad_invocation_grant SET consumed_at=clock_timestamp() WHERE proof_hash=g.proof_hash;
 RETURN identity;
END $$;
REVOKE ALL ON FUNCTION ops.seal_ad_action_authorization(uuid,uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.seal_ad_action_authorization(uuid,uuid,uuid,text) TO marketops_app;

ALTER FUNCTION ops.create_ad_bid_command(uuid,bigint,uuid,uuid,uuid,timestamptz,text)
 RENAME TO create_ad_bid_command_from_sealed_authority;
REVOKE ALL ON FUNCTION ops.create_ad_bid_command_from_sealed_authority(uuid,bigint,uuid,uuid,uuid,timestamptz,text)
 FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.create_ad_bid_command(p_recommendation uuid,p_version bigint,p_reservation uuid,p_correlation text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE a ops.ad_action_authorization%ROWTYPE; command_id uuid;
BEGIN
 SELECT * INTO a FROM ops.ad_action_authorization WHERE recommendation_id=p_recommendation;
 IF NOT FOUND OR a.expires_at<=clock_timestamp()
 OR EXISTS(SELECT 1 FROM ops.ad_authority_invalidation i WHERE i.authorization_id=a.id)
 OR a.authority_snapshot-'decisionEvidence' IS DISTINCT FROM ops.ad_bundle_authority_snapshot(a.bundle_id) THEN
  RAISE EXCEPTION 'current immutable approval authority required' USING ERRCODE='MO092'; END IF;
 IF ops.ad_outcome_baseline_is_canonical(a.outcome_baseline_id,clock_timestamp()) IS NOT TRUE THEN
  RAISE EXCEPTION 'canonical frozen Outcome authority required at command creation' USING ERRCODE='MO099'; END IF;
 IF cardinality(ops.ad_action_isolation_failures(
  (SELECT affected_set_id FROM ops.ad_outcome_baseline WHERE id=a.outcome_baseline_id),a.outcome_baseline_id,clock_timestamp()))>0 THEN
  RAISE EXCEPTION 'known cross-domain intervention prevents isolated command creation' USING ERRCODE='MO099'; END IF;
 IF ops.ad_materiality_assessment(a.bundle_id,a.candidate_id)->>'route' IS DISTINCT FROM a.materiality_route THEN
  RAISE EXCEPTION 'sealed materiality route no longer matches current independent axes' USING ERRCODE='MO092'; END IF;
 IF EXISTS(SELECT 1 FROM ops.ad_accepted_exception exception
 JOIN ops.ad_bid_candidate candidate ON candidate.id=a.candidate_id
 WHERE exception.case_id=candidate.case_id AND exception.state='ACTIVE' AND exception.ended_at IS NULL) THEN
  RAISE EXCEPTION 'ACCEPTED_EXCEPTION_ACTIVE' USING ERRCODE='MO092'; END IF;
 IF EXISTS(SELECT 1 FROM ops.ad_bid_command c JOIN ops.recommendation r ON r.id=p_recommendation
    WHERE c.ad_native_object_id=r.subject_id AND c.recommendation_id<>r.id) THEN
  RAISE EXCEPTION 'general same-object reentry is disabled pending accepted calibration' USING ERRCODE='MO092'; END IF;
 command_id:=ops.create_ad_bid_command_from_sealed_authority(p_recommendation,p_version,
   a.final_approver_user_id,p_reservation,a.bundle_id,a.expires_at,p_correlation);
 RETURN command_id;
END $$;
REVOKE ALL ON FUNCTION ops.create_ad_bid_command(uuid,bigint,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.create_ad_bid_command(uuid,bigint,uuid,text) TO marketops_app;

CREATE FUNCTION ops.ad_ordinary_promotion_covers(p_bundle uuid,p_object uuid,p_change numeric)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,ops,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM ops.ad_decision_policy_bundle b
 JOIN ops.ad_ordinary_promotion p ON p.id=b.ordinary_promotion_id AND p.bundle_id=b.id
 JOIN ops.ad_gate_authority g ON g.id=p.gate_authority_id AND g.bundle_id=b.id
 WHERE b.id=p_bundle AND b.status='ACTIVE' AND p.status='ACTIVE' AND g.status='ACTIVE'
 AND g.gate_kind='GATE_E' AND p_object=ANY(g.native_object_ids)
 AND p_change<=p.material_envelope_amount AND p_change<=g.max_bid_change_amount
 AND statement_timestamp()>=greatest(p.valid_from,g.valid_from)
 AND statement_timestamp()<least(p.valid_until,g.valid_until))
$$;
REVOKE ALL ON FUNCTION ops.ad_ordinary_promotion_covers(uuid,uuid,numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_ordinary_promotion_covers(uuid,uuid,numeric) TO marketops_app;

-- Scope selection never chooses another store's policy. Matching policies of
-- different specificity all constrain admission; narrower scope cannot erase a
-- broader organizational capacity ceiling.
ALTER TABLE core.ad_exposure_envelope
 ADD COLUMN retained_window_days integer CHECK(retained_window_days IN (7,14,30)),
 ADD COLUMN measurement_window_hours integer CHECK(measurement_window_hours BETWEEN 1 AND 8760);
CREATE FUNCTION ops.ad_exposure_snapshot(p_org uuid,p_store uuid,p_direction text)
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
     AND r.intervention_kind='CONFIRMED_MANUAL_PACKET' AND packet.state IN('MANUAL_PACKET_ISSUED',
      'MANUAL_EXECUTION_IN_PROGRESS','ACTION_REPORTED_CONFIGURATION_UNVERIFIED','MANUAL_EXECUTION_UNCERTAIN')));
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
REVOKE ALL ON FUNCTION ops.ad_exposure_snapshot(uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_exposure_snapshot(uuid,uuid,text) TO marketops_app;
CREATE FUNCTION ops.ad_exposure_failures(p_org uuid,p_store uuid,p_direction text) RETURNS text[]
LANGUAGE sql STABLE SET search_path=pg_catalog,ops,pg_temp AS $$
 SELECT ARRAY(SELECT jsonb_array_elements_text(ops.ad_exposure_snapshot(p_org,p_store,p_direction)->'reasons'))
$$;
REVOKE ALL ON FUNCTION ops.ad_exposure_failures(uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_exposure_failures(uuid,uuid,text) TO marketops_app;

ALTER FUNCTION ops.take_ad_action_reservation(uuid,uuid,uuid,uuid,uuid,text,uuid[],text,uuid,text,text,text)
 RENAME TO take_ad_action_reservation_serialized;
REVOKE ALL ON FUNCTION ops.take_ad_action_reservation_serialized(uuid,uuid,uuid,uuid,uuid,text,uuid[],text,uuid,text,text,text)
 FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.take_ad_action_reservation(p_id uuid,p_org uuid,p_object uuid,p_store uuid,p_set uuid,
 p_digest text,p_variants uuid[],p_kind text,p_reference uuid,p_direction text,p_lane text,p_correlation text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE result uuid; failures text[]; baseline_id uuid;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(p_org::text));
 IF NOT EXISTS(SELECT 1 FROM core.ad_affected_set a JOIN core.ad_native_object o ON o.id=a.ad_native_object_id
 WHERE a.id=p_set AND a.organization_id=p_org AND a.ad_native_object_id=p_object
 AND o.store_id=p_store AND a.resolution_state='COMPLETE' AND a.affected_set_digest=p_digest
 AND ARRAY(SELECT DISTINCT v FROM unnest(a.product_variant_ids) v ORDER BY v)
     =ARRAY(SELECT DISTINCT v FROM unnest(p_variants) v ORDER BY v)
 AND NOT EXISTS(SELECT 1 FROM core.ad_affected_set n WHERE n.ad_native_object_id=a.ad_native_object_id
     AND n.resolved_at>a.resolved_at)) THEN
  RAISE EXCEPTION 'reservation must identify the exact current canonical affected set' USING ERRCODE='MO097'; END IF;
 IF p_kind='CONTROLLED_AD_BID_CHANGE' AND NOT EXISTS(SELECT 1 FROM ops.ad_bid_candidate candidate
 WHERE candidate.id=p_reference AND candidate.organization_id=p_org AND candidate.ad_native_object_id=p_object
 AND candidate.affected_set_digest=p_digest AND candidate.direction=p_direction
 AND EXISTS(SELECT 1 FROM ops.ad_action_authorization authorized WHERE authorized.candidate_id=candidate.id
 AND authorized.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM ops.ad_authority_invalidation invalidated
 WHERE invalidated.authorization_id=authorized.id)))
 OR p_kind='CONFIRMED_MANUAL_PACKET' AND NOT EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet packet
 WHERE packet.id=p_reference AND packet.organization_id=p_org AND packet.ad_native_object_id=p_object
 AND packet.affected_set_id=p_set AND packet.affected_set_digest=p_digest AND packet.state='MANUAL_PACKET_ISSUED')
 OR p_kind='EXACT_PRIOR_BID_COMPENSATION' THEN
  RAISE EXCEPTION 'reservation requires exact intervention; compensation retains the original reservation' USING ERRCODE='MO097'; END IF;
 IF p_kind='CONTROLLED_AD_BID_CHANGE' THEN
  SELECT authorized.outcome_baseline_id INTO baseline_id FROM ops.ad_action_authorization authorized
   WHERE authorized.candidate_id=p_reference;
 ELSIF p_kind='CONFIRMED_MANUAL_PACKET' THEN
  SELECT packet.outcome_baseline_id INTO baseline_id FROM ops.ad_manual_execution_packet packet WHERE packet.id=p_reference;
 END IF;
 failures:=ops.ad_action_isolation_failures(p_set,baseline_id,clock_timestamp());
 IF cardinality(failures)>0 THEN
  RAISE EXCEPTION 'cross-domain isolation admission refused: %',array_to_string(failures,',') USING ERRCODE='MO097'; END IF;
 result:=ops.take_ad_action_reservation_serialized(p_id,p_org,p_object,p_store,p_set,p_digest,p_variants,
   p_kind,p_reference,p_direction,p_lane,p_correlation);
 failures:=ops.ad_exposure_failures(p_org,p_store,p_direction);
 IF cardinality(failures)>0 THEN RAISE EXCEPTION 'exposure admission refused: %',array_to_string(failures,',') USING ERRCODE='MO097'; END IF;
 RETURN result;
END $$;
REVOKE ALL ON FUNCTION ops.take_ad_action_reservation(uuid,uuid,uuid,uuid,uuid,text,uuid[],text,uuid,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.take_ad_action_reservation(uuid,uuid,uuid,uuid,uuid,text,uuid[],text,uuid,text,text,text) TO marketops_app;

-- Current authority is independently checked at creation, leasing and dispatch.
ALTER FUNCTION ops.evaluate_ad_bid_write_gate(uuid) RENAME TO evaluate_ad_bid_write_gate_base;
REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_write_gate_base(uuid) FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.evaluate_ad_bid_write_gate(p_command uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,platform,iam,pg_temp AS $$
DECLARE c ops.ad_bid_command%ROWTYPE; a ops.ad_action_authorization%ROWTYPE;
 g ops.ad_gate_authority%ROWTYPE; b ops.ad_decision_policy_bundle%ROWTYPE; reasons text[];
BEGIN
 SELECT * INTO c FROM ops.ad_bid_command WHERE id=p_command;
 IF NOT FOUND THEN RETURN ARRAY['COMMAND_NOT_FOUND']; END IF;
 reasons:=ops.evaluate_ad_bid_write_gate_base(p_command);
 IF EXISTS(SELECT 1 FROM ops.ad_accepted_exception exception JOIN ops.ad_bid_candidate candidate
 ON candidate.id=c.candidate_id WHERE exception.case_id=candidate.case_id
 AND exception.state='ACTIVE' AND exception.ended_at IS NULL) THEN
  reasons:=array_append(reasons,'ACCEPTED_EXCEPTION_ACTIVE'); END IF;
 SELECT * INTO a FROM ops.ad_action_authorization WHERE recommendation_id=c.recommendation_id;
 SELECT * INTO b FROM ops.ad_decision_policy_bundle WHERE id=c.bundle_id;
 SELECT * INTO g FROM ops.ad_gate_authority WHERE id=b.gate_authority_id;
 IF EXISTS(SELECT 1 FROM ops.ad_bid_candidate candidate JOIN mart.ad_case kase ON kase.id=candidate.case_id
 WHERE candidate.id=c.candidate_id
 AND cardinality(ops.ad_action_blockers(candidate.candidate_basis,candidate.cause_code,kase.blocker_codes))>0) THEN
  reasons:=array_append(reasons,'ACTION_EVIDENCE_BLOCKERS_UNRESOLVED'); END IF;
 IF a.id IS NULL OR a.expires_at<>c.approval_expires_at OR a.expires_at<=statement_timestamp()
 OR a.bundle_id<>c.bundle_id OR a.candidate_id<>c.candidate_id
 OR a.approval_decision_id<>c.approval_decision_id THEN
  reasons:=array_append(reasons,'SEALED_AUTHORIZATION_MISSING_OR_EXPIRED'); END IF;
 IF a.id IS NOT NULL AND (
  NOT ops.ad_actor_has_role_scope(a.final_approver_user_id,c.organization_id,c.store_id,
    CASE WHEN a.materiality_route='ORDINARY_IMPACT' THEN 'OPS_LEAD' ELSE 'OWNER' END,'AD_BID_CHANGE_APPROVE')
  OR NOT ops.ad_actor_has_role_scope(a.endorser_user_id,c.organization_id,c.store_id,'OPS_LEAD','AD_BID_CHANGE_ENDORSE')
  OR NOT ops.ad_actor_has_role_scope(a.maker_user_id,c.organization_id,c.store_id,'MARKETPLACE_OPERATOR','ADVERTISING_TASK_ACT')
  OR NOT ops.ad_actor_covers_affected_set(a.final_approver_user_id,c.organization_id,
    (SELECT affected_set_id FROM ops.ad_outcome_baseline WHERE id=a.outcome_baseline_id),'AD_BID_CHANGE_APPROVE')
  OR NOT ops.ad_actor_covers_affected_set(a.endorser_user_id,c.organization_id,
    (SELECT affected_set_id FROM ops.ad_outcome_baseline WHERE id=a.outcome_baseline_id),'AD_BID_CHANGE_ENDORSE')
  OR NOT ops.ad_actor_covers_affected_set(a.maker_user_id,c.organization_id,
    (SELECT affected_set_id FROM ops.ad_outcome_baseline WHERE id=a.outcome_baseline_id),'ADVERTISING_TASK_ACT')) THEN
  reasons:=array_append(reasons,'CURRENT_HUMAN_AUTHORITY_REVOKED'); END IF;
 IF c.outcome_baseline_id IS DISTINCT FROM a.outcome_baseline_id
 OR ops.ad_outcome_baseline_is_canonical(a.outcome_baseline_id,statement_timestamp()) IS NOT TRUE THEN
  reasons:=array_append(reasons,'CANONICAL_OUTCOME_BASELINE_AUTHORITY_INVALID'); END IF;
 reasons:=reasons||ops.ad_action_isolation_failures(
  (SELECT affected_set_id FROM ops.ad_outcome_baseline WHERE id=a.outcome_baseline_id),a.outcome_baseline_id,statement_timestamp());
 IF ops.ad_materiality_assessment(c.bundle_id,c.candidate_id)->>'route' IS DISTINCT FROM a.materiality_route THEN
  reasons:=array_append(reasons,'SEALED_MATERIALITY_ROUTE_CHANGED_OR_UNRESOLVED'); END IF;
 IF EXISTS(SELECT 1 FROM ops.ad_authority_invalidation i WHERE i.authorization_id=a.id) THEN
  reasons:=array_append(reasons,'AUTHORITY_PERMANENTLY_INVALIDATED'); END IF;
 IF a.authority_snapshot-'decisionEvidence' IS DISTINCT FROM ops.ad_bundle_authority_snapshot(c.bundle_id) THEN
  reasons:=array_append(reasons,'COMPLETE_AUTHORITY_SNAPSHOT_CHANGED'); END IF;
 IF a.authority_snapshot->'decisionEvidence' IS DISTINCT FROM (
  SELECT jsonb_agg(to_jsonb(evidence) ORDER BY evidence.evidence_kind)
  FROM ops.ad_bid_candidate candidate JOIN mart.ad_case kase ON kase.id=candidate.case_id
  JOIN mart.ad_case_purpose_evidence evidence ON evidence.case_id=kase.id AND evidence.calculation_id=kase.calculation_id
  WHERE candidate.id=c.candidate_id AND evidence.evidence_kind=ANY(
    ops.ad_required_action_evidence_kinds(candidate.candidate_basis,candidate.cause_code))
  AND evidence.decision_purpose=CASE WHEN c.direction='OPTIMIZATION_INCREASE'
    THEN 'OPTIMIZATION_BID_WRITE' ELSE 'PROTECTION_BID_WRITE' END) THEN
  reasons:=array_append(reasons,'ACTION_EVIDENCE_AUTHORITY_CHANGED'); END IF;
 IF NOT ops.ad_bid_execution_pass_matches_bundle(p_command) THEN
  reasons:=array_append(reasons,'GUARDRAIL_BUNDLE_MISMATCH'); END IF;
 IF g.id IS NULL OR g.status<>'ACTIVE' OR g.valid_from>statement_timestamp() OR g.valid_until<=statement_timestamp()
 OR g.organization_id<>c.organization_id OR g.store_id<>c.store_id OR g.platform_code<>c.platform_code
 OR g.capability_code<>'ad-bid-change' OR NOT c.ad_native_object_id=ANY(g.native_object_ids)
 OR g.bundle_id<>c.bundle_id OR g.direction<>c.direction OR g.candidate_basis<>c.candidate_basis
 OR g.currency_code<>c.currency_code
 OR NOT g.exact_object_values ? c.ad_native_object_id::text
 OR ops.ad_nonnegative_numeric(g.exact_object_values#>>ARRAY[c.ad_native_object_id::text,'currentBid']) IS DISTINCT FROM c.prior_bid_amount
 OR ops.ad_nonnegative_numeric(g.exact_object_values#>>ARRAY[c.ad_native_object_id::text,'targetBid']) IS DISTINCT FROM c.target_bid_amount
 OR (g.exact_object_values#>>ARRAY[c.ad_native_object_id::text,'currencyCode']) IS DISTINCT FROM c.currency_code
 OR (g.exact_object_values#>>ARRAY[c.ad_native_object_id::text,'bidUnitCode']) IS DISTINCT FROM c.bid_unit_code
 OR abs(c.target_bid_amount-c.prior_bid_amount) / (CASE c.bid_unit_code WHEN 'CURRENCY_MINOR' THEN 100 ELSE 1 END) >g.max_bid_change_amount
 OR (SELECT count(*) FROM ops.ad_bid_command other WHERE other.bundle_id=c.bundle_id)>g.max_commands THEN
  reasons:=array_append(reasons,'EXACT_GATE_AUTHORITY_ABSENT_OR_EXCEEDED'); END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.credential_metadata cm
  WHERE cm.marketplace_account_id=g.marketplace_account_id AND cm.organization_id=c.organization_id
  AND cm.purpose_code='ADS_WRITE' AND cm.status='ACTIVE'
  AND ops.ad_credential_authority_expiry(cm.id,c.store_id)>statement_timestamp()
  AND (cm.scope_mode='ACCOUNT' OR EXISTS(SELECT 1 FROM platform.credential_store_scope cs
    WHERE cs.credential_id=cm.id AND cs.store_id=c.store_id))) THEN
  reasons:=array_append(reasons,'ADS_WRITE_CREDENTIAL_AUTHORITY_INVALID'); END IF;
 RETURN ARRAY(SELECT DISTINCT reason FROM unnest(reasons) reason ORDER BY reason);
END $$;
REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_write_gate(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.evaluate_ad_bid_write_gate(uuid) TO marketops_app;

CREATE FUNCTION ops.ad_bundle_consumes_authority_version(p_bundle uuid,p_authority uuid)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,ops,core,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM ops.ad_decision_policy_bundle b WHERE b.id=p_bundle AND (
  p_authority=ANY(ARRAY[b.id,b.semantic_profile_id,b.conversion_definition_id,b.allowable_cpa_definition_id,
   b.qualification_policy_id,b.target_policy_id,b.outcome_policy_id,b.priority_policy_id,b.human_slo_profile_id,
   b.approval_lease_policy_id,b.exposure_envelope_id,b.materiality_policy_id,b.ordinary_promotion_id])
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(ops.ad_bundle_authority_snapshot(b.id)->'freshness') profile
    WHERE profile->>'id'=p_authority::text)
  OR EXISTS(SELECT 1 FROM ops.ad_action_authorization sealed
    CROSS JOIN LATERAL jsonb_array_elements(coalesce(sealed.authority_snapshot->'freshness','[]'::jsonb)) profile
    WHERE sealed.bundle_id=b.id AND profile->>'id'=p_authority::text)))
$$;
REVOKE ALL ON FUNCTION ops.ad_bundle_consumes_authority_version(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_bundle_consumes_authority_version(uuid,uuid) TO marketops_app;

-- Containment scopes are intersections over canonical variants, not equality
-- of two arbitrary set digests. Authority-version quarantine names the exact
-- immutable reference of a Bundle component.
CREATE OR REPLACE FUNCTION ops.ad_active_containment(p_organization_id uuid,p_object_id uuid,
 p_store_id uuid,p_platform_code text,p_capability_code text,p_affected_digest text)
RETURNS text[] LANGUAGE sql STABLE SET search_path=pg_catalog,ops,core,pg_temp AS $$
 SELECT coalesce(array_agg(DISTINCT CASE WHEN q.containment_kind='KILL_SWITCH_ACTIVE'
       THEN 'KILL_SWITCH_ACTIVE' ELSE q.containment_kind END),'{}'::text[])
 FROM ops.ad_containment q WHERE q.organization_id=p_organization_id
 AND (q.state<>'REENABLED' OR q.scope_kind='AUTHORITY_VERSION')
 AND (q.scope_kind='ENTITY' AND q.ad_native_object_id=p_object_id
 OR q.scope_kind='AFFECTED_SET' AND EXISTS(SELECT 1 FROM core.ad_affected_set held
   JOIN core.ad_affected_set subject ON subject.organization_id=held.organization_id
    AND subject.ad_native_object_id=p_object_id AND subject.affected_set_digest=p_affected_digest
   WHERE held.organization_id=q.organization_id AND held.affected_set_digest=q.affected_set_digest
    AND held.product_variant_ids && subject.product_variant_ids)
 OR q.scope_kind='PLATFORM_STORE_CAPABILITY' AND q.platform_code=p_platform_code
   AND q.store_id=p_store_id AND q.capability_code=p_capability_code
 OR q.scope_kind='PLATFORM_ACCOUNT_CAPABILITY' AND q.platform_code=p_platform_code AND q.capability_code=p_capability_code
   AND q.marketplace_account_id=(SELECT marketplace_account_id FROM core.store WHERE id=p_store_id)
 OR q.scope_kind='AUTHORITY_VERSION' AND EXISTS(SELECT 1 FROM ops.ad_decision_policy_bundle b
   WHERE b.organization_id=p_organization_id AND b.store_id=p_store_id
   AND (q.state<>'REENABLED' OR b.status='ACTIVE')
   AND (EXISTS(SELECT 1 FROM ops.ad_gate_authority gate WHERE gate.id=b.gate_authority_id
       AND p_object_id=ANY(gate.native_object_ids))
     OR EXISTS(SELECT 1 FROM ops.ad_action_authorization sealed JOIN ops.ad_bid_candidate candidate
       ON candidate.id=sealed.candidate_id WHERE sealed.bundle_id=b.id AND candidate.ad_native_object_id=p_object_id)
     OR EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet packet
       WHERE packet.bundle_id=b.id AND packet.ad_native_object_id=p_object_id))
   AND ops.ad_bundle_consumes_authority_version(b.id,
     CASE WHEN q.authority_version_reference ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
       THEN q.authority_version_reference::uuid ELSE NULL END))
 OR q.scope_kind='AUTHORITY_VERSION' AND EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet packet
   WHERE packet.organization_id=p_organization_id AND packet.store_id=p_store_id
   AND packet.ad_native_object_id=p_object_id
   AND (q.state<>'REENABLED' OR packet.state IN ('MANUAL_PACKET_DRAFT','MANUAL_PACKET_ENDORSED',
    'MANUAL_PACKET_ISSUED','MANUAL_EXECUTION_IN_PROGRESS','ACTION_REPORTED_CONFIGURATION_UNVERIFIED','MANUAL_EXECUTION_UNCERTAIN'))
   AND q.authority_version_reference=ANY(ARRAY[
     packet.semantic_profile_id::text,to_jsonb(packet)#>>'{authority_snapshot,policy,id}',
     to_jsonb(packet)#>>'{authority_snapshot,outcomePolicy,id}'])))
$$;

CREATE FUNCTION ops.invalidate_ad_authority_on_containment() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,pg_temp AS $$
BEGIN
 IF NEW.state<>'ACTIVE' OR TG_OP='UPDATE' AND OLD.state='ACTIVE' THEN RETURN NEW; END IF;
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(NEW.organization_id::text));
 INSERT INTO ops.ad_authority_invalidation(organization_id,authorization_id,cause_reference,cause_code)
 SELECT a.organization_id,a.id,NEW.id,'CONTAINMENT_ACTIVATED' FROM ops.ad_action_authorization a
 JOIN ops.recommendation r ON r.id=a.recommendation_id JOIN core.ad_native_object obj ON obj.id=r.subject_id
 JOIN ops.ad_bid_candidate candidate ON candidate.id=a.candidate_id
 WHERE a.organization_id=NEW.organization_id
 AND (NEW.scope_kind<>'AUTHORITY_VERSION' OR ops.ad_bundle_consumes_authority_version(a.bundle_id,
  CASE WHEN NEW.authority_version_reference ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN NEW.authority_version_reference::uuid ELSE NULL END))
 AND cardinality(ops.ad_active_containment(a.organization_id,
  obj.id,r.store_id,obj.platform_code,'ad-bid-change',candidate.affected_set_digest))>0 ON CONFLICT DO NOTHING;
 UPDATE ops.ad_manual_execution_packet packet SET state='MANUAL_PACKET_REVOKED',revoked_at=clock_timestamp(),
  revoked_reason='CONTAINMENT_ACTIVATED',updated_at=clock_timestamp(),version=version+1
 WHERE packet.organization_id=NEW.organization_id AND packet.state IN ('MANUAL_PACKET_DRAFT','MANUAL_PACKET_ENDORSED','MANUAL_PACKET_ISSUED')
 AND (NEW.scope_kind<>'AUTHORITY_VERSION' OR ops.ad_bundle_consumes_authority_version(packet.bundle_id,
  CASE WHEN NEW.authority_version_reference ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN NEW.authority_version_reference::uuid ELSE NULL END) OR NEW.authority_version_reference=ANY(ARRAY[packet.semantic_profile_id::text,
   to_jsonb(packet)#>>'{authority_snapshot,policy,id}',to_jsonb(packet)#>>'{authority_snapshot,outcomePolicy,id}']))
 AND cardinality(ops.ad_active_containment(packet.organization_id,packet.ad_native_object_id,packet.store_id,
 packet.platform_code,'ad-bid-change',packet.affected_set_digest))>0;
 -- Once execution has begun, a stop cannot assert what actually landed. Keep
 -- every observation and the hold, invalidate the current proof, and permit
 -- only the existing factual report / independent verification path.
 UPDATE ops.ad_manual_execution_packet packet SET state='MANUAL_EXECUTION_UNCERTAIN',
  current_proof_id=NULL,updated_at=clock_timestamp(),version=version+1
 WHERE packet.organization_id=NEW.organization_id AND packet.execution_started_at IS NOT NULL
 AND packet.state IN ('MANUAL_EXECUTION_IN_PROGRESS','ACTION_REPORTED_CONFIGURATION_UNVERIFIED',
  'MANUAL_CONFIGURATION_VERIFIED','MANUAL_EXECUTION_UNCERTAIN')
 AND (NEW.scope_kind<>'AUTHORITY_VERSION' OR ops.ad_bundle_consumes_authority_version(packet.bundle_id,
  CASE WHEN NEW.authority_version_reference ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN NEW.authority_version_reference::uuid ELSE NULL END) OR NEW.authority_version_reference=ANY(ARRAY[packet.semantic_profile_id::text,
   to_jsonb(packet)#>>'{authority_snapshot,policy,id}',to_jsonb(packet)#>>'{authority_snapshot,outcomePolicy,id}']))
 AND cardinality(ops.ad_active_containment(packet.organization_id,packet.ad_native_object_id,packet.store_id,
  packet.platform_code,'ad-bid-change',packet.affected_set_digest))>0;
 UPDATE ops.ad_action_reservation held SET configuration_resolved=false,
  unknown_or_mismatch_open=true,version=held.version+1
 FROM ops.ad_manual_execution_packet packet WHERE packet.reservation_id=held.id
 AND held.state='ACTIVE' AND packet.organization_id=NEW.organization_id
 AND packet.execution_started_at IS NOT NULL AND packet.state='MANUAL_EXECUTION_UNCERTAIN'
 AND (NEW.scope_kind<>'AUTHORITY_VERSION' OR ops.ad_bundle_consumes_authority_version(packet.bundle_id,
  CASE WHEN NEW.authority_version_reference ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN NEW.authority_version_reference::uuid ELSE NULL END) OR NEW.authority_version_reference=ANY(ARRAY[packet.semantic_profile_id::text,
   to_jsonb(packet)#>>'{authority_snapshot,policy,id}',to_jsonb(packet)#>>'{authority_snapshot,outcomePolicy,id}']))
 AND cardinality(ops.ad_active_containment(packet.organization_id,packet.ad_native_object_id,packet.store_id,
  packet.platform_code,'ad-bid-change',packet.affected_set_digest))>0;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.invalidate_ad_authority_on_containment() FROM PUBLIC;
CREATE TRIGGER ad_containment_invalidates_assets AFTER INSERT OR UPDATE ON ops.ad_containment
 FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_authority_on_containment();

CREATE FUNCTION ops.invalidate_ad_authority_on_bundle_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
BEGIN
 IF OLD.status='ACTIVE' AND NEW.status<>'ACTIVE' THEN
  INSERT INTO ops.ad_authority_invalidation(organization_id,authorization_id,cause_reference,cause_code)
  SELECT a.organization_id,a.id,OLD.id,'BUNDLE_REPLACED_OR_REVOKED' FROM ops.ad_action_authorization a
   WHERE a.bundle_id=OLD.id ON CONFLICT DO NOTHING;
 END IF; RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.invalidate_ad_authority_on_bundle_change() FROM PUBLIC;
CREATE TRIGGER ad_bundle_invalidates_assets AFTER UPDATE ON ops.ad_decision_policy_bundle
 FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_authority_on_bundle_change();

CREATE FUNCTION ops.ad_bundle_content_is_immutable() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$ BEGIN
 IF OLD.status IN ('SUPERSEDED','REVOKED') OR
 (to_jsonb(OLD)-ARRAY['status','effective_to','validation_state','validation_failure_codes',
  'activated_by_user_id','endorsed_by_user_id','approved_by_user_id','gate_authority_id','updated_at','version'])
 IS DISTINCT FROM
 (to_jsonb(NEW)-ARRAY['status','effective_to','validation_state','validation_failure_codes',
  'activated_by_user_id','endorsed_by_user_id','approved_by_user_id','gate_authority_id','updated_at','version']) THEN
  RAISE EXCEPTION 'new Bundle content requires a new immutable version' USING ERRCODE='MO096'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.ad_bundle_content_is_immutable() FROM PUBLIC;
CREATE TRIGGER ad_bundle_content_immutable BEFORE UPDATE ON ops.ad_decision_policy_bundle
 FOR EACH ROW EXECUTE FUNCTION ops.ad_bundle_content_is_immutable();
REVOKE INSERT,UPDATE,DELETE ON ops.ad_decision_policy_bundle FROM marketops_app;

INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note) VALUES
 ('iam','ad_invocation_grant','NO_ROUTE',NULL,'private one-use authenticated invocation proof; never exposed'),
 ('ops','ad_gate_authority','NO_ROUTE',NULL,'inactive exact Owner Gate EV/E authority model'),
 ('ops','ad_ordinary_promotion','NO_ROUTE',NULL,'inactive scoped evidence promotion; missing evidence falls back to Material'),
 ('ops','ad_action_authorization','NO_ROUTE',NULL,'immutable exact final approval and minimum expiry'),
 ('ops','ad_authority_invalidation','NO_ROUTE',NULL,'permanent append-only invalidation prevents resurrection');

-- Polling pending native work is a read phase. It can never route through APPLY.
CREATE FUNCTION ops.lease_ad_bid_status(p_command uuid,p_owner text,p_seconds integer)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE fence bigint; BEGIN
 IF p_owner IS NULL OR length(p_owner) NOT BETWEEN 1 AND 100 OR p_seconds NOT BETWEEN 1 AND 900 THEN
  RAISE EXCEPTION 'invalid status lease' USING ERRCODE='MO095'; END IF;
 UPDATE ops.ad_bid_command SET fence_token=fence_token+1,lease_owner=p_owner,
 lease_expires_at=clock_timestamp()+make_interval(secs=>p_seconds),updated_at=clock_timestamp()
 WHERE id=p_command AND state='PLATFORM_PENDING' AND lease_owner IS NULL
 AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp()) RETURNING fence_token INTO fence;
 IF fence IS NULL THEN RAISE EXCEPTION 'status phase is not claimable' USING ERRCODE='MO090'; END IF;
 RETURN fence;
END $$;
CREATE FUNCTION ops.defer_ad_bid_observation(p_command uuid,p_fence bigint,p_owner text,p_seconds integer)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$ BEGIN
 UPDATE ops.ad_bid_command SET lease_owner=NULL,lease_expires_at=NULL,
 next_attempt_at=clock_timestamp()+make_interval(secs=>greatest(1,least(p_seconds,900))),updated_at=clock_timestamp()
 WHERE id=p_command AND fence_token=p_fence AND lease_owner=p_owner
 AND lease_expires_at>clock_timestamp() AND state IN ('PLATFORM_PENDING','COMPENSATION_PENDING')
 AND NOT EXISTS(SELECT 1 FROM ops.ad_bid_command_attempt pending_attempt
  WHERE pending_attempt.command_id=p_command AND pending_attempt.fence_token=p_fence
   AND pending_attempt.outcome_class='IN_FLIGHT');
 IF NOT FOUND THEN RAISE EXCEPTION 'current observation lease required' USING ERRCODE='MO090'; END IF;
END $$;
REVOKE ALL ON FUNCTION ops.lease_ad_bid_status(uuid,text,integer),
 ops.defer_ad_bid_observation(uuid,bigint,text,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lease_ad_bid_status(uuid,text,integer),
 ops.defer_ad_bid_observation(uuid,bigint,text,integer) TO marketops_app;

CREATE FUNCTION ops.ad_bid_retry_is_proven(p_command uuid) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog,ops,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM ops.ad_bid_command c
 JOIN ops.ad_bid_command_readback rb ON rb.command_id=c.id
 JOIN ops.ad_bid_command_attempt read_attempt ON read_attempt.id=rb.attempt_id
 WHERE c.id=p_command AND c.retry_budget_remaining>0 AND rb.match_state='MATCHES_PRIOR'
 AND rb.bid_unit_code=c.bid_unit_code AND rb.currency_code=c.currency_code
 AND read_attempt.fence_token=c.fence_token AND rb.observed_at>statement_timestamp()-interval '30 seconds'
 AND rb.id=(SELECT latest.id FROM ops.ad_bid_command_readback latest WHERE latest.command_id=c.id
  ORDER BY latest.observed_at DESC,latest.id DESC LIMIT 1)
 AND EXISTS(SELECT 1 FROM ops.ad_bid_command_attempt proof WHERE proof.command_id=c.id
  AND proof.completed_at<=rb.observed_at AND proof.raw_observation_id IS NOT NULL
  AND (proof.error_code='provider_explicit_not_applied'
   OR proof.purpose='STATUS_ENQUIRY' AND proof.outcome_class IN ('REJECTED','ACCEPTED')
    AND proof.operation_snapshot#>>'{adSemanticProfile,idempotency_semantics}'='VERIFIED_NATIVE_KEY'))
 AND NOT EXISTS(SELECT 1 FROM ops.ad_bid_command_attempt pending WHERE pending.command_id=c.id
  AND pending.outcome_class='IN_FLIGHT') AND cardinality(ops.evaluate_ad_bid_write_gate(c.id))=0)
$$;
REVOKE ALL ON FUNCTION ops.ad_bid_retry_is_proven(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_bid_retry_is_proven(uuid) TO marketops_app;

-- Other human control operations use separate purposes; a final-approval proof
-- cannot be replayed as a Bundle publication or compensation endorsement.
ALTER TABLE iam.ad_invocation_grant ADD COLUMN purpose text NOT NULL DEFAULT 'FINAL_APPROVAL';
CREATE FUNCTION iam.issue_ad_control_invocation_grant(p_purpose text,p_proof_hash text,p_actor uuid,p_org uuid,
 p_provider uuid,p_subject text,p_session text,p_authenticated timestamptz,p_step_up_until timestamptz,
 p_target uuid,p_version uuid,p_backend integer,p_transaction bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,iam,pg_temp AS $$ BEGIN
 IF p_purpose NOT IN ('COMPENSATION_PREVIEW','COMPENSATION_ENDORSE','COMPENSATION_APPROVE',
 'BUNDLE_DRAFT','BUNDLE_ENDORSE','BUNDLE_APPROVE','CONTAINMENT_STOP','AUTHORITY_VERSION_STOP','CONTAINMENT_REENABLE',
 'CONTAINMENT_ATTEST','CONTAINMENT_ENDORSE','MANUAL_POLICY_PUBLISH','MANUAL_PACKET_SELECT',
 'MANUAL_PACKET_ENDORSE','MANUAL_PACKET_APPROVE','MANUAL_EXECUTION_REPORT','MANUAL_EXECUTION_START','MANUAL_INDEPENDENT_VERIFY') THEN
  RAISE EXCEPTION 'unknown control invocation purpose' USING ERRCODE='MO092'; END IF;
 PERFORM iam.issue_ad_invocation_grant(p_proof_hash,p_actor,p_org,p_provider,p_subject,p_session,
 p_authenticated,p_step_up_until,p_target,p_version,p_backend,p_transaction);
 UPDATE iam.ad_invocation_grant SET purpose=p_purpose WHERE proof_hash=p_proof_hash;
END $$;
REVOKE ALL ON FUNCTION iam.issue_ad_control_invocation_grant(text,text,uuid,uuid,uuid,text,text,
 timestamptz,timestamptz,uuid,uuid,integer,bigint) FROM PUBLIC,marketops_app;
DO $$ BEGIN IF EXISTS(SELECT FROM pg_roles WHERE rolname='marketops_identity_issuer') THEN
 GRANT EXECUTE ON FUNCTION iam.issue_ad_control_invocation_grant(text,text,uuid,uuid,uuid,text,text,
 timestamptz,timestamptz,uuid,uuid,integer,bigint) TO marketops_identity_issuer;
END IF; END $$;
CREATE FUNCTION ops.consume_ad_control_invocation(p_proof text,p_purpose text,p_target uuid,p_version uuid)
RETURNS iam.ad_invocation_grant LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,iam,pg_temp AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; BEGIN
 SELECT * INTO g FROM iam.ad_invocation_grant WHERE proof_hash=encode(sha256(convert_to(p_proof,'UTF8')),'hex') FOR UPDATE;
 IF NOT FOUND OR g.consumed_at IS NOT NULL OR g.expires_at<=clock_timestamp() OR g.purpose<>p_purpose
 OR g.recommendation_id<>p_target OR g.approval_decision_id<>p_version
 OR g.backend_pid<>pg_backend_pid() OR g.transaction_id<>txid_current()
 OR g.step_up_valid_until<=clock_timestamp()
 OR NOT EXISTS(SELECT 1 FROM iam.user_account actor JOIN iam.identity_provider provider ON provider.id=actor.identity_provider_id
 WHERE actor.id=g.actor_user_id AND actor.organization_id=g.organization_id AND actor.identity_provider_id=g.identity_provider_id
 AND actor.status='ACTIVE' AND actor.credentials_valid_from<=g.authenticated_at
 AND provider.status='ACTIVE' AND provider.verification_state='VERIFIED') THEN
  RAISE EXCEPTION 'exact one-use authenticated control invocation required' USING ERRCODE='MO092'; END IF;
 UPDATE iam.ad_invocation_grant SET consumed_at=clock_timestamp() WHERE proof_hash=g.proof_hash;
 RETURN g;
END $$;
REVOKE ALL ON FUNCTION ops.consume_ad_control_invocation(text,text,uuid,uuid) FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.ad_actor_has_role_scope(p_actor uuid,p_org uuid,p_store uuid,p_role text,p_action text)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,iam,core,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM iam.user_role_assignment r JOIN iam.business_role_action_scope m ON m.role_code=r.role_code
 JOIN iam.user_scope_grant s ON s.user_id=r.user_id AND s.action_code=m.action_code
 JOIN core.store st ON st.id=p_store JOIN core.marketplace_account account ON account.id=st.marketplace_account_id
 WHERE r.user_id=p_actor AND r.organization_id=p_org AND s.organization_id=p_org AND st.organization_id=p_org
 AND EXISTS(SELECT 1 FROM iam.user_account actor JOIN iam.identity_provider provider ON provider.id=actor.identity_provider_id
   WHERE actor.id=p_actor AND actor.organization_id=p_org AND actor.status='ACTIVE' AND provider.status='ACTIVE')
 AND r.role_code=p_role AND r.status='ACTIVE' AND m.action_code=p_action
 AND r.effective_from<=statement_timestamp() AND (r.effective_to IS NULL OR r.effective_to>statement_timestamp())
 AND s.status='ACTIVE' AND s.effective_from<=statement_timestamp() AND (s.effective_to IS NULL OR s.effective_to>statement_timestamp())
 AND (s.organization_ref_id=p_org OR s.store_ref_id=p_store OR s.marketplace_account_ref_id=account.id
      OR s.legal_entity_ref_id=account.legal_entity_id))
$$;
REVOKE ALL ON FUNCTION ops.ad_actor_has_role_scope(uuid,uuid,uuid,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_actor_has_role_scope(uuid,uuid,uuid,text,text) TO marketops_app;

CREATE TABLE ops.ad_compensation_authorization (
 id uuid PRIMARY KEY, command_id uuid NOT NULL REFERENCES ops.ad_bid_command(id),
 organization_id uuid NOT NULL, bundle_id uuid NOT NULL REFERENCES ops.ad_decision_policy_bundle(id),
 reservation_id uuid NOT NULL REFERENCES ops.ad_action_reservation(id),
 captured_prior_bid numeric(18,4) NOT NULL, current_owner_bid numeric(18,4) NOT NULL,
 currency_code text NOT NULL, bid_unit_code text NOT NULL, affected_set_digest text NOT NULL,
 maker_user_id uuid NOT NULL REFERENCES iam.user_account(id), previewed_at timestamptz NOT NULL,
 preview_expires_at timestamptz NOT NULL, preview_snapshot jsonb NOT NULL,
 endorser_user_id uuid REFERENCES iam.user_account(id), endorsed_at timestamptz,
 owner_user_id uuid REFERENCES iam.user_account(id), approved_at timestamptz, expires_at timestamptz,
 CHECK(maker_user_id IS DISTINCT FROM endorser_user_id AND maker_user_id IS DISTINCT FROM owner_user_id),
 CHECK(endorser_user_id IS NULL OR owner_user_id IS NULL OR endorser_user_id<>owner_user_id)
);
REVOKE ALL ON ops.ad_compensation_authorization FROM PUBLIC,marketops_app;
GRANT SELECT ON ops.ad_compensation_authorization TO marketops_app;
CREATE TABLE ops.ad_compensation_invalidation(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),compensation_id uuid NOT NULL REFERENCES ops.ad_compensation_authorization(id),
 cause_reference uuid NOT NULL,cause_code text NOT NULL,invalidated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(compensation_id,cause_reference));
REVOKE ALL ON ops.ad_compensation_invalidation FROM PUBLIC,marketops_app;
GRANT SELECT ON ops.ad_compensation_invalidation TO marketops_app;
CREATE TRIGGER ad_compensation_invalidation_immutable BEFORE UPDATE OR DELETE ON ops.ad_compensation_invalidation
FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES('ops','ad_compensation_invalidation','NO_ROUTE',NULL,'append-only compensation authority invalidation');
ALTER TABLE ops.ad_containment ADD COLUMN action_command_id uuid REFERENCES ops.ad_bid_command(id);
CREATE FUNCTION ops.preview_ad_compensation(p_preview uuid,p_command uuid,p_bundle uuid,p_proof text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; c ops.ad_bid_command%ROWTYPE; b ops.ad_decision_policy_bundle%ROWTYPE;
 lifetime integer;
BEGIN
 g:=ops.consume_ad_control_invocation(p_proof,'COMPENSATION_PREVIEW',p_command,p_preview);
 SELECT * INTO c FROM ops.ad_bid_command WHERE id=p_command FOR UPDATE;
 SELECT * INTO b FROM ops.ad_decision_policy_bundle WHERE id=p_bundle;
 IF c.id IS NULL OR g.organization_id<>c.organization_id OR NOT ops.ad_actor_has_role_scope(g.actor_user_id,
 c.organization_id,c.store_id,'MARKETPLACE_OPERATOR','ADVERTISING_TASK_ACT')
 OR b.id IS NULL OR b.direction<>'EXACT_PRIOR_BID_COMPENSATION' OR b.store_id<>c.store_id OR b.status<>'ACTIVE'
 OR NOT ops.ad_actor_covers_affected_set(g.actor_user_id,c.organization_id,
   (SELECT affected_set_id FROM ops.ad_action_reservation WHERE id=c.reservation_id),'ADVERTISING_TASK_ACT')
 OR EXISTS(SELECT 1 FROM ops.ad_compensation_authorization current_approval WHERE current_approval.command_id=c.id
   AND coalesce(current_approval.expires_at,current_approval.preview_expires_at)>clock_timestamp()
   AND NOT EXISTS(SELECT 1 FROM ops.ad_compensation_invalidation invalidated WHERE invalidated.compensation_id=current_approval.id))
 OR c.state IN ('COMPENSATION_PENDING','COMPENSATED','COMPENSATION_FAILED')
 OR NOT EXISTS(SELECT 1 FROM ops.ad_bid_command_readback rb WHERE rb.command_id=c.id AND rb.match_state='MATCHES_TARGET'
   AND rb.observed_bid=c.target_bid_amount AND rb.currency_code=c.currency_code AND rb.bid_unit_code=c.bid_unit_code
   AND NOT EXISTS(SELECT 1 FROM ops.ad_bid_command_readback newer WHERE newer.command_id=c.id
     AND (newer.observed_at,newer.id)>(rb.observed_at,rb.id)))
 OR NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation outcome WHERE outcome.command_id=c.id AND outcome.verdict='REGRESSED'
   AND outcome.evaluated_at<=clock_timestamp()
   AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation newer WHERE newer.supersedes_observation_id=outcome.id))
    AND NOT EXISTS(SELECT 1 FROM ops.ad_containment stop WHERE stop.action_command_id=c.id
     AND stop.containment_kind='EMERGENCY_ENTITY_HOLD' AND stop.cause_class='BUSINESS_HARM'
     AND stop.state IN('ACTIVE','REENABLEMENT_REVIEW') AND stop.activated_by_user_id IS NOT NULL) THEN
  RAISE EXCEPTION 'action-bound stop/regression and scoped maker are required for exact compensation' USING ERRCODE='MO094'; END IF;
 SELECT lease_seconds INTO lifetime FROM core.ad_approval_lease_policy WHERE id=b.approval_lease_policy_id AND status='ACTIVE';
 IF lifetime IS NULL THEN RAISE EXCEPTION 'compensation lease policy absent' USING ERRCODE='MO094'; END IF;
 INSERT INTO ops.ad_compensation_authorization(id,command_id,organization_id,bundle_id,reservation_id,captured_prior_bid,
 current_owner_bid,currency_code,bid_unit_code,affected_set_digest,maker_user_id,previewed_at,preview_expires_at,preview_snapshot)
 VALUES(p_preview,c.id,c.organization_id,b.id,c.reservation_id,c.prior_bid_amount,c.target_bid_amount,
 c.currency_code,c.bid_unit_code,c.affected_set_digest,g.actor_user_id,clock_timestamp(),
 least(clock_timestamp()+make_interval(secs=>lifetime),b.effective_to),ops.ad_bundle_authority_snapshot(b.id));
 RETURN p_preview;
END $$;
CREATE FUNCTION ops.endorse_ad_compensation(p_preview uuid,p_proof text) RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,iam,pg_temp AS $$
DECLARE a ops.ad_compensation_authorization%ROWTYPE; c ops.ad_bid_command%ROWTYPE; g iam.ad_invocation_grant%ROWTYPE;
BEGIN
 SELECT * INTO a FROM ops.ad_compensation_authorization WHERE id=p_preview FOR UPDATE;
 g:=ops.consume_ad_control_invocation(p_proof,'COMPENSATION_ENDORSE',a.command_id,p_preview);
 SELECT * INTO c FROM ops.ad_bid_command WHERE id=a.command_id;
 IF a.endorser_user_id IS NOT NULL OR a.preview_expires_at<=clock_timestamp() OR g.actor_user_id=a.maker_user_id
 OR NOT ops.ad_actor_has_role_scope(g.actor_user_id,a.organization_id,c.store_id,'OPS_LEAD','AD_BID_CHANGE_ENDORSE')
 OR NOT ops.ad_actor_covers_affected_set(g.actor_user_id,c.organization_id,
   (SELECT affected_set_id FROM ops.ad_action_reservation WHERE id=c.reservation_id),'AD_BID_CHANGE_ENDORSE') THEN
  RAISE EXCEPTION 'distinct scoped Operations Lead endorsement required' USING ERRCODE='MO094'; END IF;
 UPDATE ops.ad_compensation_authorization SET endorser_user_id=g.actor_user_id,endorsed_at=clock_timestamp() WHERE id=p_preview;
END $$;
CREATE FUNCTION ops.approve_ad_compensation(p_preview uuid,p_proof text) RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
DECLARE a ops.ad_compensation_authorization%ROWTYPE; c ops.ad_bid_command%ROWTYPE; g iam.ad_invocation_grant%ROWTYPE;
 b ops.ad_decision_policy_bundle%ROWTYPE; lifetime integer; authority_end timestamptz; credential_end timestamptz;
BEGIN
 SELECT * INTO a FROM ops.ad_compensation_authorization WHERE id=p_preview FOR UPDATE;
 g:=ops.consume_ad_control_invocation(p_proof,'COMPENSATION_APPROVE',a.command_id,p_preview);
 SELECT * INTO c FROM ops.ad_bid_command WHERE id=a.command_id;
 SELECT * INTO b FROM ops.ad_decision_policy_bundle WHERE id=a.bundle_id;
 SELECT material_lease_seconds INTO lifetime FROM core.ad_approval_lease_policy WHERE id=b.approval_lease_policy_id;
 IF a.owner_user_id IS NOT NULL OR a.endorser_user_id IS NULL OR a.preview_expires_at<=clock_timestamp()
 OR g.actor_user_id IN(a.maker_user_id,a.endorser_user_id)
 OR NOT ops.ad_actor_has_role_scope(g.actor_user_id,a.organization_id,c.store_id,'OWNER','AD_BID_CHANGE_APPROVE')
 OR NOT ops.ad_actor_covers_affected_set(g.actor_user_id,c.organization_id,
   (SELECT affected_set_id FROM ops.ad_action_reservation WHERE id=c.reservation_id),'AD_BID_CHANGE_APPROVE')
 OR NOT ops.ad_actor_has_role_scope(a.endorser_user_id,a.organization_id,c.store_id,'OPS_LEAD','AD_BID_CHANGE_ENDORSE')
 OR NOT ops.ad_actor_covers_affected_set(a.endorser_user_id,c.organization_id,
   (SELECT affected_set_id FROM ops.ad_action_reservation WHERE id=c.reservation_id),'AD_BID_CHANGE_ENDORSE')
 OR a.preview_snapshot IS DISTINCT FROM ops.ad_bundle_authority_snapshot(a.bundle_id) THEN
  RAISE EXCEPTION 'new distinct scoped Owner approval required' USING ERRCODE='MO094'; END IF;
 SELECT min((value->>'effective_to')::timestamptz) INTO authority_end
 FROM jsonb_each(a.preview_snapshot) WHERE jsonb_typeof(value)='object';
 SELECT min(ops.ad_credential_authority_expiry(credential.id,c.store_id)) INTO credential_end
 FROM platform.credential_metadata credential WHERE credential.marketplace_account_id=b.marketplace_account_id
 AND credential.organization_id=c.organization_id AND credential.purpose_code='ADS_WRITE' AND credential.status='ACTIVE';
 IF credential_end IS NULL OR credential_end<=clock_timestamp() OR lifetime IS NULL THEN
  RAISE EXCEPTION 'finite current compensation authorities required' USING ERRCODE='MO094'; END IF;
 UPDATE ops.ad_compensation_authorization SET owner_user_id=g.actor_user_id,approved_at=clock_timestamp(),
 expires_at=least(a.preview_expires_at,clock_timestamp()+make_interval(secs=>lifetime),
   authority_end,credential_end,
   (SELECT min(effective_to) FROM iam.user_scope_grant WHERE user_id IN(g.actor_user_id,a.endorser_user_id)
    AND action_code IN('AD_BID_CHANGE_APPROVE','AD_BID_CHANGE_ENDORSE') AND status='ACTIVE'),
   (SELECT valid_until FROM ops.ad_gate_authority WHERE id=b.gate_authority_id)) WHERE id=p_preview;
 IF cardinality(ops.evaluate_ad_bid_compensation_gate(c.id))>0 THEN
  RAISE EXCEPTION 'exact compensation gate refuses' USING ERRCODE='MO094'; END IF;
 UPDATE ops.ad_bid_command SET state='COMPENSATION_PENDING',terminal_at=NULL,lease_owner=NULL,
 lease_expires_at=NULL,updated_at=clock_timestamp() WHERE id=c.id;
END $$;
CREATE FUNCTION ops.evaluate_ad_bid_compensation_gate(p_command uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,platform,pg_temp AS $$
DECLARE a ops.ad_compensation_authorization%ROWTYPE; c ops.ad_bid_command%ROWTYPE; g ops.ad_gate_authority%ROWTYPE;
 reasons text[]:='{}';
BEGIN
 SELECT * INTO c FROM ops.ad_bid_command WHERE id=p_command;
 SELECT * INTO a FROM ops.ad_compensation_authorization WHERE command_id=p_command
 ORDER BY approved_at DESC NULLS LAST,previewed_at DESC,id DESC LIMIT 1;
 SELECT gate.* INTO g FROM ops.ad_gate_authority gate JOIN ops.ad_decision_policy_bundle b
 ON b.gate_authority_id=gate.id WHERE b.id=a.bundle_id;
 IF a.id IS NULL OR a.owner_user_id IS NULL OR a.expires_at<=statement_timestamp()
 OR EXISTS(SELECT 1 FROM ops.ad_compensation_invalidation invalidated WHERE invalidated.compensation_id=a.id)
 OR a.preview_snapshot IS DISTINCT FROM ops.ad_bundle_authority_snapshot(a.bundle_id)
 OR a.captured_prior_bid<>c.prior_bid_amount OR a.current_owner_bid<>c.target_bid_amount
 OR a.reservation_id<>c.reservation_id OR a.affected_set_digest<>c.affected_set_digest THEN
  reasons:=array_append(reasons,'EXACT_COMPENSATION_APPROVAL_ABSENT_OR_STALE'); END IF;
 IF g.id IS NULL OR g.status<>'ACTIVE' OR g.direction<>'EXACT_PRIOR_BID_COMPENSATION'
 OR g.store_id<>c.store_id OR NOT c.ad_native_object_id=ANY(g.native_object_ids)
 OR ops.ad_nonnegative_numeric(g.exact_object_values#>>ARRAY[c.ad_native_object_id::text,'currentBid']) IS DISTINCT FROM c.target_bid_amount
 OR ops.ad_nonnegative_numeric(g.exact_object_values#>>ARRAY[c.ad_native_object_id::text,'targetBid']) IS DISTINCT FROM c.prior_bid_amount
 OR (g.exact_object_values#>>ARRAY[c.ad_native_object_id::text,'bidUnitCode']) IS DISTINCT FROM c.bid_unit_code
 OR (g.exact_object_values#>>ARRAY[c.ad_native_object_id::text,'currencyCode']) IS DISTINCT FROM c.currency_code
 OR abs(c.target_bid_amount-c.prior_bid_amount)/(CASE c.bid_unit_code WHEN 'CURRENCY_MINOR' THEN 100 ELSE 1 END)>g.max_bid_change_amount
 OR (SELECT count(*) FROM ops.ad_compensation_authorization accepted WHERE accepted.bundle_id=a.bundle_id
      AND accepted.approved_at IS NOT NULL)>g.max_commands
 OR g.valid_from>statement_timestamp() OR g.valid_until<=statement_timestamp() THEN
  reasons:=array_append(reasons,'COMPENSATION_GATE_SCOPE_ABSENT'); END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.platform_capability cap WHERE cap.id=c.capability_id
 AND cap.status='ACTIVE' AND cap.verification_state='VERIFIED' AND cap.deprecated_at IS NULL)
 OR NOT EXISTS(SELECT 1 FROM ops.ad_action_reservation r WHERE r.id=c.reservation_id AND r.state='ACTIVE') THEN
  reasons:=array_append(reasons,'COMPENSATION_HARD_AUTHORITY_INVALID'); END IF;
 IF NOT ops.ad_actor_has_role_scope(a.owner_user_id,c.organization_id,c.store_id,'OWNER','AD_BID_CHANGE_APPROVE') THEN
  reasons:=array_append(reasons,'COMPENSATION_OWNER_AUTHORITY_EXPIRED'); END IF;
 IF NOT ops.ad_actor_covers_affected_set(a.owner_user_id,c.organization_id,
   (SELECT affected_set_id FROM ops.ad_action_reservation WHERE id=c.reservation_id),'AD_BID_CHANGE_APPROVE')
 OR NOT ops.ad_actor_has_role_scope(a.endorser_user_id,c.organization_id,c.store_id,'OPS_LEAD','AD_BID_CHANGE_ENDORSE')
 OR NOT ops.ad_actor_covers_affected_set(a.endorser_user_id,c.organization_id,
   (SELECT affected_set_id FROM ops.ad_action_reservation WHERE id=c.reservation_id),'AD_BID_CHANGE_ENDORSE') THEN
  reasons:=array_append(reasons,'COMPENSATION_AFFECTED_SCOPE_EXPIRED'); END IF;
 IF ops.ad_active_containment(c.organization_id,c.ad_native_object_id,c.store_id,c.platform_code,
  'ad-bid-change',c.affected_set_digest) && ARRAY['KILL_SWITCH_ACTIVE','CAPABILITY_QUARANTINED','AUTHORITY_VERSION_QUARANTINE'] THEN
  reasons:=array_append(reasons,'COMPENSATION_HARD_STOP_ACTIVE'); END IF;
 -- Only original business approval/bundle/outcome refusal is replaced by the
 -- new compensation approval. Every current platform and control stop remains.
 reasons:=reasons || ARRAY(SELECT reason FROM unnest(ops.evaluate_ad_bid_write_gate_base(c.id)) reason
 WHERE reason=ANY(ARRAY['CAPABILITY_NOT_VERIFIED','CAPABILITY_NOT_AVAILABLE_FOR_STORE','CAPABILITY_SWITCH_DISABLED',
 'GLOBAL_SWITCH_DISABLED','SCOPED_SWITCH_DISABLED','ENTITY_NOT_ALLOWLISTED','AFFECTED_SET_INCOMPLETE',
 'AFFECTED_SET_DIGEST_CHANGED','MAPPING_UNRESOLVED','MAPPING_CONFLICT_OPEN','RESERVATION_CONFLICT']));
 IF NOT EXISTS(SELECT 1 FROM platform.credential_metadata credential
 WHERE credential.marketplace_account_id=g.marketplace_account_id
 AND ops.ad_credential_authority_expiry(credential.id,c.store_id)>statement_timestamp()) THEN
  reasons:=array_append(reasons,'ADS_WRITE_CREDENTIAL_AUTHORITY_INVALID'); END IF;
 RETURN reasons || ops.ad_exposure_failures(c.organization_id,c.store_id,'EXACT_PRIOR_BID_COMPENSATION');
END $$;
REVOKE ALL ON FUNCTION ops.preview_ad_compensation(uuid,uuid,uuid,text),ops.endorse_ad_compensation(uuid,text),
 ops.approve_ad_compensation(uuid,text),ops.evaluate_ad_bid_compensation_gate(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.preview_ad_compensation(uuid,uuid,uuid,text),ops.endorse_ad_compensation(uuid,text),
 ops.approve_ad_compensation(uuid,text),ops.evaluate_ad_bid_compensation_gate(uuid) TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
 VALUES('ops','ad_compensation_authorization','NO_ROUTE',NULL,'exact human compensation preview, endorsement and final approval');

-- Bundle versions have one publisher. Draft content is never edited; activation
-- is atomic with retirement and permanent invalidation of the previous version.
CREATE TABLE ops.ad_bundle_endorsement (
 bundle_id uuid PRIMARY KEY REFERENCES ops.ad_decision_policy_bundle(id),
 endorser_user_id uuid NOT NULL REFERENCES iam.user_account(id),
 endorsed_at timestamptz NOT NULL, authority_snapshot jsonb NOT NULL
);
REVOKE ALL ON ops.ad_bundle_endorsement FROM PUBLIC,marketops_app;
GRANT SELECT ON ops.ad_bundle_endorsement TO marketops_app;
CREATE TRIGGER ad_bundle_endorsement_immutable BEFORE UPDATE OR DELETE ON ops.ad_bundle_endorsement
 FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
CREATE FUNCTION ops.create_ad_bundle_draft(p_content jsonb,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,iam,pg_temp AS $$
DECLARE b ops.ad_decision_policy_bundle%ROWTYPE; g iam.ad_invocation_grant%ROWTYPE;
BEGIN
 SELECT * INTO b FROM jsonb_populate_record(NULL::ops.ad_decision_policy_bundle,p_content);
 g:=ops.consume_ad_control_invocation(p_proof,'BUNDLE_DRAFT',b.id,(p_content->>'gate_scope_reference')::uuid);
 IF b.organization_id<>g.organization_id OR NOT ops.ad_actor_has_role_scope(g.actor_user_id,b.organization_id,
 b.store_id,'OPS_LEAD','ADVERTISING_POLICY_MANAGE') THEN
  RAISE EXCEPTION 'scoped Bundle policy publisher required' USING ERRCODE='MO092'; END IF;
 b.status:='DRAFT'; b.validation_state:='PENDING'; b.validation_failure_codes:=ARRAY['NOT_VALIDATED'];
 b.activated_by_user_id:=g.actor_user_id;b.endorsed_by_user_id:=NULL;b.approved_by_user_id:=NULL;
 b.gate_authority_id:=NULL;b.security_attestation_present:=coalesce(b.security_attestation_present,false);b.version:=0;b.created_at:=clock_timestamp();b.updated_at:=clock_timestamp();
 INSERT INTO ops.ad_decision_policy_bundle SELECT b.*;
 RETURN b.id;
END $$;
CREATE FUNCTION ops.endorse_ad_bundle(p_bundle uuid,p_gate uuid,p_proof text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,iam,pg_temp AS $$
DECLARE b ops.ad_decision_policy_bundle%ROWTYPE; g iam.ad_invocation_grant%ROWTYPE;
BEGIN
 g:=ops.consume_ad_control_invocation(p_proof,'BUNDLE_ENDORSE',p_bundle,p_gate);
 SELECT * INTO b FROM ops.ad_decision_policy_bundle WHERE id=p_bundle FOR UPDATE;
 IF b.status<>'DRAFT' OR b.activated_by_user_id=g.actor_user_id OR NOT ops.ad_actor_has_role_scope(g.actor_user_id,
 b.organization_id,b.store_id,'OPS_LEAD','ADVERTISING_POLICY_MANAGE')
 OR b.gate_scope_reference IS DISTINCT FROM p_gate::text
 OR NOT EXISTS(SELECT 1 FROM ops.ad_gate_authority gate WHERE gate.id=p_gate AND gate.bundle_id=b.id
   AND gate.organization_id=b.organization_id AND gate.status='ACTIVE') THEN
  RAISE EXCEPTION 'distinct scoped Bundle endorsement required' USING ERRCODE='MO092'; END IF;
 INSERT INTO ops.ad_bundle_endorsement VALUES(b.id,g.actor_user_id,clock_timestamp(),ops.ad_bundle_authority_snapshot(b.id)
   ||jsonb_build_object('proposedGate',(SELECT to_jsonb(gate) FROM ops.ad_gate_authority gate WHERE gate.id=p_gate)));
END $$;
-- Validate the complete combination and every immutable component reference.
ALTER FUNCTION ops.ad_bundle_validation_failures(uuid) RENAME TO ad_bundle_validation_failures_base;
CREATE FUNCTION ops.ad_bundle_validation_failures(p_bundle_id uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,platform,pg_temp AS $$
DECLARE failures text[]; snapshot jsonb; component record; b ops.ad_decision_policy_bundle%ROWTYPE;
BEGIN
 failures:=ops.ad_bundle_validation_failures_base(p_bundle_id);
 SELECT * INTO b FROM ops.ad_decision_policy_bundle WHERE id=p_bundle_id;
 IF NOT FOUND THEN RETURN failures; END IF;
 snapshot:=ops.ad_bundle_authority_snapshot(p_bundle_id);
 FOR component IN SELECT key,value FROM jsonb_each(snapshot)
 WHERE key IN ('conversion','allowableCpa','qualification','target','outcome','priority','humanSlo','lease','exposure','materiality') LOOP
  IF component.value IS NULL OR component.value='null'::jsonb
   OR component.value->>'status'<>'ACTIVE'
   OR (component.value->>'effective_from')::timestamptz>statement_timestamp()
   OR (component.value->>'effective_to')::timestamptz<=statement_timestamp()
   OR component.value->>'organization_id'<>b.organization_id::text
   OR component.value->>'scope_kind'='STORE' AND component.value->>'store_ref_id'<>b.store_id::text
   OR component.value->>'scope_kind'='PLATFORM' AND component.value->>'platform_code'<>b.platform_code THEN
   failures:=array_append(failures,upper(component.key)||'_AUTHORITY_NOT_CURRENT'); END IF;
 END LOOP;
 IF snapshot#>>'{semantic,status}' IS DISTINCT FROM 'ACTIVE'
 OR (snapshot#>>'{semantic,effective_to}')::timestamptz<=statement_timestamp() THEN
  failures:=array_append(failures,'SEMANTIC_AUTHORITY_NOT_CURRENT'); END IF;
 IF jsonb_array_length(snapshot->'freshness')=0 THEN failures:=array_append(failures,'FRESHNESS_AUTHORITY_MISSING'); END IF;
 -- Recovery authorizes replacement versions; the rejected immutable reference
 -- never becomes valid again, including after the review itself is closed.
 IF EXISTS(SELECT 1 FROM ops.ad_containment q WHERE q.organization_id=b.organization_id
   AND q.scope_kind='AUTHORITY_VERSION' AND ops.ad_bundle_consumes_authority_version(b.id,
   CASE WHEN q.authority_version_reference ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    THEN q.authority_version_reference::uuid ELSE NULL END)) THEN
  failures:=array_append(failures,'AUTHORITY_VERSION_QUARANTINED'); END IF;
 RETURN ARRAY(SELECT DISTINCT failure FROM unnest(failures) failure ORDER BY failure);
END $$;
REVOKE ALL ON FUNCTION ops.ad_bundle_validation_failures_base(uuid) FROM PUBLIC,marketops_app;
REVOKE ALL ON FUNCTION ops.ad_bundle_validation_failures(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_bundle_validation_failures(uuid) TO marketops_app;
CREATE FUNCTION ops.activate_ad_bundle(p_bundle uuid,p_gate uuid,p_proof text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,iam,pg_temp AS $$
DECLARE b ops.ad_decision_policy_bundle%ROWTYPE; e ops.ad_bundle_endorsement%ROWTYPE;
 g iam.ad_invocation_grant%ROWTYPE; gate ops.ad_gate_authority%ROWTYPE; failures text[];
BEGIN
 g:=ops.consume_ad_control_invocation(p_proof,'BUNDLE_APPROVE',p_bundle,p_gate);
 SELECT * INTO b FROM ops.ad_decision_policy_bundle WHERE id=p_bundle FOR UPDATE;
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(b.organization_id::text));
 SELECT * INTO e FROM ops.ad_bundle_endorsement WHERE bundle_id=b.id;
 SELECT * INTO gate FROM ops.ad_gate_authority WHERE id=p_gate;
 IF b.status<>'DRAFT' OR e.bundle_id IS NULL OR g.actor_user_id IN(b.activated_by_user_id,e.endorser_user_id)
 OR NOT ops.ad_actor_has_role_scope(g.actor_user_id,b.organization_id,b.store_id,'OWNER','ADVERTISING_POLICY_MANAGE')
 OR e.authority_snapshot IS DISTINCT FROM (ops.ad_bundle_authority_snapshot(b.id)||jsonb_build_object('proposedGate',to_jsonb(gate)))
 OR gate.id IS NULL OR gate.status<>'ACTIVE' OR gate.bundle_id<>b.id OR gate.store_id<>b.store_id
 OR b.gate_scope_reference IS DISTINCT FROM p_gate::text
 OR gate.organization_id<>b.organization_id OR gate.platform_code<>b.platform_code OR gate.marketplace_account_id<>b.marketplace_account_id
 OR gate.direction<>b.direction OR gate.candidate_basis<>b.candidate_basis
 OR gate.valid_from>clock_timestamp() OR gate.valid_until<=clock_timestamp() THEN
  RAISE EXCEPTION 'complete endorsed Bundle and exact scoped Owner Gate authority required' USING ERRCODE='MO092'; END IF;
 failures:=ops.ad_bundle_validation_failures(b.id);
 IF cardinality(failures)>0 THEN RAISE EXCEPTION 'whole Bundle validation failed: %',array_to_string(failures,',') USING ERRCODE='MO092'; END IF;
 UPDATE ops.ad_decision_policy_bundle SET status='SUPERSEDED',effective_to=clock_timestamp(),updated_at=clock_timestamp(),version=version+1
 WHERE organization_id=b.organization_id AND store_id=b.store_id AND direction=b.direction
 AND candidate_basis=b.candidate_basis AND native_object_kind=b.native_object_kind
 AND lifecycle_scope=b.lifecycle_scope AND status='ACTIVE';
 UPDATE ops.ad_decision_policy_bundle SET status='ACTIVE',validation_state='VALIDATED',validation_failure_codes='{}',
 endorsed_by_user_id=e.endorser_user_id,approved_by_user_id=g.actor_user_id,gate_authority_id=p_gate,
 updated_at=clock_timestamp(),version=version+1 WHERE id=b.id;
END $$;
REVOKE ALL ON FUNCTION ops.create_ad_bundle_draft(jsonb,text),ops.endorse_ad_bundle(uuid,uuid,text),
 ops.activate_ad_bundle(uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.create_ad_bundle_draft(jsonb,text),ops.endorse_ad_bundle(uuid,uuid,text),
 ops.activate_ad_bundle(uuid,uuid,text) TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
 VALUES('ops','ad_bundle_endorsement','NO_ROUTE',NULL,'immutable independent Bundle endorsement preceding atomic activation');

INSERT INTO iam.action_scope(code,display_name,description,requires_step_up,ordinal) VALUES
 ('ADVERTISING_TECHNICAL_STOP','Advertising technical stop','Explicit Platform/Security stop responsibility on the granted scope.',true,28),
 ('ADVERTISING_TECHNICAL_ATTEST','Advertising technical attestation','Explicit Platform/Security recovery attestation on the granted scope.',true,29);
INSERT INTO iam.business_role_action_scope(role_code,action_code) VALUES
 ('TECH_DATA','ADVERTISING_TECHNICAL_STOP'),('TECH_DATA','ADVERTISING_TECHNICAL_ATTEST'),
 ('OPS_LEAD','AD_BID_CHANGE_APPROVE');
ALTER TABLE ops.ad_containment ADD COLUMN review_owner_user_id uuid REFERENCES iam.user_account(id);
CREATE TABLE ops.ad_containment_attestation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), containment_id uuid NOT NULL REFERENCES ops.ad_containment(id),
 condition text NOT NULL CHECK(condition IN ('ROOT_CAUSE_CLASSIFIED','UNKNOWNS_RESOLVED','AUTHORITIES_REPLACED',
 'RESULTS_RECONCILED','CAPABILITY_EVIDENCE_CURRENT','SECURITY_ATTESTATION_PRESENT','OPERATIONS_ENDORSEMENT')),
 actor_user_id uuid NOT NULL REFERENCES iam.user_account(id), evidence_reference text NOT NULL,
 attested_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(containment_id,condition)
);
REVOKE ALL ON ops.ad_containment_attestation FROM PUBLIC,marketops_app;
GRANT SELECT ON ops.ad_containment_attestation TO marketops_app;
CREATE TRIGGER ad_containment_attestation_immutable BEFORE UPDATE OR DELETE ON ops.ad_containment_attestation
 FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
CREATE FUNCTION ops.activate_ad_regression_containment(p_observation uuid) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE outcome ops.ad_outcome_observation%ROWTYPE; c ops.ad_bid_command%ROWTYPE;
 a ops.ad_action_authorization%ROWTYPE; packet ops.ad_manual_execution_packet%ROWTYPE; identity uuid;
BEGIN
 SELECT * INTO outcome FROM ops.ad_outcome_observation WHERE id=p_observation AND verdict='REGRESSED';
 IF NOT FOUND THEN RAISE EXCEPTION 'canonical observed regression required' USING ERRCODE='MO097'; END IF;
 SELECT * INTO c FROM ops.ad_bid_command WHERE id=outcome.command_id;
 SELECT * INTO a FROM ops.ad_action_authorization WHERE recommendation_id=c.recommendation_id;
 IF c.id IS NULL THEN
  SELECT * INTO packet FROM ops.ad_manual_execution_packet WHERE id=outcome.manual_packet_id;
  c.organization_id:=packet.organization_id;c.store_id:=packet.store_id;c.platform_code:=packet.platform_code;
  c.ad_native_object_id:=packet.ad_native_object_id;c.affected_set_digest:=packet.affected_set_digest;
  c.reservation_id:=packet.reservation_id;a.endorser_user_id:=packet.endorser_user_id;
 END IF;
 IF a.endorser_user_id IS NULL THEN RAISE EXCEPTION 'accountable regression review owner absent' USING ERRCODE='MO097'; END IF;
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(c.organization_id::text));
 SELECT id INTO identity FROM ops.ad_containment WHERE evidence_reference='ad-outcome:'||p_observation;
 IF identity IS NOT NULL THEN RETURN identity; END IF;
 identity:=gen_random_uuid();
 INSERT INTO ops.ad_containment(id,organization_id,containment_kind,scope_kind,platform_code,store_id,
 ad_native_object_id,affected_set_digest,capability_code,cause_class,reason,evidence_reference,
 activated_by_trigger,activated_at,state,correlation_id,created_at,updated_at,review_owner_user_id)
 VALUES(identity,c.organization_id,'ACTION_OUTCOME_QUARANTINE','AFFECTED_SET',c.platform_code,c.store_id,
 c.ad_native_object_id,c.affected_set_digest,'ad-bid-change','OUTCOME_REGRESSION',
 'Canonical action-bound regression requires review','ad-outcome:'||p_observation,'AD_OUTCOME_REGRESSION',
 clock_timestamp(),'ACTIVE',outcome.correlation_id,clock_timestamp(),clock_timestamp(),a.endorser_user_id);
 UPDATE ops.ad_action_reservation SET regression_open=true,version=version+1 WHERE id=c.reservation_id AND state='ACTIVE';
 -- A late correction may arrive after a legitimate release. Reacquire only
 -- if no newer intervention holds the scope; the quarantine itself always
 -- persists and blocks execution, even when another reservation must settle.
 UPDATE ops.ad_action_reservation r SET state='ACTIVE',released_at=NULL,release_reason=NULL,
 regression_open=true,version=version+1 WHERE r.id=c.reservation_id AND r.state='RELEASED'
 AND NOT EXISTS(SELECT 1 FROM ops.ad_action_reservation other WHERE other.organization_id=r.organization_id
 AND other.state='ACTIVE' AND other.id<>r.id
 AND (other.ad_native_object_id=r.ad_native_object_id OR other.product_variant_ids && r.product_variant_ids));
 RETURN identity;
END $$;
CREATE FUNCTION ops.activate_ad_human_containment(p_id uuid,p_object uuid,p_scope text,p_kind text,p_cause text,
 p_review_owner uuid,p_reason text,p_evidence text,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; object_row core.ad_native_object%ROWTYPE; affected core.ad_affected_set%ROWTYPE;
 account_id uuid; permitted boolean:=false;
BEGIN
 g:=ops.consume_ad_control_invocation(p_proof,'CONTAINMENT_STOP',p_object,p_id);
 SELECT * INTO object_row FROM core.ad_native_object WHERE id=p_object;
 SELECT * INTO affected FROM core.ad_affected_set a WHERE a.ad_native_object_id=p_object
 ORDER BY a.resolved_at DESC,a.id DESC LIMIT 1;
 SELECT marketplace_account_id INTO account_id FROM core.store WHERE id=object_row.store_id;
 IF object_row.organization_id<>g.organization_id OR p_scope='AFFECTED_SET' AND affected.resolution_state IS DISTINCT FROM 'COMPLETE'
 OR p_reason IS NULL OR p_evidence IS NULL THEN RAISE EXCEPTION 'exact stop scope and evidence required' USING ERRCODE='MO097'; END IF;
 IF p_scope IN ('ENTITY','AFFECTED_SET') AND p_kind='EMERGENCY_ENTITY_HOLD' THEN
  permitted:=ops.ad_actor_has_role_scope(g.actor_user_id,g.organization_id,object_row.store_id,
   'MARKETPLACE_OPERATOR','ADVERTISING_TASK_ACT'); END IF;
 IF p_scope IN ('ENTITY','AFFECTED_SET','PLATFORM_STORE_CAPABILITY')
 AND p_cause IN ('BUSINESS_HARM','EXECUTION_INTEGRITY','OUTCOME_REGRESSION') THEN
  permitted:=permitted OR ops.ad_actor_has_role_scope(g.actor_user_id,g.organization_id,object_row.store_id,
   'OPS_LEAD','ADVERTISING_POLICY_MANAGE'); END IF;
 IF p_scope IN ('PLATFORM_STORE_CAPABILITY','PLATFORM_ACCOUNT_CAPABILITY')
 AND p_cause IN ('CREDENTIAL_OR_SECURITY','PROVIDER_OR_READBACK_DEFECT','EXECUTION_INTEGRITY') THEN
  permitted:=permitted OR ops.ad_actor_has_role_scope(g.actor_user_id,g.organization_id,object_row.store_id,
   'TECH_DATA','ADVERTISING_TECHNICAL_STOP');
  IF p_scope='PLATFORM_ACCOUNT_CAPABILITY' AND NOT EXISTS(SELECT 1 FROM iam.user_scope_grant sg
   WHERE sg.user_id=g.actor_user_id AND sg.action_code='ADVERTISING_TECHNICAL_STOP'
   AND sg.status='ACTIVE' AND sg.effective_from<=clock_timestamp() AND (sg.effective_to IS NULL OR sg.effective_to>clock_timestamp())
   AND (sg.organization_ref_id=g.organization_id OR sg.marketplace_account_ref_id=account_id)) THEN permitted:=false; END IF;
 END IF;
 IF NOT permitted OR NOT ops.ad_actor_has_role_scope(p_review_owner,g.organization_id,object_row.store_id,
 'OPS_LEAD','ADVERTISING_POLICY_MANAGE') THEN RAISE EXCEPTION 'stop exceeds actor scope or review owner is absent' USING ERRCODE='MO097'; END IF;
 INSERT INTO ops.ad_containment(id,organization_id,containment_kind,scope_kind,platform_code,marketplace_account_id,
 store_id,ad_native_object_id,affected_set_digest,capability_code,cause_class,reason,evidence_reference,activated_by_user_id,
 activated_at,state,correlation_id,created_at,updated_at,review_owner_user_id,action_command_id)
 VALUES(p_id,g.organization_id,p_kind,p_scope,object_row.platform_code,account_id,object_row.store_id,p_object,
 affected.affected_set_digest,'ad-bid-change',p_cause,p_reason,p_evidence,g.actor_user_id,clock_timestamp(),'ACTIVE',
 'ad-human-containment',clock_timestamp(),clock_timestamp(),p_review_owner,
 (SELECT c.id FROM ops.ad_bid_command c JOIN ops.ad_action_reservation held ON held.id=c.reservation_id
  WHERE c.ad_native_object_id=p_object AND c.organization_id=g.organization_id
  AND c.state='READBACK_MATCHED' AND held.state='ACTIVE' AND p_kind='EMERGENCY_ENTITY_HOLD'
  AND p_scope IN('ENTITY','AFFECTED_SET') AND p_cause='BUSINESS_HARM'
  AND EXISTS(SELECT 1 FROM ops.ad_bid_command_readback proof WHERE proof.command_id=c.id
    AND proof.match_state='MATCHES_TARGET' AND proof.observed_bid=c.target_bid_amount
    AND proof.currency_code=c.currency_code AND proof.bid_unit_code=c.bid_unit_code)
  ORDER BY c.created_at DESC,c.id DESC LIMIT 1));
 RETURN p_id;
END $$;
-- Null Store is never a wildcard: organization-wide recovery has its own
-- exact grant predicate, used only by the privileged control functions.
CREATE FUNCTION ops.ad_actor_has_organization_role_scope(p_actor uuid,p_org uuid,p_role text,p_action text)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,iam,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM iam.user_account actor JOIN iam.identity_provider provider ON provider.id=actor.identity_provider_id
 JOIN iam.user_role_assignment role ON role.user_id=actor.id AND role.organization_id=actor.organization_id
 JOIN iam.business_role_action_scope capability ON capability.role_code=role.role_code
 JOIN iam.user_scope_grant scope ON scope.user_id=actor.id AND scope.organization_id=actor.organization_id
   AND scope.action_code=capability.action_code
 WHERE actor.id=p_actor AND actor.organization_id=p_org AND actor.status='ACTIVE'
 AND provider.status='ACTIVE' AND provider.verification_state='VERIFIED'
 AND role.role_code=p_role AND capability.action_code=p_action
 AND role.status='ACTIVE' AND role.effective_from<=clock_timestamp() AND (role.effective_to IS NULL OR role.effective_to>clock_timestamp())
 AND scope.organization_ref_id=p_org AND scope.status='ACTIVE' AND scope.effective_from<=clock_timestamp()
 AND (scope.effective_to IS NULL OR scope.effective_to>clock_timestamp()))
$$;
REVOKE ALL ON FUNCTION ops.ad_actor_has_organization_role_scope(uuid,uuid,text,text) FROM PUBLIC,marketops_app;

CREATE FUNCTION ops.attest_ad_containment(p_id uuid,p_condition text,p_evidence text,p_proof text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,iam,pg_temp AS $$
DECLARE q ops.ad_containment%ROWTYPE; g iam.ad_invocation_grant%ROWTYPE; permitted boolean;
BEGIN
 SELECT * INTO q FROM ops.ad_containment WHERE id=p_id FOR UPDATE;
 g:=ops.consume_ad_control_invocation(p_proof,
 CASE WHEN p_condition='OPERATIONS_ENDORSEMENT' THEN 'CONTAINMENT_ENDORSE' ELSE 'CONTAINMENT_ATTEST' END,p_id,p_id);
 permitted:=CASE WHEN q.scope_kind='AUTHORITY_VERSION' THEN
 ops.ad_actor_has_organization_role_scope(g.actor_user_id,q.organization_id,
   CASE WHEN p_condition='SECURITY_ATTESTATION_PRESENT' THEN 'TECH_DATA' ELSE 'OPS_LEAD' END,
   CASE WHEN p_condition='SECURITY_ATTESTATION_PRESENT' THEN 'ADVERTISING_TECHNICAL_ATTEST' ELSE 'ADVERTISING_POLICY_MANAGE' END)
 WHEN p_condition='SECURITY_ATTESTATION_PRESENT' THEN
 ops.ad_actor_has_role_scope(g.actor_user_id,q.organization_id,q.store_id,'TECH_DATA','ADVERTISING_TECHNICAL_ATTEST')
 ELSE ops.ad_actor_has_role_scope(g.actor_user_id,q.organization_id,q.store_id,'OPS_LEAD','ADVERTISING_POLICY_MANAGE') END;
 IF q.id IS NULL OR g.organization_id<>q.organization_id OR q.state='REENABLED' OR NOT permitted OR p_evidence IS NULL OR length(btrim(p_evidence))<1
 OR p_condition='OPERATIONS_ENDORSEMENT' AND g.actor_user_id=q.activated_by_user_id THEN
  RAISE EXCEPTION 'independent scoped evidence attestation required' USING ERRCODE='MO097'; END IF;
 INSERT INTO ops.ad_containment_attestation(containment_id,condition,actor_user_id,evidence_reference)
 VALUES(p_id,p_condition,g.actor_user_id,p_evidence);
 UPDATE ops.ad_containment SET state='REENABLEMENT_REVIEW',updated_at=clock_timestamp(),version=version+1 WHERE id=p_id;
END $$;
CREATE FUNCTION ops.reenable_ad_containment(p_id uuid,p_new_bundle uuid,p_proof text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
DECLARE q ops.ad_containment%ROWTYPE; g iam.ad_invocation_grant%ROWTYPE; e uuid;
BEGIN
 SELECT * INTO q FROM ops.ad_containment WHERE id=p_id FOR UPDATE;
 g:=ops.consume_ad_control_invocation(p_proof,'CONTAINMENT_REENABLE',p_id,p_new_bundle);
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(q.organization_id::text));
 SELECT actor_user_id INTO e FROM ops.ad_containment_attestation WHERE containment_id=p_id AND condition='OPERATIONS_ENDORSEMENT';
 IF q.id IS NULL OR g.organization_id<>q.organization_id OR q.state='REENABLED' OR e IS NULL
 OR g.actor_user_id=e OR g.actor_user_id=q.activated_by_user_id
 OR NOT (CASE WHEN q.scope_kind='AUTHORITY_VERSION' THEN
  ops.ad_actor_has_organization_role_scope(g.actor_user_id,q.organization_id,'OWNER','ADVERTISING_POLICY_MANAGE')
 ELSE ops.ad_actor_has_role_scope(g.actor_user_id,q.organization_id,q.store_id,'OWNER','ADVERTISING_POLICY_MANAGE') END)
 OR q.scope_kind='AUTHORITY_VERSION' AND EXISTS(SELECT 1 FROM ops.ad_containment_attestation a WHERE a.containment_id=p_id
  AND NOT ops.ad_actor_has_organization_role_scope(a.actor_user_id,q.organization_id,
   CASE WHEN a.condition='SECURITY_ATTESTATION_PRESENT' THEN 'TECH_DATA' ELSE 'OPS_LEAD' END,
   CASE WHEN a.condition='SECURITY_ATTESTATION_PRESENT' THEN 'ADVERTISING_TECHNICAL_ATTEST' ELSE 'ADVERTISING_POLICY_MANAGE' END))
 OR q.scope_kind='AUTHORITY_VERSION' AND EXISTS(SELECT 1 FROM ops.ad_decision_policy_bundle remaining
  WHERE remaining.organization_id=q.organization_id AND remaining.status='ACTIVE'
  AND ops.ad_bundle_consumes_authority_version(remaining.id,q.authority_version_reference::uuid))
 OR q.scope_kind='AUTHORITY_VERSION' AND EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet packet
  WHERE packet.organization_id=q.organization_id AND packet.execution_started_at IS NOT NULL
  AND packet.state IN ('MANUAL_EXECUTION_IN_PROGRESS','ACTION_REPORTED_CONFIGURATION_UNVERIFIED','MANUAL_EXECUTION_UNCERTAIN')
  AND (ops.ad_bundle_consumes_authority_version(packet.bundle_id,q.authority_version_reference::uuid)
   OR q.authority_version_reference=ANY(ARRAY[packet.semantic_profile_id::text,
    to_jsonb(packet)#>>'{authority_snapshot,policy,id}',to_jsonb(packet)#>>'{authority_snapshot,outcomePolicy,id}'])))
 OR EXISTS(SELECT 1 FROM unnest(ARRAY['ROOT_CAUSE_CLASSIFIED','UNKNOWNS_RESOLVED','AUTHORITIES_REPLACED',
 'RESULTS_RECONCILED','CAPABILITY_EVIDENCE_CURRENT']) required(condition)
 WHERE NOT EXISTS(SELECT 1 FROM ops.ad_containment_attestation a WHERE a.containment_id=p_id AND a.condition=required.condition))
 OR q.cause_class IN ('CREDENTIAL_OR_SECURITY','PROVIDER_OR_READBACK_DEFECT','EXECUTION_INTEGRITY')
 AND NOT EXISTS(SELECT 1 FROM ops.ad_containment_attestation a WHERE a.containment_id=p_id AND a.condition='SECURITY_ATTESTATION_PRESENT')
 OR EXISTS(SELECT 1 FROM ops.ad_authority_invalidation i JOIN ops.ad_action_authorization a ON a.id=i.authorization_id
 JOIN ops.ad_bid_command c ON c.recommendation_id=a.recommendation_id WHERE i.cause_reference=p_id
 AND c.state IN ('EXECUTING','PLATFORM_PENDING','UNKNOWN_REQUIRES_READBACK','READBACK_PENDING','READBACK_MISMATCH',
 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION','MANUAL_RESOLUTION','COMPENSATION_PENDING'))
 OR NOT EXISTS(SELECT 1 FROM ops.ad_decision_policy_bundle b JOIN ops.ad_gate_authority gate ON gate.id=b.gate_authority_id
 WHERE b.id=p_new_bundle AND b.status='ACTIVE' AND (q.scope_kind='AUTHORITY_VERSION' OR b.store_id=q.store_id)
 AND b.organization_id=q.organization_id AND cardinality(ops.ad_bundle_validation_failures(b.id))=0
 AND gate.organization_id=b.organization_id AND gate.store_id=b.store_id AND gate.bundle_id=b.id
 AND gate.status='ACTIVE' AND gate.valid_from<=clock_timestamp() AND gate.valid_until>clock_timestamp()
 AND NOT EXISTS(SELECT 1 FROM ops.ad_authority_invalidation i JOIN ops.ad_action_authorization a ON a.id=i.authorization_id
  WHERE i.cause_reference=p_id AND a.bundle_id=b.id)) THEN
 RAISE EXCEPTION 'new scope, resolved facts and independent recovery authorities required' USING ERRCODE='MO097'; END IF;
 UPDATE ops.ad_containment SET state='REENABLED',root_cause_classified=true,unknowns_resolved=true,authorities_replaced=true,
 results_reconciled=true,capability_evidence_current=true,security_attestation_present=EXISTS(SELECT 1 FROM ops.ad_containment_attestation a
 WHERE a.containment_id=p_id AND a.condition='SECURITY_ATTESTATION_PRESENT'),endorsed_by_user_id=e,approved_by_user_id=g.actor_user_id,
 reenabled_scope=jsonb_build_object('newBundleId',p_new_bundle)||CASE WHEN q.scope_kind='AUTHORITY_VERSION' THEN
  jsonb_build_object('organizationId',q.organization_id,'invalidAuthorityVersion',q.authority_version_reference,
   'allActiveConsumersReplaced',true,'invalidVersionRemainsForbidden',true) ELSE '{}'::jsonb END,reenabled_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1
 WHERE id=p_id;
 RETURN true;
END $$;
REVOKE INSERT,UPDATE,DELETE ON ops.ad_containment FROM marketops_app;
REVOKE ALL ON FUNCTION ops.activate_ad_regression_containment(uuid),ops.activate_ad_human_containment(uuid,uuid,text,text,text,uuid,text,text,text),
 ops.attest_ad_containment(uuid,text,text,text),ops.reenable_ad_containment(uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.activate_ad_regression_containment(uuid),ops.activate_ad_human_containment(uuid,uuid,text,text,text,uuid,text,text,text),
 ops.attest_ad_containment(uuid,text,text,text),ops.reenable_ad_containment(uuid,uuid,text) TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
 VALUES('ops','ad_containment_attestation','NO_ROUTE',NULL,'immutable independent recovery attestations; no old asset resurrection');

-- Organization-wide authority quarantine is a human narrowing operation. It
-- does not edit the referenced version or claim a new production permission.
CREATE FUNCTION ops.activate_ad_authority_version_containment(p_id uuid,p_authority uuid,
 p_review_owner uuid,p_reason text,p_evidence text,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; required_actor uuid; allowed_roles text[]; authority_index integer;
BEGIN
 g:=ops.consume_ad_control_invocation(p_proof,'AUTHORITY_VERSION_STOP',p_authority,p_id);
 PERFORM pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(g.organization_id::text));
 IF p_authority IS NULL OR p_review_owner IS NULL OR nullif(btrim(p_reason),'') IS NULL
 OR nullif(btrim(p_evidence),'') IS NULL THEN
  RAISE EXCEPTION 'exact authority version and closure review metadata required' USING ERRCODE='MO036'; END IF;
 FOR authority_index IN 1..2 LOOP
  required_actor:=CASE authority_index WHEN 1 THEN g.actor_user_id ELSE p_review_owner END;
  allowed_roles:=CASE authority_index WHEN 1 THEN ARRAY['OWNER','OPS_LEAD'] ELSE ARRAY['OPS_LEAD'] END;
  IF NOT EXISTS(SELECT 1 FROM unnest(allowed_roles) required_role
    WHERE ops.ad_actor_has_organization_role_scope(required_actor,g.organization_id,required_role,'ADVERTISING_POLICY_MANAGE')) THEN
   RAISE EXCEPTION 'organization-wide policy control and review-owner scope required' USING ERRCODE='MO064'; END IF;
 END LOOP;
 IF NOT EXISTS(SELECT 1 FROM ops.ad_decision_policy_bundle b WHERE b.organization_id=g.organization_id
   AND ops.ad_bundle_consumes_authority_version(b.id,p_authority))
 AND NOT EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet packet WHERE packet.organization_id=g.organization_id
   AND p_authority::text=ANY(ARRAY[packet.semantic_profile_id::text,to_jsonb(packet)#>>'{authority_snapshot,policy,id}',
     to_jsonb(packet)#>>'{authority_snapshot,outcomePolicy,id}'])) THEN
  RAISE EXCEPTION 'authority version is not consumed by this organization' USING ERRCODE='MO064'; END IF;
 INSERT INTO ops.ad_containment(id,organization_id,containment_kind,scope_kind,authority_version_reference,
  cause_class,reason,evidence_reference,activated_by_user_id,activated_at,state,correlation_id,
  created_at,updated_at,review_owner_user_id)
 VALUES(p_id,g.organization_id,'AUTHORITY_VERSION_QUARANTINE','AUTHORITY_VERSION',p_authority::text,
  'AUTHORITY_VERSION_INVALID',p_reason,p_evidence,g.actor_user_id,clock_timestamp(),'ACTIVE',
  'ad-authority-version-stop',clock_timestamp(),clock_timestamp(),p_review_owner);
 RETURN p_id;
END $$;
REVOKE ALL ON FUNCTION ops.activate_ad_authority_version_containment(uuid,uuid,uuid,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.activate_ad_authority_version_containment(uuid,uuid,uuid,text,text,text) TO marketops_app;

CREATE FUNCTION ops.ad_gate_scope_is_monotonic() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE prior ops.ad_gate_authority%ROWTYPE; object_id uuid; value jsonb;
 prior_exposure core.ad_exposure_envelope%ROWTYPE; current_exposure core.ad_exposure_envelope%ROWTYPE;
BEGIN
 IF NEW.status<>'ACTIVE' THEN RETURN NEW; END IF;
 FOREACH object_id IN ARRAY NEW.native_object_ids LOOP
  value:=NEW.exact_object_values->object_id::text;
  IF value IS NULL OR jsonb_typeof(value)<>'object'
  OR coalesce(value->>'currentBid','')!~'^[0-9]+(\.[0-9]+)?$'
  OR coalesce(value->>'targetBid','')!~'^[0-9]+(\.[0-9]+)?$'
  OR value->>'currencyCode' IS DISTINCT FROM NEW.currency_code
  OR coalesce(value->>'bidUnitCode','') NOT IN ('CURRENCY_MAJOR','CURRENCY_MINOR') THEN
   RAISE EXCEPTION 'exact native object current/target/unit/currency scope required' USING ERRCODE='MO092'; END IF;
 END LOOP;
 IF NEW.gate_kind='GATE_E' THEN
  SELECT * INTO prior FROM ops.ad_gate_authority WHERE id=NEW.predecessor_gate_ev_id;
  IF prior.id IS NULL OR prior.gate_kind<>'GATE_EV' OR prior.status NOT IN ('ACTIVE','EXPIRED')
  OR prior.organization_id<>NEW.organization_id OR prior.platform_code<>NEW.platform_code
  OR prior.marketplace_account_id<>NEW.marketplace_account_id OR prior.store_id<>NEW.store_id
  OR prior.direction<>NEW.direction OR prior.candidate_basis<>NEW.candidate_basis
  OR prior.currency_code<>NEW.currency_code OR NEW.max_bid_change_amount>prior.max_bid_change_amount
  OR NOT NEW.native_object_ids <@ prior.demonstrated_object_ids THEN
   RAISE EXCEPTION 'Gate E cannot exceed demonstrated Gate EV scope' USING ERRCODE='MO092'; END IF;
  -- This authority model represents demonstrated exact native value pairs, not
  -- an inferred numerical range. A different pair requires new accepted evidence.
  FOREACH object_id IN ARRAY NEW.native_object_ids LOOP
   IF NEW.exact_object_values->object_id::text IS DISTINCT FROM prior.exact_object_values->object_id::text THEN
    RAISE EXCEPTION 'Gate E native values must be demonstrated by its exact predecessor' USING ERRCODE='MO092'; END IF;
  END LOOP;
  SELECT e.* INTO prior_exposure FROM core.ad_exposure_envelope e
   JOIN ops.ad_decision_policy_bundle b ON b.exposure_envelope_id=e.id WHERE b.id=prior.bundle_id;
  SELECT e.* INTO current_exposure FROM core.ad_exposure_envelope e
   JOIN ops.ad_decision_policy_bundle b ON b.exposure_envelope_id=e.id WHERE b.id=NEW.bundle_id;
  IF prior_exposure.id IS NULL OR current_exposure.id IS NULL
   OR current_exposure.status<>'ACTIVE'
   OR current_exposure.organization_id<>NEW.organization_id OR prior_exposure.organization_id<>NEW.organization_id
   OR current_exposure.currency_code<>prior_exposure.currency_code
   OR current_exposure.scope_kind<>prior_exposure.scope_kind
   OR current_exposure.platform_code IS DISTINCT FROM prior_exposure.platform_code
   OR current_exposure.store_ref_id IS DISTINCT FROM prior_exposure.store_ref_id
   OR current_exposure.retained_window_days IS DISTINCT FROM prior_exposure.retained_window_days
   OR current_exposure.measurement_window_hours IS DISTINCT FROM prior_exposure.measurement_window_hours
   OR current_exposure.cumulative_window_hours IS DISTINCT FROM prior_exposure.cumulative_window_hours
   OR current_exposure.max_active_interventions>prior_exposure.max_active_interventions
   OR current_exposure.max_affected_retained_sales_share>prior_exposure.max_affected_retained_sales_share
   OR current_exposure.max_associated_spend_amount>prior_exposure.max_associated_spend_amount
   OR current_exposure.max_cumulative_bid_change_amount>prior_exposure.max_cumulative_bid_change_amount
   OR current_exposure.max_unresolved_transmitted_writes>prior_exposure.max_unresolved_transmitted_writes
   OR current_exposure.reserved_recovery_headroom_count<prior_exposure.reserved_recovery_headroom_count THEN
    RAISE EXCEPTION 'Gate E cannot broaden the demonstrated aggregate exposure authority' USING ERRCODE='MO092'; END IF;
  -- Gate EV's historical evidence window is not the new Pilot window. The new
  -- Owner Gate has its own bounded window; actual sealing and admission still
  -- intersect it with every current Profile, Bundle, Envelope and actor expiry.
  -- Likewise EV's one-time command count is not an ongoing aggregate limit:
  -- the exact current Gate count and all six Envelope axes remain independent.
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.ad_gate_scope_is_monotonic() FROM PUBLIC;
CREATE TRIGGER ad_gate_scope_monotonic BEFORE INSERT OR UPDATE ON ops.ad_gate_authority
 FOR EACH ROW EXECUTE FUNCTION ops.ad_gate_scope_is_monotonic();

CREATE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$ BEGIN
 IF to_jsonb(OLD) IS DISTINCT FROM to_jsonb(NEW) THEN
 INSERT INTO ops.ad_authority_invalidation(organization_id,authorization_id,cause_reference,cause_code)
 SELECT a.organization_id,a.id,OLD.id,'REFERENCED_AUTHORITY_CHANGED' FROM ops.ad_action_authorization a
 WHERE jsonb_path_exists(a.authority_snapshot,'$.**.id ? (@ == $id)',jsonb_build_object('id',OLD.id::text))
 ON CONFLICT DO NOTHING;
 END IF; RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change() FROM PUBLIC;
DO $$ DECLARE relation text; BEGIN
 FOREACH relation IN ARRAY ARRAY['core.ad_conversion_definition','core.ad_allowable_cpa_definition',
 'core.ad_optimization_qualification_policy','core.ad_bid_target_policy','core.ad_outcome_policy',
 'core.ad_priority_policy','core.ad_human_slo_profile','core.ad_approval_lease_policy','core.ad_exposure_envelope',
 'core.ad_materiality_policy','core.ad_freshness_profile','platform.ad_semantic_profile'] LOOP
 EXECUTE format('CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON %s FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change()',relation);
 END LOOP;
END $$;
CREATE FUNCTION ops.invalidate_ad_assets_on_switch_stop() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,core,platform,pg_temp AS $$ BEGIN
 IF NEW.flag_code='ad-bid-change-write' AND NEW.state='DISABLED' AND (TG_OP='INSERT' OR OLD.state<>'DISABLED') THEN
 INSERT INTO ops.ad_authority_invalidation(organization_id,authorization_id,cause_reference,cause_code)
 SELECT a.organization_id,a.id,NEW.id,'WRITE_SWITCH_STOPPED' FROM ops.ad_action_authorization a
 JOIN ops.recommendation r ON r.id=a.recommendation_id JOIN ops.ad_decision_policy_bundle b ON b.id=a.bundle_id
 WHERE NEW.scope_kind='GLOBAL' OR NEW.scope_kind='STORE' AND NEW.store_id=r.store_id
 OR NEW.scope_kind='PLATFORM' AND NEW.platform_code=b.platform_code
 OR NEW.scope_kind='MARKETPLACE_ACCOUNT' AND NEW.marketplace_account_id=b.marketplace_account_id
 OR NEW.scope_kind='CAPABILITY' AND NEW.capability_id=(SELECT id FROM platform.platform_capability cap
 WHERE cap.platform_code=b.platform_code AND cap.capability_code='ad-bid-change')
 ON CONFLICT DO NOTHING;
 END IF; RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.invalidate_ad_assets_on_switch_stop() FROM PUBLIC;
CREATE TRIGGER ad_switch_stop_invalidates AFTER INSERT OR UPDATE ON platform.feature_flag
 FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_switch_stop();

-- Release claims are derived from durable canonical evidence. The old boolean
-- setter is not an application authority and cannot turn unknowns into facts.
REVOKE ALL ON FUNCTION ops.observe_ad_reservation_condition(uuid,text,boolean) FROM PUBLIC,marketops_app;
CREATE TABLE ops.ad_reservation_state_history (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), reservation_id uuid NOT NULL REFERENCES ops.ad_action_reservation(id),
 old_state jsonb NOT NULL,new_state jsonb NOT NULL,recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON ops.ad_reservation_state_history FROM PUBLIC,marketops_app;
GRANT SELECT ON ops.ad_reservation_state_history TO marketops_app;
CREATE FUNCTION ops.record_ad_reservation_state_history() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,pg_temp AS $$ BEGIN
 INSERT INTO ops.ad_reservation_state_history(reservation_id,old_state,new_state) VALUES(NEW.id,to_jsonb(OLD),to_jsonb(NEW));
 RETURN NEW; END $$;
REVOKE ALL ON FUNCTION ops.record_ad_reservation_state_history() FROM PUBLIC;
CREATE TRIGGER ad_reservation_state_history AFTER UPDATE ON ops.ad_action_reservation
FOR EACH ROW EXECUTE FUNCTION ops.record_ad_reservation_state_history();
CREATE TRIGGER ad_reservation_state_history_immutable BEFORE UPDATE OR DELETE ON ops.ad_reservation_state_history
FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();
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
   AND proof.packet_id=packet.id AND proof.proves_configuration AND proof.conflict_state='NONE'
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
REVOKE ALL ON FUNCTION ops.release_ad_action_reservation(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.release_ad_action_reservation(uuid,text) TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
 VALUES('ops','ad_reservation_state_history','NO_ROUTE',NULL,'append-only release and late-regression reacquisition history');

CREATE FUNCTION ops.try_release_ad_reservation_after_outcome(p_observation uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE reservation uuid;
BEGIN
 SELECT coalesce(c.reservation_id,packet.reservation_id) INTO reservation FROM ops.ad_outcome_observation o
 LEFT JOIN ops.ad_bid_command c ON c.id=o.command_id
 LEFT JOIN ops.ad_manual_execution_packet packet ON packet.id=o.manual_packet_id
 WHERE o.id=p_observation AND o.outcome_stage IN ('OPERATIONAL','OPERATIONAL_REVISED')
 AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation newer WHERE newer.supersedes_observation_id=o.id);
 IF reservation IS NULL THEN RETURN false; END IF;
 RETURN ops.release_ad_action_reservation(reservation,'Canonical completed-sales and critical-unit early safety observation');
END $$;
REVOKE ALL ON FUNCTION ops.try_release_ad_reservation_after_outcome(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.try_release_ad_reservation_after_outcome(uuid) TO marketops_app;

-- Revoking and restoring credential evidence never resurrects an older action.
CREATE FUNCTION ops.invalidate_ad_assets_on_credential_authority_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,platform,pg_temp AS $$
DECLARE credential uuid; organization uuid; account uuid;
BEGIN
 IF TG_TABLE_NAME='credential_metadata' THEN
  credential:=OLD.id;organization:=OLD.organization_id;account:=OLD.marketplace_account_id;
 ELSIF TG_TABLE_NAME='ad_write_credential_attestation' THEN
  credential:=OLD.credential_id;organization:=OLD.organization_id;account:=OLD.marketplace_account_id;
 ELSE
  credential:=OLD.credential_id;
  SELECT organization_id,marketplace_account_id INTO organization,account FROM platform.credential_metadata WHERE id=credential;
 END IF;
 INSERT INTO ops.ad_authority_invalidation(organization_id,authorization_id,cause_reference,cause_code)
 SELECT a.organization_id,a.id,credential,'CREDENTIAL_AUTHORITY_CHANGED' FROM ops.ad_action_authorization a
 JOIN ops.ad_decision_policy_bundle b ON b.id=a.bundle_id
 WHERE a.organization_id=organization AND b.marketplace_account_id=account ON CONFLICT DO NOTHING;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
REVOKE ALL ON FUNCTION ops.invalidate_ad_assets_on_credential_authority_change() FROM PUBLIC;
CREATE TRIGGER ad_credential_metadata_invalidates_assets AFTER UPDATE OR DELETE ON platform.credential_metadata
FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_credential_authority_change();
CREATE TRIGGER ad_credential_attestation_invalidates_assets AFTER UPDATE OR DELETE ON platform.ad_write_credential_attestation
FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_credential_authority_change();
CREATE TRIGGER ad_credential_store_scope_invalidates_assets AFTER UPDATE OR DELETE ON platform.credential_store_scope
FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_credential_authority_change();

CREATE FUNCTION ops.invalidate_ad_compensation_on_authority_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,platform,pg_temp AS $$
DECLARE changed jsonb; reference uuid; actor uuid; credential uuid; account uuid;
BEGIN
 IF TG_OP='INSERT' THEN changed:=to_jsonb(NEW); ELSE changed:=to_jsonb(OLD); END IF;
 reference:=nullif(changed->>'id','')::uuid;
 IF TG_TABLE_NAME='credential_store_scope' THEN reference:=(changed->>'credential_id')::uuid; END IF;
 IF TG_TABLE_SCHEMA='iam' THEN
  actor:=CASE WHEN TG_TABLE_NAME='user_account' THEN reference ELSE (changed->>'user_id')::uuid END;
 END IF;
 IF TG_TABLE_NAME IN('credential_metadata','credential_store_scope','ad_write_credential_attestation') THEN
  credential:=CASE WHEN TG_TABLE_NAME='credential_metadata' THEN reference ELSE (changed->>'credential_id')::uuid END;
  account:=(changed->>'marketplace_account_id')::uuid;
  IF account IS NULL THEN SELECT marketplace_account_id INTO account FROM platform.credential_metadata WHERE id=credential; END IF;
 END IF;
 IF TG_TABLE_NAME='feature_flag' AND (changed->>'flag_code'<>'ad-bid-change-write'
   OR TG_OP='UPDATE' AND to_jsonb(NEW)->>'state'<>'DISABLED') THEN RETURN NEW; END IF;
 INSERT INTO ops.ad_compensation_invalidation(compensation_id,cause_reference,cause_code)
 SELECT a.id,reference,'REFERENCED_'||upper(TG_TABLE_NAME)||'_CHANGED' FROM ops.ad_compensation_authorization a
 JOIN ops.ad_bid_command c ON c.id=a.command_id JOIN ops.ad_decision_policy_bundle b ON b.id=a.bundle_id
 WHERE (actor IN(a.maker_user_id,a.endorser_user_id,a.owner_user_id)
  OR account=b.marketplace_account_id
  OR a.preview_snapshot::text LIKE '%'||reference::text||'%'
  OR TG_TABLE_NAME='feature_flag' AND (changed->>'scope_kind'='GLOBAL'
    OR changed->>'capability_id'=c.capability_id::text OR changed->>'store_id'=c.store_id::text
    OR changed->>'marketplace_account_id'=b.marketplace_account_id::text OR changed->>'platform_code'=c.platform_code)
  OR TG_TABLE_NAME='ad_containment' AND cardinality(ops.ad_active_containment(c.organization_id,c.ad_native_object_id,
    c.store_id,c.platform_code,'ad-bid-change',c.affected_set_digest))>0)
 ON CONFLICT DO NOTHING;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
REVOKE ALL ON FUNCTION ops.invalidate_ad_compensation_on_authority_change() FROM PUBLIC;
DO $$ DECLARE item record; BEGIN
 FOR item IN SELECT * FROM (VALUES
 ('platform','credential_metadata'),('platform','credential_store_scope'),('platform','ad_write_credential_attestation'),
 ('platform','ad_semantic_profile'),('platform','feature_flag'),('ops','ad_gate_authority'),('ops','ad_decision_policy_bundle'),
 ('core','ad_bid_target_policy'),('core','ad_approval_lease_policy'),('core','ad_exposure_envelope'),
 ('core','ad_outcome_policy'),('core','ad_materiality_policy'),('core','ad_freshness_profile'),
 ('core','ad_conversion_definition'),('core','ad_allowable_cpa_definition'),('core','ad_optimization_qualification_policy'),
 ('core','ad_priority_policy'),('core','ad_human_slo_profile'),
 ('iam','user_account'),('iam','user_role_assignment'),('iam','user_scope_grant')) tables(schema_name,table_name)
 LOOP
 EXECUTE format('CREATE TRIGGER ad_compensation_authority_invalidated AFTER UPDATE OR DELETE ON %I.%I FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change()',item.schema_name,item.table_name);
 END LOOP;
END $$;
CREATE TRIGGER ad_new_containment_invalidates_compensation AFTER INSERT OR UPDATE ON ops.ad_containment
FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

-- Canonical regression ingress derives its complete scope from the observation.
-- A caller-selected role or containment identity cannot create another route.
REVOKE ALL ON FUNCTION ops.reopen_ad_lineage_after_regression(uuid,uuid,text,text)
 FROM PUBLIC,marketops_app;

-- Human revocation is an epoch, not a temporary false result that revives on restore.
CREATE FUNCTION ops.invalidate_ad_assets_on_human_authority_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,iam,pg_temp AS $$
DECLARE actors uuid[];
BEGIN
 IF TG_TABLE_NAME='user_account' THEN
  IF TG_OP='UPDATE' AND ROW(OLD.status,OLD.identity_provider_id,OLD.external_subject,OLD.credentials_valid_from)
    IS NOT DISTINCT FROM ROW(NEW.status,NEW.identity_provider_id,NEW.external_subject,NEW.credentials_valid_from) THEN RETURN NEW; END IF;
  actors:=ARRAY[OLD.id];
 ELSIF TG_TABLE_NAME='identity_provider' THEN
  IF TG_OP='UPDATE' AND to_jsonb(OLD)-ARRAY['display_name','updated_at','version']
    IS NOT DISTINCT FROM to_jsonb(NEW)-ARRAY['display_name','updated_at','version'] THEN RETURN NEW; END IF;
  SELECT array_agg(id) INTO actors FROM iam.user_account WHERE identity_provider_id=OLD.id;
 ELSE
  IF TG_OP='UPDATE' AND to_jsonb(OLD)-ARRAY['reason','updated_at','version']
    IS NOT DISTINCT FROM to_jsonb(NEW)-ARRAY['reason','updated_at','version'] THEN RETURN NEW; END IF;
  actors:=ARRAY[OLD.user_id];
 END IF;
 INSERT INTO ops.ad_authority_invalidation(organization_id,authorization_id,cause_reference,cause_code)
 SELECT a.organization_id,a.id,OLD.id,'HUMAN_AUTHORITY_CHANGED' FROM ops.ad_action_authorization a
 WHERE a.maker_user_id=ANY(actors) OR a.endorser_user_id=ANY(actors) OR a.final_approver_user_id=ANY(actors)
 ON CONFLICT DO NOTHING;
 UPDATE ops.ad_manual_execution_packet packet SET state='MANUAL_PACKET_REVOKED',revoked_at=clock_timestamp(),
   revoked_reason='HUMAN_AUTHORITY_CHANGED',updated_at=clock_timestamp(),version=version+1
 WHERE packet.state IN('MANUAL_PACKET_DRAFT','MANUAL_PACKET_ENDORSED','MANUAL_PACKET_ISSUED')
 AND (packet.maker_user_id=ANY(actors) OR packet.endorser_user_id=ANY(actors) OR packet.approver_user_id=ANY(actors));
 UPDATE ops.ad_manual_execution_packet packet SET state='MANUAL_EXECUTION_UNCERTAIN',current_proof_id=NULL,
   updated_at=clock_timestamp(),version=version+1
 WHERE packet.execution_started_at IS NOT NULL AND packet.state IN('MANUAL_EXECUTION_IN_PROGRESS',
  'ACTION_REPORTED_CONFIGURATION_UNVERIFIED','MANUAL_CONFIGURATION_VERIFIED','MANUAL_EXECUTION_UNCERTAIN')
 AND (packet.maker_user_id=ANY(actors) OR packet.endorser_user_id=ANY(actors)
   OR packet.approver_user_id=ANY(actors) OR packet.executor_user_id=ANY(actors));
 UPDATE ops.ad_action_reservation held SET configuration_resolved=false,unknown_or_mismatch_open=true,version=held.version+1
 FROM ops.ad_manual_execution_packet packet WHERE packet.reservation_id=held.id AND held.state='ACTIVE'
 AND packet.execution_started_at IS NOT NULL AND packet.state='MANUAL_EXECUTION_UNCERTAIN'
 AND (packet.maker_user_id=ANY(actors) OR packet.endorser_user_id=ANY(actors)
   OR packet.approver_user_id=ANY(actors) OR packet.executor_user_id=ANY(actors));
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
REVOKE ALL ON FUNCTION ops.invalidate_ad_assets_on_human_authority_change() FROM PUBLIC,marketops_app;
DO $$ DECLARE relation text; BEGIN
 FOREACH relation IN ARRAY ARRAY['iam.user_account','iam.user_role_assignment','iam.user_scope_grant','iam.identity_provider'] LOOP
  EXECUTE format('CREATE TRIGGER ad_human_authority_invalidates AFTER UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_human_authority_change()',relation);
 END LOOP;
END $$;

-- AC131: read existing Shared authority. No new fact, policy or PriceCommand writer.
-- Nonterminal actual commands are known interventions; Tasks/recommendations are not.
CREATE FUNCTION ops.ad_listing_isolation_context(p_listing uuid,p_at timestamptz)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER
SET search_path=pg_catalog,core,ops,pg_temp AS $$
 WITH price AS (
  SELECT p.*,v.ingestion_time,v.source_time FROM core.listing_price_observation p
  JOIN core.fact_provenance v ON v.id=p.provenance_id
  WHERE p.platform_listing_variant_id=p_listing AND p.observed_at<p_at
   AND coalesce(v.ingestion_time,v.source_time)<=p_at AND (v.source_time IS NULL OR v.source_time<=p_at)
   AND NOT EXISTS(SELECT 1 FROM core.listing_price_observation n JOIN core.fact_provenance nv ON nv.id=n.provenance_id
     WHERE n.supersedes_fact_id=p.id AND n.observed_at<p_at
      AND coalesce(nv.ingestion_time,nv.source_time)<=p_at AND (nv.source_time IS NULL OR nv.source_time<=p_at))
  ORDER BY p.observed_at DESC,p.id DESC LIMIT 1
 ), health AS (
  SELECT h.* FROM core.listing_health_observation h JOIN core.fact_provenance v ON v.id=h.provenance_id
  WHERE h.platform_listing_variant_id=p_listing AND h.observed_at<p_at
   AND coalesce(v.ingestion_time,v.source_time)<=p_at AND (v.source_time IS NULL OR v.source_time<=p_at)
   AND NOT EXISTS(SELECT 1 FROM core.listing_health_observation n JOIN core.fact_provenance nv ON nv.id=n.provenance_id
     WHERE n.supersedes_fact_id=h.id AND n.observed_at<p_at
      AND coalesce(nv.ingestion_time,nv.source_time)<=p_at AND (nv.source_time IS NULL OR nv.source_time<=p_at))
  ORDER BY h.observed_at DESC,h.id DESC LIMIT 1
 ), stock AS (
  SELECT DISTINCT ON(s.fulfillment_mode_code) s.* FROM core.listing_stock_observation s
  JOIN core.fact_provenance v ON v.id=s.provenance_id
  WHERE s.platform_listing_variant_id=p_listing AND s.observed_at<p_at
   AND coalesce(v.ingestion_time,v.source_time)<=p_at AND (v.source_time IS NULL OR v.source_time<=p_at)
   AND NOT EXISTS(SELECT 1 FROM core.listing_stock_observation n JOIN core.fact_provenance nv ON nv.id=n.provenance_id
     WHERE n.supersedes_fact_id=s.id AND n.observed_at<p_at
      AND coalesce(nv.ingestion_time,nv.source_time)<=p_at AND (nv.source_time IS NULL OR nv.source_time<=p_at))
  ORDER BY s.fulfillment_mode_code,s.observed_at DESC,s.id DESC
 )
 SELECT jsonb_build_object(
  'listingVariantId',p_listing,'price',(SELECT coalesce(discount_price,selling_price,list_price) FROM price),
  'currency',(SELECT currency_code FROM price),'promotion',(SELECT promotion_active FROM price),
  'sellable',(SELECT sellable FROM health),'nativeStatus',(SELECT native_status FROM health),
  'contentCompleteness',(SELECT content_completeness FROM health),
  'availability',(SELECT CASE WHEN count(*)=0 OR bool_or(available_quantity IS NULL) THEN NULL
                     WHEN sum(available_quantity)>0 THEN 'POSITIVE' ELSE 'ZERO' END FROM stock),
  'evidence',jsonb_build_object('price',(SELECT id FROM price),'health',(SELECT id FROM health),
                     'stock',(SELECT coalesce(jsonb_agg(id ORDER BY id),'[]'::jsonb) FROM stock)))
$$;
REVOKE ALL ON FUNCTION ops.ad_listing_isolation_context(uuid,timestamptz) FROM PUBLIC,marketops_app;

CREATE FUNCTION ops.ad_action_isolation_snapshot(p_set uuid,p_baseline uuid,p_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path=pg_catalog,ops,core,mart,pg_temp AS $$
DECLARE a record; b record; item record; before_context jsonb; after_context jsonb; factor text;
 failures text[]:=ARRAY[]::text[]; uncertainties text[]:=ARRAY[]::text[];
 commands jsonb:='[]'::jsonb; contexts jsonb:='[]'::jsonb; listings uuid[];
BEGIN
 SELECT * INTO a FROM core.ad_affected_set WHERE id=p_set;
 IF p_at IS NULL OR a.id IS NULL OR a.resolution_state<>'COMPLETE' OR a.resolved_at>p_at OR cardinality(a.product_variant_ids)=0 THEN
  RETURN jsonb_build_object('state','UNRESOLVED','failures','[]'::jsonb,
   'uncertainties',jsonb_build_array('CROSS_DOMAIN_CONTEXT_UNRESOLVED'),
   'knownPriceCommands','[]'::jsonb,'contexts','[]'::jsonb,'affectedSetId',p_set,'asOf',p_at);
 END IF;
 -- Internal Variant overlap includes listings in other stores/platforms, while retaining
 -- the exact frozen listing scope even if its current mapping has since ended.
 SELECT array_agg(DISTINCT id ORDER BY id) INTO listings FROM (
   SELECT unnest(a.platform_listing_variant_ids) id
   UNION SELECT m.platform_listing_variant_id FROM core.listing_mapping m
    WHERE m.organization_id=a.organization_id AND m.product_variant_id=ANY(a.product_variant_ids)
      AND m.status IN('ACTIVE','ENDED') AND m.effective_from<=p_at
      AND (m.effective_to IS NULL OR m.effective_to>p_at)) scoped;
 IF cardinality(listings)=0 OR listings IS NULL THEN
  uncertainties:=array_append(uncertainties,'CROSS_DOMAIN_CONTEXT_UNRESOLVED');
 END IF;
 -- A real command is already bound to the Shared approval/authority snapshot. An
 -- unexecuted recommendation or Task is deliberately absent from this predicate.
 SELECT coalesce(jsonb_agg(jsonb_build_object('commandId',c.id,'listingVariantId',c.platform_listing_variant_id,
    'state',c.state,'priorPrice',c.prior_price,'targetPrice',c.target_price,'currency',c.currency_code)
    ORDER BY c.id),'[]'::jsonb) INTO commands FROM ops.price_command c
 WHERE c.organization_id=a.organization_id AND c.platform_listing_variant_id=ANY(listings)
  AND c.created_at<=p_at AND c.state NOT IN('SUCCEEDED','FAILED_FINAL','COMPENSATED','COMPENSATION_FAILED')
  AND c.target_price<>c.prior_price;
 IF jsonb_array_length(commands)>0 THEN failures:=array_append(failures,'KNOWN_CROSS_DOMAIN_INTERVENTION'); END IF;
 IF p_baseline IS NULL THEN
  uncertainties:=array_append(uncertainties,'CROSS_DOMAIN_CONTEXT_UNRESOLVED');
 ELSE
  SELECT * INTO b FROM ops.ad_outcome_baseline WHERE id=p_baseline;
  IF b.id IS NULL OR b.organization_id<>a.organization_id OR b.affected_set_id<>a.id
   OR b.ad_native_object_id<>a.ad_native_object_id OR b.affected_set_digest<>a.affected_set_digest
   OR b.prepared_at>p_at THEN failures:=array_append(failures,'CROSS_DOMAIN_BASELINE_IDENTITY_MISMATCH');
  ELSE
   IF cardinality(b.listing_variant_ids)=0 THEN uncertainties:=array_append(uncertainties,'CROSS_DOMAIN_CONTEXT_UNRESOLVED'); END IF;
   FOR item IN SELECT unnest(b.listing_variant_ids) id LOOP
    before_context:=ops.ad_listing_isolation_context(item.id,b.prepared_at);
    after_context:=ops.ad_listing_isolation_context(item.id,p_at);
    contexts:=contexts||jsonb_build_array(jsonb_build_object('listingVariantId',item.id,
          'frozenAt',b.prepared_at,'before',before_context,'current',after_context));
    -- Known-to-known change is non-comparable. One unknown factor does not hide a
    -- different known factor, and unknown itself is not invented material change.
    FOREACH factor IN ARRAY ARRAY['price','currency','promotion','sellable','availability'] LOOP
     IF before_context->>factor IS NULL OR after_context->>factor IS NULL
       OR before_context->>factor='UNKNOWN' OR after_context->>factor='UNKNOWN' THEN
      uncertainties:=array_append(uncertainties,'CROSS_DOMAIN_CONTEXT_UNRESOLVED');
     ELSIF before_context->factor IS DISTINCT FROM after_context->factor THEN
      failures:=array_append(failures,'CROSS_DOMAIN_BASELINE_NOT_COMPARABLE');
     END IF;
    END LOOP;
   END LOOP;
  END IF;
 END IF;
 SELECT coalesce(array_agg(DISTINCT reason ORDER BY reason),ARRAY[]::text[]) INTO failures FROM unnest(failures) reason;
 SELECT coalesce(array_agg(DISTINCT reason ORDER BY reason),ARRAY[]::text[]) INTO uncertainties FROM unnest(uncertainties) reason;
 RETURN jsonb_build_object('state',CASE WHEN cardinality(failures)>0 THEN 'UNAVAILABLE'
    WHEN cardinality(uncertainties)>0 THEN 'UNRESOLVED' ELSE 'ISOLATED' END,
    'failures',to_jsonb(failures),'uncertainties',to_jsonb(uncertainties),
    'knownPriceCommands',commands,'contexts',contexts,
    'comparisonBasis','EXACT_ACCEPTED_PRE_ACTION_CONTEXT','affectedSetId',a.id,'asOf',p_at,
    'concurrencyBoundary','Committed known changes rechecked before transmission; no shared PriceCommand serialization claimed');
END $$;
REVOKE ALL ON FUNCTION ops.ad_action_isolation_snapshot(uuid,uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_action_isolation_snapshot(uuid,uuid,timestamptz) TO marketops_app;
CREATE FUNCTION ops.ad_action_isolation_failures(p_set uuid,p_baseline uuid,p_at timestamptz)
RETURNS text[] LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path=pg_catalog,ops,core,pg_temp AS $$
BEGIN RETURN ARRAY(SELECT jsonb_array_elements_text(ops.ad_action_isolation_snapshot(p_set,p_baseline,p_at)->'failures')); END $$;
REVOKE ALL ON FUNCTION ops.ad_action_isolation_failures(uuid,uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_action_isolation_failures(uuid,uuid,timestamptz) TO marketops_app;
