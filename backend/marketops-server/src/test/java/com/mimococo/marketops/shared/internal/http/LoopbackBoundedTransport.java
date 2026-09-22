package com.mimococo.marketops.shared.internal.http;

import com.mimococo.marketops.shared.port.OutboundHttp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Map;

/**
 * The production transport pointed at one loopback responder, for isolated integration tests.
 *
 * <p>{@link #prepare} first runs the production policy decision unchanged: the destination
 * allowlist, URI, header-name, size and timeout checks, and the public-address check against a
 * fixed public answer that is never connected. Only the resulting plan is then retargeted to the
 * loopback responder. {@link #exchange} is the production exchange, so the real Apache client and
 * the production response-header filter run.
 */
public final class LoopbackBoundedTransport implements OutboundHttp, AutoCloseable {
    private static final byte[] POLICY_ONLY_PUBLIC_ANSWER = {(byte) 93, (byte) 184, (byte) 215, (byte) 14};

    private final BoundedOutboundHttp production;
    private final InetSocketAddress responder;

    public LoopbackBoundedTransport(OutboundDestinationProperties rules, InetSocketAddress responder, Clock clock) {
        if (!responder.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("the responder must be a loopback address");
        }
        this.responder = responder;
        this.production = new BoundedOutboundHttp(rules, host -> policyAnswer(), clock);
    }

    private static InetAddress[] policyAnswer() throws UnknownHostException {
        return new InetAddress[]{InetAddress.getByAddress(POLICY_ONLY_PUBLIC_ANSWER)};
    }

    @Override
    public Plan prepare(Destination destination) {
        var decided = (BoundedOutboundHttp.Prepared) production.prepare(destination);
        URI uri = decided.destination().uri();
        var local = new Destination(destination.policyKey(),
                URI.create("http://" + uri.getHost() + ":" + responder.getPort() + uri.getRawPath()
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())),
                destination.method(), destination.headerNames(), destination.body(),
                destination.timeoutMillis(), destination.maxResponseBytes());
        return new BoundedOutboundHttp.Prepared(local, new InetAddress[]{responder.getAddress()},
                decided.names(), decided.expiresAt());
    }

    @Override
    public Response exchange(Plan plan, Map<String, String> headers) throws IOException, InterruptedException {
        return production.exchange(plan, headers);
    }

    @Override
    public void close() {
        production.close();
    }
}
