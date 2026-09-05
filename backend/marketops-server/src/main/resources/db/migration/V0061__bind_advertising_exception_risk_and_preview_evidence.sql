-- R1 keeps risk acceptance and impact evidence auditable without enabling writes.
ALTER TABLE ops.ad_accepted_exception ADD COLUMN authority_valid_until timestamptz;
GRANT UPDATE(authority_valid_until) ON ops.ad_accepted_exception TO marketops_app;
CREATE FUNCTION ops.ad_exception_risk_snapshot(p_case uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog,core,mart,ops,ledger,platform,pg_temp AS $$
 SELECT jsonb_build_object(
  'platform',c.platform_code,'account',s.marketplace_account_id,'store',c.store_id,
  'object',c.ad_native_object_id,'nativeObjectKey',obj.native_object_key,
  'semanticProfile',c.semantic_profile_id,'affectedSet',a.affected_set_digest,
  'productVariantIds',a.product_variant_ids,'calculation',c.calculation_id,
  'lane',c.lane,'cause',c.cause_code,'evidenceState',c.evidence_state,
  'confidenceState',c.confidence_state,'blockers',c.blocker_codes,
  'officialSpend',jsonb_build_object('state',c.official_spend_state,'amount',c.official_spend_amount,'currency',c.profit_currency_code),
  'absoluteProfit',jsonb_build_object('state',c.contribution_profit_state,'amount',c.contribution_profit_amount,'currency',c.profit_currency_code),
  'profitPerAdRub',jsonb_build_object('state',c.profit_per_ad_rub_state,'value',c.profit_per_ad_rub_value),
  'conversion',jsonb_build_object('state',c.ad_linked_conversion_state,'value',c.ad_linked_conversion_value,'stage',c.ad_linked_conversion_stage),
  'diagnostics',coalesce((SELECT jsonb_agg(jsonb_build_object('productVariantId',d.product_variant_id,
      'listingVariantId',d.platform_listing_variant_id,'basis',d.basis,'confidence',d.confidence_state,
      'spend',d.spend_amount,'profit',d.contribution_profit_amount,'currency',d.currency_code,
      'sellability',d.sellability_state,'availability',d.availability_state,'criticalSalesUnit',d.is_critical_sales_unit)
      ORDER BY d.product_variant_id,d.platform_listing_variant_id)
      FROM mart.ad_case_variant_diagnostic d WHERE d.case_id=c.id AND d.calculation_id=c.calculation_id),'[]'::jsonb),
  'salesEvidence',coalesce((SELECT jsonb_agg(jsonb_build_object('id',f.id,'stage',f.sale_stage,
      'sales',f.net_sales_amount,'currency',f.currency_code,'occurredAt',f.occurred_at) ORDER BY f.id)
      FROM ledger.ad_linked_sale_event f WHERE f.id IN(SELECT e.ad_linked_sale_event_id
          FROM mart.ad_case_evidence e WHERE e.case_id=c.id AND e.calculation_id=c.calculation_id)),'[]'::jsonb),
  'purposeEvidence',coalesce((SELECT jsonb_agg(jsonb_build_object('purpose',e.decision_purpose,
      'kind',e.evidence_kind,'profile',e.freshness_profile_id,'eligible',e.eligible,
      'expiresAt',e.expires_at,'reasons',e.reason_codes) ORDER BY e.decision_purpose,e.evidence_kind)
      FROM mart.ad_case_purpose_evidence e WHERE e.case_id=c.id AND e.calculation_id=c.calculation_id),'[]'::jsonb),
  'containment',ops.ad_active_containment(c.organization_id,c.ad_native_object_id,c.store_id,c.platform_code,'AD_BID_CHANGE',a.affected_set_digest),
  'policyDigest',c.policy_version_digest,'bundleId',c.bundle_id)
 FROM mart.ad_case c JOIN core.store s ON s.id=c.store_id JOIN core.ad_native_object obj ON obj.id=c.ad_native_object_id
 JOIN core.ad_affected_set a ON a.id=c.affected_set_id WHERE c.id=p_case
$$;
REVOKE ALL ON FUNCTION ops.ad_exception_risk_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_exception_risk_snapshot(uuid) TO marketops_app;

CREATE TABLE ops.ad_exception_decision_event(
 id uuid PRIMARY KEY, exception_id uuid NOT NULL REFERENCES ops.ad_accepted_exception(id),
 state text NOT NULL CHECK(state IN('REQUESTED','ENDORSED','ACTIVE','ENDED')),
 actor_user_id uuid NOT NULL REFERENCES iam.user_account(id),
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 1024),occurred_at timestamptz NOT NULL,
 UNIQUE(exception_id,state)
);
CREATE TRIGGER ad_exception_decision_immutable BEFORE UPDATE OR DELETE ON ops.ad_exception_decision_event
 FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();
GRANT SELECT,INSERT ON ops.ad_exception_decision_event TO marketops_app;

-- Losing an identity/role/grant cannot be erased by restoring its former value.
-- A reference event is retained even when the caller later sees a rolled-back refusal.
CREATE TABLE ops.ad_exception_authority_change(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),exception_id uuid NOT NULL REFERENCES ops.ad_accepted_exception(id),
 source_table text NOT NULL,source_id uuid NOT NULL,changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER ad_exception_authority_change_immutable BEFORE UPDATE OR DELETE ON ops.ad_exception_authority_change
 FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();
CREATE FUNCTION ops.record_ad_exception_identity_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE actor uuid;
BEGIN
 IF to_jsonb(NEW) IS NOT DISTINCT FROM to_jsonb(OLD) THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='user_account' THEN actor:=OLD.id; ELSE actor:=OLD.user_id; END IF;
 INSERT INTO ops.ad_exception_authority_change(exception_id,source_table,source_id)
 SELECT x.id,TG_TABLE_NAME,OLD.id FROM ops.ad_accepted_exception x
 WHERE x.state IN('REQUESTED','ENDORSED','ACTIVE') AND actor IN(x.requester_user_id,x.endorser_user_id,x.approver_user_id);
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.record_ad_exception_identity_change() FROM PUBLIC;
CREATE TRIGGER ad_exception_identity_change AFTER UPDATE ON iam.user_account
 FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_identity_change();
CREATE TRIGGER ad_exception_role_change AFTER UPDATE ON iam.user_role_assignment
 FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_identity_change();
CREATE TRIGGER ad_exception_grant_change AFTER UPDATE ON iam.user_scope_grant
 FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_identity_change();
GRANT SELECT ON ops.ad_exception_authority_change TO marketops_app;

CREATE TABLE ops.ad_impact_preview_evidence(
 evaluation_id uuid PRIMARY KEY REFERENCES ops.guardrail_evaluation(id),
 recommendation_id uuid NOT NULL REFERENCES ops.recommendation(id),
 evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
 recorded_at timestamptz NOT NULL
);
CREATE TRIGGER ad_impact_preview_evidence_immutable BEFORE UPDATE OR DELETE ON ops.ad_impact_preview_evidence
 FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();
GRANT SELECT,INSERT ON ops.ad_impact_preview_evidence TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note) VALUES
 ('ops','ad_exception_decision_event','NO_ROUTE',NULL,'immutable reason and authenticated actor for every risk decision'),
 ('ops','ad_exception_authority_change','NO_ROUTE',NULL,'permanent identity authority change prevents risk acceptance resurrection'),
 ('ops','ad_impact_preview_evidence','NO_ROUTE',NULL,'exact financial disclosure bound to the evaluated bid preview');

ALTER TABLE ops.ad_accepted_exception ADD CONSTRAINT ad_exception_authority_window_ck CHECK
 (authority_valid_until IS NOT NULL AND authority_valid_until<=expires_at AND authority_valid_until>effective_from);
CREATE FUNCTION ops.ad_exception_authority_only_tightens() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$ BEGIN
 IF NEW.authority_valid_until>OLD.authority_valid_until THEN
  RAISE EXCEPTION 'risk acceptance authority cannot be extended' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.ad_exception_authority_only_tightens() FROM PUBLIC;
CREATE TRIGGER ad_exception_authority_tightens BEFORE UPDATE ON ops.ad_accepted_exception
 FOR EACH ROW EXECUTE FUNCTION ops.ad_exception_authority_only_tightens();

CREATE FUNCTION core.check_ad_human_slo_strength() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,core,pg_temp AS $$ BEGIN
 IF EXISTS(SELECT 1 FROM core.ad_human_slo_profile protection JOIN core.ad_human_slo_profile optimization
  ON optimization.organization_id=protection.organization_id
  AND optimization.lane='OPTIMIZATION' AND optimization.status='ACTIVE'
  AND tstzrange(optimization.effective_from,optimization.effective_to,'[)') && tstzrange(protection.effective_from,protection.effective_to,'[)')
  WHERE protection.organization_id=NEW.organization_id AND protection.lane='PROTECTION' AND protection.status='ACTIVE'
  AND (protection.acknowledgement_minutes>optimization.acknowledgement_minutes
    OR protection.action_minutes>optimization.action_minutes OR protection.escalation_minutes>optimization.escalation_minutes
    OR optimization.staffed_coverage_enabled AND (NOT protection.staffed_coverage_enabled
      OR protection.staffed_coverage_timezone<>optimization.staffed_coverage_timezone
      OR EXISTS(SELECT 1 FROM generate_series(0,1439) minute WHERE
       (CASE WHEN optimization.staffed_coverage_start_minute<optimization.staffed_coverage_end_minute
         THEN minute>=optimization.staffed_coverage_start_minute AND minute<optimization.staffed_coverage_end_minute
         ELSE minute>=optimization.staffed_coverage_start_minute OR minute<optimization.staffed_coverage_end_minute END)
       AND NOT(CASE WHEN protection.staffed_coverage_start_minute<protection.staffed_coverage_end_minute
         THEN minute>=protection.staffed_coverage_start_minute AND minute<protection.staffed_coverage_end_minute
         ELSE minute>=protection.staffed_coverage_start_minute OR minute<protection.staffed_coverage_end_minute END))))) THEN
  RAISE EXCEPTION 'Protection response and coverage cannot be weaker than Optimization' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION core.check_ad_human_slo_strength() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER ad_human_slo_strength AFTER INSERT OR UPDATE ON core.ad_human_slo_profile
 DEFERRABLE INITIALLY IMMEDIATE FOR EACH ROW EXECUTE FUNCTION core.check_ad_human_slo_strength();

ALTER TABLE ops.ad_candidate_selection ADD CONSTRAINT ad_candidate_selection_baseline_fk
 FOREIGN KEY(outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id) DEFERRABLE INITIALLY DEFERRED;
