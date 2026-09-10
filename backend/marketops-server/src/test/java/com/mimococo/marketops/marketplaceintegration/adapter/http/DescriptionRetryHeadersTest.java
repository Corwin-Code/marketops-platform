package com.mimococo.marketops.marketplaceintegration.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthValueSource;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteResult;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Real loopback sockets with synthetic bodies and literal test auth; no provider or account is contacted. */
class DescriptionRetryHeadersTest {
    @Test
    void nativeHeadersSurviveTheHttpAdapterInTheirOwnUnits() throws Exception {
        var ozon=response("OZON",Map.of("Item-Retry-After",List.of("120")));
        assertThat(ozon.retryAfterSeconds()).isEqualTo(7200);
        assertThat(ozon.response().headers()).containsEntry("item-retry-after","120");
        var wb=response("WILDBERRIES",Map.of("X-Ratelimit-Retry",List.of("7200")));
        assertThat(wb.retryAfterSeconds()).isEqualTo(7200);
        assertThat(wb.response().headers()).containsEntry("x-ratelimit-retry","7200");
    }

    @Test
    void standardHeaderIsNotReinterpretedAsOzonMinutes() throws Exception {
        assertThat(response("OZON",Map.of("Retry-After",List.of("120"))).retryAfterSeconds()).isEqualTo(120);
    }

    @Test
    void duplicateAndMalformedValuesStayVisibleForDurableUnknownClassification() throws Exception {
        var duplicate=response("OZON",Map.of("Item-Retry-After",List.of("2","3")));
        assertThat(duplicate.retryAfterSeconds()).isNull();
        assertThat(duplicate.response().headers().get("item-retry-after")).contains("2","3",",");
        var malformed=response("OZON",Map.of("Item-Retry-After",List.of("soon")));
        assertThat(malformed.retryAfterSeconds()).isNull();
        assertThat(malformed.response().headers()).containsEntry("item-retry-after","soon");
    }

    @Test
    void sharedTransportPreservesCaseVariantHeaderMultiplicity() {
        var transport=new OutboundHttp.Response(429,new byte[0],Map.of("Retry-After",List.of("2"),
                "retry-after",List.of("3")),true,null);
        assertThat(transport.headers().get("retry-after")).containsExactlyInAnyOrder("2","3");
        assertThat(transport.firstHeader("retry-after")).isEmpty();
    }

    private DescriptionWriteResult response(String platform,Map<String,List<String>> headers) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/description",exchange -> {
            headers.forEach((key,values) -> values.forEach(value -> exchange.getResponseHeaders().add(key,value)));
            byte[] body="{\"synthetic\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429,body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            int port=server.getAddress().getPort();
            var operations=mock(WriteOperationRepository.class);
            var specs=mock(PlatformCallSpecRepository.class);
            var request=new DescriptionWriteRequest(DescriptionWriteRequest.Operation.READBACK,UUID.randomUUID(),
                    UUID.randomUUID(),"synthetic-listing",null,null,null,false,"synthetic-retry-request",null,null,UUID.randomUUID());
            var endpoint=new EndpointCallSpec(UUID.randomUUID(),platform,"synthetic.readback",
                    "http://127.0.0.1:"+port,"GET","/description",null,null,"application/json",null,"NONE",null,2000,4096);
            when(operations.verifiedOperation(any(),anyString())).thenReturn(Optional.of(new WriteOperationSpec(
                    request.capabilityId(),platform,"READBACK","SYNCHRONOUS","",null,null,null,null,null,null,endpoint)));
            when(specs.descriptionAttemptContext(any())).thenReturn(Optional.of(
                    new PlatformCallSpecRepository.DescriptionAttemptContext(0,6000)));
            when(specs.verifiedAuthHeaders(anyString(),anyString())).thenReturn(List.of(
                    new AuthHeaderSpec("X-Fixture-Auth",AuthValueSource.LITERAL,"synthetic","CONTENT_WRITE",1)));
            OutboundHttp transport=new OutboundHttp() {
                record LocalPlan(Destination destination) implements Plan { }
                @Override public Plan prepare(Destination destination) {
                    if (!"127.0.0.1".equals(destination.uri().getHost()) || destination.uri().getPort()!=port)
                        throw new IllegalArgumentException("only the exact loopback fixture is allowed");
                    return new LocalPlan(destination);
                }
                @Override public Response exchange(Plan plan,Map<String,String> requestHeaders)
                        throws java.io.IOException,InterruptedException {
                    var destination=((LocalPlan)plan).destination();
                    var builder=HttpRequest.newBuilder(destination.uri()).timeout(java.time.Duration.ofSeconds(2)).GET();
                    requestHeaders.forEach(builder::header);
                    var received=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
                    return new Response(received.statusCode(),received.body(),received.headers().map(),true,null);
                }
            };
            var answer=new PlatformHttpDescriptionWriteAdapter(operations,specs,mock(SecretResolverPort.class),transport,
                    Clock.systemUTC()).perform(request);
            assertThat(answer.response()).isNotNull();
            assertThat(answer.response().requestDigest()).isEqualTo(request.digest());
            assertThat(answer.response().httpStatus()).isEqualTo(429);
            assertThat(answer.body()).isEqualTo("{\"synthetic\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return answer;
        } finally {
            server.stop(0);
        }
    }
}
