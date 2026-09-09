package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.analyticsdecision.CalculationRunLedger;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.listingconversion.ConversionMeasurementView;
import com.mimococo.marketops.listingconversion.EvidencePath;
import com.mimococo.marketops.listingconversion.RatioState;
import com.mimococo.marketops.listingconversion.internal.domain.EvidencePathQualification;
import com.mimococo.marketops.listingconversion.internal.domain.VersionWindow;
import com.mimococo.marketops.listingconversion.internal.domain.VisitConversion;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingHealthRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Measures retained-visit conversion for one listing, window and evidence path.
 *
 * <p>The detail path computes the set ratio from visits and links; the official
 * summary path reads the platform's counts and qualifies them only under a
 * proven equivalence profile. Neither path ever reads the platform's label.
 */
@Service
public class ConversionMeasurementService {

    static final int DEFINITION_VERSION = 1;
    static final String SUMMARY_KIND = "VISITS_AND_RETAINED_PURCHASES";

    private final ListingFactRepository facts;
    private final ListingHealthRepository measurements;
    private final CalculationRunLedger ledger;
    private final IdGenerator ids;
    private final Clock clock;

    ConversionMeasurementService(ListingFactRepository facts, ListingHealthRepository measurements,
                                 CalculationRunLedger ledger, IdGenerator ids, Clock clock) {
        this.facts = facts;
        this.measurements = measurements;
        this.ledger = ledger;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public ConversionMeasurementView measure(UUID listingId, Instant windowStart, Instant windowEnd, int retentionDays,
                                             EvidencePath path, String triggerKind) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!windowStart.isBefore(windowEnd) || (retentionDays != 7 && retentionDays != 14 && retentionDays != 30)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant now = clock.instant();
        boolean maturity = ListingFactRepository.maturityReached(windowEnd, retentionDays, now);
        VersionWindow.Attribution attribution = VersionWindow.attribute(
                facts.displays(listingId, windowStart, windowEnd), windowStart, windowEnd);
        List<VisitConversion.Visit> visits = facts.visits(listingId, windowStart, windowEnd);
        boolean stratified = !visits.isEmpty() && visits.stream().noneMatch(v -> "UNKNOWN".equals(v.sourceChannel()));
        EvidencePathQualification.SummaryProfile profile = facts.summaryProfile(listing.organizationId(),
                listing.platformCode(), SUMMARY_KIND, now);
        List<String> disqualifications = EvidencePathQualification.disqualifications(path, !visits.isEmpty(),
                facts.purchaseLinksPresent(listingId), stratified, profile);
        boolean qualified = disqualifications.isEmpty();

        Long visitCount;
        Long retainedCount;
        BigDecimal ratio;
        RatioState state;
        Map<String, String> split;
        if (path == EvidencePath.DETAIL) {
            VisitConversion.Result result = VisitConversion.compute(visits,
                    facts.retainedVisitKeys(listingId, windowStart, windowEnd, retentionDays), maturity, qualified);
            visitCount = result.visitCount();
            retainedCount = result.retainedPurchaseVisitCount();
            ratio = result.ratio();
            state = result.state();
            split = result.sellableSplit();
        } else {
            Optional<ListingFactRepository.SummaryRow> summary = facts.latestSummary(listingId, windowStart, windowEnd);
            visitCount = summary.map(ListingFactRepository.SummaryRow::reportedVisits).orElse(null);
            retainedCount = summary.map(ListingFactRepository.SummaryRow::reportedRetainedPurchases).orElse(null);
            split = Map.of();
            if (!qualified || !maturity || visitCount == null || retainedCount == null) {
                ratio = null;
                state = RatioState.NOT_AVAILABLE;
            } else if (visitCount == 0) {
                ratio = null;
                state = RatioState.UNDEFINED;
            } else {
                retainedCount = Math.min(retainedCount, visitCount);
                ratio = BigDecimal.valueOf(retainedCount).divide(BigDecimal.valueOf(visitCount), 6, RoundingMode.DOWN);
                state = RatioState.DEFINED;
            }
        }
        UUID runId = ledger.recordCompletedRun(new CalculationRunLedger.CompletedRun(listing.organizationId(),
                listing.storeId(), triggerKind, windowOf(retentionDays), windowStart, windowEnd,
                Digest.ofText("lc-conversion-" + DEFINITION_VERSION), 1, 1, true, null, now));
        UUID id = ids.newId();
        measurements.insertMeasurement(id, listing.organizationId(), listing.storeId(), listingId, runId,
                DEFINITION_VERSION, windowStart, windowEnd, retentionDays, path, qualified, disqualifications,
                visitCount, retainedCount, ratio, state, maturity, stratified, split, attribution.excludedDays(),
                null, now, now);
        return measurements.measurement(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ConversionMeasurementView> history(UUID listingId, int limit) {
        return measurements.measurements(listingId, limit);
    }

    private static MetricWindow windowOf(int retentionDays) {
        return switch (retentionDays) {
            case 7 -> MetricWindow.D7;
            case 14 -> MetricWindow.D14;
            default -> MetricWindow.D30;
        };
    }
}
