package com.mimococo.marketops.marketplaceintegration.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.RawContentRef;
import com.mimococo.marketops.marketplaceintegration.internal.config.AdBidWriteProperties;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.AdBidCommandRepository;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWritePort;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the worker does with each answer a marketplace can give.
 *
 * <p>The port is scripted rather than reached, so every outcome — including the
 * ones a real provider would give rarely and at the worst moment — is exercised
 * here rather than hoped for. No provider is called and no credential is
 * resolved: the whole point of a port is that this is possible.
 */
class AdBidCommandWorkerTest {

    private static final UUID COMMAND = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final UUID ATTEMPT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3302");
    private static final UUID CREDENTIAL = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3303");

    private final AdBidCommandRepository commands = mock(AdBidCommandRepository.class);
    private final AdBidWritePort writePort = mock(AdBidWritePort.class);
    private final CredentialDirectory credentials = mock(CredentialDirectory.class);
    private final ListingIdentityDirectory listings = mock(ListingIdentityDirectory.class);
    private final RawCustodyService custody = mock(RawCustodyService.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final AdBidWriteProperties properties = new AdBidWriteProperties();

    private AdBidCommandWorker worker;

    private static AdBidCommandRepository.CommandRow row(String state) {
        return new AdBidCommandRepository.CommandRow(
                COMMAND, COMMAND, COMMAND, COMMAND, COMMAND, "campaign-1", "object-1", "OZON",
                COMMAND, "abc-idempotency-key-01", "RUB", "CURRENCY_MAJOR",
                "PROTECTION_DECREASE", "MAX_CPC_BOUNDED", "MATERIAL_IMPACT",
                new BigDecimal("30.0000"), new BigDecimal("20.0000"), "a".repeat(64),
                state, 0, 3, 1L, "worker", null, Instant.parse("2026-09-05T00:00:00Z"));
    }

    /**
     * An answer bound to the request that produced it, as a real adapter's is.
     *
     * <p>The digest matters: the worker treats a response naming a different
     * request as no evidence about this one, so a fixture with a made-up digest
     * would exercise that path instead of the one under test.
     */
    private static org.mockito.stubbing.Answer<AdBidWriteResult> answering(
            AdBidWriteResult.Outcome kind, String taskKey) {
        return call -> {
            AdBidWriteRequest request = call.getArgument(0);
            return new AdBidWriteResult(kind, "200", taskKey, null, null, null, new byte[] {1},
                    Instant.parse("2026-09-04T00:00:00Z"), null,
                    new AdBidWriteResult.Response(200, Map.of(), request.digest(),
                            "PROTOCOL_FIXTURE", true));
        };
    }

    @BeforeEach
    void scripted() {
        worker = new AdBidCommandWorker(commands, writePort, credentials, listings, custody,
                properties, ids);
        when(ids.newId()).thenReturn(ATTEMPT);
        when(commands.row(COMMAND)).thenReturn(Optional.of(row("PENDING")));
        when(commands.lease(eq(COMMAND), anyString(), anyInt())).thenReturn(1L);
        when(commands.leaseReadback(eq(COMMAND), anyString(), anyInt())).thenReturn(1L);
        when(commands.leaseCompensation(eq(COMMAND), anyString(), anyInt())).thenReturn(1L);
        when(commands.transition(any(), anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenReturn("ok");
        when(commands.openAttempt(any(), any(), anyString(), anyLong(), anyString(), anyString(),
                anyString())).thenReturn(ATTEMPT);
        when(commands.transitionReadback(any(), any(), anyLong(), anyString()))
                .thenReturn("MATCHES_TARGET");
        when(credentials.writeCredential(any(), any())).thenReturn(Optional.of(CREDENTIAL));
        when(custody.store(anyString(), any()))
                .thenReturn(new RawContentRef(COMMAND, "e".repeat(64), 1L, "object://fixture/response"));
        when(commands.completeAttempt(any(), anyLong(), anyString(), any(), any(), anyString()))
                .thenAnswer(call -> call.getArgument(3));
    }

    private void claim(String state) {
        when(commands.row(COMMAND)).thenReturn(Optional.of(row(state)));
        when(commands.claimable(any(), anyInt())).thenReturn(List.of(COMMAND));
    }

    @Nested
    @DisplayName("TC-AD-WORKER-001 each answer moves the command somewhere different")
    class Answers {

        @Test
        @DisplayName("an acceptance with a task handle waits for the platform")
        void acceptedWithATaskWaits() {
            claim("PENDING");
            when(writePort.perform(any()))
                    .thenAnswer(answering(AdBidWriteResult.Outcome.ACCEPTED, "task-1"));

            assertThat(worker.runOnce(Instant.now(), 10)).isEqualTo(1);
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(),
                    eq("PLATFORM_PENDING"), any(), any(), any());
        }

        @Test
        @DisplayName("an acceptance with no handle goes straight to observing")
        void acceptedWithoutATaskObserves() {
            claim("PENDING");
            when(writePort.perform(any()))
                    .thenAnswer(answering(AdBidWriteResult.Outcome.ACCEPTED, null));

            worker.runOnce(Instant.now(), 10);

            // Acceptance is not success. The only thing that closes a command is
            // a readback that matched the target.
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(),
                    eq("READBACK_PENDING"), any(), any(), any());
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(),
                    eq("READBACK_MATCHED"), any(), any(), any());
        }

        @Test
        @DisplayName("a refusal is final and carries the reason it was refused for")
        void refusalIsFinal() {
            claim("PENDING");
            when(writePort.perform(any())).thenReturn(AdBidWriteResult.refusedBeforeDispatch(
                    "write_operation_not_verified", Instant.now()));

            worker.runOnce(Instant.now(), 10);

            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("FAILED_FINAL"),
                    eq("write_operation_not_verified"), any(), any());
        }

        @Test
        @DisplayName("a retriable label without proof requires readback before any retry")
        void retriableErrorWaits() {
            claim("PENDING");
            when(writePort.perform(any())).thenReturn(new AdBidWriteResult(
                    AdBidWriteResult.Outcome.RETRIABLE_ERROR, "503", null, null, null, null,
                    null, Instant.now(), "rate_limited", null));

            worker.runOnce(Instant.now(), 10);

            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("READBACK_PENDING"),
                    any(), any(), any());
            verify(commands, never()).transition(eq(COMMAND), anyLong(), anyString(), eq("RETRY_WAIT"),
                    any(), any(), any());
        }

        @Test
        @DisplayName("a timeout and an unclassifiable answer are the same thing")
        void timeoutAndUnknownAreTheSame() {
            for (var kind : List.of(AdBidWriteResult.Outcome.TIMEOUT,
                    AdBidWriteResult.Outcome.UNKNOWN_STATE)) {
                claim("PENDING");
                when(writePort.perform(any())).thenReturn(new AdBidWriteResult(
                        kind, null, null, null, null, null, null, Instant.now(),
                        "provider_did_not_answer", null));

                worker.runOnce(Instant.now(), 10);
            }
            // Both mean "we do not know", and both oblige an observation rather
            // than a retry.
            verify(commands, org.mockito.Mockito.times(2)).transition(eq(COMMAND), anyLong(),
                    anyString(), eq("UNKNOWN_REQUIRES_READBACK"), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("TC-AD-WORKER-002 evidence that names no call is not evidence about it")
    class EvidenceBinding {

        @Test
        @DisplayName("an acceptance with no response becomes an unknown")
        void acceptanceWithoutAResponseIsUnknown() {
            claim("PENDING");
            when(writePort.perform(any())).thenReturn(new AdBidWriteResult(
                    AdBidWriteResult.Outcome.ACCEPTED, "200", null, null, null, null, null,
                    Instant.now(), null, null));

            worker.runOnce(Instant.now(), 10);

            // An adapter claiming success with nothing to show for it is not a
            // success this product will record.
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(),
                    eq("UNKNOWN_REQUIRES_READBACK"), any(), any(), any());
        }

        @Test
        @DisplayName("a response naming a different request becomes an unknown")
        void responseNamingADifferentRequestIsUnknown() {
            claim("PENDING");
            when(writePort.perform(any())).thenReturn(new AdBidWriteResult(
                    AdBidWriteResult.Outcome.ACCEPTED, "200", null, null, null, null,
                    new byte[] {1}, Instant.now(), null,
                    new AdBidWriteResult.Response(200, Map.of(), "f".repeat(64),
                            "PROVIDER_RESPONSE", true)));

            worker.runOnce(Instant.now(), 10);

            verify(commands).transition(eq(COMMAND), anyLong(), anyString(),
                    eq("UNKNOWN_REQUIRES_READBACK"), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("TC-AD-WORKER-003 no credential means no attempt and no call")
    class Credentials {

        @Test
        @DisplayName("an absent credential reference stops before an attempt row exists")
        void absentCredentialStopsBeforeAnAttempt() {
            claim("PENDING");
            when(credentials.writeCredential(any(), any())).thenReturn(Optional.empty());

            worker.runOnce(Instant.now(), 10);

            // Nothing happened, so there is no attempt to explain and the
            // command waits for a person.
            verify(commands, never()).openAttempt(any(), any(), anyString(), anyLong(),
                    anyString(), anyString(), anyString());
            verify(writePort, never()).perform(any());
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(),
                    eq("FAILED_FINAL"), eq("credential_reference_absent"), any(), any());
        }
    }

    @Nested
    @DisplayName("TC-AD-WORKER-004 an unknown result is observed, never repeated")
    class Unknown {

        @Test
        @DisplayName("a command in the unknown state reads back rather than applying")
        void unknownStateObserves() {
            claim("UNKNOWN_REQUIRES_READBACK");
            when(writePort.perform(any()))
                    .thenAnswer(answering(AdBidWriteResult.Outcome.ACCEPTED, null));

            worker.runOnce(Instant.now(), 10);

            // The only operation this path may make.
            var request = org.mockito.ArgumentCaptor.forClass(AdBidWriteRequest.class);
            verify(writePort).perform(request.capture());
            assertThat(request.getValue().operation())
                    .isEqualTo(AdBidWriteRequest.Operation.READBACK);
        }

        @Test
        @DisplayName("a third value routes to investigation rather than compensation")
        void aThirdValueRoutesToInvestigation() {
            claim("UNKNOWN_REQUIRES_READBACK");
            when(writePort.perform(any()))
                    .thenAnswer(answering(AdBidWriteResult.Outcome.ACCEPTED, null));
            when(commands.transitionReadback(any(), any(), anyLong(), anyString()))
                    .thenReturn("DIFFERENT");

            worker.runOnce(Instant.now(), 10);

            // Something outside this lineage owns that bid now. Restoring "the
            // prior bid" would overwrite whatever a third party set.
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(),
                    eq("LATER_CHANGE_OR_MISMATCH_INVESTIGATION"), any(), any(), any());
        }

        @Test
        @DisplayName("an unreadable observation leaves the command unknown")
        void unreadableObservationStaysUnknown() {
            claim("UNKNOWN_REQUIRES_READBACK");
            when(writePort.perform(any()))
                    .thenAnswer(answering(AdBidWriteResult.Outcome.ACCEPTED, null));
            when(commands.transitionReadback(any(), any(), anyLong(), anyString()))
                    .thenReturn("UNREADABLE");

            worker.runOnce(Instant.now(), 10);

            verify(commands, org.mockito.Mockito.atLeastOnce()).transition(eq(COMMAND), anyLong(),
                    anyString(), eq("UNKNOWN_REQUIRES_READBACK"), any(), any(), any());
        }
    }

    @Test
    void pendingNativeStatusPollPreservesTaskAndNeverAppliesAgain() {
        claim("PLATFORM_PENDING");
        when(commands.leaseStatus(eq(COMMAND), anyString(), anyInt())).thenReturn(2L);
        when(commands.nativeTaskKey(COMMAND)).thenReturn(Optional.of("native-task-original"));
        when(writePort.perform(any())).thenAnswer(answering(AdBidWriteResult.Outcome.RETRIABLE_ERROR, null));
        assertThat(worker.runOnce(Instant.now(), 10)).isEqualTo(1);
        var request = org.mockito.ArgumentCaptor.forClass(AdBidWriteRequest.class);
        verify(writePort).perform(request.capture());
        assertThat(request.getValue().operation()).isEqualTo(AdBidWriteRequest.Operation.STATUS_ENQUIRY);
        assertThat(request.getValue().nativeTaskKey()).isEqualTo("native-task-original");
        verify(commands).deferObservation(eq(COMMAND), eq(2L), anyString(), anyInt());
        verify(commands, never()).lease(eq(COMMAND), anyString(), anyInt());
        verify(commands, never()).transition(eq(COMMAND), anyLong(), anyString(), eq("RETRY_WAIT"), any(), any(), any());
    }

    @Test
    void resolvedNativeStatusMustReadBackBeforeSuccess() {
        claim("PLATFORM_PENDING");
        when(commands.nativeTaskKey(COMMAND)).thenReturn(Optional.of("native-task-original"));
        when(writePort.perform(any())).thenAnswer(answering(AdBidWriteResult.Outcome.ACCEPTED, null));
        worker.runOnce(Instant.now(), 10);
        var request = org.mockito.ArgumentCaptor.forClass(AdBidWriteRequest.class);
        verify(writePort,org.mockito.Mockito.times(2)).perform(request.capture());
        assertThat(request.getAllValues()).extracting(AdBidWriteRequest::operation)
                .containsExactly(AdBidWriteRequest.Operation.STATUS_ENQUIRY, AdBidWriteRequest.Operation.READBACK);
        verify(commands).transition(eq(COMMAND),anyLong(),anyString(),eq("READBACK_MATCHED"),any(),any(),any());
    }

    @Test
    void compensationKeepsItsLeaseUntilExactPriorReadback() {
        claim("COMPENSATION_PENDING");
        when(writePort.perform(any())).thenAnswer(answering(AdBidWriteResult.Outcome.ACCEPTED, null));
        when(commands.transitionReadback(any(),any(),anyLong(),anyString()))
                .thenReturn("MATCHES_TARGET", "MATCHES_PRIOR");
        worker.runOnce(Instant.now(), 10);
        var request = org.mockito.ArgumentCaptor.forClass(AdBidWriteRequest.class);
        verify(writePort,org.mockito.Mockito.times(3)).perform(request.capture());
        assertThat(request.getAllValues()).extracting(AdBidWriteRequest::operation)
                .containsExactly(AdBidWriteRequest.Operation.READBACK,AdBidWriteRequest.Operation.RESTORE,
                        AdBidWriteRequest.Operation.READBACK);
        assertThat(request.getAllValues().get(1).targetBid().amount()).isEqualByComparingTo("30.0000");
        verify(commands).transition(eq(COMMAND),anyLong(),anyString(),eq("COMPENSATED"),any(),any(),any());
        verify(commands,never()).transition(eq(COMMAND),anyLong(),anyString(),eq("READBACK_MATCHED"),any(),any(),any());
    }

    @Test
    void compensationCannotOverwriteThirdPartyCurrentValue() {
        claim("COMPENSATION_PENDING");
        when(writePort.perform(any())).thenAnswer(answering(AdBidWriteResult.Outcome.ACCEPTED, null));
        when(commands.transitionReadback(any(),any(),anyLong(),anyString())).thenReturn("DIFFERENT");
        worker.runOnce(Instant.now(), 10);
        var request = org.mockito.ArgumentCaptor.forClass(AdBidWriteRequest.class);
        verify(writePort).perform(request.capture());
        assertThat(request.getValue().operation()).isEqualTo(AdBidWriteRequest.Operation.READBACK);
        verify(commands).transition(eq(COMMAND),anyLong(),anyString(),eq("MANUAL_RESOLUTION"),
                eq("compensation_current_owner_not_proven"),any(),any());
    }

    @Test
    void sameCommandRetryNeedsIndependentDatabaseProof() {
        claim("UNKNOWN_REQUIRES_READBACK");
        when(writePort.perform(any())).thenAnswer(answering(AdBidWriteResult.Outcome.ACCEPTED, null));
        when(commands.transitionReadback(any(),any(),anyLong(),anyString())).thenReturn("MATCHES_PRIOR");
        when(commands.retryIsProven(COMMAND)).thenReturn(true);
        worker.runOnce(Instant.now(), 10);
        verify(commands).transition(eq(COMMAND),anyLong(),anyString(),eq("RETRY_WAIT"),any(),anyInt(),any());
        var request=org.mockito.ArgumentCaptor.forClass(AdBidWriteRequest.class);
        verify(writePort).perform(request.capture());
        assertThat(request.getValue().operation()).isEqualTo(AdBidWriteRequest.Operation.READBACK);
    }

    @Nested
    @DisplayName("TC-AD-WORKER-005 a refusal from the database is not a failure of the pass")
    class Refusals {

        @Test
        @DisplayName("a command the database will not lease is skipped, and the pass continues")
        void refusedLeaseIsSkipped() {
            claim("PENDING");
            when(commands.lease(eq(COMMAND), anyString(), anyInt()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "the advertising write gate is closed: CAPABILITY_NOT_VERIFIED"));

            // The gate refusing is the ordinary case in this product, not an
            // incident, and one refused command must not stop the others.
            assertThat(worker.runOnce(Instant.now(), 10)).isZero();
            verify(writePort, never()).perform(any());
        }

        @Test
        @DisplayName("a command that vanished between claim and read is skipped")
        void vanishedCommandIsSkipped() {
            when(commands.claimable(any(), anyInt())).thenReturn(List.of(COMMAND));
            when(commands.row(COMMAND)).thenReturn(Optional.empty());

            assertThat(worker.runOnce(Instant.now(), 10)).isZero();
        }

        @Test
        @DisplayName("an empty claim list does nothing at all")
        void emptyClaimListDoesNothing() {
            when(commands.claimable(any(), anyInt())).thenReturn(List.of());

            assertThat(worker.runOnce(Instant.now(), 10)).isZero();
            verify(writePort, never()).perform(any());
        }
    }
}
