package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
import com.mimococo.marketops.operatingfacts.AcceptedFactChange;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns accepted facts into recalculation work.
 *
 * <p>The feed is pulled. Nothing here is called back into by the module that
 * owns the facts, so the fact authority never depends on its consumers'
 * schedules and a slow worker cannot slow an ingestion.
 *
 * <p>The response clock starts at the instant the fact was accepted, not at the
 * instant this scan noticed it. That is deliberate: measuring from the scan
 * would make a backlog invisible, and a backlog is the one thing the clock
 * exists to expose.
 */
@Service
public class AvailabilityTriggerIngestionService {

    private static final Logger LOG =
            LoggerFactory.getLogger(AvailabilityTriggerIngestionService.class);

    private final OperatingFactQuery facts;
    private final ListingIdentityDirectory listings;
    private final AvailabilityRecalculationRepository queue;
    private final IdGenerator ids;
    private final Clock clock;

    public AvailabilityTriggerIngestionService(OperatingFactQuery facts,
                                               ListingIdentityDirectory listings,
                                               AvailabilityRecalculationRepository queue,
                                               IdGenerator ids, Clock clock) {
        this.facts = facts;
        this.listings = listings;
        this.queue = queue;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Read one page of accepted facts and queue what they invalidate.
     *
     * @param limit the largest page to read
     * @return what the scan found and what it queued
     */
    @Transactional
    public ScanResult scanOnce(int limit) {
        Instant now = clock.instant();
        queue.startCursor(now);
        Instant position = queue.cursorPosition().orElse(now);

        var changes = facts.factsAcceptedSince(position, limit);
        if (changes.isEmpty()) {
            queue.advanceCursor(position, now, 0);
            return new ScanResult(0, 0, 0, position);
        }

        java.util.Set<UUID> queued = new java.util.LinkedHashSet<>();
        int unmapped = 0;
        Instant furthest = position;
        String correlationId = "availability-scan:" + ids.newId();
        for (AcceptedFactChange change : changes) {
            if (change.factAcceptedAt().isAfter(furthest)) {
                furthest = change.factAcceptedAt();
            }
            Optional<UUID> variant = variantOf(change);
            if (variant.isEmpty()) {
                // A fact about a listing nobody has mapped has no internal
                // variant to recalculate. It is counted rather than dropped
                // silently: an unmapped listing is a mapping problem somebody
                // owns, and that problem is invisible if this reads as nothing.
                unmapped++;
                continue;
            }
            boolean enqueued = queue.enqueue(new AvailabilityRecalculationRepository.NewRequest(
                    ids.newId(), change.organizationId(), variant.get(), change.triggerClass(),
                    change.provenanceId() == null ? null : change.provenanceId().toString(),
                    change.factAcceptedAt(), now, correlationId));
            if (enqueued) {
                queued.add(variant.get());
            }
        }
        queue.advanceCursor(furthest, now, changes.size());
        if (unmapped > 0) {
            LOG.info("availability scan skipped {} accepted facts with no mapped internal variant",
                    unmapped);
        }
        return new ScanResult(changes.size(), queued.size(), unmapped, furthest);
    }

    /**
     * Which internal variant a fact is about.
     *
     * <p>A fact that already names an internal variant is taken at its word. A
     * fact about a listing is resolved through the mapping authority at the
     * instant the fact was accepted, so a mapping published afterwards cannot
     * retroactively change which variant an old fact belonged to.
     */
    private Optional<UUID> variantOf(AcceptedFactChange change) {
        if (change.productVariantId() != null) {
            return Optional.of(change.productVariantId());
        }
        if (change.platformListingVariantId() == null) {
            return Optional.empty();
        }
        return listings.internalVariantAt(change.platformListingVariantId(),
                change.factAcceptedAt());
    }

    /**
     * What one scan did.
     *
     * @param scanned accepted facts read
     * @param queued distinct variants left with pending work
     * @param unmapped facts with no mapped internal variant
     * @param position where the feed is now read to
     */
    public record ScanResult(int scanned, int queued, int unmapped, Instant position) {
    }
}
