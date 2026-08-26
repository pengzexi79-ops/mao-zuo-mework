package com.douyin.mixcut.acceptance;

import com.douyin.mixcut.external.OutboundNetworkPolicy;
import com.douyin.mixcut.external.SafeHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMockHttpServerTest {
    private final OutboundNetworkPolicy policy = new OutboundNetworkPolicy(
            Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(800),
            0, 2, Duration.ofMillis(10), 100_000, 100_000);

    @Test
    void servesSuccessRateLimitAndServerErrorOnLoopbackOnly() throws Exception {
        try (LocalMockHttpServer server = LocalMockHttpServer.start()) {
            SafeHttpClient client = new SafeHttpClient(policy, value -> value);
            assertTrue(client.get(server.url("/ok")).successful());
            SafeHttpClient.Response rate = client.get(server.url("/rate-limit"), Map.of(), false);
            assertEquals(429, rate.status());
            assertEquals("1", rate.headers().get("Retry-after").get(0));
            assertEquals(500, client.get(server.url("/server-error"), Map.of(), false).status());
            assertEquals(1, server.count("/ok"));
        }
    }

    @Test
    void retriesTransientGetAndBoundsTimeout() throws Exception {
        try (LocalMockHttpServer server = LocalMockHttpServer.start()) {
            SafeHttpClient client = new SafeHttpClient(policy, value -> value);
            SafeHttpClient.Response retry = client.get(server.url("/retry-success"));
            assertTrue(retry.successful());
            assertEquals(3, server.count("/retry-success"));
            long started = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> client.get(server.url("/timeout"), Map.of(), false));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(elapsedMs < 2_000);
        }
    }
}
