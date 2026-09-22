package com.mimococo.marketops.shared.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.shared.port.OutboundHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedOutboundHttpTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "192.0.0.1", "192.0.2.1", "198.18.1.1", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "240.0.0.1", "::1", "::", "fc00::1", "fe80::1",
            "2001:db8::1", "2001::1", "64:ff9b::7f00:1", "::ffff:127.0.0.1"})
    void nonPublicDestinationsAreNeverAdmitted(String address) throws Exception {
        assertThat(BoundedOutboundHttp.publicAddress(InetAddress.getByName(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1/a", "http://vendor.example/a", "https://named@vendor.example/a",
            "https://vendor.example:444/a", "https://vendor.example/a/../b", "https://[::1]/a",
            "https://2130706433/a", "https://0x7f000001/a", "https://vendor.example./a",
            "https://vendor.example/%2e%2e/a", "https://vendor.example/%252e/a",
            "https://vendor.example/a//b", "https://vendor.example/a#fragment"})
    void uriAmbiguitiesAreRefusedBeforeDns(String target) {
        assertThatThrownBy(() -> BoundedOutboundHttp.validateUri(URI.create(target)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "connection", "content-length", "transfer-encoding", "proxy-authorization",
            "x-forwarded-host", "cookie", "bad\r\nname"})
    void connectionAndRoutingHeadersCannotBeDeclaredAsAuthentication(String name) {
        assertThat(BoundedOutboundHttp.safeHeaderName(name)).isFalse();
    }

    @Test
    void emptyConfigurationDeniesBeforeDnsAndMixedDnsAnswersFailClosed() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        var empty = new BoundedOutboundHttp(new OutboundDestinationProperties(List.of()), host -> {
            resolutions.incrementAndGet(); return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        }, java.time.Clock.systemUTC());
        try {
            assertThatThrownBy(() -> empty.prepare(destination("https://vendor.example/api/read", 32, 1000)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(resolutions).hasValue(0);
        } finally { empty.close(); }
        var rules = new OutboundDestinationProperties(List.of(new OutboundDestinationProperties.Rule(
                "fixture", "vendor.example", "/api/", Set.of("GET"), Set.of("accept"), 32, 32, 1000)));
        var mixed = new BoundedOutboundHttp(rules, host -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")}, java.time.Clock.systemUTC());
        try {
            assertThatThrownBy(() -> mixed.prepare(destination("https://vendor.example/api/read", 32, 1000)))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally { mixed.close(); }
        var publicOnly = new BoundedOutboundHttp(rules, host -> {
            resolutions.incrementAndGet(); return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        }, java.time.Clock.systemUTC());
        try {
            var plan = (BoundedOutboundHttp.Prepared) publicOnly.prepare(destination("https://vendor.example/api/read", 32, 1000));
            assertThat(plan.addresses()).singleElement().extracting(InetAddress::getHostAddress).isEqualTo("8.8.8.8");
            assertThat(resolutions).hasValue(1);
            assertThatThrownBy(() -> publicOnly.prepare(destination("https://vendor.example/another/read", 32, 1000)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> publicOnly.prepare(destination("https://vendor.example/api/read", 33, 1000)))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally { publicOnly.close(); }
    }

    @Test
    void redirectsHaveNoSecondRequestAndResponsesStopAtTheBound() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/probe");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/probe", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/large", exchange -> {
            byte[] body = "0123456789abcdef-over-bound".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Set-Cookie", "synthetic-cookie=discard");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var client = new BoundedOutboundHttp(new OutboundDestinationProperties(List.of()), java.time.Clock.systemUTC());
        try {
            var redirect = client.exchange(loopbackTransportPlan(server, "/redirect", 16, 1000), Map.of("Accept", "application/json"));
            assertThat(redirect.statusCode()).isEqualTo(302);
            assertThat(redirected).hasValue(0);
            var large = client.exchange(loopbackTransportPlan(server, "/large", 16, 1000), Map.of("Accept", "application/json"));
            assertThat(large.complete()).isFalse();
            assertThat(large.body()).isEqualTo("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            assertThat(large.headers()).doesNotContainKey("set-cookie");
            assertThatThrownBy(() -> client.exchange(loopbackTransportPlan(server, "/probe", 16, 1000), Map.of("Host", "other.example")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(redirected).hasValue(0);
        } finally { client.close(); server.stop(0); executor.shutdownNow(); }
    }

    @Test
    void outgoingRequestEntityPreservesExactBytesIncludingMarkup() throws Exception {
        byte[] requestBody = "{\"text\":\"<script>synthetic & \\u003c payload</script>\",\"price\":\"123.40\"}"
                .getBytes(StandardCharsets.UTF_8);
        var received = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/request", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var client = new BoundedOutboundHttp(new OutboundDestinationProperties(List.of()), java.time.Clock.systemUTC());
        try {
            var destination = new OutboundHttp.Destination("fixture",
                    URI.create("http://fixture.example:" + server.getAddress().getPort() + "/request"),
                    "POST", Set.of("Content-Type"), requestBody, 1000, 128);
            // The package-private plan exercises transport only; public prepare still denies loopback HTTP.
            var plan = new BoundedOutboundHttp.Prepared(destination, new InetAddress[]{server.getAddress().getAddress()},
                    Set.of("content-type"), Instant.now().plusSeconds(5));
            var response = client.exchange(plan, Map.of("Content-Type", "application/json"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(received.get()).isEqualTo(requestBody);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"accepted\":true}");
        } finally { client.close(); server.stop(0); }
    }

    @Test
    void waitSignalsSurviveTheFilterAndOutOfBoundsLinesAreNamedButNeverKept() throws Exception {
        var response = rawExchange(List.of(
                "Item-Retry-After: 2",
                "X-Ratelimit-Retry: 120",
                "Retry-After: 1",
                "retry-after: " + "9".repeat(BoundedOutboundHttp.RESPONSE_HEADER_VALUE_LIMIT + 1),
                "Set-Cookie: session=synthetic-secret",
                "X-Unlisted: " + "x".repeat(2000)));
        assertThat(response.headers()).containsEntry("item-retry-after", List.of("2"))
                .containsEntry("x-ratelimit-retry", List.of("120"))
                .containsEntry("retry-after", List.of("1"))
                .doesNotContainKeys("set-cookie", "x-unlisted");
        assertThat(response.withheldHeaders()).containsExactly(Map.entry("retry-after", 1));
    }

    @Test
    void controlCharactersInAWaitValueAreWithheldAndCounted() throws Exception {
        var response = rawExchange(List.of("X-Ratelimit-Retry: 120\u0001", "x-ratelimit-retry: 60"));
        assertThat(response.headers()).containsEntry("x-ratelimit-retry", List.of("60"));
        assertThat(response.withheldHeaders()).containsExactly(Map.entry("x-ratelimit-retry", 1));
    }

    @Test
    void anAbsentWaitSignalIsNeitherKeptNorWithheld() throws Exception {
        var response = rawExchange(List.of("Content-Type: application/json"));
        assertThat(response.headers()).doesNotContainKeys("retry-after", "item-retry-after", "x-ratelimit-retry");
        assertThat(response.withheldHeaders()).isEmpty();
    }

    @Test
    void theParserLimitsStillFailTheExchangeRatherThanKeepingAnything() {
        // The 8192-byte line and 64-field limits are not relaxed to keep a wait signal.
        assertThatThrownBy(() -> rawExchange(List.of("Retry-After: " + "9".repeat(8200))))
                .isInstanceOf(java.io.IOException.class);
        var fields = new java.util.ArrayList<String>();
        for (int field = 0; field < 64; field++) {
            fields.add("X-Filler-" + field + ": 1");
        }
        fields.add("Retry-After: 3600");
        assertThatThrownBy(() -> rawExchange(fields)).isInstanceOf(java.io.IOException.class);
    }

    /** Writes exact header bytes over a loopback socket through the production exchange path. */
    private static OutboundHttp.Response rawExchange(List<String> headerLines) throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var responder = Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) {
                    var in = socket.getInputStream();
                    var head = new java.io.ByteArrayOutputStream();
                    while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                        int next = in.read();
                        if (next < 0) return;
                        head.write(next);
                    }
                    var out = new StringBuilder("HTTP/1.1 429 Too Many Requests\r\n");
                    headerLines.forEach(line -> out.append(line).append("\r\n"));
                    out.append("Content-Length: 2\r\nConnection: close\r\n\r\n{}");
                    socket.getOutputStream().write(out.toString().getBytes(StandardCharsets.ISO_8859_1));
                } catch (java.io.IOException ignored) {
                    // The client assertion reports any transport failure.
                }
            });
            var client = new BoundedOutboundHttp(new OutboundDestinationProperties(List.of()), java.time.Clock.systemUTC());
            try {
                var plan = new BoundedOutboundHttp.Prepared(
                        destination("http://fixture.example:" + server.getLocalPort() + "/wait", 128, 2000),
                        new InetAddress[]{InetAddress.getLoopbackAddress()}, Set.of("accept"), Instant.now().plusSeconds(5));
                var response = client.exchange(plan, Map.of("Accept", "application/json"));
                assertThat(response.statusCode()).isEqualTo(429);
                return response;
            } finally {
                client.close();
                responder.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    private static OutboundHttp.Destination destination(String uri, int responseLimit, int timeout) {
        return new OutboundHttp.Destination("fixture", URI.create(uri), "GET", Set.of("Accept"), new byte[0], timeout, responseLimit);
    }

    /** Exercises the transport only. Production prepare rejects this loopback and HTTP URI. */
    private static OutboundHttp.Plan loopbackTransportPlan(HttpServer server, String path, int limit, int timeout) {
        return new BoundedOutboundHttp.Prepared(destination("http://fixture.example:" + server.getAddress().getPort() + path, limit, timeout),
                new InetAddress[]{server.getAddress().getAddress()}, Set.of("accept"), Instant.now().plusSeconds(5));
    }
}
