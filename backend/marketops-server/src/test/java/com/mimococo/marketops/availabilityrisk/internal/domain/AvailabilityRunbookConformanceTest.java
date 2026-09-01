package com.mimococo.marketops.availabilityrisk.internal.domain;

import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.FRESHNESS_MINUTES;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.NOW;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.VARIANT;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.blockedLeadTime;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.channel;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.demand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.leadTime;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.platform;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.profit;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.warehouse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityOperationsHealth;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Isolated execution of every AC081 incident drill and its documented exit contract. */
class AvailabilityRunbookConformanceTest {

    private static final Path RUNBOOK = Path.of("..", "..", "docs", "06-runbooks",
            "availability-risk-blockers.md").normalize();
    private static final UUID ORGANIZATION =
            UUID.fromString("00000000-0000-0000-0000-000000000081");

    @Test
    @DisplayName("TC-RUNBOOK-081-1 stale source fails closed and has a bounded drill")
    void staleSourceDrill() throws IOException {
        ChildRisk risk = ChannelRiskCalculator.calculate(
                channel(400, NOW.minus(Duration.ofHours(48)), Sellability.SELLABLE),
                demand("10"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.REVIEW);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.STALE);
        assertThat(risk.supply().provenUnits()).isZero();
        assertThat(risk.blockerCodes()).contains("CHANNEL_OBSERVATION_STALE");
        assertDocumented("DRILL-STALE-SOURCE", "CHANNEL_OBSERVATION_STALE");
    }

    @Test
    @DisplayName("TC-RUNBOOK-081-2 ownership conflict cannot manufacture safe stock")
    void ownershipConflictDrill() throws IOException {
        ChildRisk risk = CompanyRiskCalculator.calculate(
                new CompanyObservation(VARIANT,
                        List.of(warehouse(400, 0, 0, NOW.minus(Duration.ofMinutes(5)))),
                        List.of(platform(400, SupplyDistinctness.UNDECLARED,
                                NOW.minus(Duration.ofMinutes(5)))), List.of()),
                demand("10"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.cause()).isEqualTo(RiskCause.OWNERSHIP_UNDECLARED);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.DATA_BLOCKED);
        assertThat(risk.blockerCodes())
                .contains("COMPANY_SUPPLY_OWNERSHIP_NOT_DECLARED");
        assertDocumented("DRILL-OWNERSHIP-CONFLICT",
                "COMPANY_SUPPLY_OWNERSHIP_NOT_DECLARED");
    }

    @Test
    @DisplayName("TC-RUNBOOK-081-3 absent policy stays policy-blocked")
    void policyBlockerDrill() throws IOException {
        ChildRisk risk = CompanyRiskCalculator.calculate(
                new CompanyObservation(VARIANT,
                        List.of(warehouse(400, 0, 0, NOW.minus(Duration.ofMinutes(5)))),
                        List.of(), List.of()),
                demand("10"), blockedLeadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.cause()).isEqualTo(RiskCause.LEAD_TIME_POLICY_MISSING);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.POLICY_BLOCKED);
        assertDocumented("DRILL-POLICY-BLOCKER", "LEAD_TIME_POLICY_MISSING");
    }

    @Test
    @DisplayName("TC-RUNBOOK-081-4 backlog and SLO breaches become named incidents")
    void backlogAndSloDrill() throws IOException {
        AvailabilityRecalculationRepository queue = healthyRepository();
        when(queue.backlog(ORGANIZATION, NOW)).thenReturn(
                new AvailabilityRecalculationRepository.Backlog(3, Duration.ofMinutes(90)));
        when(queue.latencySummary(ORGANIZATION, AvailabilityLane.CRITICAL.name(),
                NOW.minus(Duration.ofHours(24)), NOW)).thenReturn(
                new AvailabilityRecalculationRepository.LatencySummary(
                        20, 310_000, 400_000, 1));

        AvailabilityOperationsHealth.LoopHealth health = health(queue);
        assertThat(health.incidents()).contains(
                "RECALCULATION_BACKLOG_BEYOND_OBLIGATION",
                "CRITICAL_RESPONSE_HARD_BOUND_BREACHED",
                "CRITICAL_RESPONSE_DISTRIBUTION_TARGET_MISSED");
        assertDocumented("DRILL-BACKLOG-SLO-BREACH",
                "RECALCULATION_BACKLOG_BEYOND_OBLIGATION");
    }

    @Test
    @DisplayName("TC-RUNBOOK-081-5 a failed newest sweep is never reported healthy")
    void failedReconciliationDrill() throws IOException {
        AvailabilityRecalculationRepository queue = healthyRepository();
        when(queue.latestRun(ORGANIZATION)).thenReturn(Optional.of(
                new AvailabilityRecalculationRepository.LatestRun(UUID.randomUUID(),
                        "FAILED", 2, "VARIANT_REFRESH_FAILED", NOW)));

        AvailabilityOperationsHealth.LoopHealth health = health(queue);
        assertThat(health.healthy()).isFalse();
        assertThat(health.incidents()).contains("RECONCILIATION_LAST_RUN_FAILED");
        assertThat(health.latestRunFailedVariants()).isEqualTo(2);
        assertThat(health.latestRunFailureCode()).isEqualTo("VARIANT_REFRESH_FAILED");
        assertDocumented("DRILL-FAILED-RECONCILIATION",
                "RECONCILIATION_LAST_RUN_FAILED");
    }

    private static AvailabilityRecalculationRepository healthyRepository() {
        AvailabilityRecalculationRepository queue =
                mock(AvailabilityRecalculationRepository.class);
        when(queue.lastCompletedRun(ORGANIZATION))
                .thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(5))));
        when(queue.latestRun(ORGANIZATION)).thenReturn(Optional.of(
                new AvailabilityRecalculationRepository.LatestRun(UUID.randomUUID(),
                        "COMPLETED", 0, null, NOW.minus(Duration.ofMinutes(5)))));
        when(queue.backlog(ORGANIZATION, NOW)).thenReturn(
                new AvailabilityRecalculationRepository.Backlog(0, Duration.ZERO));
        when(queue.latencySummary(ORGANIZATION, AvailabilityLane.CRITICAL.name(),
                NOW.minus(Duration.ofHours(24)), NOW)).thenReturn(
                new AvailabilityRecalculationRepository.LatencySummary(0, 0, 0, 0));
        return queue;
    }

    private static AvailabilityOperationsHealth.LoopHealth health(
            AvailabilityRecalculationRepository queue) {
        return new AvailabilityOperationsHealth(queue, Clock.fixed(NOW, ZoneOffset.UTC))
                .health(ORGANIZATION);
    }

    private static void assertDocumented(String drill, String signal) throws IOException {
        String document = Files.readString(RUNBOOK);
        int start = document.indexOf("### " + drill);
        assertThat(start).as(drill).isGreaterThanOrEqualTo(0);
        int next = document.indexOf("\n### ", start + 4);
        String section = document.substring(start, next < 0 ? document.length() : next);
        assertThat(section).contains("- Signal:", "- Action:", "- Exit:", signal);
    }
}
