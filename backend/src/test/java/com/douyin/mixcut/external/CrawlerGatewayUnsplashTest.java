package com.douyin.mixcut.external;

import com.douyin.mixcut.config.AppProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrawlerGatewayUnsplashTest {

    private final CrawlerGateway gateway = new CrawlerGateway(new AppProps(), new ProcRunner());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingKeyReturnsActionableImageNotice() {
        List<CrawlerGateway.RemoteItem> rows = gateway.searchImage("unsplash", "产品背景", 5);

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).isNotice());
        assertEquals("image", rows.get(0).getType());
        assertEquals("APP_UNSPLASH_API_KEY", rows.get(0).getConfigKey());
        assertEquals("https://unsplash.com/developers", rows.get(0).getAuthUrl());
    }

    @Test
    void searchUrlUsesOfficialEndpointWithoutCredential() {
        String url = gateway.unsplashSearchUrl("产品 背景", 200);

        assertTrue(url.startsWith("https://api.unsplash.com/search/photos?"));
        assertTrue(url.contains("per_page=30"));
        assertTrue(url.contains("query=%E4%BA%A7%E5%93%81"));
        assertFalse(url.contains("key="));
        assertFalse(url.contains("client_id"));
    }

    @Test
    void mapsImageWithPreviewPageAndLicenseMetadata() throws Exception {
        JsonNode photo = mapper.readTree("""
                {"alt_description":"product background","description":null,
                 "urls":{"full":"https://images.unsplash.com/full.jpg","small":"https://images.unsplash.com/small.jpg"},
                 "links":{"html":"https://unsplash.com/photos/example"},
                 "user":{"name":"Creator"}}
                """);

        CrawlerGateway.RemoteItem item = CrawlerGateway.mapUnsplashImage(photo);

        assertNotNull(item);
        assertEquals("unsplash", item.getSource());
        assertEquals("image", item.getType());
        assertEquals("https://images.unsplash.com/full.jpg", item.getDownloadUrl());
        assertEquals("https://images.unsplash.com/small.jpg", item.getPreviewUrl());
        assertEquals("https://unsplash.com/photos/example", item.getPageUrl());
        assertTrue(item.getLicense().contains("Unsplash"));
    }
}
