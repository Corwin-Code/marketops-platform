package com.mimococo.marketops.marketplaceintegration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The description execution boundary, as the workflow sees it.
 *
 * <p>Thin on purpose, like the advertising gateway. Creating a command is one
 * call to one {@code SECURITY DEFINER} function that checks everything in one
 * transaction; the gate reasons are the database's own answer.
 */
public interface ListingDescriptionCommandGateway {

    /** Create, or return, the one command for a launched description action. */
    UUID submit(ListingDescriptionCommandRequest request);

    Optional<ListingDescriptionCommandView> forAction(UUID actionId);

    Optional<ListingDescriptionCommandView> forRecommendation(UUID recommendationId);

    Optional<ListingDescriptionCommandView> command(UUID commandId);

    /** Every reason the write gate currently refuses; empty means open. */
    List<String> gateReasons(UUID commandId);
}
