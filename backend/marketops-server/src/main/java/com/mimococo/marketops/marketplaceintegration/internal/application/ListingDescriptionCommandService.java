package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.ListingDescriptionCommandGateway;
import com.mimococo.marketops.marketplaceintegration.ListingDescriptionCommandRequest;
import com.mimococo.marketops.marketplaceintegration.ListingDescriptionCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ListingDescriptionCommandRepository;
import com.mimococo.marketops.shared.CorrelationId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The description execution boundary, as the workflow sees it: one function, one transaction. */
@Service
class ListingDescriptionCommandService implements ListingDescriptionCommandGateway {

    private final ListingDescriptionCommandRepository commands;

    ListingDescriptionCommandService(ListingDescriptionCommandRepository commands) {
        this.commands = commands;
    }

    @Override
    @Transactional
    public UUID submit(ListingDescriptionCommandRequest request) {
        return commands.create(request.actionId(), request.actorUserId(), request.expectedVersion(),
                CorrelationId.current());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ListingDescriptionCommandView> forAction(UUID actionId) {
        return commands.forAction(actionId).flatMap(commands::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ListingDescriptionCommandView> forRecommendation(UUID recommendationId) {
        return commands.forRecommendation(recommendationId).flatMap(commands::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ListingDescriptionCommandView> command(UUID commandId) {
        return commands.view(commandId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> gateReasons(UUID commandId) {
        return commands.gateReasons(commandId);
    }
}
