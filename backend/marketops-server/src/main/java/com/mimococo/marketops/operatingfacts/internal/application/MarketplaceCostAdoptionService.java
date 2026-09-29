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
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreMasterDataRepository.CostInput;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreMasterDataRepository.CurrentCost;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <p>The Owner accepted Ozon's {@code net_price} as the pilot's cost source. A
 * cost is adopted either by a person, for the listings they selected, or under
 * the store's master-data policy, for every changed cost that passes
 * {@link SellerCostCheck}. Either way the adopted version names the
 * marketplace answer as its evidence and a person as its recorder — the one
 * who acted, or the one whose standing authorization it rests on — and starts
 * when the marketplace stated the value.
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
     * Adopt each selected listing variant's newest seller cost as its internal
     * variant's purchase cost, all in one transaction or none.
     *
     * <p>An item whose purchase cost in force already equals the statement is
     * left alone and reported as unchanged. An item that cannot be adopted —
     * not mapped, an open conflict, a statement that is not the newest, no
     * seller cost, or a cost in force that starts at or after the statement —
     * refuses the whole adoption: a reviewer who approved a list expects the
     * list, not the part of it that happened to pass.
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
            Optional<CurrentCost> current = masterData.currentPurchaseCost(context.productVariantId());
            if (sameCost(current, context.sellerCostPrice(), context.currencyCode())) {
                unchanged++;
                continue;
            }
            if (current.isPresent() && !current.get().effectiveFrom().isBefore(context.observedAt())) {
                // The cost in force starts at or after this statement: adopting it
                // would rewrite history rather than succeed it.
                throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
            }
            UUID costVersionId = record(actor.organizationId(), actor.userId(), actor.userId().toString(),
                    context.productVariantId(), context.skuCode(), item.priceObservationId(),
                    context.rawObservationId(), context.sellerCostPrice(), context.currencyCode(),
                    context.observedAt(), current, validReason, now);
            adopted.add(new Adopted(item.listingVariantId(), context.productVariantId(), costVersionId));
        }
        return new Result(adopted, unchanged);
    }

    /**
     * Adopt, under a standing authorization, every mapped listing's changed
     * seller cost that {@link SellerCostCheck} lets through.
     *
     * <p>A cost that must wait for a person is counted and left as it is; so is
     * an internal variant whose listings in this store state different costs.
     *
     * @param authorizedByUserId the person recorded as the adopter
     * @param auditActor who the audit names as having acted (the automation and its policy)
     */
    @Transactional
    public AutomaticAdoption adoptAutomatically(UUID organizationId, UUID storeId, UUID authorizedByUserId,
                                                String auditActor, BigDecimal changeLimit, String reason) {
        Map<UUID, List<CostInput>> byVariant = new LinkedHashMap<>();
        for (CostInput input : masterData.automationCostInputs(organizationId, storeId)) {
            byVariant.computeIfAbsent(input.productVariantId(), variant -> new ArrayList<>()).add(input);
        }
        Instant now = clock.instant();
        int adopted = 0;
        int unchanged = 0;
        int waiting = 0;
        for (List<CostInput> statements : byVariant.values()) {
            CostInput newest = statements.getFirst();
            boolean agree = statements.stream().allMatch(statement ->
                    statement.currencyCode().equals(newest.currencyCode())
                            && statement.sellerCostPrice().compareTo(newest.sellerCostPrice()) == 0);
            if (!agree || !"ACTIVE".equals(newest.variantStatus())) {
                waiting++;
                continue;
            }
            Optional<CurrentCost> current = masterData.currentPurchaseCost(newest.productVariantId());
            if (sameCost(current, newest.sellerCostPrice(), newest.currencyCode())) {
                unchanged++;
                continue;
            }
            if (current.isPresent() && !current.get().effectiveFrom().isBefore(newest.observedAt())) {
                waiting++;
                continue;
            }
            Optional<String> anomaly = SellerCostCheck.anomaly(newest.sellerCostPrice(), newest.currencyCode(),
                    newest.buyerPrice(), current.map(CurrentCost::unitCost).orElse(null),
                    current.map(CurrentCost::currencyCode).orElse(null), changeLimit);
            if (anomaly.isPresent()) {
                waiting++;
                continue;
            }
            record(organizationId, authorizedByUserId, auditActor, newest.productVariantId(), newest.skuCode(),
                    newest.priceObservationId(), newest.rawObservationId(), newest.sellerCostPrice(),
                    newest.currencyCode(), newest.observedAt(), current, reason, now);
            adopted++;
        }
        return new AutomaticAdoption(adopted, unchanged, waiting);
    }

    private static boolean sameCost(Optional<CurrentCost> current, BigDecimal amount, String currencyCode) {
        return current.isPresent() && current.get().currencyCode().equals(currencyCode)
                && current.get().unitCost().compareTo(amount) == 0;
    }

    /** Write one adopted cost version succeeding the one in force, with its evidence and audit. */
    private UUID record(UUID organizationId, UUID recorderUserId, String auditActor, UUID productVariantId,
                        String skuCode, UUID priceObservationId, UUID rawObservationId, BigDecimal amount,
                        String currencyCode, Instant from, Optional<CurrentCost> current, String reason,
                        Instant now) {
        UUID provenanceId = facts.recordProvenance(idGenerator.newId(), organizationId,
                "MARKETPLACE_RAW", rawObservationId, null, recorderUserId, from, now,
                "marketplace seller cost (Ozon net_price) of price observation " + priceObservationId
                        + " adopted as purchase cost: " + reason);
        references.endOpenCostVersion(productVariantId, PURCHASE, from, reason);
        UUID costVersionId = idGenerator.newId();
        references.insertCostVersion(costVersionId, organizationId, productVariantId, PURCHASE, currencyCode,
                amount, provenanceId, from, now);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, auditActor,
                AuditAction.CREATE, ManualFactEntryService.COST_ENTITY_TYPE, costVersionId, skuCode,
                Map.of("unitCost", new FieldChange(
                                current.map(cost -> cost.unitCost().toPlainString()).orElse(null),
                                amount.toPlainString()),
                        "currencyCode", new FieldChange(
                                current.map(CurrentCost::currencyCode).orElse(null), currencyCode),
                        "effectiveFrom", new FieldChange(null, from.toString()),
                        "source", new FieldChange(null, "MARKETPLACE_SELLER_COST:" + priceObservationId)),
                reason, null));
        return costVersionId;
    }

    /** One listing variant and the price observation whose seller cost is adopted. */
    public record Item(UUID listingVariantId, UUID priceObservationId) {
    }

    /** One adopted cost version. */
    public record Adopted(UUID listingVariantId, UUID productVariantId, UUID costVersionId) {
    }

    /**
     * What a person's adoption did.
     *
     * @param unchanged items whose purchase cost in force already equalled the statement
     */
    public record Result(List<Adopted> adopted, int unchanged) {
    }

    /**
     * What an automatic adoption did.
     *
     * @param waiting internal variants whose changed cost waits for a person
     */
    public record AutomaticAdoption(int adopted, int unchanged, int waiting) {
    }
}
