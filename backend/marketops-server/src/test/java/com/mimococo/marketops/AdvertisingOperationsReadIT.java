package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.AdvertisingExposureView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingOperationsQuery;
import com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingReservationView;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The console's reads of what advertising is currently doing.
 *
 * <p>These are reports, and this test's job is to prove they report rather than
 * decide. The envelope read counts the same three axes the write gate counts and
 * keeps them apart; the outcome read returns all three stages and every restatement;
 * and both refuse a store the caller was not granted, in SQL rather than only in
 * the caller.
 *
 * <p>Everything is seeded with the migration role, because several of these
 * tables are deliberately not writable by the application role and a fixture
 * that used the application role would prove less than it claims.
 */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingOperationsReadIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE =
            TestDatabase.isolatedContainer();

    private static JdbcClient seed;
    private static AdvertisingGraphFixture.Graph graph;
    private static AdvertisingGraphFixture.Command command;
    private static Instant landed;
    @Autowired private javax.sql.DataSource application;
    @Autowired private com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingRecalculationRepository queue;

    @Autowired
    private AdvertisingOperationsQuery operations;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @BeforeAll
    static void openSeedConnection() {
        seed = JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
    }

    /**
     * One graph for the whole class, seeded on the first case that needs it.
     *
     * <p>Not in {@code @BeforeAll}: the schema does not exist until the Spring
     * context has run Flyway, and that happens after the static callback.
     */
    @BeforeEach
    void seedTheGraphOnce() throws Exception {
        if (graph != null) {
            return;
        }
        var migrated=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var actual=AdvertisingR1Fixture.seed(migrated);
        var admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        UUID commandId;
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,actual,actual.id("ownerUser"),null,
                    actual.id("recommendation"),actual.id("approval"));
            AdvertisingR1Fixture.seal(connection,actual,proof);
            commandId=AdvertisingR1Fixture.createCommand(connection,actual);connection.commit();
        }
        String digest=seed.sql("SELECT affected_set_digest FROM core.ad_affected_set WHERE id=:id")
                .param("id",actual.id("affectedSet")).query(String.class).single();
        graph=new AdvertisingGraphFixture.Graph(actual.id("organization"),actual.id("legalEntity"),actual.id("account"),actual.id("store"),
                actual.id("productVariant"),actual.id("object"),actual.id("affectedSet"),digest,actual.id("caseId"),actual.id("configuration"),
                actual.id("profile"),actual.id("executorUser"),actual.id("verifierUser"));
        UUID capability=seed.sql("SELECT id FROM platform.platform_capability WHERE platform_code=:platform AND capability_code='ad-bid-change'")
                .param("platform",actual.platform()).query(UUID.class).single();
        command=new AdvertisingGraphFixture.Command(commandId,capability,actual.id("bundle"),actual.id("reservation"),actual.id("approval"));
        landed=seed.sql("SELECT clock_timestamp()").query(java.sql.Timestamp.class).single().toInstant();
        UUID attempt=UUID.randomUUID(),raw=UUID.randomUUID(),content=UUID.randomUUID();
        seed.sql("INSERT INTO ops.ad_bid_command_attempt(id,command_id,attempt_no,purpose,fence_token,lease_owner,started_at,completed_at,outcome_class,correlation_id,request_digest,operation_snapshot) VALUES(:id,:command,1,'READBACK',1,'read-fixture',:at,:at,'ACCEPTED','read-fixture',repeat('a',64),'{}')")
                .param("id",attempt).param("command",commandId).param("at",java.sql.Timestamp.from(landed)).update();
        seed.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',:hash,2,'object-ref://fictional/read-oracle')")
                .param("id",content).param("hash",com.mimococo.marketops.shared.Digest.ofText(content.toString())).update();
        seed.sql("INSERT INTO raw.ad_bid_response_observation(id,command_id,attempt_id,raw_content_id,request_digest,http_status,response_headers,evidence_class,response_complete,observed_bid,observed_currency,observed_unit,observed_at,correlation_id) VALUES(:id,:command,:attempt,:content,repeat('a',64),200,'{}','PROTOCOL_FIXTURE',true,20,'RUB','CURRENCY_MAJOR',:at,'read-fixture')")
                .param("id",raw).param("command",commandId).param("attempt",attempt).param("content",content).param("at",java.sql.Timestamp.from(landed)).update();
        seed.sql("INSERT INTO ops.ad_bid_command_readback VALUES(gen_random_uuid(),:command,:attempt,:at,20,'RUB','CURRENCY_MAJOR','MATCHES_TARGET',:raw,'read-fixture')")
                .param("command",commandId).param("attempt",attempt).param("at",java.sql.Timestamp.from(landed)).param("raw",raw).update();

    }

    @Test
    @DisplayName("TC-AD-READ-001 a reservation reports which release conditions are outstanding")
    void aReservationNamesWhatItIsWaitingOn() {
        List<AdvertisingReservationView> held = operations.reservations(
                graph.organizationId(), List.of(graph.storeId()), true, 50);

        assertThat(held).isNotEmpty();
        AdvertisingReservationView reservation = held.getFirst();
        assertThat(reservation.holding()).isTrue();
        assertThat(reservation.productVariantIds()).contains(graph.productVariantId());
        // Named rather than counted. An operator looking at a reservation that
        // will not release needs to know which condition is missing.
        assertThat(reservation.outstandingReleaseConditions())
                .contains("CONFIGURATION_NOT_RESOLVED", "EARLY_OBSERVATION_INCOMPLETE");
    }

    @Test
    @DisplayName("TC-AD-READ-002 a store the caller was not granted returns nothing, in SQL")
    void anUngrantedStoreReadsNothing() {
        // The scope is applied again in the query, so a caller that passed a
        // wider list than it holds still reads nothing outside it.
        assertThat(operations.reservations(
                graph.organizationId(), List.of(UUID.randomUUID()), true, 50)).isEmpty();
        assertThat(operations.reservations(graph.organizationId(), List.of(), true, 50)).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-READ-003 no envelope is reported as no envelope, not as an empty one")
    void anUnresolvedEnvelopeIsReportedAsUnresolved() {
        // An organization nobody has written an envelope for, which is the state
        // every environment is in today: nothing may be written at all. Asked of
        // a separate organization so this does not depend on which order the
        // cases below happen to run in.
        AdvertisingExposureView exposure = operations.exposure(UUID.randomUUID());

        assertThat(exposure.resolved()).isFalse();
        assertThat(exposure.exhaustedAxes()).containsExactly("AGGREGATE_ENVELOPE_UNRESOLVED");
        // The consumption figures are still reported rather than omitted, so an
        // operator can see what is standing while no envelope resolves.
        assertThat(exposure.activeInterventions()).isZero();
        assertThat(exposure.unresolvedTransmittedWrites()).isZero();
    }

    @Test
    @DisplayName("TC-AD-READ-004 the envelope read counts the same axes the write gate counts")
    void theEnvelopeReadMatchesTheGatesOwnAxes() {
        AdvertisingExposureView exposure = operations.exposure(graph.organizationId());

        assertThat(exposure.resolved()).isTrue();
        assertThat(exposure.maxActiveInterventions()).isEqualTo(10);
        assertThat(exposure.reservedRecoveryHeadroom()).isEqualTo(2);
        assertThat(exposure.maxUnresolvedTransmittedWrites()).isEqualTo(2);
        assertThat(exposure.cumulativeWindowHours()).isEqualTo(24);
        assertThat(exposure.currencyCode()).isEqualTo("RUB");
        // Every axis independently. There is no point in the gate where one
        // axis's slack is added to another's, and none here either: the
        // intervention axis is reported against capacity less the recovery
        // headroom, and the other two against their own limits.
        assertThat(exposure.cumulativeBidChangeAmount()).isNotNull();
        assertThat(exposure.activeInterventions()).isEqualTo(1);
        assertThat(exposure.exhaustedAxes()).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-READ-005 all three outcome stages and every restatement come back, in order")
    void allThreeStagesAndEveryRestatementComeBack() {
        UUID policyId = seed.sql("""
                SELECT outcome_policy_id FROM ops.ad_decision_policy_bundle WHERE id = :bundleId
                """).param("bundleId", command.bundleId()).query(UUID.class).single();
        Instant windowStart = landed.plusSeconds(1800);
        UUID operational = recordObservation(policyId,"OPERATIONAL",1,null,windowStart);
        recordObservation(policyId,"RETAINED",1,null,windowStart);
        UUID settled = recordObservation(policyId,"SETTLED",1,null,windowStart);
        recordObservation(policyId,"SETTLED_REVISED",2,settled,windowStart);

        List<AdvertisingOutcomeView> outcomes = operations.outcomes(
                graph.organizationId(), command.commandId(), List.of(graph.storeId()));

        assertThat(outcomes).hasSize(4);
        assertThat(outcomes).allSatisfy(row->{
            assertThat(row.baselineMetricState()).isEqualTo("NOT_AVAILABLE");
            assertThat(row.baselineMetricValue()).isNull();assertThat(row.observedMetricValue()).isNull();
            assertThat(row.verdict()).isEqualTo("INDETERMINATE");
        });
        assertThat(outcomes.getFirst().id()).isEqualTo(operational);
        assertThat(outcomes.getFirst().settled()).isFalse();
        assertThat(outcomes.get(1).settled()).isFalse();
        assertThat(outcomes.get(2).settled()).isTrue();
        // The restatement names what it replaces rather than editing it. That an
        // answer changed is itself something an operator needs to see.
        assertThat(outcomes.getLast().restatement()).isTrue();
        assertThat(outcomes.getLast().supersedesObservationId()).isEqualTo(settled);
        assertThat(outcomes.getLast().revisionNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-AD-READ-006 an outcome outside the caller's stores is not readable")
    void anOutcomeOutsideTheScopeIsNotReadable() {
        assertThat(operations.outcomes(graph.organizationId(), command.commandId(),
                List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-READ-007 the containment list is bounded by the organization, not by store")
    void containmentIsAnOrganizationFact() {
        // A kill switch is not a per-store fact. An operator who could see only
        // some of the holds in force would draw the wrong conclusion about why
        // their work is stopped.
        assertThat(operations.containments(graph.organizationId(), false, 50)).isNotNull();
        assertThat(operations.containments(UUID.randomUUID(), false, 50)).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-TRIGGER-011 canonical landed configuration schedules every frozen outcome maturity")
    void outcomeMaturityUsesTheFrozenStageDeadlineAndRetainsItsExactClock() {
        var due=seed.sql("SELECT due_at FROM ops.ad_recalculation_due WHERE organization_id=:org AND trigger_class='OUTCOME_MATURITY_OR_REGRESSION' ORDER BY due_at,source_reference")
                .param("org",graph.organizationId()).query(Timestamp.class).list();
        assertThat(due).hasSize(3);
        assertThat(due.getFirst().toInstant()).isEqualTo(landed.plusSeconds(1800+336*3600));
        assertThat(due.getLast().toInstant()).isEqualTo(landed.plusSeconds(1800+1440*3600));
        // Earlier unrelated authorities were handled by another synthetic worker.
        seed.sql("UPDATE ops.ad_recalculation_due SET delivered_at=clock_timestamp() WHERE organization_id=:org AND trigger_class<>'OUTCOME_MATURITY_OR_REGRESSION'")
                .param("org",graph.organizationId()).update();
        seed.sql("UPDATE ops.ad_recalculation_request SET state='COMPLETED',completed_at=clock_timestamp(),lease_owner=NULL,leased_until=NULL WHERE organization_id=:org AND state IN('PENDING','LEASED')")
                .param("org",graph.organizationId()).update();
        queue.deliverDue(due.getFirst().toInstant(),10000);
        assertThat(seed.sql("SELECT trigger_class FROM ops.ad_recalculation_request WHERE organization_id=:org AND state='PENDING'").param("org",graph.organizationId()).query(String.class).single())
                .isEqualTo("OUTCOME_MATURITY_OR_REGRESSION");
        assertThat(seed.sql("SELECT fact_accepted_at FROM ops.ad_recalculation_request WHERE organization_id=:org AND state='PENDING'").param("org",graph.organizationId()).query(Timestamp.class).single().toInstant())
                .isEqualTo(due.getFirst().toInstant());
    }

    private UUID recordObservation(UUID policyId,String stage,int revision,UUID supersedes,Instant windowStart) {
        record Frozen(int hours,String valueState,BigDecimal value) { }
        Frozen frozen=seed.sql("""
                SELECT f.window_hours,f.snapshot#>>'{profit,absoluteProfit,valueState}' value_state,
                    (f.snapshot#>>'{profit,absoluteProfit,value}')::numeric value
                FROM ops.ad_bid_command c JOIN ops.ad_outcome_stage_baseline f ON f.outcome_baseline_id=c.outcome_baseline_id
                WHERE c.id=:command AND f.stage=replace(:stage,'_REVISED','')
                """).param("command",command.commandId()).param("stage",stage)
                .query((rs,n)->new Frozen(rs.getInt("window_hours"),rs.getString("value_state"),rs.getBigDecimal("value"))).single();
        UUID id=UUID.randomUUID();Instant end=windowStart.plusSeconds(frozen.hours()*3600L);
        seed.sql("""
                INSERT INTO ops.ad_outcome_observation(id,organization_id,command_id,ad_native_object_id,affected_set_digest,
                    outcome_policy_id,outcome_policy_version,outcome_stage,revision_no,supersedes_observation_id,
                    window_starts_at,window_ends_at,baseline_metric_state,baseline_metric_value,observed_metric_state,
                    observed_metric_value,verdict,guard_state,unresolved_reason_codes,evaluated_at,input_digest,correlation_id,adjustment_reason)
                VALUES(:id,:org,:command,:object,:digest,:policy,1,:stage,:revision,:supersedes,:start,:end,
                    :baselineState,:baseline,'NOT_AVAILABLE',NULL,'INDETERMINATE',
                    CASE WHEN CAST(:stage AS text) LIKE 'OPERATIONAL%' THEN 'NOT_APPLICABLE' ELSE 'COVERAGE_INSUFFICIENT' END,
                    ARRAY['PRE_ACTION_PROFIT_UNRESOLVED'],:evaluated,:inputDigest,:correlation,
                    CASE WHEN CAST(:revision AS integer)>1 THEN 'LATE_RETURN_RESTATEMENT' ELSE NULL END)
                """).param("id",id).param("org",graph.organizationId()).param("command",command.commandId())
                .param("object",graph.objectId()).param("digest",graph.digest()).param("policy",policyId)
                .param("stage",stage).param("revision",revision).param("supersedes",supersedes)
                .param("start",Timestamp.from(windowStart)).param("end",Timestamp.from(end))
                .param("baselineState",frozen.valueState()).param("baseline",frozen.value())
                .param("evaluated",Timestamp.from(end.plusSeconds(revision))).param("inputDigest","f".repeat(64))
                .param("correlation","ad-read-it-"+id).update();
        return id;
    }
}
