package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.internal.domain.AvailabilitySlo;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityTraceRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recalculates exactly the variants that accepted facts invalidated.
 *
 * <p>The pass itself runs in no transaction, and each variant's recalculation
 * opens its own inside {@link AvailabilityRiskRefreshService}. One variant whose
 * calculation fails must not roll back the ten that succeeded beside it, and a
 * worker that dies holding a lease must leave the other nine finished rather
 * than all ten pending.
 *
 * <p>The latency of every recalculation is written down whether it met the
 * obligation or not. Keeping only the breaches would leave nothing to compute a
 * percentile from, and the percentile is the number the Contract asks for.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class AvailabilityTargetedWorker {

    private static final Logger LOG = LoggerFactory.getLogger(AvailabilityTargetedWorker.class);

    /** How long a claimed request stays claimed before another worker may take it. */
    private static final Duration LEASE = Duration.ofMinutes(5);

    /** Attempts after which a request is abandoned rather than retried forever. */
    private static final int MAX_ATTEMPTS = 5;

    private final AvailabilityRecalculationRepository queue;
    private final AvailabilityRiskRefreshService refresh;
    private final IdGenerator ids;
    private final Clock clock;
    private final AvailabilityTraceRepository trace;

    public AvailabilityTargetedWorker(AvailabilityRecalculationRepository queue,
                                      AvailabilityRiskRefreshService refresh,
                                      IdGenerator ids, Clock clock,
                                      AvailabilityTraceRepository trace) {
        this.queue = queue;
        this.refresh = refresh;
        this.ids = ids;
        this.clock = clock;
        this.trace = trace;
    }

    /**
     * Take a bounded amount of work and finish it.
     *
     * @param limit the largest number of requests to claim
     * @return how many were recalculated
     */
    public int runOnce(int limit) {
        Instant now = clock.instant();
        String owner = "availability-targeted-" + ids.newId();
        List<AvailabilityRecalculationRepository.ClaimedRequest> claimed =
                queue.claim(owner, now.plus(LEASE), limit, now);

        int worked = 0;
        for (AvailabilityRecalculationRepository.ClaimedRequest request : claimed) {
            if (process(request)) {
                worked++;
            }
        }
        return worked;
    }

    /**
     * Recalculate one variant and record what it cost.
     *
     * <p>A failure is recorded on the request and left for another attempt
     * until the attempt bound; past that the request is abandoned so a variant
     * whose calculation is permanently broken cannot occupy the queue forever.
     * The hourly sweep still visits it.
     */
    boolean process(AvailabilityRecalculationRepository.ClaimedRequest request) {
        Instant startedAt = clock.instant();
        trace.record(request.organizationId(), request.productVariantId(),
                AvailabilityRiskRefreshService.TARGETED, "TARGETED_PROCESS_STARTED", "STARTED",
                request.correlationId(), null, request.id().toString(), "{}", startedAt);
        try {
            AvailabilityRiskRefreshService.RefreshOutcome outcome = refresh.refresh(
                    request.organizationId(), request.productVariantId(), startedAt,
                    AvailabilityRiskRefreshService.TARGETED, null, request.correlationId());
            Instant finishedAt = clock.instant();
            queue.finish(request.id(), "COMPLETED", null, finishedAt);
            recordLatency(request, outcome, finishedAt);
            trace.record(request.organizationId(), request.productVariantId(),
                    AvailabilityRiskRefreshService.TARGETED, "SLO_RECORDED", "COMPLETED",
                    outcome.correlationId(), request.correlationId(), request.id().toString(),
                    "{}", finishedAt);
            return true;
        } catch (RuntimeException failure) {
            // The reason is logged rather than stored: a failure code is a
            // stable operator-facing value and an exception message is not.
            LOG.warn("availability recalculation failed for variant {} on attempt {}",
                    request.productVariantId(), request.attemptCount(), failure);
            queue.finish(request.id(),
                    request.attemptCount() >= MAX_ATTEMPTS ? "ABANDONED" : "FAILED",
                    "RECALCULATION_FAILED", clock.instant());
            trace.record(request.organizationId(), request.productVariantId(),
                    AvailabilityRiskRefreshService.TARGETED, "SLO_RECORDED", "FAILED",
                    request.correlationId(), null, request.id().toString(), "{}",
                    clock.instant());
            return false;
        }
    }

    /**
     * Write down how long the answer took, from the fact rather than from now.
     *
     * <p>Source latency and internal latency are separate values because they
     * are separate incidents with separate owners: a marketplace that publishes
     * an hour late and a worker that runs an hour behind look identical in a
     * single combined number and need entirely different responses.
     */
    private void recordLatency(AvailabilityRecalculationRepository.ClaimedRequest request,
                               AvailabilityRiskRefreshService.RefreshOutcome outcome,
                               Instant finishedAt) {
        Instant factAcceptedAt = request.factAcceptedAt();
        Instant calculatedAt = finishedAt.isBefore(factAcceptedAt) ? factAcceptedAt : finishedAt;
        if (finishedAt.isBefore(factAcceptedAt)) {
            LOG.warn("a fact was accepted at {} which is after the current instant {};"
                    + " the recorded latency is zero rather than negative",
                    factAcceptedAt, finishedAt);
        }
        Duration internal = Duration.between(factAcceptedAt, calculatedAt);
        Optional<AvailabilityRecalculationRepository.SourceTiming> timing = sourceTiming(request);

        queue.recordObservation(new AvailabilityRecalculationRepository.SloObservation(
                ids.newId(), request.organizationId(), request.productVariantId(),
                outcome.written().lane().name(), AvailabilityRiskRefreshService.TARGETED,
                timing.map(AvailabilityRecalculationRepository.SourceTiming::sourceTime)
                        .orElse(null),
                timing.map(AvailabilityRecalculationRepository.SourceTiming::sourceTime)
                        .orElse(null),
                timing.map(AvailabilityRecalculationRepository.SourceTiming::ingestionTime)
                        .orElse(null),
                factAcceptedAt, calculatedAt,
                outcome.activation().raised().isEmpty()
                        && outcome.activation().refreshed().isEmpty() ? null : calculatedAt,
                internal.toMillis(), sourceLatencyMillis(timing, factAcceptedAt),
                AvailabilitySlo.breached(internal), outcome.correlationId()));
    }

    private Optional<AvailabilityRecalculationRepository.SourceTiming> sourceTiming(
            AvailabilityRecalculationRepository.ClaimedRequest request) {
        if (request.triggerReference() == null) {
            return Optional.empty();
        }
        try {
            return queue.provenanceTiming(UUID.fromString(request.triggerReference()));
        } catch (IllegalArgumentException notAnIdentity) {
            // A trigger reference is free text for triggers that are not facts.
            return Optional.empty();
        }
    }

    /**
     * How long the source itself took, when it said when the event happened.
     *
     * <p>Absent rather than zero when the source published no event time. Zero
     * would assert that the marketplace was instantaneous, which is a claim
     * about somebody else's system that nothing here observed.
     */
    private static Long sourceLatencyMillis(
            Optional<AvailabilityRecalculationRepository.SourceTiming> timing,
            Instant factAcceptedAt) {
        return timing.map(AvailabilityRecalculationRepository.SourceTiming::sourceTime)
                .filter(sourceTime -> !sourceTime.isAfter(factAcceptedAt))
                .map(sourceTime -> Duration.between(sourceTime, factAcceptedAt).toMillis())
                .orElse(null);
    }
}
