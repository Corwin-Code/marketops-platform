package com.mimococo.marketops.marketplaceintegration.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthValueSource;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest;
import com.mimococo.marketops.shared.JsonValues;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real localhost wire bytes with synthetic authority and schemas; no real provider or account. */
class DescriptionExactRequestTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String TEXT="  Текст с кавычками: \"товар\".\n😀  ";
    private static final String TEMPLATE="""
            [{"offer_id":"{nativeListingKey}","attributes":[{"id":{descriptionAttributeKey},
              "values":[{"value":"{descriptionText}"}]}],"kizMarked":{kizMarkedDeclared}}]
            """;
    private static final String SCHEMA="""
            {"schema":"DESCRIPTION_REQUEST_V1","evidenceRef":"fixture://verified-description",
             "mutationSemantics":"PARTIAL_ATTRIBUTE","markingPolicy":"REQUIRED","body":[{
              "offer_id":{"$bind":"LISTING_KEY","$type":"string"},
              "attributes":[{"id":{"$bind":"ATTRIBUTE_KEY","$type":"integer"},
                "values":[{"value":{"$bind":"DESCRIPTION_TEXT","$type":"string"}}]}],
              "kizMarked":{"$bind":"KIZ_MARKED","$type":"boolean"}}]}
            """;

    @Test void actualArrayWireCarriesExactUnicodeIntegerAttributeAndTrueMarking() throws Exception {
        exercise(TEMPLATE,JSON.readTree(SCHEMA),false,true);
    }

    @Test void wrongNativeObjectOrTextCannotReachTheSocket() throws Exception {
        exercise(TEMPLATE.replace("{nativeListingKey}","foreign"),JSON.readTree(SCHEMA),false,false);
        exercise(TEMPLATE.replace("{descriptionText}","prefix {descriptionText}"),JSON.readTree(SCHEMA),false,false);
    }

    @Test void duplicateJsonKeysAndAdditionalItemsCannotReachTheSocket() throws Exception {
        exercise(TEMPLATE.replace("\"offer_id\":","\"offer_id\":\"foreign\",\"offer_id\":"),JSON.readTree(SCHEMA),false,false);
        exercise(TEMPLATE.strip().replaceFirst("\\]$",",{\"offer_id\":\"foreign\"}]"),JSON.readTree(SCHEMA),false,false);
    }

    @Test void missingSchemaAndAStaleFinalAuthorityCheckCannotReachTheSocket() throws Exception {
        exercise(TEMPLATE,null,false,false);
        exercise(TEMPLATE,JSON.readTree(SCHEMA),true,false);
    }

    @Test void restoreSendsTheExactStrongVersionIncludingAnOpaqueComma() throws Exception {
        exercise(TEMPLATE,JSON.readTree(SCHEMA),false,true,"If-Match","\"version,one\"",true,false,false);
    }

    @Test void weakWildcardMultipleMissingAndCollidingVersionsNeverReachTheSocket() throws Exception {
        for (String token : new String[]{null,"*","W/\"one\"","\"one\", \"two\"","\"bad\nvalue\""}) {
            exercise(TEMPLATE,JSON.readTree(SCHEMA),false,false,"If-Match",token,true,false,false);
        }
        exercise(TEMPLATE,JSON.readTree(SCHEMA),false,false,null,"\"one\"",true,false,false);
        exercise(TEMPLATE,JSON.readTree(SCHEMA),false,false,"x-fixture-auth","one",true,false,false);
    }

    @Test void invalidBodyAndDeniedDestinationNeverResolveTheSecret() throws Exception {
        exercise(TEMPLATE,null,false,false,null,null,false,true,false);
        exercise(TEMPLATE,JSON.readTree(SCHEMA),false,false,null,null,false,true,true);
    }

    @Test void destinationDecisionPrecedesSecretAndFinalAuthorityRevocationStillPreventsDispatch() throws Exception {
        exercise(TEMPLATE,JSON.readTree(SCHEMA),false,true,null,null,false,true,false);
        exercise(TEMPLATE,JSON.readTree(SCHEMA),true,false,null,null,false,true,false);
    }

    private void exercise(String template,JsonNode schema,boolean stale,boolean expectedCall) throws Exception {
        exercise(template,schema,stale,expectedCall,null,null,false,false,false);
    }

    private void exercise(String template,JsonNode schema,boolean stale,boolean expectedCall,
                          String conditionalHeader,String version,boolean restore,boolean useSecret,boolean denyDestination) throws Exception {
        var count=new AtomicInteger(); var received=new AtomicReference<byte[]>();
        var contentType=new AtomicReference<String>();
        var receivedVersion=new AtomicReference<String>(); var prepared=new java.util.concurrent.atomic.AtomicBoolean();
        char[] secretMaterial="synthetic".toCharArray();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/description",exchange -> {
            count.incrementAndGet(); received.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            if (conditionalHeader != null) receivedVersion.set(exchange.getRequestHeaders().getFirst(conditionalHeader));
            byte[] response="{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try (var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            int port=server.getAddress().getPort();
            var specs=mock(PlatformCallSpecRepository.class); var operations=mock(WriteOperationRepository.class);
            var secrets=mock(SecretResolverPort.class);
            var context=new PlatformCallSpecRepository.DescriptionAttemptContext(0,65536);
            if (stale) when(specs.descriptionAttemptContext(any())).thenReturn(Optional.of(context),Optional.empty());
            else when(specs.descriptionAttemptContext(any())).thenReturn(Optional.of(context));
            when(specs.verifiedAuthHeaders(anyString(),anyString())).thenReturn(List.of(
                    new AuthHeaderSpec("X-Fixture-Auth",useSecret?AuthValueSource.RESOLVED_SECRET:AuthValueSource.LITERAL,useSecret?"{value}":"synthetic","CONTENT_WRITE",1)));
            when(specs.activeSecretReference(any(),anyString())).thenReturn(Optional.of("fixture://secret"));
            when(secrets.resolve(anyString())).thenAnswer(invocation -> {
                assertThat(prepared.get()).as("destination decision before secret resolution").isTrue();
                return Optional.of(secretMaterial);
            });
            var request=new DescriptionWriteRequest(restore?DescriptionWriteRequest.Operation.RESTORE:DescriptionWriteRequest.Operation.APPLY,UUID.randomUUID(),UUID.randomUUID(),
                    "offer-one",null,TEXT,"4191",true,"synthetic-exact-request",null,version,UUID.randomUUID());
            var endpoint=new EndpointCallSpec(UUID.randomUUID(),"SYNTHETIC","description",
                    "http://127.0.0.1:"+port,"POST","/description",null,null,"application/json",null,"NONE",60,2000,4096);
            when(operations.verifiedOperation(any(),anyString())).thenReturn(Optional.of(new WriteOperationSpec(
                    request.capabilityId(),"SYNTHETIC",request.operation().name(),"SYNCHRONOUS",template,null,null,null,null,null,null,endpoint,
                    conditionalHeader,"/accepted",JSON.readTree("true"),java.util.Set.of(),"/description",null,"4191",schema)));
            OutboundHttp transport=new OutboundHttp() {
                record LocalPlan(Destination destination) implements Plan { }
                @Override public Plan prepare(Destination destination) {
                    if (denyDestination || !"127.0.0.1".equals(destination.uri().getHost()) || destination.uri().getPort()!=port)
                        throw new IllegalArgumentException("only the exact localhost fixture is permitted");
                    prepared.set(true);
                    if (conditionalHeader != null) assertThat(destination.headerNames()).contains(conditionalHeader);
                    return new LocalPlan(destination);
                }
                @Override public Response exchange(Plan plan,Map<String,String> headers) throws java.io.IOException,InterruptedException {
                    var destination=((LocalPlan)plan).destination();
                    var builder=HttpRequest.newBuilder(destination.uri()).timeout(java.time.Duration.ofSeconds(2))
                            .method(destination.method(),HttpRequest.BodyPublishers.ofByteArray(destination.body()));
                    headers.forEach(builder::header);
                    var answer=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
                    return new Response(answer.statusCode(),answer.body(),answer.headers().map(),true,null);
                }
            };
            var result=new PlatformHttpDescriptionWriteAdapter(operations,specs,secrets,transport,Clock.systemUTC()).perform(request);
            assertThat(count.get()).isEqualTo(expectedCall?1:0);
            if (!useSecret || !prepared.get()) verifyNoInteractions(secrets);
            else assertThat(secretMaterial).containsOnly('\0');
            if (expectedCall) {
                assertThat(receivedVersion.get()).isEqualTo(version);
                assertThat(contentType.get()).isEqualTo("application/json");
                assertThat(result.response()).isNotNull();
                assertThat(result.response().requestDigest()).isEqualTo(request.digest());
                JsonNode wire=JsonValues.read(JSON,received.get());
                assertThat(wire.get(0).get("offer_id").asString()).isEqualTo("offer-one");
                assertThat(wire.get(0).get("attributes").get(0).get("id").isIntegralNumber()).isTrue();
                assertThat(wire.get(0).get("attributes").get(0).get("values").get(0).get("value").asString()).isEqualTo(TEXT);
                assertThat(wire.get(0).get("kizMarked").asBoolean()).isTrue();
            } else assertThat(result.response()).isNull();
        } finally { server.stop(0); }
    }
}
