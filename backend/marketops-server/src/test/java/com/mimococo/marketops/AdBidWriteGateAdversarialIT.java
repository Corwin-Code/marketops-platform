package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Live gate faults start from an actual sealed and idempotently created command.
 * Production transport remains disabled. These tests distinguish a named gate
 * refusal from an earlier privilege/row constraint; they do not claim an open
 * production APPLY path. Each test owns a separate fictional graph.
 */
class AdBidWriteGateAdversarialIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    private static JdbcClient seed,appRead;
    private AdvertisingR1Fixture.Graph graph;
    private UUID command;

    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);appRead=JdbcClient.create(application);
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    @BeforeEach void seedExactCommand() throws Exception {
        graph=AdvertisingR1Fixture.seed(migration);
        seed.sql("""
            INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,
              effective_from,status,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:actor,'ADVERTISING_POLICY_MANAGE',:org,
              now()-interval '1 day','ACTIVE','synthetic stop scope',now(),now())
            """).param("org",graph.id("organization")).param("actor",graph.id("verifierUser")).update();
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id("ownerUser"),null,
                    graph.id("recommendation"),graph.id("approval"));
            AdvertisingR1Fixture.seal(app,graph,proof);
            command=AdvertisingR1Fixture.createCommand(app,graph);
            assertThat(AdvertisingR1Fixture.createCommand(app,graph)).isEqualTo(command);
            app.commit();
        }
        assertThat(reasons()).doesNotContain("SEALED_AUTHORIZATION_MISSING_OR_EXPIRED",
                "CANONICAL_OUTCOME_BASELINE_AUTHORITY_INVALID","AUTHORITY_PERMANENTLY_INVALIDATED",
                "KILL_SWITCH_ACTIVE","QUARANTINE_ACTIVE","RESERVATION_CONFLICT");
    }

    @Test @DisplayName("TC-AD-GATE-ADV-001 a real sealed command returns named closed-transport reasons")
    void theGateRefusesRatherThanRaises() {
        assertThat(reasons()).contains("GLOBAL_SWITCH_DISABLED","CAPABILITY_SWITCH_DISABLED","GUARDRAIL_NOT_PASSED");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_authorization WHERE recommendation_id=:id")
                .param("id",graph.id("recommendation")).query(Integer.class).single()).isEqualTo(1);
    }

    @Test @DisplayName("TC-AD-GATE-ADV-002 a missing command is refused by name")
    void anAbsentCommandIsRefusedByName() {
        assertThat(appRead.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))")
                .param("id",UUID.randomUUID()).query(String.class).list()).containsExactly("COMMAND_NOT_FOUND");
    }

    @Test @DisplayName("TC-AD-GATE-ADV-003 authenticated Kill adds its named gate reason and permanent invalidation")
    void aKillSwitchAddsItsReasonAndPermanentlyInvalidatesAuthority() throws Exception {
        UUID containment=activateContainment("KILL_SWITCH_ACTIVE");
        assertThat(reasons()).contains("KILL_SWITCH_ACTIVE","AUTHORITY_PERMANENTLY_INVALIDATED");
        assertThat(reasons()).doesNotContain("QUARANTINE_ACTIVE");
        assertCannotRenewAfterPrivilegedRestoration(containment,"KILL_SWITCH_ACTIVE");
    }

    @Test @DisplayName("TC-AD-GATE-ADV-004 an entity hold is independently named and cannot revive authority")
    void aQuarantineIsNotAKillSwitch() throws Exception {
        UUID containment=activateContainment("EMERGENCY_ENTITY_HOLD");
        assertThat(reasons()).contains("QUARANTINE_ACTIVE","AUTHORITY_PERMANENTLY_INVALIDATED");
        assertThat(reasons()).doesNotContain("KILL_SWITCH_ACTIVE");
        assertCannotRenewAfterPrivilegedRestoration(containment,"QUARANTINE_ACTIVE");
    }

    @Test @DisplayName("TC-AD-GATE-ADV-005 early release refuses at privilege/canonical boundaries and stale release closes the gate")
    void aLiveReservationCannotBeReleasedEarly() {
        // App refusal is a privilege boundary, not evidence that the live gate ran.
        assertSqlRefusal(()->appRead.sql("UPDATE ops.ad_action_reservation SET configuration_resolved=true WHERE id=:id")
                .param("id",graph.id("reservation")).update(),"42501","permission denied");
        assertThat(appRead.sql("SELECT ops.release_ad_action_reservation(:id,'no factual safety observation')")
                .param("id",graph.id("reservation")).query(Boolean.class).single()).isFalse();
        // Even the owning-role attack cannot bypass the row's required shape.
        assertSqlRefusal(()->seed.sql("""
            UPDATE ops.ad_action_reservation SET state='RELEASED',released_at=clock_timestamp(),release_reason='synthetic attack'
            WHERE id=:id
            """).param("id",graph.id("reservation")).update(),"23514","ad_action_reservation_release_conditions_ck");
        // Privileged historical state injection models stale work, not canonical release evidence.
        seed.sql("""
            UPDATE ops.ad_action_reservation SET configuration_resolved=true,unknown_or_mismatch_open=false,
              early_observation_complete=true,regression_open=false,state='RELEASED',released_at=clock_timestamp(),
              release_reason='synthetic stale-worker state' WHERE id=:id
            """).param("id",graph.id("reservation")).update();
        assertThat(reasons()).contains("RESERVATION_CONFLICT");
    }

    @Test @DisplayName("TC-AD-GATE-ADV-006 envelope restoration resolves its axis but cannot resurrect the sealed command")
    void aResolvedEnvelopeCannotRevivePriorAuthority() throws Exception {
        assertThat(reasons()).doesNotContain("AGGREGATE_ENVELOPE_UNRESOLVED");
        seed.sql("UPDATE core.ad_exposure_envelope SET status='CANCELLED' WHERE id=:id")
                .param("id",graph.id("exposure")).update();
        assertThat(reasons()).contains("AGGREGATE_ENVELOPE_UNRESOLVED","AUTHORITY_PERMANENTLY_INVALIDATED");
        seed.sql("UPDATE core.ad_exposure_envelope SET status='ACTIVE' WHERE id=:id")
                .param("id",graph.id("exposure")).update();
        assertThat(reasons()).doesNotContain("AGGREGATE_ENVELOPE_UNRESOLVED");
        assertOldApprovalCannotCreateAgain();
    }

    @Test @DisplayName("TC-AD-GATE-ADV-007 baseline reasons use the declared control vocabulary")
    void everyBaselineReasonIsInTheDeclaredVocabulary() {
        assertThat(reasons()).isSubsetOf(List.of(
            "AFFECTED_SET_DIGEST_CHANGED","AGGREGATE_ENVELOPE_BLOCKED","AGGREGATE_ENVELOPE_UNRESOLVED",
            "APPROVAL_LEASE_EXPIRED","AUTHORIZATION_INVALID_OR_EXPIRED","BUNDLE_SCOPE_EXCEEDED","BUNDLE_UNRESOLVED",
            "CANDIDATE_BASIS_NOT_ENABLED","CAPABILITY_NOT_AVAILABLE_FOR_STORE","CAPABILITY_NOT_VERIFIED",
            "CAPABILITY_SWITCH_DISABLED","COMMAND_AUTHORITY_MISMATCH","DIRECTION_NOT_ENABLED","ENTITY_NOT_ALLOWLISTED",
            "GLOBAL_SWITCH_DISABLED","GUARDRAIL_NOT_PASSED","KILL_SWITCH_ACTIVE","MAPPING_CONFLICT_OPEN",
            "MAPPING_UNRESOLVED","MATERIALITY_UNRESOLVED","ORDINARY_ROUTE_NOT_PROMOTED","QUARANTINE_ACTIVE",
            "RECOMMENDATION_STALE","RESERVATION_CONFLICT","SCOPED_SWITCH_DISABLED","COMMAND_NOT_FOUND",
            "ACCEPTED_EXCEPTION_ACTIVE","ACTION_EVIDENCE_BLOCKERS_UNRESOLVED","SEALED_AUTHORIZATION_MISSING_OR_EXPIRED",
            "CANONICAL_OUTCOME_BASELINE_AUTHORITY_INVALID","SEALED_MATERIALITY_ROUTE_CHANGED_OR_UNRESOLVED",
            "AUTHORITY_PERMANENTLY_INVALIDATED","COMPLETE_AUTHORITY_SNAPSHOT_CHANGED","ACTION_EVIDENCE_AUTHORITY_CHANGED",
            "GUARDRAIL_BUNDLE_MISMATCH","EXACT_GATE_AUTHORITY_ABSENT_OR_EXCEEDED","ADS_WRITE_CREDENTIAL_AUTHORITY_INVALID"));
    }

    @Test @DisplayName("TC-AD-GATE-ADV-008 fictional approval does not enable production transport or verify real Providers")
    void theGateCannotBeSatisfiedHere() {
        assertThat(reasons()).contains("GLOBAL_SWITCH_DISABLED","CAPABILITY_SWITCH_DISABLED");
        assertThat(appRead.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",graph.id("gate")).query(Boolean.class).single()).isFalse();
        assertThat(seed.sql("""
            SELECT count(*) FROM platform.platform_capability WHERE platform_code IN('OZON','WILDBERRIES')
              AND capability_code='ad-bid-change' AND verification_state='VERIFIED'
            """).query(Long.class).single()).isZero();
    }

    private Connection transaction() throws SQLException { var app=application.getConnection();app.setAutoCommit(false);return app; }
    private Set<String> reasons() { return Set.copyOf(appRead.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))")
            .param("id",command).query(String.class).list()); }
    private UUID activateContainment(String kind) throws Exception {
        UUID id=UUID.randomUUID();
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id("verifierUser"),"CONTAINMENT_STOP",graph.id("object"),id);
            try(var call=app.prepareStatement("SELECT ops.activate_ad_human_containment(?,?,?,?,?,?,?,?,?)")) {
                call.setObject(1,id);call.setObject(2,graph.id("object"));
                call.setString(3,"KILL_SWITCH_ACTIVE".equals(kind)?"PLATFORM_STORE_CAPABILITY":"ENTITY");
                call.setString(4,kind);call.setString(5,"BUSINESS_HARM");call.setObject(6,graph.id("verifierUser"));
                call.setString(7,"fictional gate incident");call.setString(8,"fixture://named-gate-fault");call.setString(9,proof);call.execute();
            }
            app.commit();return id;
        }
    }
    private void assertCannotRenewAfterPrivilegedRestoration(UUID containment,String resolvedReason) throws Exception {
        assertSqlRefusal(()->appRead.sql("UPDATE ops.ad_containment SET state='REENABLED' WHERE id=:id")
                .param("id",containment).update(),"42501","permission denied");
        // A migration-role state oracle models even an apparently restored dependency.
        // This is not a product reenablement or a substitute for its human evidence chain.
        seed.sql("""
            UPDATE ops.ad_containment SET state='REENABLED',root_cause_classified=true,unknowns_resolved=true,
              authorities_replaced=true,results_reconciled=true,capability_evidence_current=true,
              security_attestation_present=true,endorsed_by_user_id=:endorser,approved_by_user_id=:owner,
              reenabled_scope=jsonb_build_object('syntheticRestorationAttempt',true),reenabled_at=clock_timestamp()
            WHERE id=:id
            """).param("endorser",graph.id("executorUser")).param("owner",graph.id("ownerUser")).param("id",containment).update();
        assertThat(reasons()).doesNotContain(resolvedReason);
        assertOldApprovalCannotCreateAgain();
    }
    private void assertOldApprovalCannotCreateAgain() throws Exception {
        assertThat(reasons()).contains("AUTHORITY_PERMANENTLY_INVALIDATED");
        assertThat(appRead.sql("SELECT count(*) FROM ops.ad_authority_invalidation WHERE authorization_id=(SELECT id FROM ops.ad_action_authorization WHERE recommendation_id=:id)")
                .param("id",graph.id("recommendation")).query(Integer.class).single()).isPositive();
        try(var app=transaction()) {
            assertSqlRefusal(()->AdvertisingR1Fixture.reserve(app,graph),"MO097","reservation requires exact intervention");
            app.rollback();
            // Call the public creator directly to test its own boundary after reservation admission refused.
            try(var query=app.prepareStatement("SELECT ops.create_ad_bid_command(?,(SELECT version FROM ops.recommendation WHERE id=?),?,'fictional-invalidated-creator')")) {
                query.setObject(1,graph.id("recommendation"));query.setObject(2,graph.id("recommendation"));
                query.setObject(3,graph.id("reservation"));
                assertSqlRefusal(query::execute,"MO092","current immutable approval authority required");
            }
            app.rollback();
        }
        assertThat(appRead.sql("SELECT count(*) FROM ops.ad_bid_command WHERE recommendation_id=:id")
                .param("id",graph.id("recommendation")).query(Integer.class).single()).isEqualTo(1);
    }

    private static void assertSqlRefusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                         String state,String message) {
        Throwable refusal=catchThrowable(action);
        assertThat(refusal).as("database operation must refuse").isNotNull();
        while(refusal.getCause()!=null) refusal=refusal.getCause();
        assertThat(refusal).isInstanceOf(SQLException.class).hasMessageContaining(message);
        assertThat(((SQLException)refusal).getSQLState()).isEqualTo(state);
    }
}
