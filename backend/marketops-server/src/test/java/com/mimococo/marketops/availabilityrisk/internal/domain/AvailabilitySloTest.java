package com.mimococo.marketops.availabilityrisk.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvailabilitySloTest {

    @Test
    @DisplayName("TC-SLO-001 the hard bound is what one recalculation may not exceed")
    void theHardBoundGovernsOneObservation() {
        assertThat(AvailabilitySlo.breached(Duration.ofMinutes(14))).isFalse();
        assertThat(AvailabilitySlo.breached(Duration.ofMinutes(15))).isFalse();
        assertThat(AvailabilitySlo.breached(Duration.ofMinutes(15).plusMillis(1))).isTrue();
    }

    @Test
    @DisplayName("TC-SLO-002 only the critical lane carries a distribution target")
    void onlyCriticalCarriesADistributionTarget() {
        Duration nineMinutes = Duration.ofMinutes(9);

        assertThat(AvailabilitySlo.distributionTargetMet(AvailabilityLane.CRITICAL, nineMinutes))
                .isFalse();
        assertThat(AvailabilitySlo.distributionTargetMet(AvailabilityLane.HIGH, nineMinutes))
                .as("a high answer inside the hard bound is not an incident")
                .isTrue();
        assertThat(AvailabilitySlo.distributionTargetMet(AvailabilityLane.CRITICAL,
                Duration.ofMinutes(5))).isTrue();
    }

    @Test
    @DisplayName("TC-SLO-003 the two bounds are distinct and ordered")
    void theTwoBoundsAreDistinct() {
        assertThat(AvailabilitySlo.CRITICAL_DISTRIBUTION_TARGET)
                .isLessThan(AvailabilitySlo.HARD_BOUND);
        assertThat(AvailabilitySlo.TARGET_PERCENTILE).isEqualTo(95);
    }
}
