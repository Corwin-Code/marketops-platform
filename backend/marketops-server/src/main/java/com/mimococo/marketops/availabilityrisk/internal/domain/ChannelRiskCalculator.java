package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.ChildKind;
import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.RiskConfidence;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculates the risk for one exact listing variant and fulfillment mode.
 *
 * <p>The defining property is independence. A fresh observation that a channel
 * has nothing left is actionable on its own, and no amount of staleness in an
 * unrelated source is allowed to soften it. That is why this calculator takes
 * one observation rather than a portfolio, and why the out-of-stock branch is
 * evaluated before anything that could downgrade the answer.
 */
public final class ChannelRiskCalculator {

    private static final MathContext COVER = new MathContext(10, RoundingMode.HALF_UP);

    private ChannelRiskCalculator() {
    }

    /**
     * Calculate one channel child.
     *
     * @param observation what the source shows for this exact listing and mode
     * @param demand the demand decision scoped to this channel
     * @param leadTime the resolved policy, used only for its lane thresholds
     * @param profit which profit authority spoke
     * @param freshnessMaxMinutes how old an observation may be
     * @param asOf the calculation instant
     */
    public static ChildRisk calculate(ChannelObservation observation,
                                      DemandDecision demand,
                                      LeadTimeResolution leadTime,
                                      ProfitAssessment profit,
                                      long freshnessMaxMinutes,
                                      Instant asOf) {
        List<String> blockers = new ArrayList<>();
        boolean fresh = observation.freshAt(asOf, freshnessMaxMinutes);

        // A source that named the mode but published no quantity has told us
        // nothing about availability. Reading the omission as zero would
        // manufacture a stockout; reading it as "fine" would hide one.
        if (observation.availableUnits() == null) {
            blockers.add("CHANNEL_QUANTITY_NOT_REPORTED");
            return blocked(observation, demand, leadTime, profit,
                    RiskEvidenceState.UNKNOWN, AvailabilityLane.UNRESOLVED,
                    RiskCause.STOCK_DATA_DEFECT, blockers,
                    SupplyComponent.ExclusionReason.QUANTITY_NOT_REPORTED);
        }
        if (!fresh) {
            blockers.add("CHANNEL_OBSERVATION_STALE");
            return blocked(observation, demand, leadTime, profit,
                    RiskEvidenceState.STALE, AvailabilityLane.REVIEW,
                    RiskCause.STOCK_DATA_DEFECT, blockers,
                    SupplyComponent.ExclusionReason.STALE_OBSERVATION);
        }

        int units = observation.availableUnits();
        ProvenSupply supply = ProvenSupply.of(List.of(SupplyComponent.counted(
                SupplyComponent.Source.PLATFORM_VISIBLE, units,
                observation.provenanceId(), observation.observedAt())));

        // Fresh and unsellable is a confirmed channel failure whatever the
        // quantity is: units nobody can buy are not availability.
        if (observation.sellability() == Sellability.NOT_SELLABLE) {
            return new ChildRisk(ChildKind.CHANNEL, AvailabilityLane.CRITICAL,
                    RiskEvidenceState.CONFIRMED, RiskConfidence.HIGH,
                    RiskCause.CHANNEL_NOT_SELLABLE, supply, demand, leadTime, profit,
                    null, asOf, ConservativeProof.of(List.of(
                            ProofTerm.qualitative("CHANNEL_BLOCKED",
                                    "the source states the listing cannot be bought"
                                            + (observation.blockedReason() == null ? ""
                                            : ": " + observation.blockedReason())))),
                    List.of());
        }

        // Fresh zero is CRITICAL before demand is even consulted. Whether the
        // item sells one a month or fifty a day, a channel with nothing on it
        // is selling none of them right now.
        if (units == 0) {
            return new ChildRisk(ChildKind.CHANNEL, AvailabilityLane.CRITICAL,
                    RiskEvidenceState.CONFIRMED, RiskConfidence.HIGH,
                    RiskCause.CHANNEL_OUT_OF_STOCK, supply, demand, leadTime, profit,
                    BigDecimal.ZERO, asOf, ConservativeProof.of(List.of(
                            ProofTerm.of("CHANNEL_AVAILABLE_ZERO",
                                    "the source reports zero available units for this mode",
                                    BigDecimal.ZERO))),
                    List.of());
        }

        if (observation.sellability() == Sellability.UNKNOWN) {
            blockers.add("CHANNEL_SELLABILITY_UNKNOWN");
        }

        if (!demand.usable()) {
            blockers.add("CHANNEL_DEMAND_" + demand.evidenceState().name());
            return new ChildRisk(ChildKind.CHANNEL, AvailabilityLane.REVIEW,
                    demand.evidenceState(), RiskConfidence.UNUSABLE,
                    RiskCause.DEMAND_UNOBSERVABLE, supply, demand, leadTime, profit,
                    null, null, ConservativeProof.none(), List.copyOf(blockers));
        }
        if (!leadTime.resolved()) {
            blockers.add("LEAD_TIME_POLICY_UNRESOLVED");
            return new ChildRisk(ChildKind.CHANNEL, AvailabilityLane.REVIEW,
                    RiskEvidenceState.POLICY_BLOCKED, RiskConfidence.UNUSABLE,
                    RiskCause.LEAD_TIME_POLICY_MISSING, supply, demand, leadTime, profit,
                    null, null, ConservativeProof.none(), List.copyOf(blockers));
        }

        BigDecimal cover = coverDays(units, demand.selectedRate());
        AvailabilityLane lane = LaneThresholds.laneFor(cover, leadTime);
        RiskCause cause = lane == AvailabilityLane.HEALTHY
                ? RiskCause.NONE : RiskCause.CHANNEL_COVER_SHORT;
        RiskConfidence confidence = blockers.isEmpty()
                ? demand.confidence() : demand.confidence().weakest(RiskConfidence.MEDIUM);
        RiskEvidenceState evidence = demand.evidenceState() == RiskEvidenceState.CARRIED_FORWARD
                ? RiskEvidenceState.CARRIED_FORWARD : RiskEvidenceState.CONFIRMED;

        return new ChildRisk(ChildKind.CHANNEL, lane, evidence, confidence, cause,
                supply, demand, leadTime, profit, cover,
                LaneThresholds.stockoutAt(cover, asOf), ConservativeProof.none(),
                List.copyOf(blockers));
    }

    private static ChildRisk blocked(ChannelObservation observation, DemandDecision demand,
                                     LeadTimeResolution leadTime, ProfitAssessment profit,
                                     RiskEvidenceState evidence, AvailabilityLane lane,
                                     RiskCause cause, List<String> blockers,
                                     SupplyComponent.ExclusionReason reason) {
        ProvenSupply supply = ProvenSupply.of(List.of(SupplyComponent.excluded(
                SupplyComponent.Source.PLATFORM_VISIBLE,
                observation.availableUnits() == null ? 0 : observation.availableUnits(),
                reason, observation.provenanceId(), observation.observedAt())));
        return new ChildRisk(ChildKind.CHANNEL, lane, evidence, RiskConfidence.UNUSABLE,
                cause, supply, demand, leadTime, profit, null, null,
                ConservativeProof.none(), List.copyOf(blockers));
    }

    /** Days of cover, or {@code null} when demand is zero and cover is unbounded. */
    static BigDecimal coverDays(int units, BigDecimal dailyRate) {
        if (dailyRate == null || dailyRate.signum() <= 0) {
            return null;
        }
        return BigDecimal.valueOf(units).divide(dailyRate, COVER);
    }
}
