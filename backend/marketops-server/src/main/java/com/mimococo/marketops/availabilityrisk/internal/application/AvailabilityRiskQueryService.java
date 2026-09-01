package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.AvailabilityCardView;
import com.mimococo.marketops.availabilityrisk.AvailabilityChildView;
import com.mimococo.marketops.availabilityrisk.AvailabilityRankFactorView;
import com.mimococo.marketops.availabilityrisk.AvailabilityRiskQuery;
import com.mimococo.marketops.availabilityrisk.DemandWindowView;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityQueryRepository;
import com.mimococo.marketops.shared.JsonValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


/**
 * Assembles the queue the console renders.
 *
 * <p>A card, its children, their factors and their windows are read in four
 * queries rather than one row-per-factor join, so a variant selling through
 * twenty channels does not multiply its demand windows twenty times before the
 * assembler divides them back out again.
 *
 * <p>An empty permitted-store list returns an empty queue. That is a denial
 * rather than an absence of filtering, and it is the same answer the
 * authorization contract gives.
 */
@Service
public class AvailabilityRiskQueryService implements AvailabilityRiskQuery {

    /** The largest page the console may ask for. */
    private static final int MAX_PAGE = 200;

    private final AvailabilityQueryRepository queries;
    private final ObjectMapper json;

    public AvailabilityRiskQueryService(AvailabilityQueryRepository queries, ObjectMapper json) {
        this.queries = queries;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailabilityCardView> queue(UUID organizationId, List<UUID> permittedStoreIds,
                                            List<UUID> permittedProductVariantIds,
                                            String laneFilter, int limit, int offset) {
        UUID[] stores = permittedStoreIds.toArray(UUID[]::new);
        UUID[] products = permittedProductVariantIds.toArray(UUID[]::new);
        List<AvailabilityQueryRepository.CardRow> cards = queries.queue(organizationId, stores,
                products, laneFilter, Math.clamp(limit, 1, MAX_PAGE), Math.max(0, offset));
        return assemble(organizationId, cards, stores);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AvailabilityCardView> card(UUID organizationId, UUID productVariantId,
                                               List<UUID> permittedStoreIds,
                                               List<UUID> permittedProductVariantIds) {
        UUID[] stores = permittedStoreIds.toArray(UUID[]::new);
        UUID[] products = permittedProductVariantIds.toArray(UUID[]::new);
        return queries.card(organizationId, productVariantId, stores, products)
                .map(row -> assemble(organizationId, List.of(row), stores).get(0));
    }

    /**
     * Join the four reads back into cards.
     *
     * <p>A {@code null} store scope means the caller already authorized the
     * exact card and wants every child of it; the queue path always passes a
     * concrete scope.
     */
    private List<AvailabilityCardView> assemble(UUID organizationId,
                                                List<AvailabilityQueryRepository.CardRow> cards,
                                                UUID[] permittedStoreIds) {
        if (cards.isEmpty()) {
            return List.of();
        }
        UUID[] cardIds = cards.stream().map(AvailabilityQueryRepository.CardRow::id)
                .toArray(UUID[]::new);
        List<AvailabilityQueryRepository.ChildRow> children =
                queries.children(organizationId, cardIds, permittedStoreIds);

        UUID[] calculationIds = children.stream()
                .map(AvailabilityQueryRepository.ChildRow::calculationId)
                .distinct().toArray(UUID[]::new);
        Map<UUID, List<AvailabilityRankFactorView>> factors = new LinkedHashMap<>();
        for (AvailabilityQueryRepository.FactorRow row
                : queries.factors(organizationId, calculationIds)) {
            factors.computeIfAbsent(row.calculationId(), key -> new ArrayList<>()).add(row.factor());
        }
        Map<UUID, List<DemandWindowView>> windows = new LinkedHashMap<>();
        for (AvailabilityQueryRepository.WindowRow row
                : queries.demandWindows(organizationId, calculationIds)) {
            windows.computeIfAbsent(row.calculationId(), key -> new ArrayList<>()).add(row.window());
        }

        Map<UUID, List<AvailabilityChildView>> byCard = new LinkedHashMap<>();
        for (AvailabilityQueryRepository.ChildRow child : children) {
            byCard.computeIfAbsent(child.cardId(), key -> new ArrayList<>())
                    .add(new AvailabilityChildView(child.id(), child.childKind(),
                            child.platformCode(), child.storeId(),
                            child.platformListingVariantId(), child.fulfillmentModeCode(),
                            child.lane(), child.evidenceState(), child.confidenceState(),
                            child.causeCode(), child.availableUnits(), child.dailyDemandRate(),
                            child.daysOfCover(), child.coverageHorizonDays(),
                            child.projectedStockoutAt(), child.profitLane(),
                            child.profitAtRiskAmount(), child.profitAtRiskCurrency(),
                            child.demandSelectionReason(), proofLabels(child.conservativeProof()),
                            child.blockerCodes(),
                            factors.getOrDefault(child.calculationId(), List.of()),
                            windows.getOrDefault(child.calculationId(), List.of()),
                            child.calculatedAt()));
        }

        List<AvailabilityCardView> assembled = new ArrayList<>(cards.size());
        for (AvailabilityQueryRepository.CardRow card : cards) {
            assembled.add(new AvailabilityCardView(card.id(), card.productVariantId(),
                    card.skuCode(), card.displayName(), card.lane(), card.triggeringChildId(),
                    card.rankScore(), card.policyVersionDigest(), card.asOf(),
                    card.calculatedAt(), byCard.getOrDefault(card.id(), List.of())));
        }
        return List.copyOf(assembled);
    }

    /**
     * Read the proof back as readable statements.
     *
     * <p>The stored form is structured so a test can assert it; the console
     * needs sentences. It is parsed rather than reformatted, because a second
     * renderer that rebuilt the argument from the numbers would eventually
     * build a different argument from the one the calculation made.
     */
    List<String> proofLabels(String storedProof) {
        if (storedProof == null || storedProof.isBlank()) {
            return List.of();
        }
        JsonNode root = JsonValues.read(json, storedProof);
        JsonNode terms = root.path("terms");
        if (!terms.isArray()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>(terms.size());
        for (JsonNode term : terms) {
            String label = term.path("label").asString("");
            if (!label.isBlank()) {
                labels.add(label);
            }
        }
        return List.copyOf(labels);
    }
}
