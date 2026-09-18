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
import com.mimococo.marketops.marketplaceintegration.internal.config.ListingDescriptionWriteProperties;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ListingDescriptionCommandRepository;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWritePort;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteResult;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What the description worker does with each answer a marketplace can give.
 *
 * <p>The port is scripted rather than reached: no provider is called and no
 * credential is resolved. What is asserted is the shape of the command's
 * movement — an unknown is observed and never repeated, a missing prior text is
 * never restored, and every call carries the one verified attribute.
 */
class ListingDescriptionCommandWorkerTest {

    private static final UUID COMMAND = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3401");
    private static final UUID ATTEMPT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3402");
    private static final UUID CREDENTIAL = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3403");
    private static final String PRIOR = "Прежнее описание товара";
    private static final String TARGET = "Новое описание товара для покупателя";

    private final ListingDescriptionCommandRepository commands = mock(ListingDescriptionCommandRepository.class);
    private final DescriptionWritePort writePort = mock(DescriptionWritePort.class);
    private final CredentialDirectory credentials = mock(CredentialDirectory.class);
    private final RawCustodyService custody = mock(RawCustodyService.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final ListingDescriptionWriteProperties properties = new ListingDescriptionWriteProperties();

    private ListingDescriptionCommandWorker worker;

    private static ListingDescriptionCommandRepository.CommandRow row(String state, String priorText) {
        return row(state, priorText, "listing-1");
    }

    private static ListingDescriptionCommandRepository.CommandRow row(String state, String priorText, String nativeKey) {
        return new ListingDescriptionCommandRepository.CommandRow(
                COMMAND, COMMAND, COMMAND, COMMAND, COMMAND, COMMAND, nativeKey, "OZON", COMMAND,
                "lcd-" + "0".repeat(32), priorText, "a".repeat(64), TARGET, "b".repeat(64), false, "EXACT",
                10, 6000, "c".repeat(64), state, 0, 3, 1L, "worker", null,
                Instant.parse("2026-09-05T00:00:00Z"));
    }

    private static org.mockito.stubbing.Answer<DescriptionWriteResult> answering(
            DescriptionWriteResult.Outcome kind, String taskKey) {
        return call -> {
            DescriptionWriteRequest request = call.getArgument(0);
            return new DescriptionWriteResult(kind, "200", taskKey, new byte[] {1},
                    Instant.parse("2026-09-04T00:00:00Z"), null, null,
                    new DescriptionWriteResult.Response(200, Map.of(), request.digest(), "PROTOCOL_FIXTURE", true));
        };
    }

    @BeforeEach
    void scripted() {
        worker = new ListingDescriptionCommandWorker(commands, writePort, credentials, custody, properties, ids);
        when(ids.newId()).thenReturn(ATTEMPT);
        when(commands.row(COMMAND)).thenReturn(Optional.of(row("PENDING", PRIOR)));
        when(commands.lease(eq(COMMAND), anyString(), anyInt())).thenReturn(1L);
        when(commands.leaseReadback(eq(COMMAND), anyString(), anyInt())).thenReturn(1L);
        when(commands.leaseStatus(eq(COMMAND), anyString(), anyInt())).thenReturn(1L);
        when(commands.leaseCompensation(eq(COMMAND), anyString(), anyInt())).thenReturn(1L);
        when(commands.transition(any(), anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenReturn("ok");
        when(commands.openAttempt(any(), any(), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(ATTEMPT);
        when(commands.transitionReadback(any(), any(), anyLong(), anyString())).thenReturn("MATCHES_TARGET");
        when(credentials.writeCredential(any(), any())).thenReturn(Optional.of(CREDENTIAL));
        when(credentials.descriptionAttributeKey(any())).thenReturn(Optional.of("4191"));
        when(custody.store(anyString(), any()))
                .thenReturn(new RawContentRef(COMMAND, "e".repeat(64), 1L, "object://fixture/response"));
        when(commands.completeAttempt(any(), anyLong(), anyString(), any(), any(), anyString()))
                .thenAnswer(call -> call.getArgument(3));
    }

    private void claim(String state) {
        claim(state, PRIOR);
    }

    private void claim(String state, String priorText) {
        when(commands.row(COMMAND)).thenReturn(Optional.of(row(state, priorText)));
        when(commands.claimable(any(), anyInt())).thenReturn(List.of(COMMAND));
    }

    @Nested
    @DisplayName("TC-LC-WORKER-001 each answer moves the command somewhere different")
    class Answers {

        @Test
        void missingEnquiryCredentialCannotRejectAnExistingNativeTask() {
            claim("PLATFORM_PENDING");
            when(credentials.writeCredential(any(), any())).thenReturn(Optional.empty());
            worker.runOnce(Instant.now(), 10);
            verify(writePort, never()).perform(any());
            verify(commands).deferObservation(eq(COMMAND), eq(1L), anyString(), anyInt());
            verify(commands, never()).transition(any(), anyLong(), anyString(), eq("FAILED_FINAL"), any(), any(), any());
        }

        @Test
        void historicalUnboundNativeIdentityStaysPendingWithoutAnEnquiryCall() {
            claim("PLATFORM_PENDING");
            when(commands.row(COMMAND)).thenReturn(Optional.of(row("PLATFORM_PENDING", PRIOR, null)));
            worker.runOnce(Instant.now(), 10);
            verify(writePort, never()).perform(any());
            verify(commands).deferObservation(eq(COMMAND), eq(1L), anyString(), anyInt());
            verify(commands, never()).transition(any(), anyLong(), anyString(), eq("FAILED_FINAL"), any(), any(), any());
        }

        @Test
        void providerWaitDefersReadbackWithoutAnotherCall() {
            claim("PENDING");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.RETRIABLE_ERROR, null));
            when(commands.providerWaitActive(COMMAND)).thenReturn(true);
            assertThat(worker.runOnce(Instant.now(), 10)).isEqualTo(1);
            verify(writePort, org.mockito.Mockito.times(1)).perform(any());
            verify(commands).deferObservation(eq(COMMAND),eq(1L),anyString(),anyInt());
            verify(commands,never()).transitionReadback(any(),any(),anyLong(),anyString());
        }

        @Test
        void nativeWaitLongerThanOneHourIsNotShortenedByWorker() {
            claim("PLATFORM_PENDING");
            when(writePort.perform(any())).thenAnswer(call -> {
                DescriptionWriteRequest request=call.getArgument(0);
                return new DescriptionWriteResult(DescriptionWriteResult.Outcome.RETRIABLE_ERROR,"429",null,
                        new byte[]{1},Instant.now(),null,7200,
                        new DescriptionWriteResult.Response(429,Map.of("item-retry-after","120"),request.digest(),
                                "PROTOCOL_FIXTURE",true));
            });
            assertThat(worker.runOnce(Instant.now(),10)).isEqualTo(1);
            verify(commands).deferObservation(eq(COMMAND),eq(1L),anyString(),eq(7200));
        }

        @Test
        void throttledReadbackIsParkedForLaterObservation() {
            claim("UNKNOWN_REQUIRES_READBACK");
            when(commands.providerWaitActive(COMMAND)).thenReturn(false,true);
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.RETRIABLE_ERROR,null));
            assertThat(worker.runOnce(Instant.now(),10)).isEqualTo(1);
            verify(commands).deferObservation(eq(COMMAND),eq(1L),anyString(),anyInt());
            verify(commands,never()).transitionReadback(any(),any(),anyLong(),anyString());
            verify(writePort,org.mockito.Mockito.times(1)).perform(any());
        }

        @Test
        @DisplayName("an acceptance with a task handle waits for the platform")
        void acceptedWithATaskWaits() {
            claim("PENDING");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, "task-1"));

            assertThat(worker.runOnce(Instant.now(), 10)).isEqualTo(1);
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("PLATFORM_PENDING"), any(), any(),
                    any());
            verify(commands).deferObservation(eq(COMMAND), eq(1L), anyString(), anyInt());
        }

        @Test
        @DisplayName("an acceptance with no handle is read back before it counts as success")
        void acceptedWithoutATaskObserves() {
            claim("PENDING");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, null));

            worker.runOnce(Instant.now(), 10);

            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("READBACK_PENDING"), any(), any(),
                    any());
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("READBACK_MATCHED"), any(), any(),
                    eq(ATTEMPT));
        }

        @Test
        @DisplayName("a refusal is final and carries the reason")
        void refusalIsFinal() {
            claim("PENDING");
            when(writePort.perform(any())).thenReturn(DescriptionWriteResult.refusedBeforeDispatch(
                    "non_target_field_present", Instant.now()));

            worker.runOnce(Instant.now(), 10);

            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("FAILED_FINAL"),
                    eq("non_target_field_present"), any(), any());
        }

        @Test
        @DisplayName("a retriable error reads back rather than retrying")
        void retriableErrorObserves() {
            claim("PENDING");
            when(writePort.perform(any())).thenReturn(new DescriptionWriteResult(
                    DescriptionWriteResult.Outcome.RETRIABLE_ERROR, "503", null, null, Instant.now(),
                    "rate_limited", 120, null));

            worker.runOnce(Instant.now(), 10);

            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("READBACK_PENDING"), any(), any(),
                    any());
            verify(commands, never()).transition(eq(COMMAND), anyLong(), anyString(), eq("RETRY_WAIT"), any(),
                    any(), any());
        }

        @Test
        @DisplayName("a timeout and an unclassifiable answer both mean unknown")
        void timeoutAndUnknownAreTheSame() {
            for (var kind : List.of(DescriptionWriteResult.Outcome.TIMEOUT,
                    DescriptionWriteResult.Outcome.UNKNOWN_STATE)) {
                claim("PENDING");
                when(writePort.perform(any())).thenReturn(new DescriptionWriteResult(kind, null, null, null,
                        Instant.now(), "provider_did_not_answer", null, null));

                worker.runOnce(Instant.now(), 10);
            }
            verify(commands, org.mockito.Mockito.times(2)).transition(eq(COMMAND), anyLong(), anyString(),
                    eq("UNKNOWN_REQUIRES_READBACK"), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("TC-LC-WORKER-002 every call carries the one verified attribute and the declared marking")
    class RequestShape {

        @Test
        @DisplayName("the apply request names the attribute key, the exact text and the declaration")
        void applyCarriesTheAttribute() {
            claim("PENDING");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, "task-1"));

            worker.runOnce(Instant.now(), 10);

            ArgumentCaptor<DescriptionWriteRequest> request = ArgumentCaptor.forClass(DescriptionWriteRequest.class);
            verify(writePort).perform(request.capture());
            assertThat(request.getValue().operation()).isEqualTo(DescriptionWriteRequest.Operation.APPLY);
            assertThat(request.getValue().descriptionAttributeKey()).isEqualTo("4191");
            assertThat(request.getValue().descriptionText()).isEqualTo(TARGET);
            assertThat(request.getValue().kizMarkedDeclared()).isFalse();
            assertThat(request.getValue().credentialId()).isEqualTo(CREDENTIAL);
        }

        @Test
        @DisplayName("an absent credential stops before any attempt or call")
        void absentCredentialStops() {
            claim("PENDING");
            when(credentials.writeCredential(any(), any())).thenReturn(Optional.empty());

            worker.runOnce(Instant.now(), 10);

            verify(commands, never()).openAttempt(any(), any(), anyString(), anyLong(), anyString(), anyString(),
                    any());
            verify(writePort, never()).perform(any());
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("FAILED_FINAL"),
                    eq("credential_reference_absent"), any(), any());
        }

        @Test
        @DisplayName("an answer naming a different request is no evidence about this one")
        void unboundAnswerIsUnknown() {
            claim("PENDING");
            when(writePort.perform(any())).thenReturn(new DescriptionWriteResult(
                    DescriptionWriteResult.Outcome.ACCEPTED, "200", null, new byte[] {1}, Instant.now(), null, null,
                    new DescriptionWriteResult.Response(200, Map.of(), "f".repeat(64), "PROVIDER_RESPONSE", true)));

            worker.runOnce(Instant.now(), 10);

            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("UNKNOWN_REQUIRES_READBACK"), any(),
                    any(), any());
        }
    }

    @Nested
    @DisplayName("TC-LC-WORKER-003 an unknown result is observed, never repeated")
    class Unknown {

        @Test
        @DisplayName("the only operation after an unknown is a readback")
        void unknownObserves() {
            claim("UNKNOWN_REQUIRES_READBACK");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, null));

            worker.runOnce(Instant.now(), 10);

            ArgumentCaptor<DescriptionWriteRequest> request = ArgumentCaptor.forClass(DescriptionWriteRequest.class);
            verify(writePort).perform(request.capture());
            assertThat(request.getValue().operation()).isEqualTo(DescriptionWriteRequest.Operation.READBACK);
            verify(commands, never()).lease(eq(COMMAND), anyString(), anyInt());
        }

        @Test
        @DisplayName("a third text routes to investigation, never to compensation")
        void thirdTextRoutesToInvestigation() {
            claim("UNKNOWN_REQUIRES_READBACK");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, null));
            when(commands.transitionReadback(any(), any(), anyLong(), anyString())).thenReturn("DIFFERENT");

            worker.runOnce(Instant.now(), 10);

            verify(commands).transition(eq(COMMAND), anyLong(), anyString(),
                    eq("LATER_CHANGE_OR_MISMATCH_INVESTIGATION"), any(), any(), any());
        }

        @Test
        @DisplayName("a retry after a prior-matching readback needs the database's own proof")
        void retryNeedsProof() {
            claim("UNKNOWN_REQUIRES_READBACK");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, null));
            when(commands.transitionReadback(any(), any(), anyLong(), anyString())).thenReturn("MATCHES_PRIOR");
            when(commands.retryIsProven(COMMAND)).thenReturn(false);

            worker.runOnce(Instant.now(), 10);

            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("READBACK_MISMATCH"), any(), any(),
                    any());
            verify(commands, never()).transition(eq(COMMAND), anyLong(), anyString(), eq("RETRY_WAIT"), any(),
                    any(), any());
        }
    }

    @Nested
    @DisplayName("TC-LC-WORKER-004 restoration uses a new action and current conditional preflight")
    class Restoration {
        @Test
        void legacyCompensationNeverDispatchesEvenWithCapturedPrior() {
            claim("COMPENSATION_PENDING");
            worker.runOnce(Instant.now(), 10);
            verify(writePort, never()).perform(any());
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("MANUAL_RESOLUTION"),
                    eq("new_restoration_action_required"), any(), any());
        }

        @Test
        void newRestorationWritesItsOwnApprovedTargetAndCurrentVersion() {
            claim("PENDING");
            when(commands.isExactRestoration(COMMAND)).thenReturn(true);
            when(commands.restoreVersionToken(COMMAND)).thenReturn(Optional.of("\"version-2\""));
            when(credentials.restorationAttributeKey(any())).thenReturn(Optional.of("description-field"));
            when(commands.transitionReadback(any(), any(), anyLong(), anyString()))
                    .thenReturn("MATCHES_PRIOR", "MATCHES_TARGET");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, null));
            worker.runOnce(Instant.now(), 10);
            ArgumentCaptor<DescriptionWriteRequest> requests=ArgumentCaptor.forClass(DescriptionWriteRequest.class);
            verify(writePort, org.mockito.Mockito.times(3)).perform(requests.capture());
            assertThat(requests.getAllValues()).extracting(DescriptionWriteRequest::operation).containsExactly(
                    DescriptionWriteRequest.Operation.READBACK, DescriptionWriteRequest.Operation.RESTORE,
                    DescriptionWriteRequest.Operation.READBACK);
            DescriptionWriteRequest mutation=requests.getAllValues().get(1);
            assertThat(mutation.descriptionText()).isEqualTo(TARGET);
            assertThat(mutation.expectedVersionToken()).isEqualTo("\"version-2\"");
            assertThat(mutation.descriptionAttributeKey()).isEqualTo("description-field");
            assertThat(mutation.idempotencyKey()).isEqualTo(DescriptionWriteRequest.operationIdempotencyKey(
                    DescriptionWriteRequest.Operation.RESTORE,"lcd-"+"0".repeat(32)));
            verify(commands).transition(eq(COMMAND),anyLong(),anyString(),eq("READBACK_MATCHED"),any(),any(),any());
        }

        @Test
        void laterLegitimateTextPreventsRestoration() {
            claim("PENDING");
            when(commands.isExactRestoration(COMMAND)).thenReturn(true);
            when(commands.transitionReadback(any(), any(), anyLong(), anyString())).thenReturn("DIFFERENT");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, null));
            worker.runOnce(Instant.now(), 10);
            verify(writePort).perform(org.mockito.ArgumentMatchers.argThat(r -> r.operation()==DescriptionWriteRequest.Operation.READBACK));
            verify(commands).transition(eq(COMMAND),anyLong(),anyString(),eq("LATER_CHANGE_OR_MISMATCH_INVESTIGATION"),any(),any(),any());
        }

        @Test
        void MissingConditionalVersionPreventsRestoration() {
            claim("PENDING");
            when(commands.isExactRestoration(COMMAND)).thenReturn(true);
            when(commands.transitionReadback(any(), any(), anyLong(), anyString())).thenReturn("MATCHES_PRIOR");
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, null));
            worker.runOnce(Instant.now(), 10);
            verify(writePort).perform(org.mockito.ArgumentMatchers.argThat(r -> r.operation()==DescriptionWriteRequest.Operation.READBACK));
            verify(commands,never()).transition(eq(COMMAND),anyLong(),anyString(),eq("EXECUTING"),any(),any(),any());
        }
    }

    @Nested
    @DisplayName("TC-LC-WORKER-005 a platform's pending task is polled, never re-applied")
    class Pending {

        @Test
        @DisplayName("a still-pending task is deferred with the platform's own wait")
        void pendingTaskIsDeferred() {
            claim("PLATFORM_PENDING");
            when(commands.leaseStatus(eq(COMMAND), anyString(), anyInt())).thenReturn(2L);
            when(commands.nativeTaskKey(COMMAND)).thenReturn(Optional.of("task-original"));
            when(writePort.perform(any())).thenReturn(new DescriptionWriteResult(
                    DescriptionWriteResult.Outcome.RETRIABLE_ERROR, "202", "task-original", null, Instant.now(),
                    "task_pending", 300, null));

            worker.runOnce(Instant.now(), 10);

            ArgumentCaptor<DescriptionWriteRequest> request = ArgumentCaptor.forClass(DescriptionWriteRequest.class);
            verify(writePort).perform(request.capture());
            assertThat(request.getValue().operation()).isEqualTo(DescriptionWriteRequest.Operation.STATUS_ENQUIRY);
            assertThat(request.getValue().nativeTaskKey()).isEqualTo("task-original");
            verify(commands).deferObservation(eq(COMMAND), eq(2L), anyString(), eq(300));
            verify(commands, never()).lease(eq(COMMAND), anyString(), anyInt());
        }

        @Test
        @DisplayName("an inconclusive status enquiry keeps polling the same task and never re-applies")
        void inconclusiveStatusEnquiryKeepsPollingTheSameTask() {
            claim("PLATFORM_PENDING");
            when(commands.nativeTaskKey(COMMAND)).thenReturn(Optional.of("task-original"));
            when(writePort.perform(any())).thenReturn(
                    new DescriptionWriteResult(DescriptionWriteResult.Outcome.TIMEOUT, null, null, null,
                            Instant.now(), "provider_did_not_answer", null, null),
                    new DescriptionWriteResult(DescriptionWriteResult.Outcome.UNKNOWN_STATE, null, null, null,
                            Instant.now(), "provider_did_not_answer", null, null),
                    new DescriptionWriteResult(DescriptionWriteResult.Outcome.ACCEPTED, "200", null, null,
                            Instant.now(), null, null, null));

            for (int pass = 0; pass < 3; pass++) {
                assertThat(worker.runOnce(Instant.now(), 10)).isEqualTo(1);
            }

            ArgumentCaptor<DescriptionWriteRequest> request = ArgumentCaptor.forClass(DescriptionWriteRequest.class);
            verify(writePort, org.mockito.Mockito.times(3)).perform(request.capture());
            assertThat(request.getAllValues()).extracting(DescriptionWriteRequest::operation)
                    .containsOnly(DescriptionWriteRequest.Operation.STATUS_ENQUIRY);
            assertThat(request.getAllValues()).extracting(DescriptionWriteRequest::nativeTaskKey)
                    .containsOnly("task-original");
            verify(commands, org.mockito.Mockito.times(3)).deferObservation(eq(COMMAND), eq(1L), anyString(),
                    eq(properties.getRetryDelaySeconds()));
            verify(commands, never()).lease(eq(COMMAND), anyString(), anyInt());
            verify(commands, never()).transition(any(), anyLong(), anyString(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("a resolved task must still be read back before success")
        void resolvedTaskReadsBack() {
            claim("PLATFORM_PENDING");
            when(commands.nativeTaskKey(COMMAND)).thenReturn(Optional.of("task-original"));
            when(writePort.perform(any())).thenAnswer(answering(DescriptionWriteResult.Outcome.ACCEPTED, null));

            worker.runOnce(Instant.now(), 10);

            ArgumentCaptor<DescriptionWriteRequest> request = ArgumentCaptor.forClass(DescriptionWriteRequest.class);
            verify(writePort, org.mockito.Mockito.times(2)).perform(request.capture());
            assertThat(request.getAllValues()).extracting(DescriptionWriteRequest::operation)
                    .containsExactly(DescriptionWriteRequest.Operation.STATUS_ENQUIRY,
                            DescriptionWriteRequest.Operation.READBACK);
            verify(commands).transition(eq(COMMAND), anyLong(), anyString(), eq("READBACK_MATCHED"), any(), any(),
                    any());
        }
    }

    @Nested
    @DisplayName("TC-LC-WORKER-006 a refusal from the database is not a failure of the pass")
    class Refusals {

        @Test
        @DisplayName("a command the gate will not lease is skipped, and the pass continues")
        void refusedLeaseIsSkipped() {
            claim("PENDING");
            when(commands.lease(eq(COMMAND), anyString(), anyInt()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "the description write gate is closed: PRODUCTION_WRITE_DISABLED"));

            assertThat(worker.runOnce(Instant.now(), 10)).isZero();
            verify(writePort, never()).perform(any());
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
