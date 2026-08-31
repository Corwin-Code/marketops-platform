package com.mimococo.marketops.availabilityrisk.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
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
class AvailabilityTargetedWorkerCapacityTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();

    @Mock AvailabilityRecalculationRepository queue;
    @Mock AvailabilityRiskRefreshService refresh;
    @Mock IdGenerator ids;

    private AvailabilityTargetedWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AvailabilityTargetedWorker(queue, refresh, ids,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(ids.newId()).thenAnswer(invocation -> UUID.randomUUID());
    }

    @Test
    @DisplayName("TC-TARGET-CAP-001 5000 accepted critical facts retain the five-minute distribution margin")
    void declaredCapacityRetainsTargetedSloMargin() {
        List<AvailabilityRecalculationRepository.ClaimedRequest> requests = new ArrayList<>();
        for (int index = 1; index <= 5_000; index++) {
            requests.add(new AvailabilityRecalculationRepository.ClaimedRequest(
                    new UUID(1L, index), ORGANIZATION, new UUID(2L, index),
                    "STOCK_OR_SELLABILITY", null, NOW.minus(Duration.ofMinutes(1)), 1,
                    "capacity-" + index));
        }
        when(queue.claim(anyString(), eq(NOW.plus(Duration.ofMinutes(5))), eq(5_000), eq(NOW)))
                .thenReturn(requests);
        when(refresh.refresh(eq(ORGANIZATION), org.mockito.ArgumentMatchers.any(), eq(NOW),
                eq(AvailabilityRiskRefreshService.TARGETED), eq(null)))
                .thenReturn(new AvailabilityRiskRefreshService.RefreshOutcome(null,
                        new AvailabilityProjectionWriter.WrittenCard(
                                UUID.randomUUID(), AvailabilityLane.CRITICAL, List.of()),
                        new AvailabilityCaseActivationService.ActivationResult(
                                List.of(), List.of(), false),
                        List.of(), "capacity-refresh"));

        int processed = assertTimeout(Duration.ofSeconds(30), () -> worker.runOnce(5_000));

        assertThat(processed).isEqualTo(5_000);
        ArgumentCaptor<AvailabilityRecalculationRepository.SloObservation> observations =
                ArgumentCaptor.forClass(AvailabilityRecalculationRepository.SloObservation.class);
        verify(queue, times(5_000)).recordObservation(observations.capture());
        assertThat(observations.getAllValues())
                .allSatisfy(observation -> {
                    assertThat(observation.lane()).isEqualTo(AvailabilityLane.CRITICAL.name());
                    assertThat(observation.internalLatencyMillis()).isEqualTo(60_000);
                    assertThat(observation.breached()).isFalse();
                });
    }
}
