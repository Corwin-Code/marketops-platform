package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.UUID;

/**
 * A canonical fact was accepted, and something derived from it is now stale.
 *
 * <p>This is a pull feed rather than a push notification, and deliberately so.
 * A module that consumes facts cannot be called back into by the module that
 * owns them without creating a dependency cycle, and a queue written by the
 * producer would make the fact authority responsible for its consumers'
 * schedules.
 *
 * <p>{@code factAcceptedAt} is where the internal response clock starts. It is
 * the ingestion instant of the fact itself, not the moment a worker noticed it,
 * so a backlog shows up as latency rather than disappearing into it.
 *
 * @param provenanceId the accepted fact's provenance
 * @param organizationId the owning organization
 * @param platformListingVariantId the listing variant affected, or {@code null}
 * @param productVariantId the internal variant affected, or {@code null}
 * @param triggerClass which kind of evidence changed
 * @param factAcceptedAt when the fact entered the system
 * @param sourceTime when the source considered it true, or {@code null}
 */
public record AcceptedFactChange(
        UUID provenanceId,
        UUID organizationId,
        UUID platformListingVariantId,
        UUID productVariantId,
        String triggerClass,
        Instant factAcceptedAt,
        Instant sourceTime) {
}
