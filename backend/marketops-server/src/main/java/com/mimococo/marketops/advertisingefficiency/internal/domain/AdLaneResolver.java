package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import java.util.List;
import java.util.Objects;

/**
 * Decides which of the four lanes one advertising case is in.
 *
 * <p>The order of the ladder is load-bearing and is the design, not an
 * implementation detail. Each rung answers a question that must be settled
 * before the next one is even meaningful, and reordering any two of them
 * changes what the product does:
 *
 * <ol>
 *   <li>Is a prior action's outcome or execution state unresolved? Nothing else
 *       matters while the system might be lying about what it already did.</li>
 *   <li>Is there Fresh one-sided proof of continuing harm? Proven loss outranks
 *       every opportunity and every data defect, because the money is leaving
 *       now.</li>
 *   <li>Is a decision-determinative fact missing, stale or conflicted? That is
 *       somebody's job, not a smaller opportunity.</li>
 *   <li>Is the opportunity complete, fresh, sustained and material? Only then
 *       is it work.</li>
 *   <li>Otherwise it is visible and it waits.</li>
 * </ol>
 *
 * <p>Rung two before rung three is the directional asymmetry: Protection may
 * proceed on a one-sided proof while Optimization may not, and swapping them
 * would let a broken feed silence a live loss. Rung three before rung four is
 * why a material defect never presents as a quiet Watch.
 */
public final class AdLaneResolver {

    private AdLaneResolver() {
    }

    /** The decided lane, its Protection sub-tier when it has one, and the cause. */
    public record Decision(
            AdvertisingLane lane,
            ProtectionTier protectionTier,
            AdvertisingCause cause,
            AdEvidenceState evidenceState,
            AdConfidence confidence,
            List<String> blockerCodes) {

        public Decision {
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(evidenceState, "evidenceState");
            Objects.requireNonNull(confidence, "confidence");
            blockerCodes = List.copyOf(Objects.requireNonNull(blockerCodes, "blockerCodes"));
            if ((lane == AdvertisingLane.PROTECTION) != (protectionTier != null)) {
                throw new IllegalArgumentException(
                        "a Protection case carries a sub-tier and no other lane may");
            }
        }
    }

    /** Everything the ladder needs, gathered by the caller and decided nowhere else. */
    public record Signals(
            boolean unresolvedExecutionOrRegression,
            boolean quarantineActive,
            boolean compensationDecisionPending,
            OneSidedDangerProof sellabilityDanger,
            OneSidedDangerProof availabilityDanger,
            OneSidedDangerProof criticalSalesDanger,
            OneSidedDangerProof economicHarm,
            OneSidedDangerProof policyQualifiedDanger,
            AdvertisingCause dataDefectCause,
            List<String> dataDefectBlockerCodes,
            boolean objectIndependentlyControllable,
            boolean optimizationQualified,
            boolean optimizationMaterial,
            AdMeasure recoverableProfit,
            AdEvidenceState evidenceState,
            AdConfidence confidence) {

        public Signals {
            Objects.requireNonNull(evidenceState, "evidenceState");
            Objects.requireNonNull(confidence, "confidence");
            dataDefectBlockerCodes =
                    List.copyOf(Objects.requireNonNull(dataDefectBlockerCodes, "dataDefectBlockerCodes"));
        }
    }

    /**
     * Walk the ladder.
     *
     * <p>Every rung returns rather than accumulating, so a case is in exactly one
     * lane for exactly one reason, and the reason is the one the operator is
     * shown. A case that is both losing money and missing a mapping is a
     * Protection case whose blockers say the mapping is missing — it is not two
     * cases, and it is not a Data Repair case that happens to be losing money.
     */
    public static Decision resolve(Signals signals) {
        Objects.requireNonNull(signals, "signals");

        // 1. Execution integrity. A system that cannot say what it already did
        //    has no business proposing what to do next.
        if (signals.unresolvedExecutionOrRegression()
                || signals.quarantineActive()
                || signals.compensationDecisionPending()) {
            return protection(ProtectionTier.P0, AdvertisingCause.ACTION_OUTCOME_REGRESSION, signals);
        }

        // 2. Fresh one-sided danger. Sellability and availability come first
        //    within the tier because they are the two cases where every rouble
        //    spent is certainly wasted regardless of any missing conversion.
        if (signals.sellabilityDanger() != null && signals.sellabilityDanger().established()) {
            return protection(ProtectionTier.P1, AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE, signals);
        }
        if (signals.availabilityDanger() != null && signals.availabilityDanger().established()) {
            return protection(ProtectionTier.P1, AdvertisingCause.PROMOTED_VARIANT_UNAVAILABLE, signals);
        }
        if (signals.criticalSalesDanger() != null && signals.criticalSalesDanger().established()) {
            return protection(ProtectionTier.P1, AdvertisingCause.CRITICAL_SALES_UNIT_AT_RISK, signals);
        }
        if (signals.economicHarm() != null && signals.economicHarm().established()) {
            return protection(ProtectionTier.P2, AdvertisingCause.PROVEN_ADVERTISING_LOSS, signals);
        }
        if (signals.policyQualifiedDanger() != null && signals.policyQualifiedDanger().established()) {
            return protection(ProtectionTier.P3, AdvertisingCause.PROVEN_ADVERTISING_LOSS, signals);
        }

        // 3. A decision-determinative defect. Somebody owns this; it is not a
        //    smaller opportunity and it is not a quiet wait.
        if (signals.dataDefectCause() != null && signals.dataDefectCause().dataDefect()) {
            return new Decision(AdvertisingLane.DATA_REPAIR, null, signals.dataDefectCause(),
                    signals.evidenceState(), signals.confidence(), signals.dataDefectBlockerCodes());
        }

        // 4. A qualified, material, sustained opportunity — and only on an
        //    object the marketplace lets us control, because a recommendation
        //    nobody can execute is not an opportunity.
        if (signals.optimizationQualified()
                && signals.optimizationMaterial()
                && signals.objectIndependentlyControllable()
                && signals.recoverableProfit() != null
                && signals.recoverableProfit().present()) {
            return new Decision(AdvertisingLane.OPTIMIZATION, null,
                    AdvertisingCause.RECOVERABLE_ADVERTISING_PROFIT,
                    signals.evidenceState(), signals.confidence(), signals.dataDefectBlockerCodes());
        }

        // 5. Visible, and waiting for maturity, sustainment or materiality.
        AdvertisingCause watchCause = signals.objectIndependentlyControllable()
                ? AdvertisingCause.IMMATURE_SIGNAL
                : AdvertisingCause.OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE;
        return new Decision(AdvertisingLane.WATCH, null, watchCause,
                signals.evidenceState(), signals.confidence(), signals.dataDefectBlockerCodes());
    }

    private static Decision protection(
            ProtectionTier tier, AdvertisingCause cause, Signals signals) {
        return new Decision(AdvertisingLane.PROTECTION, tier, cause,
                signals.evidenceState(), signals.confidence(), signals.dataDefectBlockerCodes());
    }
}
