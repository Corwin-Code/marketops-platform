package com.mimococo.marketops.marketplaceintegration.adapter.objectstorage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.OperationRejectedException;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** Isolated protocol tests; the transport is a mock and no provider is contacted. */
class S3CompatibleObjectStorageTest {
    private static final String REF="object-ref://fixture/raw/abc";
    private static final byte[] BODY="fixture raw bytes".getBytes(StandardCharsets.UTF_8);
    private final OutboundHttp http=mock(OutboundHttp.class);
    private final OutboundHttp.Plan plan=mock(OutboundHttp.Plan.class);
    private final SecretResolverPort secrets=mock(SecretResolverPort.class);
    private final ObjectStorageProperties properties=new ObjectStorageProperties();
    private final Clock clock=Clock.fixed(Instant.parse("2026-08-27T09:00:00Z"),ZoneOffset.UTC);
    private final S3CompatibleObjectStorage storage=new S3CompatibleObjectStorage(http,properties,secrets,clock);

    @BeforeEach
    void configureSyntheticDestination() {
        properties.setEndpoint("https://storage.fixture.invalid");
        properties.setRegion("ru-central1");
        properties.setBucket("raw-fixture");
        properties.setAccessKeyId("fixture-access-id");
        properties.setCredentialReference("secret-ref://fixture/raw");
        when(http.prepare(any())).thenReturn(plan);
        when(secrets.resolve(any())).thenAnswer(call -> Optional.of("synthetic-test-value".toCharArray()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void putSignsTheExactConditionalRequestOnlyAfterDestinationApproval() throws Exception {
        char[] material="synthetic-test-value".toCharArray();
        when(secrets.resolve(any())).thenReturn(Optional.of(material));
        when(http.exchange(any(),any())).thenReturn(response(200,BODY,true));
        assertThat(storage.putIfAbsent(REF,BODY)).isEqualTo(ObjectStoragePort.PutOutcome.STORED);
        var destination=ArgumentCaptor.forClass(OutboundHttp.Destination.class);
        var headers=ArgumentCaptor.forClass(Map.class);
        verify(http).prepare(destination.capture());
        verify(http).exchange(any(),headers.capture());
        assertThat(destination.getValue().uri().toString()).isEqualTo("https://storage.fixture.invalid/raw-fixture/raw/abc");
        assertThat(destination.getValue().body()).isEqualTo(BODY);
        assertThat(headers.getValue()).containsEntry("if-none-match","*").doesNotContainKey("host");
        // Independent Python hashlib/hmac calculation with synthetic input,
        // using the canonical request prescribed by AWS's SigV4 documentation.
        assertThat(headers.getValue().get("Authorization")).isEqualTo("AWS4-HMAC-SHA256 Credential=fixture-access-id/20260827/ru-central1/s3/aws4_request, SignedHeaders=host;if-none-match;x-amz-content-sha256;x-amz-date, Signature=36a06fb1db09c940d5a86b69aff9d039c59d4f9f8edfef2d5a88ffb677d9b42c");
        assertThat(material).containsOnly('\0');
        var order=inOrder(http,secrets);
        order.verify(http).prepare(any()); order.verify(secrets).resolve(any()); order.verify(http).exchange(any(),any());
    }

    @ParameterizedTest
    @ValueSource(ints={200,201,409,412})
    void conditionalPutHasOnlyStoredOrAlreadyPresentOutcomes(int status) throws Exception {
        when(http.exchange(any(),any())).thenReturn(response(status,BODY,true));
        assertThat(storage.putIfAbsent(REF,BODY)).isEqualTo(status<400
                ? ObjectStoragePort.PutOutcome.STORED : ObjectStoragePort.PutOutcome.ALREADY_PRESENT);
    }

    @Test
    void getAndVerificationUseTheBytesActuallyRead() throws Exception {
        when(http.exchange(any(),any())).thenReturn(response(200,BODY,true));
        assertThat(storage.read(REF)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(BODY));
        assertThat(storage.verify(REF,Digest.ofBytes(BODY))).isTrue();
        assertThat(storage.verify(REF,"0".repeat(64))).isFalse();
        when(http.exchange(any(),any())).thenReturn(response(404,new byte[0],true));
        assertThat(storage.read(REF)).isEmpty();
        assertThat(storage.verify(REF,Digest.ofBytes(BODY))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints={301,400,403,429,500})
    void unexpectedProviderStatusesAreStableFailures(int status) throws Exception {
        when(http.exchange(any(),any())).thenReturn(response(status,BODY,true));
        assertThatThrownBy(() -> storage.putIfAbsent(REF,BODY)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> storage.read(REF)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void anIncompleteSuccessfulResponseCannotBecomeCustodiedEvidence() throws Exception {
        when(http.exchange(any(),any())).thenReturn(response(200,BODY,false));
        assertThatThrownBy(() -> storage.read(REF)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> storage.putIfAbsent(REF,BODY)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void destinationRefusalNeverResolvesASigningSecret() {
        when(http.prepare(any())).thenThrow(new IllegalArgumentException("fixture denied"));
        assertThatThrownBy(() -> storage.read(REF)).isInstanceOf(OperationRejectedException.class);
        verifyNoInteractions(secrets);
    }

    @ParameterizedTest
    @ValueSource(strings={"http://fixture.invalid","https://fixture.invalid:8443","https://fixture.invalid/path","https://fixture.invalid?x=1"})
    void invalidConfigurationIsRefusedBeforeTransportOrSecrets(String endpoint) {
        properties.setEndpoint(endpoint);
        assertThatThrownBy(() -> storage.read(REF)).isInstanceOf(OperationRejectedException.class);
        verifyNoInteractions(http,secrets);
    }

    @Test
    void incompleteOrInjectedSigningConfigurationIsRefusedBeforeSecrets() {
        properties.setRegion(null);
        assertThatThrownBy(() -> storage.read(REF)).isInstanceOf(OperationRejectedException.class);
        properties.setRegion("ru-central1\nInjected: value");
        assertThatThrownBy(() -> storage.read(REF)).isInstanceOf(OperationRejectedException.class);
        verifyNoInteractions(http,secrets);
    }

    @Test
    void unresolvedSecretAndInterruptedTransportStayClosed() throws Exception {
        when(secrets.resolve(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> storage.read(REF)).isInstanceOf(OperationRejectedException.class);
        verify(http,never()).exchange(any(),any());
        when(secrets.resolve(any())).thenAnswer(call -> Optional.of("synthetic-test-value".toCharArray()));
        doThrow(new java.io.IOException("fixture outage")).when(http).exchange(any(),any());
        assertThatThrownBy(() -> storage.read(REF)).isInstanceOf(OperationRejectedException.class);
        doThrow(new InterruptedException("fixture interruption")).when(http).exchange(any(),any());
        try {
            assertThatThrownBy(() -> storage.read(REF)).isInstanceOf(OperationRejectedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void locatorAndEncodingAreBoundedAndBytePreserving() {
        assertThatThrownBy(() -> storage.read("object-ref://fixture/raw/../escape")).isInstanceOf(OperationRejectedException.class);
        assertThat(SignatureV4.encodeSegment("a b/Ж~")).isEqualTo("a%20b%2F%D0%96~");
        verifyNoInteractions(http,secrets);
    }

    private static OutboundHttp.Response response(int status,byte[] body,boolean complete) {
        return new OutboundHttp.Response(status,body,Map.of(),complete,complete?null:"RESPONSE_LIMIT");
    }
}
