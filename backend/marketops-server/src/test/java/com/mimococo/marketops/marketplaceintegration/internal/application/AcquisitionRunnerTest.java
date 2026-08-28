package com.mimococo.marketops.marketplaceintegration.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.marketplaceintegration.internal.application.AcquisitionPageWorker.Kind;
import com.mimococo.marketops.marketplaceintegration.internal.application.AcquisitionPageWorker.PageOutcome;
import com.mimococo.marketops.marketplaceintegration.internal.config.AcquisitionProperties;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository.JobExecutionContext;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository.RunState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AcquisitionRunnerTest {
    private static final UUID RUN=UUID.randomUUID(), JOB=UUID.randomUUID(), OBSERVATION=UUID.randomUUID();
    private static final String WORKER="synthetic-worker";
    private static final long FENCE=7;
    private final IngestionRunRepository runs=mock(IngestionRunRepository.class);
    private final AcquisitionPageWorker pages=mock(AcquisitionPageWorker.class);
    private final AcquisitionProperties properties=new AcquisitionProperties();
    private final AcquisitionRunner runner=new AcquisitionRunner(runs,pages,properties,() -> RUN);
    private final JobExecutionContext context=new JobExecutionContext(JOB,UUID.randomUUID(),"OZON",UUID.randomUUID(),
            UUID.randomUUID(),"ORDERS","synthetic-job",UUID.randomUUID());

    @BeforeEach
    void claimableRun() {
        properties.setMaximumCallsPerRun(2);
        when(runs.claim(RUN,WORKER,300)).thenReturn(FENCE);
        when(runs.findRun(RUN)).thenReturn(Optional.of(state("SCHEDULED",1)));
        when(runs.findJobContext(JOB)).thenReturn(Optional.of(context));
        when(runs.transition(RUN,FENCE,WORKER,"RETRY_WAIT",null,null,120)).thenReturn("RETRY_WAIT");
    }

    @Test
    void enqueueBindsTheConfiguredClaimBudgetAndWindow() {
        Instant from=Instant.parse("2026-08-27T00:00:00Z"),to=from.plusSeconds(3600);
        when(runs.enqueue(RUN,JOB,"BACKFILL",from,to,4)).thenReturn(RUN);
        assertThat(runner.enqueue(JOB,"BACKFILL",from,to)).isEqualTo(RUN);
        verify(runs).enqueue(RUN,JOB,"BACKFILL",from,to,4);
        verifyNoInteractions(pages);
    }

    @ParameterizedTest
    @ValueSource(strings={"NOT_CLAIMABLE","RETRY_BUDGET_EXHAUSTED","NOT_FOUND","JOB_NOT_EXECUTABLE"})
    void noSourceCallCanPrecedeAClaimAndExecutableContext(String reason) {
        switch(reason) {
            case "NOT_CLAIMABLE" -> when(runs.claim(RUN,WORKER,300)).thenThrow(new IllegalStateException("refused"));
            case "RETRY_BUDGET_EXHAUSTED" -> when(runs.claim(RUN,WORKER,300)).thenReturn(0L);
            case "NOT_FOUND" -> when(runs.findRun(RUN)).thenReturn(Optional.empty());
            default -> when(runs.findJobContext(JOB)).thenReturn(Optional.empty());
        }
        assertThat(runner.execute(RUN,WORKER)).isEqualTo(new AcquisitionRunner.RunOutcome(RUN,0,reason));
        verifyNoInteractions(pages);
        if(reason.equals("JOB_NOT_EXECUTABLE")) {
            verify(runs).transition(RUN,FENCE,WORKER,"FAILED_TERMINAL",null,reason);
        } else {
            verify(runs,never()).transition(any(),anyLong(),anyString(),anyString(),any(),any());
        }
    }

    @Test
    void replayRecordsItsLifecycleAndMakesZeroMarketplaceCalls() {
        when(runs.findRun(RUN)).thenReturn(Optional.of(state("REPLAY",1)));
        assertThat(runner.execute(RUN,WORKER)).isEqualTo(new AcquisitionRunner.RunOutcome(RUN,0,"REPLAY_NO_ACQUISITION"));
        var order=inOrder(runs);
        order.verify(runs).claim(RUN,WORKER,300);
        order.verify(runs).findRun(RUN);
        order.verify(runs).findJobContext(JOB);
        order.verify(runs).transition(RUN,FENCE,WORKER,"RUNNING",300,null);
        order.verify(runs).transition(RUN,FENCE,WORKER,"SUCCEEDED",null,null);
        verifyNoInteractions(pages);
    }

    @Test
    void nextPageRenewsTheSameFenceAndOnlyAnExplicitEndCompletesTheRun() {
        when(pages.acquireOnePage(RUN,FENCE,WORKER,context)).thenReturn(
                new PageOutcome(Kind.NEXT,OBSERVATION),new PageOutcome(Kind.END,UUID.randomUUID()));
        assertThat(runner.execute(RUN,WORKER)).isEqualTo(new AcquisitionRunner.RunOutcome(RUN,2,"SUCCEEDED"));
        var order=inOrder(runs,pages);
        order.verify(runs).transition(RUN,FENCE,WORKER,"RUNNING",300,null);
        order.verify(pages).acquireOnePage(RUN,FENCE,WORKER,context);
        order.verify(runs).renewLease(RUN,FENCE,WORKER,300);
        order.verify(pages).acquireOnePage(RUN,FENCE,WORKER,context);
        order.verify(runs).transition(RUN,FENCE,WORKER,"SUCCEEDED",null,null);
    }

    @ParameterizedTest
    @EnumSource(value=Kind.class,names={"UNKNOWN_RESULT","SCHEMA_DRIFT","UNREADABLE","CONFIG_INVALID"})
    void unknownOrInvalidSourceOutcomesBlockInsteadOfRetrying(Kind kind) {
        when(pages.acquireOnePage(RUN,FENCE,WORKER,context)).thenReturn(new PageOutcome(kind,OBSERVATION));
        assertThat(runner.execute(RUN,WORKER)).isEqualTo(new AcquisitionRunner.RunOutcome(RUN,1,kind.name()));
        verify(pages).acquireOnePage(RUN,FENCE,WORKER,context);
        verify(runs).transition(RUN,FENCE,WORKER,"BLOCKED",null,null);
        verify(runs,never()).renewLease(any(),anyLong(),anyString(),anyInt());
        verify(runs,never()).transition(any(),anyLong(),anyString(),eq("RETRY_WAIT"),any(),any(),anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings={"RETRY_WAIT","FAILED_TERMINAL"})
    void retryHonorsBothTheLocalBudgetAndTheDatabaseDeadline(String databaseState) {
        when(runs.findRun(RUN)).thenReturn(Optional.of(state("SCHEDULED",3)));
        when(pages.acquireOnePage(RUN,FENCE,WORKER,context)).thenReturn(new PageOutcome(Kind.RETRY_LATER,null));
        when(runs.transition(RUN,FENCE,WORKER,"RETRY_WAIT",null,null,120)).thenReturn(databaseState);
        assertThat(runner.execute(RUN,WORKER)).isEqualTo(new AcquisitionRunner.RunOutcome(RUN,0,
                databaseState.equals("RETRY_WAIT")?"READ_RETRY":"FAILED_TERMINAL"));
        verify(runs).transition(RUN,FENCE,WORKER,"RETRY_WAIT",null,null,120);
        verify(pages).acquireOnePage(RUN,FENCE,WORKER,context);
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void exhaustedOrMissingRetryStateCannotAuthorizeAnotherAttempt(boolean absent) {
        when(runs.findRun(RUN)).thenReturn(Optional.of(state("SCHEDULED",1)))
                .thenReturn(absent?Optional.empty():Optional.of(state("SCHEDULED",4)));
        when(pages.acquireOnePage(RUN,FENCE,WORKER,context)).thenReturn(new PageOutcome(Kind.RETRY_LATER,null));
        assertThat(runner.execute(RUN,WORKER).reason()).isEqualTo("FAILED_TERMINAL");
        verify(runs).transition(RUN,FENCE,WORKER,"FAILED_TERMINAL",null,"READ_RETRY");
    }

    @Test
    void callCeilingYieldsWithAllCompletedPagesCounted() {
        when(pages.acquireOnePage(RUN,FENCE,WORKER,context)).thenReturn(new PageOutcome(Kind.NEXT,OBSERVATION));
        assertThat(runner.execute(RUN,WORKER)).isEqualTo(new AcquisitionRunner.RunOutcome(RUN,2,"CALL_CEILING_REACHED"));
        verify(pages,times(2)).acquireOnePage(RUN,FENCE,WORKER,context);
        verify(runs,times(2)).renewLease(RUN,FENCE,WORKER,300);
        verify(runs).transition(RUN,FENCE,WORKER,"RETRY_WAIT",null,null,120);
    }

    @Test
    void pageFailureDoesNotDiscardTheEarlierDurablePageCount() {
        when(pages.acquireOnePage(RUN,FENCE,WORKER,context)).thenReturn(new PageOutcome(Kind.NEXT,OBSERVATION))
                .thenThrow(new IllegalStateException("synthetic-source-failure"));
        assertThat(runner.execute(RUN,WORKER)).isEqualTo(new AcquisitionRunner.RunOutcome(RUN,1,"PAGE_FAILED"));
        verify(runs).transition(RUN,FENCE,WORKER,"RETRY_WAIT",null,null,120);
    }

    @Test
    void losingTheLeaseAfterOnePagePreventsAnotherSourceCall() {
        when(pages.acquireOnePage(RUN,FENCE,WORKER,context)).thenReturn(new PageOutcome(Kind.NEXT,OBSERVATION));
        when(runs.renewLease(RUN,FENCE,WORKER,300)).thenThrow(new IllegalStateException("stale fence"));
        assertThatThrownBy(() -> runner.execute(RUN,WORKER)).isInstanceOf(IllegalStateException.class);
        verify(pages).acquireOnePage(RUN,FENCE,WORKER,context);
        verify(runs,never()).transition(any(),anyLong(),anyString(),eq("SUCCEEDED"),any(),any());
    }

    private static RunState state(String kind,int attempt) {
        return new RunState(RUN,JOB,"LEASED",FENCE,WORKER,null,attempt,0,kind,null,null,null);
    }
}
