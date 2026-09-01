package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.internal.domain.AvailabilitySlo;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether the availability loop is actually keeping its promises.
 *
 * <p>A missed sweep, a growing backlog and a breached response obligation are
 * each an incident somebody has to see. The alternative — a queue that silently
 * falls behind — looks exactly like a quiet week from the outside, and the
 * queue's whole value is that an operator can trust it is current.
 *
 * <p>Nothing here judges a business risk. It judges this Slice's own machinery,
 * which is why it reports latencies and cadences rather than lanes.
 */
@Service
public class AvailabilityOperationsHealth {

    /** How often the portfolio is expected to be reconciled. */
    public static final Duration EXPECTED_SWEEP_INTERVAL = Duration.ofHours(1);

    /** How far past the cadence a missed sweep becomes an incident. */
    private static final Duration SWEEP_GRACE = Duration.ofMinutes(15);

    /** The window the response obligation is measured over. */
    private static final Duration MEASUREMENT_WINDOW = Duration.ofHours(24);

    private final AvailabilityRecalculationRepository queue;
    private final Clock clock;

    public AvailabilityOperationsHealth(AvailabilityRecalculationRepository queue, Clock clock) {
        this.queue = queue;
        this.clock = clock;
    }

    /**
     * How the loop is doing for one organization.
     *
     * @param organizationId the organization to report on
     * @return the current state, and every incident it implies
     */
    @Transactional(readOnly = true)
    public LoopHealth health(UUID organizationId) {
        Instant now = clock.instant();
        Optional<Instant> lastSweep = queue.lastCompletedRun(organizationId);
        Optional<AvailabilityRecalculationRepository.LatestRun> latestRun =
                queue.latestRun(organizationId);
        AvailabilityRecalculationRepository.Backlog backlog = queue.backlog(organizationId, now);
        AvailabilityRecalculationRepository.LatencySummary critical = queue.latencySummary(
                organizationId, AvailabilityLane.CRITICAL.name(),
                now.minus(MEASUREMENT_WINDOW), now);

        List<String> incidents = new ArrayList<>();
        boolean sweepOverdue = lastSweep.isEmpty()
                || lastSweep.get().isBefore(now.minus(EXPECTED_SWEEP_INTERVAL).minus(SWEEP_GRACE));
        if (sweepOverdue) {
            incidents.add("RECONCILIATION_SWEEP_OVERDUE");
        }
        if (latestRun.filter(run -> "FAILED".equals(run.state())).isPresent()) {
            incidents.add("RECONCILIATION_LAST_RUN_FAILED");
        }
        // The oldest waiting fact, not the queue depth: a thousand requests
        // queued a second ago are healthy, and one queued an hour ago is not.
        if (backlog.oldestAge().compareTo(AvailabilitySlo.HARD_BOUND) > 0) {
            incidents.add("RECALCULATION_BACKLOG_BEYOND_OBLIGATION");
        }
        if (critical.breaches() > 0) {
            incidents.add("CRITICAL_RESPONSE_HARD_BOUND_BREACHED");
        }
        boolean targetMet = AvailabilitySlo.distributionTargetMet(AvailabilityLane.CRITICAL,
                Duration.ofMillis(critical.p95LatencyMillis()));
        if (critical.observations() > 0 && !targetMet) {
            incidents.add("CRITICAL_RESPONSE_DISTRIBUTION_TARGET_MISSED");
        }

        return new LoopHealth(organizationId, lastSweep.orElse(null), sweepOverdue,
                latestRun.map(AvailabilityRecalculationRepository.LatestRun::state).orElse(null),
                latestRun.map(AvailabilityRecalculationRepository.LatestRun::failureCode)
                        .orElse(null),
                latestRun.map(AvailabilityRecalculationRepository.LatestRun::failedVariantCount)
                        .orElse(0),
                backlog.pending(), backlog.oldestAge(), critical.observations(),
                Duration.ofMillis(critical.p95LatencyMillis()),
                Duration.ofMillis(critical.worstLatencyMillis()), critical.breaches(), targetMet,
                List.copyOf(incidents));
    }

    /**
     * The state of the availability loop for one organization.
     *
     * @param organizationId the organization
     * @param lastCompletedSweep when the portfolio was last fully reconciled
     * @param sweepOverdue whether the cadence has been missed
     * @param latestRunState state of the newest attempt, or {@code null}
     * @param latestRunFailureCode its failure code, or {@code null}
     * @param latestRunFailedVariants variants it could not refresh
     * @param pendingRequests recalculations waiting
     * @param oldestPendingAge how long the oldest has waited since its fact
     * @param criticalObservations critical recalculations measured in the window
     * @param criticalP95 the measured 95th percentile response
     * @param criticalWorst the slowest measured response
     * @param criticalBreaches responses past the hard bound
     * @param distributionTargetMet whether the critical percentile target held
     * @param incidents every operator-visible incident this state implies
     */
    public record LoopHealth(UUID organizationId, Instant lastCompletedSweep, boolean sweepOverdue,
                             String latestRunState, String latestRunFailureCode,
                             int latestRunFailedVariants,
                             int pendingRequests, Duration oldestPendingAge,
                             int criticalObservations, Duration criticalP95,
                             Duration criticalWorst, int criticalBreaches,
                             boolean distributionTargetMet, List<String> incidents) {

        /** Whether anything here needs an operator. */
        public boolean healthy() {
            return incidents.isEmpty();
        }
    }
}
