package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything the company knows about its own holding of one internal variant.
 *
 * <p>Unlike a channel observation this is deliberately wide, because the company
 * question is "across everything we own, is there enough". Anything the input
 * omits becomes an unclassifiable unit, and unclassifiable units are what stop
 * the answer being safe.
 *
 * @param productVariantId the internal variant
 * @param warehouseHoldings what each internal warehouse reports
 * @param platformHoldings what each store and mode reports, with its declaration
 * @param inbound attested consignments, eligible or not
 */
public record CompanyObservation(
        UUID productVariantId,
        List<WarehouseHolding> warehouseHoldings,
        List<PlatformHolding> platformHoldings,
        List<InboundConsignment> inbound) {

    public CompanyObservation {
        Objects.requireNonNull(productVariantId, "productVariantId");
        warehouseHoldings = List.copyOf(Objects.requireNonNull(warehouseHoldings, "warehouseHoldings"));
        platformHoldings = List.copyOf(Objects.requireNonNull(platformHoldings, "platformHoldings"));
        inbound = List.copyOf(Objects.requireNonNull(inbound, "inbound"));
    }

    /**
     * What one internal warehouse holds.
     *
     * @param warehouseId the warehouse
     * @param quantityOnHand units physically present
     * @param quantityReserved units already committed, or {@code null} when unrecorded
     * @param quantityQualityLocked units held in quality control, or {@code null}
     * @param observedAt when it was true, or {@code null} when unknown
     * @param provenanceId the fact this came from, or {@code null}
     */
    public record WarehouseHolding(
            UUID warehouseId,
            int quantityOnHand,
            Integer quantityReserved,
            Integer quantityQualityLocked,
            Integer quantityDamaged,
            Integer quantityWrittenOff,
            Sellability sellability,
            Instant observedAt,
            UUID provenanceId) {

        public WarehouseHolding {
            Objects.requireNonNull(warehouseId, "warehouseId");
            if (quantityOnHand < 0) {
                throw new IllegalArgumentException("quantityOnHand cannot be negative");
            }
            Objects.requireNonNull(sellability, "sellability");
            if (negative(quantityReserved) || negative(quantityQualityLocked)
                    || negative(quantityDamaged) || negative(quantityWrittenOff)) {
                throw new IllegalArgumentException("warehouse stock-state quantities cannot be negative");
            }
        }

        /** Compatibility constructor for fully classified pre-quality fixtures. */
        public WarehouseHolding(UUID warehouseId, int quantityOnHand, Integer quantityReserved,
                                Integer quantityQualityLocked, Instant observedAt,
                                UUID provenanceId) {
            this(warehouseId, quantityOnHand, quantityReserved, quantityQualityLocked,
                    0, 0, Sellability.SELLABLE, observedAt, provenanceId);
        }

        /**
         * Units actually available to sell.
         *
         * <p>Unrecorded reservations are treated as zero rather than unknown,
         * and the caller records the resulting incompleteness separately. The
         * alternative — refusing to compute anything — would make a warehouse
         * that does not publish reservations invisible instead of conservative.
         */
        public int available() {
            int reserved = quantityReserved == null ? 0 : quantityReserved;
            int locked = quantityQualityLocked == null ? 0 : quantityQualityLocked;
            int damaged = quantityDamaged == null ? 0 : quantityDamaged;
            int writtenOff = quantityWrittenOff == null ? 0 : quantityWrittenOff;
            if (sellability != Sellability.SELLABLE) {
                return 0;
            }
            return Math.max(0, quantityOnHand - reserved - locked - damaged - writtenOff);
        }

        /** Whether every material current-stock state was authoritatively supplied. */
        public boolean stateComplete() {
            return quantityReserved != null && quantityQualityLocked != null
                    && quantityDamaged != null && quantityWrittenOff != null
                    && sellability != Sellability.UNKNOWN;
        }

        /** Whether the holding is recent enough to be current supply. */
        public boolean freshAt(Instant asOf, long freshnessMaxMinutes) {
            return observedAt != null
                    && !observedAt.plusSeconds(freshnessMaxMinutes * 60L).isBefore(asOf);
        }

        private static boolean negative(Integer value) {
            return value != null && value < 0;
        }
    }

    /**
     * What one store and fulfillment mode reports, and how it was declared.
     *
     * @param storeId the store
     * @param fulfillmentModeCode the mode
     * @param availableUnits units reported, or {@code null} when none were
     * @param distinctness the ownership declaration in force
     * @param observedAt when it was true, or {@code null} when unknown
     * @param provenanceId the fact this came from, or {@code null}
     */
    public record PlatformHolding(
            UUID storeId,
            String fulfillmentModeCode,
            Integer availableUnits,
            SupplyDistinctness distinctness,
            Instant observedAt,
            UUID provenanceId) {

        public PlatformHolding {
            Objects.requireNonNull(storeId, "storeId");
            Objects.requireNonNull(fulfillmentModeCode, "fulfillmentModeCode");
            Objects.requireNonNull(distinctness, "distinctness");
        }

        /** Whether the holding is recent enough to be current supply. */
        public boolean freshAt(Instant asOf, long freshnessMaxMinutes) {
            return observedAt != null
                    && !observedAt.plusSeconds(freshnessMaxMinutes * 60L).isBefore(asOf);
        }
    }
}
