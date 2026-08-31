package com.mimococo.marketops.availabilityrisk.internal.domain;

import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.FRESHNESS_MINUTES;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.NOW;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.VARIANT;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.blockedDemand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.blockedLeadTime;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.carriedForwardDemand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.demand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.inbound;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.leadTime;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.platform;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.profit;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.warehouse;
import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompanyRiskCalculatorTest {

    private static final Instant FRESH = NOW.minus(Duration.ofMinutes(10));

    @Test
    @DisplayName("TC-COMPANY-001 a complete picture with enough cover is healthy")
    void completeAndCoveredIsHealthy() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(400, 0, 0, FRESH)), List.of(), List.of()), demand("10"));

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.HEALTHY);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.CONFIRMED);
        assertThat(risk.supply().provenUnits()).isEqualTo(400);
        assertThat(risk.supply().complete()).isTrue();
    }

    @Test
    @DisplayName("TC-COMPANY-002 mirrored platform stock is never added to internal stock")
    void mirroredPlatformStockIsNotDoubleCounted() {
        // The same 120 units appear in the warehouse and in the seller-fulfilled
        // view. Adding them would report 240 and clear a real shortage.
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(120, 0, 0, FRESH)),
                List.of(platform(120, SupplyDistinctness.MIRRORS_INTERNAL, FRESH)),
                List.of()), demand("10"));

        assertThat(risk.supply().provenUnits()).isEqualTo(120);
        assertThat(risk.supply().complete()).isTrue();
        assertThat(risk.lane()).isEqualTo(AvailabilityLane.HIGH);
        assertThat(risk.supply().excluded())
                .anyMatch(component -> component.reason()
                        == SupplyComponent.ExclusionReason.MIRRORS_INTERNAL_STOCK);
    }

    @Test
    @DisplayName("TC-COMPANY-003 physically distinct company-owned platform stock does count")
    void distinctPlatformStockCounts() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(120, 0, 0, FRESH)),
                List.of(platform(180, SupplyDistinctness.PHYSICALLY_DISTINCT, FRESH)),
                List.of()), demand("10"));

        assertThat(risk.supply().provenUnits()).isEqualTo(300);
        assertThat(risk.lane()).isEqualTo(AvailabilityLane.HEALTHY);
    }

    @Test
    @DisplayName("TC-COMPANY-004 undeclared ownership can never produce a healthy company answer")
    void undeclaredOwnershipCannotBeHealthy() {
        // The proven bound alone covers the horizon, so there is no danger proof
        // to make. But 400 unclassified units mean the answer is not knowable.
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(400, 0, 0, FRESH)),
                List.of(platform(400, SupplyDistinctness.UNDECLARED, FRESH)),
                List.of()), demand("10"));

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.UNRESOLVED);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.DATA_BLOCKED);
        assertThat(risk.cause()).isEqualTo(RiskCause.OWNERSHIP_UNDECLARED);
        assertThat(risk.blockerCodes()).contains("COMPANY_SUPPLY_OWNERSHIP_NOT_DECLARED");
    }

    @Test
    @DisplayName("TC-COMPANY-005 a proven lower bound that already runs out is provisional with a proof")
    void provenShortfallIsProvisionalWithProof() {
        // 30 owned units against 10 a day is three days of cover against a
        // 21-day horizon. The 400 undeclared units could only improve that, so
        // the danger is established regardless of what they turn out to be.
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(30, 0, 0, FRESH)),
                List.of(platform(400, SupplyDistinctness.UNDECLARED, FRESH)),
                List.of()), demand("10"));

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.PROVISIONAL);
        assertThat(risk.cause()).isEqualTo(RiskCause.COMPANY_SUPPLY_SHORT);
        assertThat(risk.proof().established()).isTrue();
        assertThat(risk.proof().terms())
                .extracting(ProofTerm::code)
                .contains("PROVEN_UNITS", "SELECTED_DEMAND_RATE", "COVERAGE_HORIZON_DAYS",
                        "REFUSED_OWNERSHIP_NOT_DECLARED");
    }

    @Test
    @DisplayName("TC-COMPANY-006 reserved and quality-locked units are excluded without blocking")
    void reservedUnitsAreExcludedButDoNotBlock() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(400, 250, 50, FRESH)), List.of(), List.of()), demand("10"));

        assertThat(risk.supply().provenUnits()).isEqualTo(100);
        assertThat(risk.supply().complete()).isTrue();
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.CONFIRMED);
        assertThat(risk.lane()).isEqualTo(AvailabilityLane.HIGH);
    }

    @Test
    @DisplayName("TC-COMPANY-007 confirmed inbound inside the horizon reduces risk")
    void eligibleInboundReducesRisk() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(50, 0, 0, FRESH)), List.of(),
                List.of(inbound(300, NOW.plus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(9)),
                        InboundConsignment.Status.SUPPLIER_CONFIRMED, FRESH))),
                demand("10"));

        assertThat(risk.supply().provenUnits()).isEqualTo(350);
        assertThat(risk.lane()).isEqualTo(AvailabilityLane.HEALTHY);
    }

    @Test
    @DisplayName("TC-COMPANY-008 a draft inbound is visible and reduces nothing")
    void draftInboundDoesNotReduceRisk() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(50, 0, 0, FRESH)), List.of(),
                List.of(inbound(300, NOW.plus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(9)),
                        InboundConsignment.Status.DRAFT, FRESH))),
                demand("10"));

        assertThat(risk.supply().provenUnits()).isEqualTo(50);
        assertThat(risk.lane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(risk.supply().excluded())
                .anyMatch(component -> component.reason()
                        == SupplyComponent.ExclusionReason.INELIGIBLE_STATUS);
    }

    @Test
    @DisplayName("TC-COMPANY-009 inbound arriving after the horizon is not current cover")
    void inboundBeyondHorizonDoesNotCount() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(50, 0, 0, FRESH)), List.of(),
                List.of(inbound(300, NOW.plus(Duration.ofDays(40)), NOW.plus(Duration.ofDays(45)),
                        InboundConsignment.Status.IN_TRANSIT, FRESH))),
                demand("10"));

        assertThat(risk.supply().provenUnits()).isEqualTo(50);
        assertThat(risk.lane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(risk.supply().excluded())
                .anyMatch(component -> component.reason()
                        == SupplyComponent.ExclusionReason.OUTSIDE_HORIZON);
    }

    @Test
    @DisplayName("TC-COMPANY-010 a cancelled inbound routes to the inbound owner, not to restock")
    void cancelledInboundRoutesToInboundCause() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(50, 0, 0, FRESH)), List.of(),
                List.of(inbound(300, NOW.plus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(9)),
                        InboundConsignment.Status.CANCELLED, FRESH))),
                demand("10"));

        assertThat(risk.cause()).isEqualTo(RiskCause.COMPANY_INBOUND_LAPSED);
    }

    @Test
    @DisplayName("TC-COMPANY-011 a missing lead-time policy blocks and never defaults to zero")
    void missingPolicyBlocks() {
        ChildRisk risk = CompanyRiskCalculator.calculate(
                new CompanyObservation(VARIANT, List.of(warehouse(400, 0, 0, FRESH)),
                        List.of(), List.of()),
                demand("10"), blockedLeadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.REVIEW);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.POLICY_BLOCKED);
        assertThat(risk.cause()).isEqualTo(RiskCause.LEAD_TIME_POLICY_MISSING);
    }

    @Test
    @DisplayName("TC-COMPANY-012 unusable demand leaves the company answer unresolved, never healthy")
    void unusableDemandIsUnresolved() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(400, 0, 0, FRESH)), List.of(), List.of()), blockedDemand());

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.UNRESOLVED);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.DATA_BLOCKED);
        assertThat(risk.cause()).isEqualTo(RiskCause.DEMAND_UNOBSERVABLE);
    }

    @Test
    @DisplayName("TC-COMPANY-013 a stale warehouse observation is not current supply")
    void staleWarehouseIsNotSupply() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(400, 0, 0, NOW.minus(Duration.ofDays(3)))),
                List.of(), List.of()), demand("10"));

        assertThat(risk.supply().provenUnits()).isZero();
        assertThat(risk.evidenceState()).isNotEqualTo(RiskEvidenceState.CONFIRMED);
        assertThat(risk.lane()).isNotEqualTo(AvailabilityLane.HEALTHY);
    }

    @Test
    @DisplayName("TC-COMPANY-014 carried-forward demand cannot produce a healthy company answer")
    void carriedForwardDemandCannotBeHealthy() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT,
                List.of(warehouse(4000, 0, 0, FRESH)), List.of(), List.of()),
                carriedForwardDemand("10"));

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.UNRESOLVED);
        assertThat(risk.blockerCodes()).contains("COMPANY_DEMAND_CARRIED_FORWARD");
    }

    @Test
    @DisplayName("TC-COMPANY-015 observing nothing at all is unresolved, never zero-supply healthy")
    void nothingObservedIsUnresolved() {
        ChildRisk risk = calculate(new CompanyObservation(VARIANT, List.of(), List.of(), List.of()),
                demand("10"));

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.UNRESOLVED);
        assertThat(risk.blockerCodes()).contains("COMPANY_SUPPLY_NOT_OBSERVED");
    }

    private ChildRisk calculate(CompanyObservation observation, DemandDecision demand) {
        return CompanyRiskCalculator.calculate(observation, demand, leadTime(), profit(),
                FRESHNESS_MINUTES, NOW);
    }
}
