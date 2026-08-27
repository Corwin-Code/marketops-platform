package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.marketplaceintegration.PriceChangeHistory;
import com.mimococo.marketops.marketplaceintegration.PriceCommandGateway;
import com.mimococo.marketops.marketplaceintegration.PriceCommandRequest;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PriceCommandRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
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

    /** How much of the derived digest the idempotency key carries. */
    private static final int KEY_DIGEST_LENGTH = 40;

    private final PriceCommandRepository commands;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    PriceCommandService(PriceCommandRepository commands,
                        MetadataAuditRecorder auditRecorder,
                        IdGenerator idGenerator,
                        Clock clock) {
        this.commands = commands;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID submit(PriceCommandRequest request) {
        Optional<PriceCommandView> existing =
                commands.forRecommendation(request.recommendationId());
        if (existing.isPresent()) {
            return existing.get().id();
        }

        UUID id = idGenerator.newId();
        String idempotencyKey = idempotencyKey(request);
        commands.insert(id, request.organizationId(), request.recommendationId(),
                request.approvalDecisionId(), request.storeId(),
                request.platformListingVariantId(), request.platformCode(),
                request.capabilityId(), idempotencyKey,
                request.targetPrice().currencyCode(), request.priorPrice().amount(),
                request.targetPrice().amount(), request.priorPriceObservationId(),
                request.entityVersionDigest(), request.retryBudget(), clock.instant());

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, "price-command-gateway",
                AuditAction.COMMAND_TRANSITION, ENTITY_TYPE, id, idempotencyKey,
                java.util.Map.of(
                        "state", new FieldChange(null, "PENDING"),
                        "recommendationId", new FieldChange(null,
                                request.recommendationId().toString()),
                        "priorPrice", new FieldChange(null,
                                request.priorPrice().amount().toPlainString()),
                        "targetPrice", new FieldChange(null,
                                request.targetPrice().amount().toPlainString())),
                null, null));
        return id;
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
    public Optional<Instant> lastChangeAt(UUID platformListingVariantId) {
        return commands.lastChangeAt(platformListingVariantId);
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

    /**
     * The identity a platform retry must not duplicate.
     *
     * <p>Derived rather than random so that resubmitting the same authorized
     * change produces the same key. A random key would let a retry at this
     * level become a second change at the marketplace, which is the exact
     * failure idempotency exists to prevent.
     */
    private static String idempotencyKey(PriceCommandRequest request) {
        String digest = Digest.ofComponents(List.of(
                request.recommendationId().toString(),
                request.approvalDecisionId().toString(),
                request.platformListingVariantId().toString(),
                request.targetPrice().amount().toPlainString(),
                request.targetPrice().currencyCode()));
        return "pc-" + digest.substring(0, KEY_DIGEST_LENGTH);
    }
}
