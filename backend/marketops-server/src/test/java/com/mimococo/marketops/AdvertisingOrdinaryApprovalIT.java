package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingHumanDecisionService;
import com.mimococo.marketops.operationsworkflow.internal.application.ApprovalService;
import com.mimococo.marketops.operationsworkflow.internal.application.ExecutionService;
import com.mimococo.marketops.operationsworkflow.internal.application.RecommendationService;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

/** Real promoted human decisions and trusted planning in an isolated fictional protocol. */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingOrdinaryApprovalIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static final String ISSUER_PASSWORD=UUID.randomUUID().toString();
    @Autowired AdvertisingHumanDecisionService humans;
    @Autowired RecommendationService recommendations;
    @Autowired ApprovalService approvals;
    @Autowired ExecutionService execution;
    @Autowired AdvertisingResponsibilityIntake responsibilities;
    private JdbcClient seed;
    private AdvertisingR1Fixture.Graph graph;
    private AuthenticatedActor maker,ops;

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
        seed=JdbcClient.create(migration);
        graph=AdvertisingMaterialityFixture.seedUnapproved(migration);
        try(var admin=DATABASE.createConnection("")) {
            TestDatabase.enableSyntheticIdentityIssuer(admin,ISSUER_PASSWORD);
        }
        role("executorUser","MARKETPLACE_OPERATOR");
        for(String user:List.of("executorUser","verifierUser")) {
            scope(user,"ADVERTISING_VIEW");scope(user,"ADVERTISING_TASK_ACT");
        }
        scope("verifierUser","AD_BID_CHANGE_ENDORSE");
        scope("verifierUser","AD_BID_CHANGE_APPROVE");
        scope("verifierUser","ADVERTISING_DECISION_EVIDENCE_VIEW");
        seed.sql("""
            UPDATE core.ad_human_slo_profile SET staffed_coverage_enabled=true,staffed_coverage_timezone='Etc/UTC',
                staffed_coverage_start_minute=0,staffed_coverage_end_minute=1439 WHERE id=:id
            """).param("id",graph.id("humanSlo")).update();
        seed.sql("""
            INSERT INTO core.ad_reporting_calendar(id,organization_id,policy_version,scope_kind,reporting_timezone,
                daily_cut_minute,operating_days,weekly_cut_weekday,weekly_cut_minute,late_revision_horizon_hours,
                owner_user_id,reason,evidence_reference,effective_from,status,created_at)
            VALUES(gen_random_uuid(),:org,1,'ORGANIZATION','Etc/UTC',0,ARRAY[1,2,3,4,5,6,7]::smallint[],1,0,24,
                :owner,'Synthetic staffed operating calendar','fixture://ordinary-staffed',now()-interval '1 day','ACTIVE',now())
            """).param("org",graph.id("organization")).param("owner",graph.id("ownerUser")).update();
        maker=actor("executorUser",BusinessRoleCode.MARKETPLACE_OPERATOR);
        ops=actor("verifierUser",BusinessRoleCode.OPS_LEAD);
        responsibilities.ensureResponsibility(graph.id("caseId"),graph.id("calculationRun"),"MARKETPLACE_OPERATOR");
        assertThat(count("ops.ad_outcome_baseline")).as("the human service must run the trusted planner").isZero();
        assertThat(count("ops.approval_decision")).as("there is no fabricated human approval").isZero();
        assertThat(route()).isEqualTo("ORDINARY_IMPACT");
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void promotedMakerAndSameEndorsingOpsLeadApproveThroughTrustedPlannerAndCreateOneCommand() {
        var endorsed=selectAndEndorse();
        UUID baseline=selectedBaseline();
        assertThat(seed.sql("SELECT ops.ad_outcome_baseline_is_attested(:id)").param("id",baseline)
                .query(Boolean.class).single()).isTrue();
        assertThat(seed.sql("""
            SELECT EXISTS(SELECT 1 FROM ops.ad_outcome_plan_grant
                WHERE baseline_id=:id AND consumed_at IS NOT NULL)
            """).param("id",baseline).query(Boolean.class).single())
                .as("the canonical planner consumed a real independent issuer proof").isTrue();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_outcome_stage_baseline WHERE outcome_baseline_id=:id")
                .param("id",baseline).query(Integer.class).single()).isEqualTo(3);
        login(maker);
        assertThatThrownBy(()->approvals.approve(maker,graph.id("recommendation"),"Maker cannot approve their own Ordinary choice",endorsed.version()))
                .isInstanceOf(OperationRejectedException.class);
        login(ops);
        var approved=approvals.approve(ops,graph.id("recommendation"),"Exact promoted Ordinary per-command approval",endorsed.version());
        assertThat(approved.state()).isEqualTo(RecommendationState.APPROVED);
        var current=recommendations.require(graph.id("recommendation"));
        UUID command=execution.createCommand(ops,current.id(),current.version()).commandId();
        assertThat(command).isNotNull();
        assertThat(seed.sql("""
            SELECT authority.materiality_route='ORDINARY_IMPACT'
                AND authority.maker_user_id=:maker AND authority.endorser_user_id=:ops
                AND authority.final_approver_user_id=:ops AND decision.decided_by_user_id=:ops
                AND authority.outcome_baseline_id=:baseline AND cmd.outcome_baseline_id=:baseline
                AND cmd.reservation_id IS NOT NULL AND gate.gate_kind='GATE_E'
                AND gate.predecessor_gate_ev_id IS NOT NULL AND NOT gate.production_write_enabled
            FROM ops.ad_action_authorization authority
            JOIN ops.approval_decision decision ON decision.id=authority.approval_decision_id
            JOIN ops.ad_bid_command cmd ON cmd.recommendation_id=authority.recommendation_id
            JOIN ops.ad_decision_policy_bundle bundle ON bundle.id=authority.bundle_id
            JOIN ops.ad_gate_authority gate ON gate.id=bundle.gate_authority_id
            WHERE cmd.id=:command
            """).param("maker",maker.userId()).param("ops",ops.userId()).param("baseline",baseline).param("command",command)
                .query(Boolean.class).single()).isTrue();
        assertThat(count("ops.ad_bid_command")).isEqualTo(1);
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id")
                .param("id",command).query(Long.class).single()).isZero();
        assertThat(seed.sql("SELECT count(*) FROM ops.approval_decision WHERE recommendation_id=:id AND decided_by_user_id=:owner")
                .param("id",graph.id("recommendation")).param("owner",graph.id("ownerUser")).query(Long.class).single()).isZero();
    }

    @Test void fixedCriticalSalesRemainMaterialDespiteThePromotedOrdinaryEnvelope() {
        seed.sql("""
            INSERT INTO core.ad_outcome_critical_unit_rule(id,outcome_policy_id,organization_id,product_variant_id,
                store_id,reason,evidence_reference)
            SELECT gen_random_uuid(),bundle.outcome_policy_id,bundle.organization_id,:product,:store,
                'Synthetic protected sales unit','fixture://ordinary-critical-refusal'
            FROM ops.ad_decision_policy_bundle bundle WHERE bundle.id=:bundle
            """).param("product",graph.id("productVariant")).param("store",graph.id("store")).param("bundle",graph.id("bundle")).update();
        assertFixedMaterial("FIXED_CRITICAL_PROTECTED_SALES_EXPOSURE");
        var endorsed=selectAndEndorse();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_outcome_critical_unit WHERE outcome_baseline_id=:id")
                .param("id",selectedBaseline()).query(Long.class).single()).isEqualTo(1);
        refuseOpsFinal(endorsed);
    }

    @Test void regressionAfterOrdinaryEndorsementCannotUseOpsFinalApproval() {
        var endorsed=selectAndEndorse();
        seed.sql("UPDATE mart.ad_case SET protection_tier='P0',cause_code='ACTION_OUTCOME_REGRESSION' WHERE id=:id")
                .param("id",graph.id("caseId")).update();
        assertFixedMaterial("FIXED_REGRESSION_OR_UNKNOWN_EXECUTION");
        refuseOpsFinal(endorsed);
    }

    @Test void unknownEvidenceAfterOrdinaryEndorsementCannotUseOpsFinalApproval() {
        var endorsed=selectAndEndorse();
        seed.sql("UPDATE mart.ad_case SET evidence_state='UNKNOWN' WHERE id=:id")
                .param("id",graph.id("caseId")).update();
        assertFixedMaterial("FIXED_UNKNOWN_DECISION_EVIDENCE");
        refuseOpsFinal(endorsed);
    }

    @Test void immutableBundlePromotionReferenceMustResolveAtTransactionCommit() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        try(var connection=migration.getConnection()) {
            connection.setAutoCommit(false);
            try(var insert=connection.prepareStatement("""
                INSERT INTO ops.ad_decision_policy_bundle
                SELECT (jsonb_populate_record(NULL::ops.ad_decision_policy_bundle,to_jsonb(bundle)||
                    jsonb_build_object('id',?::uuid,'bundle_version',2,'status','DRAFT',
                        'ordinary_promotion_id',?::uuid,'activated_by_user_id',NULL,
                        'endorsed_by_user_id',NULL,'approved_by_user_id',NULL))).*
                FROM ops.ad_decision_policy_bundle bundle WHERE id=?
                """)) {
                insert.setObject(1,UUID.randomUUID());insert.setObject(2,UUID.randomUUID());
                insert.setObject(3,graph.id("bundle"));
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            assertThatThrownBy(connection::commit).hasMessageContaining("ad_bundle_ordinary_promotion_fk");
            connection.rollback();
        }
    }

    @Test void inactivePromotionCannotActivateOrDelegateAnOrdinaryBundle() {
        seed.sql("""
            UPDATE ops.ad_ordinary_promotion SET status='DRAFT'
            WHERE id=(SELECT ordinary_promotion_id FROM ops.ad_decision_policy_bundle WHERE id=:bundle)
            """).param("bundle",graph.id("bundle")).update();
        assertThat(route()).isEqualTo("MATERIAL_IMPACT");
        assertThatThrownBy(()->seed.sql("UPDATE ops.ad_decision_policy_bundle SET status='ACTIVE' WHERE id=:bundle")
                .param("bundle",graph.id("bundle")).update()).hasMessageContaining("ORDINARY_ROUTE_PROMOTION_NOT_RECOGNISED");
    }

    @Test void promotionWithoutOwnerEvidenceCannotBeStored() {
        assertThatThrownBy(()->seed.sql("""
            UPDATE ops.ad_ordinary_promotion SET owner_approval_reference=' '
            WHERE id=(SELECT ordinary_promotion_id FROM ops.ad_decision_policy_bundle WHERE id=:bundle)
            """).param("bundle",graph.id("bundle")).update()).hasMessageContaining("ad_ordinary_promotion_evidence_present");
    }

    private RecommendationView selectAndEndorse() {
        login(maker);
        var selected=humans.select(maker,graph.id("caseId"),graph.id("candidate"),0,"Choose the exact scoped candidate");
        assertThat(selected.state()).isEqualTo(RecommendationState.VALIDATED);
        login(ops);
        var endorsed=humans.endorse(ops,selected.id(),selected.version(),"Independent Operations scope and evidence review");
        assertThat(endorsed.state()).isEqualTo(RecommendationState.READY_FOR_REVIEW);
        return endorsed;
    }

    private void refuseOpsFinal(RecommendationView endorsed) {
        login(ops);
        assertThatThrownBy(()->approvals.approve(ops,endorsed.id(),"Ordinary delegation cannot erase a fixed Material trigger",endorsed.version()))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(count("ops.approval_decision")).isZero();
        assertThat(count("ops.ad_action_authorization")).isZero();
        assertThat(count("ops.ad_bid_command")).isZero();
    }

    private String route() {
        return seed.sql("SELECT ops.ad_materiality_assessment(:bundle,:candidate)->>'route'")
                .param("bundle",graph.id("bundle")).param("candidate",graph.id("candidate")).query(String.class).single();
    }

    private void assertFixedMaterial(String reason) {
        assertThat(route()).isEqualTo("MATERIAL_IMPACT");
        assertThat(seed.sql("SELECT jsonb_exists(ops.ad_materiality_assessment(:bundle,:candidate)->'reasons',:reason)")
                .param("bundle",graph.id("bundle")).param("candidate",graph.id("candidate")).param("reason",reason)
                .query(Boolean.class).single()).isTrue();
    }

    private UUID selectedBaseline() {
        return seed.sql("SELECT outcome_baseline_id FROM ops.ad_candidate_selection WHERE recommendation_id=:id")
                .param("id",graph.id("recommendation")).query(UUID.class).single();
    }

    private long count(String table) {
        return seed.sql("SELECT count(*) FROM "+table+" WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Long.class).single();
    }

    private AuthenticatedActor actor(String user,BusinessRoleCode role) {
        String issuer=seed.sql("SELECT issuer FROM iam.identity_provider WHERE id=:id").param("id",graph.id("provider")).query(String.class).single();
        Instant authenticated=seed.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        return new AuthenticatedActor(graph.id(user),graph.id("organization"),graph.id("provider"),issuer,"Synthetic Ordinary role",
                "a".repeat(64),"b".repeat(64),authenticated,authenticated.plusSeconds(1800),true,Set.of(role));
    }

    private void login(AuthenticatedActor actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
    }

    private void role(String user,String role) {
        seed.sql("""
            INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:user,:role,'ACTIVE',now()-interval '1 hour','Synthetic scoped person',now(),now()) ON CONFLICT DO NOTHING
            """).param("org",graph.id("organization")).param("user",graph.id(user)).param("role",role).update();
    }

    private void scope(String user,String action) {
        seed.sql("""
            INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:user,:action,:org,'ACTIVE',now()-interval '1 hour','Synthetic exact test organization',now(),now()) ON CONFLICT DO NOTHING
            """).param("org",graph.id("organization")).param("user",graph.id(user)).param("action",action).update();
    }
}
