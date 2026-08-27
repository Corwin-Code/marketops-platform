package com.mimococo.marketops.aicopilot.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.aicopilot.port.ModelRequest;
import com.mimococo.marketops.aicopilot.port.ModelResponse;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class HttpModelGatewayTest {
    private final OutboundHttp http=mock(OutboundHttp.class);
    private final OutboundHttp.Plan plan=mock(OutboundHttp.Plan.class);
    private final AiRepository repository=mock(AiRepository.class);
    private final SecretResolverPort secrets=mock(SecretResolverPort.class);
    private final HttpModelGateway gateway=new HttpModelGateway(http,repository,secrets,JsonMapper.builder().build(),Clock.systemUTC());
    private final ModelRequest request=new ModelRequest("fixture-model","secret-ref://fixture/model","Advice only.","Data: \"bounded\"\n",4000);

    @BeforeEach
    void fixture() {
        when(repository.eligibleProviderSpec("fixture-model")).thenReturn(Optional.of(spec("{\"model\":\"{model}\",\"input\":\"{userPrompt}\"}")));
        when(http.prepare(any())).thenReturn(plan);
        when(secrets.resolve(any())).thenAnswer(call -> Optional.of("synthetic-test-value".toCharArray()));
    }

    @Test
    void destinationPolicyAndEligibilityPrecedeSecretResolution() {
        when(http.prepare(any())).thenThrow(new IllegalArgumentException("blocked fixture destination"));
        assertThat(gateway.invoke(request).failureCode()).isEqualTo("DESTINATION_POLICY_REFUSED");
        verifyNoInteractions(secrets);
        when(repository.eligibleProviderSpec(any())).thenReturn(Optional.empty());
        assertThat(gateway.invoke(request).failureCode()).isEqualTo("PROVIDER_NOT_ELIGIBLE");
        verifyNoInteractions(secrets);
    }

    @ParameterizedTest
    @ValueSource(strings={"not-json","[]","{\"input\":\"{notDeclared}\"}","{\"same\":1,\"same\":2}"})
    void malformedRequestTemplatesAreRefusedBeforeAnySecret(String template) {
        when(repository.eligibleProviderSpec(any())).thenReturn(Optional.of(spec(template)));
        assertThat(gateway.invoke(request).outcome()).isEqualTo(ModelResponse.Outcome.FAILED);
        verifyNoInteractions(http,secrets);
    }

    @Test
    void escapedProjectionAndResponsePointerRoundTripWithoutLeakingSecretBuffers() throws Exception {
        char[] resolved="synthetic-test-value".toCharArray();
        when(secrets.resolve(any())).thenReturn(Optional.of(resolved));
        when(http.exchange(any(),any())).thenReturn(response(200,"{\"answer\":\"{\\\"facts\\\":[]}\"}",true));
        var result=gateway.invoke(request);
        assertThat(result.answer()).contains("{\"facts\":[]}");
        assertThat(resolved).containsOnly('\0');
        var captured=org.mockito.ArgumentCaptor.forClass(OutboundHttp.Destination.class);
        verify(http).prepare(captured.capture());
        var body=JsonMapper.builder().build().readTree(captured.getValue().body());
        assertThat(body.path("input").asString()).isEqualTo(request.userPrompt());
        var ordered=inOrder(http,secrets);
        ordered.verify(http).prepare(any());
        ordered.verify(secrets).resolve(any());
        ordered.verify(http).exchange(any(),any());
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","{\"answer\":null}","{\"answer\":4}","{\"answer\":\"a\",\"answer\":\"b\"}","bad-json"})
    void unreadableOrAmbiguousResponsesCannotBecomeModelAnswers(String body) throws Exception {
        when(http.exchange(any(),any())).thenReturn(response(200,body,true));
        assertThat(gateway.invoke(request).outcome()).isEqualTo(ModelResponse.Outcome.FAILED);
    }

    @Test
    void incompleteInvalidUtf8AndProviderFailureStayDistinct() throws Exception {
        when(http.exchange(any(),any())).thenReturn(response(200,"{\"answer\":\"prefix\"}",false));
        assertThat(gateway.invoke(request).failureCode()).isEqualTo("RESPONSE_LIMIT");
        when(http.exchange(any(),any())).thenReturn(response(429,"{}",true));
        assertThat(gateway.invoke(request).failureCode()).isEqualTo("PROVIDER_REFUSED");
        when(http.exchange(any(),any())).thenReturn(new OutboundHttp.Response(200,new byte[]{(byte)0xc3,0x28},Map.of(),true,null));
        assertThat(gateway.invoke(request).failureCode()).isEqualTo("RESPONSE_NOT_READABLE");
    }

    @Test
    void missingSecretTransportFailureAndInterruptionAreClosedFailures() throws Exception {
        when(secrets.resolve(any())).thenReturn(Optional.empty());
        assertThat(gateway.invoke(request).failureCode()).isEqualTo("CREDENTIAL_UNRESOLVABLE");
        verify(http,never()).exchange(any(),any());
        when(secrets.resolve(any())).thenReturn(Optional.of("synthetic-test-value".toCharArray()));
        doThrow(new java.io.IOException("fixture outage")).when(http).exchange(any(),any());
        assertThat(gateway.invoke(request).failureCode()).isEqualTo("TRANSPORT_FAILED");
        doThrow(new InterruptedException("fixture interruption")).when(http).exchange(any(),any());
        try {
            assertThat(gateway.invoke(request).failureCode()).isEqualTo("INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private static AiRepository.ProviderCallSpec spec(String template) {
        return new AiRepository.ProviderCallSpec("https://fixture.invalid/model",template,"/answer","Authorization",
                "Bearer {value}",1000,"fixture-provider");
    }
    private static OutboundHttp.Response response(int status,String body,boolean complete) {
        return new OutboundHttp.Response(status,body.getBytes(StandardCharsets.UTF_8),Map.of("content-type",List.of("application/json")),
                complete,complete?null:"RESPONSE_LIMIT");
    }
}
