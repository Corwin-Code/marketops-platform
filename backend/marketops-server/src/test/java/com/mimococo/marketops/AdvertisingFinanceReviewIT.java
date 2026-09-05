package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.operationsworkflow.AdvertisingOutcomeReviewIntake;
import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import com.mimococo.marketops.operationsworkflow.internal.application.WorkTaskService;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.Timestamp;
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

/** Canonical observation fixtures exercise real Finance Task/IAM boundaries, without a Provider call. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingFinanceReviewIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    @Autowired DataSource application;
    @Autowired AdvertisingOutcomeReviewIntake reviews;
    @Autowired AdvertisingResponsibilityIntake responsibilities;
    @Autowired WorkTaskService tasks;
    private JdbcClient seed;
    private AdvertisingR1Fixture.Graph graph;
    private UUID command,primary,financeId;
    private Instant landed;
    private AuthenticatedActor finance;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DATABASE::getJdbcUrl);
        r.add("spring.datasource.username",TestDatabase::applicationRole);
        r.add("spring.datasource.password",TestDatabase::applicationPassword);
        r.add("spring.flyway.user",TestDatabase::migrationRole);
        r.add("spring.flyway.password",TestDatabase::migrationPassword);
    }
    @BeforeEach void fixture() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);graph=AdvertisingR1Fixture.seed(migration);
        primary=responsibilities.ensureResponsibility(graph.id("caseId"),graph.id("calculationRun"),"MARKETPLACE_OPERATOR");
        try(var app=application.getConnection()) {
            app.setAutoCommit(false);
            AdvertisingR1Fixture.seal(app,graph,AdvertisingR1Fixture.proof(admin,app,graph,graph.id("ownerUser"),null,
                    graph.id("recommendation"),graph.id("approval")));
            command=AdvertisingR1Fixture.createCommand(app,graph);app.commit();
        }
        landed=seed.sql("SELECT clock_timestamp()+interval '1 second'").query(Timestamp.class).single().toInstant();
        landedReadback();
        financeId=UUID.randomUUID();
        seed.sql("""
            INSERT INTO iam.user_account(id,organization_id,identity_provider_id,external_subject,display_name,status,
                credentials_valid_from,created_at,updated_at)
            VALUES(:id,:org,:provider,:subject,'Synthetic Finance person','ACTIVE',now()-interval '1 minute',now(),now())
            """).param("id",financeId).param("org",graph.id("organization")).param("provider",graph.id("provider"))
                .param("subject","finance-"+financeId).update();
        seed.sql("""
            INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:user,'FINANCE_ANALYST','ACTIVE',now()-interval '1 minute','Synthetic Finance',now(),now())
            """).param("org",graph.id("organization")).param("user",financeId).update();
        String issuer=seed.sql("SELECT issuer FROM iam.identity_provider WHERE id=:id").param("id",graph.id("provider")).query(String.class).single();
        Instant at=seed.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        finance=new AuthenticatedActor(financeId,graph.id("organization"),graph.id("provider"),issuer,"Synthetic Finance person",
                "a".repeat(64),"b".repeat(64),at,at.plusSeconds(1800),true,Set.of(BusinessRoleCode.FINANCE_ANALYST));
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void settledContradictionCreatesFinanceReviewWithoutInventingRegressionOrReplacingHistory() {
        UUID operational=observation("RETAINED","IMPROVED","VERIFIED_EFFICIENCY_SUCCESS",null);
        UUID settled=observation("SETTLED","UNCHANGED","NO_MATERIAL_IMPROVEMENT",null);
        UUID task=reviews.record(settled);
        assertThat(task).isNotNull().isNotEqualTo(primary);
        assertThat(reviews.record(settled)).isEqualTo(task);
        assertThat(seed.sql("SELECT required_role_code FROM ops.ad_outcome_review_responsibility WHERE task_id=:id")
                .param("id",task).query(String.class).single()).isEqualTo("FINANCE_ANALYST");
        assertThat(count("ops.ad_outcome_review_observation","task_id",task)).isEqualTo(1);
        assertThat(seed.sql("SELECT count(*) FROM ops.work_task_event WHERE task_id=:id AND event_kind='OUTCOME_OBSERVED'")
                .param("id",task).query(Integer.class).single()).isEqualTo(1);
        assertThat(seed.sql("SELECT verdict FROM ops.ad_outcome_observation WHERE id=:id").param("id",operational).query(String.class).single())
                .isEqualTo("IMPROVED");
        assertThat(count("ops.ad_containment","organization_id",graph.id("organization"))).isZero();
        assertThat(seed.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",graph.id("gate")).query(Boolean.class).single()).isFalse();
    }

    @Test void settledRevisionsReopenTheSameFinanceTaskAndKeepBothTaskAges() {
        UUID first=observation("SETTLED","REGRESSED","REGRESSION",null);
        UUID task=reviews.record(first);
        Instant age=tasks.find(task).orElseThrow().createdAt(),primaryAge=tasks.find(primary).orElseThrow().createdAt();
        seed.sql("UPDATE ops.work_task SET state='DONE',closed_at=now(),closure_reason='Synthetic historical reviewed task' WHERE id=:id")
                .param("id",task).update();
        UUID revision=observation("SETTLED_REVISED","REGRESSED","REGRESSION",first);
        assertThat(reviews.record(revision)).isEqualTo(task);
        assertThat(reviews.record(first)).isNull();
        assertThat(tasks.find(task).orElseThrow().createdAt()).isEqualTo(age);
        assertThat(tasks.find(primary).orElseThrow().createdAt()).isEqualTo(primaryAge);
        assertThat(tasks.find(task).orElseThrow().state()).isEqualTo("OPEN");
        assertThat(count("ops.ad_outcome_review_observation","task_id",task)).isEqualTo(2);
    }

    @Test void earlySafetyAndUnknownSettledEvidenceCannotForgeFinanceResponsibility() {
        UUID early=observation("OPERATIONAL","UNCHANGED","UNRESOLVED",null);
        UUID settled=observation("SETTLED","INDETERMINATE","UNRESOLVED",null);
        assertThat(reviews.record(early)).isNull();assertThat(reviews.record(settled)).isNull();
        UUID task=UUID.randomUUID();
        assertThatThrownBy(()->JdbcClient.create(application).sql("""
            INSERT INTO ops.ad_outcome_review_responsibility(task_id,organization_id,case_id,primary_task_id,
                outcome_baseline_id,action_id,action_kind,first_observation_id,first_raised_at)
            VALUES(:task,:org,:case,:primary,:baseline,:command,'COMMAND',:observation,now())
            """).param("task",task).param("org",graph.id("organization")).param("case",graph.id("caseId"))
                .param("primary",primary).param("baseline",graph.id("baseline")).param("command",command)
                .param("observation",settled).update()).hasMessageContaining("current canonical Settled contradiction required");
    }

    @Test void applicationCannotRebindThePrimaryAdvertisingTaskAsItsFinanceReview() {
        UUID observation=observation("SETTLED","REGRESSED","REGRESSION",null);
        assertThatThrownBy(()->JdbcClient.create(application).sql("""
            INSERT INTO ops.ad_outcome_review_responsibility(task_id,organization_id,case_id,primary_task_id,
                outcome_baseline_id,action_id,action_kind,first_observation_id,first_raised_at)
            SELECT primary_task_id,organization_id,case_id,primary_task_id,outcome_baseline_id,
                action_id,action_kind,:id,observed_at FROM ops.ad_settled_review_context(:id)
            """).param("id",observation).update()).hasMessageContaining("ad_review_task_distinct_ck");
        assertThat(count("ops.ad_case_responsibility","task_id",primary)).isEqualTo(1);
        assertThat(count("ops.ad_outcome_review_responsibility","task_id",primary)).isZero();
    }

    @Test void financeAssignmentRequiresItsRoleAndTheCompleteFrozenAffectedScope() {
        UUID task=reviews.record(observation("SETTLED","REGRESSED","REGRESSION",null));
        grant(false);
        login();
        assertThatThrownBy(()->tasks.acknowledge(finance,task)).isInstanceOf(OperationRejectedException.class);
        grant(true);
        long version=tasks.find(task).orElseThrow().version();
        assertThatThrownBy(()->tasks.assign(finance,task,graph.id("verifierUser"),version)).isInstanceOf(OperationRejectedException.class);
        tasks.assign(finance,task,financeId,version);
        assertThat(tasks.find(task).orElseThrow().assigneeUserId()).isEqualTo(financeId);
    }

    @Test void reviewClosureRequiresCurrentConclusiveSettledReconciliation() {
        UUID regression=observation("SETTLED","REGRESSED","REGRESSION",null);
        UUID task=reviews.record(regression);grant(true);login();
        tasks.assign(finance,task,financeId,tasks.find(task).orElseThrow().version());
        assertThatThrownBy(()->tasks.close(finance,task,true,"Unresolved contradiction cannot close",tasks.find(task).orElseThrow().version()))
                .isInstanceOf(OperationRejectedException.class);
        observation("SETTLED_REVISED","IMPROVED","VERIFIED_EFFICIENCY_SUCCESS",regression);
        tasks.close(finance,task,true,"Finance reviewed the current canonical Settled reconciliation",tasks.find(task).orElseThrow().version());
        assertThat(tasks.find(task).orElseThrow().state()).isEqualTo("DONE");
        assertThat(tasks.find(primary).orElseThrow().state()).isNotEqualTo("DONE");
    }

    private void grant(boolean complete) {
        for(String action:List.of("ADVERTISING_VIEW","ADVERTISING_TASK_ACT")) {
            seed.sql("""
                INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,store_ref_id,
                    status,effective_from,reason,created_at,updated_at)
                VALUES(gen_random_uuid(),:org,:user,:action,:full,:store,'ACTIVE',now()-interval '1 minute','Synthetic scoped Finance',now(),now())
                ON CONFLICT DO NOTHING
                """).param("org",graph.id("organization")).param("user",financeId).param("action",action)
                    .param("full",complete?graph.id("organization"):null).param("store",complete?null:graph.id("store")).update();
        }
    }
    private void login() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(finance,null,List.of())); }
    private int count(String table,String column,UUID id) {
        return seed.sql("SELECT count(*) FROM "+table+" WHERE "+column+"=:id").param("id",id).query(Integer.class).single();
    }
    private UUID observation(String stage,String verdict,String dual,UUID prior) {
        UUID id=UUID.randomUUID();
        String base=stage.replace("_REVISED","");
        seed.sql("""
            INSERT INTO ops.ad_outcome_observation(id,organization_id,command_id,ad_native_object_id,affected_set_digest,
                outcome_policy_id,outcome_policy_version,outcome_stage,revision_no,supersedes_observation_id,adjustment_reason,
                window_starts_at,window_ends_at,baseline_metric_state,baseline_metric_value,observed_metric_state,
                observed_metric_value,observed_traffic_count,settled_coverage_ratio,verdict,guard_state,unresolved_reason_codes,
                evaluated_at,input_digest,correlation_id)
            SELECT :id,b.organization_id,:command,b.ad_native_object_id,b.affected_set_digest,b.outcome_policy_id,
                b.outcome_policy_version,:stage,coalesce((SELECT revision_no+1 FROM ops.ad_outcome_observation WHERE id=:prior),1),
                :prior,CASE WHEN CAST(:prior AS uuid) IS NULL THEN NULL ELSE 'Canonical synthetic settlement correction' END,
                CAST(:landed AS timestamptz)+make_interval(mins=>(b.plan_snapshot->>'observationStartsMinutes')::integer),
                CAST(:landed AS timestamptz)+make_interval(mins=>(b.plan_snapshot->>'observationStartsMinutes')::integer,hours=>s.window_hours),
                s.snapshot#>>'{profit,absoluteProfit,valueState}',(s.snapshot#>>'{profit,absoluteProfit,value}')::numeric,
                'NOT_AVAILABLE',NULL,100,1,:verdict,CASE WHEN :base='OPERATIONAL' THEN 'NOT_APPLICABLE' ELSE 'SATISFIED' END,
                CASE WHEN :verdict IN('INDETERMINATE','NOT_YET_EVALUABLE')
                    THEN ARRAY['SYNTHETIC_FINANCIAL_EVIDENCE_UNRESOLVED'] ELSE CAST('{}' AS text[]) END,
                CAST(:landed AS timestamptz)+make_interval(mins=>(b.plan_snapshot->>'observationStartsMinutes')::integer,
                    hours=>s.window_hours)+interval '1 minute'+CASE WHEN CAST(:prior AS uuid) IS NULL THEN interval '0' ELSE interval '1 minute' END,
                repeat('c',64),'fictional-finance-review'
            FROM ops.ad_outcome_baseline b JOIN ops.ad_outcome_stage_baseline s ON s.outcome_baseline_id=b.id
            WHERE b.id=:baseline AND s.stage=:base
            """).param("id",id).param("command",command).param("stage",stage).param("base",base).param("prior",prior)
                .param("landed",Timestamp.from(landed)).param("verdict",verdict).param("baseline",graph.id("baseline")).update();
        seed.sql("""
            INSERT INTO ops.ad_outcome_axes(observation_id,outcome_baseline_id,dual_axis_verdict,sales_preservation_verdict,input_snapshot)
            VALUES(:id,:baseline,:dual,'PRESERVED','{}')
            """).param("id",id).param("baseline",graph.id("baseline")).param("dual",dual).update();
        return id;
    }
    private void landedReadback() {
        UUID attempt=UUID.randomUUID(),raw=UUID.randomUUID(),content=UUID.randomUUID();
        seed.sql("""
            INSERT INTO ops.ad_bid_command_attempt(id,command_id,attempt_no,purpose,fence_token,lease_owner,started_at,completed_at,
                outcome_class,correlation_id,request_digest,operation_snapshot)
            VALUES(:id,:command,1,'READBACK',1,'fictional-finance',:at,:at,'ACCEPTED','fictional-finance',repeat('a',64),'{}')
            """).param("id",attempt).param("command",command).param("at",Timestamp.from(landed)).update();
        seed.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',:hash,2,'object-ref://fictional/finance')")
                .param("id",content).param("hash",com.mimococo.marketops.shared.Digest.ofText(content.toString())).update();
        seed.sql("""
            INSERT INTO raw.ad_bid_response_observation(id,command_id,attempt_id,raw_content_id,request_digest,http_status,
                response_headers,evidence_class,response_complete,observed_bid,observed_currency,observed_unit,observed_at,correlation_id)
            VALUES(:id,:command,:attempt,:content,repeat('a',64),200,'{}','PROTOCOL_FIXTURE',true,20,'RUB','CURRENCY_MAJOR',:at,'fictional-finance')
            """).param("id",raw).param("command",command).param("attempt",attempt).param("content",content).param("at",Timestamp.from(landed)).update();
        seed.sql("INSERT INTO ops.ad_bid_command_readback VALUES(gen_random_uuid(),:command,:attempt,:at,20,'RUB','CURRENCY_MAJOR','MATCHES_TARGET',:raw,'fictional-finance')")
                .param("command",command).param("attempt",attempt).param("at",Timestamp.from(landed)).param("raw",raw).update();
        seed.sql("UPDATE ops.ad_bid_command SET state='READBACK_MATCHED',terminal_at=:at WHERE id=:id")
                .param("id",command).param("at",Timestamp.from(landed)).update();
    }
}
