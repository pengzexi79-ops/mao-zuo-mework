package com.douyin.mixcut.external;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Openverse public API adapter. Anonymous requests are supported; registered access is optional. */
@Component
@RequiredArgsConstructor
public class OpenverseSourceAdapter implements RemoteSourceAdapter {
    private static final String AUDIO_API = "https://api.openverse.org/v1/audio/?";
    private static final String IMAGE_API = "https://api.openverse.org/v1/images/?";

    @Override
    public String sourceKey() { return "openverse"; }

    @Override
    public boolean supports(String type) {
        return "audio".equalsIgnoreCase(type) || "image".equalsIgnoreCase(type);
    }

    @Override
    public List<CrawlerGateway.RemoteItem> search(String keyword, String type, int limit, JsonFetcher fetcher) {
        if (!supports(type)) throw new IllegalArgumentException("Openverse 当前适配音频和图片检索");
        String api = "image".equalsIgnoreCase(type) ? IMAGE_API : AUDIO_API;
        JsonNode response = fetcher.get(api + query(keyword, type, limit));
        if (response == null) {
            throw new IllegalStateException("Openverse 返回为空或网络不可达，请稍后重试或打开官方页面确认服务状态");
        }
        return map(response, type, limit);
    }

    @Override
    public List<CrawlerGateway.RemoteItem> map(JsonNode response, String type, int limit) {
        if (!supports(type) || response == null || !response.path("results").isArray()) return List.of();
        List<CrawlerGateway.RemoteItem> result = new ArrayList<>();
        for (JsonNode row : response.path("results")) {
            String mediaUrl = row.path("url").asText("");
            String license = row.path("license").asText("").toLowerCase(Locale.ROOT);
            if (mediaUrl.isBlank()) continue;
            CrawlerGateway.RemoteItem item = new CrawlerGateway.RemoteItem();
            item.setSource(sourceKey());
            item.setType(type.toLowerCase(Locale.ROOT));
            String defaultTitle = "image".equalsIgnoreCase(type) ? "Openverse 图片" : "Openverse 音频";
            item.setTitle(row.path("title").asText(row.path("creator").asText(defaultTitle)));
            item.setPageUrl(row.path("foreign_landing_url").asText(row.path("detail_url").asText("https://openverse.org/")));
            item.setDownloadUrl(mediaUrl);
            item.setPreviewUrl("image".equalsIgnoreCase(type)
                    ? row.path("thumbnail").asText(row.path("thumbnail_url").asText(mediaUrl))
                    : mediaUrl);
            item.setDuration("audio".equalsIgnoreCase(type) && row.path("duration").isNumber()
                    ? row.path("duration").asDouble() : null);
            item.setLicense(license.isBlank() ? "许可未标注，请打开来源页确认" : licenseLabel(license));
            item.setLicenseUrl(row.path("license_url").asText("https://creativecommons.org/licenses/"));
            item.setTags(tags(row.path("tags")));
            result.add(item);
            if (result.size() >= limit) break;
        }
        return result;
    }

    @Override
    public String query(String keyword, String type, int limit) {
        String extensions = "image".equalsIgnoreCase(type) ? "jpg,jpeg,png,webp" : "mp3,ogg,wav,m4a";
        return "q=" + java.net.URLEncoder.encode(keyword == null ? "" : keyword, java.nio.charset.StandardCharsets.UTF_8)
                + "&page_size=" + Math.min(20, Math.max(1, limit))
                + "&filter_dead=true&license_type=commercial&extension=" + extensions;
    }

    static boolean isCommercialLicense(String license) {
        return "cc0".equals(license) || "pdm".equals(license) || "by".equals(license);
    }

    static String licenseLabel(String license) {
        return switch (license) {
            case "cc0" -> "CC0";
            case "pdm" -> "Public Domain";
            case "by" -> "CC BY";
            case "by-sa" -> "CC BY-SA";
            case "by-nc" -> "CC BY-NC";
            case "by-nc-sa" -> "CC BY-NC-SA";
            case "by-nd" -> "CC BY-ND";
            case "by-nc-nd" -> "CC BY-NC-ND";
            default -> license;
        };
    }

    private static String tags(JsonNode tags) {
        if (tags == null || !tags.isArray()) return "";
        List<String> values = new ArrayList<>();
        for (JsonNode tag : tags) {
            String value = tag.isTextual() ? tag.asText() : tag.path("name").asText("");
            if (!value.isBlank() && !values.contains(value)) values.add(value);
            if (values.size() >= 12) break;
        }
        return String.join(", ", values);
    }
}
