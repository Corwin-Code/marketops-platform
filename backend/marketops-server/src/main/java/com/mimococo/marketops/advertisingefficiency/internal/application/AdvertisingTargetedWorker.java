package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.domain.AdvertisingSlo;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingRecalculationRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingTraceRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the targeted queue.
 *
 * <p>The pass itself runs outside any transaction; each claimed request opens
 * its own inside {@link AdvertisingCaseRefreshService}. One object that fails
 * therefore costs one object rather than the batch, which is the difference
 * between a slow recovery and a stalled queue.
 *
 * <p>A failure is recorded as a stable failure code, never as an exception
 * message. A code is something an operator can look up and a runbook can name; a
 * message is something a library changes between versions.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
class AdvertisingTargetedWorker {

    private static final Logger log = LoggerFactory.getLogger(AdvertisingTargetedWorker.class);

    /** How long a claimed request stays claimed before another worker may take it. */
    private static final Duration LEASE = Duration.ofMinutes(5);

    /** After this many attempts the request is abandoned rather than retried for ever. */
    private static final int MAX_ATTEMPTS = 5;

    private final AdvertisingRecalculationRepository queue;
    private final AdvertisingCaseRefreshService refresh;
    private final AdvertisingTraceRepository trace;
    private final IdGenerator ids;
    private final Clock clock;

    AdvertisingTargetedWorker(
            AdvertisingRecalculationRepository queue,
            AdvertisingCaseRefreshService refresh,
            AdvertisingTraceRepository trace,
            IdGenerator ids,
            Clock clock) {
        this.queue = queue;
        this.refresh = refresh;
        this.trace = trace;
        this.ids = ids;
        this.clock = clock;
    }

    /** Claim and process up to {@code limit} requests. Returns how many were handled. */
    int runOnce(int limit) {
        Instant now = clock.instant();
        String owner = "advertising-targeted-" + ids.newId();
        var claimed = queue.claim(owner, now.plus(LEASE), limit, now);
        int handled = 0;
        for (var request : claimed) {
            if (process(request)) {
                handled++;
            }
        }
        return handled;
    }

    boolean process(AdvertisingRecalculationRepository.ClaimedRequest request) {
        Instant startedAt = clock.instant();
        try {
            Optional<AdvertisingCaseRefreshService.RefreshOutcome> outcome = refresh.refresh(
                    request.organizationId(), request.adNativeObjectId(), startedAt,
                    AdvertisingProjectionWriter.TARGETED, null, request.correlationId());
            Instant finishedAt = clock.instant();
            queue.finish(request.id(), "COMPLETED", null, finishedAt);
            recordLatency(request, outcome, finishedAt);
            return true;
        } catch (RuntimeException failure) {
            Instant finishedAt = clock.instant();
            boolean exhausted = request.attemptCount() >= MAX_ATTEMPTS;
            queue.finish(request.id(), exhausted ? "ABANDONED" : "FAILED",
                    "ADVERTISING_RECALCULATION_FAILED", finishedAt);
            // The message is logged for a human and never stored: a failure code is
            // a stable operator-facing value and an exception message is not.
            log.warn("event=advertising_targeted_failed adNativeObjectId={} attempt={} reason={}",
                    request.adNativeObjectId(), request.attemptCount(), failure.toString());
            trace.record(ids.newId(), request.organizationId(), request.adNativeObjectId(),
                    "TARGETED", "SLO_RECORDED", "FAILED", request.correlationId(), null,
                    request.adNativeObjectId().toString(), "{}", finishedAt);
            return false;
        }
    }

    /**
     * Record how long MarketOps took, separately from how long the source took.
     *
     * <p>The internal clock starts at the instant the fact was accepted, not when
     * a worker picked the work up, because the wait in the queue is exactly the
     * latency this measures. A negative source latency is recorded as absent
     * rather than as zero: it means a clock disagreed, which is a different
     * problem from a fast publisher.
     */
    private void recordLatency(
            AdvertisingRecalculationRepository.ClaimedRequest request,
            Optional<AdvertisingCaseRefreshService.RefreshOutcome> outcome,
            Instant finishedAt) {
        Duration internal = Duration.between(request.factAcceptedAt(), finishedAt);
        if (internal.isNegative()) {
            log.warn("event=advertising_latency_negative adNativeObjectId={}",
                    request.adNativeObjectId());
            internal = Duration.ZERO;
        }
        String lane = outcome
                .map(result -> result.written().cases().isEmpty()
                        ? "WATCH" : result.written().cases().getFirst().lane())
                .orElse("WATCH");
        trace.recordSlo(ids.newId(), request.organizationId(), request.adNativeObjectId(),
                outcome.filter(result -> !result.written().cases().isEmpty())
                        .map(result -> result.written().cases().getFirst().caseId())
                        .orElse(null),
                lane, "TARGETED", null, null, null, request.factAcceptedAt(), finishedAt,
                outcome.map(result -> finishedAt).orElse(null),
                internal.toMillis(), null,
                AdvertisingSlo.breached(internal), request.correlationId());
    }
}
