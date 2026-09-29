package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.FactWriteRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.InternalReferenceRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreMasterDataRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adopt the unit cost a seller entered at the marketplace as the purchase cost
 * of the mapped internal variant.
 *
 * <p>The Owner accepted Ozon's {@code net_price} as the pilot's cost source; a
 * person still adopts each value, because a cost version is what every profit
 * figure and price guardrail is computed from. The adopted version names the
 * marketplace answer as its evidence and the adopting person as its recorder,
 * and starts when the marketplace stated the value.
 *
 * <p>All items are adopted in one transaction or none are: a reviewer who
 * approved a list expects the list, not the part of it that happened to pass.
 */
@Service
public class MarketplaceCostAdoptionService {

    /** The cost kind the analytics engine reads as unit cost. */
    private static final String PURCHASE = "PURCHASE";

    /** At most this many listing variants per adoption. */
    static final int MAXIMUM_ITEMS = 500;

    private final StoreMasterDataRepository masterData;
    private final InternalReferenceRepository references;
    private final FactWriteRepository facts;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    MarketplaceCostAdoptionService(StoreMasterDataRepository masterData,
                                   InternalReferenceRepository references,
                                   FactWriteRepository facts,
                                   MetadataAuditRecorder auditRecorder,
                                   IdGenerator idGenerator,
                                   Clock clock) {
        this.masterData = masterData;
        this.references = references;
        this.facts = facts;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Adopt each listing variant's newest seller cost as its internal variant's
     * purchase cost.
     *
     * <p>An item whose purchase cost in force already equals the statement is
     * left alone and reported as unchanged. An item that cannot be adopted —
     * not mapped, an open conflict, a statement that is not the newest, no
     * seller cost, or a cost in force that starts at or after the statement —
     * refuses the whole adoption.
     */
    @Transactional
    public Result adopt(AuthenticatedActor actor, UUID storeId, List<Item> items, String reason) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (items == null || items.isEmpty() || items.size() > MAXIMUM_ITEMS) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Set<UUID> seen = new HashSet<>();
        Instant now = clock.instant();
        List<Adopted> adopted = new ArrayList<>();
        int unchanged = 0;
        for (Item item : items) {
            if (item == null || item.listingVariantId() == null || item.priceObservationId() == null
                    || !seen.add(item.listingVariantId())) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            StoreMasterDataRepository.AdoptionContext context = masterData
                    .adoptionContext(actor.organizationId(), storeId, item.listingVariantId(),
                            item.priceObservationId())
                    .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
            if (context.conflictOpen() || !"ACTIVE".equals(context.variantStatus())
                    || !context.newest() || context.sellerCostPrice() == null
                    || context.sellerCostPrice().signum() <= 0) {
                throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
            }
            Optional<StoreMasterDataRepository.CurrentCost> current =
                    masterData.currentPurchaseCost(context.productVariantId());
            if (current.isPresent()
                    && current.get().currencyCode().equals(context.currencyCode())
                    && current.get().unitCost().compareTo(context.sellerCostPrice()) == 0) {
                unchanged++;
                continue;
            }
            Instant from = context.observedAt();
            if (current.isPresent() && !current.get().effectiveFrom().isBefore(from)) {
                // The cost in force starts at or after this statement: adopting it
                // would rewrite history rather than succeed it.
                throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
            }
            UUID provenanceId = facts.recordProvenance(idGenerator.newId(), actor.organizationId(),
                    "MARKETPLACE_RAW", context.rawObservationId(), null, actor.userId(), from, now,
                    "marketplace seller cost (Ozon net_price) of price observation "
                            + item.priceObservationId() + " adopted as purchase cost: " + validReason);
            references.endOpenCostVersion(context.productVariantId(), PURCHASE, from, validReason);
            UUID costVersionId = idGenerator.newId();
            references.insertCostVersion(costVersionId, actor.organizationId(),
                    context.productVariantId(), PURCHASE, context.currencyCode(),
                    context.sellerCostPrice(), provenanceId, from, now);
            auditRecorder.recordChange(new MetadataAuditChange(
                    AuditSourceDomain.OPERATING_FACTS, actor.userId().toString(),
                    AuditAction.CREATE, ManualFactEntryService.COST_ENTITY_TYPE, costVersionId,
                    context.skuCode(),
                    Map.of("unitCost", new FieldChange(
                                    current.map(cost -> cost.unitCost().toPlainString()).orElse(null),
                                    context.sellerCostPrice().toPlainString()),
                            "currencyCode", new FieldChange(
                                    current.map(StoreMasterDataRepository.CurrentCost::currencyCode)
                                            .orElse(null),
                                    context.currencyCode()),
                            "effectiveFrom", new FieldChange(null, from.toString()),
                            "source", new FieldChange(null,
                                    "MARKETPLACE_SELLER_COST:" + item.priceObservationId())),
                    validReason, null));
            adopted.add(new Adopted(item.listingVariantId(), context.productVariantId(), costVersionId));
        }
        return new Result(adopted, unchanged);
    }

    /** One listing variant and the price observation whose seller cost is adopted. */
    public record Item(UUID listingVariantId, UUID priceObservationId) {
    }

    /** One adopted cost version. */
    public record Adopted(UUID listingVariantId, UUID productVariantId, UUID costVersionId) {
    }

    /**
     * What an adoption did.
     *
     * @param unchanged items whose purchase cost in force already equalled the statement
     */
    public record Result(List<Adopted> adopted, int unchanged) {
    }
}
