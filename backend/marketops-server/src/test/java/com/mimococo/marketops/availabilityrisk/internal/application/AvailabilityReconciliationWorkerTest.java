package com.mimococo.marketops.availabilityrisk.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.InboundAttestationRepository;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionGovernance;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AvailabilityReconciliationWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    @Mock AvailabilityRecalculationRepository queue;
    @Mock AvailabilityRiskRefreshService refresh;
    @Mock AvailabilityExceptionGovernance exceptions;
    @Mock InboundAttestationRepository inbound;
    @Mock IdGenerator ids;

    private AvailabilityReconciliationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AvailabilityReconciliationWorker(queue, refresh, exceptions, inbound, ids,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(ids.newId()).thenReturn(RUN);
        when(exceptions.expireDue(ORGANIZATION, NOW)).thenReturn(List.of());
        when(exceptions.revalidateActive(ORGANIZATION, NOW)).thenReturn(List.of());
        when(queue.repairCoveredRequests(eq(ORGANIZATION), anyList(), eq(NOW))).thenReturn(0);
    }

    @Test
    @DisplayName("TC-RECON-001 one failed variant does not prevent later variants from refreshing")
    void perVariantFailureIsIsolatedAndReported() {
        UUID failed = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID healthy = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(queue.variantsToReconcile(ORGANIZATION, NOW, null, 1_000))
                .thenReturn(List.of(failed, healthy));
        when(queue.variantsToReconcile(ORGANIZATION, NOW, healthy, 1_000))
                .thenReturn(List.of());
        when(refresh.refresh(ORGANIZATION, failed, NOW,
                AvailabilityRiskRefreshService.RECONCILIATION, RUN))
                .thenThrow(new IllegalStateException("synthetic item failure"));
        when(refresh.refresh(ORGANIZATION, healthy, NOW,
                AvailabilityRiskRefreshService.RECONCILIATION, RUN))
                .thenReturn(outcome(AvailabilityLane.HEALTHY));

        var result = worker.sweep(ORGANIZATION, "SCHEDULED").orElseThrow();

        assertThat(result.completed()).isFalse();
        assertThat(result.variantCount()).isEqualTo(2);
        assertThat(result.failedVariantCount()).isEqualTo(1);
        verify(queue).repairCoveredRequests(ORGANIZATION, List.of(healthy), NOW);
        ArgumentCaptor<AvailabilityRecalculationRepository.RunOutcome> run =
                ArgumentCaptor.forClass(AvailabilityRecalculationRepository.RunOutcome.class);
        verify(queue).finishRun(run.capture());
        assertThat(run.getValue().state()).isEqualTo("FAILED");
        assertThat(run.getValue().failedVariantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-RECON-002 a portfolio larger than one database page is fully traversed")
    void portfolioIsFullyPaged() {
        List<UUID> first = new ArrayList<>();
        for (int index = 1; index <= 1_000; index++) {
            first.add(new UUID(0L, index));
        }
        UUID last = new UUID(0L, 1_001);
        when(queue.variantsToReconcile(ORGANIZATION, NOW, null, 1_000)).thenReturn(first);
        when(queue.variantsToReconcile(ORGANIZATION, NOW, first.getLast(), 1_000))
                .thenReturn(List.of(last));
        when(queue.variantsToReconcile(ORGANIZATION, NOW, last, 1_000)).thenReturn(List.of());
        when(refresh.refresh(eq(ORGANIZATION), any(), eq(NOW),
                eq(AvailabilityRiskRefreshService.RECONCILIATION), eq(RUN)))
                .thenReturn(outcome(AvailabilityLane.HEALTHY));

        var result = worker.sweep(ORGANIZATION, "SCHEDULED").orElseThrow();

        assertThat(result.completed()).isTrue();
        assertThat(result.variantCount()).isEqualTo(1_001);
        verify(queue, atLeast(2)).recordRunProgress(eq(RUN), any(), anyInt(), anyInt(), eq(0));
    }

    @Test
    @DisplayName("TC-RECON-003 the declared 5000-variant profile retains hourly sweep margin")
    void declaredCapacityRetainsHourlyMargin() {
        List<UUID> portfolio = new ArrayList<>();
        for (int index = 1; index <= 5_000; index++) {
            portfolio.add(new UUID(0L, index));
        }
        for (int page = 0; page < 5; page++) {
            int start = page * 1_000;
            UUID after = page == 0 ? null : portfolio.get(start - 1);
            when(queue.variantsToReconcile(ORGANIZATION, NOW, after, 1_000))
                    .thenReturn(portfolio.subList(start, start + 1_000));
        }
        when(queue.variantsToReconcile(ORGANIZATION, NOW, portfolio.getLast(), 1_000))
                .thenReturn(List.of());
        when(refresh.refresh(eq(ORGANIZATION), any(), eq(NOW),
                eq(AvailabilityRiskRefreshService.RECONCILIATION), eq(RUN)))
                .thenReturn(outcome(AvailabilityLane.HEALTHY));

        AvailabilityReconciliationWorker.SweepResult result = assertTimeout(
                Duration.ofSeconds(30), () -> worker.sweep(ORGANIZATION, "SCHEDULED").orElseThrow());

        assertThat(result.completed()).isTrue();
        assertThat(result.variantCount()).isEqualTo(5_000);
        assertThat(result.failedVariantCount()).isZero();
    }

    private static AvailabilityRiskRefreshService.RefreshOutcome outcome(AvailabilityLane lane) {
        return new AvailabilityRiskRefreshService.RefreshOutcome(null,
                new AvailabilityProjectionWriter.WrittenCard(UUID.randomUUID(), lane, List.of()),
                null, List.of(), "test");
    }
}
