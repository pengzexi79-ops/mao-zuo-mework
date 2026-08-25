package com.douyin.mixcut.external.media;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMediaHttpTransportTest {
    @Test
    void streamsChunkedResponseToCallerStagingPath(@TempDir Path tempDir) throws Exception {
        try (TestServer server = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[4096]);
            exchange.close();
        })) {
            Path staging = tempDir.resolve("video.part");
            var response = new DefaultMediaHttpTransport().download(request(server), staging, 10_000);

            assertEquals(200, response.status());
            assertEquals(4096, response.bytesWritten());
            assertEquals(4096, Files.size(staging));
        }
    }

    @Test
    void doesNotFollowProviderRedirect(@TempDir Path tempDir) throws Exception {
        try (TestServer server = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/internal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        })) {
            Path staging = tempDir.resolve("redirect.part");
            var response = new DefaultMediaHttpTransport().download(request(server), staging, 10_000);
            assertEquals(302, response.status());
            assertFalse(Files.exists(staging));
        }
    }

    @Test
    void rejectsKnownContentLengthBeforeWritingStaging(@TempDir Path tempDir) throws Exception {
        try (TestServer server = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 4096);
            exchange.getResponseBody().write(new byte[4096]);
            exchange.close();
        })) {
            Path staging = tempDir.resolve("video.part");

            assertThrows(MediaHttpTransport.DownloadLimitExceededException.class,
                    () -> new DefaultMediaHttpTransport().download(request(server), staging, 2048));
            assertFalse(Files.exists(staging));
        }
    }

    @Test
    void rejectsChunkedResponseAfterCumulativeLimit(@TempDir Path tempDir) throws Exception {
        try (TestServer server = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[4096]);
            exchange.close();
        })) {
            Path staging = tempDir.resolve("video.part");

            assertThrows(MediaHttpTransport.DownloadLimitExceededException.class,
                    () -> new DefaultMediaHttpTransport().download(request(server), staging, 2048));
            assertTrue(Files.size(staging) <= 2048);
        }
    }

    private MediaHttpTransport.Request request(TestServer server) {
        return new MediaHttpTransport.Request("GET", "http://127.0.0.1:" + server.port() + "/video", Map.of(), new byte[0], "");
    }

    private static class TestServer implements AutoCloseable {
        private final HttpServer server;

        private TestServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/video", handler);
            server.start();
        }

        private int port() { return server.getAddress().getPort(); }

        @Override
        public void close() { server.stop(0); }
    }
}
