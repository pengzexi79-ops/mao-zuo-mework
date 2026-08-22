package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.external.SafeHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit, fixed-target connectivity checks. It never accepts a browser-supplied URL. */
@Service
@RequiredArgsConstructor
public class ConnectivityService {
    private record Target(String key, String url, String configuredKey, boolean requiresNetwork) {}

    private static final List<Target> TARGETS = List.of(
            new Target("wikimedia", "https://commons.wikimedia.org/w/api.php?action=query&format=json&origin=*", "", true),
            new Target("archive", "https://archive.org/advancedsearch.php?q=mediatype%3Amovies&rows=1&output=json", "", true),
            new Target("pexels", "https://api.pexels.com/v1/videos/search?query=test&per_page=1", "pexels", true),
            new Target("pixabay", "https://pixabay.com/api/videos/?q=test&per_page=1", "pixabay", true),
            new Target("freesound", "https://freesound.org/apiv2/search/text/?query=test&page_size=1", "freesound", true),
            new Target("edge-tts", "https://speech.platform.bing.com/", "", true)
    );

    private final SafeHttpClient http;
    private final AppProps props;

    public List<Map<String, Object>> checkAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Target target : TARGETS) result.add(check(target));
        return result;
    }

    public Map<String, Object> check(String key) {
        Target target = TARGETS.stream().filter(item -> item.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的联网目标"));
        return check(target);
    }

    private Map<String, Object> check(Target target) {
        boolean configured = configured(target);
        Map<String, Object> report = base(target, configured);
        if (!configured) {
            report.put("errorCode", "NOT_CONFIGURED");
            report.put("checkedAt", Instant.now().toString());
            return report;
        }
        long started = System.nanoTime();
        try {
            SafeHttpClient.Response response = http.get(target.url(), Map.of("Accept", "application/json", "User-Agent", "Mework-Connectivity/1"), true);
            int status = response.status();
            report.put("reachable", true);
            report.put("httpStatus", status);
            report.put("latencyMs", elapsedMs(started));
            if (status == 401 || status == 403) report.put("errorCode", "AUTH_REQUIRED");
            else if (status == 429) {
                report.put("errorCode", "RATE_LIMITED");
                report.put("rateLimited", true);
                report.put("retryAfterSeconds", retryAfter(response));
            } else if (status >= 500) report.put("errorCode", "REMOTE_SERVER_ERROR");
            else if (status >= 400) report.put("errorCode", "REMOTE_HTTP_ERROR");
            else report.put("errorCode", null);
        } catch (Exception error) {
            report.put("reachable", false);
            report.put("latencyMs", elapsedMs(started));
            report.put("errorCode", classify(error));
        }
        return report;
    }

    private Map<String, Object> base(Target target, boolean configured) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("target", target.key());
        report.put("configured", configured);
        report.put("requiresNetwork", target.requiresNetwork());
        report.put("reachable", false);
        report.put("dns", Map.of("ok", false, "errorCode", "NOT_CHECKED"));
        report.put("tcp", Map.of("ok", false, "errorCode", "NOT_CHECKED"));
        report.put("tls", Map.of("ok", false, "errorCode", "NOT_CHECKED"));
        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("configured", proxyConfigured());
        proxy.put("used", proxyConfigured());
        report.put("proxy", proxy);
        report.put("httpStatus", null);
        report.put("rateLimited", false);
        report.put("retryAfterSeconds", null);
        report.put("latencyMs", null);
        report.put("checkedAt", Instant.now().toString());
        report.put("errorCode", "NOT_CHECKED");
        return report;
    }

    private boolean configured(Target target) {
        return switch (target.configuredKey()) {
            case "pexels" -> props.getPexelsApiKey() != null && !props.getPexelsApiKey().isBlank();
            case "pixabay" -> props.getPixabayApiKey() != null && !props.getPixabayApiKey().isBlank();
            case "freesound" -> props.getFreesoundApiKey() != null && !props.getFreesoundApiKey().isBlank();
            default -> true;
        };
    }

    private boolean proxyConfigured() {
        return hasEnv("HTTPS_PROXY") || hasEnv("https_proxy") || hasEnv("HTTP_PROXY") || hasEnv("http_proxy");
    }

    private boolean hasEnv(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private int elapsedMs(long started) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, (System.nanoTime() - started) / 1_000_000));
    }

    private Integer retryAfter(SafeHttpClient.Response response) {
        if (response.headers() == null) return null;
        String value = response.headers().entrySet().stream()
                .filter(entry -> entry.getKey() != null && "retry-after".equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        try { return value == null ? null : Math.min(3600, Math.max(0, Integer.parseInt(value.trim()))); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String classify(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getSimpleName().toLowerCase();
            if (name.contains("unknownhost")) return "DNS_FAILED";
            if (name.contains("ssl") || name.contains("handshake")) return "TLS_FAILED";
            if (name.contains("timeout")) return "TIMEOUT";
            if (name.contains("connect")) return "TCP_FAILED";
            current = current.getCause();
        }
        return "NETWORK_FAILED";
    }
}
