package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Actual current Metric/calculation/responsibility output with unchanged business facts.
 * One prior qualified window per qualification policy is explicitly synthetic historical
 * INPUT. These tests do not prove that historical window's derivation. The original
 * over-ceiling Task test is distinct from the new actual bounded Increase/Planner path.
 */
@SpringBootTest @ActiveProfiles("ci") @Import(AdvertisingVerticalPathIT.Runtime.class)
class AdvertisingOptimizationOutcomePolicyIT {
    @Autowired ApplicationContext context;
    AdvertisingVerticalPathIT f;
    UUID optimizationPolicy;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) { AdvertisingVerticalPathIT.properties(p); }

    @BeforeEach void qualifiedCurrentFactsWithExplicitPriorWindowInput() throws Exception {
        f=new AdvertisingVerticalPathIT();context.getAutowireCapableBeanFactory().autowireBean(f);
        f.topologyAndAuthorityOnly();
        // Preserve all existing sample, spend, confidence, freshness and materiality bounds.
        // Historical projection INPUT names policies already effective in that interval.
        f.sql("UPDATE core.ad_optimization_qualification_policy SET effective_from=CAST(:at AS timestamptz)-interval '70 days' WHERE organization_id=:org").update();
        for(String purpose:List.of("OPTIMIZATION_RECOMMENDATION","OPTIMIZATION_BID_WRITE"))f.sql("""
            INSERT INTO core.ad_freshness_profile
            SELECT (jsonb_populate_record(NULL::core.ad_freshness_profile,to_jsonb(p)||jsonb_build_object(
              'id',gen_random_uuid(),'decision_purpose',CAST(:purpose AS text)))).*
            FROM core.ad_freshness_profile p WHERE p.organization_id=:org AND p.decision_purpose='TASK_ACTIVATION'
            """).param("purpose",purpose).update();
        for(String lane:List.of("OPTIMIZATION","DATA_REPAIR"))f.sql("""
            INSERT INTO core.ad_human_slo_profile
            SELECT (jsonb_populate_record(NULL::core.ad_human_slo_profile,to_jsonb(p)||jsonb_build_object(
              'id',gen_random_uuid(),'policy_version',CAST(:version AS integer),'lane',CAST(:lane AS text)))).*
            FROM core.ad_human_slo_profile p WHERE p.id=:original
            """).param("version",lane.equals("OPTIMIZATION")?2:3).param("lane",lane).param("original",f.graph.id("humanSlo")).update();
        optimizationPolicy=appendOptimizationPolicy(2);
        var occurred=f.start.minusSeconds(7200);var from=f.start.minus(Duration.ofDays(30));
        for(String stage:List.of("COMPLETED","RETAINED","SETTLED"))f.company(stage,"50000",50,occurred,"qualified-current",null);
        UUID retained=f.linked("50000",50,from,f.start,occurred);
        // A separate, explicitly typed completed-stage authority supplies the completed count.
        // The existing Bundle continues to choose the retained-stage definition for economics.
        UUID completedDefinition=UUID.randomUUID();
        f.sql("""
            INSERT INTO core.ad_conversion_definition
            SELECT (jsonb_populate_record(NULL::core.ad_conversion_definition,to_jsonb(d)||jsonb_build_object(
              'id',CAST(:id AS uuid),'definition_version',2,'sale_stage','CANONICAL_AD_LINKED_COMPLETED_SALE'))).*
            FROM core.ad_conversion_definition d WHERE d.id=:conversion
            """).param("id",completedDefinition).update();
        f.sql("""
            INSERT INTO ledger.ad_linked_sale_event
            SELECT (jsonb_populate_record(NULL::ledger.ad_linked_sale_event,to_jsonb(e)||jsonb_build_object(
              'id',gen_random_uuid(),'conversion_definition_id',CAST(:definition AS uuid),
              'sale_stage','CANONICAL_AD_LINKED_COMPLETED_SALE'))).* FROM ledger.ad_linked_sale_event e WHERE e.id=:event
            """).param("definition",completedDefinition).param("event",retained).update();
        f.report(from,f.start,"6000",500);
        f.report(f.start.minus(Duration.ofDays(60)),from,"6000",500);
        f.coverage(f.start.minus(Duration.ofDays(61)),f.start.plusSeconds(300),true);
        f.context(f.start.minusSeconds(1));f.economicsAuthority();f.economicFacts(occurred);
        assertThat(f.analytics.run(f.graph.id("store"),MetricWindow.D30,"SCHEDULED",null).subjectCount()).isEqualTo(1);
        f.sql("""
            INSERT INTO mart.ad_qualification_period(organization_id,ad_native_object_id,qualification_policy_id,
              period_start,period_end,qualified,evaluated_at)
            SELECT :org,:object,p.id,CAST(:at AS timestamptz)-interval '60 days',
              CAST(:at AS timestamptz)-interval '30 days',true,CAST(:at AS timestamptz)-interval '30 days'
            FROM core.ad_optimization_qualification_policy p WHERE p.organization_id=:org
              AND p.purpose_tier IN('OPTIMIZATION_TASK','OPTIMIZATION_BID_WRITE')
            """).update();
        assertThat(f.count("mart.ad_case")).isZero();assertThat(f.count("ops.ad_case_responsibility")).isZero();
    }

    @AfterEach void disabledProductionAndNoProvider() {
        SecurityContextHolder.clearContext();
        if(f!=null) { assertThat(f.productionWrites.getEnabled()).isFalse();assertThat(f.provider.calls).isEmpty(); }
    }

    @ParameterizedTest @ValueSource(strings={"OUTCOME_POLICY_UNRESOLVED","OUTCOME_POLICY_CONFLICTED"})
    void otherwiseQualifiedOptimizationTaskBecomesExplicitPolicyRepairWithTheSameBusinessEvidence(String reason) {
        String unchangedBusiness=businessEvidence();
        var before=refresh("qualified-optimization-policy-positive");
        assertThat(before.calculation().cases()).hasSize(1);
        var opportunity=before.calculation().cases().getFirst();
        assertThat(opportunity.decision().lane().name()).isEqualTo("OPTIMIZATION");
        assertThat(opportunity.decision().cause().name()).isEqualTo("RECOVERABLE_ADVERTISING_PROFIT");
        assertThat(opportunity.decision().blockerCodes()).isEmpty();
        assertThat(opportunity.contributionProfit().value()).isEqualByComparingTo("19000");
        assertThat(opportunity.maxCpc().ceiling().amount()).isEqualByComparingTo("25");
        assertThat(opportunity.recoverableProfit().value()).isEqualByComparingTo("1000");
        assertThat(before.calculation().qualificationPeriods()).hasSize(2).allSatisfy(period->assertThat(period.qualified()).isTrue());
        assertThat(before.calculation().writeQualificationSatisfied()).isTrue();
        UUID originalCase=before.written().cases().getFirst().caseId();
        UUID originalTask=taskFor(originalCase);
        assertThat(originalTask).isNotNull();
        assertThat(f.sql("SELECT coverage_state FROM ops.ad_case_responsibility WHERE case_id=:case").param("case",originalCase).query(String.class).single()).isEqualTo("IN_COVERAGE");
        // No positive bid-increase claim: bid30 > ceiling25, and the preserved Bundle is Protection.
        assertThat(before.proposed()).isEmpty();assertThat(f.count("ops.ad_bid_candidate")).isZero();
        if(reason.equals("OUTCOME_POLICY_UNRESOLVED"))
            f.sql("UPDATE core.ad_outcome_policy SET effective_to=:at WHERE id=:id").param("id",optimizationPolicy).update();
        else appendOptimizationPolicy(3);
        var after=refresh("qualified-optimization-policy-refusal");
        assertThat(after.calculation().cases()).hasSize(1);
        var repair=after.calculation().cases().getFirst();
        assertThat(repair.decision().lane().name()).isEqualTo("DATA_REPAIR");
        assertThat(repair.decision().cause().name()).isEqualTo("DECISION_POLICY_UNRESOLVED");
        assertThat(repair.decision().blockerCodes()).containsExactly(reason);
        assertThat(repair.contributionProfit()).isEqualTo(opportunity.contributionProfit());
        assertThat(repair.maxCpc()).isEqualTo(opportunity.maxCpc());
        assertThat(repair.recoverableProfit()).isEqualTo(opportunity.recoverableProfit());
        assertThat(after.calculation().qualificationPeriods()).isEqualTo(before.calculation().qualificationPeriods());
        assertThat(after.calculation().writeQualificationSatisfied()).isFalse();
        UUID repairCase=after.written().cases().getFirst().caseId();
        UUID repairTask=taskFor(repairCase);
        assertThat(repairTask).isNotNull().isNotEqualTo(originalTask);
        assertThat(f.sql("SELECT count(*) FROM mart.ad_case WHERE organization_id=:org AND superseded_at IS NULL AND lane='OPTIMIZATION'").query(Integer.class).single()).isZero();
        assertThat(f.sql("SELECT superseded_at IS NOT NULL FROM mart.ad_case WHERE id=:case").param("case",originalCase).query(Boolean.class).single()).isTrue();
        assertThat(taskFor(originalCase)).as("Historical task identity remains auditable; this is not automatic task completion").isEqualTo(originalTask);
        refresh("qualified-optimization-policy-refusal-replay");assertThat(taskFor(repairCase)).isEqualTo(repairTask);
        assertThat(after.proposed()).isEmpty();assertThat(f.count("ops.ad_bid_candidate")).isZero();
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();assertThat(f.count("ops.ad_action_reservation")).isZero();assertThat(f.count("ops.ad_bid_command")).isZero();
        assertThat(businessEvidence()).isEqualTo(unchangedBusiness);
    }

    @org.junit.jupiter.api.Test
    void observedEconomicHeadroomWithPositiveMaterialityReachesActualIncreaseCandidateAndPlannerSelection() {
        UUID bundle=underCeilingOptimizationAuthorityInput();
        var generated=qualifiedIncrease("observed-headroom-positive");
        UUID recommendation=generated.proposed().getFirst();
        UUID candidate=candidateFor(recommendation);
        UUID caseId=generated.written().cases().getFirst().caseId();
        assertThat(taskFor(caseId)).isNotNull();
        assertThat(f.count("ops.ad_candidate_selection")).isZero();
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();
        var selected=f.humans.select(f.maker,caseId,candidate,0,"Choose actual observed-cohort economic space within the explicit bounded target");
        assertThat(selected.state().name()).isEqualTo("VALIDATED");
        assertThat(f.count("ops.ad_candidate_selection")).isEqualTo(1);
        assertThat(f.sql("SELECT bundle_id FROM ops.ad_candidate_selection WHERE candidate_id=:candidate")
                .param("candidate",candidate).query(UUID.class).single()).isEqualTo(bundle);
        assertThat(f.count("ops.ad_outcome_baseline")).isEqualTo(1);
        assertThat(f.sql("SELECT state FROM ops.ad_outcome_baseline WHERE candidate_id=:candidate")
                .param("candidate",candidate).query(String.class).single()).isEqualTo("COMPLETE");
        assertThat(f.sql("SELECT ops.ad_outcome_baseline_is_attested(id) AND ops.ad_outcome_baseline_is_canonical(id,:at) "
                +"FROM ops.ad_outcome_baseline WHERE candidate_id=:candidate")
                .param("candidate",candidate).query(Boolean.class).single()).isTrue();
        assertThat(f.sql("SELECT outcome_policy_id FROM ops.ad_outcome_baseline WHERE candidate_id=:candidate")
                .param("candidate",candidate).query(UUID.class).single()).isEqualTo(optimizationPolicy);
        assertThat(f.count("ops.ad_action_reservation")).isZero();
        assertThat(f.count("ops.ad_bid_command")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"OUTCOME_POLICY_UNRESOLVED","OUTCOME_POLICY_CONFLICTED"})
    void actualIncreaseCandidateCannotBeSelectedWhenOnlyItsOutcomePolicyBecomesMissingOrConflicted(String reason) {
        underCeilingOptimizationAuthorityInput();
        String sameBusiness=businessEvidence();
        var before=qualifiedIncrease("observed-headroom-before-policy-defect");
        UUID recommendation=before.proposed().getFirst();
        UUID candidate=candidateFor(recommendation);
        UUID caseId=before.written().cases().getFirst().caseId();
        UUID originalTask=taskFor(caseId);
        if(reason.equals("OUTCOME_POLICY_UNRESOLVED"))
            f.sql("UPDATE core.ad_outcome_policy SET effective_to=:at WHERE id=:id").param("id",optimizationPolicy).update();
        else appendOptimizationPolicy(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->f.humans.select(f.maker,caseId,candidate,0,"Policy defect cannot borrow the earlier qualified facts"))
                .isInstanceOfSatisfying(com.mimococo.marketops.shared.OperationRejectedException.class,
                        failure->assertThat(failure.errorCode().name()).isEqualTo(reason));
        assertThat(f.count("ops.ad_candidate_selection")).isZero();
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();
        var after=refresh("observed-headroom-policy-repair");
        var repair=after.calculation().cases().getFirst();
        assertThat(repair.decision().lane().name()).isEqualTo("DATA_REPAIR");
        assertThat(repair.decision().cause().name()).isEqualTo("DECISION_POLICY_UNRESOLVED");
        assertThat(repair.decision().blockerCodes()).containsExactly(reason);
        assertThat(repair.recoverableProfit()).isEqualTo(before.calculation().cases().getFirst().recoverableProfit());
        assertThat(repair.contributionProfit()).isEqualTo(before.calculation().cases().getFirst().contributionProfit());
        assertThat(repair.maxCpc()).isEqualTo(before.calculation().cases().getFirst().maxCpc());
        assertThat(after.calculation().qualificationPeriods()).isEqualTo(before.calculation().qualificationPeriods());
        assertThat(after.calculation().writeQualificationSatisfied()).isFalse();
        assertThat(after.proposed()).isEmpty();
        assertThat(f.count("ops.ad_bid_candidate")).as("The one historical computed candidate remains; no policy-blocked replacement is emitted").isEqualTo(1);
        assertThat(taskFor(caseId)).isEqualTo(originalTask);
        assertThat(taskFor(after.written().cases().getFirst().caseId())).isNotNull().isNotEqualTo(originalTask);
        assertThat(f.sql("SELECT superseded_at IS NOT NULL FROM mart.ad_case WHERE id=:case")
                .param("case",caseId).query(Boolean.class).single()).isTrue();
        assertThat(businessEvidence()).isEqualTo(sameBusiness);
        assertThat(f.count("ops.ad_candidate_selection")).isZero();assertThat(f.count("ops.ad_outcome_baseline")).isZero();
        assertThat(f.count("ops.ad_action_reservation")).isZero();assertThat(f.count("ops.ad_bid_command")).isZero();
    }

    private AdvertisingCaseRefreshService.RefreshOutcome qualifiedIncrease(String correlation) {
        var result=refresh(correlation);
        assertThat(result.calculation().cases()).hasSize(1);
        var opportunity=result.calculation().cases().getFirst();
        assertThat(opportunity.decision().lane().name()).isEqualTo("OPTIMIZATION");
        assertThat(opportunity.decision().cause().name()).isEqualTo("RECOVERABLE_ADVERTISING_PROFIT");
        assertThat(opportunity.decision().blockerCodes()).isEmpty();
        assertThat(opportunity.currentBid().value()).isEqualByComparingTo("20");
        assertThat(opportunity.eligibleTraffic().value()).isEqualByComparingTo("500");
        assertThat(opportunity.contributionProfit().value()).isEqualByComparingTo("19000");
        assertThat(opportunity.maxCpc().ceiling().amount()).isEqualByComparingTo("25");
        assertThat(opportunity.recoverableProfit().value()).isEqualByComparingTo("2500");
        assertThat(opportunity.recoverableProfit().sufficientForWrite()).isTrue();
        assertThat(result.calculation().qualificationPeriods()).hasSize(2).allSatisfy(period->assertThat(period.qualified()).isTrue());
        assertThat(result.calculation().writeQualificationSatisfied()).isTrue();
        assertThat(result.proposed()).as("Real calculator and proposal output, without a seeded ScoredCase/candidate").hasSize(1);
        UUID candidate=candidateFor(result.proposed().getFirst());
        assertThat(f.sql("SELECT direction FROM ops.ad_bid_candidate WHERE id=:candidate")
                .param("candidate",candidate).query(String.class).single()).isEqualTo("OPTIMIZATION_INCREASE");
        // The endpoint ceiling is 25 * 0.99 = 24.75. The finite candidate set
        // starts from its normalized endpoint, so its persisted request is 24.5.
        assertThat(f.sql("SELECT requested_amount FROM ops.ad_bid_candidate WHERE id=:candidate")
                .param("candidate",candidate).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("24.5");
        assertThat(f.sql("SELECT provider_normalized_amount FROM ops.ad_bid_candidate WHERE id=:candidate")
                .param("candidate",candidate).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("24.5");
        assertThat(f.sql("SELECT minimum_recoverable_amount FROM core.ad_optimization_qualification_policy "
                +"WHERE organization_id=:org AND purpose_tier IN('OPTIMIZATION_TASK','OPTIMIZATION_BID_WRITE')")
                .query(java.math.BigDecimal.class).list()).hasSize(2).allSatisfy(amount->assertThat(amount).isEqualByComparingTo("1000"));
        return result;
    }
    private UUID candidateFor(UUID recommendation) {
        return UUID.fromString(f.recommendations.require(recommendation).proposedParameters().get("candidateId"));
    }

    /**
     * Synthetic Owner policy/topology INPUT only. All business quantities and positive
     * qualification thresholds remain the before-each inputs. The application derives
     * current Case, Task, candidate, selection and frozen baseline; there is no approval,
     * command or Provider transmission. Prior sustained qualification remains INPUT.
     */
    private UUID underCeilingOptimizationAuthorityInput() {
        UUID target=UUID.randomUUID(),lease=UUID.randomUUID(),bundle=UUID.randomUUID(),gate=UUID.randomUUID();
        f.sql("""
            INSERT INTO core.ad_object_configuration_observation
            SELECT (jsonb_populate_record(NULL::core.ad_object_configuration_observation,to_jsonb(c)||jsonb_build_object(
              'id',gen_random_uuid(),'observed_bid_amount',20,'observed_at',CAST(:at AS timestamptz),
              'source_time',CAST(:at AS timestamptz),'created_at',CAST(:at AS timestamptz),'supersedes_observation_id',c.id))).*
            FROM core.ad_object_configuration_observation c WHERE c.organization_id=:org AND c.ad_native_object_id=:object
              AND NOT EXISTS(SELECT 1 FROM core.ad_object_configuration_observation n WHERE n.supersedes_observation_id=c.id)
            """).update();
        f.sql("""
            INSERT INTO core.ad_bid_target_policy
            SELECT (jsonb_populate_record(NULL::core.ad_bid_target_policy,to_jsonb(p)||jsonb_build_object(
              'id',CAST(:newId AS uuid),'policy_version',2,'direction','OPTIMIZATION_INCREASE',
              'owner_user_id',CAST(:owner AS uuid),'reason','Synthetic bounded Optimization target; no predicted traffic',
              'evidence_reference','fixture://optimization/target'))).* FROM core.ad_bid_target_policy p WHERE p.id=:oldId
            """).param("newId",target).param("oldId",f.graph.id("targetPolicy")).update();
        f.sql("""
            INSERT INTO core.ad_approval_lease_policy
            SELECT (jsonb_populate_record(NULL::core.ad_approval_lease_policy,to_jsonb(p)||jsonb_build_object(
              'id',CAST(:newId AS uuid),'policy_version',2,'direction','OPTIMIZATION_INCREASE',
              'owner_user_id',CAST(:owner AS uuid),'reason','Synthetic exact Optimization lease'))).*
            FROM core.ad_approval_lease_policy p WHERE p.id=:oldId
            """).param("newId",lease).param("oldId",f.graph.id("approvalLease")).update();
        assertThat(f.sql("""
            SELECT candidate_count=1 AND max_relative_change_ratio=0.5 AND max_absolute_change_amount=50
              AND ceiling_headroom_ratio=0.01 FROM core.ad_bid_target_policy WHERE id=:id
            """).param("id",target).query(Boolean.class).single()).isTrue();
        assertThat(f.sql("""
            SELECT (to_jsonb(n)-ARRAY['id','policy_version','direction','owner_user_id','reason','evidence_reference'])
                 = (to_jsonb(o)-ARRAY['id','policy_version','direction','owner_user_id','reason','evidence_reference'])
            FROM core.ad_bid_target_policy n CROSS JOIN core.ad_bid_target_policy o WHERE n.id=:id AND o.id=:original
            """).param("id",target).param("original",f.graph.id("targetPolicy")).query(Boolean.class).single()).isTrue();
        UUID human=f.sql("SELECT id FROM core.ad_human_slo_profile WHERE organization_id=:org AND lane='OPTIMIZATION'").query(UUID.class).single();
        f.sql("""
            INSERT INTO ops.ad_decision_policy_bundle
            SELECT (jsonb_populate_record(NULL::ops.ad_decision_policy_bundle,to_jsonb(b)||jsonb_build_object(
              'id',CAST(:newId AS uuid),'bundle_version',2,'direction','OPTIMIZATION_INCREASE','target_policy_id',CAST(:target AS uuid),
              'outcome_policy_id',CAST(:policy AS uuid),'approval_lease_policy_id',CAST(:lease AS uuid),'human_slo_profile_id',CAST(:human AS uuid),
              'gate_scope_reference',CAST(:gate AS text),'gate_authority_id',NULL,'status','DRAFT',
              'activated_by_user_id',NULL,'endorsed_by_user_id',NULL,'approved_by_user_id',NULL,
              'reason','Synthetic explicitly bound Owner Optimization Bundle input',
              'evidence_reference','fixture://optimization/bundle'))).* FROM ops.ad_decision_policy_bundle b WHERE b.id=:original
            """).param("newId",bundle).param("target",target).param("policy",optimizationPolicy).param("lease",lease)
                .param("human",human).param("gate",gate).param("original",f.graph.id("bundle")).update();
        f.sql("""
            INSERT INTO ops.ad_gate_authority
            SELECT (jsonb_populate_record(NULL::ops.ad_gate_authority,to_jsonb(g)||jsonb_build_object(
              'id',CAST(:newId AS uuid),'direction','OPTIMIZATION_INCREASE','bundle_id',CAST(:bundle AS uuid),
              'production_write_enabled',false,'exact_object_values',jsonb_build_object(CAST(:object AS text),
                jsonb_build_object('currentBid',20,'targetBid',24.5,'currencyCode','RUB','bidUnitCode','CURRENCY_MAJOR')),
              'evidence_reference','fixture://optimization/synthetic-owner-gate'))).* FROM ops.ad_gate_authority g WHERE g.id=:original
            """).param("newId",gate).param("bundle",bundle).param("original",f.graph.id("gate")).update();
        f.sql("""
            UPDATE ops.ad_decision_policy_bundle SET gate_authority_id=:gate,activated_by_user_id=:maker,
              endorsed_by_user_id=:reviewer,approved_by_user_id=:owner,status='ACTIVE' WHERE id=:bundle
            """).param("gate",gate).param("maker",f.graph.id("executorUser")).param("reviewer",f.graph.id("verifierUser"))
                .param("bundle",bundle).update();
        f.sql("""
            INSERT INTO core.ad_outcome_critical_unit_rule
            SELECT (jsonb_populate_record(NULL::core.ad_outcome_critical_unit_rule,to_jsonb(r)||jsonb_build_object(
              'id',gen_random_uuid(),'outcome_policy_id',CAST(:policy AS uuid)))).*
            FROM core.ad_outcome_critical_unit_rule r WHERE r.organization_id=:org AND r.outcome_policy_id=:outcome
            """).param("policy",optimizationPolicy).update();
        // Unchanged mapping/context throughout pre-action windows are explicit INPUT.
        var history=f.start.minus(Duration.ofDays(70));
        f.sql("UPDATE core.listing_mapping SET effective_from=:history WHERE organization_id=:org")
                .param("history",java.sql.Timestamp.from(history)).update();
        f.context(history);
        assertThat(f.count("mart.ad_case")).isZero();assertThat(f.count("ops.ad_bid_candidate")).isZero();
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();
        assertThat(f.sql("SELECT bool_and(NOT production_write_enabled) FROM ops.ad_gate_authority WHERE organization_id=:org")
                .query(Boolean.class).single()).isTrue();
        return bundle;
    }

    private AdvertisingCaseRefreshService.RefreshOutcome refresh(String correlation) {
        return f.refresh.refresh(f.graph.id("organization"),f.graph.id("object"),f.start,"TARGETED",null,correlation).orElseThrow();
    }
    private UUID taskFor(UUID caseId) {
        return f.sql("SELECT r.task_id FROM ops.ad_case_responsibility r JOIN ops.work_task t ON t.id=r.task_id WHERE r.case_id=:case")
                .param("case",caseId).query(UUID.class).single();
    }
    private UUID appendOptimizationPolicy(int version) {
        UUID id=UUID.randomUUID();
        f.sql("""
            INSERT INTO core.ad_outcome_policy
            SELECT (jsonb_populate_record(NULL::core.ad_outcome_policy,to_jsonb(p)||jsonb_build_object(
              'id',CAST(:id AS uuid),'policy_version',CAST(:version AS integer),'direction','OPTIMIZATION_INCREASE',
              'cause_code','RECOVERABLE_ADVERTISING_PROFIT','effective_to',NULL,
              'reason','Synthetic explicit Optimization Outcome authority'))).* FROM core.ad_outcome_policy p WHERE p.id=:outcome
            """).param("id",id).param("version",version).update();return id;
    }
    private String businessEvidence() {
        return f.sql("""
            SELECT jsonb_build_object(
              'reports',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM ledger.ad_object_fact x WHERE organization_id=:org),
              'linked',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM ledger.ad_linked_sale_event x WHERE organization_id=:org),
              'company',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM ledger.sales_fact x WHERE organization_id=:org),
              'metrics',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM mart.metric_value x WHERE organization_id=:org),
              'configuration',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM core.ad_object_configuration_observation x WHERE organization_id=:org),
              'affectedSet',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM core.ad_affected_set x WHERE organization_id=:org),
              'qualificationPolicies',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM core.ad_optimization_qualification_policy x WHERE organization_id=:org),
              'historicalQualification',(SELECT jsonb_agg(to_jsonb(x) ORDER BY qualification_policy_id) FROM mart.ad_qualification_period x
                WHERE organization_id=:org AND period_end=CAST(:at AS timestamptz)-interval '30 days'))::text
            """).query(String.class).single();
    }
}
