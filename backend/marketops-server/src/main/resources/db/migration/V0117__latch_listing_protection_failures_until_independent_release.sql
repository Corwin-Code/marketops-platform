-- Existing Outcome rows are the failure authority; no second incident or Task store.
CREATE FUNCTION ops.lc_unreleased_outcome_failures(p_org uuid,p_listing uuid) RETURNS uuid[]
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT coalesce(array_agg(r.id ORDER BY r.evaluated_at,r.id),'{}'::uuid[])
 FROM ops.lc_node_result r JOIN ops.lc_evaluation_plan p ON p.id=r.plan_id
 JOIN ops.lc_action a ON a.id=p.action_id
 WHERE r.organization_id=p_org AND a.platform_listing_id=p_listing AND r.protection_verdict='FAIL'
  AND NOT EXISTS(SELECT 1 FROM ops.lc_containment c WHERE c.organization_id=p_org
    AND c.scope_kind='LISTING' AND c.platform_listing_id=p_listing AND c.state='REENABLED'
    AND c.evidence_reference='lc-node-result:'||r.id::text)
$$;
REVOKE ALL ON FUNCTION ops.lc_unreleased_outcome_failures(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_unreleased_outcome_failures(uuid,uuid) TO marketops_app;

ALTER FUNCTION ops.lc_scope_contained(uuid,uuid) RENAME TO lc_scope_contained_v0077;
CREATE FUNCTION ops.lc_scope_contained(p_org uuid,p_listing uuid) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT ops.lc_scope_contained_v0077(p_org,p_listing)
    OR cardinality(ops.lc_unreleased_outcome_failures(p_org,p_listing))>0
$$;
REVOKE ALL ON FUNCTION ops.lc_scope_contained(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_scope_contained(uuid,uuid) TO marketops_app;

CREATE FUNCTION ops.lc_current_containment_authority(p_actor uuid,p_org uuid,p_store uuid,p_action text) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT CASE WHEN p_store IS NOT NULL THEN ops.lc_actor_holds_action(p_actor,p_org,p_store,p_action)
 ELSE EXISTS(SELECT 1 FROM iam.user_account actor JOIN iam.identity_provider provider ON provider.id=actor.identity_provider_id
   JOIN iam.user_role_assignment r ON r.user_id=actor.id JOIN iam.business_role_action_scope m ON m.role_code=r.role_code
   JOIN iam.user_scope_grant g ON g.user_id=actor.id AND g.action_code=m.action_code
   WHERE actor.id=p_actor AND actor.organization_id=p_org AND actor.status='ACTIVE' AND provider.status='ACTIVE'
     AND r.organization_id=p_org AND r.status='ACTIVE' AND r.effective_from<=statement_timestamp()
     AND (r.effective_to IS NULL OR r.effective_to>statement_timestamp())
     AND m.action_code=p_action AND g.organization_id=p_org AND g.organization_ref_id=p_org AND g.status='ACTIVE'
     AND g.effective_from<=statement_timestamp() AND (g.effective_to IS NULL OR g.effective_to>statement_timestamp())) END
$$;
REVOKE ALL ON FUNCTION ops.lc_current_containment_authority(uuid,uuid,uuid,text) FROM PUBLIC;

-- Reuse the existing independent attestations; a good later result is necessary,
-- not sufficient, to release the exact failure cited by a Listing containment.
ALTER FUNCTION ops.reenable_lc_containment(uuid,uuid) RENAME TO reenable_lc_containment_v0077;
REVOKE ALL ON FUNCTION ops.reenable_lc_containment_v0077(uuid,uuid) FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.reenable_lc_containment(p_containment uuid,p_actor uuid,p_proof text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE c ops.lc_containment%ROWTYPE; failed ops.lc_node_result%ROWTYPE; latest ops.lc_node_result%ROWTYPE;
 grant_row iam.ad_invocation_grant%ROWTYPE; store_ref uuid; attestation record; repair ops.lc_containment_attestation%ROWTYPE; consent ops.lc_containment_attestation%ROWTYPE; required_action text;
BEGIN
 SELECT * INTO c FROM ops.lc_containment WHERE id=p_containment FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'containment does not exist' USING ERRCODE='MO090'; END IF;
 grant_row:=ops.consume_ad_control_invocation(p_proof,'LISTING_CONTAINMENT_REENABLE',p_containment,p_containment);
 IF grant_row.actor_user_id<>p_actor OR grant_row.organization_id<>c.organization_id THEN
   RAISE EXCEPTION 'reenablement actor must match the authenticated invocation' USING ERRCODE='MO092';
 END IF;
 store_ref:=coalesce(c.store_id,(SELECT store_id FROM core.platform_listing WHERE id=c.platform_listing_id),
   (SELECT store_id FROM ops.lc_batch WHERE id=c.batch_id));
 IF NOT ops.lc_current_containment_authority(p_actor,c.organization_id,store_ref,'LISTING_CONTAINMENT_CONSENT') THEN
   RAISE EXCEPTION 'current containment consent authority required' USING ERRCODE='MO092';
 END IF;
 FOR attestation IN SELECT * FROM ops.lc_containment_attestation WHERE containment_id=c.id LOOP
   required_action:=CASE WHEN attestation.attestation_kind='REPAIR_ATTESTATION'
     THEN 'LISTING_CONTAINMENT_ATTEST' ELSE 'LISTING_CONTAINMENT_CONSENT' END;
   IF NOT ops.lc_current_containment_authority(attestation.actor_user_id,c.organization_id,store_ref,required_action)
      OR (attestation.attestation_kind='REPAIR_ATTESTATION' AND NOT EXISTS(
        SELECT 1 FROM iam.user_role_assignment r WHERE r.user_id=attestation.actor_user_id
          AND r.organization_id=c.organization_id AND r.role_code=c.cause_owner_role_code AND r.status='ACTIVE'
          AND r.effective_from<=statement_timestamp() AND (r.effective_to IS NULL OR r.effective_to>statement_timestamp()))) THEN
     RAISE EXCEPTION 'containment attestation authority no longer applies' USING ERRCODE='MO092';
   END IF;
 END LOOP;
 IF c.evidence_reference LIKE 'lc-node-result:%' THEN
   SELECT r.* INTO failed FROM ops.lc_node_result r JOIN ops.lc_evaluation_plan p ON p.id=r.plan_id
    JOIN ops.lc_action a ON a.id=p.action_id
    WHERE c.evidence_reference='lc-node-result:'||r.id::text AND r.organization_id=c.organization_id
      AND c.scope_kind='LISTING' AND a.platform_listing_id=c.platform_listing_id AND r.protection_verdict='FAIL';
   IF NOT FOUND THEN RAISE EXCEPTION 'exact failed Outcome scope required' USING ERRCODE='MO092'; END IF;
   SELECT * INTO repair FROM ops.lc_containment_attestation WHERE containment_id=c.id AND attestation_kind='REPAIR_ATTESTATION';
   SELECT * INTO consent FROM ops.lc_containment_attestation WHERE containment_id=c.id AND attestation_kind='BUSINESS_CONSENT';
   SELECT r.* INTO latest FROM ops.lc_node_result r JOIN ops.lc_evaluation_plan p ON p.id=r.plan_id
    JOIN ops.lc_action a ON a.id=p.action_id
    WHERE repair.evidence_reference='lc-node-result:'||r.id::text AND r.organization_id=c.organization_id
      AND a.platform_listing_id=c.platform_listing_id AND r.evaluated_at>failed.evaluated_at;
   IF NOT FOUND THEN RAISE EXCEPTION 'repair must cite a subsequent qualified result for this Listing' USING ERRCODE='MO092'; END IF;
   -- A true past loss need not be rewritten: the repair may concern a later node or Action.
   PERFORM 1 FROM ops.lc_evaluation_plan WHERE id=latest.plan_id FOR UPDATE;
   IF latest.protection_verdict='FAIL' OR NOT latest.maturity_reached
      OR repair.attested_at<latest.evaluated_at OR consent.attested_at IS NULL OR consent.attested_at<repair.attested_at
      OR EXISTS(SELECT 1 FROM ops.lc_node_result newer WHERE newer.plan_id=latest.plan_id AND newer.node_code=latest.node_code
           AND newer.stage=latest.stage AND newer.revision_no>latest.revision_no)
      OR EXISTS(SELECT 1 FROM jsonb_each_text(failed.protection_vector) dimension WHERE dimension.value='FAIL'
           AND latest.protection_vector->>dimension.key IS DISTINCT FROM 'PASS') THEN
     RAISE EXCEPTION 'repair evidence and subsequent independent consent must still apply' USING ERRCODE='MO092';
   END IF;
 END IF;
 PERFORM ops.reenable_lc_containment_v0077(p_containment,p_actor);
END $$;
REVOKE ALL ON FUNCTION ops.reenable_lc_containment(uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.reenable_lc_containment(uuid,uuid,text) TO marketops_app;

-- Publish failure under the same stable domain lock as launch admission.
CREATE FUNCTION ops.lock_lc_outcome_failure_publication() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.protection_verdict='FAIL' THEN
   PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(NEW.organization_id::text));
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lock_lc_outcome_failure_publication() FROM PUBLIC;
CREATE TRIGGER lc_outcome_failure_publication BEFORE INSERT ON ops.lc_node_result
 FOR EACH ROW EXECUTE FUNCTION ops.lock_lc_outcome_failure_publication();

-- Distinct one-use intent, within the existing issuer and consent authority.
CREATE OR REPLACE FUNCTION iam.issue_ad_control_invocation_grant(p_purpose text,p_proof_hash text,p_actor uuid,p_org uuid,
 p_provider uuid,p_subject text,p_session text,p_authenticated timestamptz,p_step_up_until timestamptz,
 p_target uuid,p_version uuid,p_backend integer,p_transaction bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,iam,pg_temp AS $$ BEGIN
 IF p_purpose NOT IN ('COMPENSATION_PREVIEW','COMPENSATION_ENDORSE','COMPENSATION_APPROVE',
 'BUNDLE_DRAFT','BUNDLE_ENDORSE','BUNDLE_APPROVE','CONTAINMENT_STOP','AUTHORITY_VERSION_STOP','CONTAINMENT_REENABLE',
 'CONTAINMENT_ATTEST','CONTAINMENT_ENDORSE','MANUAL_POLICY_PUBLISH','MANUAL_PACKET_SELECT',
 'MANUAL_PACKET_ENDORSE','MANUAL_PACKET_APPROVE','MANUAL_EXECUTION_REPORT','MANUAL_EXECUTION_START','MANUAL_INDEPENDENT_VERIFY',
 'LISTING_ACTION_LAUNCH','LISTING_ACTION_REVIEW','LISTING_ACTION_APPROVE','LISTING_MANUAL_VERIFY',
 'LISTING_OCCUPATION_RELEASE','LISTING_CONTAINMENT_STOP','LISTING_CONTAINMENT_ATTEST',
 'LISTING_CONTAINMENT_CONSENT','LISTING_CONTAINMENT_REENABLE','LISTING_PROMOTION_EXIT',
 'LISTING_CALIBRATION_PREPARE','LISTING_CALIBRATION_VALIDATE','LISTING_CALIBRATION_ACCEPT','LISTING_CALIBRATION_ACTIVATE') THEN
  RAISE EXCEPTION 'unknown control invocation purpose' USING ERRCODE='MO092'; END IF;
 PERFORM iam.issue_ad_invocation_grant(p_proof_hash,p_actor,p_org,p_provider,p_subject,p_session,
 p_authenticated,p_step_up_until,p_target,p_version,p_backend,p_transaction);
 UPDATE iam.ad_invocation_grant SET purpose=p_purpose WHERE proof_hash=p_proof_hash;
END $$;

-- Preserve the existing stop/attestation lifecycle while binding its claimed actor.
CREATE OR REPLACE FUNCTION ops.record_lc_containment(
    p_id uuid, p_actor uuid, p_org uuid, p_proof text, p_scope_kind text,
    p_listing uuid, p_store uuid, p_platform text, p_batch uuid,
    p_cause_class text, p_cause_owner_role text, p_reason text, p_evidence text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, iam, pg_temp
AS $$
DECLARE scope_store uuid; grant_row iam.ad_invocation_grant%ROWTYPE;
BEGIN
    grant_row:=ops.consume_ad_control_invocation(p_proof, 'LISTING_CONTAINMENT_STOP', p_id, p_id);
    IF grant_row.actor_user_id<>p_actor OR grant_row.organization_id<>p_org THEN
        RAISE EXCEPTION 'stop actor must match the authenticated invocation' USING ERRCODE='MO092';
    END IF;
    scope_store := coalesce(p_store,
        (SELECT l.store_id FROM core.platform_listing l WHERE l.id = p_listing),
        (SELECT b.store_id FROM ops.lc_batch b WHERE b.id = p_batch));
    IF NOT ops.lc_current_containment_authority(p_actor,p_org,scope_store,'LISTING_CONTAINMENT_STOP') THEN
        RAISE EXCEPTION 'current stop authority required' USING ERRCODE='MO092';
    END IF;
    INSERT INTO ops.lc_containment (id, organization_id, scope_kind, platform_listing_id, store_id,
        platform_code, batch_id, cause_class, cause_owner_role_code, stopped_by_user_id, stopped_at,
        reason, evidence_reference, state)
    VALUES (p_id, p_org, p_scope_kind, p_listing, p_store, p_platform, p_batch, p_cause_class,
        p_cause_owner_role, p_actor, clock_timestamp(), p_reason, p_evidence, 'ACTIVE');
    -- Every live action inside the scope stops. Nothing is resumed by the stop
    -- itself; reenablement is a separate, doubly attested act.
    UPDATE ops.lc_action a
       SET state = 'CONTAINED', updated_at = clock_timestamp(), version = version + 1
     WHERE a.organization_id = p_org
       AND a.state IN ('APPROVED', 'APPROVED_NOT_LAUNCHABLE', 'LAUNCHED')
       AND ops.lc_scope_contained(p_org, a.platform_listing_id);
    RETURN p_id;
END;
$$;

CREATE OR REPLACE FUNCTION ops.attest_lc_containment(
    p_id uuid, p_containment uuid, p_actor uuid, p_proof text, p_kind text, p_evidence text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ops, core, iam, pg_temp
AS $$
DECLARE containment ops.lc_containment%ROWTYPE; purpose text; action text; grant_row iam.ad_invocation_grant%ROWTYPE; scope_store uuid;
BEGIN
    SELECT * INTO containment FROM ops.lc_containment WHERE id = p_containment FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'containment does not exist' USING ERRCODE = 'MO090'; END IF;
    IF containment.state <> 'ACTIVE' THEN
        RAISE EXCEPTION 'only an active containment is attested' USING ERRCODE = 'MO091';
    END IF;
    IF p_kind = 'REPAIR_ATTESTATION' THEN
        purpose := 'LISTING_CONTAINMENT_ATTEST'; action := 'LISTING_CONTAINMENT_ATTEST';
    ELSIF p_kind = 'BUSINESS_CONSENT' THEN
        purpose := 'LISTING_CONTAINMENT_CONSENT'; action := 'LISTING_CONTAINMENT_CONSENT';
    ELSE
        RAISE EXCEPTION 'unknown attestation kind' USING ERRCODE = 'MO092';
    END IF;
    grant_row:=ops.consume_ad_control_invocation(p_proof, purpose, p_containment, p_containment);
    IF grant_row.actor_user_id<>p_actor OR grant_row.organization_id<>containment.organization_id THEN
        RAISE EXCEPTION 'attester must match the authenticated invocation' USING ERRCODE='MO092';
    END IF;
    scope_store:=coalesce(containment.store_id,
        (SELECT store_id FROM core.platform_listing WHERE id=containment.platform_listing_id),
        (SELECT store_id FROM ops.lc_batch WHERE id=containment.batch_id));
    IF NOT ops.lc_current_containment_authority(p_actor,containment.organization_id,scope_store,action) THEN
        RAISE EXCEPTION 'current attestation authority required' USING ERRCODE='MO092';
    END IF;
    -- The repair attestation comes from the cause owner's role; the consent
    -- from the business owner. One person cannot give both.
    IF p_kind = 'REPAIR_ATTESTATION' AND NOT EXISTS (
        SELECT 1 FROM iam.user_role_assignment r
         WHERE r.user_id = p_actor AND r.organization_id=containment.organization_id AND r.role_code = containment.cause_owner_role_code AND r.status = 'ACTIVE'
           AND r.effective_from<=statement_timestamp() AND (r.effective_to IS NULL OR r.effective_to>statement_timestamp())) THEN
        RAISE EXCEPTION 'the repair attestation comes from the cause owner role' USING ERRCODE = 'MO092';
    END IF;
    IF EXISTS (SELECT 1 FROM ops.lc_containment_attestation a
                WHERE a.containment_id = p_containment AND a.actor_user_id = p_actor) THEN
        RAISE EXCEPTION 'one person cannot give both halves of a reenablement' USING ERRCODE = 'MO092';
    END IF;
    INSERT INTO ops.lc_containment_attestation (id, containment_id, attestation_kind, actor_user_id,
        evidence_reference, attested_at)
    VALUES (p_id, p_containment, p_kind, p_actor, p_evidence, clock_timestamp());
    RETURN p_id;
END;
$$;
