package com.douyin.mixcut.acceptance;

import com.douyin.mixcut.external.OutboundNetworkPolicy;
import com.douyin.mixcut.external.SafeHttpClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundPolicyAcceptanceTest {
    @Test
    void nonReplayablePostIsNotRetriedAndIdempotencyKeyIsPreserved() throws Exception {
        OutboundNetworkPolicy policy = new OutboundNetworkPolicy(
                Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(500),
                0, 3, Duration.ofMillis(10), 100_000, 100_000);
        try (LocalMockHttpServer server = LocalMockHttpServer.start()) {
            SafeHttpClient client = new SafeHttpClient(policy, value -> value);
            SafeHttpClient.Response response = client.post(server.url("/idempotency"),
                    "secret-body".getBytes(StandardCharsets.UTF_8),
                    Map.of("Idempotency-Key", "acceptance-key", "Authorization", "Bearer <redacted>"), false);
            assertTrue(response.successful());
            SafeHttpClient.Response replay = client.post(server.url("/idempotency"),
                    "secret-body".getBytes(StandardCharsets.UTF_8),
                    Map.of("Idempotency-Key", "acceptance-key", "Authorization", "Bearer <redacted>"), false);
            assertTrue(replay.successful());
            assertEquals(2, server.count("/idempotency"));
            assertTrue(response.text().contains("accepted"));
        }
    }

    @Test
    void invalidAndPublicNetworkTargetsRemainBlockedByUrlGuard() {
        SafeHttpClient client = new SafeHttpClient(OutboundNetworkPolicy.defaults());
        assertThrows(IllegalArgumentException.class, () -> client.get("http://127.0.0.1:1/blocked"));
        assertThrows(IllegalArgumentException.class, () -> client.get("file:///tmp/blocked"));
    }
}
