package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.AdBidCommandGateway;
import com.mimococo.marketops.marketplaceintegration.AdBidCommandRequest;
import com.mimococo.marketops.marketplaceintegration.AdBidCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.AdBidCommandRepository;
import com.mimococo.marketops.shared.CorrelationId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The advertising execution boundary, as the workflow sees it.
 *
 * <p>Thin on purpose. Creating a command is one call to one {@code SECURITY
 * DEFINER} function that checks everything in one transaction against the row
 * versions that existed at that instant; anything this class checked first would
 * be a second opinion that could disagree with the one that counts.
 */
@Service
class AdBidCommandService implements AdBidCommandGateway {

    private final AdBidCommandRepository commands;

    AdBidCommandService(AdBidCommandRepository commands) {
        this.commands = commands;
    }

    @Override
    @Transactional
    public UUID submit(AdBidCommandRequest request) {
        return commands.create(request.recommendationId(), request.expectedVersion(),
                request.reservationId(), CorrelationId.current());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdBidCommandView> forRecommendation(UUID recommendationId) {
        return commands.forRecommendation(recommendationId).flatMap(commands::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdBidCommandView> command(UUID commandId) {
        return commands.view(commandId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> gateReasons(UUID commandId) {
        return commands.gateReasons(commandId);
    }
}
