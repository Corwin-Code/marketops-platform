package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import com.mimococo.marketops.operationsworkflow.AdvertisingTaskSloQuery;
import com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingWorkflowQueryService;
import com.mimococo.marketops.operationsworkflow.internal.application.WorkTaskService;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real Shared Task/IAM/SLO services with synthetic historical Case inputs in an isolated database. */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingResponsibilityBoundaryIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    @Autowired AdvertisingResponsibilityIntake responsibilities;
    @Autowired AdvertisingTaskSloQuery slo;
    @Autowired AdvertisingWorkflowQueryService workflows;
    @Autowired WorkTaskService tasks;
    private JdbcClient seed;
    private AdvertisingR1Fixture.Graph graph;
    private AuthenticatedActor ops;
    private Instant raisedAt;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }

    @BeforeEach void fixture() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        seed=JdbcClient.create(migration);
        graph=AdvertisingR1Fixture.seedUnapproved(migration);
        // Set only the synthetic input Case age before its first canonical responsibility intake.
        raisedAt=Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        seed.sql("UPDATE mart.ad_case SET created_at=:raised WHERE id=:id")
                .param("raised",Timestamp.from(raisedAt)).param("id",graph.id("caseId")).update();
        for(String user:List.of("executorUser","verifierUser")) {
            seed.sql("""
                INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at)
                VALUES(gen_random_uuid(),:org,:user,'MARKETPLACE_OPERATOR','ACTIVE',now()-interval '1 day','Synthetic eligible assignee',now(),now())
                ON CONFLICT DO NOTHING
                """).param("org",graph.id("organization")).param("user",graph.id(user)).update();
            grant(user,"ADVERTISING_TASK_ACT",false);grant(user,"ADVERTISING_TASK_ACT",true);
            grant(user,"ADVERTISING_VIEW",false);grant(user,"ADVERTISING_VIEW",true);
        }
        coverage(0,1439);
        seed.sql("""
            INSERT INTO core.ad_reporting_calendar(id,organization_id,policy_version,scope_kind,reporting_timezone,
                daily_cut_minute,operating_days,weekly_cut_weekday,weekly_cut_minute,late_revision_horizon_hours,
                owner_user_id,reason,evidence_reference,effective_from,status,created_at)
            VALUES(gen_random_uuid(),:org,1,'ORGANIZATION','Etc/UTC',0,ARRAY[1,2,3,4,5,6,7]::smallint[],1,0,24,
                :owner,'Explicit synthetic coverage','fixture://responsibility-coverage',now()-interval '1 day','ACTIVE',now())
            """).param("org",graph.id("organization")).param("owner",graph.id("ownerUser")).update();
        String issuer=seed.sql("SELECT issuer FROM iam.identity_provider WHERE id=:id")
                .param("id",graph.id("provider")).query(String.class).single();
        ops=new AuthenticatedActor(graph.id("verifierUser"),graph.id("organization"),graph.id("provider"),issuer,
                "Synthetic Ops", "a".repeat(64),"b".repeat(64),Instant.now(),Instant.now().plusSeconds(1800),true,
                Set.of(BusinessRoleCode.OPS_LEAD,BusinessRoleCode.MARKETPLACE_OPERATOR));
    }

    @Test void qualifiedReassignmentPreservesCaseTaskAgeAndEveryPriorSloEvent() {
        UUID task=intake();
        tasks.assign(ops,task,graph.id("executorUser"),tasks.find(task).orElseThrow().version());
        tasks.recordView(ops,task);tasks.acknowledge(ops,task);
        var before=tasks.find(task).orElseThrow();
        var status=slo.statusForCase(graph.id("caseId")).orElseThrow();
        var journal=tasks.journal(task);
        String responsibility=binding();
        String originalCase=seed.sql("SELECT to_jsonb(c)::text FROM mart.ad_case c WHERE id=:id")
                .param("id",graph.id("caseId")).query(String.class).single();
        tasks.assign(ops,task,graph.id("verifierUser"),before.version());
        var after=tasks.find(task).orElseThrow();
        var next=slo.statusForCase(graph.id("caseId")).orElseThrow();
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.recommendationId()).isEqualTo(before.recommendationId());
        assertThat(after.createdAt()).isEqualTo(raisedAt).isEqualTo(before.createdAt());
        assertThat(after.dueAt()).isEqualTo(before.dueAt());
        assertThat(after.assigneeUserId()).isEqualTo(graph.id("verifierUser"));
        assertThat(after.version()).isEqualTo(before.version()+1);
        assertThat(binding()).isEqualTo(responsibility);
        assertThat(seed.sql("SELECT to_jsonb(c)::text FROM mart.ad_case c WHERE id=:id")
                .param("id",graph.id("caseId")).query(String.class).single()).isEqualTo(originalCase);
        assertThat(next.acknowledgementDueAt()).isEqualTo(status.acknowledgementDueAt());
        assertThat(next.actionDueAt()).isEqualTo(status.actionDueAt());
        assertThat(next.escalationDueAt()).isEqualTo(status.escalationDueAt());
        assertThat(next.acknowledgedAt()).isEqualTo(status.acknowledgedAt()).isNotNull();
        assertThat(next.firstAttributableActionAt()).isNull();
        assertThat(next.wallClockExposureAgeSeconds()).isGreaterThanOrEqualTo(status.wallClockExposureAgeSeconds());
        var updated=tasks.journal(task);
        assertThat(updated.subList(0,journal.size())).isEqualTo(journal);
        assertThat(updated).hasSize(journal.size()+1);
        var handover=updated.getLast();
        assertThat(handover.eventKind()).isEqualTo("REASSIGNED");
        assertThat(handover.fromAssigneeUserId()).isEqualTo(graph.id("executorUser"));
        assertThat(handover.toAssigneeUserId()).isEqualTo(graph.id("verifierUser"));
        assertThat(handover.actorUserId()).isEqualTo(ops.userId());
        assertThat(handover.lineageKey()).isEqualTo("advertising-case:"+graph.id("caseId"));
        assertThat(handover.satisfiesActionStage()).isFalse();
        assertThat(intake()).isEqualTo(task);
        assertThat(tasks.journal(task)).filteredOn(e->e.eventKind().equals("RAISED")).hasSize(1);
    }

    @Test void reassignmentRequiresBothTheCauseRoleAndEveryAffectedResourceGrant() {
        UUID task=intake();
        tasks.assign(ops,task,graph.id("executorUser"),tasks.find(task).orElseThrow().version());
        var before=tasks.find(task).orElseThrow();
        var history=tasks.journal(task);
        assertBusinessRefusal(()->tasks.assign(ops,task,graph.id("ownerUser"),before.version()));
        seed.sql("""
            UPDATE iam.user_scope_grant SET status='REVOKED',reason='Synthetic partial scope fault'
            WHERE user_id=:user AND action_code='ADVERTISING_TASK_ACT' AND product_variant_ref_id=:product
            """).param("user",graph.id("verifierUser")).param("product",graph.id("productVariant")).update();
        // The caller is still complete-scope executor; only the new assignee's product grant is missing.
        AuthenticatedActor caller=new AuthenticatedActor(graph.id("executorUser"),ops.organizationId(),ops.identityProviderId(),
                ops.issuer(),"Synthetic Operator","a".repeat(64),"b".repeat(64),Instant.now(),Instant.now().plusSeconds(1800),true,
                Set.of(BusinessRoleCode.MARKETPLACE_OPERATOR));
        assertBusinessRefusal(()->tasks.assign(caller,task,graph.id("verifierUser"),before.version()));
        assertThat(tasks.find(task).orElseThrow()).isEqualTo(before);
        assertThat(tasks.journal(task)).isEqualTo(history);
        grant("verifierUser","ADVERTISING_TASK_ACT",true);
        tasks.assign(caller,task,graph.id("verifierUser"),before.version());
        assertThat(tasks.find(task).orElseThrow().assigneeUserId()).isEqualTo(graph.id("verifierUser"));
    }

    @ParameterizedTest
    @ValueSource(strings={"ACKNOWLEDGEMENT","ACTION","ESCALATION","COVERAGE","TIMEZONE"})
    void noProtectionResponseAxisCanBecomeWeakerThanOptimization(String axis) {
        UUID optimization=UUID.randomUUID();
        seed.sql("""
            INSERT INTO core.ad_human_slo_profile
            SELECT (jsonb_populate_record(NULL::core.ad_human_slo_profile,to_jsonb(p)||
                jsonb_build_object('id',CAST(:id AS uuid),'lane','OPTIMIZATION',
                  'acknowledgement_minutes',30,'action_minutes',90,'escalation_minutes',180))).*
            FROM core.ad_human_slo_profile p WHERE p.id=:source
            """).param("id",optimization).param("source",graph.id("humanSlo")).update();
        assertThat(seed.sql("SELECT count(*) FROM core.ad_human_slo_profile WHERE organization_id=:org AND status='ACTIVE'")
                .param("org",graph.id("organization")).query(Long.class).single()).isEqualTo(2);
        String field=switch(axis) {
            case "ACKNOWLEDGEMENT" -> "acknowledgement_minutes=31";
            case "ACTION" -> "action_minutes=91";
            case "ESCALATION" -> "escalation_minutes=181";
            case "COVERAGE" -> "staffed_coverage_start_minute=30";
            case "TIMEZONE" -> "staffed_coverage_timezone='Europe/Moscow'";
            default -> throw new AssertionError(axis);
        };
        Throwable refusal=catchThrowable(()->seed.sql("UPDATE core.ad_human_slo_profile SET "+field+" WHERE id=:id")
                .param("id",graph.id("humanSlo")).update());
        assertThat(refusal).isNotNull();
        while(refusal.getCause()!=null) refusal=refusal.getCause();
        assertThat(refusal).isInstanceOf(SQLException.class).hasMessageContaining("Protection response and coverage cannot be weaker than Optimization");
        assertThat(((SQLException)refusal).getSQLState()).isEqualTo("23514");
        assertThat(intake()).isNotNull();
        assertThat(slo.statusForCase(graph.id("caseId")).orElseThrow().actionDueAt()).isNotNull();
    }

    @Test void differentCauseOwnersKeepSeparateTasksWithExplicitCanonicalObjectLinks() {
        UUID protectionTask=intake();
        UUID repairCase=UUID.randomUUID(),repairProfile=UUID.randomUUID();
        seed.sql("""
            INSERT INTO mart.ad_case
            SELECT (jsonb_populate_record(NULL::mart.ad_case,to_jsonb(c)||
                jsonb_build_object('id',CAST(:id AS uuid),'case_key',:key,'lane','DATA_REPAIR',
                  'cause_code','PROFIT_ECONOMICS_BLOCKED','protection_tier',NULL,
                  'contribution_profit_state','NOT_AVAILABLE','contribution_profit_amount',NULL))).*
            FROM mart.ad_case c WHERE c.id=:source
            """).param("id",repairCase).param("key",UUID.randomUUID().toString().replace("-","").repeat(2))
                .param("source",graph.id("caseId")).update();
        seed.sql("""
            INSERT INTO core.ad_human_slo_profile
            SELECT (jsonb_populate_record(NULL::core.ad_human_slo_profile,to_jsonb(p)||
                jsonb_build_object('id',CAST(:id AS uuid),'lane','DATA_REPAIR'))).*
            FROM core.ad_human_slo_profile p WHERE p.id=:source
            """).param("id",repairProfile).param("source",graph.id("humanSlo")).update();
        String role=com.mimococo.marketops.advertisingefficiency.AdvertisingCause.PROFIT_ECONOMICS_BLOCKED
                .accountableRole().name();
        assertThat(role).isEqualTo("FINANCE_ANALYST");
        UUID repairTask=responsibilities.ensureResponsibility(repairCase,graph.id("calculationRun"),role);
        assertThat(repairTask).isNotEqualTo(protectionTask);
        assertThat(responsibilities.ensureResponsibility(repairCase,graph.id("calculationRun"),role)).isEqualTo(repairTask);
        assertThat(intake()).isEqualTo(protectionTask);
        assertThat(seed.sql("""
            SELECT count(*)=2 AND count(DISTINCT b.task_id)=2 AND count(DISTINCT b.recommendation_id)=2
                AND bool_and(r.subject_kind='AD_NATIVE_OBJECT' AND r.subject_id=:object
                  AND r.subject_id=c.ad_native_object_id AND r.organization_id=b.organization_id
                  AND r.store_id=c.store_id AND r.action_kind='ADVERTISING_REVIEW'
                  AND t.recommendation_id=b.recommendation_id
                  AND r.proposed_parameters->>'caseId'=c.id::text
                  AND r.proposed_parameters->>'cause'=c.cause_code
                  AND r.expected_effect->>'accountableRole'=b.owner_role_code
                  AND ((c.id=:protection AND b.task_id=:protectionTask AND b.owner_role_code='MARKETPLACE_OPERATOR'
                      AND b.slo_profile_id=:protectionProfile)
                    OR (c.id=:repair AND b.task_id=:repairTask AND b.owner_role_code='FINANCE_ANALYST'
                      AND b.slo_profile_id=:repairProfile)))
            FROM ops.ad_case_responsibility b JOIN mart.ad_case c ON c.id=b.case_id
            JOIN ops.work_task t ON t.id=b.task_id JOIN ops.recommendation r ON r.id=b.recommendation_id
            WHERE b.organization_id=:org
            """).param("object",graph.id("object")).param("org",graph.id("organization"))
                .param("protection",graph.id("caseId")).param("protectionTask",protectionTask)
                .param("protectionProfile",graph.id("humanSlo")).param("repair",repairCase)
                .param("repairTask",repairTask).param("repairProfile",repairProfile).query(Boolean.class).single()).isTrue();
        assertThat(tasks.journal(protectionTask)).filteredOn(e->e.eventKind().equals("RAISED"))
                .singleElement().satisfies(e->{
                    assertThat(e.actorRoleCode()).isEqualTo("MARKETPLACE_OPERATOR");
                    assertThat(e.lineageKey()).isEqualTo("advertising-case:"+graph.id("caseId"));
                });
        assertThat(tasks.journal(repairTask)).filteredOn(e->e.eventKind().equals("RAISED"))
                .singleElement().satisfies(e->{
                    assertThat(e.actorRoleCode()).isEqualTo("FINANCE_ANALYST");
                    assertThat(e.lineageKey()).isEqualTo("advertising-case:"+repairCase);
                });
        assertThat(slo.statusForCase(repairCase).orElseThrow().actionDueAt()).isNotNull();
    }

    @Test void unstaffedProtectionShowsOngoingHarmAgeAndTheNextStaffedResponseTogether() {
        var now=Instant.now().atZone(ZoneOffset.UTC);
        int start=(now.getHour()*60+now.getMinute()+120)%1440;
        coverage(start,(start+60)%1440);
        UUID task=intake();
        var view=workflows.workflow(ops,graph.id("caseId"));
        assertThat(view.taskId()).isEqualTo(task);
        assertThat(view.coverageState()).isEqualTo("OUT_OF_COVERAGE_ACTIVE_HARM");
        assertThat(view.slo().coverageState()).isEqualTo(view.coverageState());
        assertThat(view.nextStaffedResponseAt()).isAfter(Instant.now());
        assertThat(view.slo().nextStaffedResponseAt()).isEqualTo(view.nextStaffedResponseAt());
        assertThat(view.slo().wallClockExposureAgeSeconds()).isGreaterThanOrEqualTo(7200);
        assertThat(view.firstRaisedAt()).isEqualTo(raisedAt);
        assertThat(view.acknowledgementDueAt()).isNotNull();
        assertThat(view.actionDueAt()).isAfterOrEqualTo(view.nextStaffedResponseAt());
        assertThat(view.slo().actionPaused()).isFalse();
        assertThat(view.operatingDisposition()).isEqualTo("ACTION_REQUIRED");
        assertThat(view.taskState()).isEqualTo("OPEN");
        assertThat(view.allowedActions()).contains("TASK_ACKNOWLEDGE","TASK_ASSIGN");
        assertThat(seed.sql("SELECT lane='PROTECTION' AND official_spend_state='AVAILABLE' AND official_spend_amount>0 AND superseded_at IS NULL FROM mart.ad_case WHERE id=:id")
                .param("id",graph.id("caseId")).query(Boolean.class).single()).isTrue();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Long.class).single()).isZero();
    }

    private UUID intake() { return responsibilities.ensureResponsibility(graph.id("caseId"),graph.id("calculationRun"),"MARKETPLACE_OPERATOR"); }
    private String binding() { return seed.sql("SELECT to_jsonb(b)::text FROM ops.ad_case_responsibility b WHERE case_id=:id")
            .param("id",graph.id("caseId")).query(String.class).single(); }
    private void coverage(int start,int end) { seed.sql("""
            UPDATE core.ad_human_slo_profile SET staffed_coverage_enabled=true,staffed_coverage_timezone='Etc/UTC',
                staffed_coverage_start_minute=:start,staffed_coverage_end_minute=:end WHERE id=:id
            """).param("start",start).param("end",end).param("id",graph.id("humanSlo")).update(); }
    private void grant(String user,String action,boolean product) { seed.sql("""
            INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,store_ref_id,product_variant_ref_id,
                effective_from,status,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:user,:action,:store,:product,now()-interval '1 day','ACTIVE','Synthetic exact resource scope',now(),now())
            ON CONFLICT DO NOTHING
            """).param("org",graph.id("organization")).param("user",graph.id(user)).param("action",action)
            .param("store",product?null:graph.id("store")).param("product",product?graph.id("productVariant"):null).update(); }
    private static void assertBusinessRefusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable refusal=catchThrowable(action);
        assertThat(refusal).isInstanceOf(OperationRejectedException.class);
        assertThat(((OperationRejectedException)refusal).errorCode()).isEqualTo(ErrorCode.ACTION_NOT_PERMITTED);
    }
}
