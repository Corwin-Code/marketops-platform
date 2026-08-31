package com.mimococo.marketops.availabilityrisk.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.availabilityrisk.ProfitLane;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ProfitLaneResolverTest {

    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    @DisplayName("TC-PROFIT-LANE-001 fresh positive Settled is confirmed eligibility")
    void freshPositiveSettledIsConfirmed() {
        MetricQuery metrics = Mockito.mock(MetricQuery.class);
        when(metrics.current(MetricCode.SETTLED_CONTRIBUTION_PROFIT,
                SubjectKind.PLATFORM_LISTING_VARIANT, SUBJECT, MetricWindow.D30))
                .thenReturn(Optional.of(metric(MetricCode.SETTLED_CONTRIBUTION_PROFIT, "12")));

        var answer = new ProfitLaneResolver(metrics).resolve(SUBJECT, NOW);

        assertThat(answer.lane()).isEqualTo(ProfitLane.CONFIRMED_ELIGIBLE);
        assertThat(answer.perUnitAmount()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("TC-PROFIT-LANE-002 Operational is eligible only when Settled is unavailable")
    void operationalIsTheExplicitFallback() {
        MetricQuery metrics = Mockito.mock(MetricQuery.class);
        when(metrics.current(MetricCode.SETTLED_CONTRIBUTION_PROFIT,
                SubjectKind.PLATFORM_LISTING_VARIANT, SUBJECT, MetricWindow.D30))
                .thenReturn(Optional.empty());
        when(metrics.current(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                SubjectKind.PLATFORM_LISTING_VARIANT, SUBJECT, MetricWindow.D30))
                .thenReturn(Optional.of(metric(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT, "9")));

        var answer = new ProfitLaneResolver(metrics).resolve(SUBJECT, NOW);

        assertThat(answer.lane()).isEqualTo(ProfitLane.OPERATIONAL_ELIGIBLE);
        assertThat(answer.perUnitAmount()).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("TC-PROFIT-LANE-003 an explicit estimate remains provisional")
    void estimateNeverBecomesConfirmed() {
        MetricQuery metrics = Mockito.mock(MetricQuery.class);
        when(metrics.current(MetricCode.SETTLED_CONTRIBUTION_PROFIT,
                SubjectKind.PLATFORM_LISTING_VARIANT, SUBJECT, MetricWindow.D30))
                .thenReturn(Optional.of(metric(MetricCode.SETTLED_CONTRIBUTION_PROFIT, "7",
                        ConfidenceState.ESTIMATED_EXPLAINED, true, NOW.minusSeconds(3600))));

        var answer = new ProfitLaneResolver(metrics).resolve(SUBJECT, NOW);

        assertThat(answer.lane()).isEqualTo(ProfitLane.PROVISIONAL);
    }

    @Test
    @DisplayName("TC-PROFIT-LANE-004 stale Settled fails closed without weaker fallback")
    void staleSettledIsDecisivelyBlocked() {
        MetricQuery metrics = Mockito.mock(MetricQuery.class);
        when(metrics.current(MetricCode.SETTLED_CONTRIBUTION_PROFIT,
                SubjectKind.PLATFORM_LISTING_VARIANT, SUBJECT, MetricWindow.D30))
                .thenReturn(Optional.of(metric(MetricCode.SETTLED_CONTRIBUTION_PROFIT, "11",
                        ConfidenceState.CANONICAL_CONFIRMED, false,
                        NOW.minus(Duration.ofDays(3)))));
        when(metrics.current(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                SubjectKind.PLATFORM_LISTING_VARIANT, SUBJECT, MetricWindow.D30))
                .thenReturn(Optional.of(metric(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT, "25")));

        var answer = new ProfitLaneResolver(metrics).resolve(SUBJECT, NOW);

        assertThat(answer.lane()).isEqualTo(ProfitLane.PROFIT_DATA_BLOCKED);
    }

    @Test
    @DisplayName("TC-PROFIT-LANE-005 negative Settled is decisive over positive Operational")
    void settledLossCannotFallThroughToOperationalProfit() {
        MetricQuery metrics = Mockito.mock(MetricQuery.class);
        when(metrics.current(MetricCode.SETTLED_CONTRIBUTION_PROFIT,
                SubjectKind.PLATFORM_LISTING_VARIANT, SUBJECT, MetricWindow.D30))
                .thenReturn(Optional.of(metric(MetricCode.SETTLED_CONTRIBUTION_PROFIT, "-5")));
        when(metrics.current(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                SubjectKind.PLATFORM_LISTING_VARIANT, SUBJECT, MetricWindow.D30))
                .thenReturn(Optional.of(metric(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT, "25")));

        var answer = new ProfitLaneResolver(metrics).resolve(SUBJECT, NOW);

        assertThat(answer.lane()).isEqualTo(ProfitLane.NOT_PROFITABLE);
        assertThat(answer.perUnitAmount()).isEqualByComparingTo("-5");
        assertThat(answer.lane().eligibleForPrimaryQueue()).isFalse();
    }

    private static MetricValueView metric(MetricCode code, String value) {
        return metric(code, value, ConfidenceState.CANONICAL_CONFIRMED, false,
                NOW.minus(Duration.ofHours(1)));
    }

    private static MetricValueView metric(MetricCode code, String value,
                                          ConfidenceState confidence, boolean estimated,
                                          Instant oldestSourceTime) {
        return new MetricValueView(UUID.randomUUID(), code, 1,
                SubjectKind.PLATFORM_LISTING_VARIANT, SUBJECT, MetricWindow.D30,
                NOW.minus(Duration.ofDays(30)), NOW, ValueState.AVAILABLE,
                new BigDecimal(value), "RUB", confidence, estimated,
                oldestSourceTime, 3600L, "a".repeat(64), NOW, List.of());
    }
}
