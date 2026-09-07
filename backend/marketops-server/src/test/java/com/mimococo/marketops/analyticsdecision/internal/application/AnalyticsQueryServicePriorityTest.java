package com.mimococo.marketops.analyticsdecision.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosisRepository;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.MetricRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsQueryServicePriorityTest {
    private final MetricRepository metrics = mock(MetricRepository.class);
    private final DiagnosisRepository diagnoses = mock(DiagnosisRepository.class);
    private final AnalyticsQueryService service = new AnalyticsQueryService(metrics, diagnoses);
    private final UUID store = UUID.randomUUID();
    private static final Set<MetricCode> CODES = Set.of(MetricCode.COMPLETED_NET_SALES,
            MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT);

    @Test void pageKeepsDiagnosisOrderAndExactScoresAmountsUnknownsAndBlockingRules() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), missing = UUID.randomUUID();
        var subjects = List.of(first, second, missing);
        when(diagnoses.priorityQueue(store, MetricWindow.D14, 500)).thenReturn(List.of(
                new DiagnosisRepository.PriorityRow(first, 2, 1, 1, List.of("BLOCK_A")),
                new DiagnosisRepository.PriorityRow(second, 0, 1, 0, List.of()),
                new DiagnosisRepository.PriorityRow(missing, 0, 0, 2, List.of())));
        when(metrics.currentValuesForSubjects(SubjectKind.PLATFORM_LISTING_VARIANT,
                subjects, MetricWindow.D14, CODES)).thenReturn(Map.of(
                second, Map.of(MetricCode.COMPLETED_NET_SALES, value(second, MetricCode.COMPLETED_NET_SALES,
                        null, "USD"), MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                        value(second, MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT, "0", "USD")),
                first, Map.of(MetricCode.COMPLETED_NET_SALES, value(first, MetricCode.COMPLETED_NET_SALES,
                        "99", "RUB"), MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                        value(first, MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT, "-10", "RUB"))));

        var queue = service.priorityQueue(store, MetricWindow.D14, 900);
        assertThat(queue).extracting(row -> row.subjectId()).containsExactly(first, second, missing);
        assertThat(queue).allSatisfy(row -> {
            assertThat(row.storeId()).isEqualTo(store);
            assertThat(row.subjectKind()).isEqualTo(SubjectKind.PLATFORM_LISTING_VARIANT);
        });
        assertThat(queue.get(0).priorityScore()).isEqualByComparingTo("237");
        assertThat(queue.get(0).netSales()).isEqualByComparingTo("99");
        assertThat(queue.get(0).contributionProfit()).isEqualByComparingTo("-10");
        assertThat(queue.get(0).currencyCode()).isEqualTo("RUB");
        assertThat(queue.get(0).blockingRuleCodes()).containsExactly("BLOCK_A");
        assertThat(queue.get(1).priorityScore()).isEqualByComparingTo("25");
        assertThat(queue.get(1).netSales()).isNull();
        assertThat(queue.get(1).contributionProfit()).isEqualByComparingTo("0");
        assertThat(queue.get(1).currencyCode()).isEqualTo("USD");
        assertThat(queue.get(2).priorityScore()).isEqualByComparingTo("20");
        assertThat(queue.get(2).netSales()).isNull();
        assertThat(queue.get(2).contributionProfit()).isNull();
        assertThat(queue.get(2).currencyCode()).isNull();
        verify(metrics).currentValuesForSubjects(SubjectKind.PLATFORM_LISTING_VARIANT,
                subjects, MetricWindow.D14, CODES);
        verifyNoMoreInteractions(metrics);
    }

    @Test void moneyTermKeepsItsCeilingAndNeverRewardsNegativeSales() {
        UUID large = UUID.randomUUID(), negative = UUID.randomUUID();
        when(diagnoses.priorityQueue(store, MetricWindow.D30, 2)).thenReturn(List.of(
                new DiagnosisRepository.PriorityRow(large, 1, 0, 0, List.of()),
                new DiagnosisRepository.PriorityRow(negative, 1, 0, 0, List.of())));
        when(metrics.currentValuesForSubjects(SubjectKind.PLATFORM_LISTING_VARIANT,
                List.of(large, negative), MetricWindow.D30, CODES)).thenReturn(Map.of(
                large, Map.of(MetricCode.COMPLETED_NET_SALES,
                        value(large, MetricCode.COMPLETED_NET_SALES, "1E60", "RUB")),
                negative, Map.of(MetricCode.COMPLETED_NET_SALES,
                        value(negative, MetricCode.COMPLETED_NET_SALES, "-1", "RUB"))));
        var queue = service.priorityQueue(store, MetricWindow.D30, 2);
        assertThat(queue.get(0).priorityScore()).isEqualByComparingTo("150");
        assertThat(queue.get(1).priorityScore()).isEqualByComparingTo("100");
        assertThat(queue.get(1).netSales()).isEqualByComparingTo("-1");
    }

    @Test void emptyAuthorizedPageHasNoSubjectsToExpandAndKeepsLowerLimitBound() {
        when(diagnoses.priorityQueue(store, MetricWindow.D7, 1)).thenReturn(List.of());
        when(metrics.currentValuesForSubjects(SubjectKind.PLATFORM_LISTING_VARIANT,
                List.of(), MetricWindow.D7, CODES)).thenReturn(Map.of());
        assertThat(service.priorityQueue(store, MetricWindow.D7, 0)).isEmpty();
        verify(diagnoses).priorityQueue(store, MetricWindow.D7, 1);
        verify(metrics).currentValuesForSubjects(SubjectKind.PLATFORM_LISTING_VARIANT,
                List.of(), MetricWindow.D7, CODES);
        verifyNoMoreInteractions(metrics, diagnoses);
    }

    private static MetricValueView value(UUID subject, MetricCode code, String amount, String currency) {
        Instant at = Instant.parse("2026-09-06T00:00:00Z");
        return new MetricValueView(UUID.randomUUID(), code, 1, SubjectKind.PLATFORM_LISTING_VARIANT,
                subject, MetricWindow.D14, at.minusSeconds(14 * 86400L), at,
                amount == null ? ValueState.NOT_AVAILABLE : ValueState.AVAILABLE,
                amount == null ? null : new BigDecimal(amount), currency,
                ConfidenceState.CANONICAL_CONFIRMED, false, at, 0L, "synthetic", at, List.of());
    }
}
