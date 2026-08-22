package com.douyin.mixcut.external;

import java.time.Duration;

/** Shared, bounded policy for outbound HTTP calls. It does not accept caller-supplied URLs or headers. */
public record OutboundNetworkPolicy(
        Duration connectTimeout,
        Duration readTimeout,
        Duration totalTimeout,
        int maxRedirects,
        int maxRetries,
        Duration retryBackoff,
        long maxResponseBytes,
        long maxDownloadBytes) {

    public static OutboundNetworkPolicy defaults() {
        return new OutboundNetworkPolicy(
                Duration.ofSeconds(20), Duration.ofSeconds(30), Duration.ofSeconds(120),
                3, 2, Duration.ofMillis(400), 2_000_000L, 200_000_000L);
    }

    public OutboundNetworkPolicy {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) throw new IllegalArgumentException("connect timeout");
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) throw new IllegalArgumentException("read timeout");
        if (totalTimeout == null || totalTimeout.isNegative() || totalTimeout.isZero()) throw new IllegalArgumentException("total timeout");
        if (maxRedirects < 0 || maxRedirects > 10) throw new IllegalArgumentException("redirect limit");
        if (maxRetries < 0 || maxRetries > 5) throw new IllegalArgumentException("retry limit");
        if (retryBackoff == null || retryBackoff.isNegative()) throw new IllegalArgumentException("retry backoff");
        if (maxResponseBytes < 1 || maxDownloadBytes < 1) throw new IllegalArgumentException("response limits");
    }
}
