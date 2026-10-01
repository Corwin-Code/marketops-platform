package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.internal.domain.CompanyObservation;
import com.mimococo.marketops.availabilityrisk.internal.domain.ChannelObservation;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandWindow;
import com.mimococo.marketops.availabilityrisk.internal.domain.InboundConsignment;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandWindowEvidence;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandSource;
import com.mimococo.marketops.availabilityrisk.internal.domain.Sellability;
import com.mimococo.marketops.availabilityrisk.internal.domain.SupplyDistinctness;
import com.mimococo.marketops.availabilityrisk.internal.domain.ReturnQualityAssessment;
import com.mimococo.marketops.availabilityrisk.internal.domain.ReturnQualityPolicyVersion;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyRepository;
import com.mimococo.marketops.operatingfacts.AvailabilityObservation;
import com.mimococo.marketops.operatingfacts.DailySaleTotal;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.ListingWindowRecord;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.operatingfacts.SalesTotals;
import com.mimococo.marketops.operatingfacts.SellabilitySnapshot;
import com.mimococo.marketops.operatingfacts.StockSnapshot;
import com.mimococo.marketops.operatingfacts.WarehouseStockSnapshot;
import com.mimococo.marketops.operatingfacts.ReturnTotals;
import com.mimococo.marketops.operatingfacts.ReturnQualityEvidence;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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

    /**
     * A gatherer for exactly one calculation, which asks each question once.
     *
     * <p>The channel view and the company view of one variant read the same
     * facts, and for a variant sold through one listing in one mode several of
     * those reads are byte-identical. They cannot disagree — one transaction,
     * one snapshot, one {@code asOf} — so asking twice buys nothing but a round
     * trip. This gatherer remembers within the calculation and is discarded with
     * it; nothing is remembered across variants or across instants.
     */
    public AvailabilityEvidenceGatherer forOneCalculation() {
        return new AvailabilityEvidenceGatherer(new OneCalculationFactMemo(facts), listings,
                policies);
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
                    snapshot.quantityOnHand(), snapshot.quantityReserved(),
                    snapshot.quantityQualityLocked(), snapshot.quantityDamaged(),
                    snapshot.quantityWrittenOff(), warehouseSellability(snapshot.sellable()),
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
    public List<DemandWindowEvidence> channelDemandWindows(UUID listingVariantId,
                                                           String fulfillmentModeCode,
                                                           boolean modeAttributable,
                                                           OrderCoverage orders,
                                                           Instant asOf) {
        if (orders != null) {
            return orderedChannelWindows(listingVariantId, fulfillmentModeCode, modeAttributable,
                    orders);
        }
        List<DemandWindowEvidence> evidence = new ArrayList<>();
        for (DemandWindow window : DemandWindow.values()) {
            FactWindow factWindow = FactWindow.endingAt(asOf, Duration.ofDays(window.days()));
            if (!modeAttributable) {
                evidence.add(new DemandWindowEvidence(window, factWindow.periodStart(),
                        factWindow.periodEnd(), null, BigDecimal.ZERO,
                        DemandWindowEvidence.CensoringReason.SOURCE_STALE, null,
                        DemandSource.COMPLETED_SALES, null));
                continue;
            }
            SalesTotals sales = facts.sales(listingVariantId, SaleStage.COMPLETED, null, factWindow);
            List<AvailabilityObservation> timeline =
                    facts.availabilityObservations(listingVariantId, fulfillmentModeCode,
                            factWindow);
            List<DailySaleTotal> daily = facts.dailyCompletedUnits(listingVariantId, factWindow);
            evidence.add(build(window, factWindow, sales, timeline, daily));
        }
        return List.copyOf(evidence);
    }

    /**
     * The days the store's order facts cover and the instant ordered-unit windows end at (P9, Owner
     * decision 2026-10-02).
     *
     * <p>Daily order facts arrive a couple of days late, so a window ending now would always miss its
     * newest days and read the lag as missing demand. The windows end instead with the newest day the
     * order facts cover — but never longer ago than the policy lets old evidence stand in for current
     * ({@code maximumAge}, the carry-forward bound): when the facts stop arriving, the days after the
     * newest one count against the window until it is censored. A day is covered when any listing of
     * the store has a record for it; across several stores only the days all of them cover count.
     *
     * @param end the exclusive end of every window, a UTC midnight
     * @param days the covered UTC days
     */
    public record OrderCoverage(Instant end, Set<LocalDate> days) {

        public OrderCoverage {
            days = Set.copyOf(days);
        }

        /** The window of the given length ending at {@link #end}. */
        public FactWindow window(DemandWindow window) {
            return FactWindow.endingAt(end, Duration.ofDays(window.days()));
        }

        /** The covered days inside a window. */
        public Set<LocalDate> daysIn(FactWindow window) {
            Set<LocalDate> inside = new TreeSet<>();
            for (LocalDate day : days) {
                Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
                if (!start.isBefore(window.periodStart()) && start.isBefore(window.periodEnd())) {
                    inside.add(day);
                }
            }
            return inside;
        }
    }

    /** Where the order facts of the given stores leave the windows, as of an instant. */
    public OrderCoverage orderCoverage(Collection<UUID> storeIds, Instant asOf, Duration maximumAge) {
        LocalDate today = LocalDate.ofInstant(asOf, ZoneOffset.UTC);
        LocalDate oldestEnd = today.minusDays(Math.max(0, maximumAge.toDays()));
        FactWindow lookback = new FactWindow(oldestEnd.minusDays(DemandWindow.D30.days())
                .atStartOfDay(ZoneOffset.UTC).toInstant(), asOf);
        LocalDate end = null;
        Set<LocalDate> covered = null;
        for (UUID storeId : new TreeSet<>(storeIds)) {
            List<LocalDate> days = facts.storeOrderDays(storeId, lookback);
            LocalDate storeEnd = days.isEmpty() ? today
                    : max(days.get(days.size() - 1).plusDays(1), oldestEnd);
            end = end == null || storeEnd.isBefore(end) ? storeEnd : end;
            if (covered == null) {
                covered = new TreeSet<>(days);
            } else {
                covered.retainAll(days);
            }
        }
        return new OrderCoverage((end == null ? today : end).atStartOfDay(ZoneOffset.UTC).toInstant(),
                covered == null ? Set.of() : covered);
    }

    private static LocalDate max(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    /**
     * The three ordered-unit windows of one exact channel.
     *
     * <p>Units are summed over the covered days, a covered day without a record for the listing
     * counting as zero. The listing counts as observed only for the time it could sell on a covered
     * day: an order cannot be read for an uncovered day, and an absent order on a day it could not
     * sell says nothing about demand.
     */
    private List<DemandWindowEvidence> orderedChannelWindows(UUID listingVariantId,
                                                             String fulfillmentModeCode,
                                                             boolean modeAttributable,
                                                             OrderCoverage orders) {
        List<DemandWindowEvidence> evidence = new ArrayList<>();
        for (DemandWindow window : DemandWindow.values()) {
            FactWindow factWindow = orders.window(window);
            if (!modeAttributable) {
                evidence.add(new DemandWindowEvidence(window, factWindow.periodStart(),
                        factWindow.periodEnd(), null, BigDecimal.ZERO,
                        DemandWindowEvidence.CensoringReason.SOURCE_STALE, null,
                        DemandSource.ORDERED_UNITS, null));
                continue;
            }
            Set<LocalDate> covered = orders.daysIn(factWindow);
            Map<LocalDate, Long> byDay = orderedByDay(listingVariantId, factWindow, covered);
            Integer units = covered.isEmpty() ? null
                    : (int) byDay.values().stream().mapToLong(Long::longValue).sum();
            List<AvailabilityObservation> timeline = facts.availabilityObservations(
                    listingVariantId, fulfillmentModeCode, factWindow);
            BigDecimal observed = daysWithin(mergedSaleable(List.of(timeline), factWindow), covered);
            evidence.add(new DemandWindowEvidence(window, factWindow.periodStart(),
                    factWindow.periodEnd(), units, observed,
                    censoringReason(timeline, observed, factWindow), largestShare(byDay, units),
                    DemandSource.ORDERED_UNITS, observationBegan(timeline, factWindow)));
        }
        return List.copyOf(evidence);
    }

    /** The company's ordered-unit windows: every channel's orders, observable on any channel. */
    private List<DemandWindowEvidence> orderedCompanyWindows(List<ChannelSubject> channels,
                                                             OrderCoverage orders) {
        List<UUID> listingVariantIds = channels.stream()
                .map(channel -> channel.observation().platformListingVariantId())
                .distinct().toList();
        List<DemandWindowEvidence> evidence = new ArrayList<>();
        for (DemandWindow window : DemandWindow.values()) {
            FactWindow factWindow = orders.window(window);
            Set<LocalDate> covered = orders.daysIn(factWindow);
            Map<LocalDate, Long> byDay = new HashMap<>();
            for (UUID listingVariantId : listingVariantIds) {
                orderedByDay(listingVariantId, factWindow, covered)
                        .forEach((day, units) -> byDay.merge(day, units, Long::sum));
            }
            Integer units = covered.isEmpty() || listingVariantIds.isEmpty() ? null
                    : (int) byDay.values().stream().mapToLong(Long::longValue).sum();
            List<List<AvailabilityObservation>> timelines = new ArrayList<>();
            for (ChannelSubject channel : channels) {
                ChannelObservation observation = channel.observation();
                if (!"UNKNOWN".equals(observation.fulfillmentModeCode())) {
                    timelines.add(facts.availabilityObservations(
                            observation.platformListingVariantId(),
                            observation.fulfillmentModeCode(), factWindow));
                }
            }
            BigDecimal observed = daysWithin(mergedSaleable(timelines, factWindow), covered);
            List<AvailabilityObservation> allObservations = timelines.stream()
                    .flatMap(List::stream).toList();
            DemandWindowEvidence.CensoringReason reason = listingVariantIds.isEmpty()
                    ? DemandWindowEvidence.CensoringReason.SOURCE_STALE
                    : censoringReason(allObservations, observed, factWindow);
            evidence.add(new DemandWindowEvidence(window, factWindow.periodStart(),
                    factWindow.periodEnd(), units, observed, reason, largestShare(byDay, units),
                    DemandSource.ORDERED_UNITS, companyObservationBegan(timelines, factWindow)));
        }
        return List.copyOf(evidence);
    }

    /** One listing's ordered units per covered day; a covered day without a record is zero. */
    private Map<LocalDate, Long> orderedByDay(UUID listingVariantId, FactWindow window,
                                              Set<LocalDate> covered) {
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (LocalDate day : covered) {
            byDay.put(day, 0L);
        }
        for (ListingWindowRecord.DayOrders day : facts.dailyOrderedUnits(listingVariantId, window)) {
            if (covered.contains(day.day())) {
                byDay.merge(day.day(), day.orderedUnits(), Long::sum);
            }
        }
        return byDay;
    }

    /** How much of the saleable time falls on the covered days, in days. */
    static BigDecimal daysWithin(List<TimeInterval> saleable, Set<LocalDate> covered) {
        long minutes = 0;
        for (LocalDate day : covered) {
            Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = dayStart.plus(Duration.ofDays(1));
            for (TimeInterval interval : saleable) {
                Instant start = interval.start().isAfter(dayStart) ? interval.start() : dayStart;
                Instant end = interval.end().isBefore(dayEnd) ? interval.end() : dayEnd;
                if (end.isAfter(start)) {
                    minutes += Duration.between(start, end).toMinutes();
                }
            }
        }
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_DAY, RATIO);
    }

    /** Retained/return/QC guardrail for one exact listing. */
    public ReturnQualityAssessment returnQuality(UUID listingVariantId, UUID storeId,
                                                 ReturnQualityPolicyVersion policy,
                                                 Instant asOf) {
        if (policy == null) {
            return ReturnQualityAssessment.blocked("RETURN_QUALITY_POLICY_UNRESOLVED", true);
        }
        FactWindow window = FactWindow.endingAt(asOf, Duration.ofDays(30));
        if (nothingSold(listingVariantId, storeId, window)) {
            return ReturnQualityAssessment.clear();
        }
        ReturnQualityEvidence authority = facts.returnQualityEvidence(listingVariantId, window,
                policy.evidenceFreshnessMaximum(), asOf);
        switch (authority.state()) {
            case NO_EVIDENCE -> {
                return ReturnQualityAssessment.blocked("RETURN_QUALITY_NO_EVIDENCE", false);
            }
            case INCOMPLETE -> {
                return ReturnQualityAssessment.blocked("RETURN_QUALITY_INCOMPLETE", false);
            }
            case STALE -> {
                return ReturnQualityAssessment.blocked("RETURN_QUALITY_STALE", false);
            }
            case CONFLICTED -> {
                return ReturnQualityAssessment.review("RETURN_QUALITY_CONFLICTED");
            }
            default -> {
                // Only fresh, complete coverage may reach ratio calculation.
            }
        }
        SalesTotals completed = facts.sales(listingVariantId, SaleStage.COMPLETED, null, window);
        SalesTotals retained = facts.sales(listingVariantId, SaleStage.RETAINED, 30, window);
        ReturnTotals returns = facts.returns(listingVariantId, window);
        boolean authoritativeZero = authority.state()
                == ReturnQualityEvidence.State.FRESH_COMPLETE_ZERO_RETURNS;
        if (!completed.available() || !retained.available()
                || (!authoritativeZero && !returns.available())) {
            return ReturnQualityAssessment.blocked("RETURN_QUALITY_EVIDENCE_UNRESOLVED", false);
        }
        if (completed.units() <= 0) {
            return ReturnQualityAssessment.clear();
        }
        BigDecimal completedUnits = BigDecimal.valueOf(completed.units());
        long returnedUnits = authoritativeZero ? 0L : returns.units();
        Map<String, Long> reasons = authoritativeZero ? Map.of() : returns.unitsByReason();
        BigDecimal returnRatio = BigDecimal.valueOf(returnedUnits)
                .divide(completedUnits, RATIO);
        BigDecimal retentionRatio = BigDecimal.valueOf(retained.units())
                .divide(completedUnits, RATIO);
        long defects = reasons.getOrDefault("QUALITY", 0L)
                + reasons.getOrDefault("DAMAGED_IN_TRANSIT", 0L)
                + reasons.getOrDefault("NOT_AS_DESCRIBED", 0L);
        BigDecimal defectRatio = BigDecimal.valueOf(defects).divide(completedUnits, RATIO);
        if (defectRatio.compareTo(policy.maximumDefectReturnRatio()) > 0) {
            return ReturnQualityAssessment.review("SUPPLIER_OR_PRODUCT_DEFECT_RATE_HIGH");
        }
        if (returnRatio.compareTo(policy.maximumReturnRatio()) > 0
                || retentionRatio.compareTo(policy.minimumRetentionRatio()) < 0) {
            return ReturnQualityAssessment.review("RETURN_OR_RETENTION_GUARDRAIL_BREACHED");
        }
        return ReturnQualityAssessment.clear();
    }

    /**
     * Whether the listing sold nothing in the window at all (P9, Owner decision 2026-10-02): no
     * completed sale in the ledger and no unit ordered on any day the store's order facts cover.
     * Nothing sold means nothing can come back, so there is no return or quality question to hold the
     * risk on. It takes at least one covered day: with no order facts at all, nothing is known.
     */
    private boolean nothingSold(UUID listingVariantId, UUID storeId, FactWindow window) {
        SalesTotals completed = facts.sales(listingVariantId, SaleStage.COMPLETED, null, window);
        if (completed.evidence().present() && (!completed.available() || completed.units() > 0)) {
            return false;
        }
        if (storeId == null || facts.storeOrderDays(storeId, window).isEmpty()) {
            return false;
        }
        return facts.dailyOrderedUnits(listingVariantId, window).stream()
                .mapToLong(ListingWindowRecord.DayOrders::orderedUnits).sum() == 0;
    }

    /**
     * The three demand windows for the company, summed across every channel.
     *
     * <p>A window is observable for the company when it was observable on any
     * channel: the company can sell a unit through whichever listing is
     * available, so the best-covered channel is the honest denominator.
     */
    public List<DemandWindowEvidence> companyDemandWindows(List<ChannelSubject> channels,
                                                           OrderCoverage orders,
                                                           Instant asOf) {
        if (orders != null) {
            return orderedCompanyWindows(channels, orders);
        }
        List<UUID> listingVariantIds = channels.stream()
                .map(channel -> channel.observation().platformListingVariantId())
                .distinct().toList();
        List<DemandWindowEvidence> evidence = new ArrayList<>();
        for (DemandWindow window : DemandWindow.values()) {
            FactWindow factWindow = FactWindow.endingAt(asOf, Duration.ofDays(window.days()));
            Integer units = null;
            List<List<AvailabilityObservation>> timelines = new ArrayList<>();
            Map<java.time.LocalDate, Long> byDay = new HashMap<>();

            for (UUID listingVariantId : listingVariantIds) {
                SalesTotals sales =
                        facts.sales(listingVariantId, SaleStage.COMPLETED, null, factWindow);
                if (sales.evidence().present()) {
                    units = (units == null ? 0 : units) + (int) sales.units();
                }
                for (DailySaleTotal day : facts.dailyCompletedUnits(listingVariantId, factWindow)) {
                    byDay.merge(day.day(), day.completedUnits(), Long::sum);
                }
            }
            for (ChannelSubject channel : channels) {
                ChannelObservation observation = channel.observation();
                if (!"UNKNOWN".equals(observation.fulfillmentModeCode())) {
                    timelines.add(facts.availabilityObservations(
                            observation.platformListingVariantId(),
                            observation.fulfillmentModeCode(), factWindow));
                }
            }
            BigDecimal unionObservedDays = unionObservedDays(timelines, factWindow);
            List<AvailabilityObservation> allObservations = timelines.stream()
                    .flatMap(List::stream).toList();
            DemandWindowEvidence.CensoringReason reason = listingVariantIds.isEmpty()
                    ? DemandWindowEvidence.CensoringReason.SOURCE_STALE
                    : censoringReason(allObservations, unionObservedDays, factWindow);
            evidence.add(new DemandWindowEvidence(window, factWindow.periodStart(),
                    factWindow.periodEnd(), units, unionObservedDays, reason,
                    largestShare(byDay, units), DemandSource.COMPLETED_SALES,
                    companyObservationBegan(timelines, factWindow)));
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
                largestShare(byDay, units), DemandSource.COMPLETED_SALES,
                observationBegan(timeline, factWindow));
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

    /** Duration of the union of saleable intervals across every channel/mode. */
    static BigDecimal unionObservedDays(List<List<AvailabilityObservation>> timelines,
                                        FactWindow window) {
        long minutes = 0;
        for (TimeInterval interval : mergedSaleable(timelines, window)) {
            minutes += Duration.between(interval.start(), interval.end()).toMinutes();
        }
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_DAY, RATIO);
    }

    /** The saleable intervals of every timeline inside the window, merged where they overlap. */
    static List<TimeInterval> mergedSaleable(List<List<AvailabilityObservation>> timelines,
                                             FactWindow window) {
        List<TimeInterval> intervals = new ArrayList<>();
        for (List<AvailabilityObservation> timeline : timelines) {
            for (int index = 0; index < timeline.size(); index++) {
                AvailabilityObservation current = timeline.get(index);
                if (current.observedAt() == null || !current.saleable()) {
                    continue;
                }
                Instant end = index + 1 < timeline.size()
                        ? timeline.get(index + 1).observedAt() : window.periodEnd();
                Instant start = current.observedAt().isBefore(window.periodStart())
                        ? window.periodStart() : current.observedAt();
                if (end != null && end.isAfter(start)) {
                    intervals.add(new TimeInterval(start,
                            end.isAfter(window.periodEnd()) ? window.periodEnd() : end));
                }
            }
        }
        intervals.sort(java.util.Comparator.comparing(TimeInterval::start)
                .thenComparing(TimeInterval::end));
        List<TimeInterval> merged = new ArrayList<>();
        Instant start = null;
        Instant end = null;
        for (TimeInterval interval : intervals) {
            if (start == null) {
                start = interval.start();
                end = interval.end();
            } else if (!interval.start().isAfter(end)) {
                if (interval.end().isAfter(end)) {
                    end = interval.end();
                }
            } else {
                merged.add(new TimeInterval(start, end));
                start = interval.start();
                end = interval.end();
            }
        }
        if (start != null) {
            merged.add(new TimeInterval(start, end));
        }
        return merged;
    }

    private record TimeInterval(Instant start, Instant end) {
    }

    /**
     * When stock and sellability were first both stated inside the window, or {@code null} when
     * they already were at its start (or never were). A subject first watched inside a window could
     * not have been watched for all of it, which is a wait rather than a defect.
     */
    static Instant observationBegan(List<AvailabilityObservation> timeline, FactWindow window) {
        for (AvailabilityObservation observation : timeline) {
            if (observation.observedAt() != null && observation.availableUnits() != null
                    && !"UNKNOWN".equals(observation.sellable())) {
                return observation.observedAt().isAfter(window.periodStart())
                        ? observation.observedAt() : null;
            }
        }
        return null;
    }

    /** The company's watch began with its earliest channel's, unless one was watched from the start. */
    static Instant companyObservationBegan(List<List<AvailabilityObservation>> timelines,
                                           FactWindow window) {
        Instant earliest = null;
        for (List<AvailabilityObservation> timeline : timelines) {
            boolean stated = timeline.stream().anyMatch(observation ->
                    observation.availableUnits() != null && !"UNKNOWN".equals(observation.sellable()));
            if (!stated) {
                continue;
            }
            Instant began = observationBegan(timeline, window);
            if (began == null) {
                return null;
            }
            earliest = earliest == null || began.isBefore(earliest) ? began : earliest;
        }
        return earliest;
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

    private static Sellability warehouseSellability(String sellable) {
        if (sellable == null) {
            return Sellability.UNKNOWN;
        }
        return switch (sellable) {
            case "YES" -> Sellability.SELLABLE;
            case "NO" -> Sellability.NOT_SELLABLE;
            default -> Sellability.UNKNOWN;
        };
    }
}
