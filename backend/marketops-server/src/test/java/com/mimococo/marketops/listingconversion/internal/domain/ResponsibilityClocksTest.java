package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Clocks start when a case is first raised; only a qualified hold pauses the action clock. */
class ResponsibilityClocksTest {

    private static final Instant RAISED = Instant.parse("2026-09-01T09:00:00Z");

    @Test
    @DisplayName("TC-LC-R01 deadlines are absolute instants from the first raise")
    void deadlinesFromFirstRaise() {
        var deadlines = ResponsibilityClocks.deadlines(RAISED, Duration.ofHours(4), Duration.ofDays(2),
                Duration.ofDays(30), Duration.ofHours(6));

        assertThat(deadlines.acknowledgementDue()).isEqualTo(RAISED.plus(Duration.ofHours(4)));
        assertThat(deadlines.actionDue()).isEqualTo(RAISED.plus(Duration.ofDays(2)).plus(Duration.ofHours(6)));
        assertThat(deadlines.outcomeMaturityDue()).isEqualTo(RAISED.plus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("TC-LC-R02 a negative or absent hold never shortens a clock")
    void negativeHoldIsIgnored() {
        var deadlines = ResponsibilityClocks.deadlines(RAISED, Duration.ofHours(1), Duration.ofHours(8),
                Duration.ofDays(1), Duration.ofHours(-5));

        assertThat(deadlines.actionDue()).isEqualTo(RAISED.plus(Duration.ofHours(8)));
        assertThat(ResponsibilityClocks.actionElapsedSeconds(RAISED, RAISED.plusSeconds(100), null)).isEqualTo(100);
        assertThat(ResponsibilityClocks.actionElapsedSeconds(RAISED, RAISED.plusSeconds(100),
                Duration.ofSeconds(150))).isZero();
        assertThat(ResponsibilityClocks.actionElapsedSeconds(RAISED, RAISED.plusSeconds(100),
                Duration.ofSeconds(40))).isEqualTo(60);
    }
}
