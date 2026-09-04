package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.AdvertisingExposureView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingOperationsQuery;
import com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingReservationView;
import java.math.BigDecimal;
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
 * keeps them apart; the outcome read returns both stages and every restatement;
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
    void seedTheGraphOnce() {
        if (graph != null) {
            return;
        }
        graph = AdvertisingGraphFixture.seed(seed);
        AdvertisingGraphFixture.Decision decision = AdvertisingGraphFixture.seedDecision(
                seed, graph, "PROTECTION_DECREASE", "MAX_CPC_BOUNDED");
        command = AdvertisingGraphFixture.seedCommand(seed, graph, decision, "PENDING");
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
    @DisplayName("TC-AD-READ-005 both outcome stages and every restatement come back, in order")
    void bothStagesAndEveryRestatementComeBack() {
        UUID policyId = seed.sql("""
                SELECT outcome_policy_id FROM ops.ad_decision_policy_bundle WHERE id = :bundleId
                """).param("bundleId", command.bundleId()).query(UUID.class).single();
        Instant windowStart = Instant.parse("2026-08-05T00:00:00Z");
        UUID operational = recordObservation(policyId, "OPERATIONAL", 1, null, windowStart,
                new BigDecimal("100.000000"), new BigDecimal("140.000000"), "IMPROVED");
        UUID settled = recordObservation(policyId, "SETTLED", 1, null, windowStart,
                new BigDecimal("100.000000"), new BigDecimal("90.000000"), "REGRESSED");
        recordObservation(policyId, "SETTLED_REVISED", 2, settled, windowStart,
                new BigDecimal("100.000000"), new BigDecimal("85.000000"), "REGRESSED");

        List<AdvertisingOutcomeView> outcomes = operations.outcomes(
                graph.organizationId(), command.commandId(), List.of(graph.storeId()));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.getFirst().id()).isEqualTo(operational);
        assertThat(outcomes.getFirst().settled()).isFalse();
        assertThat(outcomes.get(1).settled()).isTrue();
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

    private UUID recordObservation(UUID policyId, String stage, int revision, UUID supersedes,
            Instant windowStart, BigDecimal baseline, BigDecimal observed, String verdict) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO ops.ad_outcome_observation (
                    id, organization_id, command_id, ad_native_object_id, affected_set_digest,
                    outcome_policy_id, outcome_policy_version, outcome_stage, revision_no,
                    supersedes_observation_id, window_starts_at, window_ends_at,
                    baseline_metric_state, baseline_metric_value, observed_metric_state,
                    observed_metric_value, observed_traffic_count, settled_coverage_ratio,
                    verdict, guard_state, unresolved_reason_codes, evaluated_at,
                    input_digest, correlation_id, adjustment_reason)
                VALUES (:id, :organizationId, :commandId, :objectId, :digest,
                    :policyId, 1, :stage, :revision, :supersedes,
                    CAST(:windowStart AS timestamptz),
                    CAST(:windowStart AS timestamptz) + interval '7 days',
                    'AVAILABLE', :baseline, 'AVAILABLE', :observed, 1000,
                    CASE WHEN CAST(:stage AS text) LIKE 'SETTLED%' THEN 0.92000 ELSE NULL END,
                    :verdict,
                    -- The guard applies to a settled claim and only to one; an
                    -- operational observation is not a settled claim.
                    CASE WHEN CAST(:stage AS text) = 'OPERATIONAL' THEN 'NOT_APPLICABLE'
                         ELSE 'SATISFIED' END, '{}', CAST(:windowStart AS timestamptz) + interval '8 days',
                    :inputDigest, :correlationId,
                    CASE WHEN CAST(:revision AS integer) > 1 THEN 'LATE_RETURN_RESTATEMENT'
                         ELSE NULL END)
                """)
                .param("id", id)
                .param("organizationId", graph.organizationId())
                .param("commandId", command.commandId())
                .param("objectId", graph.objectId())
                .param("digest", graph.digest())
                .param("policyId", policyId)
                .param("stage", stage)
                .param("revision", revision)
                .param("supersedes", supersedes)
                .param("windowStart", java.sql.Timestamp.from(windowStart))
                .param("baseline", baseline)
                .param("observed", observed)
                .param("verdict", verdict)
                .param("inputDigest", "f".repeat(64))
                .param("correlationId", "ad-read-it-" + id)
                .update();
        return id;
    }
}
