package com.mimococo.marketops.analyticsdecision.internal.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosisRepository;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.MetricRepository;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalyticsCalculationServiceWindowTest {

    @Test void historicalWindowKeepsItsBusinessIntervalAndUsesCurrentEvaluationTime() {
        Instant now=Instant.parse("2026-09-06T05:37:42Z");
        FactWindow historical=FactWindow.endingAt(Instant.parse("2026-07-01T05:00:00Z"),Duration.ofDays(30));
        UUID store=UUID.randomUUID(),organization=UUID.randomUUID(),run=UUID.randomUUID();
        MetricEngine engine=mock(MetricEngine.class);
        DiagnosisEngine diagnosis=mock(DiagnosisEngine.class);
        MetricRepository metrics=mock(MetricRepository.class);
        DiagnosisRepository diagnoses=mock(DiagnosisRepository.class);
        OperatingFactQuery facts=mock(OperatingFactQuery.class);
        OrganizationDirectory directory=mock(OrganizationDirectory.class);
        IdGenerator ids=mock(IdGenerator.class);
        when(directory.store(store)).thenReturn(java.util.Optional.of(new StoreRef(store,organization,UUID.randomUUID(),"historical","ACTIVE")));
        when(ids.newId()).thenReturn(run);
        when(facts.listingVariantsWithActivity(store,historical,2_000)).thenReturn(List.of());
        var service=new AnalyticsCalculationService(engine,diagnosis,metrics,diagnoses,facts,directory,ids,Clock.fixed(now,ZoneOffset.UTC));
        service.runForWindow(store,MetricWindow.D30,historical,"BACKFILL",null);
        verify(facts).listingVariantsWithActivity(store,historical,2_000);
        verify(metrics).openRun(eq(run),eq(organization),eq("BACKFILL"),eq("STORE"),eq(store),eq("D30"),
                eq(historical.periodStart()),eq(historical.periodEnd()),any(String.class),eq(null),eq(now),any(String.class));
    }

    @ParameterizedTest @ValueSource(strings={"FUTURE","WRONG_DURATION","UNALIGNED"})
    void historicalWindowRejectsInvalidBusinessBoundsBeforeWriting(String mutation) {
        Instant now=Instant.parse("2026-09-06T05:37:42Z");
        FactWindow window=switch(mutation) {
            case "FUTURE" -> FactWindow.endingAt(Instant.parse("2026-09-06T06:00:00Z"),Duration.ofDays(30));
            case "WRONG_DURATION" -> FactWindow.endingAt(Instant.parse("2026-09-06T05:00:00Z"),Duration.ofDays(7));
            default -> FactWindow.endingAt(Instant.parse("2026-09-06T05:01:00Z"),Duration.ofDays(30));
        };
        MetricEngine engine=mock(MetricEngine.class);
        DiagnosisEngine diagnosis=mock(DiagnosisEngine.class);
        MetricRepository metrics=mock(MetricRepository.class);
        DiagnosisRepository diagnoses=mock(DiagnosisRepository.class);
        OperatingFactQuery facts=mock(OperatingFactQuery.class);
        OrganizationDirectory directory=mock(OrganizationDirectory.class);
        IdGenerator ids=mock(IdGenerator.class);
        var service=new AnalyticsCalculationService(engine,diagnosis,metrics,diagnoses,facts,directory,ids,Clock.fixed(now,ZoneOffset.UTC));
        assertThatThrownBy(()->service.runForWindow(UUID.randomUUID(),MetricWindow.D30,window,"BACKFILL",null))
                .isInstanceOf(com.mimococo.marketops.shared.OperationRejectedException.class);
        verifyNoInteractions(metrics,diagnoses,facts,engine,directory,ids);
    }

    @Test
    void nonHourClockProducesOneExactQueriedAndStoredWindow() {
        Instant nonHour = Instant.parse("2026-08-30T05:37:42.123Z");
        FactWindow expected = FactWindow.endingAt(Instant.parse("2026-08-30T05:00:00Z"),
                Duration.ofDays(30));
        UUID organizationId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        MetricEngine engine = mock(MetricEngine.class);
        DiagnosisEngine diagnosisEngine = mock(DiagnosisEngine.class);
        MetricRepository metrics = mock(MetricRepository.class);
        DiagnosisRepository diagnoses = mock(DiagnosisRepository.class);
        OperatingFactQuery facts = mock(OperatingFactQuery.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        IdGenerator ids = mock(IdGenerator.class);
        when(organizations.store(storeId)).thenReturn(java.util.Optional.of(
                new StoreRef(storeId, organizationId, accountId, "store", "ACTIVE")));
        when(ids.newId()).thenReturn(runId);
        when(facts.listingVariantsWithActivity(storeId, expected, 2_000))
                .thenReturn(List.of(subjectId));
        when(engine.compute(organizationId, storeId, subjectId, MetricWindow.D30, expected))
                .thenReturn(Map.of());
        when(diagnosisEngine.evaluate(Map.of())).thenReturn(List.of());

        AnalyticsCalculationService service = new AnalyticsCalculationService(engine,
                diagnosisEngine, metrics, diagnoses, facts, organizations, ids,
                Clock.fixed(nonHour, ZoneOffset.UTC));
        service.run(storeId, MetricWindow.D30, "MANUAL", userId);

        verify(facts).listingVariantsWithActivity(storeId, expected, 2_000);
        verify(engine).compute(organizationId, storeId, subjectId, MetricWindow.D30, expected);
        verify(metrics).openRun(eq(runId), eq(organizationId), eq("MANUAL"), eq("STORE"),
                eq(storeId), eq("D30"), eq(expected.periodStart()), eq(expected.periodEnd()),
                any(String.class), eq(userId), eq(nonHour), any(String.class));
    }
}
