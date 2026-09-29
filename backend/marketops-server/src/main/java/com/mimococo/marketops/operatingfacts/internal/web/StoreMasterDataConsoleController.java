package com.mimococo.marketops.operatingfacts.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operatingfacts.internal.application.MarketplaceCostAdoptionService;
import com.mimococo.marketops.operatingfacts.internal.application.MasterDataAutomationService;
import com.mimococo.marketops.operatingfacts.internal.application.SellerCostCheck;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.MasterDataPolicyRepository.Policy;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreMasterDataRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreMasterDataRepository.Candidate;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreMasterDataRepository.Conflict;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreMasterDataRepository.Row;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A store's listings against the internal catalogue: which internal SKU each
 * listing is mapped to, what is still proposed or in conflict, and whether the
 * seller's marketplace cost has been adopted as the internal purchase cost.
 *
 * <p>Mapping decisions themselves go through the mapping module's own routes
 * (proposals, confirmation, rejection), one decision per candidate; this view
 * gives them names instead of identifiers. Costs are shown only to someone who
 * may enter internal facts for the store.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/stores")
class StoreMasterDataConsoleController {

    private final StoreMasterDataRepository masterData;
    private final MarketplaceCostAdoptionService adoption;
    private final MasterDataAutomationService automation;
    private final BusinessAuthorization authorization;
    private final Clock clock;

    StoreMasterDataConsoleController(StoreMasterDataRepository masterData,
                                     MarketplaceCostAdoptionService adoption,
                                     MasterDataAutomationService automation,
                                     BusinessAuthorization authorization,
                                     Clock clock) {
        this.masterData = masterData;
        this.adoption = adoption;
        this.automation = automation;
        this.authorization = authorization;
        this.clock = clock;
    }

    /** Every observed listing of the store with its mapping state and costs. */
    @GetMapping(value = "/{storeId}/master-data", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    MasterData review(AuthenticatedActor actor, @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.MAPPING_RESOLVE, ResourceScope.store(storeId));
        boolean costsVisible = authorization.permittedStoreIds(actor, ActionScopeCode.INTERNAL_FACT_INTAKE)
                .contains(storeId);
        Map<UUID, List<ProposedMapping>> candidates = masterData
                .openCandidates(actor.organizationId(), storeId).stream()
                .collect(Collectors.groupingBy(Candidate::listingVariantId,
                        Collectors.mapping(StoreMasterDataConsoleController::proposal, Collectors.toList())));
        Map<UUID, List<OpenConflict>> conflicts = masterData
                .openConflicts(actor.organizationId(), storeId).stream()
                .collect(Collectors.groupingBy(Conflict::listingVariantId,
                        Collectors.mapping(conflict -> new OpenConflict(conflict.id(), conflict.version(),
                                conflict.kind(), conflict.detail(), conflict.detectedAt()),
                                Collectors.toList())));
        Policy policy = automation.active(actor.organizationId(), storeId).orElse(null);
        BigDecimal limit = policy == null ? SellerCostCheck.DEFAULT_CHANGE_LIMIT : policy.costChangeLimit();
        List<ListingRow> rows = masterData.rows(actor.organizationId(), storeId).stream()
                .map(row -> listing(row, candidates.getOrDefault(row.listingVariantId(), List.of()),
                        conflicts.getOrDefault(row.listingVariantId(), List.of()), costsVisible, limit))
                .toList();
        return new MasterData(storeId, clock.instant(), costsVisible, summary(rows),
                policy == null ? null : policyView(policy), rows);
    }

    /**
     * Put the store's master-data policy in force (replacing the one in force)
     * and run it once. It acts on mappings and costs alike, so it needs both
     * authorities.
     */
    @PostMapping(value = "/{storeId}/master-data-automation", produces = MediaType.APPLICATION_JSON_VALUE)
    EnabledView enableAutomation(AuthenticatedActor actor, @PathVariable UUID storeId,
                                 @Valid @RequestBody EnableRequest request) {
        authorization.require(actor, ActionScopeCode.MAPPING_RESOLVE, ResourceScope.store(storeId));
        authorization.require(actor, ActionScopeCode.INTERNAL_FACT_INTAKE, ResourceScope.store(storeId));
        BigDecimal limit;
        try {
            limit = request.costChangeLimit() == null ? null : new BigDecimal(request.costChangeLimit());
        } catch (NumberFormatException notADecimal) {
            throw com.mimococo.marketops.shared.OperationRejectedException.of(
                    com.mimococo.marketops.shared.ErrorCode.VALIDATION_FAILED);
        }
        MasterDataAutomationService.Enabled enabled = automation.enable(actor, storeId, limit, request.reason());
        return new EnabledView(policyView(enabled.policy()), enabled.run());
    }

    /** Take the store's master-data policy out of force; what it decided stays in place. */
    @PostMapping(value = "/{storeId}/master-data-automation/retirement")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retireAutomation(AuthenticatedActor actor, @PathVariable UUID storeId,
                          @Valid @RequestBody RetireRequest request) {
        authorization.require(actor, ActionScopeCode.MAPPING_RESOLVE, ResourceScope.store(storeId));
        authorization.require(actor, ActionScopeCode.INTERNAL_FACT_INTAKE, ResourceScope.store(storeId));
        automation.retire(actor, storeId, request.reason(), request.expectedVersion());
    }

    private static AutomationPolicy policyView(Policy policy) {
        return new AutomationPolicy(policy.id(), policy.version(), policy.autoConfirmMapping(),
                policy.autoAdoptSellerCost(), policy.costChangeLimit().toPlainString(),
                policy.authorizedByUserId(), policy.authorizedAt(), policy.reason());
    }

    /** Adopt the seller's marketplace cost of the given listings as internal purchase cost. */
    @PostMapping(value = "/{storeId}/marketplace-costs/adoption",
            produces = MediaType.APPLICATION_JSON_VALUE)
    MarketplaceCostAdoptionService.Result adopt(AuthenticatedActor actor, @PathVariable UUID storeId,
                                                @Valid @RequestBody AdoptionRequest request) {
        authorization.require(actor, ActionScopeCode.INTERNAL_FACT_INTAKE, ResourceScope.store(storeId));
        return adoption.adopt(actor, storeId, request.items().stream()
                        .map(item -> new MarketplaceCostAdoptionService.Item(item.listingVariantId(),
                                item.priceObservationId()))
                        .toList(),
                request.reason());
    }

    private static ProposedMapping proposal(Candidate candidate) {
        return new ProposedMapping(candidate.id(), candidate.version(), candidate.productVariantId(),
                candidate.skuCode(), candidate.variantName(), candidate.productName(),
                candidate.matchMethod(), text(candidate.confidence()));
    }

    private static ListingRow listing(Row row, List<ProposedMapping> proposals, List<OpenConflict> conflicts,
                                      boolean costsVisible, BigDecimal limit) {
        Mapping mapping = row.mappedVariantId() == null ? null
                : new Mapping(row.mappedVariantId(), row.mappedSkuCode(), row.mappedVariantName(),
                        row.mappedProductName(), row.mappedVariantStatus(), row.mappedFrom());
        SellerCost sellerCost = !costsVisible || row.sellerCostPrice() == null ? null
                : new SellerCost(row.priceObservationId(), text(row.sellerCostPrice()),
                        row.priceCurrencyCode(), row.priceObservedAt());
        PurchaseCost cost = !costsVisible || row.unitCost() == null ? null
                : new PurchaseCost(text(row.unitCost()), row.costCurrencyCode(), row.costEffectiveFrom(),
                        row.costSourceKind());
        String costState = costState(mapping, sellerCost, cost);
        String costAnomaly = !"TO_ADOPT".equals(costState) ? null
                : SellerCostCheck.anomaly(row.sellerCostPrice(), row.priceCurrencyCode(), row.buyerPrice(),
                        row.unitCost(), row.costCurrencyCode(), limit).orElse(null);
        return new ListingRow(row.listingId(), row.listingVariantId(), row.nativeListingKey(),
                row.nativeSkuKey(), row.nativeItemKey(), row.nativeBarcode(), row.title(),
                state(mapping, proposals, conflicts), mapping, proposals, conflicts, sellerCost, cost,
                costState, costAnomaly);
    }

    /**
     * Where the listing stands in the mapping work.
     *
     * <p>A mapping with an open conflict is a conflict: analytics treats the
     * listing as unmapped until it is resolved. An open proposal is something a
     * person can act on, so it comes before a conflict without one: a conflict
     * left open by an earlier matcher run (no internal variant existed yet) is
     * closed by confirming the proposal.
     */
    private static String state(Mapping mapping, List<ProposedMapping> proposals, List<OpenConflict> conflicts) {
        if (mapping != null) return conflicts.isEmpty() ? "MAPPED" : "CONFLICT";
        if (!proposals.isEmpty()) return "PROPOSED";
        if (!conflicts.isEmpty()) return "CONFLICT";
        return "UNMATCHED";
    }

    /**
     * Whether the seller's marketplace cost is the purchase cost in force:
     * ADOPTED when it is, TO_ADOPT when a mapped listing's cost differs or is
     * missing, and null when there is nothing to adopt or nothing to adopt it to.
     */
    private static String costState(Mapping mapping, SellerCost sellerCost, PurchaseCost cost) {
        if (mapping == null || sellerCost == null) return null;
        if (cost != null && Objects.equals(cost.currencyCode(), sellerCost.currencyCode())
                && new BigDecimal(cost.unitCost()).compareTo(new BigDecimal(sellerCost.amount())) == 0) {
            return "ADOPTED";
        }
        return "TO_ADOPT";
    }

    private static Summary summary(List<ListingRow> rows) {
        int mapped = 0;
        int proposed = 0;
        int conflicts = 0;
        int unmatched = 0;
        int withSellerCost = 0;
        int costAdopted = 0;
        int costToAdopt = 0;
        for (ListingRow row : rows) {
            switch (row.state()) {
                case "MAPPED" -> mapped++;
                case "PROPOSED" -> proposed++;
                case "CONFLICT" -> conflicts++;
                default -> unmatched++;
            }
            if (row.sellerCost() != null) withSellerCost++;
            if ("ADOPTED".equals(row.costState())) costAdopted++;
            if ("TO_ADOPT".equals(row.costState())) costToAdopt++;
        }
        return new Summary(rows.size(), mapped, proposed, conflicts, unmatched, withSellerCost, costAdopted,
                costToAdopt);
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * The whole review.
     *
     * @param costsVisible whether the viewer may see and adopt costs for this store
     */
    record MasterData(UUID storeId, Instant generatedAt, boolean costsVisible, Summary summary,
                      AutomationPolicy automation, List<ListingRow> rows) {
    }

    /**
     * The store's master-data policy in force.
     *
     * @param costChangeLimit decimal text; 0.30 = a change of up to ±30 % is adopted without a person
     */
    record AutomationPolicy(UUID policyId, long version, boolean autoConfirmMapping, boolean autoAdoptSellerCost,
                            String costChangeLimit, UUID authorizedByUserId, Instant authorizedAt,
                            String reason) {
    }

    /** A policy put in force and what its first run did. */
    record EnabledView(AutomationPolicy policy, MasterDataAutomationService.RunResult run) {
    }

    /** Why the policy is put in force, and its cost change limit (decimal text; default 0.30). */
    record EnableRequest(String costChangeLimit, @NotBlank String reason) {
    }

    /** Why the policy is taken out of force, against the version read. */
    record RetireRequest(@NotBlank String reason, @NotNull Long expectedVersion) {
    }

    /** Counts across the store's listings. */
    record Summary(int listings, int mapped, int proposed, int conflicts, int unmatched, int withSellerCost,
                   int costAdopted, int costToAdopt) {
    }

    /**
     * One listing variant.
     *
     * @param state {@code MAPPED}, {@code PROPOSED}, {@code CONFLICT} or {@code UNMATCHED}
     * @param costState {@code ADOPTED}, {@code TO_ADOPT} or {@code null}
     * @param costAnomaly why a cost to adopt waits for a person under the policy's rules, or {@code null}
     */
    record ListingRow(UUID listingId, UUID listingVariantId, String nativeListingKey, String nativeSkuKey,
                      String nativeItemKey, String nativeBarcode, String title, String state, Mapping mapping,
                      List<ProposedMapping> proposals, List<OpenConflict> conflicts, SellerCost sellerCost,
                      PurchaseCost purchaseCost, String costState, String costAnomaly) {
    }

    /** The mapping in force. */
    record Mapping(UUID productVariantId, String skuCode, String variantName, String productName,
                   String variantStatus, Instant effectiveFrom) {
    }

    /** An open proposal a person can confirm or reject; {@code version} guards the decision. */
    record ProposedMapping(UUID candidateId, long version, UUID productVariantId, String skuCode,
                           String variantName, String productName, String matchMethod, String confidence) {
    }

    /** An open conflict. */
    record OpenConflict(UUID conflictId, long version, String kind, String detail, Instant detectedAt) {
    }

    /** The unit cost the seller entered at the marketplace, from the newest price observation. */
    record SellerCost(UUID priceObservationId, String amount, String currencyCode, Instant observedAt) {
    }

    /**
     * The internal purchase cost in force.
     *
     * @param sourceKind {@code MARKETPLACE_RAW}, {@code MANUAL_ENTRY} or {@code INTERNAL_IMPORT}
     */
    record PurchaseCost(String unitCost, String currencyCode, Instant effectiveFrom, String sourceKind) {
    }

    /** Which listings' seller cost to adopt, and why. */
    record AdoptionRequest(@NotEmpty List<@Valid AdoptionItem> items, @NotBlank String reason) {
    }

    /** One listing variant and the price observation it was reviewed with. */
    record AdoptionItem(UUID listingVariantId, UUID priceObservationId) {
    }
}
