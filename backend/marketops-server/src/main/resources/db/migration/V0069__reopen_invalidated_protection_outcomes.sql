-- Protection terminal invalidation is an Outcome regression, independent of
-- profit-axis success. Reuse the existing quarantine/reservation authority.
CREATE FUNCTION ops.ad_protection_outcome_invalidated(p_observation uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM ops.ad_outcome_observation o JOIN ops.ad_outcome_axes current ON current.observation_id=o.id
   JOIN LATERAL (
     -- The immediately preceding applicable result can be a revision of this
     -- stage or a completed shorter stage of the same frozen action window.
     -- Selecting before testing terminal state prevents repeated reopenings
     -- from an older terminal after responsibility is already active again.
     SELECT prior.business_outcome,previous.id=o.supersedes_observation_id same_window_revision
     FROM ops.ad_outcome_observation previous
     JOIN ops.ad_outcome_axes prior ON prior.observation_id=previous.id
       AND prior.outcome_baseline_id=current.outcome_baseline_id
     WHERE previous.id<>o.id AND previous.command_id IS NOT DISTINCT FROM o.command_id
       AND previous.manual_packet_id IS NOT DISTINCT FROM o.manual_packet_id
       AND previous.ad_native_object_id=o.ad_native_object_id AND previous.affected_set_digest=o.affected_set_digest
       AND previous.window_starts_at=o.window_starts_at AND previous.window_ends_at<=o.window_ends_at
       AND previous.evaluated_at<=o.evaluated_at
       AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation newer
         WHERE newer.supersedes_observation_id=previous.id AND newer.id<>o.id)
     ORDER BY previous.evaluated_at DESC,previous.window_ends_at DESC,previous.revision_no DESC,previous.id DESC LIMIT 1
   ) prior ON true
   WHERE o.id=p_observation
     AND prior.business_outcome IN('VERIFIED_AD_RISK_CLEARED','VERIFIED_AD_EXPOSURE_STOPPED')
     AND current.business_outcome IN('PROTECTION_IN_PROGRESS','OUTCOME_PENDING','OUTCOME_CONFOUNDED','IMPROVED_NOT_HEALTHY')
     AND (prior.same_window_revision OR (
       -- A longer stage with unknown inputs cannot invalidate a lawful result
       -- for the earlier window. Cross-stage recurrence needs observed harm.
       current.input_snapshot#>>'{observation,protectionEvidence,exactAffectedScope}'='true'
       AND ((prior.business_outcome='VERIFIED_AD_EXPOSURE_STOPPED'
         AND current.input_snapshot#>>'{observation,officialSpend,valueState}'='AVAILABLE'
         AND current.input_snapshot#>>'{observation,officialSpend,evidenceState}'='CANONICAL_CONFIRMED'
         AND (current.input_snapshot#>>'{observation,officialSpend,value}')::numeric>0)
       OR (prior.business_outcome='VERIFIED_AD_RISK_CLEARED' AND (
         (current.input_snapshot#>>'{baseline,originalCause}' IN('PROMOTED_VARIANT_NOT_SELLABLE','PROMOTED_VARIANT_UNAVAILABLE')
          AND current.input_snapshot#>>ARRAY['observation','protectionEvidence',
             CASE current.input_snapshot#>>'{baseline,originalCause}' WHEN 'PROMOTED_VARIANT_NOT_SELLABLE' THEN 'sellabilityCleared' ELSE 'availabilityCleared' END]='false'
          AND current.input_snapshot#>>ARRAY['observation','protectionEvidence',
             CASE current.input_snapshot#>>'{baseline,originalCause}' WHEN 'PROMOTED_VARIANT_NOT_SELLABLE' THEN 'sellabilityWindowComplete' ELSE 'availabilityWindowComplete' END]='true'
          AND EXISTS(SELECT 1 FROM jsonb_array_elements(current.input_snapshot#>'{observation,purposeEvidence}') proof
            WHERE proof->>'eligible'='true' AND proof->>'kind'=CASE current.input_snapshot#>>'{baseline,originalCause}'
              WHEN 'PROMOTED_VARIANT_NOT_SELLABLE' THEN 'SELLABILITY' ELSE 'AVAILABILITY' END))
         OR (current.input_snapshot#>>'{baseline,originalCause}'='PROVEN_ADVERTISING_LOSS'
          AND current.input_snapshot#>>'{observation,profit,absoluteProfit,valueState}'='AVAILABLE'
          AND current.input_snapshot#>>'{observation,profit,absoluteProfit,evidenceState}'='CANONICAL_CONFIRMED'
          AND (current.input_snapshot#>>'{observation,profit,absoluteProfit,value}')::numeric<0))))))
     AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation next WHERE next.supersedes_observation_id=o.id))
$$;
REVOKE ALL ON FUNCTION ops.ad_protection_outcome_invalidated(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_protection_outcome_invalidated(uuid) TO marketops_app;

CREATE OR REPLACE FUNCTION ops.activate_ad_regression_containment(p_observation uuid) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,core,pg_temp AS $$
DECLARE outcome ops.ad_outcome_observation%ROWTYPE; c ops.ad_bid_command%ROWTYPE;
 a ops.ad_action_authorization%ROWTYPE; packet ops.ad_manual_execution_packet%ROWTYPE; identity uuid;
BEGIN
 SELECT * INTO outcome FROM ops.ad_outcome_observation WHERE id=p_observation AND (verdict='REGRESSED' OR ops.ad_protection_outcome_invalidated(id));
 IF NOT FOUND THEN RAISE EXCEPTION 'canonical financial/sales regression or invalidated Protection terminal required' USING ERRCODE='MO097'; END IF;
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
 'Canonical action-bound safety result regressed or its Protection terminal was invalidated','ad-outcome:'||p_observation,'AD_OUTCOME_REGRESSION',
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
