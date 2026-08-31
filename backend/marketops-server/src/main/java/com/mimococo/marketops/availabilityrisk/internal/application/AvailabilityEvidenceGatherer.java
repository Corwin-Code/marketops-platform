package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.internal.domain.CompanyObservation;
import com.mimococo.marketops.availabilityrisk.internal.domain.ChannelObservation;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandWindow;
import com.mimococo.marketops.availabilityrisk.internal.domain.InboundConsignment;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandWindowEvidence;
import com.mimococo.marketops.availabilityrisk.internal.domain.Sellability;
import com.mimococo.marketops.availabilityrisk.internal.domain.SupplyDistinctness;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyRepository;
import com.mimococo.marketops.operatingfacts.AvailabilityObservation;
import com.mimococo.marketops.operatingfacts.DailySaleTotal;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.operatingfacts.SalesTotals;
import com.mimococo.marketops.operatingfacts.SellabilitySnapshot;
import com.mimococo.marketops.operatingfacts.StockSnapshot;
import com.mimococo.marketops.operatingfacts.WarehouseStockSnapshot;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns published facts into the exact inputs the calculators expect.
 *
 * <p>All of the reading happens here so that the calculators stay pure and
 * therefore comparable: a targeted recalculation and an hourly sweep gather the
 * same evidence for the same instant and hand identical values to identical
 * functions, which is what makes their results provably equal rather than
 * merely usually equal.
 *
 * <p>Nothing in this class decides anything. Where a fact is missing it says so
 * with an absent value or an explicit censoring reason; it never substitutes a
 * zero, and it never resolves an ambiguity the calculator is supposed to see.
 */
@Component
public class AvailabilityEvidenceGatherer {

    private static final MathContext RATIO = new MathContext(12, RoundingMode.HALF_UP);
    private static final BigDecimal MINUTES_PER_DAY = BigDecimal.valueOf(1440);

    private final OperatingFactQuery facts;
    private final ListingIdentityDirectory listings;
    private final AvailabilityPolicyRepository policies;

    public AvailabilityEvidenceGatherer(OperatingFactQuery facts,
                                        ListingIdentityDirectory listings,
                                        AvailabilityPolicyRepository policies) {
        this.facts = facts;
        this.listings = listings;
        this.policies = policies;
    }

    /** One exact channel, with the identity the Contract fixes for it. */
    public record ChannelSubject(ChannelObservation observation, String platformCode) {
    }

    /**
     * Every channel the variant is currently sold through.
     *
     * <p>One subject per listing variant and fulfillment mode, because that is
     * the identity a channel risk is about. A listing selling through two modes
     * is two independently governed risks, not an average of them.
     */
    public List<ChannelSubject> channelSubjects(UUID productVariantId, Instant asOf) {
        List<ChannelSubject> subjects = new ArrayList<>();
        for (UUID listingVariantId : listings.listingVariantsFor(productVariantId, asOf)) {
            Optional<ListingVariantContext> context =
                    listings.variantContext(listingVariantId, asOf);
            if (context.isEmpty()) {
                continue;
            }
            ListingVariantContext resolved = context.get();
            StockSnapshot stock = facts.latestStock(listingVariantId, asOf);
            SellabilitySnapshot health = facts.latestSellability(listingVariantId, asOf)
                    .orElseGet(SellabilitySnapshot::absent);
            UUID provenance = stock.evidence().provenanceIds().stream().findFirst().orElse(null);

            if (stock.availableByMode().isEmpty()) {
                // No source named a mode. The channel still exists and still has
                // an unanswered availability question, so it is reported with an
                // absent quantity rather than omitted from the queue.
                subjects.add(new ChannelSubject(new ChannelObservation(listingVariantId,
                        resolved.storeId(), "UNKNOWN", null, stock.observedAt(),
                        sellability(health), health.blockedReason(), provenance),
                        resolved.platformCode()));
                continue;
            }
            for (Map.Entry<String, Integer> mode : stock.availableByMode().entrySet()) {
                subjects.add(new ChannelSubject(new ChannelObservation(listingVariantId,
                        resolved.storeId(), mode.getKey(), mode.getValue(), stock.observedAt(),
                        sellability(health), health.blockedReason(), provenance),
                        resolved.platformCode()));
            }
        }
        return List.copyOf(subjects);
    }

    /**
     * Everything the company knows about its own holding of one variant.
     *
     * <p>A platform holding with no ownership declaration is included as
     * {@code UNDECLARED} rather than dropped. Dropping it would make the
     * company total look complete when it is not, and completeness is exactly
     * what decides whether the answer may be safe.
     */
    public CompanyObservation companyObservation(UUID organizationId, UUID productVariantId,
                                                 List<ChannelSubject> channels,
                                                 List<InboundConsignment> inbound,
                                                 Instant asOf) {
        List<CompanyObservation.WarehouseHolding> warehouses = new ArrayList<>();
        for (WarehouseStockSnapshot snapshot
                : facts.internalStockByWarehouse(productVariantId, asOf)) {
            warehouses.add(new CompanyObservation.WarehouseHolding(snapshot.warehouseId(),
                    snapshot.quantityOnHand(), snapshot.quantityReserved(), null,
                    snapshot.observedAt(), snapshot.provenanceId()));
        }

        Map<String, SupplyDistinctness> declared = new HashMap<>();
        for (AvailabilityPolicyRepository.OwnershipRow row
                : policies.ownershipDeclarations(organizationId, asOf)) {
            declared.put(row.storeId() + "|" + row.fulfillmentModeCode(), row.distinctness());
        }

        List<CompanyObservation.PlatformHolding> platform = new ArrayList<>();
        for (ChannelSubject channel : channels) {
            ChannelObservation observation = channel.observation();
            String key = observation.storeId() + "|" + observation.fulfillmentModeCode();
            platform.add(new CompanyObservation.PlatformHolding(observation.storeId(),
                    observation.fulfillmentModeCode(), observation.availableUnits(),
                    declared.getOrDefault(key, SupplyDistinctness.UNDECLARED),
                    observation.observedAt(), observation.provenanceId()));
        }

        return new CompanyObservation(productVariantId, List.copyOf(warehouses),
                List.copyOf(platform),
                inbound);
    }

    /**
     * The three demand windows for one exact channel.
     *
     * <p>Each window carries how much of it the listing could actually sell in,
     * derived from the merged stock and sellability timeline rather than
     * assumed from the window's length.
     */
    public List<DemandWindowEvidence> channelDemandWindows(UUID listingVariantId, Instant asOf) {
        List<DemandWindowEvidence> evidence = new ArrayList<>();
        for (DemandWindow window : DemandWindow.values()) {
            FactWindow factWindow = FactWindow.endingAt(asOf, Duration.ofDays(window.days()));
            SalesTotals sales = facts.sales(listingVariantId, SaleStage.COMPLETED, null, factWindow);
            List<AvailabilityObservation> timeline =
                    facts.availabilityObservations(listingVariantId, factWindow);
            List<DailySaleTotal> daily = facts.dailyCompletedUnits(listingVariantId, factWindow);
            evidence.add(build(window, factWindow, sales, timeline, daily));
        }
        return List.copyOf(evidence);
    }

    /**
     * The three demand windows for the company, summed across every channel.
     *
     * <p>A window is observable for the company when it was observable on any
     * channel: the company can sell a unit through whichever listing is
     * available, so the best-covered channel is the honest denominator.
     */
    public List<DemandWindowEvidence> companyDemandWindows(List<UUID> listingVariantIds,
                                                           Instant asOf) {
        List<DemandWindowEvidence> evidence = new ArrayList<>();
        for (DemandWindow window : DemandWindow.values()) {
            FactWindow factWindow = FactWindow.endingAt(asOf, Duration.ofDays(window.days()));
            Integer units = null;
            BigDecimal bestObservedDays = BigDecimal.ZERO;
            List<AvailabilityObservation> bestTimeline = List.of();
            Map<java.time.LocalDate, Long> byDay = new HashMap<>();

            for (UUID listingVariantId : listingVariantIds) {
                SalesTotals sales =
                        facts.sales(listingVariantId, SaleStage.COMPLETED, null, factWindow);
                if (sales.evidence().present()) {
                    units = (units == null ? 0 : units) + (int) sales.units();
                }
                List<AvailabilityObservation> timeline =
                        facts.availabilityObservations(listingVariantId, factWindow);
                BigDecimal observed = observedDays(timeline, factWindow);
                if (observed.compareTo(bestObservedDays) > 0 || bestTimeline.isEmpty()) {
                    bestObservedDays = observed.max(bestObservedDays);
                    bestTimeline = timeline;
                }
                for (DailySaleTotal day : facts.dailyCompletedUnits(listingVariantId, factWindow)) {
                    byDay.merge(day.day(), day.completedUnits(), Long::sum);
                }
            }
            // The reason is derived once, from the best coverage any channel
            // achieved. Deriving it inside the loop left a window that nothing
            // could be observed in looking fully observed.
            DemandWindowEvidence.CensoringReason reason = listingVariantIds.isEmpty()
                    ? DemandWindowEvidence.CensoringReason.SOURCE_STALE
                    : censoringReason(bestTimeline, bestObservedDays, factWindow);
            evidence.add(new DemandWindowEvidence(window, factWindow.periodStart(),
                    factWindow.periodEnd(), units, bestObservedDays, reason,
                    largestShare(byDay, units)));
        }
        return List.copyOf(evidence);
    }

    private DemandWindowEvidence build(DemandWindow window, FactWindow factWindow,
                                       SalesTotals sales,
                                       List<AvailabilityObservation> timeline,
                                       List<DailySaleTotal> daily) {
        Integer units = sales.evidence().present() ? (int) sales.units() : null;
        BigDecimal observed = observedDays(timeline, factWindow);
        Map<java.time.LocalDate, Long> byDay = new HashMap<>();
        for (DailySaleTotal day : daily) {
            byDay.merge(day.day(), day.completedUnits(), Long::sum);
        }
        return new DemandWindowEvidence(window, factWindow.periodStart(), factWindow.periodEnd(),
                units, observed, censoringReason(timeline, observed, factWindow),
                largestShare(byDay, units));
    }

    /**
     * How many days of a window the listing could actually sell in.
     *
     * <p>Each observation states a condition that holds until the next one
     * replaces it, so an interval counts when the observation opening it was
     * saleable. The period before the first observation is not counted: nothing
     * had been stated yet, and assuming either state would be inventing
     * evidence.
     */
    static BigDecimal observedDays(List<AvailabilityObservation> timeline, FactWindow window) {
        if (timeline.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long observableMinutes = 0;
        for (int index = 0; index < timeline.size(); index++) {
            AvailabilityObservation current = timeline.get(index);
            if (current.observedAt() == null || !current.saleable()) {
                continue;
            }
            Instant until = index + 1 < timeline.size()
                    ? timeline.get(index + 1).observedAt()
                    : window.periodEnd();
            if (until == null || !until.isAfter(current.observedAt())) {
                continue;
            }
            Instant capped = until.isAfter(window.periodEnd()) ? window.periodEnd() : until;
            observableMinutes += Duration.between(current.observedAt(), capped).toMinutes();
        }
        return BigDecimal.valueOf(observableMinutes).divide(MINUTES_PER_DAY, RATIO);
    }

    /** Why observation was incomplete, or {@code null} when it was not. */
    static DemandWindowEvidence.CensoringReason censoringReason(
            List<AvailabilityObservation> timeline, BigDecimal observed, FactWindow window) {
        BigDecimal length = BigDecimal.valueOf(
                Duration.between(window.periodStart(), window.periodEnd()).toMinutes())
                .divide(MINUTES_PER_DAY, RATIO);
        if (observed.compareTo(length) >= 0) {
            return null;
        }
        if (timeline.isEmpty()) {
            return DemandWindowEvidence.CensoringReason.SOURCE_STALE;
        }
        boolean blocked = timeline.stream()
                .anyMatch(observation -> "NO".equals(observation.sellable()));
        if (blocked) {
            return DemandWindowEvidence.CensoringReason.NOT_SELLABLE;
        }
        boolean empty = timeline.stream().anyMatch(observation ->
                observation.availableUnits() != null && observation.availableUnits() == 0);
        if (empty) {
            return DemandWindowEvidence.CensoringReason.NO_STOCK;
        }
        return DemandWindowEvidence.CensoringReason.PARTIAL_COVERAGE;
    }

    /** The share of a window's units the busiest day contributed. */
    static BigDecimal largestShare(Map<java.time.LocalDate, Long> byDay, Integer units) {
        if (units == null || units <= 0 || byDay.isEmpty()) {
            return null;
        }
        long busiest = byDay.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return BigDecimal.valueOf(busiest).divide(BigDecimal.valueOf(units), RATIO);
    }

    private static Sellability sellability(SellabilitySnapshot health) {
        if (!health.present()) {
            return Sellability.UNKNOWN;
        }
        return switch (health.sellable()) {
            case "YES" -> Sellability.SELLABLE;
            case "NO" -> Sellability.NOT_SELLABLE;
            default -> Sellability.UNKNOWN;
        };
    }
}
