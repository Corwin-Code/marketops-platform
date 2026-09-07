package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.mimococo.marketops.advertisingefficiency.internal.domain.AdActionDependencyPolicy;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteRequest;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** Actual facts -> calculator -> candidate -> Planner -> three humans -> SQL -> fixture port. */
@SpringBootTest @ActiveProfiles("ci") @Import(AdvertisingVerticalPathIT.Runtime.class)
class AdvertisingEconomicCauseBoundIT {
    static final String BASIS="CAUSE_BOUND_PROTECTION_STEP";
    static final String CAUSE="PROVEN_ADVERTISING_LOSS";
    @Autowired ApplicationContext context;
    AdvertisingVerticalPathIT f;
    UUID candidate;
    long spendSourceAgeSeconds;
    boolean splitOfficialReports;
    long firstAcceptedAgeSeconds;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        AdvertisingVerticalPathIT.properties(properties);
    }

    @BeforeEach void seedOnlySyntheticTopologyAndAuthority() throws Exception {
        spendSourceAgeSeconds=0;
        splitOfficialReports=false;firstAcceptedAgeSeconds=0;
        f=new AdvertisingVerticalPathIT() {
            @Override String authorityOnly(String source) {
                String policy="'RUB', 0.01, false, NULL,\n        CAST('{}' AS text[])";
                assertThat(source).contains(policy);
                return super.authorityOnly(source.replace("MAX_CPC_BOUNDED",BASIS)
                        .replace(policy,"'RUB', NULL, true, 0.2,\n        ARRAY['PROVEN_ADVERTISING_LOSS']::text[]"));
            }
            @Override void report(Instant from,Instant to,String amount,int clicks) {
                // Missing traffic affects conversion; accepted exact Spend remains independently observable.
                if(splitOfficialReports) {
                    Instant middle=from.plus(Duration.between(from,to).dividedBy(2));
                    segment(from,middle,new BigDecimal(amount).divide(new BigDecimal("2")),
                            clock.instant().minusSeconds(spendSourceAgeSeconds),clock.instant().minusSeconds(firstAcceptedAgeSeconds));
                    segment(middle,to,new BigDecimal(amount).divide(new BigDecimal("2")),clock.instant(),clock.instant());
                } else segment(from,to,new BigDecimal(amount),clock.instant().minusSeconds(spendSourceAgeSeconds),clock.instant());
            }
            void segment(Instant from,Instant to,BigDecimal amount,Instant sourceTime,Instant recordedAt) {
                sql("""
                    INSERT INTO ledger.ad_object_fact(id,organization_id,provenance_id,ad_native_object_id,store_id,
                      source_fact_key,period_start,period_end,currency_code,spend_amount,clicks,
                      report_window_complete,correction_window_open,source_time,recorded_at)
                    VALUES(gen_random_uuid(),:org,:source,:object,:store,:key,:from,:to,'RUB',:money,NULL,
                      true,false,:sourceTime,:recordedAt)
                    """).param("key",UUID.randomUUID().toString()).param("from",Timestamp.from(from))
                        .param("to",Timestamp.from(to)).param("money",amount)
                        .param("sourceTime",Timestamp.from(sourceTime)).param("recordedAt",Timestamp.from(recordedAt)).update();
            }
            @Override UUID company(String stage,String amount,int quantity,Instant occurred,String order,UUID supersedes) {
                if("COMPLETED".equals(stage) && "before".equals(order) && supersedes==null) {
                    UUID last=null;
                    // The same exact company total across real daily observations supports
                    // inherited demand qualification without weakening its sample/outlier bounds.
                    for(int day=0;day<10;day++) last=super.company(stage,"1000",1,
                            occurred.minus(Duration.ofDays(day)),"completed-before-"+day,null);
                    return last;
                }
                return super.company(stage,amount,quantity,occurred,order,supersedes);
            }
        };
        context.getAutowireCapableBeanFactory().autowireBean(f);
        f.topologyAndAuthorityOnly();
        for(String kind:List.of("SELLABILITY","AVAILABILITY")) f.sql("""
            INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,scope_kind,
              source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
              requires_window_complete,requires_correction_window_closed,minimum_coverage_ratio,minimum_confidence_state,provider_incident_blocks,
              owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
            VALUES(gen_random_uuid(),:org,1,:kind,'PROTECTION_BID_WRITE','ORGANIZATION',60,60,0,0,true,true,1,
              'CANONICAL_CONFIRMED',true,:owner,'Synthetic exact current physical safety','fixture://cv-b/safety',
              CAST(:at AS timestamptz)-interval '1 day',CAST(:at AS timestamptz)+interval '1 day','ACTIVE',:at)
            """).param("kind",kind).update();
        inheritedAvailabilityAuthorities();
    }
    @AfterEach void clearIdentityAndConfirmNoProductionEnablement() {
        SecurityContextHolder.clearContext();
        if(f!=null && f.productionWrites!=null) assertThat(f.productionWrites.getEnabled()).isFalse();
    }

    @Test void completeNegativeEconomicsWithUnavailableConversionTraversesTheActualGovernedPath() {
        calculateCandidate();
        var selected=select();
        assertThat(selected.state()).isEqualTo(RecommendationState.VALIDATED);
        assertThat(f.count("ops.ad_outcome_baseline")).isEqualTo(1);
        assertThat(f.sql("SELECT state FROM ops.ad_outcome_baseline WHERE organization_id=:org").query(String.class).single()).isEqualTo("COMPLETE");
        var endorsed=f.humans.endorse(f.ops,f.recommendation,selected.version(),"Review complete economic danger");
        f.humans.preparePreview(f.owner,f.recommendation);
        var preview=f.guardrails.previewAdBidChange(f.recommendations.require(f.recommendation),GuardrailPurpose.IMPACT_PREVIEW);
        assertThat(preview.verdict().passed()).as(preview.toString()).isTrue();
        assertThat(preview.projection().candidateBasis()).isEqualTo(BASIS);
        assertThat(preview.projection().maxCpcAmount()).isNull();
        assertThat(preview.evidence().path("recoveryState").asText()).isEqualTo("EXPOSURE_LIMIT_ONLY_NOT_PROFITABILITY_OR_HEALTH");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(f.owner,null,List.of()));
        assertThat(f.approvals.approve(f.owner,f.recommendation,"Approve the exact synthetic bounded decrease",endorsed.version()).state())
                .isEqualTo(RecommendationState.APPROVED);
        f.command=f.execution.createCommand(f.owner,f.recommendation,f.recommendations.require(f.recommendation).version()).commandId();
        assertThat(f.jdbc.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))").param("id",f.command).query(String.class).list()).isEmpty();
        assertThat(f.sql("SELECT bounds->'requiredEvidenceKinds' FROM ops.ad_action_authorization WHERE recommendation_id=:recommendation")
                .param("recommendation",f.recommendation).query(String.class).single())
                .contains("AD_LINKED_SALE_EVENT","COST_AND_FEE","OFFICIAL_AD_SPEND","AFFECTED_SET","AD_OBJECT_CONFIGURATION","SELLABILITY","AVAILABILITY")
                .doesNotContain("OFFICIAL_AD_TRAFFIC");
        Object worker=context.getBean("adBidCommandWorker");
        ReflectionTestUtils.invokeMethod(worker,"runOnce",Instant.now(),10);
        assertThat(f.sql("SELECT state FROM ops.ad_bid_command WHERE id=:command").param("command",f.command).query(String.class).single())
                .isEqualTo("READBACK_MATCHED");
        assertThat(f.provider.calls).containsExactly(AdBidWriteRequest.Operation.APPLY,AdBidWriteRequest.Operation.READBACK);
        ReflectionTestUtils.invokeMethod(worker,"runOnce",Instant.now(),10);
        assertThat(f.provider.calls).hasSize(2);
        assertThat(f.sql("SELECT count(*) FROM ops.ad_bid_command WHERE platform_code IN('OZON','WILDBERRIES')").query(Integer.class).single()).isZero();
    }

    @Test void unresolvedEconomicsNeverBecomeAnEconomicDangerOrCandidate() {
        f.acceptPreActionFacts(false);
        var result=refresh();
        assertThat(result.calculation().cases()).noneMatch(value->value.identity().cause().name().equals(CAUSE));
        assertThat(result.proposed()).isEmpty();
        assertThat(f.count("ops.ad_case_responsibility")).isPositive();
        assertNoCommandOrCall();
    }

    @Test void anExactPolicyThatDoesNotAcceptThisCauseCannotGenerateTheStep() {
        f.sql("UPDATE core.ad_bid_target_policy SET cause_bound_causes=ARRAY['PROMOTED_VARIANT_UNAVAILABLE'] WHERE organization_id=:org").update();
        f.acceptPreActionFacts(true);
        var result=refresh();
        assertThat(result.calculation().cases()).anyMatch(value->value.identity().cause().name().equals(CAUSE));
        assertThat(result.proposed()).isEmpty();
        assertThat(f.count("ops.ad_bid_candidate")).isZero();
        assertNoCommandOrCall();
    }

    @Test void oldSourceWithNewAcceptanceCannotProveEconomicDanger() {
        spendSourceAgeSeconds=3601;
        f.acceptPreActionFacts(true);
        var result=refresh();
        assertThat(result.calculation().cases()).noneMatch(value->value.identity().cause().name().equals(CAUSE));
        assertThat(result.proposed()).isEmpty();
        assertNoCommandOrCall();
    }

    @Test void aNewerReportSegmentDoesNotRefreshTheOlderConsumedSource() {
        splitOfficialReports=true;spendSourceAgeSeconds=3601;
        f.acceptPreActionFacts(true);
        var result=refresh();
        var facts=context.getBean(com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.class)
                .objectFacts(f.graph.id("organization"),f.graph.id("object"),f.start.minus(Duration.ofDays(30)),f.start).orElseThrow();
        assertThat(facts.factCount()).isEqualTo(2);assertThat(facts.spendAmount()).isEqualByComparingTo("6000");
        assertThat(facts.coverageRatio()).isEqualByComparingTo("1");assertThat(facts.everyWindowComplete()).isTrue();
        assertThat(facts.latestSourceTime()).isEqualTo(f.start);
        assertThat(facts.earliestSourceTime()).isEqualTo(f.start.minusSeconds(3601));
        assertThat(result.calculation().purposeEvidence()).anySatisfy(proof->{
            assertThat(proof.purpose()).isEqualTo("PROTECTION_BID_WRITE");assertThat(proof.kind()).isEqualTo("OFFICIAL_AD_SPEND");
            assertThat(proof.sourceTime()).isEqualTo(facts.earliestSourceTime());assertThat(proof.eligible()).isFalse();
        });
        assertThat(result.proposed()).isEmpty();assertNoCommandOrCall();
    }

    @Test void completeFreshSegmentsRetainTheirExactOldestAcceptanceAndCanQualify() {
        splitOfficialReports=true;spendSourceAgeSeconds=60;firstAcceptedAgeSeconds=30;
        calculateCandidate();
        var facts=context.getBean(com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.class)
                .objectFacts(f.graph.id("organization"),f.graph.id("object"),f.start.minus(Duration.ofDays(30)),f.start).orElseThrow();
        assertThat(facts.factCount()).isEqualTo(2);assertThat(facts.acceptedAt()).isEqualTo(f.start);
        assertThat(facts.oldestAcceptedAt()).isEqualTo(f.start.minusSeconds(30));
        assertThat(f.sql("SELECT accepted_at FROM mart.ad_case_purpose_evidence WHERE case_id=:case AND decision_purpose='PROTECTION_BID_WRITE' AND evidence_kind='OFFICIAL_AD_SPEND'")
                .param("case",f.caseId).query(Instant.class).single()).isEqualTo(facts.oldestAcceptedAt());
        assertNoCommandOrCall();
    }

    @Test void aNewerFavorableUnrelatedMetricWindowCannotReplaceTheApplicableHistoricalCohort() {
        f.acceptPreActionFacts(true);
        var original=f.metrics.currentValues(com.mimococo.marketops.analyticsdecision.SubjectKind.PLATFORM_LISTING_VARIANT,
                f.graph.id("listingVariant"),com.mimococo.marketops.analyticsdecision.MetricWindow.D30);
        appendLaterFavorableCanonicalWindow();
        var latest=f.metrics.currentValues(com.mimococo.marketops.analyticsdecision.SubjectKind.PLATFORM_LISTING_VARIANT,
                f.graph.id("listingVariant"),com.mimococo.marketops.analyticsdecision.MetricWindow.D30);
        assertThat(latest.get(com.mimococo.marketops.analyticsdecision.MetricCode.UNIT_COST).numericValue()).isEqualByComparingTo("1");
        var linked=historicalLinked(f.start);
        var economics=context.getBean(AdvertisingEvidenceGatherer.class).economicsForSales(linked,
                f.start.minus(Duration.ofDays(30)),f.start,f.clock.instant());
        assertThat(economics.get(f.graph.id("listingVariant")).unitCost().value()).isEqualByComparingTo("500");
        assertThat(economics.get(f.graph.id("listingVariant")).lineage()).allSatisfy(value ->
                assertThat(value.metricValueId()).isEqualTo(original.get(value.metricCode()).metricValueId()));
        var spend=com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure.available(new BigDecimal("6000"),
                com.mimococo.marketops.advertisingefficiency.AdEvidenceState.CANONICAL_CONFIRMED);
        assertThat(AdvertisingAttributedEconomics.calculate(linked.orElseThrow(),economics,java.util.Map.of(),spend,"RUB")
                .profit().absoluteProfit().value()).isEqualByComparingTo("-1000");
        assertNoCommandOrCall();
    }

    @Test void onlyAnUnrelatedFavorableCanonicalWindowLeavesHistoricalEconomicsUnresolved() {
        f.acceptPreActionFacts(true,false);
        appendLaterFavorableCanonicalWindow();
        assertThat(context.getBean(AdvertisingEvidenceGatherer.class).economicsForSales(historicalLinked(f.start),
                f.start.minus(Duration.ofDays(30)),f.start,f.clock.instant())).isEmpty();
        assertNoCommandOrCall();
    }

    @Test void oneListingCannotReuseOneCoveredLineToAuthorizeAnotherUncoveredCohort() {
        f.acceptPreActionFacts(true);
        Instant later=f.start.plus(Duration.ofDays(31));f.clock.at=later;
        f.linked("10000",10,later.minus(Duration.ofDays(30)),later,later.minusSeconds(7200));
        var linked=historicalLinked(later);
        assertThat(linked.orElseThrow().lines()).hasSize(2);
        assertThat(context.getBean(AdvertisingEvidenceGatherer.class).economicsForSales(linked,
                f.start.minus(Duration.ofDays(30)),later,later)).isEmpty();
        assertNoCommandOrCall();
    }

    java.util.Optional<com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.LinkedSaleAggregate>
            historicalLinked(Instant to) {
        return context.getBean(com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.class)
                .linkedSales(f.graph.id("organization"),f.graph.id("object"),"CANONICAL_AD_LINKED_RETAINED_SALE",
                        f.start.minus(Duration.ofDays(30)),to,f.clock.instant());
    }

    void appendLaterFavorableCanonicalWindow() {
        f.clock.at=f.start.plus(Duration.ofDays(31));Instant occurred=f.clock.instant().minusSeconds(7200);
        f.company("COMPLETED","10000",10,occurred,"unrelated-later-window",null);
        f.company("RETAINED","10000",10,occurred,"unrelated-later-window",null);
        f.company("SETTLED","10000",10,occurred,"unrelated-later-window",null);
        f.coverage(f.clock.instant().minus(Duration.ofDays(31)),f.clock.instant(),true);
        f.context(f.clock.instant().minusSeconds(1));f.economicFacts(occurred);
        f.sql("UPDATE core.cost_version SET effective_to=:cut,updated_at=:at WHERE organization_id=:org AND effective_to IS NULL")
                .param("cut",Timestamp.from(f.start.plus(Duration.ofDays(1)))).update();
        f.sql("""
            INSERT INTO core.cost_version(id,organization_id,product_variant_id,cost_kind,currency_code,unit_cost,
              provenance_id,effective_from,status,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:variant,'PURCHASE','RUB',1,:source,:cut,'ACTIVE',:at,:at)
            """).param("cut",Timestamp.from(f.start.plus(Duration.ofDays(1)))).update();
        assertThat(f.analytics.run(f.graph.id("store"),com.mimococo.marketops.analyticsdecision.MetricWindow.D30,
                "SCHEDULED",null).subjectCount()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"OFFICIAL_AD_SPEND","AD_LINKED_SALE_EVENT","COST_AND_FEE","AFFECTED_SET","AD_OBJECT_CONFIGURATION","SELLABILITY","AVAILABILITY"})
    void everyCauseDependencyIsRequiredAtTheRealPlannerAndPreview(String kind) {
        calculateCandidate();
        f.sql("UPDATE core.ad_freshness_profile SET status='RETIRED' WHERE organization_id=:org AND decision_purpose='PROTECTION_BID_WRITE' AND evidence_kind=:kind")
                .param("kind",kind).update();
        assertThat(f.jdbc.sql("SELECT unnest(ops.ad_economic_cause_bound_failures(:id,:at))").param("id",candidate).param("at",Timestamp.from(f.start))
                .query(String.class).list()).contains("ECONOMIC_CAUSE_PURPOSE_EVIDENCE_UNRESOLVED");
        assertThatThrownBy(this::select).isInstanceOf(RuntimeException.class);
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();
        assertNoCommandOrCall();
    }

    @ParameterizedTest @ValueSource(strings={"AD_LINKED_QUANTITY_LINEAGE_UNAVAILABLE","MIXED_OR_UNRESOLVED_SALES_CURRENCY",
        "PROVIDER_TO_CANONICAL_ATTRIBUTION_GAP_MATERIAL","CRITICAL_SALES_GUARD_EVIDENCE_UNRESOLVED","CRITICAL_UNIT_COVERAGE_UNRESOLVED",
        "ACCEPTED_EXCEPTION_ACTIVE","CURRENT_HUMAN_AUTHORITY_REVOKED","AFFECTED_SET_UNRESOLVED","INVENTORY_EVIDENCE_UNRESOLVED",
        "LINE_COST_COMPONENT_UNAVAILABLE:11111111-1111-1111-1111-111111111111"})
    void economicDependencyExemptionRetainsEveryNonConversionBlockerInJavaAndSql(String blocker) {
        var input=List.of("AD_LINKED_CONVERSION_NOT_WRITE_GRADE",blocker);
        assertThat(AdActionDependencyPolicy.actionBlockers(BASIS,CAUSE,input)).containsExactly(blocker);
        assertThat(f.jdbc.sql("SELECT unnest(ops.ad_action_blockers(:basis,:cause,:blockers))")
                .param("basis",BASIS).param("cause",CAUSE).param("blockers",input.toArray(new String[0])).query(String.class).list())
                .containsExactly(blocker);
    }

    @Test void JavaAndSqlUseTheSameEvidenceKindsForEveryBasisAndCause() {
        for(String basis:List.of(BASIS,"MAX_CPC_BOUNDED")) for(String cause:List.of(CAUSE,"PROMOTED_VARIANT_NOT_SELLABLE","PROMOTED_VARIANT_UNAVAILABLE")) {
            assertThat(f.jdbc.sql("SELECT unnest(ops.ad_required_action_evidence_kinds(:basis,:cause))")
                    .param("basis",basis).param("cause",cause).query(String.class).list())
                    .containsExactlyElementsOf(AdActionDependencyPolicy.requiredEvidenceKinds(basis,cause));
        }
    }

    @Test void anUnsupportedCauseCannotHaveAClearJavaOrSqlPreviewDependencySet() {
        String unsupported="UNKNOWN_DANGER";
        assertThat(AdActionDependencyPolicy.requiredEvidenceKinds(BASIS,unsupported)).isEmpty();
        assertThat(AdActionDependencyPolicy.actionBlockers(BASIS,unsupported,List.of())).containsExactly("CAUSE_BOUND_CAUSE_UNSUPPORTED");
        assertThat(f.jdbc.sql("SELECT unnest(ops.ad_action_blockers(:basis,:cause,ARRAY[]::text[]))")
                .param("basis",BASIS).param("cause",unsupported).query(String.class).list()).containsExactly("CAUSE_BOUND_CAUSE_UNSUPPORTED");
        assertNoCommandOrCall();
    }

    @Test void anActualAcceptedExceptionKeepsTheEconomicCandidateInert() {
        calculateCandidate();
        f.scope("executorUser","ADVERTISING_EXCEPTION_REQUEST");
        var service=context.getBean(com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingExceptionService.class);
        var requested=service.request(f.maker,f.caseId,f.start.plusSeconds(600),f.start.plusSeconds(300),
                "Synthetic explicit risk acceptance","fixture://cv-b/accepted-exception");
        var endorsed=service.endorse(f.ops,requested.id(),requested.version(),"Independent synthetic exception review");
        var approved=service.approve(f.owner,requested.id(),endorsed.version(),"Accept exact synthetic economic risk");
        assertThat(approved.state()).isEqualTo("ACTIVE");
        assertThatThrownBy(this::select).isInstanceOf(RuntimeException.class);
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();
        assertNoCommandOrCall();
    }

    @Test void causeBoundProofDoesNotReplaceIndependentEndorsementOrOwnerApproval() {
        calculateCandidate();
        var selected=select();
        assertThatThrownBy(()->f.humans.endorse(f.maker,f.recommendation,selected.version(),"Invalid self endorsement"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->f.execution.createCommand(f.owner,f.recommendation,selected.version()))
                .isInstanceOf(RuntimeException.class);
        assertThat(f.count("ops.ad_action_authorization")).isZero();
        assertNoCommandOrCall();
    }

    @Test void revokedMakerScopeCannotSelectTheEconomicCandidate() {
        calculateCandidate();
        f.sql("UPDATE iam.user_scope_grant SET status='REVOKED' WHERE organization_id=:org AND user_id=:maker AND action_code='ADVERTISING_TASK_ACT'")
                .param("maker",f.maker.userId()).update();
        assertThatThrownBy(this::select).isInstanceOf(RuntimeException.class);
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();
        assertNoCommandOrCall();
    }

    @ParameterizedTest @ValueSource(strings={"COST_AND_FEE","SELLABILITY","AVAILABILITY"})
    void revokingEconomicOrPhysicalSafetyAfterApprovalPreventsTheActualFixtureTransmission(String kind) {
        calculateCandidate();
        var selected=select();
        var endorsed=f.humans.endorse(f.ops,f.recommendation,selected.version(),"Review exact synthetic cause evidence");
        f.humans.preparePreview(f.owner,f.recommendation);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(f.owner,null,List.of()));
        f.approvals.approve(f.owner,f.recommendation,"Approve exact synthetic cause before independent revocation",endorsed.version());
        f.command=f.execution.createCommand(f.owner,f.recommendation,f.recommendations.require(f.recommendation).version()).commandId();
        f.sql("UPDATE core.ad_freshness_profile SET status='RETIRED' WHERE organization_id=:org AND decision_purpose='PROTECTION_BID_WRITE' AND evidence_kind=:kind")
                .param("kind",kind).update();
        assertThat(f.jdbc.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))").param("id",f.command).query(String.class).list())
                .contains("ECONOMIC_CAUSE_PURPOSE_EVIDENCE_UNRESOLVED");
        ReflectionTestUtils.invokeMethod(context.getBean("adBidCommandWorker"),"runOnce",Instant.now(),10);
        assertThat(f.provider.calls).isEmpty();
        assertThat(f.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:command AND purpose='APPLY'")
                .param("command",f.command).query(Integer.class).single()).isZero();
    }

    void calculateCandidate() {
        f.acceptPreActionFacts(true);
        var result=refresh();
        var economic=result.calculation().cases().stream().filter(value->value.identity().cause().name().equals(CAUSE)).findFirst().orElseThrow();
        assertThat(economic.contributionProfit().value()).isEqualByComparingTo("-1000");
        assertThat(economic.contributionProfit().sufficientForWrite()).isTrue();
        assertThat(economic.maxCpc().writeGrade()).isFalse();
        assertThat(economic.conversion().writeGrade()).isFalse();
        assertThat(result.calculation().causeBoundProtectionQualified(economic)).isTrue();
        assertThat(result.proposed()).as("Actual cases: %s; purpose: %s",result.calculation().cases(),result.calculation().purposeEvidence()).hasSize(1);
        f.recommendation=result.proposed().getFirst();
        candidate=UUID.fromString(f.recommendations.require(f.recommendation).proposedParameters().get("candidateId"));
        f.caseId=f.sql("SELECT case_id FROM ops.ad_bid_candidate WHERE id=:candidate").param("candidate",candidate).query(UUID.class).single();
        assertThat(f.sql("SELECT candidate_basis FROM ops.ad_bid_candidate WHERE id=:candidate").param("candidate",candidate).query(String.class).single()).isEqualTo(BASIS);
        assertThat(f.jdbc.sql("SELECT unnest(ops.ad_economic_cause_bound_failures(:id,:at))").param("id",candidate).param("at",Timestamp.from(f.start)).query(String.class).list()).isEmpty();
    }
    AdvertisingCaseRefreshService.RefreshOutcome refresh() {
        f.context(f.start.minus(Duration.ofDays(31)));
        context.getBean(com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityRiskRefreshService.class)
                .refresh(f.graph.id("organization"),f.graph.id("productVariant"),f.start,"TARGETED",null,"cv-b-inherited-safety");
        return f.refresh.refresh(f.graph.id("organization"),f.graph.id("object"),f.start,"TARGETED",null,"cv-b-cause-bound").orElseThrow();
    }
    com.mimococo.marketops.operationsworkflow.RecommendationView select() {
        f.fictionalDispatchControls(candidate);
        return f.humans.select(f.maker,f.caseId,candidate,0,"Select exact economic cause-bound synthetic candidate");
    }
    void assertNoCommandOrCall() {
        assertThat(f.count("ops.ad_bid_command")).isZero();
        assertThat(f.provider.calls).isEmpty();
    }

    void inheritedAvailabilityAuthorities() {
        f.sql("""
            INSERT INTO core.lead_time_safety_policy(id,organization_id,scope_kind,scope_precedence,
              lead_time_days_min,lead_time_days_max,safety_days,owner_user_id,reason,evidence_reference,last_reviewed_at,
              effective_from,status,policy_version,created_at)
            VALUES(gen_random_uuid(),:org,'ORGANIZATION',3,10,14,7,:owner,'Synthetic inherited lead time',
              'fixture://cv-b/lead-time',:at,CAST(:at AS timestamptz)-interval '60 days','ACTIVE',1,:at)
            """).update();
        f.sql("""
            INSERT INTO core.demand_observation_policy(id,organization_id,minimum_sample_units,acceleration_ratio,
              deceleration_ratio,outlier_share_ratio,minimum_coverage_ratio,carry_forward_max_days,stock_freshness_max_minutes,
              owner_user_id,reason,evidence_reference,effective_from,status,policy_version,created_at)
            VALUES(gen_random_uuid(),:org,5,1.5,0.6,0.7,0.6,14,60,:owner,'Synthetic inherited demand policy',
              'fixture://cv-b/demand',CAST(:at AS timestamptz)-interval '60 days','ACTIVE',1,:at)
            """).update();
        f.sql("""
            INSERT INTO core.work_activation_policy(id,organization_id,high_sustained_cycles,critical_action_sla_minutes,
              high_action_sla_minutes,blocker_action_sla_minutes,outcome_sla_minutes,verification_window_minutes,
              owner_user_id,reason,evidence_reference,effective_from,status,policy_version,created_at)
            VALUES(gen_random_uuid(),:org,2,60,240,480,2880,1440,:owner,'Synthetic inherited activation',
              'fixture://cv-b/activation',CAST(:at AS timestamptz)-interval '60 days','ACTIVE',1,:at)
            """).update();
        f.sql("""
            INSERT INTO core.availability_priority_policy(id,organization_id,policy_version,time_weight,profit_weight,
              velocity_weight,lifecycle_weight,confidence_weight,owner_user_id,reason,evidence_reference,effective_from,status,created_at)
            VALUES(gen_random_uuid(),:org,1,400,5,20,25,-10,:owner,'Synthetic inherited priority',
              'fixture://cv-b/priority',CAST(:at AS timestamptz)-interval '60 days','ACTIVE',:at)
            """).update();
        f.sql("""
            INSERT INTO core.return_quality_policy(id,organization_id,policy_version,maximum_return_ratio,minimum_retention_ratio,
              maximum_defect_return_ratio,evidence_freshness_max_minutes,owner_user_id,reason,evidence_reference,effective_from,status,created_at)
            VALUES(gen_random_uuid(),:org,1,0.25,0.8,0.1,1440,:owner,'Synthetic inherited return guard',
              'fixture://cv-b/return-guard',CAST(:at AS timestamptz)-interval '60 days','ACTIVE',:at)
            """).update();
    }
}
