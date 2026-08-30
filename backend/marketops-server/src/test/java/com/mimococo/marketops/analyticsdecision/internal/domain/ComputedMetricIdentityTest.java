package com.mimococo.marketops.analyticsdecision.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.ValueState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ComputedMetricIdentityTest {

    @Test
    void equivalentRerunsDeduplicateButStalenessAndStateTransitionsDoNot() {
        UUID input = UUID.randomUUID();
        ComputedMetric confirmed = metric(input, ValueState.AVAILABLE,
                ConfidenceState.CANONICAL_CONFIRMED, new BigDecimal("10.0000"));
        ComputedMetric equivalent = metric(input, ValueState.AVAILABLE,
                ConfidenceState.CANONICAL_CONFIRMED, new BigDecimal("10.0"));
        ComputedMetric stale = metric(input, ValueState.AVAILABLE,
                ConfidenceState.STALE, new BigDecimal("10.0000"));
        ComputedMetric unavailable = metric(input, ValueState.NOT_AVAILABLE,
                ConfidenceState.INCOMPLETE, null);

        assertThat(digest(confirmed)).isEqualTo(digest(equivalent));
        assertThat(digest(stale)).isNotEqualTo(digest(confirmed));
        assertThat(digest(unavailable)).isNotEqualTo(digest(confirmed));
    }

    private static ComputedMetric metric(UUID input, ValueState state,
                                         ConfidenceState confidence, BigDecimal amount) {
        return new ComputedMetric(MetricCode.UNIT_COST, state, amount,
                amount == null ? null : "RUB", confidence,
                Instant.parse("2026-08-30T04:00:00Z"),
                List.of(MetricInput.costVersion(input)));
    }

    private static String digest(ComputedMetric metric) {
        return metric.inputDigest(2, "PLATFORM_LISTING_VARIANT", UUID.fromString(
                        "11111111-1111-4111-8111-111111111111"), "D30",
                Instant.parse("2026-07-31T05:00:00Z"),
                Instant.parse("2026-08-30T05:00:00Z"));
    }
}
