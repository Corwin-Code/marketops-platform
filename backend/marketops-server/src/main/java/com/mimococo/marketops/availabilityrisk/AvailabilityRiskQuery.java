package com.mimococo.marketops.availabilityrisk;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Published read access to the availability queue.
 *
 * <p>Deliberately narrow. A console controller depending on a broad interface
 * would pull every implementation of it into the boundary the architecture
 * tests walk, and the queue needs exactly two questions answered.
 */
public interface AvailabilityRiskQuery {

    /**
     * The queue, most urgent first.
     *
     * <p>The caller supplies the store scope it has already been authorized
     * for. Passing an empty scope returns nothing rather than everything: an
     * empty grant is a denial, never an absence of filtering.
     */
    List<AvailabilityCardView> queue(UUID organizationId, List<UUID> permittedStoreIds,
                                     List<UUID> permittedProductVariantIds,
                                     String laneFilter, int limit, int offset);

    /** One card with every child, factor and window behind it. */
    Optional<AvailabilityCardView> card(UUID organizationId, UUID productVariantId,
                                        List<UUID> permittedStoreIds,
                                        List<UUID> permittedProductVariantIds);
}
