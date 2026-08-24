package com.mimococo.marketops.marketplaceintegration.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The request can exist only as the exact identity-bound database grant. */
class AcquisitionRequestTest {

    private static final UUID DECISION = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ENDPOINT = UUID.randomUUID();
    private static final UUID CREDENTIAL = UUID.randomUUID();
    private static final UUID SCOPE_GRANT = UUID.randomUUID();
    private static final Instant GRANTED = Instant.parse("2026-08-25T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    @DisplayName("TC-PORT-001 the sole executor copies every identity from the structured grant")
    void executorCopiesTheCompleteGrant() {
        CallAuthorityGrant grant = grant(1L, 1, ENDPOINT, GRANTED.plusSeconds(30));
        RecordedAcquisitionPort port = new RecordedAcquisitionPort(
                "{}", "OK", AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
        AuthorizedAcquisitionExecutor executor = new AuthorizedAcquisitionExecutor(
                port, Clock.fixed(GRANTED.plusSeconds(1), ZoneOffset.UTC));

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
        assertThat(request.boundarySetDigest()).isEqualTo(DIGEST);
    }

    @Test
    @DisplayName("TC-PORT-002 a grant without an endpoint identity is refused")
    void endpointlessGrantIsRefused() {
        assertThatThrownBy(() -> grant(1L, 1, null, GRANTED.plusSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("endpointId");
    }

    @Test
    @DisplayName("TC-PORT-003 nonpositive fence/sequence and empty authority are refused")
    void malformedGrantIsRefused() {
        assertThatThrownBy(() -> grant(0L, 1, ENDPOINT, GRANTED.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grant(1L, 0, ENDPOINT, GRANTED.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grant(1L, 1, ENDPOINT, GRANTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expire after");
    }

    @Test
    @DisplayName("TC-PORT-004 the sole executor refuses an expired structured grant")
    void executorRefusesExpiredGrant() {
        RecordedAcquisitionPort port = new RecordedAcquisitionPort(
                "{}", "OK", AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
        AuthorizedAcquisitionExecutor executor = new AuthorizedAcquisitionExecutor(
                port, Clock.fixed(GRANTED.plusSeconds(31), ZoneOffset.UTC));

        assertThatThrownBy(() -> executor.execute(
                grant(1L, 1, ENDPOINT, GRANTED.plusSeconds(30))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
        assertThat(port.recorded()).isEmpty();
    }

    private static CallAuthorityGrant grant(
            long fenceToken, int callSeq, UUID endpointId, Instant expiresAt) {
        return new CallAuthorityGrant(
                DECISION, JOB, RUN, fenceToken, "worker-a", "OZON", endpointId,
                CREDENTIAL, SCOPE_GRANT, callSeq, GRANTED, expiresAt,
                List.of("JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT"),
                List.of(1L, 1L, 1L, 1L), DIGEST);
    }
}
