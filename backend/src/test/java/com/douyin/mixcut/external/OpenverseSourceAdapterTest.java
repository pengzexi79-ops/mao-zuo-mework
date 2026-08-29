package com.douyin.mixcut.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenverseSourceAdapterTest {
    private final OpenverseSourceAdapter adapter = new OpenverseSourceAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsAudioResultsAndKeepsLicenseMetadataForManualReview() throws Exception {
        JsonNode response = mapper.readTree("""
                {
                  "results": [
                    {"title":"Share Alike","url":"https://cdn.example/share-alike.mp3","license":"by-sa","license_url":"https://creativecommons.org/licenses/by-sa/4.0/"},
                    {"title":"Rain","url":"https://cdn.example/rain.mp3","license":"cc0","license_url":"https://creativecommons.org/publicdomain/zero/1.0/","foreign_landing_url":"https://source.example/rain","duration":12.5,"creator":"A. Creator","tags":["rain",{"name":"nature"}]},
                    {"title":"Attribution","url":"https://cdn.example/attribution.ogg","license":"by","license_url":"https://creativecommons.org/licenses/by/4.0/"}
                  ]
                }
                """);

        List<CrawlerGateway.RemoteItem> items = adapter.map(response, "audio", 10);

        assertEquals(3, items.size(), "人工检索应保留结果，由自动补齐策略决定是否入队");
        CrawlerGateway.RemoteItem rain = items.stream()
                .filter(item -> "Rain".equals(item.getTitle()))
                .findFirst().orElseThrow();
        assertEquals("openverse", rain.getSource());
        assertEquals("audio", rain.getType());
        assertEquals("Rain", rain.getTitle());
        assertEquals("https://cdn.example/rain.mp3", rain.getDownloadUrl());
        assertEquals("https://source.example/rain", rain.getPageUrl());
        assertEquals(12.5, rain.getDuration());
        assertEquals("CC0", rain.getLicense());
        assertTrue(rain.getTags().contains("rain"));
        assertTrue(rain.getTags().contains("nature"));
        assertEquals("CC BY-SA", items.stream()
                .filter(item -> "Share Alike".equals(item.getTitle()))
                .findFirst().orElseThrow().getLicense());
    }

    @Test
    void queryIsBoundedAndSearchUsesOfficialAudioEndpoint() throws Exception {
        assertTrue(adapter.supports("audio"));
        assertTrue(adapter.supports("image"));
        assertFalse(adapter.supports("video"));
        assertTrue(adapter.query("轻快 音乐", "audio", 99).contains("page_size=20"));
        assertTrue(adapter.query("轻快 音乐", "audio", 99).contains("license_type=commercial"));
        assertTrue(adapter.query("轻快 音乐", "audio", 99).contains("extension=mp3,ogg,wav,m4a"));

        List<String> requested = new java.util.ArrayList<>();
        adapter.search("rain", "audio", 3, url -> {
            requested.add(url);
            return parse("{\"results\":[]}");
        });

        assertEquals(1, requested.size());
        assertTrue(requested.get(0).startsWith("https://api.openverse.org/v1/audio/?"));
    }

    @Test
    void mapsImageResultAndUsesOfficialImageEndpoint() throws Exception {
        JsonNode response = mapper.readTree("""
                {"results":[
                  {"title":"Product background","url":"https://cdn.example/product.jpg","thumbnail":"https://cdn.example/product-thumb.jpg","license":"by","license_url":"https://creativecommons.org/licenses/by/4.0/","foreign_landing_url":"https://source.example/product","tags":["product"]},
                  {"title":"Non commercial","url":"https://cdn.example/restricted.jpg","license":"by-nc"}
                ]}
                """);
        List<String> requested = new java.util.ArrayList<>();
        List<CrawlerGateway.RemoteItem> items = adapter.search("product", "image", 3, url -> {
            requested.add(url);
            return response;
        });

        assertEquals(2, items.size(), "人工检索应展示受限许可，供用户核验后决定是否使用");
        assertEquals("image", items.get(0).getType());
        assertEquals("https://cdn.example/product.jpg", items.get(0).getDownloadUrl());
        assertEquals("https://cdn.example/product-thumb.jpg", items.get(0).getPreviewUrl());
        assertTrue(requested.get(0).startsWith("https://api.openverse.org/v1/images/?"));
        assertTrue(requested.get(0).contains("extension=jpg,jpeg,png,webp"));
    }

    @Test
    void turnsMissingResponseIntoAnActionableFailureInsteadOfFalseNoResults() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.search("rain", "audio", 3, url -> null));
        assertTrue(error.getMessage().contains("网络不可达"));
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new AssertionError("测试响应 JSON 无效", e);
        }
    }
}
