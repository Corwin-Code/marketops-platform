package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The most recent observed availability of one listing variant, by fulfillment
 * mode.
 *
 * <p>Marketplace-held and seller-held stock are kept apart rather than summed. A
 * stockout diagnosis has to distinguish "the marketplace has none" from "we have
 * none", and a single total answers neither question.
 *
 * @param observedAt when the marketplace considered it true, or {@code null}
 * @param availableByMode available units per fulfillment mode
 * @param evidence what the answer was derived from
 */
public record StockSnapshot(
        Instant observedAt,
        Map<String, Integer> availableByMode,
        FactEvidence evidence) {

    public StockSnapshot {
        availableByMode = Map.copyOf(Objects.requireNonNull(availableByMode, "availableByMode"));
    }

    /** An answer nothing contributed to. */
    public static StockSnapshot absent() {
        return new StockSnapshot(null, Map.of(), FactEvidence.none());
    }

    /**
     * Units available across every mode the source reported.
     *
     * <p>The sum is meaningful only because the modes are also kept separately;
     * a caller that needs to know where the stock is asks for the map.
     */
    public int totalAvailable() {
        return availableByMode.values().stream().mapToInt(Integer::intValue).sum();
    }
}
