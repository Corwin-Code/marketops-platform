package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.ChildKind;
import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.RiskConfidence;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One calculated child risk, with everything needed to explain and rank it.
 *
 * <p>This is the calculator's output and the projection's input. It is a value:
 * calculating the same evidence twice produces two equal instances, which is
 * what makes the targeted-versus-sweep equivalence property testable by
 * comparison rather than by inspecting side effects.
 *
 * @param kind channel or company
 * @param lane the calculated urgency
 * @param evidenceState what the answer rests on
 * @param confidence how much weight the rank should give it
 * @param cause why somebody is needed, or {@link RiskCause#NONE}
 * @param supply the supply that was proven, and everything refused
 * @param demand the demand decision and its windows
 * @param leadTime the resolved policy, or a blocked resolution
 * @param profit which profit authority spoke
 * @param daysOfCover proven supply divided by demand rate, or {@code null}
 * @param projectedStockoutAt when cover runs out, or {@code null}
 * @param proof the conservative argument, empty when none was established
 * @param blockerCodes stable codes for every defect this child is waiting on
 */
public record ChildRisk(
        ChildKind kind,
        AvailabilityLane lane,
        RiskEvidenceState evidenceState,
        RiskConfidence confidence,
        RiskCause cause,
        ProvenSupply supply,
        DemandDecision demand,
        LeadTimeResolution leadTime,
        ProfitAssessment profit,
        BigDecimal daysOfCover,
        Instant projectedStockoutAt,
        ConservativeProof proof,
        List<String> blockerCodes) {

    public ChildRisk {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(evidenceState, "evidenceState");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(supply, "supply");
        Objects.requireNonNull(demand, "demand");
        Objects.requireNonNull(profit, "profit");
        Objects.requireNonNull(proof, "proof");
        blockerCodes = List.copyOf(Objects.requireNonNull(blockerCodes, "blockerCodes"));

        // The two rules that must never be violated, checked where the value is
        // built rather than only where it is stored. A calculator bug should
        // fail loudly in a unit test, not quietly at an insert.
        if (kind == ChildKind.COMPANY && lane == AvailabilityLane.HEALTHY
                && !evidenceState.sufficientForSafety()) {
            throw new IllegalArgumentException(
                    "a company child cannot be healthy on " + evidenceState + " evidence");
        }
        if (evidenceState == RiskEvidenceState.PROVISIONAL && !proof.established()) {
            throw new IllegalArgumentException(
                    "a provisional risk must carry the proof that established it");
        }
    }

    /** Whether this child warrants an accountable case. */
    public boolean actionable() {
        return cause.actionable();
    }
}
