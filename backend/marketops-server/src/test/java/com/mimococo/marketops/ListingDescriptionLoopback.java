package com.mimococo.marketops;

import com.mimococo.marketops.shared.port.OutboundHttp;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.ObjectMapper;

/** One explicitly bound fictional listing; every actual socket is this test's own loopback server. */
final class ListingDescriptionLoopback implements OutboundHttp,AutoCloseable {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private HttpServer server;
    private String key;
    private String target;
    private String current;
    private String mode;
    private int polls;
    private boolean conditional;
    private boolean omitVersion;
    final List<String> received=new CopyOnWriteArrayList<>();
    final List<byte[]> bodies=new CopyOnWriteArrayList<>();

    void open(String nativeKey,String exactTarget,String scenario) throws IOException {
        if (server!=null) throw new IllegalStateException("fixture already open");
        key=nativeKey;target=exactTarget;mode=scenario;current=scenario.equals("EMPTY_SOURCE")?"":ListingConversionFixture.PRIOR_TEXT_ONE;polls=0;
        received.clear();bodies.clear();conditional=false;omitVersion=false;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/fixture/descriptions",exchange->{
            byte[] body=exchange.getRequestBody().readAllBytes();
            received.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());bodies.add(body);
            boolean mutation=exchange.getRequestMethod().equals("POST");
            Object answer;
            if (mutation) {
                // A separate native edit after preflight changes the required conditional version.
                String currentVersion=mode.equals("VERSION_CONFLICT")?"\"fixture-version-3\"":"\"fixture-version-2\"";
                if (conditional && !currentVersion.equals(exchange.getRequestHeaders().getFirst("If-Match"))) {
                    exchange.sendResponseHeaders(412,-1);exchange.close();return;
                }
                var submitted=json.readTree(body);
                if (!submitted.path("offer_id").asString().equals(key)
                        || !submitted.path("attributes").get(0).path("values").get(0).path("value").asString().equals(target)) {
                    exchange.sendResponseHeaders(400,-1);exchange.close();return;
                }
                if (!mode.equals("ASYNC")) current=target;
                answer=mode.equals("ASYNC")?Map.of("accepted",true,"task_id","fixture-task")
                        :Map.of("items",List.of(Map.of("offer_id",key,"accepted",true)));
            } else answer=Map.of("items",List.of(Map.of("offer_id",key,"description",current,"kizMarked",false)));
            if (conditional && !omitVersion) exchange.getResponseHeaders().add("ETag","\"fixture-version-2\"");
            byte[] response=json.writeValueAsBytes(answer);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.createContext("/fixture/task-info",exchange->{
            byte[] body=exchange.getRequestBody().readAllBytes();
            received.add("POST /fixture/task-info");bodies.add(body);
            if (!json.readTree(body).path("task_id").asString().equals("fixture-task")) {
                exchange.sendResponseHeaders(400,-1);exchange.close();return;
            }
            polls++;
            if(polls>1) current=target;
            byte[] response=json.writeValueAsBytes(Map.of("items",List.of(Map.of("offer_id",key,"status",polls>1?"done":"working"))));
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
    }
    void openRestoration(String nativeKey,String appliedText,String restorationText,String scenario) throws IOException {
        open(nativeKey,restorationText,scenario.equals("CRASH")?"CRASH_AFTER_APPLY":scenario);
        current=scenario.equals("LATER_CHANGE")?"Позднейшее законное описание":appliedText;
        conditional=true;omitVersion=scenario.equals("MISSING_VERSION");
    }
    private record LocalPlan(Destination destination) implements Plan { }
    @Override public Plan prepare(Destination destination) {
        boolean apply=destination.method().equals("POST") && destination.uri().getPath().equals("/fixture/descriptions");
        boolean read=destination.method().equals("GET") && destination.uri().getPath().equals("/fixture/descriptions/"+key);
        boolean status=destination.method().equals("POST") && destination.uri().getPath().equals("/fixture/task-info") && mode.equals("ASYNC");
        if (server==null || !destination.uri().getScheme().equals("https")
                || !destination.uri().getHost().equals("example.invalid") || destination.uri().getRawQuery()!=null
                || !(apply||read||status)) throw new IllegalArgumentException("outside exact fictional description endpoint");
        return new LocalPlan(destination);
    }
    @Override public Response exchange(Plan plan,Map<String,String> headers) throws IOException,InterruptedException {
        var destination=((LocalPlan)plan).destination();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+destination.uri().getRawPath()))
                .timeout(java.time.Duration.ofSeconds(3)).method(destination.method(),HttpRequest.BodyPublishers.ofByteArray(destination.body()));
        headers.forEach(request::header);
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
        if(mode.equals("CRASH_AFTER_APPLY") && destination.method().equals("POST"))
            throw new SimulatedProcessLoss();
        return new Response(response.statusCode(),response.body(),response.headers().map(),true,null);
    }
    static final class SimulatedProcessLoss extends Error {
        SimulatedProcessLoss() { super("synthetic process loss after native acceptance, before response custody"); }
    }
    void stop() { if(server!=null) {server.stop(0);server=null;} }
    @Override public void close() { stop();client.close(); }
}
