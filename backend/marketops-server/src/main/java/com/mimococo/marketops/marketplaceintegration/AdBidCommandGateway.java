package com.mimococo.marketops.marketplaceintegration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way the workflow reaches the advertising execution boundary.
 *
 * <p>Four methods, and none of them can make a bid change happen. Submitting
 * creates an outbox row that a gate will still refuse unless every authority
 * holds; reading tells the workflow what the boundary knows; and the gate
 * reasons are readable so a refusal can be explained to the person who asked for
 * it rather than appearing as a silent nothing.
 *
 * <p>{@code operationsworkflow} depends on this interface and on nothing else in
 * this module. It never touches a port, an adapter, a repository or the command
 * tables, which is an architecture test rather than a convention.
 */
public interface AdBidCommandGateway {

    /**
     * Create the command for one approved recommendation, or return the one that
     * already exists.
     *
     * <p>Idempotent on the recommendation: one approval produces one command
     * however many times this is called.
     */
    UUID submit(AdBidCommandRequest request);

    /** The command for one recommendation, if the boundary has made one. */
    Optional<AdBidCommandView> forRecommendation(UUID recommendationId);

    /** One command with its attempts and readbacks. */
    Optional<AdBidCommandView> command(UUID commandId);

    /**
     * Why the write gate would refuse this command right now.
     *
     * <p>An empty list means it would not — which is not the same as saying it
     * will still be empty when the worker asks, because the gate is evaluated
     * again at lease and again immediately before transmission.
     */
    List<String> gateReasons(UUID commandId);
}
