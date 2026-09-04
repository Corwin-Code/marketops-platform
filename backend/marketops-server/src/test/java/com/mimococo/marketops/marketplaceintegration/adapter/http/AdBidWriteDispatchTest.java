package com.mimococo.marketops.marketplaceintegration.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What the advertising write adapter does once an answer arrives.
 *
 * <p>Its sibling {@link AdBidWriteRefusalTest} covers everything that stops
 * before a socket. This one covers the other half, and the recurring theme is
 * that the adapter proposes rather than decides: a status the platform meant as
 * a refusal arrives here as an unknown state, because only the database
 * re-classifies from the frozen operation shape. Getting that backwards would
 * let an adapter close a command on a body nobody had checked.
 *
 * <p>No provider is contacted. The transport is a mock, every value is
 * synthetic, and the "secret" is a literal test string that the last case
 * proves is zeroed on the way out.
 */
class AdBidWriteDispatchTest {

    private static final UUID CAPABILITY = UUID.randomUUID();
    private static final UUID CREDENTIAL = UUID.randomUUID();
    private static final UUID ENDPOINT = UUID.randomUUID();
    private static final byte[] BODY = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);
    private final OutboundHttp http = mock(OutboundHttp.class);
    private final PlatformCallSpecRepository specs = mock(PlatformCallSpecRepository.class);
    private final WriteOperationRepository operations = mock(WriteOperationRepository.class);
    private final SecretResolverPort secrets = mock(SecretResolverPort.class);
    private final List<char[]> handedOut = new ArrayList<>();

    private final AdBidWritePort adapter =
            new PlatformHttpAdBidWriteAdapter(operations, specs, secrets, http, clock);

    private String requestTemplate = "{\"bid\":\"{targetBid}\"}";
    private String pathTemplate = "/ads/object/{nativeObjectKey}/bid";
    private String queryTemplate = null;

    @BeforeEach
    void everythingVerified() {
        when(specs.adBidAttemptCurrent(any())).thenReturn(true);
        when(operations.verifiedOperation(any(), anyString())).thenAnswer(call ->
                Optional.of(new WriteOperationSpec(CAPABILITY, "FIXTURE", call.getArgument(1),
                        "SYNCHRONOUS", requestTemplate, null, null, null, null,
                        "/bid", "/currency", endpoint(), null)));
        when(specs.verifiedAuthHeaders(eq("FIXTURE"), anyString())).thenReturn(List.of(
                new AuthHeaderSpec("Authorization", AuthValueSource.RESOLVED_SECRET,
                        "Bearer {value}", "ADS_WRITE", 1)));
        when(specs.activeSecretReference(eq(CREDENTIAL), anyString()))
                .thenReturn(Optional.of("secret-ref://fixture/ads-write"));
        when(secrets.resolve("secret-ref://fixture/ads-write")).thenAnswer(call -> {
            char[] material = "synthetic-test-value".toCharArray();
            handedOut.add(material);
            return Optional.of(material);
        });
        when(http.prepare(any())).thenReturn(mock(OutboundHttp.Plan.class));
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-008 an answered call carries the exact bytes tied to the exact request")
    void anAnsweredCallCarriesItsOwnEvidence() throws Exception {
        answerWith(200, BODY, Map.of("content-type", List.of("application/json")), true);
        AdBidWriteRequest request = apply();

        AdBidWriteResult result = adapter.perform(request);

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.ACCEPTED);
        assertThat(result.body()).isEqualTo(BODY);
        assertThat(result.errorCode()).isNull();
        assertThat(result.response()).isNotNull();
        assertThat(result.response().httpStatus()).isEqualTo(200);
        assertThat(result.response().evidenceClass()).isEqualTo("PROVIDER_RESPONSE");
        // The one fact that makes the bytes evidence about this call rather
        // than about some other call that happened to answer.
        assertThat(result.response().requestDigest()).isEqualTo(request.digest());
        assertThat(result.response().headers()).containsEntry("content-type", "application/json");
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-009 only the recorded allowlist survives into the evidence record")
    void headersOutsideTheAllowlistAreNotRetained() throws Exception {
        answerWith(201, BODY, Map.of(
                "content-type", List.of("application/json"),
                "x-request-id", List.of("req-1"),
                // Not on the list, and a name that would be worth exfiltrating.
                "set-cookie", List.of("session=abc"),
                // On the list, but longer than an evidence record may keep.
                "etag", List.of("e".repeat(257)),
                // Present with no values at all, which is not a header value.
                "retry-after", List.of()), true);

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.response().headers())
                .containsOnlyKeys("content-type", "x-request-id");
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-010 an incomplete body can never be an acceptance")
    void truncatedBytesAreNeverAccepted() throws Exception {
        // A prefix of a success is not a success. The status says 200 and the
        // adapter must still refuse to call it one.
        answerWith(200, BODY, Map.of(), false);

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.UNKNOWN_STATE);
        assertThat(result.response().complete()).isFalse();
        assertThat(result.body()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-011 a status the platform meant as a refusal is an unknown, not a rejection")
    void aRefusingStatusIsStillAnUnknownHere() throws Exception {
        answerWith(409, BODY, Map.of(), true);

        AdBidWriteResult result = adapter.perform(apply());

        // REJECTED here would mean "nothing happened", and a 409 does not say
        // that. Only the database may turn these bytes into a refusal.
        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.UNKNOWN_STATE);
        assertThat(result.nativeStatus()).isEqualTo("409");
        assertThat(result.response()).isNotNull();
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-012 an answer that cannot be evidence is an unknown state, not a thrown exception")
    void anUnusableAnswerDoesNotEscapeThePort() throws Exception {
        // A status outside 100..599 cannot be an HTTP status, so the evidence
        // record refuses to exist. The port must still return a value.
        answerWith(0, BODY, Map.of(), true);

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.UNKNOWN_STATE);
        assertThat(result.errorCode()).isEqualTo("provider_evidence_missing_or_unbound");
        assertThat(result.response()).isNull();
        assertThat(result.body()).isNull();
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-013 a literal header needs no secret at all")
    void aLiteralHeaderResolvesWithoutTheSecretPort() throws Exception {
        when(specs.verifiedAuthHeaders(eq("FIXTURE"), anyString())).thenReturn(List.of(
                new AuthHeaderSpec("Content-Type", AuthValueSource.LITERAL,
                        "application/json", "ADS_WRITE", 1)));
        answerWith(200, BODY, Map.of(), true);

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.ACCEPTED);
        verify(secrets, never()).resolve(anyString());
        assertThat(sentHeaders()).containsEntry("Content-Type", "application/json");
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-014 an account-native-key header takes the account's own identifier")
    void anAccountKeyHeaderComesFromTheCredentialsAccount() throws Exception {
        when(specs.verifiedAuthHeaders(eq("FIXTURE"), anyString())).thenReturn(List.of(
                new AuthHeaderSpec("Client-Id", AuthValueSource.ACCOUNT_NATIVE_KEY,
                        "{value}", "ADS_WRITE", 1)));
        when(specs.accountNativeKey(CREDENTIAL)).thenReturn(Optional.of("account-fixture"));
        answerWith(200, BODY, Map.of(), true);

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.ACCEPTED);
        assertThat(sentHeaders()).containsEntry("Client-Id", "account-fixture");
        verify(secrets, never()).resolve(anyString());
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-015 an account whose native key is not recorded is a refusal")
    void anUnrecordedAccountKeyRefusesBeforeDispatch() {
        when(specs.verifiedAuthHeaders(eq("FIXTURE"), anyString())).thenReturn(List.of(
                new AuthHeaderSpec("Client-Id", AuthValueSource.ACCOUNT_NATIVE_KEY,
                        "{value}", "ADS_WRITE", 1)));
        when(specs.accountNativeKey(CREDENTIAL)).thenReturn(Optional.empty());

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.REJECTED);
        assertThat(result.errorCode()).isEqualTo("credential_unresolvable");
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-016 a template naming something an advertising request does not carry is refused")
    void anUnrenderableTemplateRefusesBeforeDispatch() {
        // The price vocabulary. The advertising placeholders deliberately do
        // not include it, so a registry row that names it cannot be sent.
        requestTemplate = "{\"price\":\"{targetPrice}\"}";

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.REJECTED);
        assertThat(result.errorCode()).isEqualTo("request_could_not_be_built");
        verify(http, never()).prepare(any());
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-017 a provider that does not answer is an unknown state, never a refusal")
    void aTransportFailureIsAnUnknownState() throws Exception {
        when(http.exchange(any(), any())).thenThrow(new IOException("synthetic transport failure"));

        AdBidWriteResult result = adapter.perform(apply());

        // Something may have left this process. Calling that a refusal would
        // free the command to be sent a second time.
        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.UNKNOWN_STATE);
        assertThat(result.errorCode()).isEqualTo("provider_did_not_answer");
        assertThat(result.response()).isNull();
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-018 an interrupted call is an unknown state and leaves the interrupt flag set")
    void anInterruptedCallRestoresTheFlag() throws Exception {
        when(http.exchange(any(), any())).thenThrow(new InterruptedException("synthetic shutdown"));

        AdBidWriteResult result = adapter.perform(apply());

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.UNKNOWN_STATE);
        assertThat(result.errorCode()).isEqualTo("provider_did_not_answer");
        // Swallowing the interrupt would leave a shutting-down worker running.
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-019 the destination is built from the recorded shape and nothing else")
    void theDestinationComesFromTheRegistry() throws Exception {
        queryTemplate = "campaign={nativeCampaignKey}";
        answerWith(200, BODY, Map.of(), true);

        adapter.perform(apply());

        OutboundHttp.Destination destination = destination();
        assertThat(destination.uri()).hasToString(
                "https://fixture.invalid/ads/object/object-fixture/bid?campaign=campaign-fixture");
        assertThat(destination.policyKey()).isEqualTo("FIXTURE:AD_BID_APPLY");
        assertThat(destination.method()).isEqualTo("POST");
        assertThat(destination.timeoutMillis()).isEqualTo(5000);
        assertThat(new String(destination.body(), StandardCharsets.UTF_8))
                .isEqualTo("{\"bid\":\"31.5000\"}");
        // The header names travel with the destination; the values do not.
        assertThat(destination.headerNames()).containsExactly("Authorization");
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-020 a blank query template adds no separator to the destination")
    void aBlankQueryContributesNothing() throws Exception {
        queryTemplate = "   ";
        answerWith(200, BODY, Map.of(), true);

        adapter.perform(apply());

        assertThat(destination().uri())
                .hasToString("https://fixture.invalid/ads/object/object-fixture/bid");
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-021 an enquiry carries no bid and no unit, and still builds a destination")
    void anEnquiryRendersWithoutTheBidVocabulary() throws Exception {
        requestTemplate = "";
        pathTemplate = "/ads/task/{nativeTaskKey}";
        answerWith(200, BODY, Map.of(), true);

        AdBidWriteResult result = adapter.perform(new AdBidWriteRequest(
                AdBidWriteRequest.Operation.STATUS_ENQUIRY, CAPABILITY, CREDENTIAL,
                "campaign-fixture", "object-fixture", null, null,
                "fixture-idempotency-key", "task-fixture", null, UUID.randomUUID()));

        assertThat(result.outcome()).isEqualTo(AdBidWriteResult.Outcome.ACCEPTED);
        assertThat(destination().uri())
                .hasToString("https://fixture.invalid/ads/task/task-fixture");
        assertThat(destination().body()).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-ADAPTER-022 resolved secret material does not outlive the call")
    void resolvedSecretsAreZeroedWhateverHappened() throws Exception {
        when(http.exchange(any(), any())).thenThrow(new IOException("synthetic transport failure"));

        adapter.perform(apply());

        assertThat(handedOut).isNotEmpty();
        // Not "was not logged" — the array the port handed over is itself blank.
        assertThat(handedOut).allSatisfy(material ->
                assertThat(new String(material)).isEqualTo("\0".repeat(material.length)));
    }

    private void answerWith(int status, byte[] body, Map<String, List<String>> headers,
            boolean complete) throws Exception {
        when(http.exchange(any(), any())).thenReturn(
                new OutboundHttp.Response(status, body, headers, complete, null));
    }

    private OutboundHttp.Destination destination() {
        ArgumentCaptor<OutboundHttp.Destination> captor =
                ArgumentCaptor.forClass(OutboundHttp.Destination.class);
        verify(http).prepare(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> sentHeaders() throws Exception {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(http).exchange(any(), captor.capture());
        return captor.getValue();
    }

    private static AdBidWriteRequest apply() {
        return new AdBidWriteRequest(AdBidWriteRequest.Operation.APPLY, CAPABILITY, CREDENTIAL,
                "campaign-fixture", "object-fixture",
                Money.of(new BigDecimal("31.50"), "RUB"), "CURRENCY_MAJOR",
                "fixture-idempotency-key", null, null, UUID.randomUUID());
    }

    private EndpointCallSpec endpoint() {
        return new EndpointCallSpec(ENDPOINT, "FIXTURE", "AD_BID_APPLY",
                "https://fixture.invalid", "POST", pathTemplate, queryTemplate, null,
                "application/json", null, "SINGLE_RESPONSE", 60, 5000, 65536L);
    }
}
