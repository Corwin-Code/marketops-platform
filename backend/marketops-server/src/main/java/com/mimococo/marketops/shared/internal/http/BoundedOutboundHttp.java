package com.mimococo.marketops.shared.internal.http;

import com.mimococo.marketops.shared.port.OutboundHttp;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded HTTP with no proxy, redirect, automatic retry, cookies or second DNS lookup. */
@Component
@EnableConfigurationProperties(OutboundDestinationProperties.class)
@Transactional(propagation = Propagation.NEVER)
public class BoundedOutboundHttp implements OutboundHttp {
    static final int HARD_BODY_LIMIT = 8 * 1024 * 1024;
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "connection", "content-length",
            "transfer-encoding", "proxy-authorization", "proxy-connection", "cookie", "set-cookie",
            "forwarded", "upgrade", "te", "trailer", "expect", "accept-encoding");
    private static final Set<String> RESPONSE_HEADERS = Set.of("content-type", "content-encoding", "date",
            "etag", "x-version-id", "retry-after", "x-request-id", "x-correlation-id",
            "x-ratelimit-remaining", "x-ratelimit-reset", "x-ratelimit-limit");
    private final OutboundDestinationProperties properties;
    private final DnsLookup lookup;
    private final java.time.Clock clock;
    private final ThreadPoolExecutor dnsWorkers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("outbound-dns-", 0).factory());
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("outbound-deadline-", 0).factory());

    @org.springframework.beans.factory.annotation.Autowired
    public BoundedOutboundHttp(OutboundDestinationProperties properties, java.time.Clock clock) { this(properties, InetAddress::getAllByName,clock); }

    BoundedOutboundHttp(OutboundDestinationProperties properties, DnsLookup lookup, java.time.Clock clock) {
        this.properties = properties; this.lookup = lookup; this.clock=clock;
    }

    @FunctionalInterface
    interface DnsLookup { InetAddress[] resolve(String host) throws UnknownHostException; }

    @Override
    public Plan prepare(Destination destination) {
        URI uri = destination.uri();
        validateUri(uri);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Set<String> names = destination.headerNames().stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (names.size() != destination.headerNames().size() || names.size() > 32
                || names.stream().anyMatch(name -> !safeHeaderName(name))) throw refused();
        var rules = properties.destinations().stream().filter(rule ->
                destination.policyKey().equals(rule.key()) && host.equals(rule.host())
                        && rule.pathPrefix() != null && rule.pathPrefix().startsWith("/")
                        && (uri.getRawPath().equals(rule.pathPrefix())
                            || uri.getRawPath().startsWith(rule.pathPrefix().endsWith("/")
                                ? rule.pathPrefix() : rule.pathPrefix() + "/"))
                        && rule.methods() != null && rule.methods().contains(destination.method())
                        && rule.headers() != null && rule.headers().containsAll(names)).toList();
        if (rules.size() != 1) throw refused();
        var rule = rules.getFirst();
        if (!Set.of("GET", "POST", "PUT", "PATCH", "HEAD").contains(destination.method())
                || destination.timeoutMillis() < 1 || destination.timeoutMillis() > 60_000
                || destination.timeoutMillis() > rule.timeoutMillis()
                || destination.maxResponseBytes() < 1 || destination.maxResponseBytes() > HARD_BODY_LIMIT
                || destination.maxResponseBytes() > rule.maxResponseBytes()
                || destination.body().length > HARD_BODY_LIMIT
                || destination.body().length > rule.maxRequestBytes()) throw refused();
        var future = dnsWorkers.submit(() -> lookup.resolve(host));
        try {
            InetAddress[] addresses = future.get(2, TimeUnit.SECONDS);
            if (addresses.length == 0 || addresses.length > 16
                    || Arrays.stream(addresses).anyMatch(address -> !publicAddress(address))) throw refused();
            return new Prepared(destination, addresses.clone(), names, clock.instant().plusSeconds(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw refused();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw refused();
        } finally { future.cancel(true); }
    }

    static void validateUri(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getHost() == null || uri.getHost().length() > 253
                || !uri.getHost().matches("(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                || uri.getHost().matches("[0-9.]+") || !uri.normalize().equals(uri)
                || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")
                || uri.getRawPath().contains("//")
                || uri.getRawPath().toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c|25|0[0-9a-f]|1[0-9a-f]|7f).*")) throw refused();
    }

    static boolean safeHeaderName(String name) {
        return name.matches("[a-z0-9][a-z0-9-]{0,63}") && !FORBIDDEN_HEADERS.contains(name)
                && !name.startsWith("x-forwarded-") && !name.startsWith("proxy-");
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255; int b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return a != 0 && a != 127 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 192 && b == 0 && (c == 0 || c == 2))
                    && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    && !(a == 203 && b == 0 && c == 113);
        }
        return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 0x01
                && (((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)
                    || (bytes[2] & 255) < 2));
    }

    @Override
    public Response exchange(Plan plan, Map<String, String> headers) throws IOException, InterruptedException {
        if (!(plan instanceof Prepared prepared) || !prepared.expiresAt().isAfter(clock.instant())) throw refused();
        Destination destination = prepared.destination();
        Set<String> actual = headers.keySet().stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (actual.size() != headers.size() || !prepared.names().containsAll(actual)
                || headers.values().stream().anyMatch(value -> value == null || value.length() > 8192
                    || value.chars().anyMatch(Character::isISOControl))) throw refused();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("outbound cancelled");
        String approvedHost = destination.uri().getHost();
        DnsResolver pinned = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!approvedHost.equalsIgnoreCase(host)) throw new UnknownHostException("unapproved host");
                return prepared.addresses().clone();
            }
            @Override public String resolveCanonicalHostname(String host) throws UnknownHostException {
                if (!approvedHost.equalsIgnoreCase(host)) throw new UnknownHostException("unapproved host");
                return approvedHost;
            }
        };
        Timeout timeout = Timeout.ofMilliseconds(destination.timeoutMillis());
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned)
                .setConnectionFactory(org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory.builder()
                        .http1Config(org.apache.hc.core5.http.config.Http1Config.custom()
                                .setMaxHeaderCount(64).setMaxLineLength(8192).build()).build())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout)
                        .setSocketTimeout(timeout).build()).build();
        HttpUriRequestBase request = new HttpUriRequestBase(destination.method(), destination.uri());
        request.setConfig(RequestConfig.custom().setResponseTimeout(timeout).setConnectionRequestTimeout(timeout)
                .setHardCancellationEnabled(true).build());
        if (destination.body().length > 0) request.setEntity(new ByteArrayEntity(destination.body(), null));
        headers.forEach(request::setHeader);
        request.setHeader("Accept-Encoding", "identity");
        AtomicBoolean expired = new AtomicBoolean();
        var timer = deadlines.schedule(() -> { expired.set(true); request.cancel(); },
                destination.timeoutMillis(), TimeUnit.MILLISECONDS);
        try (var client = HttpClients.custom().setConnectionManager(manager)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableContentCompression().disableAuthCaching().build();
             var response = client.executeOpen(null, request, null)) {
            Map<String, List<String>> safeHeaders = new LinkedHashMap<>();
            for (var header : response.getHeaders()) {
                String name = header.getName().toLowerCase(Locale.ROOT);
                if (RESPONSE_HEADERS.contains(name) && header.getValue().length() <= 1024
                        && header.getValue().chars().noneMatch(Character::isISOControl)) {
                    safeHeaders.computeIfAbsent(name, key -> new java.util.ArrayList<>()).add(header.getValue());
                }
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            boolean complete = true;
            String failure = null;
            if (response.getEntity() != null) {
                try (var input = response.getEntity().getContent()) {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        int count = input.read(buffer, 0, Math.min(buffer.length, destination.maxResponseBytes() - body.size() + 1));
                        if (count == -1) break;
                        int admitted = Math.min(count, destination.maxResponseBytes() - body.size());
                        body.write(buffer, 0, admitted);
                        if (admitted != count) { complete = false; failure = "RESPONSE_LIMIT_EXCEEDED"; request.cancel(); break; }
                    }
                } catch (IOException interruptedBody) { complete = false; failure = "RESPONSE_INCOMPLETE"; }
            }
            if (expired.get()) { complete = false; failure = "RESPONSE_DEADLINE_EXCEEDED"; }
            return new Response(response.getCode(), body.toByteArray(), safeHeaders, complete, failure);
        } finally { timer.cancel(false); manager.close(); }
    }

    /** Package-private construction is used only by isolated loopback transport tests. */
    record Prepared(Destination destination, InetAddress[] addresses, Set<String> names, Instant expiresAt) implements Plan { }

    private static IllegalArgumentException refused() { return new IllegalArgumentException("outbound destination policy refused"); }

    @PreDestroy
    void close() { dnsWorkers.shutdownNow(); deadlines.shutdownNow(); }
}
