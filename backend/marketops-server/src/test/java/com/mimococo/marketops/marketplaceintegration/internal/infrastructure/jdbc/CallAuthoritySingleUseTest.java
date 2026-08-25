package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import com.mimococo.marketops.marketplaceintegration.port.RecordedAcquisitionPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Semantic proof for the internal, non-transferable, one-shot authority value. */
class CallAuthoritySingleUseTest {

    private static final UUID DECISION = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ENDPOINT = UUID.randomUUID();
    private static final UUID CREDENTIAL = UUID.randomUUID();
    private static final UUID SCOPE_GRANT = UUID.randomUUID();
    private static final Instant GRANTED = Instant.parse("2026-08-25T00:00:00Z");
    private static final Instant SERVER_DEADLINE = GRANTED.plusSeconds(30);
    private static final Instant LEASE_DEADLINE = GRANTED.plusSeconds(60);
    private static final String DIGEST = "a".repeat(64);

    @Test
    @DisplayName("TC-PORT-001 the executor copies every field from the consumed grant")
    void executorCopiesTheCompleteGrant() {
        CallAuthorityGrant grant = grant(1L, 1, ENDPOINT, SERVER_DEADLINE);
        RecordedAcquisitionPort port = recordedPort();
        AuthorizedAcquisitionExecutor executor = executor(port);

        assertThatCode(() -> executor.execute(grant)).doesNotThrowAnyException();

        AcquisitionRequest request = port.recorded().getFirst();
        assertThat(request.decisionId()).isEqualTo(DECISION);
        assertThat(request.jobId()).isEqualTo(JOB);
        assertThat(request.runId()).isEqualTo(RUN);
        assertThat(request.endpointId()).isEqualTo(ENDPOINT);
        assertThat(request.credentialId()).isEqualTo(CREDENTIAL);
        assertThat(request.scopeGrantId()).isEqualTo(SCOPE_GRANT);
        assertThat(request.fenceToken()).isEqualTo(1L);
        assertThat(request.callSeq()).isEqualTo(1);
        assertThat(request.runLeaseExpiresAt()).isEqualTo(LEASE_DEADLINE);
        assertThat(request.serverPolicyDeadline()).isEqualTo(SERVER_DEADLINE);
        assertThat(request.boundarySetDigest()).isEqualTo(DIGEST);
    }

    @Test
    @DisplayName("TC-PORT-002 a grant without an endpoint identity is refused")
    void endpointlessGrantIsRefused() {
        assertThatThrownBy(() -> grant(1L, 1, null, SERVER_DEADLINE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("endpointId");
    }

    @Test
    @DisplayName("TC-PORT-003 malformed sequence, lease and policy envelopes are refused")
    void malformedGrantIsRefused() {
        assertThatThrownBy(() -> grant(0L, 1, ENDPOINT, SERVER_DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grant(1L, 0, ENDPOINT, SERVER_DEADLINE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grant(1L, 1, ENDPOINT, GRANTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expire after");
        assertThatThrownBy(() -> new CallAuthorityGrant(
                DECISION, JOB, RUN, 1L, "worker-a", "OZON", ENDPOINT, CREDENTIAL,
                SCOPE_GRANT, 1, GRANTED, GRANTED.plusSeconds(31), LEASE_DEADLINE,
                SERVER_DEADLINE, scopes(), List.of(1L, 1L, 1L, 1L), DIGEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server policy");
    }

    @Test
    @DisplayName("TC-PORT-004 the executor refuses an expired structured grant")
    void executorRefusesExpiredGrant() {
        RecordedAcquisitionPort port = recordedPort();
        AuthorizedAcquisitionExecutor executor = new AuthorizedAcquisitionExecutor(
                port, Clock.fixed(GRANTED.plusSeconds(31), ZoneOffset.UTC));

        assertThatThrownBy(() -> executor.execute(
                grant(1L, 1, ENDPOINT, SERVER_DEADLINE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
        assertThat(port.recorded()).isEmpty();
    }

    @Test
    @DisplayName("TC-PORT-005 the same legitimate grant executes only once sequentially")
    void sameGrantExecutesOnlyOnceSequentially() {
        CallAuthorityGrant grant = grant(1L, 1, ENDPOINT, SERVER_DEADLINE);
        RecordedAcquisitionPort port = recordedPort();
        AuthorizedAcquisitionExecutor executor = executor(port);

        assertThatCode(() -> executor.execute(grant)).doesNotThrowAnyException();
        assertThatThrownBy(() -> executor.execute(grant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already consumed");
        assertThat(port.recorded()).hasSize(1);
    }

    @Test
    @DisplayName("TC-PORT-006 concurrent use of one grant invokes the port exactly once")
    void sameGrantExecutesOnlyOnceConcurrently() throws Exception {
        CallAuthorityGrant grant = grant(1L, 1, ENDPOINT, SERVER_DEADLINE);
        AtomicInteger invocations = new AtomicInteger();
        AcquisitionPort port = request -> {
            invocations.incrementAndGet();
            return result();
        };
        AuthorizedAcquisitionExecutor executor = executor(port);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = callers.submit(() -> attempt(start, executor, grant));
            Future<Boolean> second = callers.submit(() -> attempt(start, executor, grant));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(invocations).hasValue(1);
    }

    private static boolean attempt(
            CountDownLatch start,
            AuthorizedAcquisitionExecutor executor,
            CallAuthorityGrant grant) throws InterruptedException {
        start.await();
        try {
            executor.execute(grant);
            return true;
        } catch (IllegalStateException alreadyConsumed) {
            return false;
        }
    }

    private static AuthorizedAcquisitionExecutor executor(AcquisitionPort port) {
        return new AuthorizedAcquisitionExecutor(
                port, Clock.fixed(GRANTED.plusSeconds(1), ZoneOffset.UTC));
    }

    private static RecordedAcquisitionPort recordedPort() {
        return new RecordedAcquisitionPort(
                "{}", "OK", AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
    }

    private static AcquisitionResult result() {
        return new AcquisitionResult(
                new byte[0], "OK", AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES, GRANTED);
    }

    private static CallAuthorityGrant grant(
            long fenceToken, int callSeq, UUID endpointId, Instant expiresAt) {
        return new CallAuthorityGrant(
                DECISION, JOB, RUN, fenceToken, "worker-a", "OZON", endpointId,
                CREDENTIAL, SCOPE_GRANT, callSeq, GRANTED, expiresAt,
                LEASE_DEADLINE, SERVER_DEADLINE, scopes(),
                List.of(1L, 1L, 1L, 1L), DIGEST);
    }

    private static List<String> scopes() {
        return List.of("JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT");
    }
}
