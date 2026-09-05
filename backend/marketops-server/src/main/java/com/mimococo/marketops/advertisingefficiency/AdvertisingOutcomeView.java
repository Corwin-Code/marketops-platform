package com.mimococo.marketops.advertisingefficiency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One observation of what a bid change actually did.
 *
 * <p>Completed, 30-day retained and settled sales are independent observation
 * stages. Late corrections append revisions to the affected stage.
 *
 * <p>{@code revisionNo} and {@code supersedesObservationId} exist for the same
 * reason. A settled figure can be restated when a late return arrives, and the
 * restatement is a new observation that names the one it replaces — never an
 * edit of the original, which would erase the fact that the answer changed.
 *
 * @param id the observation
 * @param commandId the change it is about
 * @param outcomeStage which reading this is
 * @param revisionNo which restatement of that reading, starting at one
 * @param supersedesObservationId the observation it replaces, or {@code null}
 * @param adjustmentReason why it was restated, or {@code null}
 * @param windowStartsAt the start of the measured window
 * @param windowEndsAt the end of it
 * @param baselineMetricState whether a baseline figure exists at all
 * @param baselineMetricValue the baseline, or {@code null} when it does not
 * @param observedMetricState whether an observed figure exists at all
 * @param observedMetricValue the observed value, or {@code null}
 * @param observedTrafficCount the traffic behind the observation, or {@code null}
 * @param settledCoverageRatio how much of the orders have settled, or {@code null}
 * @param verdict what the comparison concluded
 * @param guardState what the completed-sales guard said before anything was acted on
 * @param unresolvedReasonCodes why the verdict is not conclusive, when it is not
 * @param evaluatedAt when this observation was taken
 */
public record AdvertisingOutcomeView(
        UUID id,
        UUID commandId,
        UUID manualPacketId,
        String outcomeStage,
        int revisionNo,
        UUID supersedesObservationId,
        String adjustmentReason,
        Instant windowStartsAt,
        Instant windowEndsAt,
        String baselineMetricState,
        BigDecimal baselineMetricValue,
        String observedMetricState,
        BigDecimal observedMetricValue,
        Long observedTrafficCount,
        BigDecimal settledCoverageRatio,
        String verdict,
        String guardState,
        List<String> unresolvedReasonCodes,
        Instant evaluatedAt,
        Axes axes) {

    public record CriticalGuard(UUID productVariantId, UUID listingVariantId, String guardState,
            BigDecimal baselineSales, BigDecimal observedSales) { }
    public record Axes(String dualAxisVerdict, String salesPreservationVerdict, String businessOutcome,
            BigDecimal baselineAbsoluteProfit, BigDecimal observedAbsoluteProfit,
            BigDecimal baselineProfitPerRub, BigDecimal observedProfitPerRub,
            BigDecimal companyBaselineSales, BigDecimal companyObservedSales,
            String currencyCode, String inputSnapshot, List<CriticalGuard> criticalGuards) { }

    public AdvertisingOutcomeView(UUID id, UUID commandId, String outcomeStage, int revisionNo,
            UUID supersedesObservationId, String adjustmentReason, Instant windowStartsAt,
            Instant windowEndsAt, String baselineMetricState, BigDecimal baselineMetricValue,
            String observedMetricState, BigDecimal observedMetricValue, Long observedTrafficCount,
            BigDecimal settledCoverageRatio, String verdict, String guardState,
            List<String> unresolvedReasonCodes, Instant evaluatedAt) {
        this(id,commandId,null,outcomeStage,revisionNo,supersedesObservationId,adjustmentReason,windowStartsAt,
                windowEndsAt,baselineMetricState,baselineMetricValue,observedMetricState,observedMetricValue,
                observedTrafficCount,settledCoverageRatio,verdict,guardState,unresolvedReasonCodes,evaluatedAt,null);
    }

    public AdvertisingOutcomeView withAxes(Axes newAxes) {
        return new AdvertisingOutcomeView(id,commandId,manualPacketId,outcomeStage,revisionNo,supersedesObservationId,
                adjustmentReason,windowStartsAt,windowEndsAt,baselineMetricState,baselineMetricValue,
                observedMetricState,observedMetricValue,observedTrafficCount,settledCoverageRatio,
                verdict,guardState,unresolvedReasonCodes,evaluatedAt,newAxes);
    }

    public AdvertisingOutcomeView {
        Objects.requireNonNull(id, "id");
        if((commandId == null) == (manualPacketId == null)) throw new IllegalArgumentException("exactly one intervention anchor is required");
        Objects.requireNonNull(outcomeStage, "outcomeStage");
        Objects.requireNonNull(verdict, "verdict");
        unresolvedReasonCodes =
                List.copyOf(unresolvedReasonCodes == null ? List.of() : unresolvedReasonCodes);
    }

    /** Whether this reading counts what the buyer kept rather than what they ordered. */
    public boolean settled() {
        return outcomeStage.startsWith("SETTLED");
    }

    /** Whether this observation has itself been restated by a later one. */
    public boolean restatement() {
        return supersedesObservationId != null;
    }
}
