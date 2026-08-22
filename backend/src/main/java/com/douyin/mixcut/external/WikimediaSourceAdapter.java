package com.douyin.mixcut.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Wikimedia Commons adapter: public API, explicit license allowlist, no account credentials. */
@Component
@RequiredArgsConstructor
public class WikimediaSourceAdapter implements RemoteSourceAdapter {
    private static final String API = "https://commons.wikimedia.org/w/api.php?";
    private static final Pattern CC_BY_ONLY = Pattern.compile("\\bcc[\\s_-]?by(?!\\s*[-_\\s]?(sa|nc|nd))", Pattern.CASE_INSENSITIVE);
    private static final Pattern LICENSES_BY_ONLY = Pattern.compile("licenses/by(?![-_]?(sa|nc|nd))", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTION_ONLY = Pattern.compile("\\battribution(?!\\s*[-_]?(share|non|no))", Pattern.CASE_INSENSITIVE);
    private static final Set<String> PLACEHOLDERS = Set.of("demo", "example", "placeholder", "sample", "test");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String sourceKey() { return "wikimedia"; }

    @Override
    public boolean supports(String type) { return "audio".equalsIgnoreCase(type) || "video".equalsIgnoreCase(type); }

    @Override
    public List<CrawlerGateway.RemoteItem> map(JsonNode response, String type, int limit) {
        return mapResponse(response, type, limit);
    }

    @Override
    public String query(String keyword, String type, int limit) {
        return "action=query&format=json&formatversion=2&generator=search&gsrnamespace=6&gsrlimit="
                + Math.min(20, Math.max(1, limit)) + "&gsrsearch=" + encode((keyword == null ? "" : keyword) + " filetype:" + type)
                + "&prop=imageinfo&iiprop=url%7Cextmetadata";
    }

    /** Map a MediaWiki API response through the same license and placeholder rules. */
    List<CrawlerGateway.RemoteItem> mapResponse(JsonNode root, String type, int limit) {
        if (!supports(type) || root == null || !root.path("query").path("pages").isArray()) return List.of();
        List<CrawlerGateway.RemoteItem> result = new ArrayList<>();
        for (JsonNode page : root.path("query").path("pages")) {
            JsonNode info = page.path("imageinfo").path(0);
            String download = info.path("url").asText("");
            String license = info.path("extmetadata").path("LicenseShortName").path("value").asText("");
            if (license.isBlank()) license = info.path("extmetadata").path("License").path("value").asText("");
            String title = page.path("title").asText("");
            if (download.isBlank() || !isWhitelistedLicense(license) || isPlaceholder(title)) continue;
            CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
            item.setSource(sourceKey());
            item.setType(type.toLowerCase(Locale.ROOT));
            item.setTitle(title.replaceFirst("^File:", ""));
            item.setPageUrl("https://commons.wikimedia.org/wiki/" + encodePath(title));
            item.setDownloadUrl(download);
            item.setPreviewUrl(download);
            item.setLicense(license);
            item.setLicenseUrl(info.path("extmetadata").path("LicenseUrl").path("value").asText(""));
            item.setTags(info.path("extmetadata").path("Artist").path("value").asText("").replaceAll("<[^>]+>", "").trim());
            result.add(item);
            if (result.size() >= limit) break;
        }
        return result;
    }

    static boolean isWhitelistedLicense(String value) {
        if (value == null || value.isBlank()) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return v.contains("cc0") || v.contains("cc 0") || v.contains("public domain") || v.contains("publicdomain")
                || v.contains("cc-pd") || v.contains("cc pd") || CC_BY_ONLY.matcher(v).find()
                || LICENSES_BY_ONLY.matcher(v).find() || ATTRIBUTION_ONLY.matcher(v).find();
    }

    static boolean isPlaceholder(String title) {
        if (title == null || title.isBlank()) return false;
        String name = title.replaceFirst("^File:", "");
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.trim().toLowerCase(Locale.ROOT);
        return PLACEHOLDERS.contains(name) || Pattern.compile("\\b(demo|example|placeholder|sample)\\b").matcher(name).find();
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private String encodePath(String value) {
        String[] parts = value.split("/", -1);
        return java.util.Arrays.stream(parts).map(this::encode).reduce((a, b) -> a + "/" + b).orElse("");
    }
}
