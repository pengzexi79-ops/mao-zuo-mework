package com.douyin.mixcut.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikimediaSourceAdapterTest {
    private final WikimediaSourceAdapter adapter = new WikimediaSourceAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsOnlyLicensedNonPlaceholderItems() throws Exception {
        String json = """
                {"query":{"pages":[
                  {"title":"File:Good flower scene.mp4","imageinfo":[{"url":"https://upload.wikimedia.org/good.mp4","extmetadata":{"LicenseShortName":{"value":"CC BY 4.0"},"LicenseUrl":{"value":"https://creativecommons.org/licenses/by/4.0/"},"Artist":{"value":"<b>Alice</b>"}}}]},
                  {"title":"File:ShareAlike.mp4","imageinfo":[{"url":"https://upload.wikimedia.org/sa.mp4","extmetadata":{"LicenseShortName":{"value":"CC BY-SA 4.0"}}}]},
                  {"title":"File:Useful.mp4","imageinfo":[{"url":"https://upload.wikimedia.org/useful.mp4","extmetadata":{"LicenseShortName":{"value":"CC0 1.0"}}}]}
                ]}}
                """;
        List<CrawlerGateway.RemoteItem> items = adapter.map(mapper.readTree(json), "video", 10);
        assertEquals(2, items.size());
        assertEquals("wikimedia", items.get(0).getSource());
        assertEquals("CC BY 4.0", items.get(0).getLicense());
        assertTrue(items.get(0).getTags().contains("Alice"));
        assertEquals("Useful.mp4", items.get(1).getTitle());
    }

    @Test
    void buildsEncodedCommonsQueryWithoutDoubleEncoding() {
        String query = adapter.query("red flower", "video", 5);
        assertTrue(query.contains("iiprop=url%7Cextmetadata"));
        assertTrue(query.contains("red+flower+filetype%3Avideo"));
        assertFalse(query.contains("%257C"));
    }

    @Test
    void rejectsUnsupportedTypeAndNonWhitelistedLicenses() throws Exception {
        assertFalse(adapter.supports("image"));
        assertTrue(WikimediaSourceAdapter.isWhitelistedLicense("Public Domain"));
        assertTrue(WikimediaSourceAdapter.isWhitelistedLicense("CC BY 4.0"));
        assertFalse(WikimediaSourceAdapter.isWhitelistedLicense("CC BY-SA 4.0"));
        assertFalse(WikimediaSourceAdapter.isWhitelistedLicense(""));
    }
}
