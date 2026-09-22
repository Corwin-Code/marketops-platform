package com.mimococo.marketops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback HTTP/1.1 peer that answers with exactly scripted bytes.
 *
 * <p>Header lines are written verbatim, in ISO-8859-1, so name case, repeated names, over-long
 * values and control characters reach the client as a provider could send them. Every request
 * is recorded as {@code "METHOD path"}. An unscripted request is recorded and answered 500.
 */
final class ScriptedWaitResponder implements AutoCloseable {
    record Answer(int status, List<String> headerLines, byte[] body) {
        Answer {
            headerLines = List.copyOf(headerLines);
            body = body.clone();
        }

        static Answer json(int status, String document, String... headerLines) {
            return new Answer(status, List.of(headerLines), document.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final int HEAD_LIMIT = 16 * 1024;
    private final ServerSocket socket;
    private final Deque<Answer> script = new ArrayDeque<>();
    private final Thread acceptor;
    final List<String> received = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();

    ScriptedWaitResponder() throws IOException {
        socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = Thread.ofPlatform().daemon().name("scripted-wait-responder").start(this::serve);
    }

    InetSocketAddress address() {
        return new InetSocketAddress(socket.getInetAddress(), socket.getLocalPort());
    }

    synchronized ScriptedWaitResponder then(Answer answer) {
        script.addLast(answer);
        return this;
    }

    private synchronized Answer next() {
        return script.pollFirst();
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket connection = socket.accept()) {
                connection.setSoTimeout(5_000);
                answer(connection);
            } catch (IOException closedOrBroken) {
                if (socket.isClosed()) return;
            }
        }
    }

    private void answer(Socket connection) throws IOException {
        InputStream input = connection.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = input.read();
            if (next < 0 || head.size() >= HEAD_LIMIT) return;
            head.write(next);
            matched = (next == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] requestLine = lines[0].split(" ");
        int length = 0;
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(line.substring("content-length:".length()).trim());
                } catch (NumberFormatException malformed) {
                    return;
                }
            }
        }
        // A request this responder cannot frame is dropped; the client sees the exchange fail.
        if (length < 0 || length > HEAD_LIMIT) return;
        byte[] body = input.readNBytes(length);
        received.add(requestLine[0] + " " + requestLine[1]);
        bodies.add(new String(body, StandardCharsets.UTF_8));
        Answer answer = next();
        if (answer == null) answer = Answer.json(500, "{}");
        StringBuilder response = new StringBuilder("HTTP/1.1 ").append(answer.status()).append(" Scripted\r\n");
        for (String line : answer.headerLines()) response.append(line).append("\r\n");
        response.append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(answer.body().length).append("\r\n")
                .append("Connection: close\r\n\r\n");
        OutputStream output = connection.getOutputStream();
        output.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(answer.body());
        output.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
        try {
            acceptor.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
