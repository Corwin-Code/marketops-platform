package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdvertisingSealedAuthorityIT {
    static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    static DataSource migration,application,admin;
    static JdbcClient seed;
    AdvertisingR1Fixture.Graph graph;

    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    @BeforeEach void fixture() throws Exception { graph=AdvertisingR1Fixture.seed(migration); }
    Connection transaction() throws SQLException { var connection=application.getConnection(); connection.setAutoCommit(false); return connection; }
    String proof(Connection app, UUID actor) throws Exception {
        return AdvertisingR1Fixture.proof(admin,app,graph,actor,null,graph.id("recommendation"),graph.id("approval"));
    }

    @Test void realApplicationCreatorUsesExactImmutableApprovalAndMinimumFrozenExpiry() throws Exception {
        try (var app=transaction()) {
            UUID authorization=AdvertisingR1Fixture.seal(app,graph,proof(app,graph.id("ownerUser")));
            UUID command=AdvertisingR1Fixture.createCommand(app,graph);
            assertThat(AdvertisingR1Fixture.createCommand(app,graph)).isEqualTo(command);
            app.commit();
            assertThat(seed.sql("SELECT outcome_baseline_id FROM ops.ad_bid_command WHERE id=:id").param("id",command).query(UUID.class).single())
                    .isEqualTo(graph.id("baseline"));
            assertThat(seed.sql("SELECT extract(epoch FROM (expires_at-final_approved_at)) FROM ops.ad_action_authorization WHERE id=:id")
                    .param("id",authorization).query(Integer.class).single()).isEqualTo(18*60);
            try (var query=app.prepareStatement("SELECT ops.evaluate_ad_bid_write_gate(?)")) {
                query.setObject(1,command);
                try(var row=query.executeQuery()) {
                    row.next(); assertThat((String[])row.getArray(1).getArray())
                            .contains("GLOBAL_SWITCH_DISABLED","CAPABILITY_SWITCH_DISABLED","GUARDRAIL_NOT_PASSED");
                }
            }
            assertThat(seed.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                    .param("id",graph.id("gate")).query(Boolean.class).single()).isFalse();
        }
    }

    @Test void syntheticIssuerKeepsNativeClockAndExactBindingsForFinalAndControlGrants() throws Exception {
        for (String purpose : new String[]{null, "CONTAINMENT_STOP"}) {
            try (var app = transaction(); var administrator = admin.getConnection()) {
                int pid;
                long transactionId;
                try (var query = app.createStatement(); var row = query.executeQuery("SELECT pg_backend_pid(),txid_current()")) {
                    row.next(); pid = row.getInt(1); transactionId = row.getLong(2);
                }
                String issued = AdvertisingR1Fixture.proof(admin, app, graph, graph.id("ownerUser"),
                        purpose, graph.id("recommendation"), graph.id("approval"));
                try (var query = administrator.prepareStatement("""
                        SELECT authenticated_at, step_up_valid_until, issued_at, expires_at,
                               backend_pid, transaction_id, actor_user_id, organization_id,
                               identity_provider_id, recommendation_id, approval_decision_id,
                               purpose, consumed_at
                          FROM iam.ad_invocation_grant
                         WHERE proof_hash=encode(sha256(convert_to(?,'UTF8')),'hex')
                        """)) {
                    query.setString(1, issued);
                    try (var row = query.executeQuery()) {
                        assertThat(row.next()).isTrue();
                        var authenticated = row.getTimestamp(1).toInstant();
                        var stepUp = row.getTimestamp(2).toInstant();
                        var issuedAt = row.getTimestamp(3).toInstant();
                        var expires = row.getTimestamp(4).toInstant();
                        assertThat(java.time.Duration.between(authenticated, stepUp)).isEqualTo(java.time.Duration.ofHours(1));
                        assertThat(authenticated).isBeforeOrEqualTo(issuedAt);
                        assertThat(expires).isAfter(issuedAt).isBeforeOrEqualTo(issuedAt.plusSeconds(30));
                        assertThat(expires).isBeforeOrEqualTo(stepUp);
                        assertThat(row.getInt(5)).isEqualTo(pid);
                        assertThat(row.getLong(6)).isEqualTo(transactionId);
                        assertThat(row.getObject(7, UUID.class)).isEqualTo(graph.id("ownerUser"));
                        assertThat(row.getObject(8, UUID.class)).isEqualTo(graph.id("organization"));
                        assertThat(row.getObject(9, UUID.class)).isEqualTo(graph.id("provider"));
                        assertThat(row.getObject(10, UUID.class)).isEqualTo(graph.id("recommendation"));
                        assertThat(row.getObject(11, UUID.class)).isEqualTo(graph.id("approval"));
                        assertThat(row.getString(12)).isEqualTo(purpose == null ? "FINAL_APPROVAL" : purpose);
                        assertThat(row.getTimestamp(13)).isNull();
                        assertThat(row.next()).isFalse();
                    }
                }
                app.rollback();
            }
        }
    }

    @Test void actualIssuerStillRejectsFutureAuthenticationAndExpiredStepUp() throws Exception {
        for (boolean futureAuthentication : new boolean[]{true, false}) {
            String digest = UUID.randomUUID().toString().replace("-", "").repeat(2);
            try (var app = transaction(); var identity = admin.getConnection()) {
                try (var role = identity.createStatement()) { role.execute("SET ROLE marketops_identity_issuer"); }
                int pid;
                long transactionId;
                try (var query = app.createStatement(); var row = query.executeQuery("SELECT pg_backend_pid(),txid_current()")) {
                    row.next(); pid = row.getInt(1); transactionId = row.getLong(2);
                }
                try (var query = identity.prepareStatement("""
                        WITH auth_clock AS MATERIALIZED (SELECT clock_timestamp() AS at)
                        SELECT iam.issue_ad_invocation_grant(?,?,?,?,?,?,
                               auth_clock.at + CASE WHEN ? THEN interval '1 hour' ELSE interval '-1 minute' END,
                               auth_clock.at + CASE WHEN ? THEN interval '2 hours' ELSE interval '-1 second' END,
                               ?,?,?,?) FROM auth_clock
                        """)) {
                    query.setString(1, digest); query.setObject(2, graph.id("ownerUser"));
                    query.setObject(3, graph.id("organization")); query.setObject(4, graph.id("provider"));
                    query.setString(5, "a".repeat(64)); query.setString(6, "b".repeat(64));
                    query.setBoolean(7, futureAuthentication); query.setBoolean(8, futureAuthentication);
                    query.setObject(9, graph.id("recommendation")); query.setObject(10, graph.id("approval"));
                    query.setInt(11, pid); query.setLong(12, transactionId);
                    assertThatThrownBy(query::execute).isInstanceOfSatisfying(SQLException.class, failure -> {
                        assertThat(failure.getSQLState()).isEqualTo("MO092");
                        assertThat(failure.getMessage()).contains("trusted current authentication required");
                    });
                }
                app.rollback();
            }
            assertThat(JdbcClient.create(admin).sql("SELECT count(*) FROM iam.ad_invocation_grant WHERE proof_hash=:digest")
                    .param("digest", digest).query(Integer.class).single()).isZero();
        }
    }

    @Test void pendingApprovalCannotTakeAnExposureReservation() throws Exception {
        try(var app=transaction()) {
            assertThatThrownBy(()->AdvertisingR1Fixture.reserve(app,graph)).hasMessageContaining("exact intervention");
            app.rollback();
        }
    }

    @Test void applicationCannotMintProofOrRestoreTheRemovedActorParameterCreator() {
        JdbcClient app=JdbcClient.create(application);
        for(String table:List.of("iam.ad_invocation_grant","ops.ad_action_authorization","ops.ad_authority_invalidation",
                "ops.ad_compensation_authorization","ops.ad_gate_authority","platform.ad_write_credential_attestation")) {
            for(String privilege:List.of("INSERT","UPDATE","DELETE")) {
                assertThat(app.sql("SELECT has_table_privilege(current_user,:table,:privilege)")
                        .param("table",table).param("privilege",privilege).query(Boolean.class).single()).isFalse();
            }
        }
        assertThat(app.sql("SELECT has_function_privilege(current_user,'ops.create_ad_bid_command_from_sealed_authority(uuid,bigint,uuid,uuid,uuid,timestamptz,text)','EXECUTE')")
                .query(Boolean.class).single()).isFalse();
        assertThatThrownBy(()->app.sql("SET ROLE marketops_identity_issuer").update()).hasRootCauseInstanceOf(SQLException.class);
    }

    @Test void arbitraryApplicationGucCannotImpersonateFinalApprover() throws Exception {
        try(var app=transaction()) {
            try(var setting=app.prepareStatement("SELECT set_config('marketops.authenticated_actor',?,true)")) {
                setting.setString(1,graph.id("ownerUser").toString());setting.execute();
            }
            assertThatThrownBy(()->AdvertisingR1Fixture.seal(app,graph,"attacker-selected-proof"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("one-use transaction-bound authenticated invocation required");
            app.rollback();
        }
    }

    @Test void grantFromDifferentPhysicalApplicationSessionCannotBeReplayed() throws Exception {
        try(var first=transaction();var second=transaction()) {
            String issued=proof(first,graph.id("ownerUser"));
            assertThatThrownBy(()->AdvertisingR1Fixture.seal(second,graph,issued)).hasMessageContaining("transaction-bound");
            second.rollback();
            assertThat(AdvertisingR1Fixture.seal(first,graph,issued)).isNotNull();
            first.rollback();
        }
    }

    @Test void makerProofCannotBecomeOwnersFinalApproval() throws Exception {
        try(var app=transaction()) {
            String issued=proof(app,graph.id("executorUser"));
            assertThatThrownBy(()->AdvertisingR1Fixture.seal(app,graph,issued)).hasMessageContaining("final approval identity");
            app.rollback();
        }
    }

    @Test void currentActorRevocationInvalidatesAnAlreadyIssuedProof() throws Exception {
        try(var app=transaction()) {
            String issued=proof(app,graph.id("ownerUser"));
            seed.sql("UPDATE iam.user_account SET credentials_valid_from=clock_timestamp()+interval '1 minute' WHERE id=:id")
                    .param("id",graph.id("ownerUser")).update();
            assertThatThrownBy(()->AdvertisingR1Fixture.seal(app,graph,issued)).hasMessageContaining("transaction-bound");
            app.rollback();
        }
    }

    @Test void storeOnlyApprovalGrantCannotAuthoriseUndisclosedAffectedProducts() throws Exception {
        seed.sql("""
            UPDATE iam.user_scope_grant SET organization_ref_id=NULL,store_ref_id=:store
            WHERE user_id=:actor AND action_code='AD_BID_CHANGE_APPROVE'
            """).param("store",graph.id("store")).param("actor",graph.id("ownerUser")).update();
        try(var app=transaction()) {
            assertThatThrownBy(()->AdvertisingR1Fixture.seal(app,graph,proof(app,graph.id("ownerUser"))))
                    .hasMessageContaining("current exact Material/Ordinary scoped final approval required");app.rollback();
        }
    }

    @Test void finalApprovalCannotSwapInAnUnapprovedBaseline() throws Exception {
        try(var app=transaction()) {
            String issued=proof(app,graph.id("ownerUser"));
            try(var call=app.prepareStatement("SELECT ops.seal_ad_action_authorization(?,?,?,?)")) {
                call.setObject(1,graph.id("recommendation"));call.setObject(2,graph.id("approval"));
                call.setObject(3,UUID.randomUUID());call.setString(4,issued);
                assertThatThrownBy(call::execute).hasMessageContaining("final approval identity or sealed selection is invalid");
            }
            app.rollback();
        }
    }

    @Test void changedPolicyAfterEndorsementCannotBeSilentlySealed() throws Exception {
        seed.sql("UPDATE core.ad_bid_target_policy SET max_absolute_change_amount=max_absolute_change_amount+1 WHERE id=:id")
                .param("id",graph.id("targetPolicy")).update();
        try(var app=transaction()) {
            String issued=proof(app,graph.id("ownerUser"));
            assertThatThrownBy(()->AdvertisingR1Fixture.seal(app,graph,issued)).hasMessageContaining("selection/endorsement authority changed");
            app.rollback();
        }
    }

    @Test void cumulativeExposureIsMajorCurrencyAndUsesAllBroaderPolicies() throws Exception {
        try(var app=transaction()) {
            AdvertisingR1Fixture.seal(app,graph,proof(app,graph.id("ownerUser")));
            AdvertisingR1Fixture.createCommand(app,graph);app.commit();
        }
        seed.sql("UPDATE core.ad_exposure_envelope SET max_cumulative_bid_change_amount=5 WHERE id=:id")
                .param("id",graph.id("exposure")).update();
        assertThat(JdbcClient.create(application).sql("SELECT ops.ad_exposure_failures(:org,:store,'PROTECTION_DECREASE')")
                .param("org",graph.id("organization")).param("store",graph.id("store"))
                .query((row,index)->(String[])row.getArray(1).getArray()).single())
                .contains("CUMULATIVE_BID_CHANGE","AGGREGATE_ENVELOPE_BLOCKED");
    }

    @Test void causeBoundKnownDangerCanBeSealedWithoutConversionOrCostEvidence() throws Exception {
        graph=seedCauseBoundGraph("0.4");
        var constructor=com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingImpactEvidenceService.class
                .getDeclaredConstructor(JdbcClient.class,tools.jackson.databind.ObjectMapper.class);
        constructor.setAccessible(true);
        var impact=constructor.newInstance(JdbcClient.create(application),new tools.jackson.databind.ObjectMapper());
        var preview=impact.capture(graph.id("recommendation"),java.time.Instant.now(),graph.id("bundle"));
        assertThat(preview.path("economicMaxCpc").path("state").asText()).isEqualTo("NOT_AVAILABLE");
        assertThat(preview.path("economicMaxCpc").path("amount").isNull()).isTrue();
        assertThat(preview.path("submittedUnitMaxCpc").path("amount").isNull()).isTrue();
        assertThat(preview.path("conservativeCeiling").path("amount").isNull()).isTrue();
        assertThat(preview.path("submittedConfiguration").path("basis").asText()).isEqualTo("CAUSE_BOUND_PROTECTION_STEP");
        assertThat(preview.path("recoveryState").asText()).isEqualTo("EXPOSURE_LIMIT_ONLY_NOT_PROFITABILITY_OR_HEALTH");
        try(var app=transaction()) {
            UUID authority=AdvertisingR1Fixture.seal(app,graph,proof(app,graph.id("ownerUser")));
            assertThat(AdvertisingR1Fixture.createCommand(app,graph)).isNotNull();app.commit();
            assertThat(seed.sql("SELECT bounds->'requiredEvidenceKinds' FROM ops.ad_action_authorization WHERE id=:id")
                    .param("id",authority).query(String.class).single())
                    .contains("SELLABILITY","OFFICIAL_AD_SPEND","AD_OBJECT_CONFIGURATION","AFFECTED_SET")
                    .doesNotContain("COST_AND_FEE","AD_LINKED_SALE_EVENT","OFFICIAL_AD_TRAFFIC");
        }
    }

    @Test void causeBoundStepCannotExceedExactOwnerPolicyRatio() throws Exception {
        graph=seedCauseBoundGraph("0.1");
        try(var app=transaction()) {
            AdvertisingR1Fixture.seal(app,graph,proof(app,graph.id("ownerUser")));
            assertThatThrownBy(()->AdvertisingR1Fixture.createCommand(app,graph))
                    .hasMessageContaining("exact target and Provider policy");app.rollback();
        }
    }

    @Test void causeBoundProtectionCannotTreatMissingDangerAsFreshEvidence() throws Exception {
        graph=seedCauseBoundGraph("0.4");
        seed.sql("DELETE FROM mart.ad_case_purpose_evidence WHERE case_id=:id AND evidence_kind='SELLABILITY'")
                .param("id",graph.id("caseId")).update();
        try(var app=transaction()) {
            assertThatThrownBy(()->AdvertisingR1Fixture.seal(app,graph,proof(app,graph.id("ownerUser"))))
                    .hasMessageContaining("all evidence purpose expiry bounds required");app.rollback();
        }
    }

    @Test void causeBoundFinancialExceptionNeverIgnoresUnknownCriticalSafety() throws Exception {
        graph=seedCauseBoundGraph("0.4");
        seed.sql("UPDATE mart.ad_case SET blocker_codes=ARRAY['CRITICAL_UNIT_COVERAGE_UNRESOLVED'] WHERE id=:id")
                .param("id",graph.id("caseId")).update();
        try(var app=transaction()) {
            assertThatThrownBy(()->AdvertisingR1Fixture.seal(app,graph,proof(app,graph.id("ownerUser"))))
                    .hasMessageContaining("action-specific evidence blockers remain unresolved");app.rollback();
        }
    }

    /** A protection-only variation must preserve the independent frozen Outcome authorities. */
    private AdvertisingR1Fixture.Graph seedCauseBoundGraph(String ratio) throws Exception {
        var result=AdvertisingR1Fixture.seedOutcome(migration,sql->causeBound(sql,ratio));
        for(var stage:java.util.Map.of(
                "OPERATIONAL",List.of("EARLY_COMPLETED_SALES_OUTCOME","COMPANY_COMPLETED_SALE"),
                "RETAINED",List.of("FINAL_RETAINED_SALES_OUTCOME","COMPANY_RETAINED_SALE"),
                "SETTLED",List.of("SETTLED_FINANCIAL_OUTCOME","SETTLEMENT")).entrySet()) {
            var required=new java.util.ArrayList<>(List.of("OFFICIAL_AD_SPEND","OFFICIAL_AD_TRAFFIC",
                    "AD_LINKED_SALE_EVENT","COST_AND_FEE","AD_OBJECT_CONFIGURATION","AFFECTED_SET",
                    "SELLABILITY","AVAILABILITY","PRICE_AND_PROMOTION"));
            required.add(stage.getValue().get(1));
            assertThat(seed.sql("""
                    SELECT evidence_kind FROM core.ad_freshness_profile
                     WHERE organization_id=:org AND decision_purpose=:purpose
                       AND profile_version=1 AND status='ACTIVE'
                    """).param("org",result.id("organization")).param("purpose",stage.getValue().get(0))
                    .query(String.class).list()).as("complete independent %s profiles",stage.getKey())
                    .containsExactlyInAnyOrderElementsOf(required);
            assertThat(seed.sql("""
                    SELECT ARRAY(SELECT jsonb_object_keys(snapshot->'freshnessProfiles'))
                      FROM ops.ad_outcome_stage_baseline
                     WHERE outcome_baseline_id=:baseline AND stage=:stage
                    """).param("baseline",result.id("baseline")).param("stage",stage.getKey())
                    .query((row,index)->(String[])row.getArray(1).getArray()).single())
                    .as("complete frozen %s profile snapshot",stage.getKey())
                    .containsExactlyInAnyOrderElementsOf(required);
        }
        assertThat(seed.sql("""
                SELECT evidence_kind FROM core.ad_freshness_profile
                 WHERE organization_id=:org AND decision_purpose='PROTECTION_BID_WRITE'
                """).param("org",result.id("organization")).query(String.class).list())
                .contains("SELLABILITY").doesNotContain("COST_AND_FEE");
        return result;
    }

    private static String causeBound(String sql,String ratio) {
        String disabledPolicy="'RUB', 0.01, false, NULL,\n        CAST('{}' AS text[])";
        assertThat(sql).as("known synthetic target policy must be transformed explicitly").contains(disabledPolicy);
        assertThat(sql).as("only the protection profile and its purpose evidence change kind")
                .containsOnlyOnce("'COST_AND_FEE','PROTECTION_BID_WRITE'")
                .containsOnlyOnce("'PROTECTION_BID_WRITE','COST_AND_FEE'");
        String result=sql.replace("MAX_CPC_BOUNDED","CAUSE_BOUND_PROTECTION_STEP")
                .replace("PROVEN_ADVERTISING_LOSS","PROMOTED_VARIANT_NOT_SELLABLE")
                .replace(disabledPolicy,
                        "'RUB', NULL, true, "+ratio+",\n        ARRAY['PROMOTED_VARIANT_NOT_SELLABLE']::text[]")
                .replace("max_cpc_amount, cause_code, generated_at", "max_cpc_amount, max_cpc_absence_reason, cause_code, generated_at")
                .replace("'CURRENCY_MAJOR', 22.0000, 'PROMOTED_VARIANT_NOT_SELLABLE'",
                        "'CURRENCY_MAJOR', NULL, 'CONVERSION_NOT_WRITE_GRADE', 'PROMOTED_VARIANT_NOT_SELLABLE'")
                .replace("UPDATE mart.ad_case SET max_cpc_amount=22", "UPDATE mart.ad_case SET max_cpc_state='NOT_AVAILABLE',max_cpc_amount=NULL,blocker_codes=ARRAY['AD_LINKED_CONVERSION_NOT_WRITE_GRADE']")
                .replace("'COST_AND_FEE','PROTECTION_BID_WRITE'", "'SELLABILITY','PROTECTION_BID_WRITE'")
                .replace("'PROTECTION_BID_WRITE','COST_AND_FEE'", "'PROTECTION_BID_WRITE','SELLABILITY'");
        StringBuilder nativeOnly=new StringBuilder();
        for(String statement:result.split(";")) {
            if(statement.contains("mart.ad_case_purpose_evidence")
                    && (statement.contains("'OFFICIAL_AD_TRAFFIC'") || statement.contains("'AD_LINKED_SALE_EVENT'"))) continue;
            nativeOnly.append(statement).append(';');
        }
        return nativeOnly.toString();
    }
}
