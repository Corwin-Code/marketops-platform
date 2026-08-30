package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.PriceChangeHistory;
import com.mimococo.marketops.marketplaceintegration.PriceCommandGateway;
import com.mimococo.marketops.marketplaceintegration.PriceCommandRequest;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PriceCommandRepository;
import com.mimococo.marketops.shared.CorrelationId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating commands, reading them back, and answering what this product has
 * already done to a price.
 *
 * <p>Creating a command makes no call. Every condition that decides whether a
 * write may happen is evaluated inside the transaction that claims the command
 * for a worker, so the gate is read at the moment of the write rather than at
 * the moment somebody asked for it.
 *
 * <p>Submission is idempotent on the proposal. The idempotency key is derived
 * from the proposal, the decision that authorized it and the price it asks for,
 * so the same authorized change always produces the same key: a duplicate
 * submission finds the existing command, and a platform that sees the key twice
 * knows it is one change.
 */
@Service
public class PriceCommandService implements PriceCommandGateway, PriceChangeHistory {

    static final String ENTITY_TYPE = "price-command";

    private final PriceCommandRepository commands;
    PriceCommandService(PriceCommandRepository commands) {
        this.commands = commands;
    }

    @Override
    @Transactional
    public UUID submit(PriceCommandRequest request) {
        return commands.create(request.recommendationId(), request.expectedVersion(),
                request.actorId(), CorrelationId.current());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PriceCommandView> find(UUID commandId) {
        return commands.find(commandId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PriceCommandView> forRecommendation(UUID recommendationId) {
        return commands.forRecommendation(recommendationId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PriceCommandView> needingOperator(UUID storeId, int limit) {
        return commands.needingOperator(storeId, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal cumulativeChangeRate(UUID platformListingVariantId, Instant since) {
        return commands.cumulativeChangeRate(platformListingVariantId, since);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal cumulativeChangeRate(UUID platformListingVariantId, Instant since,
                                           Instant at) {
        return commands.cumulativeChangeRate(platformListingVariantId, since, at);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastChangeAt(UUID platformListingVariantId) {
        return commands.lastChangeAt(platformListingVariantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastChangeAt(UUID platformListingVariantId, Instant at) {
        return commands.lastChangeAt(platformListingVariantId, at);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> priceChangeCapability(String platformCode) {
        return commands.priceChangeCapability(platformCode);
    }

    /** Why the write gate is currently closed for a command, if it is. */
    @Transactional(readOnly = true)
    public List<String> gateReasons(UUID commandId) {
        return commands.gateReasons(commandId);
    }

}
