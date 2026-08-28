package com.mimococo.marketops.operatingfacts.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.marketplaceintegration.*;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.NormalizationDeclarationRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/** Failure paths over stored evidence; no provider or object storage is contacted. */
class NormalizationRunnerTest {
    private final IngestionJobDirectory jobs=mock(IngestionJobDirectory.class);
    private final RawEvidenceQuery evidence=mock(RawEvidenceQuery.class);
    private final NormalizationDeclarationRepository declarations=mock(NormalizationDeclarationRepository.class);
    private final FactRecorder recorder=mock(FactRecorder.class);
    private final IdGenerator ids=UUID::randomUUID;
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private final Instant now=Instant.parse("2026-08-28T00:00:00Z");
    private final UUID jobId=UUID.randomUUID(),mappingId=UUID.randomUUID();
    private final IngestionJobView job=new IngestionJobView(jobId,UUID.randomUUID(),"OZON",UUID.randomUUID(),UUID.randomUUID(),"PRICE","fixture-job","ACTIVE");
    private final NormalizationRunner runner=new NormalizationRunner(jobs,evidence,declarations,
            new PayloadReader(JsonMapper.builder().build()),recorder,ids,Clock.fixed(now,ZoneOffset.UTC),transactions);

    @BeforeEach
    void verifiedDeclaration() {
        when(jobs.job(jobId)).thenReturn(Optional.of(job));
        when(declarations.liveMapping("OZON","PRICE")).thenReturn(Optional.of(new NormalizationDeclarationRepository.MappingDeclaration(mappingId,"/rows",1)));
        when(declarations.fieldPointers(mappingId)).thenReturn(Map.of("nativeListingKey","/listing","nativeVariantKey","/variant"));
        when(declarations.valueKinds("PRICE")).thenReturn(Map.of());
        when(declarations.requiredFields("PRICE")).thenReturn(List.of("nativeListingKey","nativeVariantKey"));
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(declarations.advanceProgress(eq(jobId),any(),any(),anyLong(),eq(now),anyLong())).thenReturn(true);
        when(recorder.record(eq(job),any(),any())).thenReturn(1);
    }

    @Test
    void absentJobStoreAndDeclarationCannotReadRawOrWriteAnything() {
        when(jobs.job(jobId)).thenReturn(Optional.empty());
        assertThat(runner.runOnce(jobId).reason()).isEqualTo("JOB_NOT_FOUND");
        when(jobs.job(jobId)).thenReturn(Optional.of(new IngestionJobView(jobId,job.organizationId(),"OZON",job.marketplaceAccountId(),null,"PRICE","fixture-job","ACTIVE")));
        assertThat(runner.runOnce(jobId).reason()).isEqualTo("JOB_HAS_NO_STORE");
        when(jobs.job(jobId)).thenReturn(Optional.of(job));
        when(declarations.liveMapping("OZON","PRICE")).thenReturn(Optional.empty());
        assertThat(runner.runOnce(jobId).reason()).isEqualTo("PAYLOAD_DECLARATION_NOT_VERIFIED");
        verifyNoInteractions(evidence,recorder,transactions);
    }

    @Test
    void emptyPagePreservesProgressWithoutStartingATransaction() {
        assertThat(runner.runOnce(jobId).reason()).isEqualTo("NOTHING_TO_PROCESS");
        verifyNoInteractions(recorder,transactions);
        noCursorAdvance();
    }

    @Test
    void verifiedBytesAreReadBeforeTheFactTransactionAndCursorMovesAfterCommit() {
        var observation=observation("SUCCESS_BYTES");
        page(observation,"{\"rows\":[{\"listing\":\"L\",\"variant\":\"V\",\"unknown\":\"kept-in-raw\"}]}");
        var outcome=runner.runOnce(jobId);
        assertThat(outcome.reason()).isEqualTo("PROCESSED");
        assertThat(outcome.factsRecorded()).isEqualTo(1);
        var ordered=inOrder(evidence,transactions,declarations,recorder);
        ordered.verify(evidence).verifiedBody(observation.observationId());
        ordered.verify(transactions).getTransaction(any());
        ordered.verify(declarations).recordDrift(any(),eq(jobId),eq(mappingId),eq("/unknown"),eq(observation.observationId()),eq(now));
        ordered.verify(recorder).record(eq(job),eq(observation),any());
        ordered.verify(transactions).commit(any());
        ordered.verify(declarations).advanceProgress(jobId,now,observation.observationId(),1,now,0);
    }

    @Test
    void missingOrCorruptObjectCannotBeSkippedByTheCursor() {
        var observation=observation("SUCCESS_BYTES");
        when(evidence.observationsAfter(jobId,null,null,100)).thenReturn(List.of(observation));
        var outcome=runner.runOnce(jobId);
        assertThat(outcome.reason()).isEqualTo("RAW_UNVERIFIABLE");
        assertThat(outcome.recordsRejected()).isEqualTo(1);
        noCursorAdvance();
        verifyNoInteractions(recorder,transactions);
    }

    @ParameterizedTest
    @ValueSource(strings={"not-json","{\"rows\":[{}],\"rows\":[]}","{\"wrong\":[]}","{\"rows\":[null]}","{\"rows\":null}"})
    void unreadablePayloadLeavesTheCursorForRepairAndReplay(String body) {
        page(observation("SUCCESS_BYTES"),body);
        assertThat(runner.runOnce(jobId).reason()).isEqualTo("PAYLOAD_UNREADABLE");
        verifyNoInteractions(recorder,transactions);
        noCursorAdvance();
    }

    @Test
    void oneIncompleteRecordRefusesTheWholeObservationButKeepsDriftEvidence() {
        var observation=observation("SUCCESS_BYTES");
        page(observation,"{\"rows\":[{\"listing\":\"L\",\"variant\":\"V\"},{\"listing\":\"L\",\"new\":1}]}");
        var outcome=runner.runOnce(jobId);
        assertThat(outcome.reason()).isEqualTo("REQUIRED_FIELD_MISSING");
        assertThat(outcome.recordsRejected()).isEqualTo(1);
        assertThat(outcome.factsRecorded()).isZero();
        verify(declarations).recordDrift(any(),eq(jobId),eq(mappingId),eq("/new"),eq(observation.observationId()),eq(now));
        verify(transactions).commit(any());
        verifyNoInteractions(recorder);
        noCursorAdvance();
    }

    @Test
    void factFailureRollsBackTheObservationAndDoesNotAdvance() {
        page(observation("SUCCESS_BYTES"),"{\"rows\":[{\"listing\":\"L\",\"variant\":\"V\"}]}");
        when(recorder.record(any(),any(),any())).thenThrow(new IllegalStateException("injected fact failure"));
        assertThatThrownBy(() -> runner.runOnce(jobId)).isInstanceOf(IllegalStateException.class);
        verify(transactions).rollback(any());
        verify(transactions,never()).commit(any());
        noCursorAdvance();
    }

    @Test
    void unrepresentableMoneyOrQuantityRollsBackAndRemainsAvailableForRepair() {
        page(observation("SUCCESS_BYTES"),"{\"rows\":[{\"listing\":\"L\",\"variant\":\"V\"}]}");
        when(recorder.record(any(),any(),any())).thenThrow(new ArithmeticException("injected range refusal"));
        var result=runner.runOnce(jobId);
        assertThat(result.reason()).isEqualTo("RECORD_OUT_OF_RANGE");
        assertThat(result.recordsRejected()).isEqualTo(1);
        verify(transactions).rollback(any());
        verify(transactions,never()).commit(any());
        noCursorAdvance();
    }

    @Test
    void businessFailureBytesAreNotNormalizedAsFacts() {
        var observation=observation("BUSINESS_FAILURE_BYTES");
        when(evidence.observationsAfter(jobId,null,null,100)).thenReturn(List.of(observation));
        assertThat(runner.runOnce(jobId).factsRecorded()).isZero();
        verify(evidence,never()).verifiedBody(any());
        verifyNoInteractions(recorder,transactions);
        verify(declarations).advanceProgress(jobId,now,observation.observationId(),1,now,0);
    }

    @Test
    void losingCursorCasDoesNotClaimOwnershipOrRepeatFactWrites() {
        var observation=observation("SUCCESS_BYTES");
        UUID previous=UUID.randomUUID(); Instant previousTime=now.minusSeconds(60);
        when(declarations.progress(jobId)).thenReturn(Optional.of(new NormalizationDeclarationRepository.ProgressCursor(previousTime,previous,10,7)));
        when(evidence.observationsAfter(jobId,previousTime,previous,100)).thenReturn(List.of(observation));
        when(evidence.verifiedBody(observation.observationId())).thenReturn(Optional.of("{\"rows\":[{\"listing\":\"L\",\"variant\":\"V\"}]}".getBytes(StandardCharsets.UTF_8)));
        when(declarations.advanceProgress(jobId,now,observation.observationId(),1,now,7)).thenReturn(false);
        assertThat(runner.runOnce(jobId).reason()).isEqualTo("CURSOR_TAKEN_OVER");
        verify(recorder,times(1)).record(eq(job),eq(observation),any());
    }

    private RawObservationView observation(String outcome) {
        return new RawObservationView(UUID.randomUUID(),jobId,UUID.randomUUID(),"PAGE","fixture-page",now,"fixture",outcome,now,"1".repeat(64),32);
    }
    private void page(RawObservationView observation,String body) {
        when(evidence.observationsAfter(jobId,null,null,100)).thenReturn(List.of(observation));
        when(evidence.verifiedBody(observation.observationId())).thenReturn(Optional.of(body.getBytes(StandardCharsets.UTF_8)));
    }
    private void noCursorAdvance() { verify(declarations,never()).advanceProgress(any(),any(),any(),anyLong(),any(),anyLong()); }
}
