package com.mimococo.marketops.availabilityrisk.internal.domain;

import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A future source cannot create present supply or remove a present shortage. */
class SupplyEvidenceChronologyTest {
    @ParameterizedTest
    @ValueSource(longs = {1, 86_400_000_000_000L})
    void futureStockCannotCreateHealthyChannelOrCompany(long futureNanos) {
        Instant future = NOW.plusNanos(futureNanos);
        ChildRisk channel = ChannelRiskCalculator.calculate(channel(400, future, Sellability.SELLABLE),
                demand("10"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);
        ChildRisk warehouse = company(List.of(warehouse(400, 0, 0, future)), List.of(), List.of());
        ChildRisk platform = company(List.of(),
                List.of(platform(400, SupplyDistinctness.PHYSICALLY_DISTINCT, future)), List.of());
        for (ChildRisk risk : List.of(channel, warehouse, platform)) {
            assertThat(risk.lane()).isNotEqualTo(AvailabilityLane.HEALTHY);
            assertThat(risk.supply().provenUnits()).isZero();
        }
        assertThat(warehouse.supply().complete()).isFalse();
        assertThat(platform.supply().complete()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 86_400_000_000_000L})
    void futureInboundVerificationCannotErasePreexistingShortage(long futureNanos) {
        ChildRisk risk = company(List.of(warehouse(100, 0, 0, NOW)), List.of(),
                List.of(inbound(300, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(4)),
                        InboundConsignment.Status.IN_TRANSIT, NOW.plusNanos(futureNanos))));
        assertThat(risk.lane()).isNotEqualTo(AvailabilityLane.HEALTHY);
        assertThat(risk.projectedStockoutAt()).isEqualTo(NOW.plus(Duration.ofDays(10)));
        assertThat(risk.supply().excluded()).noneMatch(component ->
                component.reason() == SupplyComponent.ExclusionReason.SCHEDULED_FUTURE);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void absentOrInvalidFreshnessBoundNeverQualifiesEvenAnExactCurrentObservation(long bound) {
        assertFreshness(NOW, bound, false);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void inclusiveFreshnessBoundaryDoesNotRoundAwayNanosecondExpiry(long overdueNanos) {
        Instant source = NOW.minus(Duration.ofMinutes(FRESHNESS_MINUTES)).minusNanos(overdueNanos);
        assertFreshness(source, FRESHNESS_MINUTES, overdueNanos == 0);
    }

    private void assertFreshness(Instant source, long bound, boolean expected) {
        assertThat(channel(400, source, Sellability.SELLABLE).freshAt(NOW, bound)).isEqualTo(expected);
        assertThat(warehouse(400, 0, 0, source).freshAt(NOW, bound)).isEqualTo(expected);
        assertThat(platform(400, SupplyDistinctness.PHYSICALLY_DISTINCT, source).freshAt(NOW, bound))
                .isEqualTo(expected);
        assertThat(inbound(300, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(4)),
                InboundConsignment.Status.IN_TRANSIT, source)
                .eligibleAt(NOW, NOW.plus(Duration.ofDays(21)), bound)).isEqualTo(expected);
    }

    private ChildRisk company(List<CompanyObservation.WarehouseHolding> warehouses,
                              List<CompanyObservation.PlatformHolding> platforms,
                              List<InboundConsignment> consignments) {
        return CompanyRiskCalculator.calculate(new CompanyObservation(VARIANT, warehouses, platforms, consignments),
                demand("10"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);
    }
}
