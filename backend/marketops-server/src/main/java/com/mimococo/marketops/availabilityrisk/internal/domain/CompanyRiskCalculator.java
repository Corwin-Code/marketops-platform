package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.ChildKind;
import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.RiskConfidence;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculates whether the company itself is about to run out of one variant.
 *
 * <p>This calculator fails closed, and the shape of the code follows the shape
 * of that rule. It first establishes what it can actually prove — units that are
 * owned, fresh, distinct from units already counted, and currently sellable —
 * and separately records every unit it had to refuse. Only then does it ask what
 * the proven lower bound implies.
 *
 * <p>The three outcomes when something material is missing are exhaustive:
 *
 * <ul>
 *   <li>the lower bound alone already runs out inside the horizon, so the danger
 *       is real whatever the refused units turn out to be — {@code PROVISIONAL}
 *       with the argument attached;</li>
 *   <li>the lower bound looks adequate, but the refused units could change that
 *       either way, so the missing fact decides the answer —
 *       {@code UNRESOLVED} / {@code DATA_BLOCKED};</li>
 *   <li>nothing material is missing — an ordinary confirmed answer.</li>
 * </ul>
 *
 * <p>What never happens is {@code HEALTHY} on incomplete evidence. That is
 * checked again in {@link ChildRisk}'s constructor and a third time by a
 * database constraint, because it is the single rule whose violation would make
 * the whole product dishonest.
 */
public final class CompanyRiskCalculator {

    private CompanyRiskCalculator() {
    }

    /**
     * Calculate the company child.
     *
     * @param observation everything known about the company's holding
     * @param demand the demand decision scoped to the company
     * @param leadTime the resolved policy, or a blocked resolution
     * @param profit which profit authority spoke
     * @param freshnessMaxMinutes how old an observation may be
     * @param asOf the calculation instant
     */
    public static ChildRisk calculate(CompanyObservation observation,
                                      DemandDecision demand,
                                      LeadTimeResolution leadTime,
                                      ProfitAssessment profit,
                                      long freshnessMaxMinutes,
                                      Instant asOf) {
        List<String> blockers = new ArrayList<>();

        // A blocked policy is decisive on its own: without a horizon there is no
        // question to answer, and inventing one would be inventing the answer.
        if (!leadTime.resolved()) {
            blockers.add("LEAD_TIME_POLICY_UNRESOLVED");
            return new ChildRisk(ChildKind.COMPANY, AvailabilityLane.REVIEW,
                    RiskEvidenceState.POLICY_BLOCKED, RiskConfidence.UNUSABLE,
                    RiskCause.LEAD_TIME_POLICY_MISSING, ProvenSupply.none(), demand,
                    leadTime, profit, null, null, ConservativeProof.none(),
                    List.copyOf(blockers));
        }

        Instant horizonEnd = asOf.plus(Duration.ofDays(leadTime.coverageHorizonDays()));
        List<SupplyComponent> components = new ArrayList<>();

        for (CompanyObservation.WarehouseHolding holding : observation.warehouseHoldings()) {
            if (!holding.freshAt(asOf, freshnessMaxMinutes)) {
                components.add(SupplyComponent.excluded(
                        SupplyComponent.Source.INTERNAL_WAREHOUSE, holding.quantityOnHand(),
                        SupplyComponent.ExclusionReason.STALE_OBSERVATION,
                        holding.provenanceId(), holding.observedAt()));
                continue;
            }
            int available = holding.available();
            int withheld = holding.quantityOnHand() - available;
            if (withheld > 0) {
                // Reserved and quality-locked units are known and correctly
                // excluded, so they are recorded without undermining the total.
                components.add(SupplyComponent.excluded(
                        SupplyComponent.Source.INTERNAL_WAREHOUSE, withheld,
                        holding.quantityReserved() != null && holding.quantityReserved() > 0
                                ? SupplyComponent.ExclusionReason.RESERVED
                                : SupplyComponent.ExclusionReason.NOT_SELLABLE,
                        holding.provenanceId(), holding.observedAt()));
            }
            components.add(SupplyComponent.counted(SupplyComponent.Source.INTERNAL_WAREHOUSE,
                    available, holding.provenanceId(), holding.observedAt()));
        }

        for (CompanyObservation.PlatformHolding holding : observation.platformHoldings()) {
            int units = holding.availableUnits() == null ? 0 : holding.availableUnits();
            if (holding.distinctness() == SupplyDistinctness.MIRRORS_INTERNAL) {
                // These units are the warehouse's own, already counted. Whether
                // the platform published a quantity for them, and how fresh it
                // is, changes nothing about company supply.
                components.add(SupplyComponent.excluded(SupplyComponent.Source.PLATFORM_VISIBLE,
                        units, SupplyComponent.ExclusionReason.MIRRORS_INTERNAL_STOCK,
                        holding.provenanceId(), holding.observedAt()));
                continue;
            }
            if (holding.availableUnits() == null) {
                components.add(SupplyComponent.excluded(SupplyComponent.Source.PLATFORM_VISIBLE,
                        0, SupplyComponent.ExclusionReason.QUANTITY_NOT_REPORTED,
                        holding.provenanceId(), holding.observedAt()));
                continue;
            }
            if (!holding.freshAt(asOf, freshnessMaxMinutes)) {
                components.add(SupplyComponent.excluded(SupplyComponent.Source.PLATFORM_VISIBLE,
                        units, SupplyComponent.ExclusionReason.STALE_OBSERVATION,
                        holding.provenanceId(), holding.observedAt()));
                continue;
            }
            switch (holding.distinctness()) {
                case MIRRORS_INTERNAL -> throw new IllegalStateException(
                        "a mirrored holding is handled before freshness");
                case PHYSICALLY_DISTINCT -> components.add(SupplyComponent.counted(
                        SupplyComponent.Source.PLATFORM_VISIBLE, units,
                        holding.provenanceId(), holding.observedAt()));
                case UNDECLARED -> components.add(SupplyComponent.excluded(
                        SupplyComponent.Source.PLATFORM_VISIBLE, units,
                        SupplyComponent.ExclusionReason.OWNERSHIP_NOT_DECLARED,
                        holding.provenanceId(), holding.observedAt()));
            }
        }

        for (InboundConsignment consignment : observation.inbound()) {
            if (consignment.eligibleAt(asOf, horizonEnd, freshnessMaxMinutes)) {
                components.add(SupplyComponent.counted(SupplyComponent.Source.ELIGIBLE_INBOUND,
                        consignment.quantity(), null, consignment.lastVerifiedAt()));
            } else {
                components.add(SupplyComponent.excluded(SupplyComponent.Source.ELIGIBLE_INBOUND,
                        consignment.quantity(),
                        consignment.exclusionAt(asOf, horizonEnd, freshnessMaxMinutes),
                        null, consignment.lastVerifiedAt()));
            }
        }

        ProvenSupply supply = ProvenSupply.of(components);
        boolean complete = supply.complete();
        if (!complete) {
            supply.excluded().stream()
                    .filter(SupplyComponent::underminesCompleteness)
                    .map(component -> "COMPANY_SUPPLY_" + component.reason().name())
                    .distinct()
                    .forEach(blockers::add);
        }
        if (!supply.present()) {
            blockers.add("COMPANY_SUPPLY_NOT_OBSERVED");
        }

        // Demand is decisive too. An unusable demand answer means the horizon
        // question has no arithmetic, and zero would answer it falsely.
        if (!demand.usable()) {
            blockers.add("COMPANY_DEMAND_" + demand.evidenceState().name());
            return new ChildRisk(ChildKind.COMPANY, AvailabilityLane.UNRESOLVED,
                    demand.evidenceState() == RiskEvidenceState.CONFLICTED
                            ? RiskEvidenceState.CONFLICTED : RiskEvidenceState.DATA_BLOCKED,
                    RiskConfidence.UNUSABLE, RiskCause.DEMAND_UNOBSERVABLE, supply, demand,
                    leadTime, profit, null, null, ConservativeProof.none(),
                    List.copyOf(blockers));
        }

        BigDecimal cover = ChannelRiskCalculator.coverDays(supply.provenUnits(), demand.selectedRate());
        AvailabilityLane lowerBoundLane = LaneThresholds.laneFor(cover, leadTime);
        Instant stockoutAt = LaneThresholds.stockoutAt(cover, asOf);

        if (complete && supply.present()) {
            RiskCause cause = lowerBoundLane == AvailabilityLane.HEALTHY
                    ? RiskCause.NONE : shortageCause(observation, asOf, horizonEnd, freshnessMaxMinutes);
            RiskEvidenceState evidence =
                    demand.evidenceState() == RiskEvidenceState.CARRIED_FORWARD
                            ? RiskEvidenceState.CARRIED_FORWARD : RiskEvidenceState.CONFIRMED;
            // Carried-forward demand is not sufficient for safety, so a healthy
            // answer built on it would be refused. Report it as unresolved
            // rather than pretending the shortfall in evidence does not exist.
            if (lowerBoundLane == AvailabilityLane.HEALTHY && !evidence.sufficientForSafety()) {
                blockers.add("COMPANY_DEMAND_CARRIED_FORWARD");
                return new ChildRisk(ChildKind.COMPANY, AvailabilityLane.UNRESOLVED,
                        evidence, RiskConfidence.LOW, RiskCause.DEMAND_UNOBSERVABLE, supply,
                        demand, leadTime, profit, cover, stockoutAt, ConservativeProof.none(),
                        List.copyOf(blockers));
            }
            return new ChildRisk(ChildKind.COMPANY, lowerBoundLane, evidence,
                    demand.confidence(), cause, supply, demand, leadTime, profit,
                    cover, stockoutAt, ConservativeProof.none(), List.copyOf(blockers));
        }

        // Something material could not be classified. Either the lower bound
        // already settles the question, or the missing fact does.
        if (lowerBoundLane != AvailabilityLane.HEALTHY && supply.present()) {
            ConservativeProof proof = proveDanger(supply, demand, leadTime, cover);
            return new ChildRisk(ChildKind.COMPANY, lowerBoundLane,
                    RiskEvidenceState.PROVISIONAL, RiskConfidence.LOW,
                    shortageCause(observation, asOf, horizonEnd, freshnessMaxMinutes),
                    supply, demand, leadTime, profit, cover, stockoutAt, proof,
                    List.copyOf(blockers));
        }
        return new ChildRisk(ChildKind.COMPANY, AvailabilityLane.UNRESOLVED,
                RiskEvidenceState.DATA_BLOCKED, RiskConfidence.UNUSABLE,
                dataCause(supply), supply, demand, leadTime, profit, cover, stockoutAt,
                ConservativeProof.none(), List.copyOf(blockers));
    }

    /**
     * Build the argument that the shortfall is already established.
     *
     * <p>Every term uses only counted supply. The refused units appear in the
     * proof as an explicit statement that they were refused and why, so a
     * reviewer can see that the conclusion does not depend on them.
     */
    private static ConservativeProof proveDanger(ProvenSupply supply, DemandDecision demand,
                                                 LeadTimeResolution leadTime, BigDecimal cover) {
        List<ProofTerm> terms = new ArrayList<>();
        terms.add(ProofTerm.of("PROVEN_UNITS",
                "units that are owned, fresh and proven distinct from stock already counted",
                BigDecimal.valueOf(supply.provenUnits())));
        terms.add(ProofTerm.of("SELECTED_DEMAND_RATE",
                "units per day from " + demand.selectedWindow() + ": " + demand.reason(),
                demand.selectedRate()));
        terms.add(ProofTerm.of("COVERAGE_HORIZON_DAYS",
                "lead time " + leadTime.leadTimeDaysMax() + " plus safety "
                        + leadTime.safetyDays(),
                BigDecimal.valueOf(leadTime.coverageHorizonDays())));
        if (cover != null) {
            terms.add(ProofTerm.of("PROVEN_DAYS_OF_COVER",
                    "the proven lower bound covers fewer days than the horizon requires",
                    cover));
        }
        for (SupplyComponent excluded : supply.excluded()) {
            if (excluded.underminesCompleteness()) {
                terms.add(ProofTerm.of("REFUSED_" + excluded.reason().name(),
                        "units observed but not counted, which can only reduce the shortfall,"
                                + " never create it",
                        BigDecimal.valueOf(excluded.units())));
            }
        }
        return ConservativeProof.of(terms);
    }

    /** Which shortage cause owns this, preferring the one somebody can act on. */
    private static RiskCause shortageCause(CompanyObservation observation, Instant asOf,
                                           Instant horizonEnd, long freshnessMaxMinutes) {
        boolean inboundLapsed = observation.inbound().stream()
                .anyMatch(consignment -> !consignment.eligibleAt(asOf, horizonEnd, freshnessMaxMinutes)
                        && switch (consignment.businessStatus()) {
                            case CANCELLED, OVERDUE, CONFLICTED, UNKNOWN -> true;
                            default -> false;
                        });
        return inboundLapsed ? RiskCause.COMPANY_INBOUND_LAPSED : RiskCause.COMPANY_SUPPLY_SHORT;
    }

    /** Which data defect is blocking, preferring the most specific. */
    private static RiskCause dataCause(ProvenSupply supply) {
        boolean undeclared = supply.excluded().stream().anyMatch(component ->
                component.reason() == SupplyComponent.ExclusionReason.OWNERSHIP_NOT_DECLARED);
        return undeclared ? RiskCause.OWNERSHIP_UNDECLARED : RiskCause.STOCK_DATA_DEFECT;
    }
}
