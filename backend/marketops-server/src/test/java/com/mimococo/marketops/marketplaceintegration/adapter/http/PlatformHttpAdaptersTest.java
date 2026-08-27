package com.mimococo.marketops.marketplaceintegration.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.marketplaceintegration.internal.domain.*;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.*;
import com.mimococo.marketops.shared.Money;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Isolated transport fixtures: no provider, DNS or real credential is used. */
class PlatformHttpAdaptersTest {
    private final UUID endpointId=UUID.randomUUID(),credentialId=UUID.randomUUID(),capabilityId=UUID.randomUUID();
    private final Clock clock=Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"),ZoneOffset.UTC);
    private final OutboundHttp http=mock(OutboundHttp.class);
    private final OutboundHttp.Plan plan=mock(OutboundHttp.Plan.class);
    private final PlatformCallSpecRepository specs=mock(PlatformCallSpecRepository.class);
    private final WriteOperationRepository operations=mock(WriteOperationRepository.class);
    private final SecretResolverPort secrets=mock(SecretResolverPort.class);
    private final AcquisitionRequest acquisition=mock(AcquisitionRequest.class);
    private final PlatformHttpAcquisitionAdapter reads=new PlatformHttpAcquisitionAdapter(http,specs,secrets,clock);
    private final PlatformHttpPriceWriteAdapter writes=new PlatformHttpPriceWriteAdapter(http,operations,specs,secrets,JsonMapper.builder().build(),clock);

    @BeforeEach
    void recordedFixture() {
        when(acquisition.endpointId()).thenReturn(endpointId);
        when(acquisition.credentialId()).thenReturn(credentialId);
        when(acquisition.callAuthorityExpiresAt()).thenReturn(clock.instant().plusSeconds(30));
        when(specs.findVerifiedSpec(endpointId)).thenReturn(Optional.of(endpoint()));
        when(specs.reserveCallBudget(endpointId)).thenReturn(true);
        when(specs.verifiedAuthHeaders(eq("FIXTURE"),anyString())).thenAnswer(call -> List.of(
                new AuthHeaderSpec("Authorization",AuthValueSource.RESOLVED_SECRET,"Bearer {value}",call.getArgument(1),1)));
        when(specs.activeSecretReference(eq(credentialId),anyString())).thenReturn(Optional.of("secret-ref://fixture/read"));
        when(secrets.resolve("secret-ref://fixture/read")).thenAnswer(call -> Optional.of("synthetic-test-value".toCharArray()));
        when(http.prepare(any())).thenReturn(plan);
        when(specs.priceAttemptCurrent(any())).thenReturn(true);
        when(specs.acquisitionEvidenceDigest(any(),any())).thenReturn(Optional.of("1".repeat(64)));
        when(operations.verifiedOperation(any(),any())).thenAnswer(call -> Optional.of(new WriteOperationSpec(
                capabilityId,"FIXTURE",call.getArgument(1),"SYNCHRONOUS","{}",null,null,null,null,
                "/price","/currency",endpoint(),"If-Match")));
    }

    @Test
    void destinationRefusalHappensBeforeEitherAdapterResolvesSecrets() {
        when(http.prepare(any())).thenThrow(new IllegalArgumentException("synthetic denied destination"));
        assertThat(reads.acquire(acquisition).failureCode()).isEqualTo("request_could_not_be_built");
        assertThat(writes.perform(write(PriceWriteRequest.Operation.READBACK)).outcome()).isNotEqualTo(PriceWriteResult.Outcome.ACCEPTED);
        verifyNoInteractions(secrets);
    }

    @Test
    void malformedHeaderTemplateAndUnverifiedEndpointsResolveNothing() {
        when(specs.verifiedAuthHeaders(eq("FIXTURE"),anyString())).thenReturn(List.of(
                new AuthHeaderSpec("Authorization",AuthValueSource.RESOLVED_SECRET,"Bearer {value}\r\nInjected: value","READ",1)));
        assertThat(reads.acquire(acquisition).failureCode()).isEqualTo("request_could_not_be_built");
        assertThat(writes.perform(write(PriceWriteRequest.Operation.READBACK)).outcome()).isNotEqualTo(PriceWriteResult.Outcome.ACCEPTED);
        verifyNoInteractions(http,secrets);
        when(specs.findVerifiedSpec(endpointId)).thenReturn(Optional.empty());
        assertThat(reads.acquire(acquisition).failureCode()).isEqualTo("endpoint_not_verified");
        when(operations.verifiedOperation(any(),any())).thenReturn(Optional.empty());
        assertThat(writes.perform(write(PriceWriteRequest.Operation.READBACK)).outcome()).isNotEqualTo(PriceWriteResult.Outcome.ACCEPTED);
        verifyNoInteractions(http,secrets);
    }

    @Test
    void expiredAuthorityAndSharedQuotaRefusalNeverReachHttpOrSecrets() {
        when(acquisition.callAuthorityExpiresAt()).thenReturn(clock.instant());
        assertThat(reads.acquire(acquisition).failureCode()).isEqualTo("call_authority_expired");
        when(acquisition.callAuthorityExpiresAt()).thenReturn(clock.instant().plusSeconds(30));
        when(specs.reserveCallBudget(endpointId)).thenReturn(false);
        assertThat(reads.acquire(acquisition).retryable()).isTrue();
        assertThat(writes.perform(write(PriceWriteRequest.Operation.READBACK)).outcome()).isEqualTo(PriceWriteResult.Outcome.RETRIABLE_ERROR);
        verifyNoInteractions(http,secrets);
    }

    @Test
    void missingAccountEvidenceAndUnboundPriceAttemptCannotPrepareAnExternalCall() {
        when(specs.acquisitionEvidenceDigest(any(),any())).thenReturn(Optional.empty());
        when(specs.priceAttemptCurrent(any())).thenReturn(false);
        assertThat(reads.acquire(acquisition).outcome()).isNotEqualTo(AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
        assertThat(writes.perform(write(PriceWriteRequest.Operation.READBACK)).outcome()).isNotEqualTo(PriceWriteResult.Outcome.ACCEPTED);
        verifyNoInteractions(http,secrets);
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void changedEvidenceBeforeSecretResolutionOrBeforeDispatchCannotEscape(boolean beforeSecret) throws Exception {
        var digest=Optional.of("1".repeat(64)); var changed=Optional.of("2".repeat(64));
        when(specs.acquisitionEvidenceDigest(any(),any())).thenReturn(digest,beforeSecret?changed:digest,changed);
        when(specs.priceAttemptCurrent(any())).thenReturn(true,!beforeSecret,false);
        char[] resolved="synthetic-test-value".toCharArray();
        when(secrets.resolve(any())).thenReturn(Optional.of(resolved));
        assertThat(reads.acquire(acquisition).outcome()).isNotEqualTo(AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
        assertThat(writes.perform(write(PriceWriteRequest.Operation.READBACK)).outcome()).isNotEqualTo(PriceWriteResult.Outcome.ACCEPTED);
        verify(http,never()).exchange(any(),any());
        if (beforeSecret) verifyNoInteractions(secrets);
        else assertThat(resolved).containsOnly('\0');
    }

    @ParameterizedTest
    @ValueSource(ints={200,400,401,408,429,500,503})
    void acquisitionRetainsExactBodiesAndBackpressureWithoutInventingSourceTime(int status) throws Exception {
        byte[] bytes="{\"next\":null}".getBytes(StandardCharsets.UTF_8);
        when(http.exchange(any(),any())).thenReturn(new OutboundHttp.Response(status,bytes,
                Map.of("content-type",List.of("application/json; charset=utf-8"),"retry-after",List.of("180"),
                        "set-cookie",List.of("discarded")),true,null));
        var result=reads.acquire(acquisition);
        assertThat(result.body()).isEqualTo(bytes);
        assertThat(result.sourceTime()).isNull();
        assertThat(result.responseHeaders()).containsEntry("retry-after","180").doesNotContainKey("set-cookie");
        assertThat(result.retryable()).isEqualTo(status==408||status==429||status>=500);
        assertThat(result.outcome()==AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES).isEqualTo(status==200);
        var ordered=inOrder(http,secrets);
        ordered.verify(http).prepare(any());
        ordered.verify(secrets).resolve("secret-ref://fixture/read");
        ordered.verify(http).exchange(any(),any());
    }

    @Test
    void unexpectedContentTypeAndPartialBytesCannotBecomeSuccess() throws Exception {
        when(http.exchange(any(),any())).thenReturn(new OutboundHttp.Response(200,"{}".getBytes(StandardCharsets.UTF_8),
                Map.of("content-type",List.of("text/html")),true,null));
        assertThat(reads.acquire(acquisition).failureCode()).isEqualTo("UNEXPECTED_CONTENT_TYPE");
        when(http.exchange(any(),any())).thenReturn(new OutboundHttp.Response(200,"{\"price\":".getBytes(StandardCharsets.UTF_8),Map.of(),false,"RESPONSE_LIMIT"));
        assertThat(reads.acquire(acquisition).responseComplete()).isFalse();
        var result=writes.perform(write(PriceWriteRequest.Operation.APPLY));
        assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.UNKNOWN_STATE);
        assertThat(result.response().complete()).isFalse();
    }

    @Test
    void exactReadbackMoneyIsPreservedAndSecretBuffersAreCleared() throws Exception {
        char[] buffer="synthetic-test-value".toCharArray();
        when(secrets.resolve(any())).thenReturn(Optional.of(buffer));
        when(http.exchange(any(),any())).thenReturn(response("{\"price\":99999999999999.9999,\"currency\":\"RUB\"}"));
        var request=write(PriceWriteRequest.Operation.READBACK);
        var result=writes.perform(request);
        assertThat(result.observedPrice()).isEqualByComparingTo("99999999999999.9999");
        assertThat(result.response().requestDigest()).isEqualTo(request.digest());
        assertThat(buffer).containsOnly('\0');
    }

    @ParameterizedTest
    @ValueSource(strings={"{\"price\":1,\"price\":2}","null","{}","{\"price\":\"not-money\"}","not-json"})
    void malformedPriceResponsesCannotAuthorizeSuccess(String body) throws Exception {
        when(http.exchange(any(),any())).thenReturn(response(body));
        assertThat(writes.perform(write(PriceWriteRequest.Operation.READBACK)).outcome()).isNotEqualTo(PriceWriteResult.Outcome.ACCEPTED);
    }

    @Test
    void transportFailureAndExpiredPlansKeepMutatingOutcomesUnknown() throws Exception {
        when(http.exchange(any(),any())).thenThrow(new java.io.IOException("synthetic interruption"));
        assertThat(writes.perform(write(PriceWriteRequest.Operation.RESTORE)).outcome()).isEqualTo(PriceWriteResult.Outcome.UNKNOWN_STATE);
        assertThat(reads.acquire(acquisition).retryable()).isTrue();
        doThrow(new IllegalArgumentException("expired plan")).when(http).exchange(any(),any());
        assertThat(writes.perform(write(PriceWriteRequest.Operation.APPLY)).outcome()).isEqualTo(PriceWriteResult.Outcome.UNKNOWN_STATE);
        assertThat(reads.acquire(acquisition).failureCode()).isEqualTo("outbound_plan_rejected");
    }

    private EndpointCallSpec endpoint() {
        return new EndpointCallSpec(endpointId,"FIXTURE","read","https://fixture.invalid","POST","/read",null,null,
                "application/json","/next","CURSOR",10,1000,4096);
    }
    private PriceWriteRequest write(PriceWriteRequest.Operation operation) {
        return new PriceWriteRequest(operation,capabilityId,credentialId,"listing","variant",Money.of(new java.math.BigDecimal("12.30"),"RUB"),
                "fixture-idempotency",null,"fixture-version");
    }
    private static OutboundHttp.Response response(String body) {
        return new OutboundHttp.Response(200,body.getBytes(StandardCharsets.UTF_8),Map.of("content-type",List.of("application/json")),true,null);
    }
}
