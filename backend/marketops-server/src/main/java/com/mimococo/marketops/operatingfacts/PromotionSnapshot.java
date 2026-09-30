package com.mimococo.marketops.operatingfacts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One marketplace promotion as the store's newest promotion snapshot described it, with the
 * products the newest answers named as able to join it and as taking part. Titles and
 * descriptions are marketplace text; every other part is {@code null} when the marketplace did
 * not state it.
 *
 * @param promotionId the promotion's identity
 * @param nativePromotionKey the marketplace's own identifier (Ozon: the action id)
 * @param freezesAt from when prices can only go down and products can no longer leave
 * @param items the candidates and participants, newest answer of each membership
 * @param evidence what the answer was derived from
 */
public record PromotionSnapshot(
        java.util.UUID promotionId,
        String nativePromotionKey,
        Instant observedAt,
        String title,
        String promotionKind,
        String description,
        Instant startsAt,
        Instant endsAt,
        Instant freezesAt,
        Integer candidateCount,
        Integer participantCount,
        Integer bannedCount,
        Boolean participating,
        Boolean voucher,
        Boolean targeted,
        String discountKind,
        BigDecimal discountValue,
        List<Item> items,
        FactEvidence evidence) {

    public PromotionSnapshot {
        Objects.requireNonNull(promotionId, "promotionId");
        Objects.requireNonNull(nativePromotionKey, "nativePromotionKey");
        items = List.copyOf(items);
        Objects.requireNonNull(evidence, "evidence");
    }

    /**
     * One product of the promotion; amounts in {@code currencyCode}.
     *
     * @param membership CANDIDATE (can join) or PARTICIPANT (takes part)
     * @param maxActionPrice the highest price the product may have in the promotion
     * @param recommendedActionPrice the price the marketplace recommends in it
     * @param aboveRecommended whether the price is above the recommended one (it may be removed)
     * @param priceForMaxBoost the price that earns the largest boost
     * @param addMode AUTOMATIC or SELLER for a participant, otherwise {@code null}
     * @param provenanceId the answer the product was read from
     */
    public record Item(
            java.util.UUID listingVariantId,
            String membership,
            Instant observedAt,
            String currencyCode,
            BigDecimal price,
            BigDecimal actionPrice,
            BigDecimal maxActionPrice,
            BigDecimal recommendedActionPrice,
            Boolean aboveRecommended,
            BigDecimal currentBoost,
            BigDecimal minBoost,
            BigDecimal maxBoost,
            BigDecimal priceForMinBoost,
            BigDecimal priceForMaxBoost,
            Integer minStock,
            Integer recommendedStock,
            Integer stock,
            String addMode,
            Boolean quarantined,
            java.util.UUID provenanceId) {

        public Item {
            Objects.requireNonNull(listingVariantId, "listingVariantId");
            Objects.requireNonNull(membership, "membership");
        }
    }
}
