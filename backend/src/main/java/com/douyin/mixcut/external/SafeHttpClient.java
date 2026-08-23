package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.security.UrlGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Small bounded transport primitive. Callers decide whether a request is safe to retry. */
@Slf4j
@Component
public class SafeHttpClient {
    public record Response(int status, Map<String, List<String>> headers, byte[] body, String finalUrl) {
        public String text() { return new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8); }
        public boolean successful() { return status >= 200 && status < 300; }
    }

    private final OutboundNetworkPolicy policy;
    private final Function<String, String> urlValidator;

    public SafeHttpClient() {
        this(OutboundNetworkPolicy.defaults(), UrlGuard::validate);
    }

    @Autowired
    public SafeHttpClient(AppProps props) {
        this(new OutboundNetworkPolicy(
                java.time.Duration.ofSeconds(Math.max(1, props.getNetworkConnectTimeoutSec())),
                java.time.Duration.ofSeconds(Math.max(1, props.getNetworkReadTimeoutSec())),
                java.time.Duration.ofSeconds(Math.max(1, props.getNetworkTotalTimeoutSec())),
                Math.max(0, props.getNetworkMaxRedirects()),
                Math.max(0, props.getNetworkMaxRetries()),
                java.time.Duration.ofMillis(Math.max(0, props.getNetworkRetryBackoffMs())),
                Math.max(1, props.getNetworkMaxResponseBytes()),
                Math.max(1, props.getNetworkMaxDownloadBytes())), UrlGuard::validate);
    }

    public SafeHttpClient(OutboundNetworkPolicy policy) {
        this(policy, UrlGuard::validate);
    }

    /**
     * Explicit validation seam for deterministic transport tests. Application wiring always uses
     * {@link UrlGuard#validate(String)}; this constructor is never selected by configuration.
     */
    public SafeHttpClient(OutboundNetworkPolicy policy, Function<String, String> urlValidator) {
        this.policy = policy;
        this.urlValidator = urlValidator;
    }

    public Response get(String url) {
        return execute("GET", url, null, Map.of(), true);
    }

    public Response get(String url, Map<String, String> headers, boolean retryable) {
        return execute("GET", url, null, headers, retryable);
    }

    /** POST is non-replayable by default; pass retryable only for an explicitly idempotent operation. */
    public Response post(String url, byte[] body, Map<String, String> headers, boolean retryable) {
        return execute("POST", url, body, headers, retryable);
    }

    private Response execute(String method, String rawUrl, byte[] body, Map<String, String> headers, boolean retryable) {
        long deadline = System.nanoTime() + policy.totalTimeout().toNanos();
        String current = urlValidator.apply(rawUrl);
        int attempts = retryable ? policy.maxRetries() + 1 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                Response response = once(method, current, body, headers, deadline);
                if (retryable && shouldRetry(response.status()) && attempt + 1 < attempts) {
                    sleepBackoff(attempt, deadline);
                    continue;
                }
                return response;
            } catch (RuntimeException error) {
                if (!retryable || attempt + 1 >= attempts) throw error;
                sleepBackoff(attempt, deadline);
            }
        }
        throw new IllegalStateException("HTTP request deadline exceeded");
    }

    private Response once(String method, String urlValue, byte[] body, Map<String, String> headers, long deadline) {
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(urlValidator.apply(urlValue));
            connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMillis(policy.connectTimeout(), deadline));
            connection.setReadTimeout(timeoutMillis(policy.readTimeout(), deadline));
            HttpURLConnection activeConnection = connection;
            headers.forEach((key, value) -> {
                if (key != null && value != null) activeConnection.setRequestProperty(key, value);
            });
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (var output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null || location.isBlank()) throw new IllegalStateException("redirect missing location");
                throw new IllegalStateException("redirect requires caller policy");
            }
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = readBounded(stream, policy.maxResponseBytes());
            return new Response(status, connection.getHeaderFields(), bytes, uri.toString());
        } catch (Exception error) {
            log.debug("bounded HTTP request failed: {}", safeUrl(urlValue));
            throw new IllegalStateException("outbound HTTP request failed", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private byte[] readBounded(InputStream input, long maxBytes) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) throw new IllegalStateException("response exceeds configured limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private int timeoutMillis(java.time.Duration configured, long deadline) {
        long remaining = Math.max(1, deadline - System.nanoTime());
        long millis = Math.min(configured.toMillis(), Math.max(1, remaining / 1_000_000));
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private void sleepBackoff(int attempt, long deadline) {
        long millis = Math.min(policy.retryBackoff().toMillis() * (attempt + 1), Math.max(0, (deadline - System.nanoTime()) / 1_000_000));
        if (millis <= 0) throw new IllegalStateException("outbound HTTP request deadline exceeded");
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("outbound HTTP request interrupted", e); }
    }

    private boolean shouldRetry(int status) { return status == 408 || status == 425 || status == 429 || status >= 500; }
    private boolean isSensitiveHeader(String key) { String lower = key.toLowerCase(Locale.ROOT); return lower.contains("authorization") || lower.contains("api-key") || lower.contains("token"); }
    private String safeUrl(String raw) { try { URI uri = URI.create(raw); return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath(); } catch (Exception e) { return "<invalid-url>"; } }
}
