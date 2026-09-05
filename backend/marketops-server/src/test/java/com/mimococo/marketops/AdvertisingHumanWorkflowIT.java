package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import com.mimococo.marketops.operationsworkflow.AdvertisingTaskSloQuery;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingExceptionService;
import com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingHumanDecisionService;
import com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingWorkflowQueryService;
import com.mimococo.marketops.operationsworkflow.internal.application.ApprovalService;
import com.mimococo.marketops.operationsworkflow.internal.application.ExecutionService;
import com.mimococo.marketops.operationsworkflow.internal.application.GuardrailService;
import com.mimococo.marketops.operationsworkflow.internal.application.RecommendationService;
import com.mimococo.marketops.operationsworkflow.internal.application.WorkTaskService;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real service transactions, live IAM and isolated PostgreSQL; no transmission adapter is called. */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingHumanWorkflowIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static final String ISSUER_PASSWORD=UUID.randomUUID().toString();
    @Autowired AdvertisingResponsibilityIntake responsibilities;
    @Autowired AdvertisingHumanDecisionService humans;
    @Autowired AdvertisingExceptionService exceptions;
    @Autowired AdvertisingTaskSloQuery slo;
    @Autowired com.mimococo.marketops.operationsworkflow.AdvertisingReconciliationMaintenance maintenance;
    @Autowired AdvertisingWorkflowQueryService workflows;
    @Autowired WorkTaskService tasks;
    @Autowired RecommendationService recommendations;
    @Autowired ApprovalService approvals;
    @Autowired ExecutionService execution;
    @Autowired GuardrailService guardrails;
    @Autowired com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingCandidateRepository candidates;
    @Autowired DataSource application;
    private JdbcClient seed;
    private AdvertisingR1Fixture.Graph graph;
    private UUID task;
    private AuthenticatedActor maker,ops,owner;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
        registry.add("marketops.identity.invocation.jdbc-url",DATABASE::getJdbcUrl);
        registry.add("marketops.identity.invocation.username",()->"marketops_identity_issuer");
        registry.add("marketops.identity.invocation.password",()->ISSUER_PASSWORD);
    }
    @BeforeEach void fixture() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        seed=JdbcClient.create(migration);graph=AdvertisingR1Fixture.seedUnapproved(migration);
        try(var admin=DATABASE.createConnection("")) {
            try(var statement=admin.createStatement()) { statement.execute("ALTER ROLE marketops_identity_issuer LOGIN PASSWORD '"+ISSUER_PASSWORD+"'"); }
        }
        role("executorUser","MARKETPLACE_OPERATOR");
        for(String user:List.of("executorUser","verifierUser","ownerUser")) {
            for(String action:List.of("ADVERTISING_VIEW","ADVERTISING_TASK_ACT","ADVERTISING_EXCEPTION_REQUEST")) scope(user,action);
        }
        for(String user:List.of("verifierUser","ownerUser")) scope(user,"ADVERTISING_DECISION_EVIDENCE_VIEW");
        seed.sql("UPDATE core.ad_human_slo_profile SET staffed_coverage_enabled=true,staffed_coverage_timezone='Etc/UTC',staffed_coverage_start_minute=0,staffed_coverage_end_minute=1439 WHERE id=:id")
                .param("id",graph.id("humanSlo")).update();
        seed.sql("""
                INSERT INTO core.ad_reporting_calendar(id,organization_id,policy_version,scope_kind,reporting_timezone,
                    daily_cut_minute,operating_days,weekly_cut_weekday,weekly_cut_minute,late_revision_horizon_hours,
                    owner_user_id,reason,evidence_reference,effective_from,status,created_at)
                VALUES(gen_random_uuid(),:org,1,'ORGANIZATION','Etc/UTC',0,ARRAY[1,2,3,4,5,6,7]::smallint[],1,0,24,
                    :owner,'Explicit fictional staffed calendar','fixture://staffed',now()-interval '1 day','ACTIVE',now())
                """).param("org",graph.id("organization")).param("owner",graph.id("ownerUser")).update();
        maker=actor("executorUser",BusinessRoleCode.MARKETPLACE_OPERATOR);
        ops=actor("verifierUser",BusinessRoleCode.OPS_LEAD);
        owner=actor("ownerUser",BusinessRoleCode.OWNER);
        task=responsibilities.ensureResponsibility(graph.id("caseId"),graph.id("calculationRun"),"MARKETPLACE_OPERATOR");
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void realMakerOpsOwnerChainFreezesOneBaselineAndCreatesOneCommand() {
        UUID recommendation=graph.id("recommendation");
        assertThat(recommendations.require(recommendation).state()).isEqualTo(RecommendationState.DRAFT);
        var selected=humans.select(maker,graph.id("caseId"),graph.id("candidate"),0,"Choose generated bounded decrease");
        assertThat(selected.state()).isEqualTo(RecommendationState.VALIDATED);
        assertThat(workflows.workflow(maker,graph.id("caseId")).operatingDisposition()).isEqualTo("ACTION_IN_PROGRESS");
        assertThatThrownBy(this::request).isInstanceOf(OperationRejectedException.class);
        var endorsed=humans.endorse(ops,recommendation,selected.version(),"Operational scope checked");
        assertThat(endorsed.state()).isEqualTo(RecommendationState.READY_FOR_REVIEW);
        humans.preparePreview(owner,recommendation);
        var preview=guardrails.previewAdBidChange(recommendations.require(recommendation),GuardrailPurpose.IMPACT_PREVIEW);
        assertThat(preview.evidence().path("risk").path("absoluteProfit").has("state")).isTrue();
        assertThat(preview.evidence().path("submittedConfiguration").path("target").decimalValue()).isEqualByComparingTo("20");
        login(owner);
        var approved=approvals.approve(owner,recommendation,"Exact per-command Owner approval",endorsed.version());
        assertThat(approved.state()).isEqualTo(RecommendationState.APPROVED);
        UUID command=execution.createCommand(owner,recommendation,recommendations.require(recommendation).version()).commandId();
        assertThat(command).isNotNull();
        assertThat(seed.sql("""
                SELECT s.outcome_baseline_id=a.outcome_baseline_id AND a.outcome_baseline_id=cmd.outcome_baseline_id
                FROM ops.ad_candidate_selection s JOIN ops.ad_action_authorization a USING(recommendation_id)
                JOIN ops.ad_bid_command cmd USING(recommendation_id) WHERE s.recommendation_id=:id
                """).param("id",recommendation).query(Boolean.class).single()).isTrue();
        assertThat(tasks.journal(task)).extracting(event->event.eventKind()).contains("ACTION_RECORDED");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE recommendation_id=:id").param("id",recommendation).query(Long.class).single()).isEqualTo(1);
    }

    @Test void responsibilityIsIndependentOfCandidateAndPageViewIsNotAcknowledgement() {
        assertThat(responsibilities.ensureResponsibility(graph.id("caseId"),graph.id("calculationRun"),"MARKETPLACE_OPERATOR")).isEqualTo(task);
        tasks.recordView(maker,task);
        var viewed=slo.statusForCase(graph.id("caseId")).orElseThrow();
        assertThat(viewed.acknowledgedAt()).isNull();assertThat(viewed.firstAttributableActionAt()).isNull();
        tasks.acknowledge(maker,task);
        var acknowledged=slo.statusForCase(graph.id("caseId")).orElseThrow();
        assertThat(acknowledged.acknowledgedAt()).isNotNull();assertThat(acknowledged.firstAttributableActionAt()).isNull();
        assertThatThrownBy(()->tasks.recordAction(maker,task,"DATA_OR_MAPPING_REPAIR",UUID.randomUUID().toString(),"unproven repair"))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(()->tasks.assign(maker,task,owner.userId(),tasks.find(task).orElseThrow().version()))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(()->tasks.close(maker,task,true,"hide unresolved risk",tasks.find(task).orElseThrow().version()))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test void ownerAcceptedRiskPausesOnlyActionAndMustEndBeforeNewPreviewOrIntent() {
        var original=slo.statusForCase(graph.id("caseId")).orElseThrow();
        var request=request();
        var endorsed=exceptions.endorse(ops,request.id(),request.version(),"Operations accepts exact known risk");
        var active=exceptions.approve(owner,request.id(),endorsed.version(),"Owner accepts bounded risk until review");
        var paused=slo.statusForCase(graph.id("caseId")).orElseThrow();
        assertThat(paused.actionPaused()).isTrue();
        assertThat(paused.acknowledgementDueAt()).isEqualTo(original.acknowledgementDueAt());
        assertThat(workflows.workflow(maker,graph.id("caseId")).operatingDisposition()).isEqualTo("ACCEPTED_EXCEPTION_ACTIVE");
        assertThatThrownBy(()->humans.preparePreview(owner,graph.id("recommendation"))).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(()->humans.select(maker,graph.id("caseId"),graph.id("candidate"),0,"new intent while exception active"))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(()->tasks.reopen(maker,task,false,"reset the action epoch while risk is accepted"))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(workflows.workflow(maker,graph.id("caseId")).allowedActions()).doesNotContain("TASK_REOPEN","TASK_ACTION","TASK_START");
        exceptions.end(maker,request.id(),active.version(),"End risk acceptance and rebuild evidence");
        assertThat(slo.statusForCase(graph.id("caseId")).orElseThrow().actionPaused()).isFalse();
        assertThat(tasks.journal(task)).extracting(event->event.eventKind()).contains("REOPENED");
        assertThat(recommendations.require(graph.id("recommendation")).state()).isEqualTo(RecommendationState.CANCELLED);
        assertThat(exceptions.review(owner,request.id()).exposureSnapshotJson()).contains("salesEvidence","diagnostics","account");
    }

    @Test void restoredRoleCannotResurrectAnExceptionAndSameTaskReopens() {
        var request=request();var endorsed=exceptions.endorse(ops,request.id(),request.version(),"Ops risk endorsement");
        exceptions.approve(owner,request.id(),endorsed.version(),"Owner bounded risk acceptance");
        seed.sql("UPDATE iam.user_role_assignment SET status='REVOKED' WHERE user_id=:id AND role_code='OWNER'").param("id",owner.userId()).update();
        seed.sql("UPDATE iam.user_role_assignment SET status='ACTIVE' WHERE user_id=:id AND role_code='OWNER'").param("id",owner.userId()).update();
        assertThat(exceptions.refreshInvalidation(graph.id("caseId"))).isEqualTo(1);
        assertThat(exceptions.forCase(owner,graph.id("caseId")).getFirst().state()).isEqualTo("INVALIDATED");
        assertThat(tasks.find(task).orElseThrow().state()).isEqualTo("OPEN");
        assertThat(tasks.journal(task)).extracting(event->event.eventKind()).contains("REOPENED");
        assertThat(slo.statusForCase(graph.id("caseId")).orElseThrow().firstAttributableActionAt()).isNull();
    }

    @Test void exceptionReasonsAreAuditedAndPartiallyScopedReviewerCannotReadProfit() {
        var request=request();
        assertThatThrownBy(()->exceptions.endorse(ops,request.id(),request.version()," ")).isInstanceOf(OperationRejectedException.class);
        assertThat(exceptions.forCase(owner,graph.id("caseId")).getFirst().state()).isEqualTo("REQUESTED");
        assertThat(exceptions.review(maker,request.id()).disclosureState()).isEqualTo("MASKED");
        assertThat(exceptions.review(maker,request.id()).exposureSnapshotJson()).isNull();
        assertThatThrownBy(()->humans.preparePreview(maker,graph.id("recommendation"))).isInstanceOf(OperationRejectedException.class);
    }

    @Test void reconciliationRecordsExpiryOnceWithoutRewritingApprovalOrReleasingExposure() {
        seed.sql("UPDATE mart.ad_case_purpose_evidence SET expires_at=now()+interval '5 seconds' WHERE case_id=:id")
                .param("id",graph.id("caseId")).update();
        UUID recommendation=graph.id("recommendation");
        var selected=humans.select(maker,graph.id("caseId"),graph.id("candidate"),0,"Bound the finite choice");
        var endorsed=humans.endorse(ops,recommendation,selected.version(),"Ops endorsement before expiry");
        login(owner);approvals.approve(owner,recommendation,"Owner short synthetic lease",endorsed.version());
        String before=seed.sql("SELECT to_jsonb(a)::text FROM ops.ad_action_authorization a WHERE recommendation_id=:id")
                .param("id",recommendation).query(String.class).single();
        seed.sql("SELECT pg_sleep(greatest(0,extract(epoch FROM expires_at-clock_timestamp()))::double precision+0.05) FROM ops.ad_action_authorization WHERE recommendation_id=:id")
                .param("id",recommendation).query(rs -> { rs.next();return 0; });
        var counts=maintenance.reconcile(graph.id("organization"),Instant.now());
        assertThat(counts.expiredApprovals()).isEqualTo(1);
        assertThat(counts.expiredRecommendations()).isEqualTo(1);
        assertThat(maintenance.reconcile(graph.id("organization"),Instant.now()).expiredApprovals()).isZero();
        assertThat(seed.sql("SELECT to_jsonb(a)::text FROM ops.ad_action_authorization a WHERE recommendation_id=:id")
                .param("id",recommendation).query(String.class).single()).isEqualTo(before);
        assertThat(recommendations.require(recommendation).state()).isEqualTo(RecommendationState.EXPIRED);
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Long.class).single()).isZero();
    }

    @Test void aSchedulerClockAheadCannotExpireCurrentApprovalOrRecommendation() {
        UUID recommendation=graph.id("recommendation");
        var selected=humans.select(maker,graph.id("caseId"),graph.id("candidate"),0,"Exact finite choice");
        var endorsed=humans.endorse(ops,recommendation,selected.version(),"Independent operation review");
        login(owner);approvals.approve(owner,recommendation,"Current bounded approval",endorsed.version());
        var counts=maintenance.reconcile(graph.id("organization"),Instant.now().plusSeconds(86400));
        assertThat(counts.expiredApprovals()).isZero();assertThat(counts.expiredRecommendations()).isZero();
        assertThat(recommendations.require(recommendation).state()).isEqualTo(RecommendationState.APPROVED);
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_authority_invalidation WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Long.class).single()).isZero();
    }

    @Test void restoredCaseAuthorityCannotRestoreAcceptedRiskAndViewReadsTheReopenedTask() {
        var request=request();var endorsed=exceptions.endorse(ops,request.id(),request.version(),"Ops bounded risk");
        exceptions.approve(owner,request.id(),endorsed.version(),"Owner bounded risk");
        String digest=seed.sql("SELECT policy_version_digest FROM mart.ad_case WHERE id=:id")
                .param("id",graph.id("caseId")).query(String.class).single();
        seed.sql("UPDATE mart.ad_case SET policy_version_digest=:digest WHERE id=:id")
                .param("id",graph.id("caseId")).param("digest","e".repeat(64)).update();
        seed.sql("UPDATE mart.ad_case SET policy_version_digest=:digest WHERE id=:id")
                .param("id",graph.id("caseId")).param("digest",digest).update();
        var view=workflows.workflow(maker,graph.id("caseId"));
        assertThat(view.operatingDisposition()).isEqualTo("ACTION_REQUIRED");
        assertThat(view.taskId()).isEqualTo(task);assertThat(view.taskState()).isEqualTo("OPEN");
        assertThat(view.slo().actionPaused()).isFalse();assertThat(view.slo().firstAttributableActionAt()).isNull();
        assertThat(exceptions.forCase(owner,graph.id("caseId")).getFirst().state()).isEqualTo("INVALIDATED");
    }

    @Test void unresolvedResponseProfileEscalatesOnceOnTheSameTaskWithoutClaimingTimeliness() {
        seed.sql("UPDATE ops.ad_case_responsibility SET profile_snapshot='{}'::jsonb WHERE case_id=:id")
                .param("id",graph.id("caseId")).update();
        var first=maintenance.reconcile(graph.id("organization"),Instant.now());
        assertThat(first.escalatedTasks()).isEqualTo(1);
        assertThat(maintenance.reconcile(graph.id("organization"),Instant.now()).escalatedTasks()).isZero();
        var status=slo.statusForCase(graph.id("caseId")).orElseThrow();
        assertThat(status.coverageState()).isEqualTo("PROFILE_OR_CALENDAR_MISSING");
        assertThat(status.actionDueAt()).isNull();assertThat(status.acknowledgementDueAt()).isNull();
        assertThat(tasks.journal(task)).extracting(event->event.eventKind()).contains("ESCALATED");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Long.class).single()).isZero();
    }

    @Test void anOwnerProfileBecomingAvailableRepairsTheSameTaskWithoutRewritingPastUnknowns() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var other=AdvertisingR1Fixture.seedUnapproved(migration);
        seed.sql("UPDATE core.ad_human_slo_profile SET status='RETIRED' WHERE id=:id").param("id",other.id("humanSlo")).update();
        UUID sameTask=responsibilities.ensureResponsibility(other.id("caseId"),other.id("calculationRun"),"MARKETPLACE_OPERATOR");
        Instant beforeRepair=Instant.now();
        assertThat(slo.statusForCase(other.id("caseId"),beforeRepair).orElseThrow().coverageState()).isEqualTo("PROFILE_OR_CALENDAR_MISSING");
        Instant firstRaised=seed.sql("SELECT first_raised_at FROM ops.ad_case_responsibility WHERE case_id=:id")
                .param("id",other.id("caseId")).query((rs,n)->rs.getTimestamp(1).toInstant()).single();
        seed.sql("UPDATE core.ad_human_slo_profile SET status='ACTIVE',staffed_coverage_enabled=true,staffed_coverage_timezone='Etc/UTC',staffed_coverage_start_minute=0,staffed_coverage_end_minute=1439 WHERE id=:id")
                .param("id",other.id("humanSlo")).update();
        seed.sql("""
                INSERT INTO core.ad_reporting_calendar(id,organization_id,policy_version,scope_kind,reporting_timezone,
                    daily_cut_minute,operating_days,weekly_cut_weekday,weekly_cut_minute,late_revision_horizon_hours,
                    owner_user_id,reason,evidence_reference,effective_from,status,created_at)
                VALUES(gen_random_uuid(),:org,1,'ORGANIZATION','Etc/UTC',0,ARRAY[1,2,3,4,5,6,7]::smallint[],1,0,24,
                    :owner,'New explicit fictional coverage','fixture://new-staffed',now(),'ACTIVE',now())
                """).param("org",other.id("organization")).param("owner",other.id("ownerUser")).update();
        assertThat(responsibilities.ensureResponsibility(other.id("caseId"),other.id("calculationRun"),"MARKETPLACE_OPERATOR")).isEqualTo(sameTask);
        assertThat(slo.statusForCase(other.id("caseId")).orElseThrow().actionDueAt()).isNotNull();
        assertThat(slo.statusForCase(other.id("caseId"),beforeRepair).orElseThrow().coverageState()).isEqualTo("PROFILE_OR_CALENDAR_MISSING");
        assertThat(seed.sql("SELECT first_raised_at FROM ops.ad_case_responsibility WHERE case_id=:id")
                .param("id",other.id("caseId")).query((rs,n)->rs.getTimestamp(1).toInstant()).single()).isEqualTo(firstRaised);
        assertThat(tasks.journal(sameTask)).filteredOn(e->e.eventKind().equals("RAISED")).hasSize(1);
    }

    @Test void independentCauseKeepsOneConcurrentTaskAndThreeFiniteChoicesWithoutFutureSloLeakage() throws Exception {
        UUID otherCase=UUID.randomUUID();
        seed.sql("""
                INSERT INTO mart.ad_case SELECT (jsonb_populate_record(NULL::mart.ad_case,to_jsonb(c)
                  ||jsonb_build_object('id',CAST(:id AS uuid),'case_key',:key,
                    'cause_code','PROMOTED_VARIANT_NOT_SELLABLE','protection_tier','P1'))).*
                FROM mart.ad_case c WHERE c.id=:source
                """).param("id",otherCase).param("key",UUID.randomUUID().toString().replace("-","").repeat(2))
                .param("source",graph.id("caseId")).update();
        Instant beforeBinding=Instant.now();
        assertThat(slo.statusForCase(otherCase,beforeBinding)).isEmpty();
        List<UUID> concurrentTasks;
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var calls=new java.util.ArrayList<java.util.concurrent.Future<UUID>>();
            for(int index=0;index<4;index++) calls.add(workers.submit(()->{
                start.await();return responsibilities.ensureResponsibility(otherCase,graph.id("calculationRun"),"MARKETPLACE_OPERATOR");
            }));
            start.countDown();concurrentTasks=new java.util.ArrayList<>();
            for(var call:calls) concurrentTasks.add(call.get(30,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(concurrentTasks.stream().distinct().toList()).hasSize(1).doesNotContain(task);
        assertThat(slo.statusForCase(otherCase,beforeBinding)).isEmpty();
        assertThat(slo.statusForCase(otherCase).orElseThrow().actionDueAt()).isNotNull();
        UUID otherTask=concurrentTasks.getFirst();
        assertThat(tasks.journal(otherTask)).filteredOn(event->event.eventKind().equals("RAISED")).hasSize(1);
        var grid=new com.mimococo.marketops.advertisingefficiency.internal.domain.ProviderBidGrid(
                "CURRENCY_MAJOR","RUB",0,new java.math.BigDecimal("1"),new java.math.BigDecimal("1"),
                new java.math.BigDecimal("1000"),true,"VERIFIED");
        var limits=new com.mimococo.marketops.advertisingefficiency.internal.domain.BidStepLimits(
                new java.math.BigDecimal("0.5"),new java.math.BigDecimal("50"),new java.math.BigDecimal("0.1"));
        var maxCpc=new com.mimococo.marketops.advertisingefficiency.internal.domain.MaxCpc(
                com.mimococo.marketops.advertisingefficiency.SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE,
                new com.mimococo.marketops.shared.Money(new java.math.BigDecimal("22"),"RUB"),
                com.mimococo.marketops.advertisingefficiency.AdEvidenceState.CANONICAL_CONFIRMED,
                com.mimococo.marketops.advertisingefficiency.internal.domain.MaxCpc.Absence.NONE);
        seed.sql("UPDATE core.ad_bid_target_policy SET candidate_count=3,allow_protection_intermediate_target=true WHERE id=:id")
                .param("id",graph.id("targetPolicy")).update();
        var endpoint=com.mimococo.marketops.advertisingefficiency.internal.domain.BidCandidate.decrease(
                com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure.available(new java.math.BigDecimal("30"),
                    com.mimococo.marketops.advertisingefficiency.AdEvidenceState.CANONICAL_CONFIRMED),
                maxCpc,limits,grid,"MAX_CPC_BOUNDED").orElseThrow();
        var choices=com.mimococo.marketops.advertisingefficiency.internal.domain.BidCandidateSet.generate(
                endpoint,3,limits,grid,maxCpc,true);
        assertThat(choices).hasSize(3);
        String digest=candidates.resolvedAffectedSet(graph.id("organization"),otherCase).orElseThrow().digest();
        var firstRecommendations=new java.util.ArrayList<UUID>();
        for(int repetition=0;repetition<2;repetition++) {
            var proposed=new java.util.ArrayList<UUID>();int ordinal=0;
            for(var choice:choices) {
                UUID candidate=candidates.record(UUID.randomUUID(),graph.id("organization"),otherCase,graph.id("object"),
                        digest,graph.id("targetPolicy"),1,graph.id("profile"),choice,++ordinal,new java.math.BigDecimal("22"),
                        null,"PROMOTED_VARIANT_NOT_SELLABLE",Instant.now(),"finite-choice-runtime");
                proposed.add(recommendations.proposeBidChange(new com.mimococo.marketops.operationsworkflow.AdvertisingBidProposal(
                        "finite-choice-runtime",graph.id("organization"),graph.id("store"),graph.id("object"),otherCase,
                        candidate,choice.direction(),choice.providerNormalizedAmount(),com.mimococo.marketops.analyticsdecision.MetricWindow.D30,
                        java.math.BigDecimal.ONE,java.util.Map.of("cause","PROMOTED_VARIANT_NOT_SELLABLE"),"HIGH",14,
                        java.time.Duration.ofMinutes(60),graph.id("calculationRun"),
                        candidates.entityVersionDigest(graph.id("object"),candidate).orElseThrow(),List.of())));
            }
            if(repetition==0) firstRecommendations.addAll(proposed);else assertThat(proposed).isEqualTo(firstRecommendations);
        }
        assertThat(firstRecommendations.stream().distinct().toList()).hasSize(3);
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_candidate WHERE case_id=:case").param("case",otherCase).query(Long.class).single()).isEqualTo(3);
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_case_responsibility WHERE organization_id=:org").param("org",graph.id("organization")).query(Long.class).single()).isEqualTo(2);
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:org").param("org",graph.id("organization")).query(Long.class).single()).isZero();
    }

    private AdvertisingExceptionService.View request() {
        Instant now=Instant.now();return exceptions.request(maker,graph.id("caseId"),now.plusSeconds(1800),now.plusSeconds(900),
                "Accept exact continuing risk pending repair","fixture://risk-review");
    }
    private AuthenticatedActor actor(String user,BusinessRoleCode role) {
        String issuer=seed.sql("SELECT issuer FROM iam.identity_provider WHERE id=:id").param("id",graph.id("provider")).query(String.class).single();
        return new AuthenticatedActor(graph.id(user),graph.id("organization"),graph.id("provider"),issuer,"Synthetic role",
                "a".repeat(64),"b".repeat(64),Instant.now(),Instant.now().plusSeconds(1800),true,Set.of(role));
    }
    private void login(AuthenticatedActor actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
    }
    private void role(String user,String role) {
        seed.sql("INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),:org,:user,:role,'ACTIVE',now()-interval '1 hour','synthetic role',now(),now()) ON CONFLICT DO NOTHING")
                .param("org",graph.id("organization")).param("user",graph.id(user)).param("role",role).update();
    }
    private void scope(String user,String action) {
        seed.sql("INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),:org,:user,:action,:org,'ACTIVE',now()-interval '1 hour','synthetic scope',now(),now()) ON CONFLICT DO NOTHING")
                .param("org",graph.id("organization")).param("user",graph.id(user)).param("action",action).update();
    }
}
