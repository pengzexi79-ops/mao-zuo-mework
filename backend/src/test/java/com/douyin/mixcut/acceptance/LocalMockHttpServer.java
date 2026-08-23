package com.douyin.mixcut.acceptance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only deterministic HTTP server for acceptance tests. */
final class LocalMockHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();

    private LocalMockHttpServer(HttpServer server) {
        this.server = server;
        server.createContext("/ok", this::ok);
        server.createContext("/rate-limit", this::rateLimit);
        server.createContext("/server-error", this::serverError);
        server.createContext("/timeout", this::timeout);
        server.createContext("/retry-success", this::retrySuccess);
        server.createContext("/idempotency", this::idempotency);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "acceptance-http-mock");
            thread.setDaemon(true);
            return thread;
        }));
    }

    static LocalMockHttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        LocalMockHttpServer mock = new LocalMockHttpServer(server);
        server.start();
        return mock;
    }

    String url(String path) {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("mock path");
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    int count(String path) {
        AtomicInteger count = counts.get(path);
        return count == null ? 0 : count.get();
    }

    private void ok(HttpExchange exchange) throws IOException {
        increment(exchange.getRequestURI().getPath());
        respond(exchange, 200, "{\"ok\":true}", Map.of());
    }

    private void rateLimit(HttpExchange exchange) throws IOException {
        increment(exchange.getRequestURI().getPath());
        respond(exchange, 429, "{\"error\":\"rate_limited\"}", Map.of("Retry-After", "1"));
    }

    private void serverError(HttpExchange exchange) throws IOException {
        increment(exchange.getRequestURI().getPath());
        respond(exchange, 500, "{\"error\":\"server_error\"}", Map.of());
    }

    private void timeout(HttpExchange exchange) throws IOException {
        increment(exchange.getRequestURI().getPath());
        sleep(250L);
        respond(exchange, 200, "{\"ok\":true}", Map.of());
    }

    private void retrySuccess(HttpExchange exchange) throws IOException {
        int count = increment(exchange.getRequestURI().getPath());
        if (count < 3) respond(exchange, 503, "{\"error\":\"try_again\"}", Map.of());
        else respond(exchange, 200, "{\"ok\":true,\"attempt\":" + count + "}", Map.of());
    }

    private void idempotency(HttpExchange exchange) throws IOException {
        increment(exchange.getRequestURI().getPath());
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || key.isBlank()) {
            respond(exchange, 400, "{\"error\":\"missing_idempotency_key\"}", Map.of());
            return;
        }
        String digest = Integer.toHexString(key.hashCode());
        idempotencyKeys.putIfAbsent(digest, key);
        respond(exchange, idempotencyKeys.get(digest).equals(key) ? 200 : 409,
                "{\"accepted\":true}", Map.of());
    }

    private int increment(String path) {
        return counts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private void respond(HttpExchange exchange, int status, String body, Map<String, String> headers) throws IOException {
        headers.forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
