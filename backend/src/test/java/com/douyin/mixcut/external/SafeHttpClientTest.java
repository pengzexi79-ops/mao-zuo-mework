package com.douyin.mixcut.external;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeHttpClientTest {

    @Test
    void policyRejectsUnsafeBounds() {
        assertThrows(IllegalArgumentException.class, () -> new OutboundNetworkPolicy(
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, Duration.ZERO, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OutboundNetworkPolicy(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 11, 1, Duration.ZERO, 1, 1));
    }

    @Test
    void unsafeUrlIsRejectedBeforeOpeningConnection() {
        SafeHttpClient client = new SafeHttpClient();
        assertThrows(IllegalArgumentException.class, () -> client.get("http://127.0.0.1:8760/api/system/env"));
        assertThrows(IllegalArgumentException.class, () -> client.get("http://localhost:8760/"));
    }

    @Test
    void unknownHostIsNotReturnedAsSuccessfulResponse() {
        SafeHttpClient client = new SafeHttpClient();
        assertThrows(IllegalArgumentException.class, () -> client.get("https://definitely-not-a-real-host.invalid/"));
    }

    @Test
    void defaultPolicyHasBoundedResponseAndRetryLimits() {
        OutboundNetworkPolicy policy = OutboundNetworkPolicy.defaults();
        assertEquals(3, policy.maxRedirects());
        assertEquals(2, policy.maxRetries());
        assertEquals(2_000_000L, policy.maxResponseBytes());
        assertEquals(200_000_000L, policy.maxDownloadBytes());
    }
}
