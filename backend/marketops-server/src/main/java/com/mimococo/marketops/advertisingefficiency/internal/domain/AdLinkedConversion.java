package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A conversion rate the product is allowed to believe.
 *
 * <p>This type exists to make one specific piece of arithmetic impossible. The
 * tempting shortcut — company sales over advertising clicks — produces a number
 * that looks like a conversion rate, sorts like a conversion rate, and is not
 * one, because the numerator counts sales this object had nothing to do with.
 * A bid computed from it is wrong in the direction of spending more.
 *
 * <p>So the numerator is not a number here. It is a count of sale events that
 * were deterministically linked to this exact object or to a governed scope
 * containing it, over a window that matches the traffic denominator, over an
 * affected set that was completely resolved, with coverage above the floor the
 * governing definition published. {@link #writeGrade(BigDecimal, BigDecimal)}
 * is the only constructor that produces a conversion a controlled write may
 * consume, and it refuses every one of those conditions individually so the
 * refusal says which one failed.
 *
 * <p>The stage travels with the rate for the same reason: an Allowable CPA
 * priced against an Order multiplied by a conversion measured against a
 * Retained Sale is wrong by the whole cancellation and return rate, and
 * {@link MaxCpc} refuses that multiplication by comparing these stages.
 */
public record AdLinkedConversion(
        SaleStage stage,
        AdMeasure rate,
        long linkedEventCount,
        long eligibleTrafficCount,
        BigDecimal linkageCoverageRatio,
        BigDecimal affectedSetCoverageRatio,
        boolean affectedSetComplete,
        boolean windowsAligned,
        AdEvidenceState evidenceState) {

    private static final MathContext CONTEXT = new MathContext(12, RoundingMode.HALF_UP);

    public AdLinkedConversion {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(evidenceState, "evidenceState");
        if (linkedEventCount < 0 || eligibleTrafficCount < 0) {
            throw new IllegalArgumentException("event and traffic counts cannot be negative");
        }
        if (!stage.canonical() && rate.sufficientForWrite()) {
            throw new IllegalArgumentException(
                    "a provider observation can never be a write-grade conversion");
        }
    }

    /**
     * A conversion that may be consumed by a controlled write.
     *
     * <p>Every argument the Contract names is checked here, and a failure
     * produces a refusal rather than a weaker number, because a conversion
     * silently degraded to "best effort" is the input that makes an unjustified
     * bid look justified.
     */
    public static AdLinkedConversion writeGrade(
            SaleStage stage,
            long linkedEventCount,
            long eligibleTrafficCount,
            BigDecimal linkageCoverageRatio,
            BigDecimal affectedSetCoverageRatio,
            boolean affectedSetComplete,
            boolean windowsAligned,
            long minimumSampleEvents,
            BigDecimal minimumLinkageCoverage,
            BigDecimal minimumAffectedSetCoverage,
            AdEvidenceState evidenceState) {
        Objects.requireNonNull(stage, "stage");
        if (!stage.canonical()) {
            return blocked(stage, AdEvidenceState.NOT_AVAILABLE,
                    linkedEventCount, eligibleTrafficCount,
                    linkageCoverageRatio, affectedSetCoverageRatio,
                    affectedSetComplete, windowsAligned);
        }
        if (!evidenceState.sufficientForWrite()) {
            return blocked(stage, evidenceState, linkedEventCount, eligibleTrafficCount,
                    linkageCoverageRatio, affectedSetCoverageRatio,
                    affectedSetComplete, windowsAligned);
        }
        if (!affectedSetComplete || !windowsAligned) {
            return blocked(stage, AdEvidenceState.INCOMPLETE, linkedEventCount, eligibleTrafficCount,
                    linkageCoverageRatio, affectedSetCoverageRatio,
                    affectedSetComplete, windowsAligned);
        }
        if (eligibleTrafficCount <= 0) {
            // No denominator is not a zero conversion. It is no conversion.
            return new AdLinkedConversion(stage,
                    AdMeasure.undefined(AdEvidenceState.NOT_AVAILABLE),
                    linkedEventCount, eligibleTrafficCount,
                    linkageCoverageRatio, affectedSetCoverageRatio,
                    affectedSetComplete, windowsAligned, AdEvidenceState.NOT_AVAILABLE);
        }
        if (linkedEventCount < minimumSampleEvents) {
            return blocked(stage, AdEvidenceState.INCOMPLETE, linkedEventCount, eligibleTrafficCount,
                    linkageCoverageRatio, affectedSetCoverageRatio,
                    affectedSetComplete, windowsAligned);
        }
        if (below(linkageCoverageRatio, minimumLinkageCoverage)
                || below(affectedSetCoverageRatio, minimumAffectedSetCoverage)) {
            return blocked(stage, AdEvidenceState.INCOMPLETE, linkedEventCount, eligibleTrafficCount,
                    linkageCoverageRatio, affectedSetCoverageRatio,
                    affectedSetComplete, windowsAligned);
        }
        BigDecimal value = BigDecimal.valueOf(linkedEventCount)
                .divide(BigDecimal.valueOf(eligibleTrafficCount), CONTEXT);
        return new AdLinkedConversion(stage,
                AdMeasure.available(value, evidenceState),
                linkedEventCount, eligibleTrafficCount,
                linkageCoverageRatio, affectedSetCoverageRatio,
                affectedSetComplete, windowsAligned, evidenceState);
    }

    /**
     * The marketplace's own attributed conversion, recorded as what it is.
     *
     * <p>Available for reconciliation, discrepancy work and trend diagnosis. Not
     * a company number, and never write-grade — the compact constructor above
     * enforces that rather than trusting callers to remember it.
     */
    public static AdLinkedConversion providerObservation(
            long attributedEvents, long providerTraffic) {
        if (providerTraffic <= 0) {
            return new AdLinkedConversion(SaleStage.PROVIDER_NATIVE_OBSERVATION,
                    AdMeasure.undefined(AdEvidenceState.NOT_AVAILABLE),
                    attributedEvents, providerTraffic, null, null, false, false,
                    AdEvidenceState.NOT_AVAILABLE);
        }
        BigDecimal value = BigDecimal.valueOf(attributedEvents)
                .divide(BigDecimal.valueOf(providerTraffic), CONTEXT);
        return new AdLinkedConversion(SaleStage.PROVIDER_NATIVE_OBSERVATION,
                AdMeasure.available(value, AdEvidenceState.PROVISIONAL_OR_ESTIMATED),
                attributedEvents, providerTraffic, null, null, false, false,
                AdEvidenceState.PROVISIONAL_OR_ESTIMATED);
    }

    /** Whether a controlled write may consume this conversion. */
    public boolean writeGrade() {
        return stage.canonical() && rate.sufficientForWrite() && affectedSetComplete && windowsAligned;
    }

    private static AdLinkedConversion blocked(
            SaleStage stage,
            AdEvidenceState evidenceState,
            long linkedEventCount,
            long eligibleTrafficCount,
            BigDecimal linkageCoverageRatio,
            BigDecimal affectedSetCoverageRatio,
            boolean affectedSetComplete,
            boolean windowsAligned) {
        return new AdLinkedConversion(stage, AdMeasure.notAvailable(evidenceState),
                linkedEventCount, eligibleTrafficCount, linkageCoverageRatio,
                affectedSetCoverageRatio, affectedSetComplete, windowsAligned, evidenceState);
    }

    private static boolean below(BigDecimal observed, BigDecimal floor) {
        if (floor == null) {
            return false;
        }
        return observed == null || observed.compareTo(floor) < 0;
    }
}
