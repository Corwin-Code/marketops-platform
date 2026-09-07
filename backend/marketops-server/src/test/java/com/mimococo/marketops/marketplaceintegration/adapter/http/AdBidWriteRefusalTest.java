package com.mimococo.marketops.marketplaceintegration.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthValueSource;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWritePort;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult;
import com.mimococo.marketops.shared.Money;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The advertising write adapter refuses before it dispatches.
 *
 * <p>No provider, no DNS and no real credential is used. Every fixture below is
 * synthetic, and the point of each case is the same: a missing piece of recorded
 * verification is a refusal that never reaches a socket, not an exception and
 * not an unknown state. That distinction is what makes an unverified provider
 * path unreachable rather than merely switched off.
 */
class AdBidWriteRefusalTest {

    private static final UUID CAPABILITY = UUID.randomUUID();
    private static final UUID CREDENTIAL = UUID.randomUUID();
    private static final UUID ENDPOINT = UUID.randomUUID();

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
    private final OutboundHttp http = mock(OutboundHttp.class);
    private final PlatformCallSpecRepository specs = mock(PlatformCallSpecRepository.class);
    private final WriteOperationRepository operations = mock(WriteOperationRepository.class);
    private final SecretResolverPort secrets = mock(SecretResolverPort.class);

    private final AdBidWritePort adapter =
            new PlatformHttpAdBidWriteAdapter(operations, specs, secrets, http, clock);

    @BeforeEach
    void everythingVerified() {
        when(specs.adBidAttemptCurrent(any())).thenReturn(true);
        when(operations.verifiedOperation(any(), anyString())).thenAnswer(call ->
                Optional.of(new WriteOperationSpec(CAPABILITY, "FIXTURE", call.getArgument(1),
                        "SYNCHRONOUS", "{\"bid\":\"{targetBid}\"}", null, null, null, null,
                        "/bid", "/currency", endpoint(), null)));
        when(specs.verifiedAuthHeaders(eq("FIXTURE"), anyString())).thenReturn(List.of(
                new AuthHeaderSpec("Authorization", AuthValueSource.RESOLVED_SECRET,
                        "Bearer {value}", "ADS_WRITE", 1)));
        when(specs.activeSecretReference(eq(CREDENTIAL), anyString()))
                .thenReturn(Optional.of("secret-ref://fixture/ads-write"));
        when(secrets.resolve("secret-ref://fixture/ads-write"))
                .thenAnswer(call -> Optional.of("synthetic-test-value".toCharArray()));
        when(http.prepare(any())).thenReturn(mock(OutboundHttp.Plan.class));
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-001 an unverified capability never reaches a socket")
    void unverifiedOperationRefusesBeforeDispatch() {
        when(operations.verifiedOperation(any(), anyString())).thenReturn(Optional.empty());

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.REJECTED);
        assertThat(result.errorCode()).isEqualTo("write_operation_not_verified");
        assertThat(result.response()).isNull();
        verifyNoInteractions(http, secrets);
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-002 a platform with no recorded advertising auth is unreachable")
    void unrecordedAuthenticationRefusesBeforeDispatch() {
        when(specs.verifiedAuthHeaders(eq("FIXTURE"), anyString())).thenReturn(List.of());

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.errorCode()).isEqualTo("authentication_not_recorded");
        verifyNoInteractions(http, secrets);
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-003 a credential absent from this environment is a refusal")
    void unresolvableCredentialRefusesBeforeDispatch() {
        // This is the case that holds in every environment this code runs in
        // today: nothing has an advertising secret, so nothing can be sent.
        when(specs.activeSecretReference(eq(CREDENTIAL), anyString())).thenReturn(Optional.empty());

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.errorCode()).isEqualTo("credential_unresolvable");
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-004 an outbound destination the policy declines is a refusal, not an unknown")
    void declinedDestinationIsARefusal() {
        when(http.prepare(any())).thenThrow(new IllegalArgumentException("synthetic denied host"));

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.REJECTED);
        assertThat(result.errorCode()).isEqualTo("outbound_destination_refused");
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-005 a stale attempt is refused before anything is prepared")
    void staleAttemptRefusesBeforeAnyPreparation() {
        when(specs.adBidAttemptCurrent(any())).thenReturn(false);

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.errorCode()).isEqualTo("attempt_authority_not_current");
        verifyNoInteractions(http, secrets, operations);
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-006 an attempt that goes stale during preparation still stops the call")
    void staleAttemptAtTheSocketStopsTheCall() throws Exception {
        // A kill switch thrown while the destination was being built. The
        // command is still leased, the headers are already resolved, and the
        // call must not happen anyway.
        when(specs.adBidAttemptCurrent(any())).thenReturn(true, false);

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.REJECTED);
        assertThat(result.errorCode()).isEqualTo("attempt_authority_not_current");
        // Building the destination is local work. Exchanging it is the socket,
        // and that is the thing that must not have happened.
        verify(http, never()).exchange(any(), any());
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-007 a refusal is never mistaken for an unknown state")
    void everyRefusalIsRejectedRatherThanUnknown() {
        // The whole point. An unknown state obliges a readback and blocks a
        // retry; a refusal means nothing happened and the command is free.
        for (Runnable brokenFixture : List.<Runnable>of(
                () -> when(operations.verifiedOperation(any(), anyString()))
                        .thenReturn(Optional.empty()),
                () -> when(specs.verifiedAuthHeaders(eq("FIXTURE"), anyString()))
                        .thenReturn(List.of()),
                () -> when(specs.activeSecretReference(eq(CREDENTIAL), anyString()))
                        .thenReturn(Optional.empty()),
                () -> when(specs.adBidAttemptCurrent(any())).thenReturn(false))) {
            everythingVerified();
            brokenFixture.run();

            assertThat(adapter.perform(apply()).outcome())
                    .isEqualTo(AdBidWriteResult.Outcome.REJECTED);
        }
    }

    private static AdBidWriteRequest apply() {
        return new AdBidWriteRequest(AdBidWriteRequest.Operation.APPLY, CAPABILITY, CREDENTIAL,
                "campaign-fixture", "object-fixture",
                Money.of(new BigDecimal("31.50"), "RUB"), "CURRENCY_MAJOR",
                "fixture-idempotency-key", null, null, UUID.randomUUID());
    }

    private static EndpointCallSpec endpoint() {
        return new EndpointCallSpec(ENDPOINT, "FIXTURE", "AD_BID_APPLY",
                "https://fixture.invalid", "POST", "/bid", null, null,
                "application/json", null, "SINGLE_RESPONSE", 60, 5000, 65536L);
    }
}
