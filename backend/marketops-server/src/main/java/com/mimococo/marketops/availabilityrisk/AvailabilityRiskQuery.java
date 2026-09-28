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
     * One page of the queue, most urgent first, with the matching total.
     *
     * <p>The caller supplies the store scope it has already been authorized
     * for. Passing an empty scope returns nothing rather than everything: an
     * empty grant is a denial, never an absence of filtering.
     *
     * <p>{@code query} narrows by SKU or variant name and is matched literally;
     * {@code null} or blank means no search.
     */
    AvailabilityQueuePage queue(UUID organizationId, List<UUID> permittedStoreIds,
                                List<UUID> permittedProductVariantIds, String laneFilter,
                                String query, int limit, int offset);

    /** One card with every child, factor and window behind it. */
    Optional<AvailabilityCardView> card(UUID organizationId, UUID productVariantId,
                                        List<UUID> permittedStoreIds,
                                        List<UUID> permittedProductVariantIds);
}
