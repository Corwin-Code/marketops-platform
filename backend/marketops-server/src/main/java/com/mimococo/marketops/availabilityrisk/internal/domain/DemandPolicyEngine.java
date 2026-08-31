package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.RiskConfidence;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses one demand rate from three windows, deterministically and with reasons.
 *
 * <p>Three rules shape everything here.
 *
 * <p>It never takes the most urgent window because it is the most urgent. A
 * policy that always picked the highest rate would be a policy that always
 * reports danger, and an operator learns within a week to ignore it. The
 * baseline is the longest eligible window; a shorter one wins only when the
 * evidence shows a sustained, monotone change.
 *
 * <p>It never treats an unobservable period as a quiet one. A window whose
 * coverage falls below the policy floor is censored, and a censored window is
 * removed from selection rather than allowed to drag the rate down.
 *
 * <p>It never returns zero to mean "we do not know". When no window is eligible
 * the answer is a blocked state carrying the reason, or — for a bounded,
 * versioned period — the last eligible answer, visibly downgraded.
 */
public final class DemandPolicyEngine {

    private DemandPolicyEngine() {
    }

    /** The eligibility verdict for one window. */
    public enum WindowEligibility {
        /** Usable as evidence. */
        ELIGIBLE,
        /** Too few units to distinguish a rate from noise. */
        LOW_SAMPLE,
        /** Too little of the window was observable. */
        CENSORED,
        /** One day dominates the window and needs a human look. */
        OUTLIER_REVIEW,
        /** The windows disagree in a way no rule resolves. */
        WINDOW_CONFLICT,
        /** No source answered for this window. */
        DATA_BLOCKED
    }

    /**
     * Decide the canonical demand rate.
     *
     * @param evidence the three windows, in any order
     * @param settings the published policy version in force
     * @param lastEligible the previous eligible answer, or {@code null}
     * @param now the calculation instant
     */
    public static DemandDecision decide(List<DemandWindowEvidence> evidence,
                                        DemandPolicySettings settings,
                                        CarriedForwardDemand lastEligible,
                                        Instant now) {
        Map<DemandWindow, DemandWindowEvidence> byWindow = new EnumMap<>(DemandWindow.class);
        for (DemandWindowEvidence one : evidence) {
            byWindow.put(one.window(), one);
        }
        Map<DemandWindow, WindowEligibility> verdicts = new EnumMap<>(DemandWindow.class);
        for (DemandWindow window : DemandWindow.values()) {
            verdicts.put(window, classify(byWindow.get(window), settings));
        }

        List<DemandWindow> eligible = new ArrayList<>();
        for (DemandWindow window : DemandWindow.values()) {
            if (verdicts.get(window) == WindowEligibility.ELIGIBLE) {
                eligible.add(window);
            }
        }

        if (eligible.isEmpty()) {
            return unusable(evidence, verdicts, settings, lastEligible, now);
        }

        DemandWindow baseline = eligible.get(eligible.size() - 1);
        Trend trend = trend(byWindow, verdicts, settings);

        DemandWindow selected;
        String reason;
        switch (trend) {
            case ACCELERATING -> {
                selected = DemandWindow.D7;
                reason = "sustained recent acceleration: D7 exceeds D14 beyond the policy ratio";
            }
            case DECELERATING -> {
                selected = DemandWindow.D7;
                reason = "sustained recent deceleration: D7 falls below D14 beyond the policy ratio";
            }
            case CONFLICTED -> {
                return conflicted(evidence, settings);
            }
            default -> {
                selected = baseline;
                reason = "stable baseline: longest eligible window " + baseline.name();
            }
        }

        DemandWindowEvidence chosen = byWindow.get(selected);
        RiskConfidence confidence = confidenceFor(selected, verdicts, byWindow);
        return new DemandDecision(chosen.dailyRate(), selected, reason,
                RiskEvidenceState.CONFIRMED, confidence, List.copyOf(evidence), null, null);
    }

    /** How the recent windows move relative to the longer ones. */
    private enum Trend { STABLE, ACCELERATING, DECELERATING, CONFLICTED }

    /**
     * Classify the movement between windows.
     *
     * <p>"Sustained" means monotone, not merely large. A single spike lifts D7
     * above D14 while leaving D14 at or below D30, and treating that as
     * acceleration is exactly how one unusual day becomes a permanent alarm.
     */
    private static Trend trend(Map<DemandWindow, DemandWindowEvidence> byWindow,
                               Map<DemandWindow, WindowEligibility> verdicts,
                               DemandPolicySettings settings) {
        if (verdicts.get(DemandWindow.D7) != WindowEligibility.ELIGIBLE
                || verdicts.get(DemandWindow.D14) != WindowEligibility.ELIGIBLE) {
            return Trend.STABLE;
        }
        BigDecimal shortRate = byWindow.get(DemandWindow.D7).dailyRate();
        BigDecimal midRate = byWindow.get(DemandWindow.D14).dailyRate();
        if (shortRate == null || midRate == null || midRate.signum() == 0) {
            return Trend.STABLE;
        }
        boolean longEligible = verdicts.get(DemandWindow.D30) == WindowEligibility.ELIGIBLE;
        BigDecimal longRate = longEligible ? byWindow.get(DemandWindow.D30).dailyRate() : null;

        boolean risingStep = shortRate.compareTo(midRate.multiply(settings.accelerationRatio())) >= 0;
        boolean fallingStep = shortRate.compareTo(midRate.multiply(settings.decelerationRatio())) <= 0;

        if (longRate == null) {
            return risingStep ? Trend.ACCELERATING : fallingStep ? Trend.DECELERATING : Trend.STABLE;
        }
        boolean midAboveLong = midRate.compareTo(longRate) >= 0;
        boolean midBelowLong = midRate.compareTo(longRate) <= 0;

        if (risingStep && midAboveLong) {
            return Trend.ACCELERATING;
        }
        if (fallingStep && midBelowLong) {
            return Trend.DECELERATING;
        }
        if (risingStep || fallingStep) {
            // The step is large but the direction is not sustained across the
            // longer window. Picking either end would be a coin toss presented
            // as a calculation.
            return Trend.CONFLICTED;
        }
        return Trend.STABLE;
    }

    private static WindowEligibility classify(DemandWindowEvidence evidence,
                                              DemandPolicySettings settings) {
        if (evidence == null || !evidence.observed()) {
            return WindowEligibility.DATA_BLOCKED;
        }
        if (evidence.coverageRatio().compareTo(settings.minimumCoverageRatio()) < 0) {
            return WindowEligibility.CENSORED;
        }
        if (evidence.completedUnits() < settings.minimumSampleUnits()) {
            return WindowEligibility.LOW_SAMPLE;
        }
        BigDecimal share = evidence.largestSingleDayShare();
        if (share != null && share.compareTo(settings.outlierShareRatio()) > 0) {
            return WindowEligibility.OUTLIER_REVIEW;
        }
        return WindowEligibility.ELIGIBLE;
    }

    private static DemandDecision conflicted(List<DemandWindowEvidence> evidence,
                                             DemandPolicySettings settings) {
        return new DemandDecision(null, null,
                "window conflict: a large recent step is not sustained across the longer window",
                RiskEvidenceState.CONFLICTED, RiskConfidence.UNUSABLE,
                List.copyOf(evidence), null, null);
    }

    /**
     * Answer when no window is eligible.
     *
     * <p>Carry-forward is allowed only when censoring is what made the windows
     * ineligible. A low sample or an unexplained outlier is a different problem,
     * and carrying an old rate over it would hide it.
     */
    private static DemandDecision unusable(List<DemandWindowEvidence> evidence,
                                           Map<DemandWindow, WindowEligibility> verdicts,
                                           DemandPolicySettings settings,
                                           CarriedForwardDemand lastEligible,
                                           Instant now) {
        boolean censoredSomewhere = verdicts.containsValue(WindowEligibility.CENSORED);
        if (censoredSomewhere && lastEligible != null) {
            Instant expiry = lastEligible.observedAt().plus(settings.carryForwardMax());
            if (now.isBefore(expiry)) {
                return new DemandDecision(lastEligible.rate(), lastEligible.window(),
                        "carried forward: every recent window is materially censored",
                        RiskEvidenceState.CARRIED_FORWARD, RiskConfidence.LOW,
                        List.copyOf(evidence), lastEligible.observedAt(), expiry);
            }
            return new DemandDecision(null, null,
                    "carry-forward expired while observation remained censored",
                    RiskEvidenceState.DATA_BLOCKED, RiskConfidence.UNUSABLE,
                    List.copyOf(evidence), lastEligible.observedAt(), expiry);
        }
        String reason;
        if (censoredSomewhere) {
            reason = "every recent window is materially censored and nothing eligible was ever observed";
        } else if (verdicts.containsValue(WindowEligibility.OUTLIER_REVIEW)) {
            reason = "one day dominates every window; an unexplained outlier needs review";
        } else if (verdicts.containsValue(WindowEligibility.LOW_SAMPLE)) {
            reason = "every window is below the policy minimum sample";
        } else {
            reason = "no source answered for any window";
        }
        return new DemandDecision(null, null, reason,
                RiskEvidenceState.DATA_BLOCKED, RiskConfidence.UNUSABLE,
                List.copyOf(evidence), null, null);
    }

    /**
     * How confident the selection is.
     *
     * <p>Censoring anywhere costs confidence even when the selected window
     * itself was clean: a rate chosen from the one window that could be observed
     * is a weaker statement than the same rate confirmed by all three.
     */
    private static RiskConfidence confidenceFor(DemandWindow selected,
                                                Map<DemandWindow, WindowEligibility> verdicts,
                                                Map<DemandWindow, DemandWindowEvidence> byWindow) {
        long ineligible = verdicts.values().stream()
                .filter(verdict -> verdict != WindowEligibility.ELIGIBLE).count();
        DemandWindowEvidence chosen = byWindow.get(selected);
        if (ineligible == 0 && !chosen.censored()) {
            return RiskConfidence.HIGH;
        }
        if (ineligible >= 2) {
            return RiskConfidence.LOW;
        }
        return RiskConfidence.MEDIUM;
    }
}
