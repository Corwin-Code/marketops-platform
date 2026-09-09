package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.listingconversion.RecalculationClass;
import com.mimococo.marketops.listingconversion.RecalculationQueueView;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.GovernanceRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the recalculation queue, class by class, and records the latency.
 *
 * <p>RISK before ORDINARY before FULL_REVIEW. Each item is its own committed
 * step, so a failed recalculation records its failure and does not hold the
 * rest of the queue.
 */
@Service
public class RecalculationService {

    private static final Logger log = LoggerFactory.getLogger(RecalculationService.class);

    private final GovernanceRepository governance;
    private final ListingHealthService health;
    private final IdGenerator ids;
    private final Clock clock;

    RecalculationService(GovernanceRepository governance, ListingHealthService health, IdGenerator ids, Clock clock) {
        this.governance = governance;
        this.health = health;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public UUID enqueue(UUID organizationId, UUID listingId, RecalculationClass triggerClass, String reference,
                        Instant sourceTime) {
        UUID id = ids.newId();
        governance.enqueue(id, organizationId, listingId, triggerClass, reference, sourceTime, clock.instant());
        return id;
    }

    @Transactional(propagation = Propagation.NEVER)
    public int runOnce(int limit) {
        int finished = 0;
        for (GovernanceRepository.QueuedRow row : governance.claim(limit, clock.instant())) {
            try {
                health.recompute(row.listingId(), "SCHEDULED");
                governance.finish(row.id(), null, null, clock.instant());
                finished++;
            } catch (RuntimeException failure) {
                log.warn("event=lc_recalculation_failed queueId={} failureType={}", row.id(),
                        failure.getClass().getSimpleName());
                governance.finish(row.id(), null, "recalculation_failed", clock.instant());
            }
        }
        return finished;
    }

    @Transactional(readOnly = true)
    public List<RecalculationQueueView> queue(UUID organizationId, int limit) {
        return governance.queue(organizationId, limit);
    }
}
