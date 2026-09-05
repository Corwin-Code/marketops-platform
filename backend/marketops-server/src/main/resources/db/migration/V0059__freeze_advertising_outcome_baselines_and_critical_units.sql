-- R1: outcome facts and the required-unit rule are frozen before any command.
ALTER TABLE core.ad_outcome_policy
    ADD COLUMN non_worsening_profit_band numeric(18,4) CHECK(non_worsening_profit_band>=0),
    ADD COLUMN non_worsening_per_rub_band numeric(18,6) CHECK(non_worsening_per_rub_band>=0),
    ADD COLUMN minimum_ad_spend_denominator numeric(18,4) CHECK(minimum_ad_spend_denominator>0),
    ADD COLUMN comparison_scale integer CHECK(comparison_scale BETWEEN 0 AND 8),
    ADD COLUMN comparison_rounding_mode text CHECK(comparison_rounding_mode IN('HALF_UP','HALF_EVEN','DOWN','UP')),
    ADD COLUMN material_boundary_inclusive boolean,
    ADD COLUMN negative_profit_terminal text CHECK(negative_profit_terminal='KEEP_PROTECTION_OPEN'),
    ADD COLUMN critical_unit_definition_complete boolean NOT NULL DEFAULT false,
    ADD COLUMN retained_window_days integer NOT NULL DEFAULT 30 CHECK (retained_window_days = 30),
    ADD COLUMN material_profit_delta numeric(18,4) CHECK (material_profit_delta >= 0),
    ADD COLUMN material_profit_per_rub_delta numeric(18,6) CHECK (material_profit_per_rub_delta >= 0),
    ADD COLUMN sales_preservation_tolerance_ratio numeric(6,5)
        CHECK (sales_preservation_tolerance_ratio >= 0 AND sales_preservation_tolerance_ratio < 1);

CREATE TABLE core.ad_outcome_critical_unit_rule (
    id uuid PRIMARY KEY,
    outcome_policy_id uuid NOT NULL REFERENCES core.ad_outcome_policy(id),
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    product_variant_id uuid NOT NULL,
    store_id uuid,
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 1024),
    evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id),
    UNIQUE NULLS NOT DISTINCT(outcome_policy_id,product_variant_id,store_id)
);

CREATE TABLE ops.ad_outcome_baseline (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    candidate_id uuid REFERENCES ops.ad_bid_candidate(id),
    manual_proposal_id uuid,
    ad_native_object_id uuid NOT NULL,
    affected_set_id uuid NOT NULL REFERENCES core.ad_affected_set(id),
    affected_set_digest text NOT NULL CHECK (affected_set_digest ~ '^[0-9a-f]{64}$'),
    product_variant_ids uuid[] NOT NULL,
    listing_variant_ids uuid[] NOT NULL,
    outcome_policy_id uuid NOT NULL REFERENCES core.ad_outcome_policy(id),
    outcome_policy_version integer NOT NULL,
    case_calculation_id uuid NOT NULL,
    policy_version_digest text NOT NULL,
    prepared_at timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    plan_snapshot jsonb NOT NULL CHECK (jsonb_typeof(plan_snapshot) = 'object'),
    input_digest text NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK (state IN ('COMPLETE','INCOMPLETE')),
    blocker_codes text[] NOT NULL,
    UNIQUE (candidate_id,input_digest),
    UNIQUE (manual_proposal_id,input_digest),
    CHECK((candidate_id IS NULL)<>(manual_proposal_id IS NULL)),
    FOREIGN KEY(ad_native_object_id,organization_id) REFERENCES core.ad_native_object(id,organization_id),
    CHECK(prepared_at < valid_until),
    CHECK(state <> 'COMPLETE' OR cardinality(blocker_codes) = 0)
);
CREATE TABLE ops.ad_outcome_stage_baseline (
    outcome_baseline_id uuid NOT NULL REFERENCES ops.ad_outcome_baseline(id),
    stage text NOT NULL CHECK(stage IN ('OPERATIONAL','RETAINED','SETTLED')),
    window_hours integer NOT NULL CHECK(window_hours > 0),
    snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot) = 'object'),
    PRIMARY KEY(outcome_baseline_id,stage)
);
CREATE TABLE ops.ad_outcome_critical_unit (
    outcome_baseline_id uuid NOT NULL REFERENCES ops.ad_outcome_baseline(id),
    product_variant_id uuid NOT NULL,
    listing_variant_id uuid NOT NULL REFERENCES core.platform_listing_variant(id),
    rule_id uuid NOT NULL REFERENCES core.ad_outcome_critical_unit_rule(id),
    PRIMARY KEY(outcome_baseline_id,product_variant_id,listing_variant_id)
);
CREATE TABLE ops.ad_outcome_critical_guard (
    outcome_baseline_id uuid NOT NULL REFERENCES ops.ad_outcome_baseline(id),
    product_variant_id uuid NOT NULL,
    listing_variant_id uuid NOT NULL,
    guard_state text NOT NULL CHECK(guard_state IN ('PASS','REGRESSED','UNKNOWN','NOT_DUE')),
    observed_at timestamptz NOT NULL,
    observation_id uuid NOT NULL REFERENCES ops.ad_outcome_observation(id),
    baseline_sales numeric(18,4),
    observed_sales numeric(18,4),
    PRIMARY KEY(outcome_baseline_id,product_variant_id,listing_variant_id,observed_at,observation_id),
    FOREIGN KEY(outcome_baseline_id,product_variant_id,listing_variant_id)
        REFERENCES ops.ad_outcome_critical_unit(outcome_baseline_id,product_variant_id,listing_variant_id)
);
CREATE TABLE ops.ad_outcome_axes (
    observation_id uuid PRIMARY KEY REFERENCES ops.ad_outcome_observation(id),
    outcome_baseline_id uuid NOT NULL REFERENCES ops.ad_outcome_baseline(id),
    dual_axis_verdict text NOT NULL,
    business_outcome text NOT NULL DEFAULT 'OUTCOME_PENDING' CHECK(business_outcome IN(
        'VERIFIED_EFFICIENCY_SUCCESS','VERIFIED_AD_RISK_CLEARED','VERIFIED_AD_EXPOSURE_STOPPED','IMPROVED_NOT_HEALTHY',
        'PROTECTION_IN_PROGRESS','OUTCOME_PENDING','OUTCOME_CONFOUNDED')),
    sales_preservation_verdict text NOT NULL,
    baseline_absolute_profit numeric(18,4),
    observed_absolute_profit numeric(18,4),
    baseline_profit_per_rub numeric(18,6),
    observed_profit_per_rub numeric(18,6),
    company_baseline_sales numeric(18,4),
    company_observed_sales numeric(18,4),
    currency_code text,
    input_snapshot jsonb NOT NULL CHECK(jsonb_typeof(input_snapshot) = 'object')
);
ALTER TABLE ops.ad_bid_command ADD COLUMN outcome_baseline_id uuid REFERENCES ops.ad_outcome_baseline(id);
ALTER TABLE ops.ad_action_authorization ADD CONSTRAINT ad_authorization_outcome_baseline_fk
    FOREIGN KEY(outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

CREATE FUNCTION ops.bind_ad_outcome_baseline() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog,pg_temp AS $$
BEGIN
    IF NEW.direction = 'EXACT_PRIOR_BID_COMPENSATION' THEN RETURN NEW; END IF;
    SELECT p.id INTO NEW.outcome_baseline_id FROM ops.ad_outcome_baseline p
      JOIN ops.ad_action_authorization authority ON authority.outcome_baseline_id=p.id
        AND authority.recommendation_id=NEW.recommendation_id AND authority.candidate_id=NEW.candidate_id
        AND authority.expires_at>NEW.created_at
      JOIN ops.ad_bid_candidate candidate ON candidate.id = p.candidate_id
      JOIN mart.ad_case c ON c.id = candidate.case_id
      WHERE p.candidate_id = NEW.candidate_id AND p.organization_id = NEW.organization_id
        AND p.affected_set_digest = NEW.affected_set_digest AND p.state = 'COMPLETE'
        AND p.case_calculation_id = c.calculation_id AND p.policy_version_digest = c.policy_version_digest
        AND p.prepared_at <= NEW.created_at AND p.valid_until > NEW.created_at
        AND ops.ad_outcome_baseline_is_canonical(p.id,NEW.created_at)
;
    IF NEW.outcome_baseline_id IS NULL THEN
        RAISE EXCEPTION 'an exact complete frozen outcome baseline is required' USING ERRCODE = 'MO099';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ad_bid_command_freeze_outcome BEFORE INSERT ON ops.ad_bid_command
FOR EACH ROW EXECUTE FUNCTION ops.bind_ad_outcome_baseline();

CREATE TRIGGER ad_outcome_baseline_immutable BEFORE UPDATE OR DELETE ON ops.ad_outcome_baseline
FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();
CREATE TRIGGER ad_outcome_stage_baseline_immutable BEFORE UPDATE OR DELETE ON ops.ad_outcome_stage_baseline
FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();
CREATE TRIGGER ad_outcome_critical_unit_immutable BEFORE UPDATE OR DELETE ON ops.ad_outcome_critical_unit
FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();
CREATE TRIGGER ad_outcome_critical_guard_immutable BEFORE UPDATE OR DELETE ON ops.ad_outcome_critical_guard
FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();
CREATE TRIGGER ad_outcome_axes_immutable BEFORE UPDATE OR DELETE ON ops.ad_outcome_axes
FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();
GRANT SELECT ON core.ad_outcome_critical_unit_rule TO marketops_app;
GRANT SELECT,INSERT ON ops.ad_outcome_baseline,ops.ad_outcome_stage_baseline,ops.ad_outcome_critical_unit,
    ops.ad_outcome_critical_guard,ops.ad_outcome_axes TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES ('core','ad_outcome_critical_unit_rule','NO_ROUTE',NULL,'versioned pre-action non-offsettable sales units'),
       ('ops','ad_outcome_baseline','NO_ROUTE',NULL,'immutable pre-action plan and baseline'),
       ('ops','ad_outcome_stage_baseline','NO_ROUTE',NULL,'distinct Completed Retained and Settled baselines'),
       ('ops','ad_outcome_critical_unit','NO_ROUTE',NULL,'frozen required unit membership'),
       ('ops','ad_outcome_critical_guard','NO_ROUTE',NULL,'per-unit non-offsettable safety result'),
       ('ops','ad_outcome_axes','NO_ROUTE',NULL,'canonical dual-axis result and evidence');

-- Exact official settlement-item association. Amounts and quantities are read
-- from the canonical SETTLED sales fact, never supplied as advertising estimates.
CREATE TABLE ledger.ad_settlement_attribution (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    ad_linked_sale_event_id uuid NOT NULL REFERENCES ledger.ad_linked_sale_event(id),
    settled_sales_fact_id uuid NOT NULL UNIQUE REFERENCES ledger.sales_fact(id),
    linkage_evidence_reference text NOT NULL CHECK(length(btrim(linkage_evidence_reference)) BETWEEN 1 AND 512),
    accepted_at timestamptz NOT NULL
);
CREATE FUNCTION ledger.validate_ad_settlement_attribution() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger.sales_fact financial JOIN ledger.ad_linked_sale_event linked
        ON linked.id=NEW.ad_linked_sale_event_id
        WHERE financial.id=NEW.settled_sales_fact_id AND financial.organization_id=NEW.organization_id
          AND linked.organization_id=NEW.organization_id
          AND financial.platform_listing_variant_id=linked.platform_listing_variant_id
          AND financial.sale_stage='SETTLED' AND financial.quantity>0
          AND linked.sale_stage='CANONICAL_AD_LINKED_RETAINED_SALE'
          AND financial.currency_code=linked.currency_code) THEN
        RAISE EXCEPTION 'settlement attribution must bind the same canonical unit and stage' USING ERRCODE='MO099';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validate_ad_settlement_attribution BEFORE INSERT ON ledger.ad_settlement_attribution
FOR EACH ROW EXECUTE FUNCTION ledger.validate_ad_settlement_attribution();
CREATE TRIGGER ad_settlement_attribution_immutable BEFORE UPDATE OR DELETE ON ledger.ad_settlement_attribution
FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();
GRANT SELECT,INSERT ON ledger.ad_settlement_attribution TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES ('ledger','ad_settlement_attribution','NO_ROUTE',NULL,'exact canonical financial attribution; never provider-attribution proxy');

-- Three distinct stages, with revisions preserving their own observation lineage.
ALTER TABLE ops.ad_outcome_observation DROP CONSTRAINT ad_outcome_observation_stage_ck;
ALTER TABLE ops.ad_outcome_observation ADD CONSTRAINT ad_outcome_observation_stage_ck
CHECK(outcome_stage IN('OPERATIONAL','OPERATIONAL_REVISED','RETAINED','RETAINED_REVISED','SETTLED','SETTLED_REVISED'));
ALTER TABLE ops.ad_outcome_observation DROP CONSTRAINT ad_outcome_observation_guard_stage_ck;
ALTER TABLE ops.ad_outcome_observation ADD CONSTRAINT ad_outcome_observation_guard_stage_ck
CHECK((outcome_stage IN('OPERATIONAL','OPERATIONAL_REVISED'))=(guard_state='NOT_APPLICABLE'));

ALTER TABLE ops.ad_outcome_observation DROP CONSTRAINT ad_outcome_observation_revision_shape_ck;
ALTER TABLE ops.ad_outcome_observation ADD CONSTRAINT ad_outcome_observation_revision_shape_ck
CHECK((outcome_stage LIKE '%_REVISED')=(supersedes_observation_id IS NOT NULL AND revision_no>1 AND adjustment_reason IS NOT NULL));
ALTER TABLE ops.ad_outcome_observation DROP CONSTRAINT ad_outcome_observation_first_revision_ck;
ALTER TABLE ops.ad_outcome_observation ADD CONSTRAINT ad_outcome_observation_first_revision_ck
CHECK(outcome_stage LIKE '%_REVISED' OR revision_no=1);
ALTER TABLE ops.ad_outcome_observation DROP CONSTRAINT ad_outcome_observation_settled_guard_ck;
ALTER TABLE ops.ad_outcome_observation ADD CONSTRAINT ad_outcome_observation_settled_guard_ck
CHECK(outcome_stage IN('OPERATIONAL','OPERATIONAL_REVISED') OR guard_state='SATISFIED'
    OR verdict IN('INDETERMINATE','NOT_YET_EVALUABLE','REGRESSED'));

CREATE FUNCTION ops.validate_frozen_ad_outcome_observation() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $$
DECLARE baseline ops.ad_outcome_baseline%ROWTYPE; frozen_stage ops.ad_outcome_stage_baseline%ROWTYPE;
    prior ops.ad_outcome_observation%ROWTYPE; landed timestamptz; stage_code text;
BEGIN
    SELECT b.* INTO baseline FROM ops.ad_bid_command c JOIN ops.ad_outcome_baseline b ON b.id=c.outcome_baseline_id
      WHERE c.id=NEW.command_id AND c.organization_id=NEW.organization_id;
    IF NOT FOUND OR baseline.ad_native_object_id<>NEW.ad_native_object_id
      OR baseline.affected_set_digest<>NEW.affected_set_digest OR baseline.outcome_policy_id<>NEW.outcome_policy_id
      OR baseline.outcome_policy_version<>NEW.outcome_policy_version THEN
        RAISE EXCEPTION 'observation must use the exact sealed pre-action baseline' USING ERRCODE='MO099';
    END IF;
    stage_code=replace(NEW.outcome_stage,'_REVISED','');
    SELECT * INTO frozen_stage FROM ops.ad_outcome_stage_baseline WHERE outcome_baseline_id=baseline.id AND stage=stage_code;
    SELECT min(observed_at) INTO landed FROM ops.ad_bid_command_readback WHERE command_id=NEW.command_id AND match_state='MATCHES_TARGET';
    IF NOT FOUND OR landed IS NULL OR NEW.window_starts_at<>landed+make_interval(mins=>(baseline.plan_snapshot->>'observationStartsMinutes')::integer)
      OR NEW.window_ends_at<>NEW.window_starts_at+make_interval(hours=>frozen_stage.window_hours)
      OR (NEW.evaluated_at<NEW.window_ends_at AND NEW.verdict<>'NOT_YET_EVALUABLE') THEN
        RAISE EXCEPTION 'observation must respect the frozen window' USING ERRCODE='MO099';
    END IF;
    IF NEW.supersedes_observation_id IS NOT NULL THEN
      SELECT * INTO prior FROM ops.ad_outcome_observation WHERE id=NEW.supersedes_observation_id;
      IF NOT FOUND OR prior.command_id<>NEW.command_id OR replace(prior.outcome_stage,'_REVISED','')<>stage_code
        OR prior.revision_no+1<>NEW.revision_no OR NEW.evaluated_at<prior.evaluated_at
        OR EXISTS(SELECT 1 FROM ops.ad_outcome_observation WHERE supersedes_observation_id=prior.id) THEN
        RAISE EXCEPTION 'revision must append to the exact same stage lineage' USING ERRCODE='MO099';
      END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validate_frozen_ad_outcome_observation BEFORE INSERT ON ops.ad_outcome_observation
FOR EACH ROW EXECUTE FUNCTION ops.validate_frozen_ad_outcome_observation();

-- Only the trusted canonical planner may attest a computed baseline. The
-- application SQL role can neither mint this proof nor insert frozen payloads.
CREATE TABLE ops.ad_outcome_plan_grant (
    proof_digest text PRIMARY KEY CHECK(proof_digest ~ '^[0-9a-f]{64}$'),
    baseline_id uuid NOT NULL,organization_id uuid NOT NULL REFERENCES core.organization(id),
    payload_digest text NOT NULL CHECK(payload_digest ~ '^[0-9a-f]{64}$'),
    application_backend_pid integer NOT NULL,application_transaction_id bigint NOT NULL,
    issued_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,consumed_at timestamptz
);
CREATE TABLE ops.ad_outcome_baseline_attestation (
    outcome_baseline_id uuid PRIMARY KEY REFERENCES ops.ad_outcome_baseline(id),
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    payload_digest text NOT NULL CHECK(payload_digest ~ '^[0-9a-f]{64}$'),
    attested_at timestamptz NOT NULL,
    planner_authority text NOT NULL CHECK(planner_authority='CANONICAL_OUTCOME_PLANNER_V1')
);
REVOKE ALL ON ops.ad_outcome_plan_grant,ops.ad_outcome_baseline_attestation FROM PUBLIC,marketops_app;
REVOKE INSERT,UPDATE,DELETE ON ops.ad_outcome_baseline,ops.ad_outcome_stage_baseline,ops.ad_outcome_critical_unit FROM marketops_app;
CREATE TRIGGER ad_outcome_attestation_immutable BEFORE UPDATE OR DELETE ON ops.ad_outcome_baseline_attestation
    FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE FUNCTION ops.ad_outcome_plan_snapshot(p_policy uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog,core,pg_temp AS $$
 SELECT jsonb_build_object('id',p.id,'version',p.policy_version,'operationalHours',p.completed_sales_guard_hours,
  'settledHours',greatest(720,p.settlement_window_hours),'observationStartsMinutes',p.observation_starts_minutes,
  'absoluteDelta',p.material_profit_delta,'perRubDelta',p.material_profit_per_rub_delta,
  'salesTolerance',p.sales_preservation_tolerance_ratio,'minimumCoverage',p.minimum_settled_coverage_ratio,
  'minimumTraffic',p.minimum_traffic_count,'criticalDefinitionComplete',p.critical_unit_definition_complete,
  'absoluteNonWorseningBand',p.non_worsening_profit_band,'perRubNonWorseningBand',p.non_worsening_per_rub_band,
  'minimumAdSpend',p.minimum_ad_spend_denominator,'comparisonScale',p.comparison_scale,
  'roundingMode',p.comparison_rounding_mode,'boundaryInclusive',p.material_boundary_inclusive,
  'negativeProfitTerminal',p.negative_profit_terminal)
 FROM core.ad_outcome_policy p WHERE p.id=p_policy
$$;
CREATE FUNCTION ops.ad_outcome_payload_digest(p_baseline jsonb,p_stages jsonb,p_units jsonb) RETURNS text
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,ops,pg_temp SET timezone='UTC' AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object(
  'baseline',to_jsonb(jsonb_populate_record(NULL::ops.ad_outcome_baseline,p_baseline)),
  'stages',(SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY s.stage),'[]') FROM jsonb_populate_recordset(NULL::ops.ad_outcome_stage_baseline,p_stages) s),
  'units',(SELECT coalesce(jsonb_agg(to_jsonb(u) ORDER BY u.product_variant_id,u.listing_variant_id),'[]') FROM jsonb_populate_recordset(NULL::ops.ad_outcome_critical_unit,p_units) u)
 )::text,'UTF8')),'hex')
$$;
CREATE FUNCTION ops.ad_outcome_stored_payload_digest(p_baseline uuid) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp SET timezone='UTC' AS $$
 SELECT ops.ad_outcome_payload_digest(to_jsonb(b),
  (SELECT coalesce(jsonb_agg(to_jsonb(s)),'[]') FROM ops.ad_outcome_stage_baseline s WHERE s.outcome_baseline_id=b.id),
  (SELECT coalesce(jsonb_agg(to_jsonb(u)),'[]') FROM ops.ad_outcome_critical_unit u WHERE u.outcome_baseline_id=b.id))
 FROM ops.ad_outcome_baseline b WHERE b.id=p_baseline
$$;
CREATE FUNCTION ops.issue_ad_outcome_plan_grant(p_proof_digest text,p_baseline uuid,p_organization uuid,
 p_payload_digest text,p_backend integer,p_transaction bigint) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
BEGIN
 IF p_proof_digest !~ '^[0-9a-f]{64}$' OR p_payload_digest !~ '^[0-9a-f]{64}$' OR p_backend<=0 OR p_transaction<=0 THEN
   RAISE EXCEPTION 'canonical planner proof is malformed' USING ERRCODE='MO099'; END IF;
 INSERT INTO ops.ad_outcome_plan_grant VALUES(p_proof_digest,p_baseline,p_organization,p_payload_digest,
   p_backend,p_transaction,clock_timestamp(),clock_timestamp()+interval '30 seconds',NULL);
END $$;
REVOKE ALL ON FUNCTION ops.issue_ad_outcome_plan_grant(text,uuid,uuid,text,integer,bigint) FROM PUBLIC,marketops_app;
GRANT EXECUTE ON FUNCTION ops.issue_ad_outcome_plan_grant(text,uuid,uuid,text,integer,bigint) TO marketops_identity_issuer;
GRANT USAGE ON SCHEMA ops TO marketops_identity_issuer;

CREATE FUNCTION ops.ad_outcome_freshness_snapshot(p_profile uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog,core,pg_temp AS $$
 SELECT jsonb_build_object('id',f.id,'version',f.profile_version,'evidenceKind',f.evidence_kind,'decisionPurpose',f.decision_purpose,
  'sourceMaxAgeMinutes',f.source_max_age_minutes,'acceptedFactMaxAgeMinutes',f.accepted_fact_max_age_minutes,
  'expectedPublicationLagMinutes',f.expected_publication_lag_minutes,'correctionWindowMinutes',f.correction_window_minutes,
  'requiresWindowComplete',f.requires_window_complete,'requiresCorrectionWindowClosed',f.requires_correction_window_closed,
  'minimumCoverageRatio',f.minimum_coverage_ratio,'minimumConfidenceState',f.minimum_confidence_state,
  'providerIncidentBlocks',f.provider_incident_blocks,'effectiveTo',f.effective_to)
 FROM core.ad_freshness_profile f WHERE f.id=p_profile
$$;
REVOKE ALL ON FUNCTION ops.ad_outcome_freshness_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_outcome_freshness_snapshot(uuid) TO marketops_app;

CREATE FUNCTION ops.ad_outcome_baseline_is_attested(p_baseline uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM ops.ad_outcome_baseline b JOIN ops.ad_outcome_baseline_attestation a
  ON a.outcome_baseline_id=b.id AND a.organization_id=b.organization_id
  WHERE b.id=p_baseline AND a.planner_authority='CANONICAL_OUTCOME_PLANNER_V1'
    AND a.payload_digest=ops.ad_outcome_stored_payload_digest(b.id))
$$;
CREATE FUNCTION ops.ad_outcome_baseline_is_canonical(p_baseline uuid,p_at timestamptz) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,ops,core,platform,mart,pg_temp AS $$
DECLARE b ops.ad_outcome_baseline%ROWTYPE; p core.ad_outcome_policy%ROWTYPE; a core.ad_affected_set%ROWTYPE;
 obj core.ad_native_object%ROWTYPE; st record; snap jsonb; item jsonb; units jsonb; expected_units jsonb;
 expected_critical jsonb; actual_critical jsonb; hours integer; policy_json jsonb; fresh core.ad_freshness_profile%ROWTYPE;
 direction_code text; purpose_code text; kind_code text; calc uuid; digest text; company numeric; total numeric;
BEGIN
 SELECT * INTO b FROM ops.ad_outcome_baseline WHERE id=p_baseline;
 IF NOT FOUND OR b.state<>'COMPLETE' OR cardinality(b.blocker_codes)<>0 OR b.prepared_at>p_at OR b.valid_until<=p_at
   OR NOT ops.ad_outcome_baseline_is_attested(b.id) THEN RETURN false; END IF;
 SELECT * INTO p FROM core.ad_outcome_policy WHERE id=b.outcome_policy_id AND organization_id=b.organization_id;
 SELECT * INTO a FROM core.ad_affected_set WHERE id=b.affected_set_id AND organization_id=b.organization_id;
 SELECT * INTO obj FROM core.ad_native_object WHERE id=b.ad_native_object_id AND organization_id=b.organization_id;
 IF p.id IS NULL OR a.id IS NULL OR obj.id IS NULL OR p.policy_version<>b.outcome_policy_version
   OR p.status NOT IN('ACTIVE','RETIRED') OR p.effective_from>b.prepared_at OR p.effective_from>p_at
   OR (p.effective_to IS NOT NULL AND (p.effective_to<=p_at OR b.valid_until>p.effective_to))
   OR a.resolution_state<>'COMPLETE' OR a.ad_native_object_id<>b.ad_native_object_id OR a.resolved_at>b.prepared_at
   OR a.affected_set_digest<>b.affected_set_digest OR NOT(a.product_variant_ids @> b.product_variant_ids AND a.product_variant_ids <@ b.product_variant_ids)
   OR NOT p.critical_unit_definition_complete
   OR num_nonnulls(p.material_profit_delta,p.material_profit_per_rub_delta,p.sales_preservation_tolerance_ratio,
      p.non_worsening_profit_band,p.non_worsening_per_rub_band,p.minimum_ad_spend_denominator,p.comparison_scale,
      p.comparison_rounding_mode,p.material_boundary_inclusive,p.negative_profit_terminal)<>10
   OR NOT(p.scope_kind='ORGANIZATION' OR (p.scope_kind='PLATFORM' AND p.platform_code=obj.platform_code)
      OR (p.scope_kind='STORE' AND p.store_ref_id=obj.store_id)) THEN RETURN false; END IF;
 IF EXISTS(SELECT 1 FROM core.ad_outcome_policy other WHERE other.organization_id=b.organization_id
   AND other.id<>p.id AND other.direction=p.direction AND other.status IN('ACTIVE','RETIRED')
   AND other.effective_from<=p_at AND (other.effective_to IS NULL OR other.effective_to>p_at)
   AND (other.scope_kind='ORGANIZATION' OR (other.scope_kind='PLATFORM' AND other.platform_code=obj.platform_code)
      OR (other.scope_kind='STORE' AND other.store_ref_id=obj.store_id))
   AND CASE other.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END
      <=CASE p.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END) THEN RETURN false; END IF;
 policy_json:=ops.ad_outcome_plan_snapshot(p.id);
 IF b.plan_snapshot IS DISTINCT FROM policy_json THEN RETURN false; END IF;
 IF b.candidate_id IS NOT NULL THEN
  SELECT k.calculation_id,k.policy_version_digest,c.direction INTO calc,digest,direction_code
  FROM ops.ad_bid_candidate c JOIN mart.ad_case k ON k.id=c.case_id
  WHERE c.id=b.candidate_id AND c.organization_id=b.organization_id AND k.ad_native_object_id=b.ad_native_object_id
    AND k.affected_set_id=b.affected_set_id AND k.superseded_at IS NULL;
 ELSE
  SELECT k.calculation_id,k.policy_version_digest,m.intended_state->>'direction' INTO calc,digest,direction_code
  FROM ops.ad_manual_proposal m JOIN mart.ad_case k ON k.id=m.case_id
  WHERE m.id=b.manual_proposal_id AND m.organization_id=b.organization_id AND k.ad_native_object_id=b.ad_native_object_id
    AND k.affected_set_id=b.affected_set_id AND k.superseded_at IS NULL;
 END IF;
 IF calc IS DISTINCT FROM b.case_calculation_id OR digest IS DISTINCT FROM b.policy_version_digest
   OR direction_code IS DISTINCT FROM p.direction THEN RETURN false; END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object('productVariantId',m.product_variant_id,'listingVariantId',m.platform_listing_variant_id,
    'storeId',listing.store_id,'ruleId',(SELECT r.id FROM core.ad_outcome_critical_unit_rule r
      WHERE r.organization_id=b.organization_id AND r.outcome_policy_id=p.id AND r.product_variant_id=m.product_variant_id
        AND (r.store_id IS NULL OR r.store_id=listing.store_id) ORDER BY r.store_id NULLS LAST LIMIT 1))
    ORDER BY m.product_variant_id,m.platform_listing_variant_id),'[]') INTO expected_units
 FROM core.listing_mapping m JOIN core.platform_listing_variant variant ON variant.id=m.platform_listing_variant_id
 JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id
 WHERE m.organization_id=b.organization_id AND m.product_variant_id=ANY(b.product_variant_ids)
   AND m.status IN('ACTIVE','ENDED') AND m.effective_from<=b.prepared_at AND (m.effective_to IS NULL OR m.effective_to>b.prepared_at);
 IF jsonb_array_length(expected_units)=0 OR EXISTS(SELECT 1 FROM unnest(b.product_variant_ids) product
      WHERE NOT EXISTS(SELECT 1 FROM jsonb_array_elements(expected_units) unit WHERE (unit->>'productVariantId')::uuid=product))
    OR (SELECT array_agg((unit->>'listingVariantId')::uuid ORDER BY unit->>'listingVariantId') FROM jsonb_array_elements(expected_units) unit)
      IS DISTINCT FROM (SELECT array_agg(listing ORDER BY listing::text) FROM unnest(b.listing_variant_ids) listing) THEN RETURN false; END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object('productVariantId',unit->'productVariantId','listingVariantId',unit->'listingVariantId','ruleId',unit->'ruleId')
   ORDER BY unit->>'productVariantId',unit->>'listingVariantId'),'[]') INTO expected_critical
 FROM jsonb_array_elements(expected_units) unit WHERE unit->>'ruleId' IS NOT NULL;
 SELECT coalesce(jsonb_agg(jsonb_build_object('productVariantId',u.product_variant_id,'listingVariantId',u.listing_variant_id,'ruleId',u.rule_id)
   ORDER BY u.product_variant_id,u.listing_variant_id),'[]') INTO actual_critical
 FROM ops.ad_outcome_critical_unit u WHERE u.outcome_baseline_id=b.id;
 IF expected_critical IS DISTINCT FROM actual_critical OR (SELECT count(*) FROM ops.ad_outcome_stage_baseline WHERE outcome_baseline_id=b.id)<>3 THEN RETURN false; END IF;
 FOR st IN SELECT * FROM ops.ad_outcome_stage_baseline WHERE outcome_baseline_id=b.id LOOP
  snap:=st.snapshot;
  hours:=CASE st.stage WHEN 'OPERATIONAL' THEN p.completed_sales_guard_hours WHEN 'RETAINED' THEN 720 ELSE greatest(720,p.settlement_window_hours) END;
  purpose_code:=CASE st.stage WHEN 'OPERATIONAL' THEN 'EARLY_COMPLETED_SALES_OUTCOME' WHEN 'RETAINED' THEN 'FINAL_RETAINED_SALES_OUTCOME' ELSE 'SETTLED_FINANCIAL_OUTCOME' END;
  kind_code:=CASE st.stage WHEN 'OPERATIONAL' THEN 'COMPANY_COMPLETED_SALE' WHEN 'RETAINED' THEN 'COMPANY_RETAINED_SALE' ELSE 'SETTLEMENT' END;
  IF st.window_hours<>hours OR snap->>'stage' IS DISTINCT FROM st.stage
    OR (snap->>'from')::timestamptz IS DISTINCT FROM b.prepared_at-make_interval(hours=>hours)
    OR (snap->>'to')::timestamptz IS DISTINCT FROM b.prepared_at
    OR jsonb_typeof(snap->'units') IS DISTINCT FROM 'array' OR jsonb_typeof(snap->'evidenceIds') IS DISTINCT FROM 'array'
    OR jsonb_typeof(snap->'blockers') IS DISTINCT FROM 'array' OR jsonb_typeof(snap->'profit') IS DISTINCT FROM 'object'
    OR jsonb_typeof(snap->'companySales') IS DISTINCT FROM 'object' OR jsonb_typeof(snap->'officialSpend') IS DISTINCT FROM 'object'
    OR snap->>'confounderDigest' IS NULL THEN RETURN false; END IF;
  SELECT jsonb_agg(unit->'unit' ORDER BY unit#>>'{unit,productVariantId}',unit#>>'{unit,listingVariantId}') INTO units
   FROM jsonb_array_elements(snap->'units') unit;
  IF units IS DISTINCT FROM expected_units THEN RETURN false; END IF;
  SELECT * INTO fresh FROM core.ad_freshness_profile WHERE id=(snap#>>'{freshnessProfile,id}')::uuid
    AND organization_id=b.organization_id AND status IN('ACTIVE','RETIRED') AND effective_from<=b.prepared_at
    AND (effective_to IS NULL OR (effective_to>p_at AND b.valid_until<=effective_to))
    AND effective_from<=p_at AND decision_purpose=purpose_code AND evidence_kind=kind_code
    AND (scope_kind='ORGANIZATION' OR (scope_kind='PLATFORM' AND platform_code=obj.platform_code)
      OR (scope_kind='STORE' AND store_ref_id=obj.store_id) OR (scope_kind='SEMANTIC_PROFILE' AND semantic_profile_id=obj.semantic_profile_id));
  IF NOT FOUND OR ((snap->'freshnessProfile')-'effectiveTo') IS DISTINCT FROM (ops.ad_outcome_freshness_snapshot(fresh.id)-'effectiveTo')
    OR (snap#>>'{freshnessProfile,effectiveTo}')::timestamptz IS DISTINCT FROM fresh.effective_to THEN RETURN false; END IF;
  IF EXISTS(SELECT 1 FROM core.ad_freshness_profile other WHERE other.organization_id=b.organization_id
    AND other.id<>fresh.id AND other.evidence_kind=kind_code AND other.decision_purpose=purpose_code
    AND other.status IN('ACTIVE','RETIRED') AND other.effective_from<=p_at AND (other.effective_to IS NULL OR other.effective_to>p_at)
    AND (other.scope_kind='ORGANIZATION' OR (other.scope_kind='PLATFORM' AND other.platform_code=obj.platform_code)
      OR (other.scope_kind='STORE' AND other.store_ref_id=obj.store_id) OR (other.scope_kind='SEMANTIC_PROFILE' AND other.semantic_profile_id=obj.semantic_profile_id))
    AND CASE other.scope_kind WHEN 'SEMANTIC_PROFILE' THEN 0 WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END
      <=CASE fresh.scope_kind WHEN 'SEMANTIC_PROFILE' THEN 0 WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END) THEN RETURN false; END IF;
  total:=0;
  FOR item IN SELECT value FROM jsonb_array_elements(snap->'units') LOOP
   IF st.stage='OPERATIONAL' AND (item#>>'{sales,valueState}' IS DISTINCT FROM 'AVAILABLE'
      OR item#>>'{sales,evidenceState}' NOT IN('CANONICAL_CONFIRMED','OPERATIONAL')
      OR jsonb_typeof(item#>'{sales,value}') IS DISTINCT FROM 'number') THEN RETURN false; END IF;
   total:=total+(item#>>'{sales,value}')::numeric;
  END LOOP;
  IF st.stage='OPERATIONAL' AND (snap#>>'{companySales,valueState}' IS DISTINCT FROM 'AVAILABLE'
     OR snap#>>'{companySales,evidenceState}' NOT IN('CANONICAL_CONFIRMED','OPERATIONAL')
     OR (snap#>>'{companySales,value}')::numeric IS DISTINCT FROM total) THEN RETURN false; END IF;
  IF direction_code='OPTIMIZATION_INCREASE' AND st.stage='RETAINED' AND
    (snap#>>'{companySales,valueState}' IS DISTINCT FROM 'AVAILABLE'
     OR snap#>>'{profit,absoluteProfit,valueState}' IS DISTINCT FROM 'AVAILABLE'
     OR snap#>>'{profit,profitPerAdRub,valueState}' IS DISTINCT FROM 'AVAILABLE'
     OR snap#>>'{profit,absoluteProfit,evidenceState}' NOT IN('CANONICAL_CONFIRMED','OPERATIONAL')
     OR snap#>>'{profit,profitPerAdRub,evidenceState}' NOT IN('CANONICAL_CONFIRMED','OPERATIONAL')) THEN RETURN false; END IF;
 END LOOP;
 RETURN true;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR invalid_datetime_format THEN RETURN false;
END $$;

CREATE FUNCTION ops.freeze_ad_outcome_baseline(p_baseline jsonb,p_stages jsonb,p_units jsonb,p_proof text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE b ops.ad_outcome_baseline%ROWTYPE; g ops.ad_outcome_plan_grant%ROWTYPE; digest text;
BEGIN
 b:=jsonb_populate_record(NULL::ops.ad_outcome_baseline,p_baseline);
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(p_stages||p_units) entry WHERE (entry->>'outcome_baseline_id')::uuid IS DISTINCT FROM b.id) THEN
   RAISE EXCEPTION 'frozen rows must belong to the exact attested baseline' USING ERRCODE='MO099'; END IF;
 digest:=ops.ad_outcome_payload_digest(p_baseline,p_stages,p_units);
 SELECT * INTO g FROM ops.ad_outcome_plan_grant WHERE proof_digest=encode(sha256(convert_to(p_proof,'UTF8')),'hex') FOR UPDATE;
 IF NOT FOUND OR g.baseline_id IS DISTINCT FROM b.id OR g.organization_id IS DISTINCT FROM b.organization_id
    OR g.payload_digest IS DISTINCT FROM digest OR g.application_backend_pid<>pg_backend_pid()
    OR g.application_transaction_id<>txid_current() OR g.expires_at<=clock_timestamp() OR g.consumed_at IS NOT NULL THEN
   RAISE EXCEPTION 'exact canonical planner proof required' USING ERRCODE='MO099'; END IF;
 UPDATE ops.ad_outcome_plan_grant SET consumed_at=clock_timestamp() WHERE proof_digest=g.proof_digest;
 INSERT INTO ops.ad_outcome_baseline SELECT b.*;
 INSERT INTO ops.ad_outcome_stage_baseline SELECT * FROM jsonb_populate_recordset(NULL::ops.ad_outcome_stage_baseline,p_stages);
 INSERT INTO ops.ad_outcome_critical_unit SELECT * FROM jsonb_populate_recordset(NULL::ops.ad_outcome_critical_unit,p_units);
 INSERT INTO ops.ad_outcome_baseline_attestation VALUES(b.id,b.organization_id,digest,clock_timestamp(),'CANONICAL_OUTCOME_PLANNER_V1');
 IF b.state='COMPLETE' AND NOT ops.ad_outcome_baseline_is_canonical(b.id,b.prepared_at) THEN
   RAISE EXCEPTION 'canonical baseline shape, policy, membership or stage is unresolved' USING ERRCODE='MO099'; END IF;
 RETURN b.id;
END $$;
REVOKE ALL ON FUNCTION ops.freeze_ad_outcome_baseline(jsonb,jsonb,jsonb,text),ops.ad_outcome_baseline_is_attested(uuid),
 ops.ad_outcome_baseline_is_canonical(uuid,timestamptz),ops.ad_outcome_stored_payload_digest(uuid),
 ops.ad_outcome_payload_digest(jsonb,jsonb,jsonb),ops.ad_outcome_plan_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.freeze_ad_outcome_baseline(jsonb,jsonb,jsonb,text),ops.ad_outcome_baseline_is_attested(uuid),
 ops.ad_outcome_baseline_is_canonical(uuid,timestamptz),ops.ad_outcome_payload_digest(jsonb,jsonb,jsonb),ops.ad_outcome_plan_snapshot(uuid) TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note) VALUES
 ('ops','ad_outcome_plan_grant','NO_ROUTE',NULL,'independent trusted planner proof bound to application transaction and full computed payload'),
 ('ops','ad_outcome_baseline_attestation','NO_ROUTE',NULL,'immutable trusted canonical calculation provenance; application cannot self-attest');
