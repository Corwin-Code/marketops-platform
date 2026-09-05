package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.operationsworkflow.AdvertisingOutcomePlanning;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class AdvertisingOutcomePlanningService implements AdvertisingOutcomePlanning {
    record Policy(UUID id, int version, int operationalHours, int settledHours, int observationStartsMinutes,
                  BigDecimal absoluteDelta, BigDecimal perRubDelta, BigDecimal salesTolerance,
                  BigDecimal minimumCoverage, long minimumTraffic, boolean criticalDefinitionComplete,
                  BigDecimal absoluteNonWorseningBand,BigDecimal perRubNonWorseningBand,BigDecimal minimumAdSpend,
                  Integer comparisonScale,String roundingMode,Boolean boundaryInclusive,String negativeProfitTerminal) {
        boolean complete() { return criticalDefinitionComplete && absoluteDelta!=null && perRubDelta!=null && salesTolerance!=null
                && absoluteNonWorseningBand!=null && perRubNonWorseningBand!=null && minimumAdSpend!=null && comparisonScale!=null
                && roundingMode!=null && boundaryInclusive!=null && "KEEP_PROTECTION_OPEN".equals(negativeProfitTerminal); }
    }
    record Scope(UUID organization, UUID candidate, UUID object, UUID caseId, UUID calculation,
                 UUID affectedSet, String affectedDigest, List<UUID> products, List<UUID> listings,
                 String policyDigest, String platform, UUID store, UUID semanticProfile, Policy policy, Instant expiresAt, String direction) { }
    private final JdbcClient jdbc;
    private final AdvertisingOutcomeEvidenceService evidence;
    private final AdvertisingPolicyRepository policies;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final AdvertisingOutcomeService observations;
    private final AdvertisingOutcomePlanAttestor attestor;
    AdvertisingOutcomePlanningService(JdbcClient jdbc, AdvertisingOutcomeEvidenceService evidence,
            AdvertisingPolicyRepository policies, ObjectMapper json, IdGenerator ids, AdvertisingOutcomeService observations, AdvertisingOutcomePlanAttestor attestor) {
        this.jdbc=jdbc; this.evidence=evidence; this.policies=policies; this.json=json; this.ids=ids; this.observations=observations;this.attestor=attestor;
    }

    @Override @Transactional
    public UUID prepare(UUID organizationId, UUID candidateId, Instant at) {
        List<Scope> scopes = jdbc.sql("""
                SELECT c.id AS candidate_id,c.case_id,c.ad_native_object_id,c.semantic_profile_id,
                    k.calculation_id,k.policy_version_digest,k.platform_code,k.store_id,
                    a.id AS affected_set_id,a.affected_set_digest,a.product_variant_ids,a.platform_listing_variant_ids,
                    p.*, least(required.expires_at,p.effective_to) AS expires_at
                FROM ops.ad_bid_candidate c JOIN mart.ad_case k ON k.id=c.case_id
                JOIN LATERAL (SELECT min(e.expires_at) AS expires_at FROM mart.ad_case_purpose_evidence e
                    WHERE e.case_id=k.id AND e.calculation_id=k.calculation_id
                      AND e.decision_purpose=CASE c.direction WHEN 'OPTIMIZATION_INCREASE' THEN 'OPTIMIZATION_BID_WRITE' ELSE 'PROTECTION_BID_WRITE' END
                      AND e.evidence_kind=ANY(ops.ad_required_action_evidence_kinds(c.candidate_basis,c.cause_code))
                    HAVING count(*)=cardinality(ops.ad_required_action_evidence_kinds(c.candidate_basis,c.cause_code))
                      AND bool_and(e.eligible AND e.expires_at IS NOT NULL AND e.expires_at>:at)) required ON true
                JOIN core.ad_affected_set a ON a.id=k.affected_set_id
                JOIN ops.ad_decision_policy_bundle b ON b.organization_id=c.organization_id AND b.store_id=k.store_id
                  AND b.semantic_profile_id=c.semantic_profile_id AND b.target_policy_id=c.target_policy_id
                  AND b.direction=c.direction AND b.candidate_basis=c.candidate_basis
                  AND b.status='ACTIVE' AND b.validation_state='VALIDATED' AND b.effective_from<=:at
                  AND (b.effective_to IS NULL OR b.effective_to>:at)
                JOIN core.ad_outcome_policy p ON p.id=b.outcome_policy_id AND p.organization_id=c.organization_id AND p.direction=c.direction
                WHERE c.id=:candidate AND c.organization_id=:organization
                  AND c.affected_set_digest=a.affected_set_digest AND a.resolution_state='COMPLETE'
                  AND k.superseded_at IS NULL
                  AND p.status IN('ACTIVE','RETIRED') AND p.effective_from<=:at AND (p.effective_to IS NULL OR p.effective_to>:at)
                  AND (p.scope_kind='ORGANIZATION' OR (p.scope_kind='PLATFORM' AND p.platform_code=k.platform_code)
                    OR (p.scope_kind='STORE' AND p.store_ref_id=k.store_id))
                  AND NOT EXISTS(SELECT 1 FROM core.ad_outcome_policy other WHERE other.organization_id=p.organization_id AND other.direction=p.direction
                    AND other.id<>p.id AND other.status IN('ACTIVE','RETIRED') AND other.effective_from<=:at AND (other.effective_to IS NULL OR other.effective_to>:at)
                    AND (other.scope_kind='ORGANIZATION' OR (other.scope_kind='PLATFORM' AND other.platform_code=k.platform_code)
                      OR (other.scope_kind='STORE' AND other.store_ref_id=k.store_id))
                    AND CASE other.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END
                      <=CASE p.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END)
                LIMIT 2
                """).param("organization",organizationId).param("candidate",candidateId).param("at",Timestamp.from(at))
                .query((rs,index)->new Scope(organizationId,candidateId,rs.getObject("ad_native_object_id",UUID.class),
                        rs.getObject("case_id",UUID.class),rs.getObject("calculation_id",UUID.class),rs.getObject("affected_set_id",UUID.class),
                        rs.getString("affected_set_digest"),List.of((UUID[])rs.getArray("product_variant_ids").getArray()),
                        List.of((UUID[])rs.getArray("platform_listing_variant_ids").getArray()),rs.getString("policy_version_digest"),
                        rs.getString("platform_code"),rs.getObject("store_id",UUID.class),rs.getObject("semantic_profile_id",UUID.class),
                        new Policy(rs.getObject("id",UUID.class),rs.getInt("policy_version"),rs.getInt("completed_sales_guard_hours"),
                                Math.max(720,rs.getInt("settlement_window_hours")),rs.getInt("observation_starts_minutes"),
                                rs.getBigDecimal("material_profit_delta"),rs.getBigDecimal("material_profit_per_rub_delta"),
                                rs.getBigDecimal("sales_preservation_tolerance_ratio"),rs.getBigDecimal("minimum_settled_coverage_ratio"),
                                rs.getLong("minimum_traffic_count"),rs.getBoolean("critical_unit_definition_complete"),
                                rs.getBigDecimal("non_worsening_profit_band"),rs.getBigDecimal("non_worsening_per_rub_band"),rs.getBigDecimal("minimum_ad_spend_denominator"),
                                rs.getObject("comparison_scale",Integer.class),rs.getString("comparison_rounding_mode"),rs.getObject("material_boundary_inclusive",Boolean.class),rs.getString("negative_profit_terminal")),
                        rs.getTimestamp("expires_at")==null?null:rs.getTimestamp("expires_at").toInstant(),rs.getString("direction"))).list();
        if(scopes.size()!=1) { return null; }
        return freeze(scopes.getFirst(), at, null);
    }

    private UUID freeze(Scope scope, Instant at, UUID manualProposal) {
        if(!attestor.available()) return null;
        UUID organizationId=scope.organization(), candidateId=scope.candidate();
        if(scope.expiresAt()==null || !scope.expiresAt().isAfter(at)) { return null; }
        UUID reusable=jdbc.sql("""
                SELECT id FROM ops.ad_outcome_baseline WHERE organization_id=:organization
                    AND candidate_id IS NOT DISTINCT FROM :candidate AND manual_proposal_id IS NOT DISTINCT FROM :manual
                    AND case_calculation_id=:calculation AND policy_version_digest=:digest AND affected_set_id=:affected
                    AND outcome_policy_id=:policy AND valid_until>:at
                    AND ops.ad_outcome_baseline_is_attested(id) AND plan_snapshot=ops.ad_outcome_plan_snapshot(outcome_policy_id)
                    AND (state='INCOMPLETE' OR ops.ad_outcome_baseline_is_canonical(id,:at))
                ORDER BY prepared_at DESC LIMIT 1
                """).param("organization",organizationId).param("candidate",candidateId).param("manual",manualProposal)
                .param("calculation",scope.calculation()).param("digest",scope.policyDigest()).param("affected",scope.affectedSet())
                .param("policy",scope.policy().id()).param("at",Timestamp.from(at)).query(UUID.class).optional().orElse(null);
        if(reusable!=null) { return reusable; }
        boolean reviewed=manualProposal==null ? jdbc.sql("SELECT EXISTS(SELECT 1 FROM ops.ad_candidate_selection WHERE candidate_id=:id)")
                .param("id",candidateId).query(Boolean.class).single()
                : jdbc.sql("SELECT EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet WHERE proposal_id=:id)")
                    .param("id",manualProposal).query(Boolean.class).single();
        // A changed authority starts a new recommendation/proposal chain. It
        // cannot replace the baseline that a human has already reviewed.
        if(reviewed) { return null; }
        List<AdvertisingOutcomeEvidenceService.Unit> units=units(scope,at);
        List<String> blockers=new ArrayList<>();
        if(!scope.policy().criticalDefinitionComplete()) { blockers.add("CRITICAL_UNIT_DEFINITION_UNRESOLVED"); }
        if(!scope.policy().complete()) {
            blockers.add("OUTCOME_DUAL_AXIS_POLICY_UNRESOLVED");
        }
        if(units.isEmpty() || !units.stream().map(AdvertisingOutcomeEvidenceService.Unit::productVariantId).distinct().toList().containsAll(scope.products())) {
            blockers.add("COMPANY_AFFECTED_SCOPE_UNRESOLVED");
        }
        List<AdvertisingOutcomeEvidenceService.Snapshot> snapshots=new ArrayList<>();
        for(String stage:List.of("OPERATIONAL","RETAINED","SETTLED")) {
            int hours=stage.equals("OPERATIONAL")?scope.policy().operationalHours():stage.equals("RETAINED")?720:scope.policy().settledHours();
            String purpose=stage.equals("OPERATIONAL")?"EARLY_COMPLETED_SALES_OUTCOME":stage.equals("RETAINED")?"FINAL_RETAINED_SALES_OUTCOME":"SETTLED_FINANCIAL_OUTCOME";
            String kind=stage.equals("OPERATIONAL")?"COMPANY_COMPLETED_SALE":stage.equals("RETAINED")?"COMPANY_RETAINED_SALE":"SETTLEMENT";
            var freshness=policies.resolveFreshness(organizationId,kind,purpose,scope.platform(),scope.store(),scope.semanticProfile(),at);
            if(freshness.isEmpty()) { blockers.add("OUTCOME_FRESHNESS_PROFILE_UNRESOLVED:"+stage); }
            snapshots.add(evidence.snapshot(organizationId,scope.object(),scope.affectedSet(),stage,units,
                    at.minus(Duration.ofHours(hours)),at,at,freshness.orElse(null)));
        }
        for(var snapshot:snapshots) {
            if(snapshot.stage().equals("OPERATIONAL") && (!snapshot.companySales().sufficientForWrite()
                    || snapshot.units().stream().anyMatch(unit->unit.unit().ruleId()!=null && !unit.sales().sufficientForWrite()))) {
                blockers.add("OUTCOME_BASELINE_INSUFFICIENT");
                blockers.add("EARLY_COMPANY_OR_CRITICAL_BASELINE_UNRESOLVED");
            }
            if("OPTIMIZATION_INCREASE".equals(scope.direction()) && snapshot.stage().equals("RETAINED")
                    && (!snapshot.companySales().sufficientForWrite() || !snapshot.profit().absoluteProfit().sufficientForWrite()
                        || !snapshot.profit().profitPerAdRub().sufficientForWrite())) {
                if(!blockers.contains("OUTCOME_BASELINE_INSUFFICIENT")) blockers.add("OUTCOME_BASELINE_INSUFFICIENT");
                blockers.add("OPTIMIZATION_RETAINED_BASELINE_UNRESOLVED");
            }
        }
        Instant frozenExpiry=java.util.stream.Stream.concat(java.util.stream.Stream.of(scope.expiresAt()),
            snapshots.stream().map(AdvertisingOutcomeEvidenceService.Snapshot::freshnessProfile).filter(java.util.Objects::nonNull)
                .map(AdvertisingPolicyRepository.FreshnessProfile::effectiveTo).filter(java.util.Objects::nonNull)).min(Instant::compareTo).orElseThrow();
        if(!frozenExpiry.isAfter(at)) return null;
        String planJson=json.writeValueAsString(scope.policy());
        String digest=Digest.ofComponents(List.of(scope.calculation().toString(),scope.policyDigest(),scope.affectedDigest(),
                planJson,frozenExpiry.toString(),json.writeValueAsString(snapshots)));
        UUID existing=jdbc.sql("SELECT id FROM ops.ad_outcome_baseline WHERE candidate_id IS NOT DISTINCT FROM :candidate AND manual_proposal_id IS NOT DISTINCT FROM :manual AND input_digest=:digest AND ops.ad_outcome_baseline_is_attested(id)")
                .param("candidate",candidateId).param("manual",manualProposal).param("digest",digest).query(UUID.class).optional().orElse(null);
        if(existing!=null) { return existing; }
        UUID id=ids.newId();
        Map<String,Object> baseline=new java.util.LinkedHashMap<>();
        baseline.put("id",id);baseline.put("organization_id",organizationId);baseline.put("candidate_id",candidateId);
        baseline.put("manual_proposal_id",manualProposal);baseline.put("ad_native_object_id",scope.object());
        baseline.put("affected_set_id",scope.affectedSet());baseline.put("affected_set_digest",scope.affectedDigest());
        baseline.put("product_variant_ids",scope.products());
        baseline.put("listing_variant_ids",units.stream().map(AdvertisingOutcomeEvidenceService.Unit::listingVariantId).distinct().toList());
        baseline.put("outcome_policy_id",scope.policy().id());baseline.put("outcome_policy_version",scope.policy().version());
        baseline.put("case_calculation_id",scope.calculation());baseline.put("policy_version_digest",scope.policyDigest());
        baseline.put("prepared_at",at);baseline.put("valid_until",frozenExpiry);baseline.put("plan_snapshot",scope.policy());
        baseline.put("input_digest",digest);baseline.put("state",blockers.isEmpty()?"COMPLETE":"INCOMPLETE");baseline.put("blocker_codes",blockers);
        List<Map<String,Object>> stageRows=snapshots.stream().map(snapshot->Map.<String,Object>of("outcome_baseline_id",id,
            "stage",snapshot.stage(),"window_hours",Math.toIntExact(Duration.between(snapshot.from(),snapshot.to()).toHours()),"snapshot",snapshot)).toList();
        List<Map<String,Object>> criticalRows=units.stream().filter(unit->unit.ruleId()!=null).map(unit->Map.<String,Object>of(
            "outcome_baseline_id",id,"product_variant_id",unit.productVariantId(),"listing_variant_id",unit.listingVariantId(),"rule_id",unit.ruleId())).toList();
        String baselineJson=json.writeValueAsString(baseline),stagesJson=json.writeValueAsString(stageRows),unitsJson=json.writeValueAsString(criticalRows);
        record Invocation(String digest,int backend,long transaction) { }
        var invocation=jdbc.sql("SELECT ops.ad_outcome_payload_digest(CAST(:baseline AS jsonb),CAST(:stages AS jsonb),CAST(:units AS jsonb)) digest,pg_backend_pid() backend,txid_current() transaction")
            .param("baseline",baselineJson).param("stages",stagesJson).param("units",unitsJson)
            .query((rs,index)->new Invocation(rs.getString("digest"),rs.getInt("backend"),rs.getLong("transaction"))).single();
        String proof=attestor.attest(id,organizationId,invocation.digest(),invocation.backend(),invocation.transaction());
        jdbc.sql("SELECT ops.freeze_ad_outcome_baseline(CAST(:baseline AS jsonb),CAST(:stages AS jsonb),CAST(:units AS jsonb),:proof)")
            .param("baseline",baselineJson).param("stages",stagesJson).param("units",unitsJson).param("proof",proof).query(UUID.class).single();
        return id;
    }

    @Override @Transactional
    public UUID prepareManual(UUID organizationId, UUID proposalId, Instant at) {
        var scope=jdbc.sql("""
                SELECT proposal.case_id,proposal.ad_native_object_id,k.calculation_id,k.policy_version_digest,k.platform_code,k.store_id,k.semantic_profile_id,
                    a.id affected_set_id,a.affected_set_digest,a.product_variant_ids,a.platform_listing_variant_ids,p.*,
                    least(proposal.expires_at,manual.effective_to,p.effective_to) expires_at
                FROM ops.ad_manual_proposal proposal JOIN mart.ad_case k ON k.id=proposal.case_id
                JOIN core.ad_affected_set a ON a.id=proposal.affected_set_id
                JOIN core.ad_manual_policy manual ON manual.id=proposal.policy_id
                JOIN core.ad_outcome_policy p ON p.id=manual.outcome_policy_id AND p.organization_id=proposal.organization_id
                WHERE proposal.id=:proposal AND proposal.organization_id=:organization
                    AND ops.ad_manual_proposal_current(proposal.id) AND a.resolution_state='COMPLETE'
                    AND p.direction=proposal.intended_state->>'direction'
                    AND p.status IN('ACTIVE','RETIRED') AND p.effective_from<=:at AND (p.effective_to IS NULL OR p.effective_to>:at)
                    AND (p.scope_kind='ORGANIZATION' OR (p.scope_kind='PLATFORM' AND p.platform_code=k.platform_code)
                      OR (p.scope_kind='STORE' AND p.store_ref_id=k.store_id))
                    AND NOT EXISTS(SELECT 1 FROM core.ad_outcome_policy other WHERE other.organization_id=p.organization_id AND other.direction=p.direction
                      AND other.id<>p.id AND other.status IN('ACTIVE','RETIRED') AND other.effective_from<=:at AND (other.effective_to IS NULL OR other.effective_to>:at)
                      AND (other.scope_kind='ORGANIZATION' OR (other.scope_kind='PLATFORM' AND other.platform_code=k.platform_code)
                        OR (other.scope_kind='STORE' AND other.store_ref_id=k.store_id))
                      AND CASE other.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END
                        <=CASE p.scope_kind WHEN 'STORE' THEN 0 WHEN 'PLATFORM' THEN 1 ELSE 2 END)
                """).param("proposal",proposalId).param("organization",organizationId).param("at",Timestamp.from(at))
                .query((rs,index)->new Scope(organizationId,null,rs.getObject("ad_native_object_id",UUID.class),rs.getObject("case_id",UUID.class),
                        rs.getObject("calculation_id",UUID.class),rs.getObject("affected_set_id",UUID.class),rs.getString("affected_set_digest"),
                        List.of((UUID[])rs.getArray("product_variant_ids").getArray()),List.of((UUID[])rs.getArray("platform_listing_variant_ids").getArray()),
                        rs.getString("policy_version_digest"),rs.getString("platform_code"),rs.getObject("store_id",UUID.class),rs.getObject("semantic_profile_id",UUID.class),
                        new Policy(rs.getObject("id",UUID.class),rs.getInt("policy_version"),rs.getInt("completed_sales_guard_hours"),Math.max(720,rs.getInt("settlement_window_hours")),
                            rs.getInt("observation_starts_minutes"),rs.getBigDecimal("material_profit_delta"),rs.getBigDecimal("material_profit_per_rub_delta"),
                            rs.getBigDecimal("sales_preservation_tolerance_ratio"),rs.getBigDecimal("minimum_settled_coverage_ratio"),rs.getLong("minimum_traffic_count"),
                            rs.getBoolean("critical_unit_definition_complete"),rs.getBigDecimal("non_worsening_profit_band"),rs.getBigDecimal("non_worsening_per_rub_band"),
                            rs.getBigDecimal("minimum_ad_spend_denominator"),rs.getObject("comparison_scale",Integer.class),rs.getString("comparison_rounding_mode"),
                            rs.getObject("material_boundary_inclusive",Boolean.class),rs.getString("negative_profit_terminal")),rs.getTimestamp("expires_at").toInstant(),rs.getString("direction"))).optional();
        return scope.map(value->freeze(value,at,proposalId)).orElse(null);
    }

    @Override @Transactional
    public UUID observeManual(UUID organizationId, UUID packetId, Instant at) {
        return observations.evaluateManual(organizationId,packetId,at);
    }

    private List<AdvertisingOutcomeEvidenceService.Unit> units(Scope scope,Instant at) {
        return jdbc.sql("""
                SELECT m.product_variant_id,m.platform_listing_variant_id,listing.store_id,
                    (SELECT rule.id FROM core.ad_outcome_critical_unit_rule rule WHERE rule.outcome_policy_id=:policy
                        AND rule.organization_id=:organization AND rule.product_variant_id=m.product_variant_id
                        AND (rule.store_id IS NULL OR rule.store_id=listing.store_id)
                        ORDER BY rule.store_id NULLS LAST LIMIT 1) AS rule_id
                FROM core.listing_mapping m JOIN core.platform_listing_variant variant ON variant.id=m.platform_listing_variant_id
                JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id
                WHERE m.organization_id=:organization AND m.product_variant_id=ANY(:products)
                    AND m.status IN ('ACTIVE','ENDED') AND m.effective_from<=:at AND (m.effective_to IS NULL OR m.effective_to>:at)
                ORDER BY m.product_variant_id,m.platform_listing_variant_id
                """).param("policy",scope.policy().id()).param("organization",scope.organization()).param("products",scope.products().toArray(new UUID[0]))
                .param("at",Timestamp.from(at)).query((rs,index)->new AdvertisingOutcomeEvidenceService.Unit(rs.getObject("product_variant_id",UUID.class),
                        rs.getObject("platform_listing_variant_id",UUID.class),rs.getObject("store_id",UUID.class),rs.getObject("rule_id",UUID.class))).list();
    }
}
