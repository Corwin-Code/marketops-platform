package com.mimococo.marketops.marketplaceintegration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The published way to ask for a price to change on a marketplace.
 *
 * <p>Submitting creates a command; it does not make a call. Everything that
 * decides whether the call may happen — the capability's verification, the
 * switches, the allowlist, the authorization, the mapping and the deterministic
 * guardrail — is evaluated inside the transaction that claims the command for a
 * worker. That is deliberate: a gate checked at submission would be a gate that
 * was true at some earlier moment.
 *
 * <p>The workflow module asks; it does not execute. Keeping the wire protocol,
 * the retries, the readback and the compensation on this side of the boundary
 * means there is one writer for the command tables and one place a marketplace
 * fact can live.
 */
public interface PriceCommandGateway {

    /**
     * Create a command for an authorized proposal.
     *
     * <p>Idempotent on the proposal: submitting the same recommendation twice
     * returns the command that already exists rather than creating a second one,
     * because two commands for one approval would be two licences to write.
     */
    UUID submit(PriceCommandRequest request);

    /** One command with its attempts and readbacks. */
    Optional<PriceCommandView> find(UUID commandId);

    /** The command created for one proposal, if there is one. */
    Optional<PriceCommandView> forRecommendation(UUID recommendationId);

    /** Commands of one store that a person has to look at. */
    List<PriceCommandView> needingOperator(UUID storeId, int limit);

    /**
     * The verified price-change capability of one marketplace, if there is one.
     *
     * <p>A caller asking for a command must name the capability the write goes
     * through, and only this side of the boundary knows which capabilities are
     * registered and verified. An unverified marketplace resolves to nothing, so
     * a command for it cannot even be created.
     */
    Optional<UUID> priceChangeCapability(String platformCode);
}
