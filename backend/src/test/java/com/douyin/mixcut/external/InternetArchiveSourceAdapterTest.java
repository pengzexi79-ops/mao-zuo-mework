package com.douyin.mixcut.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternetArchiveSourceAdapterTest {
    private final InternetArchiveSourceAdapter adapter = new InternetArchiveSourceAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expandsSearchIdentifiersIntoLicensedMediaFiles() throws Exception {
        String search = "{\"response\":{\"docs\":[{\"identifier\":\"demo-id\",\"title\":\"Demo\",\"licenseurl\":\"https://creativecommons.org/publicdomain/zero/1.0/\"},{\"identifier\":\"real-id\",\"title\":\"Real\",\"licenseurl\":\"https://creativecommons.org/licenses/by/4.0/\"}]}}";
        String metadata = "{\"metadata\":{\"identifier\":\"real-id\"},\"files\":[{\"name\":\"notes.txt\"},{\"name\":\"clip.mp4\",\"length\":\"2.5\"}]}";
        List<String> calls = new java.util.ArrayList<>();
        List<CrawlerGateway.RemoteItem> items = adapter.search("test scene", "video", 5, url -> {
            calls.add(url);
            if (url.contains("advancedsearch")) return parse(search);
            return url.contains("real-id") ? parse(metadata) : parse("{\"files\":[]}");
        });
        assertEquals(1, items.size());
        assertEquals("archive", items.get(0).getSource());
        assertEquals("CC BY 4.0", items.get(0).getLicense());
        assertTrue(items.get(0).getDownloadUrl().contains("real-id"));
        assertEquals(3, calls.size());
    }

    @Test
    void queryUsesCorrectMediaTypeAndSafeLimits() {
        String audio = adapter.query("red flower", "audio", 99);
        String video = adapter.query("red flower", "video", 0);
        assertTrue(audio.contains("mediatype%3Aaudio"));
        assertTrue(video.contains("mediatype%3Amovies"));
        assertTrue(audio.contains("rows=10"));
        assertTrue(video.contains("rows=1"));
    }

    @Test
    void licenseAndPlaceholderRulesAreExplicit() {
        assertTrue(InternetArchiveSourceAdapter.isWhitelistedLicense("https://creativecommons.org/publicdomain/zero/1.0/"));
        assertTrue(InternetArchiveSourceAdapter.isWhitelistedLicense("https://creativecommons.org/licenses/by/4.0/"));
        assertFalse(InternetArchiveSourceAdapter.isWhitelistedLicense("https://creativecommons.org/licenses/by-sa/4.0/"));
        assertEquals("CC BY 4.0", InternetArchiveSourceAdapter.licenseLabel("https://creativecommons.org/licenses/by/4.0/"));
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String value) {
        try { return mapper.readTree(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final class JsonNodeFactory {
        JsonNodeFactory(ObjectMapper ignored) {}
    }
}
