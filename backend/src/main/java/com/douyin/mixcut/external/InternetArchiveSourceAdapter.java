package com.douyin.mixcut.external;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Internet Archive adapter: advanced search followed by bounded metadata expansion. */
@Component
@RequiredArgsConstructor
public class InternetArchiveSourceAdapter implements RemoteSourceAdapter {
    private static final String SEARCH = "https://archive.org/advancedsearch.php?";
    private static final String METADATA = "https://archive.org/metadata/";

    @Override
    public String sourceKey() { return "archive"; }

    @Override
    public boolean supports(String type) { return "audio".equalsIgnoreCase(type) || "video".equalsIgnoreCase(type); }

    @Override
    public List<CrawlerGateway.RemoteItem> search(String keyword, String type, int limit, JsonFetcher fetcher) {
        if (!supports(type)) throw new IllegalArgumentException("Internet Archive 不支持该媒体类型");
        JsonNode search = fetcher.get(SEARCH + query(keyword, type, limit));
        if (search == null) return List.of();
        List<CrawlerGateway.RemoteItem> result = new ArrayList<>();
        for (JsonNode doc : search.path("response").path("docs")) {
            String identifier = doc.path("identifier").asText("");
            String licenseUrl = doc.path("licenseurl").asText("");
            if (identifier.isBlank() || !isWhitelistedLicense(licenseUrl)) continue;
            JsonNode metadata = fetcher.get(METADATA + encodePath(identifier));
            if (metadata == null) continue;
            result.addAll(mapMetadata(metadata, type, limit - result.size(), doc.path("title").asText(identifier), licenseUrl, identifier));
            if (result.size() >= limit) break;
        }
        return result.stream().limit(limit).toList();
    }

    @Override
    public List<CrawlerGateway.RemoteItem> map(JsonNode response, String type, int limit) {
        return mapMetadata(response, type, limit, response == null ? "" : response.path("metadata").path("title").asText(""),
                response == null ? "" : response.path("metadata").path("licenseurl").asText(""),
                response == null ? "" : response.path("metadata").path("identifier").asText(""));
    }

    @Override
    public String query(String keyword, String type, int limit) {
        String mediaType = "video".equalsIgnoreCase(type) ? "movies" : "audio";
        String phrase = keyword == null || keyword.isBlank() ? "video footage" : keyword.trim().replace('"', ' ');
        return "q=" + encode("mediatype:" + mediaType + " AND title:(\"" + phrase + "\")")
                + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=licenseurl&rows="
                + Math.min(10, Math.max(1, limit)) + "&output=json";
    }

    private List<CrawlerGateway.RemoteItem> mapMetadata(JsonNode metadata, String type, int limit, String title, String licenseUrl, String identifier) {
        if (metadata == null || limit <= 0 || identifier.isBlank() || !isWhitelistedLicense(licenseUrl)) return List.of();
        List<CrawlerGateway.RemoteItem> result = new ArrayList<>();
        for (JsonNode file : metadata.path("files")) {
            String name = file.path("name").asText("");
            String lower = name.toLowerCase(Locale.ROOT);
            boolean accepted = "audio".equalsIgnoreCase(type)
                    ? lower.matches(".*\\.(mp3|ogg|opus|wav|flac|m4a|aac)$")
                    : lower.matches(".*\\.(mp4|webm|mkv|mov|avi|m4v)$");
            if (!accepted || isPlaceholder(name)) continue;
            CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
            item.setSource(sourceKey());
            item.setType(type.toLowerCase(Locale.ROOT));
            item.setTitle(title + " · " + name);
            item.setPageUrl("https://archive.org/details/" + encodePath(identifier));
            item.setDownloadUrl("https://archive.org/download/" + encodePath(identifier) + "/" + encodePath(name));
            item.setPreviewUrl(item.getDownloadUrl());
            item.setDuration(file.path("length").isNumber() && file.path("length").asDouble() > 0 ? file.path("length").asDouble() : null);
            item.setLicense(licenseLabel(licenseUrl));
            item.setLicenseUrl(licenseUrl);
            result.add(item);
            if (result.size() >= limit) break;
        }
        return result;
    }

    static boolean isWhitelistedLicense(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.toLowerCase(Locale.ROOT);
        return value.contains("cc0") || value.contains("publicdomain") || value.contains("public domain")
                || Pattern.compile("licenses/by(?![-_]?(sa|nc|nd))", Pattern.CASE_INSENSITIVE).matcher(value).find();
    }

    static String licenseLabel(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.contains("cc0") || value.contains("publicdomain/zero")) return "CC0";
        if (value.contains("publicdomain") || value.contains("public domain")) return "Public Domain";
        var match = Pattern.compile("licenses/by/(\\d\\.\\d)").matcher(value);
        return match.find() ? "CC BY " + match.group(1) : raw.trim();
    }

    private boolean isPlaceholder(String value) {
        String name = value == null ? "" : value.replaceFirst("^File:", "");
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return Pattern.compile("\\b(demo|example|placeholder|sample|test)\\b", Pattern.CASE_INSENSITIVE).matcher(name).find();
    }

    private String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    private String encodePath(String value) { return java.util.Arrays.stream(value.split("/", -1)).map(part -> java.net.URLEncoder.encode(part, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")).reduce((a,b) -> a + "/" + b).orElse(""); }
}
